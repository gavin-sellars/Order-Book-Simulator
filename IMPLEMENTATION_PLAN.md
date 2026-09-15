# OrderBookSim: Implementation Plan

Based on `order-book-guide.md`. The goal is one instrument on one venue: a correct L3 order book with zero allocation, tested against a reference, benchmarked, fed by **synthetic order flow encoded as Nasdaq ITCH 5.0 binary messages**, with a latency-aware simulation layer and a sample market maker.

> **Scope (revised 2026-09-14):** Real ITCH files and LOBSTER data are out of scope for now. A Hawkes-driven generator runs its own matching engine and writes a byte-accurate ITCH 5.0 stream. Everything downstream (reader, book builder, simulator, benchmarks) consumes that stream exactly as it would a real Nasdaq file, so adding real data later only means pointing the reader at a different file.

---

## 0. Environment and project setup

**What's on this machine now:** JDK 25.0.4.1 LTS (active on PATH; JDK 23 is also installed), git, no Maven or Gradle, `JAVA_HOME` unset, and the folder isn't a git repo yet.

| Decision | Choice | Reason |
|---|---|---|
| JDK | **JDK 25 LTS** (installed) | Current LTS. The FFM `MemorySegment` API is available if generated files grow past 2 GB. Set `JAVA_HOME` to `C:\Program Files\Java\jdk-25.0.4.1`, or rely on Gradle toolchain auto-detection. |
| Build | **Gradle (Kotlin DSL) with the wrapper checked in** | The `me.champeau.jmh` plugin gives a clean `src/jmh` source set, so benchmarks stay out of `main`. The wrapper means nothing has to be installed globally. |
| Tests | JUnit 5 + jqwik | Property-based tests with shrinking (guide Part 8) |
| Metrics | HdrHistogram | Guide Part 7 |
| Plotting | Write CSV, then a tiny Python/gnuplot script in `tools/` | Keeps the Java core free of dependencies |

Steps:
1. `git init`, add `.gitignore` (build/, .gradle/, *.itch, data/).
2. Create the Gradle wrapper (download a Gradle distribution once, run `gradle wrapper`, commit `gradlew*` and `gradle/`).
3. `settings.gradle.kts` sets up a single module. `build.gradle.kts` uses the `java`, `application` and `me.champeau.jmh` plugins, with toolchain `JavaLanguageVersion.of(25)`.
4. Test task: `jvmArgs("-ea")`, `useJUnitPlatform { includeEngines("junit-jupiter", "jqwik") }`.
5. Check dependency versions when setting up. The ones in the guide (HdrHistogram 2.2.2, JMH 1.37, jqwik 1.8.4) are a starting point.

### Source layout

```
src/main/java/obs/
  core/      OrderBook, OrderPool, TradeListener, BookListener, Side, PriceLadderConfig, BookValidator
  mem/       LongIntMap, SpscLongRingBuffer, PaddedLong, LongBitSet (touch bitset)
  feed/      MessageHandler, BookBuilder (feed -> book adapter), HawkesProcess, SyntheticFlowGenerator
  feed/itch/ ItchMessageType, ItchLayout (offsets/lengths as constants), ItchWriter, ItchReader,
             ItchEncoder (flow events -> ITCH), ItchDump (human-readable decoder CLI)
  sim/       SimClock, SimEvent, LatencyModel, Exchange, ShadowBook, SyntheticOrderTracker, SimRunner
  strategy/  Strategy, StrategyContext, SampleMarketMaker
  metrics/   LatencyRecorder, PnlTracker, FillStats, CsvWriter
  ref/       RefOrderBook
  app/       Main entry points: ReplayMain, SimMain, LatencySweepMain, ThroughputMain
src/test/java/obs/...   unit, property, differential, golden, and determinism tests
src/jmh/java/obs/bench/ JMH benchmarks
tools/                  plot_pnl.py, fetch_lobster_sample.md (instructions only)
data/                   (gitignored) LOBSTER/ITCH samples
```

Layering rule, enforced by a test: `core` and `mem` import nothing from the other packages. An ArchUnit test would do it, or a simple test that scans the imports of the source files.

---

## 1. Bugs and gaps in the guide's code to fix during implementation

The guide's snippets are illustrative, and several break if copied as-is. This is the most important section of the plan.

### Core book (Part 3)
1. **No range or tick validation.** If `toIndex(price)` is outside `[0, levels)`, you get `ArrayIndexOutOfBounds`. Prices below `basePrice` are worse: integer division rounds toward zero, so you get a *wrong* index instead of an error. Validate with `price >= base && price < base + levels*tick && (price-base) % tick == 0`, and reject bad prices through a status code, not an exception on the hot path.
2. **Market orders aren't supported.** Passing `Long.MAX_VALUE` as a price overflows `toIndex`. Add `addMarketOrder(id, side, qty)` that matches with limitIdx = `levels-1` for buys and `0` for sells, and never rests.
3. **Duplicate order IDs leak pool slots.** `idToSlot.put` overwrites the old mapping and the old slot is never freed. Check `get(id) != NOT_FOUND` first and reject.
4. **Feed replay must not match.** ITCH and LOBSTER adds are already post-match: the exchange has done the matching, and trades arrive as separate E/C messages. So the book needs two entry points:
   - `addLimitOrder` (matching engine mode: synthetic flow and strategy orders)
   - `addRestingOrder` (book-builder mode: link into the level, no matching)
   Also add `execute(id, qty)`, which reduces the order and fires a trade callback with the resting price, and `replace(oldId, newId, qty, price)`, which is a cancel plus a resting add and **loses queue priority**.
