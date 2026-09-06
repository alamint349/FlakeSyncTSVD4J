# Applying TSVD4J to FlakeSync-Localized Regions

**Progress report (revised)**
Md Al Amin — 6 September 2026

---

## 1. Summary

I integrated TSVD4J's thread-safety-violation (TSV) detection into the FlakeSync
tool so that concurrency analysis can be restricted to the code regions FlakeSync
localizes, and evaluated it on four flaky tests from FlakeSync's own subject list.

Two questions were posed:

- **RQ1** — Can targeted TSV analysis separate FlakeSync-repairable synchronization
  issues from genuine concurrency bugs?
- **RQ2** — Does restricting the analysis to FlakeSync-localized regions provide
  runtime or scalability benefit over whole-program analysis?

This revision supersedes the version of 30 August. Two claims in that draft did not
survive larger sample sizes and a controlled experiment, and both are corrected in
§2.

**RQ2.** Scoping the analysis to FlakeSync's region is 2.4× faster and finds
nothing. A controlled experiment separating instrumentation scope from reporting
scope shows the cause is *localization mismatch*, not the loss of schedule
perturbation I had previously proposed: the conflicting accesses lie wholly outside
the region, so no scoped run could report them regardless of how densely the region
is instrumented.

**RQ1.** Not evaluable on any of the four subjects, each blocked for a different
documented reason (§8). The pattern appears structural rather than accidental, and
§7.2 sets out why.

**A third finding.** On `Accenture/mercury`, whole-program TSV instrumentation makes
the subject test fail unconditionally by exceeding its own timeout, independent of
any injected delay. For tests with hard timing deadlines the analysis cannot be
applied at all.

---

## 2. Corrections to the 30 August draft

1. **The conflicting pairs are not failure-linked.** The earlier draft reported that
   the pairs appeared only under the failure-inducing delay, based on N = 3. At
   N = 20 the undelayed baseline finds both pairs in 20/20 runs while the test
   passes throughout. The pairs are background scheduler activity in RxJava, present
   whether or not the flaky failure occurs, and unrelated to the bug FlakeSync
   repairs.

2. **Scoping fails through mismatch, not lost perturbation.** The earlier draft
   attributed the scoped null result to TSVD4J's instrumentation acting as both
   sensor and actuator, so that narrowing the scope removes the perturbation that
   exposes conflicts. The experiment in §3.1 shows instead that both pairs lie
   outside the region entirely. The sensor/actuator effect may still apply to pairs
   that do fall inside a region; there is no evidence for it here, and evidence
   against it being the operative cause on this subject.

---

## 3. What was built

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
| `Agent.java` | New `TSV_DETECTION` mode; dispatches to `ClassTracer`, chained with FlakeSync's delay injector so the two compose |
| `TsvScope.java` | New — parses any FlakeSync location artifact into a class-name set; supports an instrumentation scope and an independent reporting region |
| `SurefireExecution` | New `createTsvDetectionExec(...)` factory |
| `TsvDetectMojo` | New goal `tsvdetect`; repeats N randomized runs, writes per-run CSVs plus a manifest with wall-clock and outcome |
| `tools/` | `aggregate_tsv.py` (pair sets, saturation curves, region structure), `run_conditions.sh` (runs and files one condition) |

Because delay injection and TSV analysis are both `ClassVisitor`s taking a delegate,
they compose. Delay injection is placed outer so the TSV tracer records unshifted
line numbers, keeping pair endpoints comparable against FlakeSync's location files.

### 3.1 Instrumentation scope versus reporting scope

Following the suggested experiment, `-DtsvReport` sets a reporting region
*independent* of what is instrumented. A run can therefore instrument the whole
program, preserving the full delay injection, while labelling each reported pair
with how many of its two endpoints fall inside the FlakeSync-localized region
(`0`, `1`, or `2`). This separates the two candidate explanations directly:

- pairs labelled `2` — the region contains them, so a scoped run should have found
  them; the loss would be attributable to perturbation;
- pairs labelled `0` or `1` — a scoped run could never have reported them; the
  region is the wrong place to look.

---

## 4. Validation of the port

