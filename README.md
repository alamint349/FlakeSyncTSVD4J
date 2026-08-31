# Applying TSVD4J to FlakeSync-Localized Regions

**Progress report**
Md Al Amin — 30 August 2026

---

## 1. Summary

I integrated TSVD4J's thread-safety-violation (TSV) detection into the FlakeSync
tool so that concurrency analysis can be restricted to the code regions FlakeSync
localizes, and evaluated it on three flaky tests from FlakeSync's own subject list.

Two questions were posed:

- **RQ1** — Can targeted TSV analysis separate FlakeSync-repairable synchronization
  issues from genuine concurrency bugs?
- **RQ2** — Does restricting the analysis to FlakeSync-localized regions provide
  runtime or scalability benefit over whole-program analysis?

**RQ2 is answered, with a negative result and a mechanism.** Scoping the analysis
to FlakeSync's region is ~2.7× faster but finds nothing, and this is not a coverage
problem: instrumenting the exact classes that contain the conflicting accesses
still finds nothing. TSVD4J's detection power comes from the volume of delay it
injects across the program, not from observing the right locations. The speedup and
the sensitivity loss are the same effect.

**RQ1 is currently blocked.** The discriminator requires comparing TSV output before
and after FlakeSync's repair, and none of the three subjects produced a usable
post-repair condition — each for a different reason, described in §6. The pattern
across them suggests a structural reason the question is hard, discussed in §7.2.

---

## 2. What was built

Both tools have the same architecture: a `premain` Java agent doing ASM bytecode
rewriting, driven by a Maven plugin that rewrites the Surefire configuration and
re-runs `test`. Both use group ID `edu.utexas.ece` and both shade ASM 9.0 to the
same relocated package `agent.org.objectweb.asm`.

Running them as two stacked `-javaagent` flags was rejected: the identical shading
coordinates mean one jar's ASM silently wins for both agents, and neither tool's
blacklist excludes the other's classes, so each would instrument the other's
transformer.

Instead, TSVD4J's analysis was merged into `flakesync-core` as a new agent mode.

| Component | Change |
|---|---|
| `flakesync-core` | `edu.utexas.ece.tsvd4j.agent.*` copied in (8 classes) |
| `blacklist.txt` | `edu.utexas.ece.tsvd4j` added — required, or the agent instruments TSVD4J's own `Proxy` and recurses |
| `Agent.java` | New `TSV_DETECTION` mode; dispatches to `ClassTracer`, optionally chained with FlakeSync's delay injector |
| `TsvScope.java` | New — parses any FlakeSync location artifact into a class-name scope set; also counts classes considered vs. instrumented |
| `SurefireExecution` | New `createTsvDetectionExec(...)` factory |
| `TsvDetectMojo` | New goal `tsvdetect`; repeats N randomized runs, writes per-run CSVs plus a manifest with wall-clock and outcome |
| `tools/aggregate_tsv.py` | Unions pair sets across runs, prints saturation curve and set differences |

Because delay injection and TSV analysis are both `ClassVisitor`s taking a delegate,
they compose. Delay injection is placed outer so the TSV tracer records unshifted
line numbers, which keeps pair endpoints comparable against FlakeSync's location
files.

---

## 3. Validation of the port

A minimal subject (`dataRace-Example`) was written with two deliberate races: an
unsynchronized `int` field and an unsynchronized `ArrayList`.

| Configuration | Pairs found |
|---|---|
| Stock `mvn tsvd4j:tsvd4j` | `Counter\|16`, `Counter\|add\|25` |
| Merged tool, unscoped | identical |
| Merged tool, scoped to 1 of 4 classes | both pairs retained |

The reported line numbers are exact (16 is the field update, 25 is the list
mutation), so pair endpoints can be mapped onto FlakeSync's location files without
an offset correction.

Note that pairs are printed in two formats: field races as `Class|line`, API races
as `Class|method|line`.

---

## 4. Defects found and fixed

Four issues were found during integration. The first three were in my integration
code; the fourth is a property of the existing tool worth recording.

1. **The `field` and `api` system properties are mutually exclusive selectors, not
   additive flags.** `ClassTracer` defaults both detectors on; setting `field`
   disables `api` and vice versa. My initial "both" mode set both properties and
   therefore silently ran field-only, halving the findings. "Both" means setting
   neither.

2. **The scope parser took the last comma-separated field.** `BarrierPoints.csv`
   has four fields ending in a numeric threshold, so the barrier point's class was
   discarded and the string `"1"` was added to the scope instead. Replaced with a
   regex that extracts every fully-qualified class token anywhere on the line,
   which handles all of FlakeSync's artifact formats uniformly.