5. **The "never crossed" check doesn't apply to raw feed replay.** A feed-built book can be briefly locked or crossed. Assert it only in matching-engine mode. In replay mode, count crossed states and report them.
6. **`advanceTouch` worst case is O(levels)** when a side empties. Implement the `long[]` bitset (`LongBitSet.nextSetBit/prevSetBit` using `numberOfTrailingZeros/LeadingZeros`). Keep the linear scan behind a flag so the README can show the difference in a benchmark.
7. Add a per-order **arrival sequence number** (`long[] seq` in `OrderPool`). It costs 8 bytes per order and makes the queue-position check for synthetic orders O(1) (see §6).
8. Add depth accessors that don't allocate, for snapshots and golden tests: `levelPriceAt(side, n)`, `levelQtyAt(side, n)`, `levelCountAt(side, n)`, which walk out from the touch using the bitset.

### LongIntMap (Part 3)
9. `put` on an existing key throws "map full" when the map is at capacity. Check for the key before checking capacity.
10. The comment says the hash mix is "borrowed from SplitMix64", but it's actually part of MurmurHash3's `fmix64`. Use the full fmix64 (two multiply/xor-shift rounds) and fix the comment.

### SPSC ring buffer (Part 4)
11. **False sharing is still there.** `cachedReadSeq`, which the producer writes, and `cachedWriteSeq`, which the consumer writes, are plain fields next to each other in the same object. Move each one into the padded region of the side that owns it. For example, use a class hierarchy `Pad | producer fields (writeSeq, cachedReadSeq) | Pad | consumer fields (readSeq, cachedWriteSeq) | Pad` instead of separate `PaddedLong` objects. Also pad the ends of the backing array (for example, allocate `capacity + 2*PAD` and offset indices) so neighbouring objects' headers don't share the first and last cache lines.
12. Verify the actual layout with **JOL** (`org.openjdk.jol:jol-core`) in a test, and print it in the README. Field layout changed in JDK 15, so don't assume it.
13. The `poll(emptySentinel)` return value can collide with a real value. Document it, or use a `LongConsumer`-style `drain(handler, limit)`. Draining is also faster because it does one volatile write per batch.

### ITCH reader and writer (Part 5, now synthetic)
14. **Put the offsets in one place.** The writer and reader must share one `ItchLayout` constants class. If they each hardcode offsets, a bug in one can be hidden by the same bug in the other. The one-file 2 GB `MappedByteBuffer` limit doesn't matter for generated files of a few hundred MB. Keep the reader behind an interface so a `MemorySegment` version can be added when real files come back.
15. **Always emit an `R` (Stock Directory) message** for the synthetic ticker before any order messages, and have the reader resolve ticker → stock locate from it rather than hardcoding the filter. Real files work this way, so the code won't need to change later.
16. **Emit `S` system events** (`O` start of messages, `S` start of system hours, `Q` start of market hours, `M` end of market hours, `E` end of system hours, `C` end of messages) to bracket the session. The simulator uses Q/M as its trading window.
17. A partial message at EOF is silently dropped (`break`). Throw instead, since a synthetic file must never be truncated.
18. Offsets and lengths must still match the official Nasdaq TotalView-ITCH 5.0 spec, so the files are genuinely readable by third-party ITCH tools. Expected lengths (excluding the 2-byte length prefix): `S`=12, `R`=39, `A`=36, `F`=40, `E`=31, `C`=36, `X`=23, `D`=19, `U`=35, `P`=44. Check these against the PDF once and pin them in hand-built byte-fixture tests.

### Simulation layer (Part 6)
19. **SimClock has no tie-break.** Add a `long seq` to every event and compare `(time, seq)`. The guide mentions this but the code doesn't do it.
20. **Gaussian jitter can go negative.** Then `schedule` throws "event in the past", or order-entry messages overtake each other, which a TCP channel doesn't allow. Use a non-negative distribution (lognormal or shifted exponential) and **enforce FIFO per channel**: `deliver = max(lastDeliver + 1, now + delay)`.
21. **The guide's shadow-book claim is wrong for ITCH.** It says to "insert your synthetic orders and let normal matching handle them", but ITCH has no aggressor orders to match against. It only reports executions against specific resting orders. So synthetic fills have to be *inferred*: an execution against a real order that is **behind** the synthetic order at the same level, or at a worse price, means the synthetic order would have filled first. `QueuedOrder` as written is the L2 approximation. The L3 design is in §6.
22. The `Strategy` interface has no way to send orders. Add a `StrategyContext` with `sendLimit`, `cancel` and `now()`.

