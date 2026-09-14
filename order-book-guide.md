# Building a Low-Latency Order Book Simulator in Java

**A complete guide for someone who knows basic Java and nothing about markets.**

---

## How to read this

This guide has nine parts. Read them in order. Parts 1–2 explain what you're building and why the obvious approach is slow. Parts 3–6 build the real system. Parts 7–8 prove it works. Part 9 is a build schedule.

You should write code as you go. Part 1 has a complete working version you can finish in an evening. Everything after that is making it fast and making it a simulator instead of just a data structure.

Do not skip Part 1 and jump to the fast version. You will not understand what the fast version is doing, and more importantly you won't be able to explain in an interview why it's faster, which is the entire point of the project.

---

# Part 0: What is an order book?

## Buying and selling on an exchange

An exchange is a place where people trade shares of a stock. Two kinds of instruction can arrive:

**Limit order**: "Buy 100 shares of AAPL, but pay no more than $150.00." This is a price ceiling. If nobody will sell at $150.00 or below, your order doesn't trade. It sits and waits.

**Market order**: "Buy 100 shares of AAPL at whatever the cheapest available price is." This trades immediately against whatever is available.

Nearly all resting liquidity comes from limit orders. Market orders consume it.

## The book itself

The **order book** is the collection of all limit orders currently waiting. It has two sides:

- **Bids**: people who want to buy. Higher price = more aggressive = better for a seller.
- **Asks** (or offers): people who want to sell. Lower price = more aggressive = better for a buyer.

A snapshot might look like this:

```
        ASKS (sellers)
  $150.03   900 shares
  $150.02   400 shares
  $150.01   200 shares   <-- best ask (lowest sell price)
  --------------------   <-- the spread
  $150.00   500 shares   <-- best bid (highest buy price)
  $149.99  1200 shares
  $149.98   700 shares
        BIDS (buyers)
```

The **best bid** is the highest price anyone will pay. The **best ask** is the lowest price anyone will sell at. The gap between them is the **spread**. The best bid and best ask together are called **the touch** or **the inside**.

Here is the single most important structural fact: **the best bid is always strictly below the best ask.** If someone submits a buy order at $150.01 when the best ask is $150.01, those two orders trade immediately and the order doesn't rest in the book. A book where bid ≥ ask is called **crossed**, and it means your code has a bug. You will use this as an assertion later.

## Price-time priority

Multiple orders can sit at the same price. When a trade happens at that price, who gets filled first?

The rule at most equity exchanges is **price-time priority**:

1. Better price wins. A bid at $150.00 is ahead of a bid at $149.99, always.
2. At the same price, earlier arrival wins. First in, first out.

This is why each price level is a **queue**, not just a total quantity. If you join the bid at $150.00 and there are already 500 shares there, you are behind 500 shares. When a seller comes and sells 300 shares, those 300 go to the orders ahead of you. You get nothing. This matters enormously later, in Part 6.

## Matching

When an order arrives that would cross the book, the exchange matches it:

```
Book:  best ask is $150.01 with 200 shares

Incoming: BUY 500 shares, limit $150.03

Step 1: $150.01 <= $150.03, so it crosses. Take all 200 shares.
        Trade: 200 @ $150.01. 300 shares remain to fill.
Step 2: New best ask is $150.02 with 400 shares.
        $150.02 <= $150.03, crosses. Take 300 of the 400.
        Trade: 300 @ $150.02. 0 shares remain.
Step 3: Order fully filled. Nothing rests.
        Book now shows $150.02 with 100 shares as best ask.
```

Note that the trades happen at the **resting order's price**, not the incoming order's price. The incoming buyer was willing to pay $150.03 but got filled at $150.01 and $150.02. This is called **price improvement** and it's standard.

If the incoming order had been for 5000 shares, it would have eaten through all the asks at or below $150.03, and the unfilled remainder would rest in the book as a new best bid at $150.03.

## The message stream

An exchange broadcasts a stream of messages describing every change to the book. The four that matter:

| Message | Meaning |
|---|---|
| **Add** | A new limit order joined the book. Has an ID, side, price, quantity. |
| **Cancel** | An order was reduced or removed. References the ID. |
| **Execute** | An order traded, fully or partially. References the ID. |
| **Replace** | Cancel one order and add another, atomically. |

Your simulator consumes this stream and maintains the book state. That's the core loop.

## Depth levels: L1, L2, L3

You'll hear these terms. They describe how much detail a data feed gives you.

- **L1**: just the best bid and best ask, with sizes. Cheapest, least useful.
- **L2**: every price level with total quantity at each. You can see the shape of the book but not individual orders.
- **L3**: every individual order, with its own ID. You can see exactly how many orders are at each price and in what sequence.

You want **L3**, because queue position (Part 6) is impossible to model accurately without it. Nasdaq's ITCH feed is L3 and public sample files exist.

---

# Part 1: Build the slow, correct version first

Before optimizing anything, build a version that works. You will use this as the reference implementation to test the fast one against. Do not throw it away.

## Representing a price

**Never use `double` or `float` for prices.** This is not a performance concern, it's a correctness concern. Floating point cannot represent $0.01 exactly, in the same way decimal cannot represent 1/3 exactly. Add a penny to itself a hundred times in `double` and you don't get exactly $1.00. In a system that compares prices for equality millions of times a second, this will produce wrong answers.

Use a `long` holding the price in the smallest unit. If the tick size (minimum price increment) is $0.01, then:

```java
// $150.01 is stored as 15001
long price = 15001;
```

Nasdaq ITCH uses four decimal places, so $150.01 arrives as 1500100. Pick one convention and stick to it throughout. Convert to a display string only at the edge of the system, never in the core.

## The reference implementation

```java
package obs.ref;

import java.util.*;

public class RefOrderBook {

    public static final byte BUY = 0;
    public static final byte SELL = 1;

    public static class Order {
        public final long id;
        public final byte side;
        public final long price;
        public int quantity;      // remaining, not original

        public Order(long id, byte side, long price, int quantity) {
            this.id = id;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
        }
    }

    // Bids sorted descending: best (highest) bid is firstKey().
    private final TreeMap<Long, ArrayDeque<Order>> bids =
            new TreeMap<>(Comparator.reverseOrder());

    // Asks sorted ascending: best (lowest) ask is firstKey().
    private final TreeMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();

    // So cancels can find an order by ID without scanning.
    private final HashMap<Long, Order> byId = new HashMap<>();

    private final List<Trade> trades = new ArrayList<>();

    public record Trade(long aggressorId, long restingId, long price, int qty) {}

    /** Submit a limit order. Matches what it can, rests the remainder. */
    public void addLimitOrder(long id, byte side, long price, int quantity) {
        Order incoming = new Order(id, side, price, quantity);

        TreeMap<Long, ArrayDeque<Order>> opposite = (side == BUY) ? asks : bids;

        // Match against the opposite side while prices cross.
        while (incoming.quantity > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> bestEntry = opposite.firstEntry();
            long bestPrice = bestEntry.getKey();

            boolean crosses = (side == BUY) ? (bestPrice <= price)
                                            : (bestPrice >= price);
            if (!crosses) break;

            ArrayDeque<Order> queue = bestEntry.getValue();

            while (incoming.quantity > 0 && !queue.isEmpty()) {
                Order resting = queue.peek();          // FIFO: oldest first
                int fill = Math.min(incoming.quantity, resting.quantity);

                incoming.quantity -= fill;
                resting.quantity  -= fill;
                trades.add(new Trade(id, resting.id, bestPrice, fill));

                if (resting.quantity == 0) {
                    queue.poll();
                    byId.remove(resting.id);
                }
            }

            if (queue.isEmpty()) {
                opposite.pollFirstEntry();   // price level is gone
            }
        }

        // Whatever didn't fill rests in the book.
        if (incoming.quantity > 0) {
            TreeMap<Long, ArrayDeque<Order>> own = (side == BUY) ? bids : asks;
            own.computeIfAbsent(price, k -> new ArrayDeque<>()).add(incoming);
            byId.put(id, incoming);
        }
    }

    /** Remove an order entirely. */
    public void cancel(long id) {
        Order o = byId.remove(id);
        if (o == null) return;                  // already gone; not an error

        TreeMap<Long, ArrayDeque<Order>> side = (o.side == BUY) ? bids : asks;
        ArrayDeque<Order> queue = side.get(o.price);
        if (queue != null) {
            queue.remove(o);                    // O(n) scan -- see below
            if (queue.isEmpty()) side.remove(o.price);
        }
    }

    /** Reduce an order's size without losing queue position. */
    public void reduce(long id, int by) {
        Order o = byId.get(id);
        if (o == null) return;
        o.quantity -= by;
        if (o.quantity <= 0) cancel(id);
    }

    public Long bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public Long bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

    public List<Trade> drainTrades() {
        List<Trade> out = new ArrayList<>(trades);
        trades.clear();
        return out;
    }
}
```

