# dataRace-Example

Minimal subject for validating the TSVD4J port into FlakeSync. `Counter` has an
unsynchronized `count` field and an unsynchronized `ArrayList`, both driven from
two threads by `CounterTest#testConcurrentIncrement`.

The test always passes. Any conflicting pair reported comes from the analysis,
not from a test failure.

The fully-qualified test name is:

    com.example.CounterTest#testConcurrentIncrement

## Step 5 — baseline: stock TSVD4J

    mvn clean install
    mvn tsvd4j:tsvd4j
    cat .tsvd4j/Conflicting-Pairs.txt

Expect pairs mentioning `com/example/Counter`. Save this file:

    cp .tsvd4j/Conflicting-Pairs.txt /tmp/baseline-stock.txt

## Step 6 — merged tool, unscoped

    rm -rf .tsvd4j .flakesync
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:tsvdetect \
      -Dflakesync.testName=com.example.CounterTest#testConcurrentIncrement \
      -Dflakesync.tsvScope=NONE \
      -Dflakesync.tsvRuns=5

    cat .flakesync/Results-TSV/com.example.CounterTest.testConcurrentIncrement-TSV-run0.csv

The pair set should match `/tmp/baseline-stock.txt`. The header line
`#classesConsidered=` must be nonzero -- if it is 0 the agent never ran.

## Step 7 — merged tool, scoped to one class

    echo "com.example.Counter#0" > /tmp/scope.txt
    rm -rf .flakesync
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:tsvdetect \
      -Dflakesync.testName=com.example.CounterTest#testConcurrentIncrement \
      -Dflakesync.tsvScope=/tmp/scope.txt \
      -Dflakesync.tsvRuns=5

    head -5 .flakesync/Results-TSV/*-TSV-run0.csv

Expect `#scopeSize=1`, a much smaller `#classesInstrumented`, and the race still
detected. If the race disappears, the other endpoint of the pair lives outside
the scope -- widen `/tmp/scope.txt` to confirm, and note it: that is the
two-endpoint coverage limit, which is a result worth reporting, not a bug.

## Optional — run the field and API detectors separately

    -Dflakesync.tsvKind=field
    -Dflakesync.tsvKind=api

`field` should flag `count`; `api` should flag the `ArrayList` operations.