### Benchmarks and tests (Parts 7–8)
23. **The JMH benchmarks in the guide don't reach a steady state.** `addPassiveOrder` adds orders for 2 seconds per iteration and exhausts the 1<<20 pool. `aggressiveCross` empties the asks within a few calls and then just measures resting orders at 1,502,000. Every benchmark op has to leave the book the way it found it: add+cancel pairs, cross plus replenish, or a cycle through a pre-generated message array in which adds and cancels balance.
24. The HdrHistogram loop in Part 7 has the same pool-exhaustion problem.
25. `validate()` relies on `assert` and only checks the bid side. Write it with explicit `throw new IllegalStateException`, check **both sides**, and also check `pool.levelIdx/side` against the list each order is in, `levelCount >= 0`, and that the bitset matches `head != EMPTY`.
26. In the differential property test, `RefOrderBook.bestBid()` returns `null` while the fast book returns `Long.MIN_VALUE`, so `assertEquals` fails on an empty book. Normalise through an adapter. Compare **the full trade sequence and full depth**, not only the touch.

### Hawkes generator (Part 5)
27. The recursive intensity update in the guide tracks intensity but doesn't *generate* arrivals. Use **Ogata thinning**: between events the exponential kernel only decays, so the current λ is a valid upper bound, which makes thinning exact and cheap.
28. Cancels have to target orders that are still live. The generator keeps its own live-order set (an array plus an index map with swap-remove) so it can pick a random live order in O(1).

### LOBSTER (not in the guide's code; **deferred**, kept for when real data returns)
29. LOBSTER message files refer to orders submitted **before the file starts**, so executions and cancels arrive for IDs the book has never seen. Seed the initial book from orderbook row 0 before applying message 1, taking message 1 out of the snapshot. Apply unknown-ID messages to a placeholder "legacy" order per level. Otherwise the golden test fails on message 1.
30. Type 5 (hidden execution) doesn't change the visible book. Type 7 is a halt. Empty-level padding uses price ±9999999999 with size 0. Direction on an execution is the side of the **resting** order. Only the top N levels are in the file, so compare only those.

---

## 2. Milestone plan

Every milestone ends in a working, committed state with passing tests. Rough effort estimates follow the guide.

### Progress
- [x] **M0** (2026-09-14): git repo, Gradle 9.7.1 wrapper, JDK 25 toolchain, JUnit 6.1.3 + jqwik 1.10.1.
- [x] **M1** (2026-09-14): `obs.core` (`Side`, `OrderResult`, `TradeListener`, `Prices`), `RefOrderBook`, `PrintBookDemo`, 26 unit tests + a random-session property (never crossed, every share accounted for). Run with `.\gradlew.bat build run`.
- [x] **M2** (2026-09-14): `obs.mem` (`LongIntMap`, `LongBitSet`), `obs.core` (`OrderPool`, `OrderBook`, `BookValidator`, `Book` interface shared with `RefOrderBook`).
  - Tests: the shared behaviour tests run against both books; the fast book is structurally validated after every test.
  - Reference comparison: 2 × 1,000 random sessions (matching-only and mixed) comparing results, trades and full depth after every message, plus a seeded 1M-message run.
  - Also: `LongIntMap` vs `HashMap` and `LongBitSet` vs `java.util.BitSet` properties, and a layering test.
  - Planted-bug check: a touch-tracking bug, a reduce-quantity bug and a blank-slot map deletion were each caught.
  - Deferred to M3: linear-scan touch option for the bitset benchmark comparison.
- [x] **M3** (2026-09-14): measurement. Results, hardware and commands are in `docs/BENCHMARKS.md`.
  - Workload: `obs.workload` holds the standard prefilled book and a cancel-heavy `MessageTape`; a full replay restores the book exactly.
  - Metrics: HdrHistogram `LatencyRecorder`.
  - Benchmarks: four JMH classes (`OrderBookBenchmark`, `TapeBenchmark`, `TouchSearchBenchmark`, `LongIntMapBenchmark`), wired by hand as a `jmh` source set because the Gradle plugin predates Gradle 9.
  - Tasks: `gradlew jmh | latency | epsilonSmoke`.
  - `OrderBook.TouchSearch.LINEAR_SCAN` added for comparison, with all fast-book tests run for both searches.
  - Zero allocation proven three ways: `ZeroAllocationTest` (per-thread allocation counter, 0 bytes), Epsilon GC for 200M messages (0 bytes), and JMH `-prof gc` (≈0 B/op on every fast-book benchmark).
  - **Finding: the fast book is not faster everywhere.** It wins on deep-queue cancels (38 vs 270 ns) and on the realistic tape (114 vs 379 ns/message). It loses on single operations against a small book when the pool is 1M orders, because the id map is then ~24 MB of randomly accessed slots. With a 4,096-order pool, add+cancel falls to 34 ns (reference 46 ns), but crossing and sweeping are still slower than the reference.
  - Experiment: unmixed (`IDENTITY`) hashing makes lookups 2-3× faster but removals ~1,500× slower (clustering), so `MIX` stays.
  - Open question for M3 follow-up: pack each order's hot fields into fewer cache lines, and/or size the id map to the expected working set.