3. **The agent dispatches on a single `agentmode`.** `TSV_DETECTION` therefore
   *replaced* delay injection rather than composing with it, so the delayed
   condition was silently identical to the undelayed one. Fixed by chaining the two
   `ClassVisitor`s.

4. **The `clean_*` run cache masks failures across configuration changes.** Cached
   passing outcomes are reused, so a stage can report "no critical points found"
   when the underlying test does fail. On the Uniffle subject this produced an empty
   `CriticalPoints.csv` that resolved immediately once the cache was cleared. All
   reported runs clear `clean_*` and `fail_*` beforehand.

A related operational note: subjects must be checked out at the commits pinned in
`testscripts/input/inputs.csv`. On current `main` the Uniffle bug is fixed upstream
and nothing reproduces.

---

## 5. Experimental setup

- JDK 21.0.10, Ubuntu 24.04, single machine
- JaCoCo disabled (`-Djacoco.skip=true`) so that only the FlakeSync agent transforms
  classes; JaCoCo otherwise fails to instrument JDK internals and adds a third
  transformer to the chain
- N = 3 runs per configuration (see §8 — this is below what the randomization warrants)

Conditions:

| Label | Scope | FlakeSync delay | Repair applied |
|---|---|---|---|
| T0 | whole program | none | no |
| T1 | whole program | yes | no |
| T2 | whole program | yes | yes |
| C2 | FlakeSync region | yes | no |
| C2+ | region + classes containing the pairs | yes | no |

---

## 6. Results

### 6.1 Apache Uniffle — `GrpcServerTest#testGrpcExecutorPool`

Commit `625377c`. Full pipeline completed; flakiness reproduced (`expected: <1.0>
but was: <0.0>` at a 200 ms delay); both patches generated.

| Scope | Classes instrumented | Pairs | Test time |
|---|---|---|---|
| Whole program | 82 | 1 (in 2 of 3 runs) | 36.8 s |
| FlakeSync region | 2 | 0 | 0.9 s |
| Region + `GRPCMetrics` | 3 | 0 | 0.9 s |

The single pair was `io/prometheus/client/DoubleAdder|179` — a self-pair inside a
third-party striped-counter class. `DoubleAdder` is built on deliberately
unsynchronized per-cell CAS writes; the access pattern is by design, so this is
almost certainly a false positive.

The actual failure is visible in the source: `GrpcThreadPoolExecutor.afterExecute`
decrements the metrics gauge only after a task completes, and the test's
`Thread.sleep(120)` races that update. This is an ordering/visibility bug, not an
unsynchronized field access — so there is no true conflicting pair for a barrier to
eliminate, and RQ1 cannot be evaluated on this subject.

### 6.2 rxjava2-extras — `FlowablesTest#testCache`

Commit `7663d3b`. Failure reproduces reliably at a 1600 ms delay.

| Condition | Classes instrumented | Delay | Pairs | Outcome | Test time |
|---|---|---|---|---|---|
| T0 | 311 | none | 0 | passes 3/3 | 17.5 s |
| T1 | 311 | 1600 ms | 2 (stable across 3 runs) | fails 3/3 | 16.6 s |
| C2 | 3 | 1600 ms | 0 | fails 3/3 | 6.2 s |
| C2+ | 5 | 1600 ms | 0 | fails 3/3 | 6.2 s |

The two pairs were:

```
SchedulerPoolFactory$ScheduledTask|keySet|160  <->  SchedulerPoolFactory|put|153
SchedulerPoolFactory$ScheduledTask|remove|162  <->  SchedulerPoolFactory|put|153
```

Both are collection-API conflicts on a shared map — one thread inserting while
another iterates keys and removes. Unlike the Uniffle finding, these are
cross-class, reproducible across every run, and a plausible real race.

Two observations follow.

**The pairs are failure-linked.** They appear only under the delay that induces the
failure (T1) and never without it (T0). This is a direct association between TSV
output and FlakeSync-localized flakiness.

**Scoping eliminates the signal, and coverage is not the explanation.** C2+ was
constructed by adding the exact classes named in the T1 pairs to the scope. It still
found nothing. Since TSVD4J exposes conflicts by injecting delays *at instrumented
interception points*, the instrumentation is simultaneously the sensor and the
actuator; removing 306 of 311 classes removes almost all of the perturbation that
makes a near-miss observable. The ~10 s runtime difference between T1 and C2 is
precisely the injected delay that was lost.

**T2 could not be run.** `PatchingMojo` failed with `Could not find Java file for
class: io.reactivex.internal.schedulers.ScheduledRunnable`. FlakeSync's barrier
point falls inside the RxJava dependency jar, and the repair requires source to
insert into.

