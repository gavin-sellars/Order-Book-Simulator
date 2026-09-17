package obs.sim;

import obs.core.Book;
import obs.core.OrderBook;
import obs.core.OrderResult;
import obs.core.Side;
import obs.core.TradeListener;
import obs.feed.BookBuilder;
import obs.feed.FlowConfig;
import obs.feed.MessageHandler;
import obs.feed.itch.ItchLayout;
import obs.feed.itch.ItchReader;
import obs.metrics.FillStats;
import obs.metrics.PnlTracker;
import obs.strategy.Strategy;
import obs.strategy.StrategyContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runs a {@link Strategy} against a replayed ITCH session, with latency (Part 6 of the guide).
 *
 * The feed drives time: before each message the {@link SimClock} fires everything scheduled
 * earlier (market data reaching the strategy, strategy orders reaching the exchange, fills
 * reaching the strategy). The message then goes to {@link SimulatedOrders}, which decides whether
 * it fills a strategy order, and then to the book. Every change to the top of the book and every
 * trade is published to the strategy after the market-data delay; the strategy's orders and
 * cancels reach the exchange after the order-entry delay, and fills and acknowledgements come back
 * after the same delay.
 *
 * P&L is booked when a fill happens at the exchange, and marked at the mid price. The simulation
 * assumes no market impact; see {@link SimulatedOrders}. A Simulation runs once.
 */
public final class Simulation implements MessageHandler, StrategyContext {

    /**
     * @param makerRebatePerShare rebate for resting fills, in price units per share (for example 20 = $0.0020)
     * @param takerFeePerShare    fee for aggressive fills, in price units per share
     * @param sampleIntervalNanos how often to record P&L and position
     */
    public record Config(LatencyModel latency, long makerRebatePerShare, long takerFeePerShare, long sampleIntervalNanos) {
        public Config {
            Objects.requireNonNull(latency, "latency");
            if (makerRebatePerShare < 0 || takerFeePerShare < 0) throw new IllegalArgumentException("fees must be non-negative");
            if (sampleIntervalNanos <= 0) throw new IllegalArgumentException("sampleIntervalNanos must be positive");
        }
    }

    public record Sample(long time, long markToMarket, long position) {}

    public record Result(
            long markToMarket,
            long cash,
            long position,
            long maxAbsPosition,
            long netFees,
            long volume,
            long fills,
            long ordersSent,
            long ordersWithFills,
            long cancelsSent,
            long rejectedOrders,
            double fillRate,
            double shareFillRate,
            List<Sample> samples) {}

    private static final long FLUSH_NANOS = 60 * FlowConfig.NANOS_PER_SECOND;

    private final OrderBook book;
    private final BookBuilder builder;
    private final SimulatedOrders orders;
    private final SimClock clock = new SimClock(0);
    private final LatencyModel latency;
    private final Config config;
    private final Strategy strategy;
    private final PnlTracker pnl;
    private final FillStats stats = new FillStats();
    private final List<Sample> samples = new ArrayList<>();

    private boolean started;
    private boolean marketOpen;
    private long lastBid = Book.NO_BID;
    private long lastAsk = Book.NO_ASK;
    private long lastBidQty;
    private long lastAskQty;
    private long lastMid;
    private long nextClientId = 1;
    private long maxAbsPosition;
    private long rejectedOrders;

    /** A simulation whose book holds up to 65,536 resting orders. */
    public Simulation(long minPrice, long tickSize, int levels, Strategy strategy, Config config) {
        this(minPrice, tickSize, levels, 1 << 16, strategy, config);
    }

    /** @param poolCapacity most historical orders resting at once; the book fails loudly beyond it */
    public Simulation(long minPrice, long tickSize, int levels, int poolCapacity, Strategy strategy, Config config) {
        this(new OrderBook(minPrice, tickSize, levels, poolCapacity, TradeListener.NONE), strategy, config);
    }

    /**
     * A simulation on a book built elsewhere, such as one sized for a real stock with far levels
     * ({@link obs.feed.StockProfile#newBook}). The book must be empty, and its listener is not used.
     */
    public Simulation(OrderBook book, Strategy strategy, Config config) {
        if (book.orderCount() != 0) throw new IllegalArgumentException("the book must start empty");
        this.book = book;
        this.builder = new BookBuilder(book);
        this.orders = new SimulatedOrders(book, this::exchangeFill);
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        this.config = config;
        this.latency = config.latency();
        this.pnl = new PnlTracker(config.makerRebatePerShare(), config.takerFeePerShare());
    }

    /** A simulation whose price ladder covers the prices a synthetic session can use. */
    public static Simulation forFlow(FlowConfig flow, Strategy strategy, Config config) {
        return new Simulation(flow.minPrice(), flow.tickSize(), flow.ladderLevels(), flow.poolCapacity(), strategy, config);
    }

    public Result run(ItchReader reader, String ticker) {
        if (started) throw new IllegalStateException("a Simulation can only run once");
        started = true;

        strategy.init(this);
        reader.replay(ticker, this);
        clock.runThrough(clock.now() + FLUSH_NANOS);         // deliver anything still in flight

        return new Result(pnl.markToMarket(markPrice()), pnl.cash(), pnl.position(), maxAbsPosition, pnl.netFees(),
                pnl.volume(), pnl.fills(), stats.ordersSent(), stats.ordersWithFills(), stats.cancelsSent(), rejectedOrders,
                stats.fillRate(), stats.shareFillRate(), List.copyOf(samples));
    }