- [x] **M4** (2026-09-14): synthetic ITCH feed.
  - `obs.feed.itch`: `ItchLayout`, `ItchWriter`, `ItchReader`.
  - `obs.feed`: `MessageHandler`, `BookBuilder`, `HawkesProcess` (multivariate, Ogata thinning), `FlowConfig`, `SyntheticItchGenerator` (matches on `RefOrderBook`, writes A/F/E/X/D/U plus S and R).
  - `obs.app.ItchSessionMain` and the `gradlew itchSession` task.
  - Tests:
    - 10 byte-exact writer fixtures written by hand from the spec, plus reader fixtures for 'C' and 'P'.
    - Hawkes statistics: Poisson variance, stationary rates, clustering via the Fano factor.
    - Round trip: a 10-minute session (~100k events) with touch and order count compared after every event and full depth every 250 events, plus 25 random-seed sessions.
    - Market shape: cancel-to-trade > 10, two-sided > 99%, spread ≤ 3 ticks > 90%.
  - Full 390-minute session: 3,979,093 messages. Generation 1.84M msg/s, fast-book replay 6.17M msg/s, 0 rejected messages, and the final book equals the generator's.
  - Deviations from the plan:
    - Snapshots stay in memory in the test; no `.snap` sidecar.
    - Replaces are always passive, so the "crossing replace" path isn't needed.
    - The 10M-message check is a 4M-message full day, compared on its final state rather than after every event.
  - Known limits:
    - Resting orders sit at the 5,000 cap late in the session.
    - Fixed in M5: the price barely moved all day, because the planned fair-value random walk was missing. The generator now has one, plus informed marketable orders.