That's a functioning order book. Write a small `main` that adds a few orders and prints the book, and confirm it behaves like the examples in Part 0.

## What's wrong with it

Everything in this class is a reasonable choice for ordinary software. Every one of them is wrong for this problem. Understanding exactly why is the substance of the project.

**`queue.remove(o)` is O(n).** `ArrayDeque.remove(Object)` scans the whole deque comparing references. A price level in a liquid stock can hold hundreds of orders. Cancels are the *most common* message type in real markets, typically well over 90% of all order activity. So your most frequent operation is your slowest one.

**`TreeMap` allocates a node per insert.** Every new price level creates a `TreeMap.Entry` object. Every `ArrayDeque` creates a backing array. This garbage accumulates and the garbage collector eventually has to stop your program to clean it up. More on this in Part 2.

**`TreeMap` lookups chase pointers.** A red-black tree lookup follows references from node to node. Each node is at an arbitrary heap address. Each hop is likely a cache miss (Part 2 explains why that costs ~100ns).

**`HashMap<Long, Order>` boxes every key.** `Long` is an object. Every `byId.put(id, o)` creates a `Long` object wrapping the primitive. Every lookup either allocates one or hits the small integer cache. Then the map stores a `Node` object holding references to the `Long` and the `Order`, so a lookup is: hash the `Long`, find the bucket array slot, follow a reference to the `Node`, follow a reference to the `Long` to compare, follow a reference to the `Order`. Four potential cache misses to do one lookup.

**`ArrayList<Trade>` with a `record` allocates a `Trade` object per fill**, in the hottest possible path.

Every one of these is fixed in Part 3. First you need to understand the machine well enough to know why they're problems.

---

# Part 2: Why the machine is slow

You cannot make code fast without knowing what the hardware is doing. This part has no code. Read it anyway.

## A sense of scale

Rough costs on a modern server CPU:

| Operation | Time | Relative |
|---|---|---|
| One CPU instruction | ~0.3 ns | 1x |
| L1 cache read | ~1 ns | 3x |
| L2 cache read | ~4 ns | 13x |
| L3 cache read | ~15 ns | 50x |
| Main memory (RAM) read | ~80–100 ns | ~300x |
| Young-generation GC pause | 200 µs – 5 ms | 1,000,000x |

Read that last row again. A single garbage collection pause is a *million times* longer than an instruction. If your book processes a message in 500 nanoseconds, one 2-millisecond GC pause is equivalent to 4,000 lost messages.

This is the core insight of low-latency Java: **you are not optimizing instruction count, you are optimizing memory access patterns and avoiding the garbage collector.**

## Cache lines and locality

The CPU never reads one byte from RAM. It reads a **cache line**, 64 bytes, into cache. If you read `array[0]`, the CPU pulls `array[0]` through `array[15]` (for a 4-byte `int` array) into L1. Reading `array[1]` next is then nearly free.

This makes sequential access through an array dramatically faster than following references between objects scattered across the heap. Two consequences:

**Arrays of primitives beat collections of objects.** An `int[1000]` is 4000 contiguous bytes: 63 cache lines, fetched efficiently, and the hardware prefetcher will see the pattern and load them ahead of time. An `ArrayList<Integer>` of the same data is an array of 1000 references, each pointing to a separate `Integer` object somewhere else in memory. Walking it is 1000 potential cache misses.

**Pointer chasing defeats prefetching.** In a linked structure, the CPU can't predict the next address until it has loaded the current node. Each hop is a serialized, full-latency memory access. A tree lookup with 10 levels can cost 1000ns even though it's "only" ten comparisons.

## Struct of arrays vs array of structs

Say you have 100,000 orders, each with an ID, price, quantity, and side.

**Array of structs (AoS)**, the normal Java way:

```java
Order[] orders = new Order[100_000];   // array of references
```

This is 100,000 references pointing to 100,000 separate heap objects. Each `Order` has a 12–16 byte object header before any of your data. To read just the quantities of all orders, you touch 100,000 scattered locations and pull in a full cache line each time, most of which is header and fields you don't need.

**Struct of arrays (SoA)**:

```java
long[] orderIds   = new long[100_000];
long[] orderPrice = new long[100_000];
int[]  orderQty   = new int[100_000];
byte[] orderSide  = new byte[100_000];
```

An order is now an `int` index. Order 42 is `orderIds[42]`, `orderPrice[42]`, and so on. No object headers. Each array is contiguous. Reading all quantities touches only `orderQty`, which is 400KB of packed data instead of ~5MB scattered.

The cost is that your code is less readable. `orderQty[i]` instead of `order.quantity`. This is a real tradeoff and you should be able to say so.

> **Aside for the future:** Project Valhalla will eventually bring value types to Java, which will let you get AoS syntax with SoA layout. It isn't shipped yet. Don't claim it is.

## The garbage collector

Java's heap is split into a **young generation** (where new objects go) and an **old generation** (where objects that survive long enough get promoted). When the young gen fills, a **minor GC** runs: it finds which young objects are still reachable, copies them elsewhere, and wipes the rest.

To do this safely it must **stop the world**. Every application thread is paused. The pause length depends on how many objects survived, not on how much garbage there is.

For most software this is invisible. For a latency-sensitive system, a stop-the-world pause is the single largest source of tail latency. Your median might be 500ns and your 99.99th percentile 3 milliseconds, and the entire gap is GC.

The fix is not to tune the collector. The fix is **to allocate nothing on the hot path**. If you never create garbage during message processing, the young gen never fills, and minor GC never runs. You allocate everything up front during startup and reuse it forever.

This is the discipline behind object pooling (Part 3). It's why you preallocate the price ladder. It's why trades are written into a preallocated buffer instead of being `new Trade(...)`.