A minimal subject (`dataRace-Example`) was written with two deliberate races: an
unsynchronized `int` field and an unsynchronized `ArrayList`.

| Configuration | Pairs found |
|---|---|
| Stock `mvn tsvd4j:tsvd4j` | `Counter\|16`, `Counter\|add\|25` |
| Merged tool, unscoped | identical |
| Merged tool, scoped to 1 of 4 classes | both pairs retained |

Reported line numbers are exact, so pair endpoints map onto FlakeSync's location
files without an offset correction. Pairs print in two formats: field races as
`Class|line`, API races as `Class|method|line`.

---

## 5. Defects found and fixed

1. **The `field` and `api` system properties are mutually exclusive selectors, not
   additive flags.** `ClassTracer` defaults both detectors on; setting `field`
   disables `api` and vice versa. Setting both silently ran field-only, halving the
   findings.

2. **The scope parser took the last comma-separated field.** `BarrierPoints.csv` has
   four fields ending in a numeric threshold, so the barrier point's class was
   discarded and the string `"1"` entered the scope instead. Replaced with a regular
   expression extracting every fully-qualified class token on the line.

3. **The agent dispatches on a single `agentmode`.** `TSV_DETECTION` initially
   *replaced* delay injection rather than composing with it, making the delayed
   condition identical to the undelayed one. Fixed by chaining the two
   `ClassVisitor`s.

4. **The `clean_*` run cache masks failures across configuration changes.** Cached
   passing outcomes are reused, so a stage can report "no critical points found"
   when the test does fail. All reported runs clear `clean_*` and `fail_*`
   beforehand.

Subjects must be checked out at the commits pinned in
`testscripts/input/inputs.csv`; on current `main` the Uniffle bug is fixed upstream
and nothing reproduces.

---

## 6. Experimental setup

JDK 21.0.10, Ubuntu 24.04, single machine. JaCoCo disabled (`-Djacoco.skip=true`) so
only the FlakeSync agent transforms classes. N = 20 per condition unless stated
otherwise.

| Label | Instr. scope | Report region | Delay | Repair |
|---|---|---|---|---|
| T0 | whole program | FlakeSync region | none | no |
| T1 | whole program | FlakeSync region | yes | no |
| T2 | whole program | FlakeSync region | yes | yes |
| C2 | FlakeSync region | FlakeSync region | yes | no |
| R1pos | whole program | pair-owning classes | yes | no |

`R1pos` is a positive control for the labelling mechanism: the reporting region is
set to the classes that actually contain the pairs, so correct labelling must return
`2`.

---

## 7. Results

### 7.1 rxjava2-extras — `FlowablesTest#testCache`

Commit `7663d3b`. Failure reproduces reliably at a 1600 ms delay. All conditions
N = 20 except `R1pos` (N = 3); every saturation curve is flat from run 0.

| Cond. | Instr. | Region | Pairs | In region | Fail% | Median ms |
|---|---|---|---|---|---|---|
| T0 | 310 | 3 | 2 (20/20) | 0 (neither) | 0 | 18,727 |
| T1 | 311 | 3 | 2 (20/20) | 0 (neither) | 100 | 17,892 |
| C2 | 3 | 3 | 0 (20/20) | — | 100 | 7,458 |
| R1pos | 311 | 2 | 2 (3/3) | 2 (both) | 100 | 18,194 |

The two pairs were:

```
SchedulerPoolFactory$ScheduledTask|keySet|160
    <-> SchedulerPoolFactory|put|153
SchedulerPoolFactory$ScheduledTask|remove|162
    <-> SchedulerPoolFactory|put|153
```

Both are collection-API conflicts on a shared map — one thread inserting while
another iterates keys and removes.

**The pairs are unrelated to the flakiness.** T0 and T1 return an identical pair set.
T0 injects no delay and the test passes in 20/20 runs; T1 injects the delay and the
test fails in 20/20. The conflicting accesses are therefore background scheduler
activity, present regardless of whether the flaky failure occurs.