- [x] **M5** (2026-09-14): simulation layer. Results are in `docs/BENCHMARKS.md`.
  - `obs.sim`: `SimClock` (time + sequence tie-break), `LatencyModel` (base delay + exponential jitter, FIFO per channel), `SimulatedOrders` (L3 queue-position inference via arrival sequence numbers), `Simulation` (ITCH replay driving the clock, strategy and P&L).
  - `obs.strategy`: `Strategy`, `StrategyContext`, `SampleMarketMaker`.
  - `obs.metrics`: `PnlTracker` (integer price units, maker rebate / taker fee), `FillStats`.
  - `OrderBook` gained `nextArrivalSeq`, `priceOf` and `sideOf`.
  - Generator: `FlowConfig.fairValueVolatility` (default 150 = $0.015/√s) and `informedProbability` (0.5). Passive orders never rest on the wrong side of fair value.
  - Apps: `LatencySweepMain` (`gradlew latencySweep`, writes CSVs to `build/reports/sim`) and `tools/plot_pnl.py`.
  - Tests:
    - The guide's queue-position walkthrough as a literal test, plus ahead/behind/trade-through/replace cases.
    - Exact callback timings through a hand-written ITCH session.
    - Latency turning a takeable quote into a missed one.
    - Determinism (identical `Result` for identical inputs; latency changes it).
    - Market maker unit tests, including a regression test for a freeze bug the sweep exposed: a quote filling while its cancel was in flight left that side waiting forever.
  - Result: P&L falls monotonically with latency, from −$3,782 at 0 to −$9,351 at 50 ms, and the fill rate from 62% to 54%. The naive maker loses even at zero latency, because informed flow adversely selects it.
  - Deviations from the plan:
    - Live synthetic mode (strategy inside the generator's matching engine) not built; ITCH replay only.
    - The position limit is checked when quoting, so in-flight fills can exceed it by about one quote (max 1,099 against a 1,000 limit).
- [x] **M6** (2026-09-14): threading. Results are in `docs/BENCHMARKS.md`.
  - `obs.mem.SpscLongRingBuffer`:
    - Producer-written and consumer-written fields are in separate padded groups, fixing the guide's shared-line cached sequences.
    - The backing array is padded at both ends.
    - Adds a batch `drain` alongside `offer`/`poll`.
  - `ItchReader` is split into `scan` (framing, on the producer thread) and `deliver` (decoding, on the book thread); `replay` uses both on one thread.
  - `obs.feed.PipelinedReplay` passes message offsets between the threads, with SPIN / YIELD / PARK idle strategies. `obs.app.PipelinedReplayMain` / `gradlew pipelinedReplay` compares the modes.
  - Tests:
    - Ring semantics, including wrap-around, drain limits and a throwing consumer.
    - 20M-value two-thread stress for `poll` and `drain`.
    - JOL layout check (`jol-core` 0.17, test only; it prints a JDK 25 `sun.misc.Unsafe` deprecation warning).
    - Pipelined replay builds the same book as single-threaded for every idle strategy and ring size, and a book-thread failure reaches the caller.
  - **Result: no speedup, as the guide warns for file replay.** Full-day session, median of 7:
    - single thread 9.04M msg/s
    - two threads, spin 8.88M
    - two threads, yield 9.11M
    - two threads, park 3.33M
  - The parsing half is too cheap to be worth handing off; it only shifts work onto a second core.
  - The ring buffer is kept for a live feed, where the producer would be blocked on network I/O.
- [ ] M7: polish (README)

### M1: Reference book (1 evening)
**Files:** `ref/RefOrderBook.java`, `app/PrintBookDemo.java`, `test/ref/RefOrderBookTest.java`
- Copy the guide's `RefOrderBook`. Add `addMarketOrder`, `execute`, `replace`, `addResting`, duplicate-ID rejection, and a `depth(side, n)` snapshot.
- A `BookFormatter` at the edge of the system turns long prices into strings.
- Unit tests: rest only, exact fill, partial fill, multi-level sweep with price improvement (the guide's Part 0 example as a literal test), sweep and rest the remainder, cancel from the middle of a queue, reduce keeps priority, replace loses priority, cancel of an unknown ID is a no-op.
- **Done when** the demo prints the Part 0 book and all the tests pass.

### M2: Fast core (1 week)
**Files:** `mem/LongIntMap`, `mem/LongBitSet`, `core/OrderPool`, `core/OrderBook`, `core/TradeListener`, `core/BookValidator`
Build order:
1. `LongIntMap` and its tests: put/get/remove, overwrite, a clustered-collision scenario, a remove that wraps around the table end, and a jqwik property against `HashMap<Long,Integer>` with random operations. The remove cases are where the bugs will be.
2. `OrderPool` and its tests: allocate/release/reuse, exhaustion throws, liveCount.
3. `LongBitSet` and its tests: next/prev set bit across word boundaries, at index 0, at the last index, when empty.
4. `OrderBook` with every fix from §1 (1–8). Return codes: `ACCEPTED, REJECTED_PRICE, REJECTED_DUP_ID, REJECTED_QTY`.
5. `BookValidator` (fix 25). It sits in the `core` package so it can reach internal state through package-private accessors.
6. **Differential tests** (`test/core/DifferentialTest`):
   - jqwik `@Provide` produces message sequences: weighted adds (passive and crossing), cancels of live and dead IDs, reduces, market orders, replaces. Prices come from a narrow band so crossings actually happen.
   - After every message: validate the fast book, compare touch, depth (all levels) and the trade list against `RefOrderBook`.
   - Also a seeded long-run test with 1M messages and validation every 1,000 messages.
- **Done when** 2,000+ jqwik tries at around 500 messages each, plus the 1M-message run, all agree. Don't move on until they do.

### M3: Measurement (2 days)
**Files:** `metrics/LatencyRecorder`, `src/jmh/.../OrderBookBenchmark`, `LongIntMapBenchmark`, `SpscBenchmark` (added in M6), `RefVsFastBenchmark`, `app/LatencyHistogramMain`
- JMH benchmarks designed for a steady state (fix 23): `addPassiveThenCancel`, `cancelFromMiddleOfDeepQueue`, `crossOneLevelAndReplenish`, `sweepFiveLevelsAndReplenish`, `realisticMix` (cycles a pre-generated array, about 90% cancels).
- Run the same benchmarks against `RefOrderBook` too. That gives the headline "N× faster" comparison.
- `-prof gc`: `gc.alloc.rate.norm` must be 0 on every fast-book benchmark. Also run under `-XX:+UseEpsilonGC -Xmx256m` as a smoke test (the guide's Appendix C).
- `LatencyRecorder` wraps HdrHistogram. It has a mode that measures from the **intended** send time at a fixed rate, which avoids coordinated omission. Report p50/p99/p99.9/p99.99/max as a table.
- Show a bitset-touch vs linear-scan benchmark on a gapped book.
- **Done when** the README table is filled in with hardware, JVM and flags, and allocation is 0 bytes/op.

### M4: Synthetic ITCH feed (1 week)
**Files:** `feed/HawkesProcess`, `feed/SyntheticFlowGenerator`, `feed/itch/*`, `feed/MessageHandler`, `feed/BookBuilder`, `app/GenerateItchMain`, `app/ReplayMain`, `app/ThroughputMain`

This used to be "real data". It now produces the data instead. The pipeline is:

```
HawkesProcess ──arrivals──▶ SyntheticFlowGenerator ──intents──▶ Generator matching engine (RefOrderBook)
                                                                     │ adds / fills / cancels / replaces
                                                                     ▼
                                                              ItchEncoder ──▶ ItchWriter ──▶ session.itch
                                                                                                │
             OrderBook (book-builder mode) ◀── BookBuilder ◀── ItchReader ◀────────────────────┘
```

1. **`HawkesProcess`** (fix 27): a multivariate exponential-kernel Hawkes process simulated with Ogata thinning, with a seeded `SplittableRandom`. Four event streams, each with its own λ₀, and a cross-excitation matrix α (e.g. a marketable order excites cancels and new passive orders on the opposite side):
   - passive limit add (at or behind the touch)
   - marketable order (crosses the spread)
   - cancel (full delete)
   - partial cancel or replace
   The time unit is nanoseconds since midnight. The session starts at 09:30:00 and ends at 16:00:00, bracketed by the `S` events from fix 16.
2. **`SyntheticFlowGenerator`** turns each arrival into an *intent* with a side, price and size:
   - Passive price offset from the touch uses a geometric distribution in ticks, so most orders land at the touch or near it.
   - Size uses round lots (100) with an occasional odd lot or large order.
   - Cancels pick a random live order in O(1) with the array plus swap-remove index (fix 28). A tunable bias towards recent orders is closer to real behaviour.
   - The mix is tuned so the **cancel-to-trade ratio is above 10:1**, and the generator reports the realised ratio at the end.
   - A slow random-walk "fair value" anchors prices, so the book drifts instead of collapsing to one level. Bounds keep the price inside the ladder.
3. **The generator runs a matching engine** (`RefOrderBook`), because ITCH describes what the exchange *did*, not what participants *sent*:
   - Passive add, doesn't cross → `A` (and sometimes `F` with a fake MPID).
   - Marketable order → one `E` per resting order it hits, carrying that order's ref and a unique increasing match number. If a remainder rests, an `A` for the remainder, after the `E`s. An aggressor that fully fills produces **no** `A` message, which is exactly how real ITCH looks.
   - Full cancel → `D`. Partial cancel → `X`.
   - Replace → `U` with a new order ref: new price and/or size, loses priority. If the replacement would cross, emit `D` for the old order and treat it as a new marketable order (`E`s then an `A`), so no `U` ever produces a crossed book.
   - Order refs, tracking numbers and match numbers all increase monotonically. `stockLocate` comes from the `R` message.
   - Using the **reference book** as the generator's engine keeps the generator independent of the fast `OrderBook`. The round-trip test (step 6) then really checks two separate implementations against each other.
4. **`ItchEncoder` / `ItchWriter`:** a big-endian `ByteBuffer` over a reusable direct buffer, flushed to a `FileChannel`. The 2-byte length prefix matches the BinaryFILE framing Nasdaq uses. Timestamps are 6-byte big-endian ns since midnight, and prices are 4 implied decimals (`$150.01` → `1500100`, tick = 100). Alpha fields (stock, MPID) are space-padded ASCII. Writing doesn't have to be free of allocation, but it shouldn't allocate per message.
5. **`ItchReader` / `BookBuilder`** (fixes 4, 14–17): reads with absolute offsets and no allocation, filters by locate, dispatches to `MessageHandler`. `BookBuilder` drives the fast `OrderBook` through `addRestingOrder / execute / reduce / cancel / replace`. `ItchDump` prints messages as text for debugging (`java -cp ... obs.app.ItchDump session.itch | head`).
6. **Round-trip golden test** (`test/feed/ItchRoundTripTest`). This replaces the LOBSTER golden test as the independent correctness check:
   - While generating, the generator takes a depth snapshot of its `RefOrderBook` every K messages and stores it in memory or a side file.
   - Replaying `session.itch` through `ItchReader → BookBuilder → OrderBook` must give **identical depth at the same message indices**. The replayed `E` stream must also reproduce the generator's trade list exactly: price, size, resting ref and match number.
   - A jqwik property repeats this on many small seeded sessions (a few thousand messages each) so shrinking works. A single large seeded session (10M+ messages) runs as a slow test.
7. **Byte-fixture tests** (fix 18): hand-assemble one message of each type as a `byte[]` literal from the spec layout, and check that `ItchWriter` produces exactly those bytes and `ItchReader` decodes exactly those fields.
8. **Hawkes statistical tests** (seeded): the empirical event rate is within tolerance of the stationary rate `(I − α/β)⁻¹ λ₀`, and the variance of counts in fixed windows is greater than a Poisson process with the same mean would give (evidence of clustering).
9. `GenerateItchMain` CLI with `--seed --duration --lambda0 --alpha --beta --ticker --out`, which prints a summary (message counts by type, cancel-to-trade ratio, trades, final spread). `ThroughputMain` replays a generated full session and reports messages/sec, validating once at the end.
- **Done when** the round-trip test passes with zero mismatches on a 10M+ message session (record the count for the README), fixtures pass for every message type, and the replay throughput is recorded.

### M5: Simulation layer (1 week)
This is the part of the project that makes it quant work rather than a data structures exercise. Details are in §6.
**Files:** `sim/SimClock`, `sim/LatencyModel`, `sim/Exchange`, `sim/SyntheticOrderTracker`, `sim/SimRunner`, `strategy/Strategy`, `strategy/StrategyContext`, `strategy/SampleMarketMaker`, `metrics/PnlTracker`, `metrics/FillStats`, `app/SimMain`, `app/LatencySweepMain` (Hawkes and flow generation already exist from M4)
1. `SimClock` with the `(time, seq)` ordering and pooled or reused event objects where it's easy. The sim layer doesn't have to allocate nothing, but it should be light.
2. `LatencyModel` with non-negative jitter and FIFO per channel (fix 20), seeded.
3. Two ways to feed the simulation, sharing one `Strategy`:
   - **ITCH replay:** read a generated `session.itch` and use the queue-position inference in §6.
   - **Live synthetic:** `SyntheticFlowGenerator` drives the book directly in matching-engine mode, so strategy orders really interact with the flow.
4. `Exchange` holds the book, receives feed events and strategy orders scheduled on the clock, and publishes book updates with market-data delay.
5. `SyntheticOrderTracker` handles queue position with L3 data (§6).
6. `PnlTracker`: cash, inventory, mark-to-mid P&L, realised and unrealised, and optional maker rebate and taker fee per share. `FillStats`: orders sent, filled, fill rate, average queue wait.
7. `SampleMarketMaker`: quotes one tick inside the spread (or joins the touch when the spread is one tick), fixed size, cancels and requotes when the touch moves, and skews or stops a side when inventory goes past a limit.
8. `LatencySweepMain` runs the same seed and data at 10 µs, 100 µs, 1 ms and 10 ms, and writes `pnl_vs_latency.csv` plus a P&L time series for each run. `tools/plot_pnl.py` makes the charts.
- **Done when** the P&L curve, fill rate and latency sweep chart exist, and the determinism test passes (see M7/§4).

### M6: Threading (3 days, optional)
**Files:** `mem/SpscLongRingBuffer` (with fixes 11–13), `app/PipelinedReplayMain`, `bench/SpscBenchmark`
- Messages are packed into longs. Pass a *file offset* (long) through the ring and let the consumer read fields straight from the shared read-only `MemorySegment`, so there is no encoding cost.
- Wait strategy is configurable: spin with `Thread.onSpinWait`, yield, or park.
- Tests: a two-thread stress test pushing 100M values checks order and that nothing is lost or duplicated; JOL checks the layout; the Epsilon smoke test.
- Measure pipelined against single-threaded replay and **report honestly**. For file replay the single-threaded version may win.

### M7: Polish (2 days)
- README following the guide's list: description, benchmark table near the top, architecture diagram, design decisions and the alternatives rejected, where the design loses, how to run it in three commands or fewer.
- A determinism test in CI: run `SimMain` twice on the same seed and data, hash all trades, fills and snapshots with a streaming SHA-256, and assert the hashes are equal.
- Optional GitHub Actions workflow: build, unit and property tests (golden tests skip without data).

---

## 3. Key interfaces (to lock in early)

```java
// core
public interface TradeListener { void onTrade(long aggressorId, long restingId, long price, int qty, byte aggressorSide); }
public interface BookListener  { void onTopOfBookChanged(long bid, long bidQty, long ask, long askQty); } // optional, fired only on change

public final class OrderBook {
  int addLimitOrder(long id, byte side, long price, int qty);   // matching mode
  int addMarketOrder(long id, byte side, int qty);               // returns unfilled
  int addRestingOrder(long id, byte side, long price, int qty);  // book-builder mode
  boolean cancel(long id);
  boolean reduce(long id, int by);
  boolean execute(long id, int qty);                             // feed-driven fill
  int replace(long oldId, long newId, long price, int qty);      // loses priority; price-then-qty like the adds
  long bestBid(); long bestAsk(); long bidQtyAt(long px); long askQtyAt(long px);
  int depth(byte side, int n, long[] pxOut, long[] qtyOut);      // no allocation
  // package-private: orderSeq(slot), slotOf(id), levelHead... for validator/tracker
}

// feed
public interface MessageHandler {
  void onAdd(long ts, long ref, byte side, int qty, long px);
  void onExecute(long ts, long ref, int qty, long execPxOrMinus1);
  void onCancel(long ts, long ref, int qty);
  void onDelete(long ts, long ref);
  void onReplace(long ts, long oldRef, long newRef, int qty, long px);
  void onHiddenTrade(long ts, byte side, int qty, long px);
  void onSystemEvent(long ts, byte code);
}

// strategy
public interface StrategyContext { long now(); long sendLimit(byte side, long px, int qty); void cancel(long clientId); }
public interface Strategy {
  void init(StrategyContext ctx);
  void onBookUpdate(long t, long bid, long ask, long bidQty, long askQty);
  void onTrade(long t, long px, int qty, byte aggressorSide);
  void onOwnFill(long t, long clientId, long px, int qty);
  void onOwnCancelAck(long t, long clientId);
  void onOrderRejected(long t, long clientId, int reason);
}
```

---

## 4. Test matrix

| Layer | Test | Kind |
|---|---|---|
| mem | LongIntMap vs HashMap | jqwik property |
| mem | LongBitSet boundaries | unit |
| mem | SPSC ordering and no loss, JOL layout | stress, layout |
| core | Part 0 scenarios, edge cases, rejections | unit |
| core | Fast vs Ref: trades, depth, touch after every message | jqwik differential |
| core | Validator after every message; never crossed in matching mode | property |
| core | Zero allocation | JMH `-prof gc` and an Epsilon run |
| feed | ITCH byte fixtures for each message type (writer and reader) | unit |
| feed | Generator's RefOrderBook depth and trades == ITCH replay into fast OrderBook | round-trip golden, jqwik + 10M slow run |
| feed | Generated stream is well-formed: R before orders, S brackets, refs/match numbers increasing, no E/X/D/U for unknown refs | property |
| feed | Hawkes: empirical rate ≈ λ₀/(1−α/β); clustering (variance > Poisson) | statistical, seeded |
| sim | Clock tie-break ordering; FIFO per channel under jitter | unit |
| sim | Queue-position scenarios (guide's Part 6 walkthrough as a test) | unit |
| sim | Same seed gives the same output hash | determinism |
| arch | core/mem have no outward imports | architecture |

---

## 5. Synthetic ITCH notes
- **Price convention:** 4 implied decimals everywhere, both in ITCH and in the core book. The tick is 100 ($0.01). The synthetic ticker `SYNTH` (8-char space-padded) starts at $150.00, and the ladder covers $50.00–$250.00 = 20,000 levels, the same as the guide.
- **Fields that don't matter for the book** (tracking number, `R` classification fields, MPID) get fixed, valid-looking values so third-party ITCH parsers accept the file.
- **Session file layout:** `S O`, `R`, `S S`, `S Q`, order flow, `S M`, `S E`, `S C`. Keep one locate ID (1) per file. A multi-ticker file is a later extension.
- **Keep the generator's in-memory snapshots out of the `.itch` file.** They go in a sidecar (`session.snap`, simple fixed-width binary) so the `.itch` stays pure spec.
- Treat any add outside the ladder as a counted rejection, not a crash. The generator's price bounds should keep this at 0; assert it in tests.
- **Later, for real data:** when real files come back, only the reader needs to change (`MemorySegment` for >2 GB, gunzip, `P` messages), plus the LOBSTER work in fixes 29–30. Nothing downstream of `MessageHandler` changes.

---

## 6. Queue position design with L3 data (replaces the guide's shadow-book claim)

The simulation assumes no market impact. Historical orders are never removed because of synthetic orders.

- Synthetic orders are **not** inserted into the historical book's lists. Liquidity from real orders must stay exactly as it was historically, or the golden-file consistency breaks. They live in `SyntheticOrderTracker`, keyed by `(side, levelIdx)`, and each one records:
  - `arrivalSeq`: the value of the book's global sequence counter when the order reached the exchange (after order-entry latency)
  - `qtyAhead`: the level's quantity at arrival
  - `remaining`
- **Real order event at a level with synthetic orders** (O(1) to check, because synthetic orders are rare):
  - Cancel/reduce of a real order with `seq < arrivalSeq`: it was ahead, so `qtyAhead -= qty`. If `seq > arrivalSeq` it was behind, so no change. The data is L3, so this is exact, with no pessimistic assumption needed.
  - Execute of a real order with `seq < arrivalSeq`: `qtyAhead -= qty`.
  - Execute of a real order with `seq > arrivalSeq`, at the same price: history filled someone behind us, so **we would have filled first**. Fill `min(remaining, qty)`.
  - Execute at a price that trades *through* our level (for a synthetic bid: a real bid executed at a price **below** ours; for a synthetic ask: a real ask executed **above** ours): every resting order at our level would have been hit first, so fill up to the executed quantity.
  - `replace` gives the real order a new seq, which moves it to the back. That's handled automatically.
- **Marketable synthetic order at arrival:** fill against the arrival-time book walking out from the touch. Keep a `consumedAtLevel` counter per level so the strategy can't take the same historical liquidity twice. Reset it when the level's real quantity changes. Document this as an approximation.
- **Matching-engine mode** (synthetic Hawkes flow, no historical constraint): synthetic orders really can be inserted into the book and matched normally. The guide's shadow-book idea is valid there. The same `Strategy` code runs in either mode.
- Double-count protection: a strategy fill caused by an execute doesn't reduce the historical order. That is the no-impact assumption, and the README has to say so.

---

## 7. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Differential tests pass because both books share a bug | Hand-written Part 0 scenario tests. The generator's semantics (what an ITCH message *means*) are also reviewed by hand against Part 0. |
| Writer and reader share the same wrong offset, so the round trip passes but the file isn't real ITCH | Byte fixtures assembled independently from the spec PDF, not from `ItchLayout` |
| Synthetic flow is unrealistic (book collapses, one-sided, never trades) | Generator summary stats as test assertions: spread mostly 1–3 ticks, both sides non-empty > 99% of the time, cancel:trade > 10:1 |
| JMH numbers on Windows laptop are noisy | State the hardware honestly; if possible, also run on Linux (WSL2 is acceptable, but say it's WSL) |
| Sim-layer scope creep | One strategy, one ticker, one sweep chart. Stop there. |
| Gradle picks up JDK 23 instead of 25 | Toolchain pinned to 25 in `build.gradle.kts`; set `JAVA_HOME` |

---

## 8. Definition of done (whole project)
- [ ] All unit, property, differential and determinism tests green; the ITCH round-trip test green on a 10M+ message session, with the count recorded
- [ ] JMH: 0 bytes/op on hot paths; the Epsilon run completes
- [ ] Benchmark table (fast vs ref, p50–max) with hardware and JVM flags
- [ ] Synthetic ITCH generation rate and full-session replay throughput recorded
- [ ] Sample market maker P&L curve, fill rate, and P&L-vs-latency chart
- [ ] README with architecture, design decisions, rejected alternatives, limitations (no impact, fixed ladder, fixed pool), and a three-command run
