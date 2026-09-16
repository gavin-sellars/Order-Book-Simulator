# Benchmarks

Re-measured 2026-09-15, after an audit of the earlier figures (see [What changed](#what-changed-after-the-audit)). Every number below was measured on this machine; nothing is estimated.

## Environment

| | |
|---|---|
| Machine | ASUS ROG Zephyrus G14 GA402RJ (laptop) |
| CPU | AMD Ryzen 9 6900HS, 8 cores / 16 threads. Caches: L2 4 MB, L3 16 MB (totals reported by Windows) |
| Memory | 16 GB DDR5-4800 (2 × 8 GB) |
| OS | Windows 11 Home, build 26200, "High performance" power plan |
| JVM | Java HotSpot 25.0.4.1+1-LTS-5, G1, `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` |
| JMH | 1.37, annotation defaults: 3 forks, 5 × 1 s warm-up, 10 × 1 s measurement, `-prof gc` |
| Background load | 22–28% CPU at the checkpoints (browser, system services); no other benchmarks running |

### Caveats

- **Noise.** A laptop on Windows is noisy. Error bars are JMH's 99.9% confidence intervals; replay figures are medians over several fresh JVMs. An earlier run of this suite alongside a game using about three cores measured everything 30–60% slower, so treat differences under about 1.5× with care.
- **Coarse clock.** `System.nanoTime()` costs about 24 ns per call here and only advances in 100 ns steps. Per-message percentiles are therefore quantised to 100 ns and include the timer's own cost. A fast-book p50 of "1" or "100" means "below the clock's resolution". JMH's averages are the precise per-operation numbers.
- **Synthetic data.** Every workload here is generated. Nothing has been run on a real exchange feed.
- **JMH warning.** JMH 1.37 prints a "terminally deprecated `sun.misc.Unsafe`" warning on JDK 25. It doesn't affect results.

## What changed after the audit

The first version of these benchmarks flattered the fast book in one place and hid its biggest weakness in another:
- **The benchmark tape let the book grow to 87,492 resting orders** in about 15 price levels. A typical cancel had about 2,000 orders ahead of it, which the reference book's `ArrayDeque.remove` scans. `MessageTape` now caps the flow at 4,000 resting orders (5,000 with the prefilled book, the same as the synthetic ITCH session), and only cancels orders that are still resting; 20% of the old tape's cancels hit orders that had already filled.
- **The fast book's pool was sized for 1,048,576 orders**, which makes its id map 24 MB, bigger than the L3 cache. On the full-day replay that made the fast book slower than a TreeMap book with O(1) cancel. Pools are now sized to the book (16,384 orders); the 1M figure is kept below as a finding.
- **`RefLinkedOrderBook`** (the reference book with only the O(1) cancel added) is now benchmarked alongside, so the gain can be split between the O(1) cancel and everything else.
- **JMH forks went from 2 to 3**; `itchSession` checks every event rather than only the end of the day; and `gradlew replayLatency` gives warmed-up replay throughput and percentiles.

## Full-day ITCH replay

`gradlew replayLatency -PreplayArgs="<book> 4"`: the synthetic session from `itchSession` (3,976,272 messages delivered, 119,300,866 bytes), replayed from a memory-mapped file into a fresh book on a single thread. Each JVM replays 4 times; run 1 is warm-up and excluded, and the JVM reports the median of runs 2–4. Pages of the mapping are touched before timing. Every book ran in 5 separate JVMs, interleaved in rotating order; the table gives the median of those 5 medians.

| Book | Median msg/s | ns/msg | Per-JVM medians (M msg/s) |
|---|---|---|---|
| `RefOrderBook` (TreeMap + ArrayDeque) | 7.08M | 141.2 | 7.31, 7.08, 6.98, 7.13, 6.68 |
| `RefLinkedOrderBook` (TreeMap + O(1) cancel) | 14.78M | 67.6 | 14.14, 14.78, 13.81, 14.84, 15.78 |
| **`OrderBook`, pool 16,384** | **21.56M** | **46.4** | 21.56, 19.96, 21.96, 22.04, 21.52 |
| `OrderBook`, pool 1,048,576 (3 JVMs) | 11.78M | 84.9 | 13.07, 11.78, 10.69 |

Per-message latency on a further replay in each JVM, `System.nanoTime()` around each message's decode and apply (ns, median of each percentile across the JVMs):

| ns | p50 | p99 | p99.9 | p99.99 | max | GC during the pass |
|---|---|---|---|---|---|---|
| `RefOrderBook` | 200 | 700 | 1,300 | 8,303 | 1,709,055 | 1 collection, 1–2 ms, every JVM |
| `RefLinkedOrderBook` | 100 | 500 | 1,200 | 4,703 | 1,601,535 | 1 collection, 1 ms, in 4 of 5 |
| **`OrderBook`, pool 16,384** | **≤ 100** | **100** | **1,000** | **1,500** | **89,855** | **none** |
| `OrderBook`, pool 1,048,576 | 100 | 400 | 600 | 2,901 | 83,519 | none |

What it shows:
- **The fast book is 3.0× the reference book** on mean cost (141 → 46 ns per message), with p99 at the clock's resolution instead of 700 ns.
- **About half of that is the O(1) cancel.** `RefLinkedOrderBook` changes nothing but the queue structure and gets from 7.1M to 14.8M msg/s. The array ladder, pool and primitive id map take it the rest of the way, to 21.6M.
- **That second half only exists when the id map fits in cache.** With a 1M-order pool, the fast book drops to 11.8M msg/s, behind `RefLinkedOrderBook`.
- **GC explains the reference books' tail, not the fast book's.** Their 1.5–2.1 ms maxima coincide with a G1 young collection during the pass. The fast book allocates nothing and collected nothing; its 50–155 µs maxima are most likely OS scheduling.
- **The median is below the clock.** The fast book's p50 is under 100 ns in every JVM. The mean (46 ns) is the defensible per-message figure.

## Zero allocation on the hot path

Proven three independent ways:

| Method | Result |
|---|---|
| `ZeroAllocationTest` (runs on every build): per-thread allocation counter across 10 tape replays | **0 bytes** for the fast book. The same measurement on the reference book reads millions of bytes. |
| `gradlew epsilonSmoke`: Epsilon GC (never collects), 512 MB heap | **200,375,208 messages replayed, 0 bytes allocated**, 6.9 s, 29,080,297 messages/sec |
| JMH `-prof gc`, `gc.alloc.rate.norm` | **0.000–0.011 B/op** on every fast-book benchmark, with 0 collections in every fork. The reference books allocate 93–9,184 B/op. |

## Benchmark tape

`MessageTape`: 1,005,999 messages per pass. 48% passive limit adds, 3% crossing limit orders, 43% cancels, 5% reduces, against a prefilled book of 50 levels a side with 10 orders per level. The flow never has more than 5,000 orders resting. The tape restores the book at the end of each pass.

Measured while replaying the tape: at most 4,760 flow orders rest at once, spread over 50 levels a side. A removed order has a median of about 255 orders ahead of it, in queues of about 511 (the histogram uses power-of-two buckets, so read these as orders of magnitude); the synthetic ITCH day gives about 127 ahead in queues of 255. Only 0.07% of cancels hit an order that had already filled, against 20% on the old tape.

| | Fast book | `RefLinkedOrderBook` | Reference book |
|---|---|---|---|
| JMH `TapeBenchmark.replayOneMessage` | **30.0 ± 0.5 ns/msg** | 48.5 ± 2.9 ns/msg | 121.5 ± 5.1 ns/msg |
| Untimed replay (`gradlew latency`) | **31,437,518 msg/s** (31.8 ns) | | 6,236,545 msg/s (160.3 ns) |
| Allocation (JMH) | **0 B/op** | 97.0 B/op | 93.2 B/op |

### Per-message latency (HdrHistogram, `gradlew latency`)

Service time for 10,059,990 messages (10 passes). Values are quantised to 100 ns and include about 25 ns of `nanoTime` overhead; a p50 of 1 means most messages finished within one clock step.

Fast book:

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| limit add (passive) | 1 | 100 | 300 | 500 | 139,007 |
| limit add (crossing) | 100 | 200 | 400 | 1,100 | 30,815 |
| cancel | 1 | 100 | 300 | 700 | 139,775 |
| reduce | 1 | 100 | 300 | 600 | 105,215 |
| **all messages** | **1** | **100** | **300** | **600** | **139,775** |

Reference book:

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| limit add (passive) | 100 | 300 | 500 | 5,203 | 89,663 |
| limit add (crossing) | 100 | 500 | 800 | 3,201 | 30,207 |
| cancel | 300 | 600 | 1,000 | 10,207 | 1,868,799 |
| reduce | 100 | 600 | 900 | 4,703 | 55,423 |
| **all messages** | **100** | **600** | **900** | **7,903** | **1,868,799** |

Cancels are where the designs differ most. The reference book's 1.9 ms maximum is a G1 pause.

### Coordinated omission

The fast book at a fixed 100,000 messages/sec for 10 seconds, with one 50 ms stall injected halfway (standing in for a GC pause).

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| service time (from actual start) | 200 | 700 | 1,200 | 10,207 | 50,462,719 |
| response time (from intended start) | 200 | 7,503 | **40,894,463** | **49,545,215** | 50,495,487 |

The stall delayed about 5,000 messages. Measured from each message's actual start, it shows up as a single slow sample and p99.9 looks like 1.2 µs. Measured from when each message was due, the true p99.9 is 41 ms.

## Single operations (JMH)

`OrderBookBenchmark`: each operation leaves the book exactly as it found it, against the same prefilled book. Fast book pool 16,384 orders.

| Operation | Fast (ns/op) | `RefLinkedOrderBook` | Reference | Fast B/op | Reference B/op |
|---|---|---|---|---|---|
| `addPassiveThenCancel`: join the back of a level, cancel | **28.1 ± 1.0** | 52.3 ± 7.6 | 49.4 ± 3.7 | 0 | 192 |
| `cancelFromDeepQueueAndRejoin`: random cancel from a queue of 1,000 | **32.3 ± 1.1** | 75.2 ± 7.9 | 320.6 ± 48.9 | 0 | 216 |
| `crossOneOrderAndReplenish`: take one front order, replace it | **39.0 ± 3.1** | 40.7 ± 2.3 | 43.2 ± 3.2 | 0 | 192 |
| `sweepFiveLevelsAndReplenish`: 50 fills, 50 adds | **1,639.1 ± 105.4** | 2,024.9 ± 135.9 | 1,947.4 ± 125.7 | 0.011 | 9,184 |

With the pool sized to the book, the fast book wins every one of these. In the earlier measurements, taken with a 1,048,576-order pool, it lost three of the four.

## Finding: pool size decides whether the fast book wins

The same benchmarks on the fast book with a 1,048,576-order pool. The pool size also sets the id map's size.

| Fast book | Pool 16,384 | Pool 1,048,576 | Reference book |
|---|---|---|---|
| `addPassiveThenCancel` (ns/op) | **28.1 ± 1.0** | 89.7 ± 4.3 | 49.4 ± 3.7 |
| `cancelFromDeepQueueAndRejoin` (ns/op) | **32.3 ± 1.1** | 34.9 ± 0.6 | 320.6 ± 48.9 |
| `crossOneOrderAndReplenish` (ns/op) | **39.0 ± 3.1** | 108.2 ± 9.8 | 43.2 ± 3.2 |
| `sweepFiveLevelsAndReplenish` (ns/op) | **1,639.1 ± 105.4** | 3,516.5 ± 272.4 | 1,947.4 ± 125.7 |
| `TapeBenchmark.replayOneMessage` (ns/msg) | **30.0 ± 0.5** | 60.6 ± 2.3 | 121.5 ± 5.1 |
| Full-day replay (msg/s) | **21.56M** | 11.78M | 7.08M |

The worst-case pool costs 2-3× on everything that touches the id map, and inverts three of the four single-operation results against the TreeMap book. Only `cancelFromDeepQueueAndRejoin`, which is dominated by the reference book's queue scan, survives it.

- **The id map is bigger than the cache.** With a 1M-order pool, `LongIntMap` has 2,097,152 slots of 8-byte keys and 4-byte values: 24 MB, more than the 16 MB L3 cache. Mixing the key's bits sends each order id to an effectively random slot, so nearly every add, cancel and fill pays a main-memory read. At 16,384 orders the map is 393 KB.
- **The reference book stays in cache.** Its `HashMap` never holds more than a few thousand entries.
- **Crossing is still expensive.** Each fill touches an order's fields in seven separate pool arrays (id, qty, level, side, sequence, next, prev), plus the level arrays: many cache lines per order.

Possible follow-ups, none done yet:
- Pack each order's hot fields into one or two cache lines.
- Try huge pages to cut TLB misses.

## Touch search: bitset vs linear scan

`TouchSearchBenchmark`: cancel the best bid when the next bid is `gap` levels below, then restore it.

| Gap (levels) | Bitset (ns/op) | Linear scan (ns/op) |
|---|---|---|
| 1 | 31.5 ± 2.6 | **29.0 ± 0.5** |
| 100 | **33.3 ± 2.8** | 44.2 ± 1.3 |
| 10,000 | **105.0 ± 2.9** | 1,669.2 ± 81.8 |

The bitset bounds the worst case: 15.9× faster across a 10,000-level gap, and within noise of the scan when levels are adjacent.

## Id map: LongIntMap vs HashMap<Long, Integer>

100,000 live sequential ids. "Churn" adds the next id and removes the oldest; "get" looks up random live ids.

| | LongIntMap (MIX) | HashMap |
|---|---|---|
| churn | 31.7 ± 0.4 ns, **0 B/op** | **15.8 ± 0.9 ns**, 96 B/op |
| get | **9.2 ± 0.7 ns**, **0 B/op** | 9.3 ± 1.3 ns, 24 B/op |

`HashMap` still wins churn on sequential keys: a `Long` hashes almost to itself, so consecutive ids sit in neighbouring buckets and stay in cache. Its allocation is the price: 96 bytes per churn operation, which eventually means GC pauses.

### Hashing experiment

| | MIX, table for 100k | MIX, table for 1M | IDENTITY, table for 100k | IDENTITY, table for 1M |
|---|---|---|---|---|
| churn (ns/op) | **31.7 ± 0.4** | 36.0 ± 5.0 | 77,923.2 ± 8,725.8 | 86,306.6 ± 10,112.7 |
| get (ns/op) | 9.2 ± 0.7 | 10.5 ± 2.4 | **3.5 ± 0.2** | **3.5 ± 0.1** |

Without mixing, lookups get 2.6× faster, but removal becomes catastrophic, about 2,500× slower: 100,000 sequential live keys form one unbroken run of occupied slots, and backward-shift deletion scans to the end of that run on every remove. Mixing the key's bits is the right default for linear probing. This is why the guide recommends it, although its stated reason (long probe chains on insert) isn't where the cost shows up.

## Synthetic ITCH session

`gradlew itchSession` generates a full trading day (09:30 to 16:00, seed 20260914, default `FlowConfig`), writes it as ITCH 5.0, replays the file into the fast book once, then replays it again and checks it against the generator's reference book after every event. A single run, not a JMH measurement.

| | |
|---|---|
| File | 119,300,866 bytes |
| Messages | 3,976,273: 1,749,123 add, 81,667 execute, 218,413 cancel, 1,707,765 delete, 219,298 replace, 7 session |
| Flow | 3,941,931 Hawkes events, 13,275,981 shares traded, cancel-to-trade 23.6 : 1; price opens at $150.00, closes at $146.97 / $146.98; at most 5,000 resting orders |
| Generation (reference book matching, ITCH writing, recording the reference book's state for the check) | 1.44 s, **2,757,039 messages/sec** |
| Replay into the fast book, one cold run including JIT warm-up | 0.237 s, **16,766,921 messages/sec, 59.6 ns/message** |
| Check | 3,941,931 of 3,941,931 events matched on touch and order count; 3,942 full-depth snapshots (every 1,000 events) matched; 81,667 of 81,667 executions matched; 0 rejected messages, 0 referring to an unknown order; closing touch 146.97 / 146.98 with 4,998 resting orders; structure valid |

Zero unknown order references is expected by construction: the file starts from an empty book and was produced by the reference book the replay is compared with.

## Simulation: market maker P&L vs latency

Milestone 5. `gradlew latencySweep` replays the full-day session above six times with `SampleMarketMaker`:
- quotes 100 shares a side at the touch (one tick inside when the spread is 3+ ticks)
- has a position limit of 1,000
- earns a $0.0020/share maker rebate and pays a $0.0030/share taker fee

Latency is the same in both directions, with exponential jitter averaging 10% of it. P&L is marked to the mid at the close. The same session and seed are used for every run.

| One-way latency | P&L $ | Net fees $ | Volume | Fills | Orders | Orders filled | Max \|position\| |
|---|---|---|---|---|---|---|---|
| 0 | −3,781.59 | −397.98 | 207,247 | 2,622 | 3,612 | 62.3% | 1,098 |
| 10 µs | −6,431.85 | −351.33 | 213,661 | 2,656 | 3,887 | 59.3% | 1,099 |
| 100 µs | −6,439.15 | −353.03 | 213,261 | 2,655 | 3,888 | 59.2% | 1,099 |
| 1 ms | −6,736.92 | −347.83 | 210,217 | 2,628 | 3,828 | 59.2% | 1,098 |
| 10 ms | −7,678.18 | −320.73 | 192,000 | 2,442 | 3,509 | 58.2% | 1,099 |
| 50 ms | −9,350.76 | −231.18 | 148,416 | 1,951 | 2,838 | 53.7% | 1,097 |

Each replay of the 4M-message session with the strategy took 0.27–0.39 s, against 0.7–0.9 s before the pool was sized to the book. The P&L, fills and orders are unchanged: pool capacity doesn't affect any decision the simulation makes.

Negative fees are net rebates earned.

What it shows:
- **Latency costs money.** P&L falls at every step, from −$3,782 to −$9,351, and fewer orders fill. A slower maker's quotes stay in the book after the fair value has moved, so informed orders pick them off; and it joins queues later.
- **The maker loses even at zero latency.** Half of marketable orders trade towards the hidden fair value, so fills against this maker are adversely selected. A one-tick spread plus a rebate doesn't cover that. The strategy has no signal and no inventory skew; it only exists to exercise the simulator.
- **10 µs and 100 µs are almost identical.** Order events in this flow are milliseconds apart, so a 90 µs difference rarely changes which event a message lands after. The biggest step is from 0 to 10 µs: at zero latency the maker reacts before the next event without fail.
- **The position limit is soft.** It is checked when quoting, so orders already working can fill past it: max 1,099 against 1,000.

Caveats:
- One synthetic session and one seed.
- No market impact (see `SimulatedOrders`).
- No queue priority for our replaced orders.
- Mark-to-mid at the close.

The dollar amounts depend heavily on the generator's `informedProbability` and fair-value volatility. The direction of the latency effect is the result to rely on, not the size.

A bug found by this sweep: before the fix, a quote that filled while its cancel was in flight left that side waiting for an acknowledgement that could never match. The maker sent only 31–46 orders all day. `SampleMarketMakerTest.anOrderThatFillsWhileItsCancelIsInFlightDoesNotFreezeThatSide` covers it.

Before the generator had a fair value, the same sweep gave $205.10 at every latency: with a price that never moved, latency had nothing to act on.

## Threading: pipelined replay

Milestone 6. `gradlew pipelinedReplay` replays the full-day session file (3,976,272 messages delivered) into a fast book. It runs 3 warm-up runs, then 7 measured runs per mode, and checks every run's final book against the single-threaded one. In two-thread mode:
- the main thread walks the file's framing and publishes each message's offset into a 65,536-slot `SpscLongRingBuffer`
- a second thread decodes the messages and applies them to the book

| Mode | Median msg/s | Best msg/s | Median ns/msg |
|---|---|---|---|
| **single thread** | **22,551,055** | 24,235,468 | 44.3 |
| two threads, spin | 20,880,272 | 28,051,321 | 47.9 |
| two threads, yield | 21,116,574 | 22,146,270 | 47.4 |
| two threads, park | 17,727,188 | 19,237,388 | 56.4 |

**Two threads don't help file replay.** All three two-thread modes have a lower median than one thread.
- **Nothing to offload.** Walking the length prefixes is a few instructions per message, so moving it to another thread saves almost nothing. The book thread still decodes and applies every message, and now pays for a ring buffer hop as well.
- **Park is slowest.** `LockSupport.parkNanos(1)` sleeps for at least the scheduler's timer resolution every time the consumer finds the ring empty.
- **Where it would help.** The design pays off when the producer does slow, blocking work the book shouldn't wait on, such as reading a live feed from a socket. Here it is a demonstration of the single-writer principle, not a speedup, and none of the latency figures above use it.

## Coverage

`gradlew jacocoTestReport`: 225 tests, **75.4% line coverage** of the main source set (1,681 of 2,231 lines). The command-line entry points in `obs.app` have no unit tests; without them coverage is 94.3%. By package: `obs.core` 95.2%, `obs.mem` 99.3%, `obs.ref` 99.2%, `obs.feed` 93.1%, `obs.feed.itch` 95.9%, `obs.sim` 97.6%. Counting the benchmark code and JMH's generated classes as well, which no test runs, gives 37.6%.

## Reproducing

```
gradlew test                                                        # includes ZeroAllocationTest
gradlew jacocoTestReport                                            # coverage, build/reports/jacoco
gradlew itchSession                                                 # full-day synthetic ITCH: generate, replay, check every event
gradlew replayLatency "-PreplayArgs=fast"                           # also ref, refLinked, fast:1048576; run each several times
gradlew epsilonSmoke                                                # 200M messages under Epsilon GC
gradlew latency                                                     # tape percentiles and coordinated omission demo
gradlew pipelinedReplay                                             # single-thread vs two-thread ITCH replay
gradlew latencySweep                                                # market maker P&L at 0 / 10 us / 100 us / 1 ms / 10 ms / 50 ms
python tools/plot_pnl.py                                            # charts from the sweep CSVs (needs matplotlib)
gradlew jmh "-PjmhArgs=-prof gc TapeBenchmark OrderBookBenchmark TouchSearchBenchmark LongIntMapBenchmark"
gradlew jmh "-PjmhArgs=-prof gc -p impl=fast -p poolCapacity=1048576 TapeBenchmark OrderBookBenchmark"
gradlew jmh "-PjmhArgs=-prof gc -p hashing=MIX,IDENTITY -p tableEntries=100000,1048576 LongIntMapBenchmark.longIntMap"
```

JMH results are also written to `build/reports/jmh/results.json`.