### 6.3 rxjava2-extras — `TransformersTest#testBufferMaxCountAndTimeoutAsyncCountWins`

All five pipeline stages ran. `barrierpointsearch` took 46 minutes and produced only
a header row — no barrier point. The earlier stages' outputs have not yet been
inspected to determine whether the failure reproduced at all.

---

## 7. Findings

### 7.1 Region scoping is incompatible with delay-based race detection (RQ2)

The premise behind RQ2 — narrow the scope, keep the finding, save the time — does
not hold for detectors of this class. Sensitivity scales with the total volume of
injected delay, so any scope restriction trades detection power for speed at
roughly the rate it removes instrumentation. This is not specific to TSVD4J; it
should apply to any dynamic detector that perturbs schedules to expose conflicts.

The practical consequence is that "run the expensive detector only where FlakeSync
pointed" is not a viable optimization for this family of tools, however appealing
it looks on paper.

### 7.2 Where barriers go and where races are are different places

Across all three subjects, FlakeSync's barrier points and TSVD4J's conflicting pairs
were in different classes, and in two of three cases in different artifacts
entirely:

| Subject | FlakeSync barrier point | TSV pair location |
|---|---|---|
| Uniffle | `GrpcServerTest#77` (test) | `io.prometheus.client.DoubleAdder` (dependency) |
| rxjava2 `testCache` | `ScheduledRunnable` (dependency) | `io.reactivex...SchedulerPoolFactory` (dependency) |
| rxjava2 `Transformers` | none found | — |

FlakeSync localizes *where to impose an ordering*, which is a property of the test's
control flow. TSVD4J localizes *where shared state is touched without
synchronization*, which is a property of the data. These coincide less often than
the research question assumes.

This also suggests why RQ1 is hard rather than merely unlucky: FlakeSync's async
flaky tests are ordering bugs — a read that happens before another thread's write —
where each individual access may be correctly synchronized. TSVD4J detects
unsynchronized concurrent access. These are different properties, and their
intersection may be small by construction.

---

## 8. Limitations

- **N = 3 per configuration.** TSVD4J's delay injection is randomized. On Uniffle,
  runs 0 and 1 found a pair and run 2 found none, from an identical configuration.
  Saturation has not been established; N should be raised and justified from a
  cumulative-unique-pairs curve before any of these numbers are treated as final.
- **One machine, one JDK.** Timing-sensitive reproduction may differ elsewhere.
  `concurrentfind` on rxjava2 returned a superset of the authors' expected output
  (8 additional methods), consistent with different interleaving on JDK 21.
- **RQ2 rests on a single subject.** Only `testCache` produced pairs to lose.
- **No ground-truth negatives.** No genuine concurrency bug has been run through the
  pipeline yet, so the discriminator has not been tested in the direction it would
  need to work.
- **One unresolved anomaly.** A scope file with 9 entries reported `scopeSize=5`,
  suggesting the appended entries did not parse or were duplicates. This does not
  affect the C2/C2+ results, which were verified class-by-class, but it needs
  checking before a scope-size sensitivity curve is produced.

---

## 9. Proposed next steps

1. **Preserve and re-run the rxjava2 conditions with larger N** (10–20) into
   separate output directories, and produce the saturation curve. This is the
   evidence base for §7.1.
2. **Attempt `Accenture/mercury`** (`MulticastTest#routingTest`, commit `8586dc7`).
   It is an application codebase rather than a reactive library, so its barrier point
   is more likely to land in patchable source — the best remaining chance at T2.
3. **Construct a within-project negative** by removing a `synchronized` or `volatile`
   from a correctly-synchronized class in a subject that already reproduces, and
   confirm the test becomes flaky. This gives ground truth without the codebase
   confound that comparing across projects would introduce.
4. **If T2 remains unreachable after (2),** I would propose reframing the
   contribution around §7.1 and §7.2 — a measured negative result with a mechanism,
   plus the structural argument for why barrier localization and race localization
   diverge — rather than continuing to search for a subject where the discriminator
   can be evaluated.

I would welcome guidance on (4) in particular: whether to invest further in finding
a workable T2 subject, or to develop the negative result as the contribution.

---

## 10. Artifacts

- Integration patch against `shanto-Rahman/Flakesync-tool` (655 lines, verified to
  apply to a clean checkout)
- Follow-up patch with the scope-parser and transformer-composition fixes
- `dataRace-Example` validation subject
- `tools/aggregate_tsv.py`, `tools/run_study.sh`
- Raw per-run CSVs under `results/`, one directory per condition