**Scoping fails through mismatch.** Both pairs are labelled `0` in every run of T0
and T1: neither endpoint lies in the FlakeSync-localized region. The `R1pos` control
returns the same pairs labelled `2` when the reporting region is set to the
pair-owning classes, which establishes that the labelling reflects region membership
and not a parsing failure. A scoped run could not have reported these pairs under
any amount of perturbation.

**T2 could not be run.** `PatchingMojo` failed with `Could not find Java file for
class: io.reactivex.internal.schedulers.ScheduledRunnable`. The barrier point falls
inside the RxJava dependency jar, and the repair requires source to insert into.

### 7.2 Apache Uniffle — `GrpcServerTest#testGrpcExecutorPool`

Commit `625377c`. Full pipeline completed; flakiness reproduced (`expected: <1.0>
but was: <0.0>` at a 200 ms delay); both patches generated.

| Scope | Classes instr. | Pairs | Test time |
|---|---|---|---|
| Whole program | 82 | 1 (in 2 of 3 runs) | 36.8 s |
| FlakeSync region | 2 | 0 | 0.9 s |
| Region + `GRPCMetrics` | 3 | 0 | 0.9 s |

The single pair was `io/prometheus/client/DoubleAdder|179`, a self-pair inside a
third-party striped-counter class built on deliberately unsynchronized per-cell CAS
writes — almost certainly a false positive. The actual failure is an
ordering/visibility issue: `GrpcThreadPoolExecutor.afterExecute` decrements the
metrics gauge only after a task completes, and the test's `Thread.sleep(120)` races
that update. There is no true conflicting pair for a barrier to eliminate.

These runs predate the N = 20 protocol and were not repeated, since the subject
cannot support the discrimination experiment either way.

### 7.3 rxjava2-extras — `TransformersTest`

All five pipeline stages ran. `barrierpointsearch` took 46 minutes and produced only
a header row — no barrier point, therefore no repair and no T2.

### 7.4 Accenture/mercury — `MulticastTest#routingTest`

Commit `8586dc7`. Two separate obstacles.

**No barrier point, therefore no T2.** `barrierpointsearch` produced only a header
row and `patch` generated nothing; no `.flakesync/patch` directory was created and
`git diff` was empty. A condition initially recorded as T2 was in fact T1 repeated,
and has been relabelled in the published data.

**Instrumentation overhead exceeds the test's own timeout.** `routingTest` uses hard
five-second deadlines (`platform.waitForProvider(..., 5)` followed by
`bench.poll(5, TimeUnit.SECONDS)`, and a completion-queue poll with the same bound).
Uninstrumented the test passes in 7.5 s. Under whole-program TSV instrumentation it
takes 197 s and fails in 20/20 runs — including T0, where no FlakeSync delay is
injected at all.

| Cond. | Instr. | Region | Pairs | Fail% | Median ms |
|---|---|---|---|---|---|
| T0 | 142 | 1 | 0 (20/20) | 100 | 199,076 |
| T1 | 143 | 1 | 0 (20/20) | 100 | 199,822 |

The failure is an artifact of the measurement apparatus, not of the bug under study.
Whole-program TSVD4J also found no conflicting pairs anywhere in `platform-core`
under any condition.

---

## 8. Findings

### 8.1 Localization mismatch, not lost sensitivity (RQ2)

Restricting TSV analysis to FlakeSync's region found no conflicting pairs at N = 20,
at 2.4× the speed. The controlled experiment shows why: both pairs lie wholly
outside the region (`endpointsInRegion = 0` in 20/20 runs), with a positive control
confirming the measurement. The speedup buys nothing because there was nothing in
the region to find.

This is a different claim from the one in the previous draft, and a stronger one,
because it is measured rather than inferred. It also generalizes differently: the
problem is not that scoping weakens a schedule-perturbing detector, but that one
technique's localization does not identify the locations the other technique
analyses.

### 8.2 Where barriers go and where races are are different places

Across four subjects, FlakeSync's barrier points and TSVD4J's conflicting pairs
never shared a class, and never shared an artifact:

| Subject | FlakeSync barrier point | TSV pair location |
|---|---|---|
| Uniffle | `GrpcServerTest#77` (test source) | `io.prometheus.client.DoubleAdder` (dependency) |
| rxjava2 `testCache` | `ScheduledRunnable` (dependency) | `io.reactivex...SchedulerPoolFactory` (different class, same dependency) |
| rxjava2 `Transformers` | none found | — |
| mercury | none found | none found |

Same class: 0 of 2 evaluable subjects. Same artifact, different class: 1 of 2.
Different artifact: 1 of 2. Two of four subjects produced no barrier point at all.
With only two subjects yielding conflicting pairs the sample is small, so this is
best described as consistent rather than statistically supported.

FlakeSync localizes *where to impose an ordering*, a property of the test's control
flow. TSVD4J localizes *where shared state is touched without synchronization*, a
property of the data. These coincide less often than the research question assumes.

### 8.3 A feasibility limit for timing-sensitive tests

§7.4 shows a case where whole-program TSV instrumentation cannot be applied at all:
the overhead alone (7.5 s to 197 s) exceeds deadlines written into the test, so the
test fails regardless of the bug. This is independent of the localization question
and constrains which subjects the combined analysis can address.

---

## 9. Status of RQ1

The discriminator requires comparing TSV output before and after FlakeSync's repair.
No subject produced a usable post-repair condition:

| Subject | Blocker |
|---|---|
| Uniffle | Only pair is a false positive in a thread-safe library class; the real bug is an ordering issue with no corresponding conflicting pair |
| rxjava2 `testCache` | Barrier point inside a dependency jar; `PatchingMojo` has no source to modify |
| rxjava2 `Transformers` | No barrier point found (46-minute search) |
| mercury | No barrier point found; instrumentation additionally breaks the test unconditionally |

I read this as structural rather than accidental. FlakeSync's async flaky tests are
ordering bugs — a read that happens before another thread's write — where each
individual access may be correctly synchronized. TSVD4J detects unsynchronized
concurrent access. These are different properties, and the four subjects suggest
their intersection is small.

---

## 10. Limitations

- **RQ2 rests on a single subject.** Only `testCache` produced pairs to lose.
  Uniffle produced one likely false positive; the other two subjects produced none.
- **The structural count has n = 2.** Only two subjects yielded both a barrier point
  and conflicting pairs.
- **Uniffle was not re-run at N = 20.**
- **One machine, one JDK.** `concurrentfind` on rxjava2 returned a superset of the
  authors' expected output (8 additional methods), consistent with different
  interleaving on JDK 21.
- **No ground-truth negatives.** No genuine concurrency bug has been run through the
  pipeline, so the discriminator has not been tested in the direction it would need
  to work.
- **The sensor/actuator hypothesis is not refuted in general.** It is only shown not
  to be the operative cause on the one subject where pairs exist. A subject whose
  pairs fall inside the region would test it properly.

---

## 11. Proposed next steps

1. **Two subjects remain** in FlakeSync's list (`luwak`, `tomcat_exporter`). Running
   them would raise the structural count from n = 2 and might yield a post-repair
   condition.
2. **Construct a within-project negative** by removing a `synchronized` or
   `volatile` from a correctly-synchronized class in a subject that already
   reproduces. This gives ground truth without the codebase confound of comparing
   across projects.
3. **A subject whose pairs fall inside the region** would let the sensor/actuator
   hypothesis be tested directly, by comparing scoped and whole-program detection of
   pairs labelled `2`.
4. **Otherwise, develop the negative result** around §8.1–8.3: measured localization
   mismatch, TSV findings unrelated to the repaired flakiness, and the feasibility
   limit for timing-sensitive tests.

---

## 12. Artifacts

Repository: `https://github.com/alamint349/FlakeSyncTSVD4J` (branch
`tsvd4j-integration`).

- Integration of TSVD4J as the `TSV_DETECTION` agent mode, with `TsvScope` and the
  `tsvdetect` goal
- `-DtsvReport` separation of instrumentation and reporting scope
- `dataRace-Example` validation subject
- `tools/aggregate_tsv.py`, `tools/run_conditions.sh`
- Raw per-run CSVs under `results/`, one directory per condition, plus saturation
  data