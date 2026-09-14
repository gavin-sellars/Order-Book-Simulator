# Benchmarks

Measured 2026-09-14 on the code at milestone 3. Every number below was measured on this machine; nothing is estimated.

## Environment

| | |
|---|---|
| Machine | ASUS ROG Zephyrus G14 GA402RJ (laptop) |
| CPU | AMD Ryzen 9 6900HS, 8 cores / 16 threads. Caches: L1 512 KB, L2 4 MB, L3 16 MB (totals reported by Windows) |
| Memory | 16 GB DDR5-4800 (2 × 8 GB) |
| OS | Windows 11 Home, build 26200, "High performance" power plan |
| JVM | Java HotSpot 25.0.4.1+1-LTS-5 |
| JMH | 1.37 |

### Caveats

- **Short JMH runs.** Each JMH run used `-f 1 -wi 3 -i 5 -w 1s -r 1s` (1 fork, 3 × 1 s warm-up, 5 × 1 s measurement) to keep the suite to about 3 minutes. The benchmarks' own defaults (2 forks, 5 + 10 iterations) are more reliable. Error bars are JMH's 99.9% confidence intervals.
- **Noise.** A laptop on Windows is noisy. The same benchmark measured 110 ns in one run and 199 ns in another (see [pool size](#finding-where-the-fast-book-loses)). Treat differences under about 2× with care.
- **Coarse clock.** `System.nanoTime()` on this machine costs 25.9 ns per call and only advances in 100 ns steps. The HdrHistogram per-message percentiles are therefore quantised to 100 ns and include timer overhead. JMH's averages are the precise per-operation numbers.
- **Warning from JMH.** JMH 1.37 prints a "terminally deprecated `sun.misc.Unsafe`" warning on JDK 25. It doesn't affect results.

## Zero allocation on the hot path

Proven three independent ways:

| Method | Result |
|---|---|
| `ZeroAllocationTest` (runs on every build): per-thread allocation counter across 10 tape replays | **0 bytes** for the fast book. The same measurement on the reference book reads millions of bytes. |
| `gradlew epsilonSmoke`: Epsilon GC (never collects), 512 MB heap | **200,006,080 messages replayed, 0 bytes allocated**, 21.2 s, 9,421,257 messages/sec |
| JMH `-prof gc`, `gc.alloc.rate.norm` | **≈ 0 B/op** on every fast-book benchmark. The largest reading, 0.03 B/op, comes from JMH's own per-iteration bookkeeping. The reference book allocates 87–9,184 B/op. |

## Realistic message mix

`MessageTape`: 1,111,111 messages per pass. 43% passive limit adds, 4% crossing limit orders, 48% cancels, 5% reduces, against a prefilled book of 50 levels a side with 10 orders per level. The tape restores the book at the end of each pass.

| | Fast book | Reference book |
|---|---|---|
| JMH `TapeBenchmark.replayOneMessage` | **114.1 ± 3.5 ns/msg** | 379.3 ± 83.1 ns/msg |
| Untimed replay (`gradlew latency`) | **9,107,034 msg/s** (109.8 ns) | 3,017,898 msg/s (331.4 ns) |
| Allocation (JMH) | ≈ 0 B/op | 87.4 B/op |

### Per-message latency (HdrHistogram, `gradlew latency`)

Service time for 11,111,110 messages (10 passes), with G1 and `-Xms2g -Xmx2g -XX:+AlwaysPreTouch`. Values are quantised to 100 ns and include about 26 ns of `nanoTime` overhead.

Fast book:

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| limit add (passive) | 100 | 400 | 500 | 3,101 | 150,015 |
| limit add (crossing) | 200 | 600 | 1,000 | 5,103 | 44,927 |
| cancel | 100 | 400 | 700 | 3,401 | 128,319 |
| reduce | 100 | 400 | 600 | 3,301 | 132,479 |
| **all messages** | **100** | **400** | **700** | **3,301** | **150,015** |

Reference book:

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| limit add (passive) | 100 | 300 | 500 | 4,001 | 1,534,975 |
| limit add (crossing) | 200 | 600 | 900 | 4,303 | 51,231 |
| cancel | 500 | 2,301 | 3,901 | 12,207 | 214,911 |
| reduce | 200 | 1,800 | 2,601 | 6,403 | 141,439 |
| **all messages** | **100** | **2,101** | **3,101** | **8,207** | **1,534,975** |

Cancels are where the designs differ most: the reference book's p99 cancel is 5.8× the fast book's, because `ArrayDeque.remove` scans the queue. The ~100–150 µs maxima in both books are most likely OS scheduling on a laptop; they haven't been profiled yet.

### Coordinated omission

The fast book at a fixed 100,000 messages/sec for 10 seconds, with one 50 ms stall injected halfway (standing in for a GC pause).

| ns | p50 | p99 | p99.9 | p99.99 | max |
|---|---|---|---|---|---|
| service time (from actual start) | 200 | 700 | 1,300 | 4,803 | 50,790,399 |
| response time (from intended start) | 200 | 1,900 | **41,189,375** | **49,840,127** | 50,790,399 |

The stall delayed about 5,000 messages. Measured from each message's actual start, it shows up as a single slow sample and p99.9 looks like 1.3 µs. Measured from when each message was due, the true p99.9 is 41 ms.

## Single operations (JMH)

`OrderBookBenchmark`: each operation leaves the book exactly as it found it, against the same prefilled book. Pool capacity 1,048,576 orders.

| Operation | Fast (ns/op) | Reference (ns/op) | Fast B/op | Reference B/op |
|---|---|---|---|---|
| `cancelFromDeepQueueAndRejoin`: random cancel from a queue of 1,000 | **38.2 ± 0.4** | 269.7 ± 11.2 | ≈ 0 | 216 |
| `addPassiveThenCancel`: join the back of a level, cancel | 110.3 ± 13.3 | **46.1 ± 1.3** | ≈ 0 | 192 |
| `crossOneOrderAndReplenish`: take one front order, replace it | 144.5 ± 14.3 | **43.0 ± 1.9** | ≈ 0 | 192 |
| `sweepFiveLevelsAndReplenish`: 50 fills, 50 adds | 4,453.6 ± 59.0 | **1,983.2 ± 32.3** | 0.03 | 9,184 |

## Finding: where the fast book loses

The fast book is slower than the TreeMap reference on three of the four single operations. Experiment: the same benchmarks on the fast book with a 4,096-order pool instead of 1,048,576. The pool size also sets the id map's size.

| Operation (fast book) | Pool 4,096 | Pool 1,048,576 | Reference |
|---|---|---|---|
| `addPassiveThenCancel` | **33.7 ± 2.9** | 198.9 ± 124.8 | 46.1 |
| `cancelFromDeepQueueAndRejoin` | 50.4 ± 4.0 | 45.3 ± 19.6 | 269.7 |
| `crossOneOrderAndReplenish` | 54.9 ± 6.6 | 227.4 ± 67.6 | 43.0 |
| `sweepFiveLevelsAndReplenish` | 2,246.7 ± 365.4 | 8,629.2 ± 2,326.4 | 1,983.2 |

The size of the book's preallocated structures, not the algorithm, explains most of the gap:

- **The id map is bigger than the cache.** With a 1M-order pool, `LongIntMap` has 2,097,152 slots of 8-byte keys and 4-byte values: 24 MB, more than the 16 MB L3 cache. Mixing the key's bits sends each order id to an effectively random slot, so nearly every add, cancel and fill pays a main-memory read.
- **The pool itself is probably not the cause.** It is also large (about 33 MB), but freed slots are reused last-in-first-out, so the slots in use stay in cache. This is an inference; the experiment changes both sizes at once.
- **The reference book stays in cache.** Its `HashMap` never holds more than about 2,000 entries, so all of its structures fit in cache despite the pointer chasing.
- **Crossing and sweeping still lose at 4,096.** Each fill touches an order's fields in seven separate pool arrays (id, qty, level, side, sequence, next, prev), plus the level arrays: many cache lines per order. The struct-of-arrays layout helps scans over one field, but matching reads most fields of each order it touches.

Where the fast book clearly wins: cancelling from deep queues (O(1) against O(n)), which is the dominant message in real flow, and the realistic tape overall (3.3×). It also has zero allocation and far better cancel tails.

Possible follow-ups, none done yet:
- Pack each order's hot fields into one or two cache lines.
- Size the id map to the expected live-order count rather than the pool's worst case.
- Try huge pages to cut TLB misses.

## Touch search: bitset vs linear scan

`TouchSearchBenchmark`: cancel the best bid when the next bid is `gap` levels below, then restore it.

| Gap (levels) | Bitset (ns/op) | Linear scan (ns/op) |
|---|---|---|
| 1 | 26.6 ± 0.2 | 32.5 ± 0.6 |
| 100 | 27.4 ± 1.0 | 41.4 ± 3.0 |
| 10,000 | **103.3 ± 2.8** | 1,834.4 ± 39.9 |

The bitset bounds the worst case: 18× faster across a 10,000-level gap, and no slower when levels are adjacent.

## Id map: LongIntMap vs HashMap<Long, Integer>

100,000 live sequential ids. "Churn" adds the next id and removes the oldest; "get" looks up random live ids.

| | LongIntMap (MIX) | HashMap |
|---|---|---|
| churn | 33.4 ± 0.5 ns, 0 B/op | **16.5 ± 1.6 ns**, 96 B/op |
| get | **7.5 ± 0.1 ns**, 0 B/op | 12.4 ± 9.6 ns, 24 B/op |

`HashMap` wins churn on sequential keys: a `Long` hashes almost to itself, so consecutive ids sit in neighbouring buckets and stay in cache. Its allocation is the price: 96 bytes per churn operation, which eventually means GC pauses.

### Hashing experiment

| | MIX, table for 100k | MIX, table for 1M | IDENTITY, table for 100k | IDENTITY, table for 1M |
|---|---|---|---|---|
| churn (ns/op) | 42.4 ± 12.1 | 58.4 ± 16.3 | 73,519.6 ± 2,804.1 | 76,595.6 ± 20,280.4 |
| get (ns/op) | 8.4 ± 2.2 | 12.5 ± 16.3 | **3.6 ± 0.6** | **3.5 ± 0.1** |

Without mixing, lookups get 2–3× faster, but removal becomes catastrophic: 100,000 sequential live keys form one unbroken run of occupied slots, and backward-shift deletion scans to the end of that run on every remove. Mixing the key's bits is the right default for linear probing. This is why the guide recommends it, although its stated reason (long probe chains on insert) isn't where the cost shows up.

## Synthetic ITCH session

Re-measured in milestone 5, after the generator gained its fair-value random walk; same machine. `gradlew itchSession` generates a full trading day (09:30 to 16:00, seed 20260914, default `FlowConfig`), writes it as ITCH 5.0, then replays the file into the fast book. The file replay runs with `-Xms2g -Xmx2g -XX:+AlwaysPreTouch` and G1; a single run, not a JMH measurement.

| | |
|---|---|
| File | 119,300,866 bytes |
| Messages | 3,976,273: 1,749,123 add, 81,667 execute, 218,413 cancel, 1,707,765 delete, 219,298 replace, 7 session |
| Flow | 3,941,931 Hawkes events, 13,275,981 shares traded, cancel-to-trade 23.6 : 1; price opens at $150.00, closes at $146.97 / $146.98 |
| Generation (reference book matching + ITCH writing) | 1.97 s, **2,017,469 messages/sec** |
| Replay into the fast book (memory-mapped file, `ItchReader` → `BookBuilder`) | 0.613 s, **6,491,688 messages/sec, 154.0 ns/message** |
| Check | 0 rejected messages; closing touch 146.97 / 146.98 with 4,998 resting orders, identical to the generator's reference book; structure valid |

The replay figure is lower than the 9.1M messages/sec tape replay above. It includes decoding ITCH from the mapped file, and the session's book holds up to 5,000 resting orders rather than about 1,000. A single end-to-end run like this also includes JIT warm-up.

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

Each replay of the 4M-message session with the strategy took 0.7–0.9 s. Negative fees are net rebates earned.

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

## Reproducing

```
gradlew test                                                   # includes ZeroAllocationTest
gradlew epsilonSmoke                                           # 200M messages under Epsilon GC
gradlew itchSession                                            # full-day synthetic ITCH: generate, replay, check
gradlew latencySweep                                           # market maker P&L at 0 / 10 us / 100 us / 1 ms / 10 ms / 50 ms
python tools/plot_pnl.py                                       # charts from the sweep CSVs (needs matplotlib)
gradlew latency                                                # HdrHistogram tables and coordinated omission demo
gradlew jmh "-PjmhArgs=-f 1 -wi 3 -i 5 -w 1s -r 1s -prof gc"   # quick JMH suite as run here (~3 min)
gradlew jmh "-PjmhArgs=-prof gc"                               # full defaults (~15 min)
gradlew jmh "-PjmhArgs=-p impl=fast -p poolCapacity=4096,1048576 OrderBookBenchmark"
gradlew jmh "-PjmhArgs=-p hashing=MIX,IDENTITY -p tableEntries=100000,1048576 LongIntMapBenchmark.longIntMap"
```

JMH results are also written to `build/reports/jmh/results.json`.