    // ---------------------------------------------------------------- the feed

    @Override
    public void onSystemEvent(long timestamp, byte eventCode) {
        clock.advanceTo(timestamp);
        if (eventCode == ItchLayout.START_OF_MARKET_HOURS && !marketOpen) {
            marketOpen = true;
            scheduleSample(timestamp);
        } else if (eventCode == ItchLayout.END_OF_MARKET_HOURS && marketOpen) {
            marketOpen = false;
            recordSample(timestamp);
            clock.schedule(latency.marketDataDelivery(timestamp), strategy::onSessionEnd);
        }
    }

    @Override
    public void onAdd(long timestamp, long orderRef, byte side, int shares, long price) {
        clock.advanceTo(timestamp);
        orders.beforeAdd(orderRef, side, shares, price);
        builder.onAdd(timestamp, orderRef, side, shares, price);
        publishTopOfBook(timestamp);
    }

    @Override
    public void onExecute(long timestamp, long orderRef, int shares, long matchNumber, long price) {
        clock.advanceTo(timestamp);
        byte restingSide = book.sideOf(orderRef);
        long tradePrice = book.priceOf(orderRef);
        orders.beforeExecute(orderRef, shares);
        builder.onExecute(timestamp, orderRef, shares, matchNumber, price);
        if (restingSide >= 0 && marketOpen) {
            byte aggressorSide = Side.opposite(restingSide);
            clock.schedule(latency.marketDataDelivery(timestamp), now -> strategy.onTrade(now, tradePrice, shares, aggressorSide));
        }
        publishTopOfBook(timestamp);
    }

    @Override
    public void onCancel(long timestamp, long orderRef, int shares) {
        clock.advanceTo(timestamp);
        orders.beforeCancel(orderRef, shares);
        builder.onCancel(timestamp, orderRef, shares);
        publishTopOfBook(timestamp);
    }

    @Override
    public void onDelete(long timestamp, long orderRef) {
        clock.advanceTo(timestamp);
        orders.beforeDelete(orderRef);
        builder.onDelete(timestamp, orderRef);
        publishTopOfBook(timestamp);
    }

    @Override
    public void onReplace(long timestamp, long oldRef, long newRef, int shares, long price) {
        clock.advanceTo(timestamp);
        orders.beforeReplace(oldRef);
        builder.onReplace(timestamp, oldRef, newRef, shares, price);
        publishTopOfBook(timestamp);
    }

    private void publishTopOfBook(long timestamp) {
        long bid = book.bestBid();
        long ask = book.bestAsk();
        long bidQty = bid == Book.NO_BID ? 0 : book.bidQtyAt(bid);
        long askQty = ask == Book.NO_ASK ? 0 : book.askQtyAt(ask);
        if (bid == lastBid && ask == lastAsk && bidQty == lastBidQty && askQty == lastAskQty) return;

        lastBid = bid;
        lastAsk = ask;
        lastBidQty = bidQty;
        lastAskQty = askQty;
        if (bid != Book.NO_BID && ask != Book.NO_ASK) lastMid = (bid + ask) / 2;
        if (!marketOpen) return;
        clock.schedule(latency.marketDataDelivery(timestamp), now -> strategy.onBookUpdate(now, bid, ask, bidQty, askQty));
    }

    // ---------------------------------------------------------------- the strategy's side

    @Override
    public long now() {
        return clock.now();
    }

    @Override
    public long tickSize() {
        return book.tickSize();
    }

    @Override
    public long sendLimit(byte side, long price, int qty) {
        long clientId = nextClientId++;
        stats.onOrderSent(qty);
        clock.schedule(latency.orderArrival(clock.now()), now -> orderArrives(clientId, side, price, qty));
        return clientId;
    }

    @Override
    public void cancel(long clientId) {
        stats.onCancelSent();
        clock.schedule(latency.orderArrival(clock.now()), now -> cancelArrives(clientId));
    }

    private void orderArrives(long clientId, byte side, long price, int qty) {
        int result = marketOpen ? orders.submit(clientId, side, price, qty) : OrderResult.REJECTED_MARKET_CLOSED;
        if (result != OrderResult.ACCEPTED) {
            rejectedOrders++;
            clock.schedule(latency.responseDelivery(clock.now()), now -> strategy.onOrderRejected(now, clientId, result));
        }
    }

    private void cancelArrives(long clientId) {
        int cancelled = orders.cancel(clientId);
        clock.schedule(latency.responseDelivery(clock.now()), now -> strategy.onOwnCancelAck(now, clientId, cancelled));
    }

    private void exchangeFill(long clientId, byte side, long price, int qty, boolean passive, int remaining) {
        pnl.onFill(side, price, qty, passive);
        stats.onFill(clientId, qty, remaining);
        maxAbsPosition = Math.max(maxAbsPosition, Math.abs(pnl.position()));
        clock.schedule(latency.responseDelivery(clock.now()),
                now -> strategy.onOwnFill(now, clientId, side, price, qty, remaining));
    }

    // ---------------------------------------------------------------- P&L samples

    private void scheduleSample(long time) {
        clock.schedule(time, now -> {
            if (!marketOpen) return;
            recordSample(now);
            scheduleSample(now + config.sampleIntervalNanos());
        });
    }

    private void recordSample(long time) {
        samples.add(new Sample(time, pnl.markToMarket(markPrice()), pnl.position()));
    }

    /** The mid price, or the last mid seen while the book was two-sided. */
    private long markPrice() {
        return lastMid;
    }
}