"Zero allocation on the hot path" is a claim you should be able to prove, and Part 7 shows how (JMH's `-prof gc` reports bytes allocated per operation; you want that to read 0).

## False sharing

Two variables that sit within the same 64-byte cache line are, as far as the hardware is concerned, one unit. If thread A writes variable X on core 1, and thread B writes variable Y on core 2, and X and Y share a cache line, then every write by A invalidates B's cached copy and vice versa. The cores ping the line back and forth over the interconnect.

Neither thread touches the other's data. They just happen to be neighbours in memory. This is **false sharing**, and it can cost an order of magnitude on an otherwise correct concurrent structure.

The fix is **padding**: deliberately wasting memory so hot, independently-written variables land on separate cache lines. Part 4 does this in the ring buffer.

## Volatile, and what it actually means

You'll need `volatile` in Part 4. Briefly:

Without `volatile`, a field written by one thread may never become visible to another. This is not theoretical. The JIT compiler is allowed to hoist a non-volatile field read out of a loop and read it once, so a thread spinning on a plain `boolean flag` can loop forever after another thread sets it.

`volatile` on a field means: every read goes to memory (or a coherent cache), every write is published, and the compiler can't reorder other memory operations across it. It is not a lock and it does not make compound operations like `i++` atomic. It's a visibility and ordering guarantee, and that's exactly what a single-producer single-consumer queue needs.

---

# Part 3: The fast core

Now rebuild the book with everything from Part 2 applied.

## Design 1: the price ladder

Replace `TreeMap<Long, Level>` with a **flat array indexed by price**.

```java
int index = (int) ((price - basePrice) / tickSize);
```

Lookup becomes one subtraction, one division (the JIT turns division by a constant into a multiply), and one array access. O(1), no pointer chasing, and neighbouring price levels are neighbours in memory, which matters because matching walks through adjacent levels.

**Sizing it.** For a stock trading around $150 with a $0.01 tick, a range of $50 to $250 is 20,000 ticks. That covers any plausible single-day move with enormous margin. You allocate the whole range once at startup.

**The objection you'll get, and the answer.** An interviewer will ask what happens for an instrument whose price range is unknown or enormous. Two answers:

1. **Recentering.** Treat the array as a window around the current price. When the touch approaches within some margin of an edge, shift the contents (or treat it as a circular buffer and move the origin). Cost is amortized near zero because it happens rarely.
2. **Hybrid.** Keep a dense array for a window around the touch, and spill far-from-touch levels into a `TreeMap`. Almost all activity is near the touch, so the slow path is rarely taken.

Say clearly that for a single equity you deliberately chose the simple fixed range because the price range is bounded and known, and that recentering is what you'd add for futures or crypto. Knowing when *not* to build the complicated thing is a signal in your favour.

**Storing the levels.** Don't make it `PriceLevel[]`, which reintroduces object-per-level and null checks. Use SoA:

```java
private final int[] levelHead;   // pool index of first (oldest) order, -1 if empty
private final int[] levelTail;   // pool index of last (newest) order, -1 if empty
private final long[] levelQty;   // total resting quantity at this level
private final int[] levelCount;  // number of orders at this level
```

Two sets of these, one per side. A level is empty exactly when `levelHead[i] == -1`.

**Tracking the touch.** Keep `bestBidIdx` and `bestAskIdx`. On an add that improves the touch, update in one comparison. On a removal that empties the best level, scan outward for the next non-empty one. In practice the next level is usually one or two steps away.

If you want to remove the worst case, keep a `long[]` bitset over the ladder with one bit per level and use `Long.numberOfTrailingZeros` to jump 64 levels at a time. That's a genuinely nice optimization to mention, and about 30 lines.

## Design 2: intrusive doubly-linked list

Each price level needs a FIFO queue with O(1) removal from the middle, because cancels target specific orders.

A standard `LinkedList<Order>` has two problems: each insert allocates a `Node` object, and finding the node for a given order requires a scan.

**Intrusive** means the links live inside the order record itself:

```java
int[] orderNext;   // pool index of the next order at this level, -1 if last
int[] orderPrev;   // pool index of the previous order, -1 if first
```

Given an order's pool index, unlinking it is:

```java
private void unlink(int idx, int[] head, int[] tail, int levelIdx) {
    int p = orderPrev[idx];
    int n = orderNext[idx];

    if (p != -1) orderNext[p] = n; else head[levelIdx] = n;
    if (n != -1) orderPrev[n] = p; else tail[levelIdx] = p;

    orderPrev[idx] = -1;
    orderNext[idx] = -1;
}
```

Constant time, no allocation, no scan. This is the fix for the O(n) `queue.remove(o)` in Part 1, and it's the single biggest win in the whole design because cancels dominate the message mix.

## Design 3: the order pool

Orders are created and destroyed constantly. Allocating one per add would generate garbage at exactly the rate you're trying to avoid.

Preallocate a fixed number of slots and hand out indices:

```java
package obs.core;

public final class OrderPool {

    // Struct-of-arrays storage. An "order" is an int index into these.
    public final long[] id;
    public final long[] price;
    public final int[]  qty;
    public final int[]  levelIdx;
    public final byte[] side;
    public final int[]  next;      // intrusive list forward link
    public final int[]  prev;      // intrusive list backward link

    private final int capacity;
    private int freeHead;          // head of the free list
    private int liveCount;

    public OrderPool(int capacity) {
        this.capacity = capacity;
        id       = new long[capacity];
        price    = new long[capacity];
        qty      = new int[capacity];
        levelIdx = new int[capacity];
        side     = new byte[capacity];
        next     = new int[capacity];
        prev     = new int[capacity];

        // Thread every slot onto the free list, reusing next[] as the link.
        for (int i = 0; i < capacity - 1; i++) next[i] = i + 1;
        next[capacity - 1] = -1;
        freeHead = 0;
    }

    public int allocate() {
        int idx = freeHead;
        if (idx == -1) throw new IllegalStateException("order pool exhausted");
        freeHead = next[idx];
        next[idx] = -1;
        prev[idx] = -1;
        liveCount++;
        return idx;
    }

    public void release(int idx) {
        next[idx] = freeHead;
        prev[idx] = -1;
        freeHead = idx;
        liveCount--;
    }

    public int liveCount() { return liveCount; }
    public int capacity()  { return capacity; }
}
```

Note the free list reuses the `next` array. A slot is either a live order (where `next` is a list link) or free (where `next` is a free-list link). It's never both, so the memory does double duty. Zero allocation after construction.

**On exhaustion:** throwing is the right call for a simulator. Silently growing would mean a mid-run array copy, which is a multi-millisecond latency spike that corrupts your measurements. Size the pool generously (a few million slots costs tens of megabytes) and fail loudly if you're wrong.

## Design 4: the open-addressing long→int map

You need to map order IDs (given in the feed as `long`) to pool indices (`int`). `HashMap<Long, Integer>` boxes both. Write your own.

**Open addressing** means collisions are resolved by walking to the next slot in the same array, rather than by following a chain of node objects. One array, no per-entry objects, and collision resolution stays in cache because the next slot is usually the same cache line.

```java
package obs.mem;

/** Open-addressed long -> int map with linear probing. No boxing, no nodes. */
public final class LongIntMap {

    public static final int NOT_FOUND = -1;

    private final long[] keys;
    private final int[] values;      // NOT_FOUND marks an empty slot
    private final int mask;
    private final int maxSize;
    private int size;

    /** capacity is rounded up to a power of two; load factor is 0.5. */
    public LongIntMap(int expectedEntries) {
        int cap = Integer.highestOneBit(Math.max(16, expectedEntries * 2 - 1)) << 1;
        keys = new long[cap];
        values = new int[cap];
        java.util.Arrays.fill(values, NOT_FOUND);
        mask = cap - 1;
        maxSize = cap / 2;
    }

    /**
     * Mixes the bits of the key. Order IDs are often sequential, and sequential
     * keys with an identity hash cluster into one region of the table.
     * This is a finalizing mix borrowed from SplitMix64.
     */
    private int index(long key) {
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    public void put(long key, int value) {
        if (size >= maxSize) throw new IllegalStateException("map full");
        int i = index(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) { values[i] = value; return; }   // overwrite
            i = (i + 1) & mask;                                  // linear probe
        }
        keys[i] = key;
        values[i] = value;
        size++;
    }

    public int get(long key) {
        int i = index(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) return values[i];
            i = (i + 1) & mask;
        }
        return NOT_FOUND;
    }

    public int remove(long key) {
        int i = index(key);
        while (values[i] != NOT_FOUND) {
            if (keys[i] == key) {
                int v = values[i];
                removeAt(i);
                return v;
            }
            i = (i + 1) & mask;
        }
        return NOT_FOUND;
    }

    /**
     * Backward-shift deletion. Simply blanking a slot would break probe chains:
     * a later key that probed past this slot would become unreachable. Instead,
     * walk forward and pull back any entry whose ideal slot is at or before the
     * hole. Load factor 0.5 guarantees an empty slot exists, so this terminates.
     */
    private void removeAt(int i) {
        int j = i;
        while (true) {
            j = (j + 1) & mask;
            if (values[j] == NOT_FOUND) break;

            int k = index(keys[j]);
            if (cyclicallyWithin(i, k, j)) continue;   // correctly placed; leave it

            keys[i] = keys[j];
            values[i] = values[j];
            i = j;
        }
        values[i] = NOT_FOUND;
        keys[i] = 0L;
        size--;
    }

    /** True if k lies in the cyclic interval (i, j]. */
    private static boolean cyclicallyWithin(int i, int k, int j) {
        return (i <= j) ? (i < k && k <= j) : (i < k || k <= j);
    }

    public int size() { return size; }
}
```

Two things worth understanding here.

**`& mask` instead of `% capacity`.** If capacity is a power of two, `x & (capacity - 1)` gives the same result as `x % capacity` for non-negative `x`, and a bitwise AND is a single-cycle instruction while integer division is ~20–40 cycles. This is why the capacity is rounded to a power of two. The same trick appears in the ring buffer.

**Why deletion is not just "blank the slot."** Suppose keys A and B both hash to slot 5. A goes in slot 5, B probes and goes in slot 6. Now delete A by blanking slot 5. A later `get(B)` starts at slot 5, sees an empty slot, and concludes B isn't in the map. It is, in slot 6. Backward-shift deletion fixes this by repairing the chain. The alternative, tombstones, is simpler but degrades over time as tombstones accumulate.

## Putting the core together

```java
package obs.core;

import obs.mem.LongIntMap;

public final class OrderBook {

    public static final byte BUY = 0;
    public static final byte SELL = 1;
    private static final int EMPTY = -1;

    private final long basePrice;
    private final long tickSize;
    private final int levels;

    // Bid ladder
    private final int[]  bidHead;
    private final int[]  bidTail;
    private final long[] bidQty;
    private final int[]  bidCount;

    // Ask ladder
    private final int[]  askHead;
    private final int[]  askTail;
    private final long[] askQty;
    private final int[]  askCount;

    private int bestBidIdx = EMPTY;
    private int bestAskIdx = EMPTY;

    private final OrderPool pool;
    private final LongIntMap idToSlot;
    private final TradeListener listener;

    public OrderBook(long basePrice, long tickSize, int levels,
                     int poolCapacity, TradeListener listener) {
        this.basePrice = basePrice;
        this.tickSize  = tickSize;
        this.levels    = levels;
        this.listener  = listener;

        bidHead = newFilled(levels); bidTail = newFilled(levels);
        askHead = newFilled(levels); askTail = newFilled(levels);
        bidQty = new long[levels];   askQty = new long[levels];
        bidCount = new int[levels];  askCount = new int[levels];

        pool = new OrderPool(poolCapacity);
        idToSlot = new LongIntMap(poolCapacity);
    }

    private static int[] newFilled(int n) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, EMPTY);
        return a;
    }

    private int toIndex(long price) {
        return (int) ((price - basePrice) / tickSize);
    }

    private long toPrice(int index) {
        return basePrice + (long) index * tickSize;
    }

    public void addLimitOrder(long orderId, byte side, long price, int quantity) {
        int remaining = match(orderId, side, price, quantity);
        if (remaining > 0) rest(orderId, side, price, remaining);
    }

    /** Cross against the opposite side. Returns unfilled quantity. */
    private int match(long aggressorId, byte side, long limitPrice, int qty) {
        int limitIdx = toIndex(limitPrice);
        boolean buying = (side == BUY);

        while (qty > 0) {
            int bestIdx = buying ? bestAskIdx : bestBidIdx;
            if (bestIdx == EMPTY) break;

            // A buyer crosses when the best ask is at or below their limit.
            boolean crosses = buying ? (bestIdx <= limitIdx) : (bestIdx >= limitIdx);
            if (!crosses) break;

            int[]  head  = buying ? askHead  : bidHead;
            long[] lvQty = buying ? askQty   : bidQty;
            int[]  count = buying ? askCount : bidCount;
            long   px    = toPrice(bestIdx);

            while (qty > 0 && head[bestIdx] != EMPTY) {
                int restingSlot = head[bestIdx];              // FIFO: oldest first
                int available = pool.qty[restingSlot];
                int fill = Math.min(qty, available);

                qty -= fill;
                pool.qty[restingSlot] -= fill;
                lvQty[bestIdx] -= fill;

                listener.onTrade(aggressorId, pool.id[restingSlot], px, fill);

                if (pool.qty[restingSlot] == 0) {
                    removeOrder(restingSlot);
                }
            }

            if (head[bestIdx] == EMPTY) advanceTouch(buying);
        }
        return qty;
    }

    private void rest(long orderId, byte side, long price, int quantity) {
        int idx = toIndex(price);
        int slot = pool.allocate();

        pool.id[slot] = orderId;
        pool.price[slot] = price;
        pool.qty[slot] = quantity;
        pool.side[slot] = side;
        pool.levelIdx[slot] = idx;

        int[] head = (side == BUY) ? bidHead : askHead;
        int[] tail = (side == BUY) ? bidTail : askTail;
        long[] lvQty = (side == BUY) ? bidQty : askQty;
        int[] count = (side == BUY) ? bidCount : askCount;

        // Append at the tail: newest order has the worst time priority.
        int t = tail[idx];
        pool.prev[slot] = t;
        pool.next[slot] = EMPTY;
        if (t == EMPTY) head[idx] = slot; else pool.next[t] = slot;
        tail[idx] = slot;

        lvQty[idx] += quantity;
        count[idx]++;
        idToSlot.put(orderId, slot);

        if (side == BUY) {
            if (bestBidIdx == EMPTY || idx > bestBidIdx) bestBidIdx = idx;
        } else {
            if (bestAskIdx == EMPTY || idx < bestAskIdx) bestAskIdx = idx;
        }
    }

    public void cancel(long orderId) {
        int slot = idToSlot.get(orderId);
        if (slot == LongIntMap.NOT_FOUND) return;
        boolean wasBuy = (pool.side[slot] == BUY);
        int idx = pool.levelIdx[slot];
        removeOrder(slot);
        int[] head = wasBuy ? bidHead : askHead;
        if (head[idx] == EMPTY && idx == (wasBuy ? bestBidIdx : bestAskIdx)) {
            advanceTouch(!wasBuy);
        }
    }

    /** Partial cancel. Keeps queue position, which matters -- see Part 6. */
    public void reduce(long orderId, int by) {
        int slot = idToSlot.get(orderId);
        if (slot == LongIntMap.NOT_FOUND) return;
        int newQty = pool.qty[slot] - by;
        if (newQty <= 0) { cancel(orderId); return; }
        pool.qty[slot] = newQty;
        long[] lvQty = (pool.side[slot] == BUY) ? bidQty : askQty;
        lvQty[pool.levelIdx[slot]] -= by;
    }

    private void removeOrder(int slot) {
        int idx = pool.levelIdx[slot];
        boolean buy = (pool.side[slot] == BUY);

        int[] head  = buy ? bidHead  : askHead;
        int[] tail  = buy ? bidTail  : askTail;
        long[] lvQty = buy ? bidQty  : askQty;
        int[] count = buy ? bidCount : askCount;

        int p = pool.prev[slot];
        int n = pool.next[slot];
        if (p != EMPTY) pool.next[p] = n; else head[idx] = n;
        if (n != EMPTY) pool.prev[n] = p; else tail[idx] = p;

        lvQty[idx] -= pool.qty[slot];
        count[idx]--;

        idToSlot.remove(pool.id[slot]);
        pool.release(slot);
    }

    /** Walk outward to find the next non-empty level on the given side. */
    private void advanceTouch(boolean askSide) {
        if (askSide) {
            int i = bestAskIdx;
            while (i < levels && askHead[i] == EMPTY) i++;
            bestAskIdx = (i < levels) ? i : EMPTY;
        } else {
            int i = bestBidIdx;
            while (i >= 0 && bidHead[i] == EMPTY) i--;
            bestBidIdx = (i >= 0) ? i : EMPTY;
        }
    }

    public long bestBid() { return bestBidIdx == EMPTY ? Long.MIN_VALUE : toPrice(bestBidIdx); }
    public long bestAsk() { return bestAskIdx == EMPTY ? Long.MAX_VALUE : toPrice(bestAskIdx); }
    public long bidQtyAt(long price) { return bidQty[toIndex(price)]; }
    public long askQtyAt(long price) { return askQty[toIndex(price)]; }

    public interface TradeListener {
        void onTrade(long aggressorId, long restingId, long price, int qty);
    }
}
```

Notice there is no `new` anywhere outside the constructor. Trades are reported through a callback interface rather than by allocating `Trade` objects, so the consumer decides whether to store them and can write into a preallocated buffer.

Now run your reference implementation and this one on the same message sequence and assert the books match at every step. That's your first real test.

---

# Part 4: Threading

## Single writer

The matching engine is **single-threaded**. One thread owns the book and is the only thread that ever mutates it.

This sounds like giving up performance. It isn't, and here's the argument to make:

**Locks are slow and unpredictable.** An uncontended lock is cheap. A contended one parks the thread, which is a context switch costing microseconds and destroying cache warmth. Your latency distribution becomes hostage to scheduling.

**Matching is inherently sequential anyway.** Price-time priority is a statement about ordering. Two threads matching against the same book concurrently would need to agree on order, which means synchronizing, which means you've reintroduced the cost you were avoiding.

**Determinism is worth more than throughput here.** For a simulator, replaying the same input must produce byte-identical output. Multithreaded matching makes that essentially impossible, and it makes bugs unreproducible.

This is the **single writer principle**, and it's what the LMAX Disruptor is built around. If you need more throughput, you shard by instrument: one thread per stock, no shared state. That scales linearly and keeps determinism within each instrument.

So the shape is: I/O and parsing on one thread, matching on another, connected by a queue.

## The SPSC ring buffer

**SPSC** = single producer, single consumer. Exactly one thread writes, exactly one reads. This constraint is what makes it fast: no CAS loops, no locks, just two counters and careful ordering.

Do not use `ArrayBlockingQueue`. It takes a lock on every operation and allocates nodes. Do not pull in the Disruptor library either, at least for this project. Writing it yourself is 100 lines and is the thing you'll actually be asked about.

```java
package obs.mem;

/**
 * Lock-free single-producer single-consumer ring buffer of longs.
 * Safe for EXACTLY one producer thread and one consumer thread.
 */
public final class SpscLongRingBuffer {

    private final long[] buffer;
    private final int mask;

    // Padded so producer and consumer sequences never share a cache line.
    private final PaddedLong writeSeq = new PaddedLong();
    private final PaddedLong readSeq  = new PaddedLong();

    // Thread-local caches of the other side's sequence. These let each thread
    // avoid reading the other's volatile field on most operations, which is
    // where the cross-core traffic comes from.
    private long cachedReadSeq  = 0;
    private long cachedWriteSeq = 0;

    public SpscLongRingBuffer(int capacity) {
        if (Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("capacity must be a power of two");
        buffer = new long[capacity];
        mask = capacity - 1;
    }

    /** Producer thread only. Returns false if full. */
    public boolean offer(long value) {
        long w = writeSeq.get();
        if (w - cachedReadSeq >= buffer.length) {
            cachedReadSeq = readSeq.get();                 // only now pay for the read
            if (w - cachedReadSeq >= buffer.length) return false;
        }
        buffer[(int) (w & mask)] = value;
        writeSeq.set(w + 1);                               // volatile write publishes the slot
        return true;
    }

    /** Consumer thread only. Returns sentinel if empty. */
    public long poll(long emptySentinel) {
        long r = readSeq.get();
        if (r >= cachedWriteSeq) {
            cachedWriteSeq = writeSeq.get();
            if (r >= cachedWriteSeq) return emptySentinel;
        }
        long value = buffer[(int) (r & mask)];
        readSeq.set(r + 1);
        return value;
    }

    // --- padding machinery ---
    // The JVM lays out superclass fields before subclass fields, so sandwiching
    // the real value between two blocks of dummy longs keeps it alone on its
    // own 64-byte cache line. 7 longs = 56 bytes on each side.

    static class LhsPad { long p1, p2, p3, p4, p5, p6, p7; }

    static class Value extends LhsPad { volatile long value; }

    static final class PaddedLong extends Value {
        @SuppressWarnings("unused")
        long q1, q2, q3, q4, q5, q6, q7;

        long get() { return value; }
        void set(long v) { value = v; }
    }
}
```

Four things to understand here.

**The capacity must be a power of two** so that `w & mask` replaces `w % capacity`. Same reasoning as the hash map.

**Sequences are monotonically increasing and never wrap back.** `writeSeq` counts total items ever written. The slot is `writeSeq & mask`. This means "is it full?" is just `write - read >= capacity`, with no ambiguity between full and empty (the classic ring buffer bug where head == tail could mean either).

**The cached sequences are the main optimization.** Without them, every `offer` reads the consumer's volatile `readSeq`, which is a field the consumer is constantly writing. That read pulls a cache line owned by the other core, every single time. With the cache, the producer only pays that cost when it believes the buffer is full. In the common case where the consumer is keeping up, the producer touches only its own cache lines.

**The padding.** `writeSeq` is written constantly by the producer. `readSeq` is written constantly by the consumer. If they landed in the same cache line, every write by either would invalidate the other's copy. The `LhsPad`/`Value`/`PaddedLong` inheritance sandwich forces them apart. `@jdk.internal.vm.annotation.Contended` does the same thing more cleanly but requires `-XX:-RestrictContended` and isn't exported to user code, so the inheritance trick remains the portable option.

**Why `volatile` is sufficient and no lock is needed:** the producer writes the slot *before* the volatile write to `writeSeq`. The volatile write is a release: everything written before it is visible to any thread that subsequently reads that volatile and sees the new value. The consumer's volatile read of `writeSeq` is an acquire. So if the consumer sees `writeSeq == w+1`, it is guaranteed to see the data written into slot `w`. This is the Java Memory Model's happens-before relationship, and it's the entire correctness argument for the structure.

## Waiting

When the consumer finds the buffer empty, it has to wait. Options:

```java
Thread.onSpinWait();     // busy-spin, hints to CPU to save power in the loop
Thread.yield();          // give up the timeslice
LockSupport.parkNanos(1) // sleep
```

`onSpinWait()` gives the lowest latency, because the thread never leaves the CPU and its caches stay warm. It costs a core at 100% utilization. That tradeoff (burning a core to save microseconds) is exactly the tradeoff real trading systems make, and saying so shows you understand the domain.

For a simulator you'll usually run the whole thing single-threaded anyway for determinism. Build the ring buffer for the live-feed path and to have something to talk about.

**Mention but don't necessarily implement:** pinning threads to specific CPU cores (via `taskset` or the OpenHFT affinity library) so the OS scheduler never migrates them, and isolating those cores from the scheduler entirely with `isolcpus`. This is standard practice in production and knowing the terms costs you nothing.

---

# Part 5: Feeding the book

## Real data: Nasdaq ITCH 5.0

ITCH is Nasdaq's L3 market data protocol. It's binary, well documented, and historical sample files are published free on Nasdaq's FTP site. Parsing it is the most credible way to say your book handles real market data.

Structure of a file: a sequence of messages, each preceded by a 2-byte big-endian length. All multi-byte fields are **big-endian**, which matters because x86 is little-endian and Java's `ByteBuffer` defaults to big-endian (convenient here).

Key message types:

| Type | Name | Payload after header |
|---|---|---|
| `A` | Add Order | orderRef(8), side(1), shares(4), stock(8), price(4) |
| `F` | Add Order with MPID | same as A, plus attribution(4) |
| `E` | Order Executed | orderRef(8), shares(4), matchNumber(8) |
| `C` | Executed with Price | orderRef(8), shares(4), matchNum(8), printable(1), price(4) |
| `X` | Order Cancel | orderRef(8), cancelledShares(4) |
| `D` | Order Delete | orderRef(8) |
| `U` | Order Replace | origRef(8), newRef(8), shares(4), price(4) |

Every message begins with the same 11-byte header: type(1), stockLocate(2), trackingNumber(2), timestamp(6). The timestamp is nanoseconds since midnight, packed into six bytes.

> Verify these offsets against the current official ITCH specification before you rely on them. Nasdaq publishes it as a PDF. Parsing against a spec you actually read is part of the exercise, and layouts do get revised.

**Read the file with `MappedByteBuffer`.** This maps the file into your process's address space so the OS pages it in on demand, with no read syscalls and no copying into a Java byte array:

```java
package obs.feed;

import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public final class ItchReader {

    public interface Handler {
        void onAdd(long ts, long orderRef, byte side, int shares, long price);
        void onExecute(long ts, long orderRef, int shares);
        void onCancel(long ts, long orderRef, int shares);
        void onDelete(long ts, long orderRef);
        void onReplace(long ts, long oldRef, long newRef, int shares, long price);
    }

    private final MappedByteBuffer buf;

    public ItchReader(String path) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r");
             FileChannel ch = raf.getChannel()) {
            buf = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
            // ByteBuffer is big-endian by default, which is what ITCH uses.
        }
    }

    /** Filter to a single stockLocate id so you only build one book. */
    public void replay(Handler h, int stockLocateFilter) {
        while (buf.remaining() > 2) {
            int len = Short.toUnsignedInt(buf.getShort());
            if (buf.remaining() < len) break;

            int start = buf.position();
            byte type = buf.get(start);
            int locate = Short.toUnsignedInt(buf.getShort(start + 1));

            if (locate == stockLocateFilter) {
                long ts = readSixByteTimestamp(start + 5);
                dispatch(h, type, ts, start);
            }
            buf.position(start + len);
        }
    }

    private long readSixByteTimestamp(int off) {
        // Six bytes, big-endian, assembled without allocating.
        return ((long) Short.toUnsignedInt(buf.getShort(off)) << 32)
             | (Integer.toUnsignedLong(buf.getInt(off + 2)));
    }

    private void dispatch(Handler h, byte type, long ts, int p) {
        switch (type) {
            case 'A', 'F' -> h.onAdd(ts,
                    buf.getLong(p + 11),
                    (byte) (buf.get(p + 19) == 'B' ? 0 : 1),
                    buf.getInt(p + 20),
                    Integer.toUnsignedLong(buf.getInt(p + 32)));
            case 'E', 'C' -> h.onExecute(ts, buf.getLong(p + 11), buf.getInt(p + 19));
            case 'X'      -> h.onCancel (ts, buf.getLong(p + 11), buf.getInt(p + 19));
            case 'D'      -> h.onDelete (ts, buf.getLong(p + 11));
            case 'U'      -> h.onReplace(ts,
                    buf.getLong(p + 11), buf.getLong(p + 19),
                    buf.getInt(p + 27),
                    Integer.toUnsignedLong(buf.getInt(p + 31)));
            default -> { }   // system events, trades, etc. -- ignore for now
        }
    }
}
```

Two properties to call out: **zero allocation** (fields are read directly out of the mapped buffer by absolute offset; no intermediate message object is created) and **zero copy** (the file never gets copied into heap memory). Both are things an interviewer will recognize.

**A subtlety about ITCH and prices:** execute and cancel messages carry only an order reference, not a price or a side. Your book must look up the order by ID to know which level to modify. This is precisely why `LongIntMap` exists and why its speed matters.

**Alternative data source:** LOBSTER (lobsterdata.com) publishes free sample files for a handful of stocks as plain CSV, along with a reconstructed order book for each. It's much easier to start with, and critically, their reconstructed book gives you a golden file to validate against. Consider starting with LOBSTER and adding ITCH afterwards.

## Synthetic data

You also want a generator, so you can produce arbitrary volumes and stress specific conditions.

**The naive model** is a Poisson process: orders arrive independently at a constant average rate, prices drawn from some distribution around the touch. This is easy and unrealistic.

**Why it's unrealistic:** real order flow is *clustered*. Activity begets activity. A burst of orders makes another burst more likely in the next few milliseconds. Poisson arrivals are memoryless and produce smooth, evenly-spread flow that never stresses your system the way real bursts do.

**The better model is a Hawkes process**, a self-exciting point process. Each event temporarily raises the arrival intensity:

```
λ(t) = λ₀ + Σ  α · exp(-β(t - tᵢ))
          tᵢ<t
```

`λ₀` is the baseline rate. Each past event at time `tᵢ` adds `α` to the intensity, decaying exponentially at rate `β`. With `α/β < 1` the process is stable; as it approaches 1 you get heavy clustering.

The exponential kernel is chosen because it allows an O(1) recursive update rather than summing over all history:

```java
// On each event, at time t, having last updated at lastT:
intensity = lambda0 + (intensity - lambda0) * Math.exp(-beta * (t - lastT));
intensity += alpha;      // this event excites future arrivals
lastT = t;
```

Include realistic proportions: in modern equity markets the great majority of orders are cancelled rather than filled, and cancel-to-trade ratios above 10:1 are normal. If your generator produces mostly fills, you're testing the wrong path.

Being able to explain why you chose Hawkes over Poisson is a market-microstructure signal, and it's the kind of thing that separates a CS project from a quant project.

---

# Part 6: The simulation layer

This is what makes it a *simulator* rather than a data structure, and it's the part a quant recruiter cares most about. Three components.

## 1. The simulation clock

Do not use wall-clock time. Use an **event-driven clock**: a priority queue of timestamped events, where time jumps directly to the next event's timestamp.

```java
package obs.sim;

import java.util.PriorityQueue;

public final class SimClock {

    public interface Event { long time(); void fire(); }

    private final PriorityQueue<Event> queue =
            new PriorityQueue<>((a, b) -> Long.compare(a.time(), b.time()));
    private long now;

    public long now() { return now; }

    public void schedule(Event e) {
        if (e.time() < now) throw new IllegalArgumentException("event in the past");
        queue.add(e);
    }

    public void run(long until) {
        while (!queue.isEmpty() && queue.peek().time() <= until) {
            Event e = queue.poll();
            now = e.time();       // time jumps forward to the event
            e.fire();
        }
    }
}
```

This gives you two things. **Determinism**: same inputs, same event order, same results, every run. **Speed**: a full trading day simulates in seconds because idle time costs nothing.

Ties matter. If two events share a timestamp, break the tie by a monotonically increasing sequence number, otherwise `PriorityQueue` ordering is unspecified and your runs stop being reproducible.

## 2. The latency model

**This is the single most important idea in the whole project.**

A naive backtest does this: see the book, decide to place an order, place it, assume it's there. That's a lie. In reality:

```
t=0     Exchange's matching engine updates the book.
t+2µs   Update leaves the exchange.
t+50µs  Update arrives at your machine (wire latency).
t+51µs  Your strategy processes it and decides to place an order.
t+52µs  Order leaves your machine.
t+100µs Order arrives at the exchange gateway.
t+105µs Order reaches the matching engine.
```

Your order acts on a book that is **105 microseconds stale**. In that window, the level you wanted to join may have been consumed, the price may have moved, and the opportunity you saw may be gone. Faster participants saw the same update earlier and already acted.

A backtest ignoring this systematically overstates performance, and the strategies it overstates most are exactly the latency-sensitive ones you'd be trying to evaluate.

Model it explicitly:

```java
package obs.sim;

public final class LatencyModel {

    private final long marketDataNanos;   // exchange -> strategy
    private final long orderEntryNanos;   // strategy -> exchange
    private final java.util.Random jitter;
    private final long jitterRangeNanos;

    public LatencyModel(long md, long oe, long jitterRange, long seed) {
        this.marketDataNanos = md;
        this.orderEntryNanos = oe;
        this.jitterRangeNanos = jitterRange;
        this.jitter = new java.util.Random(seed);   // seeded: reproducible
    }

    public long marketDataDelay() { return marketDataNanos + noise(); }
    public long orderEntryDelay() { return orderEntryNanos + noise(); }

    private long noise() {
        return jitterRangeNanos == 0 ? 0
             : (long) (jitter.nextGaussian() * jitterRangeNanos);
    }
}
```

So every book update the strategy sees is scheduled at `eventTime + marketDataDelay()`, and every order the strategy sends is scheduled to reach the exchange at `decisionTime + orderEntryDelay()`.

Then make the latency **configurable and sweep it**. Running the same strategy at 10µs, 100µs, and 1ms and plotting P&L against latency is a genuinely interesting result and makes a great README chart. It also directly answers "so what does the simulator tell you?"

## 3. Queue position tracking

The second big idea, and the one that most distinguishes a serious simulator.

When your simulated order joins a price level, it goes to the back of the queue. Whether it fills depends on what happens to the orders ahead of it.

```
You place a bid for 100 at $150.00.
There are already 500 shares resting at $150.00.
Your queue position: 500 shares ahead.

A market sell of 300 arrives -> fills the first 300 ahead of you.
  Shares ahead: 200. You: nothing.

Someone ahead of you cancels 150 shares.
  Shares ahead: 50. You: still nothing.

A market sell of 200 arrives -> 50 fills the rest ahead, 100 fills YOU,
  50 goes to whoever is behind you.
  You are filled.
```

The naive assumption is "I get filled if the price trades at or through my level." That's wrong and it's wrong in the optimistic direction, which is the worst kind of wrong for a backtest.

Two ways to track this, depending on your data:

**With L3 data (what you have):** run a **shadow book**. Maintain the real book from the feed, insert your synthetic orders into it, and let the normal matching logic handle them. Because you see every individual order and every individual cancel, your order's position is tracked exactly by the existing intrusive linked list. This is why building on L3 was worth the effort.

**With only L2 data:** you'd have to approximate, because when quantity at a level decreases you can't tell whether it was a cancel from ahead of you or behind you. The standard pessimistic assumption is that cancels come from behind you and fills come from in front. Knowing this limitation is worth mentioning even though you don't face it.

```java
package obs.sim;

/** Tracks fill eligibility for one simulated order resting in the book. */
public final class QueuedOrder {
    public final long price;
    public final byte side;
    public int remaining;
    public long qtyAhead;        // shares with better time priority

    public QueuedOrder(long price, byte side, int qty, long qtyAhead) {
        this.price = price; this.side = side;
        this.remaining = qty; this.qtyAhead = qtyAhead;
    }

    /** A trade consumed `traded` shares at this level. Returns shares filled to us. */
    public int onTradeAtLevel(int traded) {
        if (qtyAhead >= traded) { qtyAhead -= traded; return 0; }
        int toUs = (int) Math.min(remaining, traded - qtyAhead);
        qtyAhead = 0;
        remaining -= toUs;
        return toUs;
    }

    /** An order ahead of us cancelled. We move up the queue. */
    public void onCancelAhead(int qty) {
        qtyAhead = Math.max(0, qtyAhead - qty);
    }
}
```

## The honest limitation

State this in your README, because being asked about it and having no answer is bad, and raising it yourself is good:

**The simulator assumes no market impact.** Your orders are inserted into a historical message stream that was generated in a world where your orders did not exist. In reality, your presence would change other participants' behaviour. A large order would move the price. Other market makers would react to your quotes.

For small orders in liquid names this is a tolerable approximation. For large orders it is not. Modelling impact properly requires either an agent-based simulation where other participants respond, or an empirical impact model (square-root law and similar). Naming the limitation and the direction of the fix is the right move.

## Strategy interface

Keep it small:

```java
package obs.strategy;

public interface Strategy {
    void onBookUpdate(long simTime, long bestBid, long bestAsk,
                      long bidQty, long askQty);
    void onTrade(long simTime, long price, int qty, byte aggressorSide);
    void onOwnFill(long simTime, long orderId, long price, int qty);
    void onOwnCancelAck(long simTime, long orderId);
}
```

Write one sample strategy so there's something to run. A basic market maker: quote one tick inside the spread on both sides, size fixed, cancel and requote when the touch moves, stop quoting a side when inventory exceeds a limit. It doesn't need to be profitable. It needs to exercise the machinery and produce a P&L curve and a fill-rate number.

---

# Part 7: Measuring it

Unmeasured performance claims are worth nothing. This part is what turns the project into a résumé line.

## Percentiles, never averages

Report p50, p99, p99.9, p99.99, and max. Never report a mean.

The reason: latency distributions have long right tails. A system with a 500ns median and a 50ms max has a mean of maybe 600ns, which tells you nothing about the 50ms. In trading, the tail is what costs money, because it happens precisely during bursts when the market is moving and your orders matter most.

## HdrHistogram

Use `org.hdrhistogram:HdrHistogram`. It records values across a huge dynamic range at constant memory and constant recording cost, with no allocation after construction:

```java
import org.HdrHistogram.Histogram;

// Track 1ns to 10s with 3 significant digits of precision.
Histogram hist = new Histogram(1, 10_000_000_000L, 3);

for (int i = 0; i < iterations; i++) {
    long start = System.nanoTime();
    book.addLimitOrder(id++, side, price, qty);
    hist.recordValue(System.nanoTime() - start);
}

System.out.printf("p50    %d ns%n", hist.getValueAtPercentile(50.0));
System.out.printf("p99    %d ns%n", hist.getValueAtPercentile(99.0));
System.out.printf("p99.9  %d ns%n", hist.getValueAtPercentile(99.9));
System.out.printf("p99.99 %d ns%n", hist.getValueAtPercentile(99.99));
System.out.printf("max    %d ns%n", hist.getMaxValue());
```

Be aware that `System.nanoTime()` itself costs roughly 20–30ns, so for operations in that range the measurement overhead is significant. This is why you also use JMH, which handles it properly.

## Coordinated omission

This is the single most impressive thing you can know about latency measurement, and most candidates have never heard of it.

Suppose your benchmark loop is: send a request, wait for the response, record the time, repeat. Now suppose one operation stalls for 100ms because of a GC pause. You record one 100ms sample.

But in a real system with requests arriving at a fixed rate, those requests kept arriving during the stall. If the rate was 10,000/sec, then 1,000 requests queued up behind that pause, and they experienced latencies of 100ms, 99.9ms, 99.8ms, and so on. The true distribution has 1,000 terrible samples. Your benchmark recorded one.

By waiting, your measurement harness **coordinated** with the system under test, and omitted exactly the samples that mattered. The result is a tail latency figure that can be off by orders of magnitude, always in the flattering direction.

Two fixes:

1. Record latency against the **intended** send time, not the actual one. If you meant to send at t=100µs and only managed at t=50ms, the latency includes the 49.9ms of queueing.
2. Use HdrHistogram's `recordValueWithExpectedInterval(value, expectedInterval)`, which synthesizes the missing samples for you.

Being able to explain coordinated omission in an interview is worth more than a hundred nanoseconds of median latency.

## JMH

JMH is the JVM's microbenchmark harness. Hand-rolled microbenchmarks in Java are almost always wrong, for reasons JMH exists to solve:

- **JIT warmup.** The first few thousand executions run interpreted or lightly compiled. Measuring them gives numbers 10–100x too slow. JMH runs warmup iterations first.
- **Dead code elimination.** If you compute a result and don't use it, the JIT removes the computation entirely and you benchmark an empty loop. JMH's `Blackhole` consumes results in a way the compiler can't see through.
- **Constant folding.** If inputs are compile-time constants, the JIT precomputes the answer. JMH's `@State` objects prevent this.
- **Profile pollution / on-stack replacement.** Various subtler effects that JMH's forking and iteration structure handle.

```java
package obs.bench;

import obs.core.OrderBook;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 10, time = 2)
@Fork(value = 3, jvmArgs = {"-Xms4g", "-Xmx4g", "-XX:+AlwaysPreTouch"})
public class OrderBookBenchmark {

    private OrderBook book;
    private long nextId;

    @Setup(Level.Iteration)
    public void setup() {
        book = new OrderBook(10_000_00L, 100L, 20_000, 1 << 20, (a, r, p, q) -> {});
        // Prefill with a realistic book shape before measuring.
        for (int i = 0; i < 10_000; i++) {
            book.addLimitOrder(nextId++, OrderBook.BUY,  1_499_000L - (i % 50) * 100L, 100);
            book.addLimitOrder(nextId++, OrderBook.SELL, 1_501_000L + (i % 50) * 100L, 100);
        }
    }

    @Benchmark
    public void addPassiveOrder() {
        book.addLimitOrder(nextId++, OrderBook.BUY, 1_499_000L, 100);
    }

    @Benchmark
    public void addThenCancel() {
        long id = nextId++;
        book.addLimitOrder(id, OrderBook.BUY, 1_498_000L, 100);
        book.cancel(id);
    }

    @Benchmark
    public void aggressiveCross(Blackhole bh) {
        book.addLimitOrder(nextId++, OrderBook.BUY, 1_502_000L, 500);
        bh.consume(book.bestAsk());
    }
}
```

Run with `-prof gc` to get allocation rate. **`gc.alloc.rate.norm` is bytes allocated per operation, and for your hot paths it should read 0.** That's how you prove the zero-allocation claim rather than asserting it.

## Profiling

When your p99.9 is much worse than your median, find out why rather than guessing. Use **async-profiler**, which samples without the safepoint bias that afflicts most Java profilers:

```
java -agentpath:/path/libasyncProfiler.so=start,event=cpu,file=cpu.html -jar ...
```

Also run with `-Xlog:gc*` and correlate GC log timestamps against your latency spikes. If they line up, it's GC. If they don't, look at page faults (`-XX:+AlwaysPreTouch` fixes first-touch faults), TLB misses on a large ladder (consider huge pages), or JIT deoptimization (`-XX:+PrintCompilation` shows `made not entrant` events).

## What to publish

A table in your README, with hardware and flags stated:

```
Hardware: AMD Ryzen 7950X, 64GB DDR5-5600, Ubuntu 24.04
JVM:      OpenJDK 21.0.3, -Xms4g -Xmx4g -XX:+AlwaysPreTouch -XX:+UseEpsilonGC

Operation          p50      p99     p99.9   p99.99      max
-----------------------------------------------------------
add (passive)     ___ns    ___ns    ___ns    ___ns    ___ns
cancel            ___ns    ___ns    ___ns    ___ns    ___ns
add (crossing)    ___ns    ___ns    ___ns    ___ns    ___ns

ITCH replay throughput: _______ messages/sec, single core
Allocation on hot path: 0 bytes/op (JMH -prof gc)
```

Numbers without a hardware spec look invented. Fill every blank with something you measured.

> `-XX:+UseEpsilonGC` is the no-op collector: it allocates and never collects, so the JVM dies when the heap fills. If your hot path truly allocates nothing, a benchmark run under Epsilon will complete without dying. That is a very strong, very cheap proof of the zero-allocation claim. Use it for benchmarks only, obviously.

---

# Part 8: Proving it's correct

Fast and wrong is worthless. These checks are cheap and their absence is glaring to anyone who reviews the code.

## Invariant checks

Write a validator that walks the entire structure and asserts every property that must hold. Run it after every message in test mode, and never in benchmarks.

```java
public void validate() {
    // 1. The book is never crossed.
    if (bestBidIdx != EMPTY && bestAskIdx != EMPTY)
        assert bestBidIdx < bestAskIdx : "crossed book";

    // 2. bestBidIdx really is the highest non-empty bid level.
    for (int i = bestBidIdx + 1; i < levels; i++)
        assert bidHead[i] == EMPTY : "bid above the touch at " + i;

    // 3. Each level's cached quantity equals the sum of its orders,
    //    and its cached count equals the length of its list.
    for (int i = 0; i < levels; i++) {
        long sum = 0; int n = 0;
        for (int s = bidHead[i]; s != EMPTY; s = pool.next[s]) { sum += pool.qty[s]; n++; }
        assert sum == bidQty[i] && n == bidCount[i] : "level desync at " + i;
    }

    // 4. Forward and backward links agree.
    for (int i = 0; i < levels; i++) {
        int prev = EMPTY;
        for (int s = bidHead[i]; s != EMPTY; s = pool.next[s]) {
            assert pool.prev[s] == prev : "broken back-link";
            prev = s;
        }
        assert bidTail[i] == prev : "bad tail pointer";
    }

    // 5. No leaks: live orders in the pool == entries in the id map.
    assert pool.liveCount() == idToSlot.size() : "pool/map leak";
}
```

Invariant 5 catches the most insidious class of bug: a pool slot released without removing its ID from the map, or vice versa, which produces a slow leak that only manifests hours into a run.

Run tests with `-ea` to enable assertions.

## Property-based testing

Use **jqwik**. Instead of writing specific test cases, you declare properties that must hold for *any* valid input, and the library generates thousands of random inputs trying to break them. When it finds a failure it **shrinks** it: automatically reduces the failing case to the smallest one that still fails, so you get a 3-message reproduction instead of a 50,000-message one.

```java
import net.jqwik.api.*;

class OrderBookProperties {

    @Property(tries = 2000)
    void bookIsNeverCrossed(@ForAll("messageSequences") List<Msg> msgs) {
        OrderBook book = newBook();
        for (Msg m : msgs) {
            apply(book, m);
            assertTrue(book.bestBid() < book.bestAsk());
        }
    }

    @Property(tries = 2000)
    void matchesReferenceImplementation(@ForAll("messageSequences") List<Msg> msgs) {
        OrderBook fast = newBook();
        RefOrderBook slow = new RefOrderBook();
        for (Msg m : msgs) {
            apply(fast, m);
            apply(slow, m);
            assertEquals(slow.bestBid(), fast.bestBid());
            assertEquals(slow.bestAsk(), fast.bestAsk());
        }
    }

    @Provide
    Arbitrary<List<Msg>> messageSequences() { /* generate adds/cancels/crosses */ }
}
```

The second property is why you kept the reference implementation from Part 1. Differential testing against a simple, obviously-correct version is the most effective way to validate an optimized one.

## Replay determinism

```java
@Test
void replayIsDeterministic() throws Exception {
    String a = runFullReplayAndHashOutput("sample.itch");
    String b = runFullReplayAndHashOutput("sample.itch");
    assertEquals(a, b);
}
```

Run the same input twice, hash the complete output (all trades, all book snapshots), assert the hashes match. This catches accidental nondeterminism from iteration order, unseeded randomness, or threading. It's three lines and it's the foundation of trusting any simulation result.

## Golden-file validation

The strongest correctness claim available to you. LOBSTER publishes both the raw message stream and their own reconstructed order book. Replay their messages through your book and compare your reconstruction to theirs, level by level, message by message.

If you can write "reconstruction verified against LOBSTER reference output across 12.4M messages with zero mismatches," that is a far more convincing statement than any number of unit tests, because it's an independent check against someone else's implementation.

---

# Part 9: Build order

Do these in sequence. Each milestone is independently demoable, so if you run out of time you still have something coherent.

**Milestone 1 — Reference book (1 evening).**
Part 1's `RefOrderBook`. Unit tests for add, cancel, partial fill, full fill, multi-level sweep. Print the book to console. You now understand the domain.

**Milestone 2 — Fast core (1 week).**
`OrderPool`, `LongIntMap`, ladder-based `OrderBook`. Differential test against Milestone 1. Do not move on until they agree on randomized sequences.

**Milestone 3 — Measurement (2 days).**
JMH benchmarks, HdrHistogram, first real numbers. Run `-prof gc` and drive allocation to zero. This is where the project becomes résumé-legible, so don't defer it.

**Milestone 4 — Real data (3–4 days).**
LOBSTER CSV first because it's easy. Then ITCH binary parsing. Golden-file validation against LOBSTER's reconstruction. Report replay throughput.

**Milestone 5 — Simulation layer (1 week).**
`SimClock`, `LatencyModel`, `QueuedOrder`, `Strategy` interface, one sample market maker. Produce a P&L curve and a fill-rate statistic. This is the milestone that makes it a quant project rather than a data-structures project.

**Milestone 6 — Threading (3 days, optional).**
`SpscLongRingBuffer`, split parsing from matching. Measure whether it actually helps; be honest if it doesn't, since for a file replay it may not.

**Milestone 7 — Polish (2 days).**
README with architecture diagram, benchmark table, design-decisions section, rejected-alternatives section, limitations section. This is what actually gets read.

## Scope discipline

One instrument. One venue. Finished and measured.

A multi-asset cross-venue framework that's 60% done is worth less than a single-stock simulator that's complete, benchmarked, and has a good README. Reviewers skim the README and maybe open one source file. Make the README carry the project.

## What the README must contain

1. **One-paragraph description** of what it is.
2. **Benchmark table** with hardware and JVM flags. Near the top.
3. **Architecture diagram**, even ASCII.
4. **Design decisions**, with the alternative rejected and why. Array ladder vs TreeMap. Intrusive list vs LinkedList. Object pool vs allocation. Single writer vs concurrent matching.
5. **Where the design loses.** The array ladder wastes memory on wide tick ranges and needs recentering for unbounded instruments. The pool has a fixed ceiling. The simulator has no market impact model. Showing you know the weaknesses is more convincing than showing the design.
6. **How to run it**, in three commands or fewer.

## The interview questions you will actually get

Prepare answers. These are the real ones.

**"Your p99.9 is 8µs but your median is 600ns. Where does the gap come from?"**
Have the profile. Usual suspects: GC (correlate the GC log), page faults on first touch (`AlwaysPreTouch`), TLB misses if the ladder is large (huge pages), JIT deoptimization on a branch only taken during bursts, and the scan in `advanceTouch` when the book gaps out.

**"Why not use a TreeMap? It's O(log n) and n is small."**
Because asymptotic complexity is the wrong model at this scale. With n = 20,000 levels, log n is about 14 comparisons, but each one is a potential cache miss at ~100ns, so the constant factor dominates completely. The array is one cache miss. Then concede the real cost: memory, and recentering for unbounded price ranges.

**"How do you know your book is correct?"**
Differential testing against a simple reference implementation, property-based tests with shrinking, invariant validation after every message in test mode, and golden-file validation against LOBSTER's independent reconstruction over N million messages.

**"What's the most unrealistic thing about your simulator?"**
No market impact. Your orders are inserted into a history that was generated without them. Fine for small orders in liquid names, wrong for anything large. The fix is an agent-based model or an empirical impact function.

**"Why single-threaded? Don't you have multiple cores?"**
Matching is sequential by definition because price-time priority is an ordering rule. Concurrency would require synchronization that costs more than it saves, and it would destroy replay determinism, which for a simulator is non-negotiable. Scale by sharding across instruments instead, which is what real exchanges do.

---

# Appendix A: Glossary

| Term | Meaning |
|---|---|
| Ask / offer | A resting sell order, or the price of the best one |
| Bid | A resting buy order, or the price of the best one |
| Spread | Best ask minus best bid |
| Touch / inside | The best bid and best ask together |
| Tick | Minimum price increment |
| Crossed book | Bid ≥ ask. Always a bug in your code |
| Aggressor | The incoming order that causes a trade |
| Resting / passive | An order sitting in the book waiting |
| Queue position | How much quantity has better time priority than you |
| L1 / L2 / L3 | Feed detail: touch only / per-level totals / per-order |
| ITCH | Nasdaq's binary L3 market data protocol |
| SPSC | Single producer, single consumer |
| False sharing | Unrelated variables on one cache line causing coherence traffic |
| Coordinated omission | Measurement bug where the harness hides the worst latencies |
| Hawkes process | Self-exciting arrival process; models order flow clustering |

# Appendix B: Dependencies

```xml
<dependencies>
  <dependency>
    <groupId>org.hdrhistogram</groupId>
    <artifactId>HdrHistogram</artifactId>
    <version>2.2.2</version>
  </dependency>
  <dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.37</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.37</version>
    <scope>provided</scope>
  </dependency>
  <dependency>
    <groupId>net.jqwik</groupId>
    <artifactId>jqwik</artifactId>
    <version>1.8.4</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Check for current versions when you set up; these move.

# Appendix C: JVM flags

**For benchmarking:**
```
-Xms8g -Xmx8g              Fixed heap; no resizing pauses
-XX:+AlwaysPreTouch        Fault in all heap pages at startup, not during the run
-XX:+UseEpsilonGC          No-op collector; proves zero allocation (run dies if you allocate)
-XX:+UnlockExperimentalVMOptions   Required for Epsilon
```

**For a long-running realistic run:**
```
-Xms8g -Xmx8g
-XX:+AlwaysPreTouch
-XX:+UseZGC                Concurrent collector, sub-millisecond pauses
-Xlog:gc*:file=gc.log      So you can correlate pauses with latency spikes
```

**For diagnosing:**
```
-XX:+PrintCompilation      Watch for "made not entrant" (deoptimization)
-XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining
```

# Appendix D: Package layout

```
obs/
  core/       Order semantics. OrderBook, OrderPool, MatchingEngine, TradeListener
  mem/        Reusable primitives. LongIntMap, SpscLongRingBuffer, padding helpers
  feed/       Input. ItchReader, LobsterReader, HawkesGenerator, Handler interfaces
  sim/        Simulation. SimClock, LatencyModel, QueuedOrder, ShadowBook, Exchange
  strategy/   Strategy interface, SampleMarketMaker
  metrics/    LatencyRecorder (HdrHistogram wrapper), BookStats, PnlTracker
  ref/        RefOrderBook -- the slow reference implementation, kept for testing
  bench/      JMH harnesses
```

Keep `core` and `mem` free of dependencies on anything else. They should be usable standalone, which is a reasonable proxy for whether your layering is clean.
