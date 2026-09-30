# Phase 3 — Test & Live Runner

Phase 3 adds a repeatable 1,000-question web-planner corpus, a Tavily live runner, the problem catalogue, and the usage-pattern report.

## Deterministic test

From the project root:

```bash
bash jvm-tests/run-tests.sh
```

This compiles the pure-Java core and runs the existing suites plus the Phase 3 1,000-question corpus.

## Dry run

```bash
bash jvm-tests/run-phase3-live.sh --dry-run --all
```

This classifies all 1,000 rows without sending a network request.

## Live Tavily sample

Set a key only in the environment. The runner never prints the key and never writes it to the result file.

```bash
TAVILY_API_KEY='YOUR_REAL_KEY' bash jvm-tests/run-phase3-live.sh --limit 25
```

For multiple keys:

```bash
TAVILY_API_KEYS='KEY1,KEY2' bash jvm-tests/run-phase3-live.sh --limit 25 --output phase3/live-results.tsv
```

For the pasted-link / Extract path:

```bash
TAVILY_API_KEY='YOUR_REAL_KEY' bash jvm-tests/run-phase3-live.sh --category url_read --limit 20 --output phase3/live-url-results.tsv
```

To run all 1,000 rows explicitly:

```bash
TAVILY_API_KEY='YOUR_REAL_KEY' bash jvm-tests/run-phase3-live.sh --all --output phase3/live-results.tsv
```

A full live run can consume API quota and may take much longer than the deterministic test, so it is opt-in rather than part of `run-tests.sh`.

## Dataset columns

`phase3/questions.tsv` stores the id, category, question text, expected search decision, expected display/read flags, settings mode, attachment state, and optional previous-query context. The Android code is not changed by the dataset.
