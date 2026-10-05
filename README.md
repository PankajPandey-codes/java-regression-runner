# java-regression-runner

**Personal project.** A CSV/JSON-manifest-driven API regression testing engine for Java 17 — a way to regression-test a multi-service HTTP API without writing a line of per-test Java/JUnit code.

Testers describe test cases declaratively: a CSV manifest row names an API, points at an input-parameters file and an expected-response file, and flags whether it's currently enabled. The engine executes each row as one or more HTTP (or raw SQL) steps, deep-compares actual vs. expected JSON with a configurable assertion DSL, chains captured values between steps, and produces both machine-readable and human-readable reports. It's built to run equally well ad hoc from the CLI or unattended overnight via a systemd timer.

## Core capabilities

- **Manifest-driven test cases** — a CSV manifest (`apisToBeValidated/*.csv`) lists API name, input-parameters file, expected-response file, an enable/disable flag, module, and business-function tags. Each row maps to a per-step CSV of parameters/expected status codes and JSON arrays of payload/expected-response bodies — one array element per step.
- **Two execution modes** — `dry-run` validates manifest completeness (missing files, enabled counts, module tallies) with zero network calls; `execute` actually runs the suite with a configurable thread pool, auth token fetch/refresh, and per-run timestamped result folders.
- **Declarative assertions** — a JSON `assertions[]` array per expected step (`equals`, `contains`, and more), resolved via a small JSONPath-like engine with field filters (`[?field=value]`), plus a full deep-diff comparator with `ignore`/`commonIgnore` field exclusion and unordered-array support.
- **Auth handling** — fetches a bearer token (username/password/tenant), with automatic retry and a configurable proactive refresh interval, so long overnight runs don't die on token expiry.
- **Database steps** — an optional parameterized SQL step type (MySQL in production, H2 in tests) as an alternative to HTTP, with a precision-safe result mapper so chained numeric values don't lose decimal precision.
- **Step chaining** — any step's response can be referenced by later steps via a `{{stepId.response.path}}` template syntax, resolved into strings or bound query parameters.
- **Reporting** — JSON execution summaries, a styled HTML report, a separate cross-run "analysis" diff pass (HTTP codes/payloads across runs), a hash-based "canary" doc tied to repo/commit SHA for CI gating, a combined tabbed regression report, and a `run-status.json` for liveness polling mid-run.
- **Unattended overnight runs** — a shell wrapper backgrounds the runner with file-lock-based mutual exclusion and PID/log tracking (start/stop/status/tail), paired with a watchdog script that fails if the run went stale or errored. Systemd service+timer units are included as deployment templates.

## Architecture

```
CLI (--mode=dry-run|execute|render|analyze)
  → Engine (DryRunEngine | ExecuteEngine)
      → ManifestLoader reads CSV manifests + per-step JSON/CSV files
      → per step: HTTP call or SQL query
      → RuntimeAssertionEvaluator + JsonComparator score pass/fail
      → ScenarioContext / StepSnapshot persist state for chaining
      → results land in results/<runStamp>/
  → Reporters (Execution / Analysis / Canary / RunStatus / Regression) fan out from the run folder
```

A `Resolution<T>` value-or-errors type is used throughout the templating/assertion code instead of exceptions for expected failure paths. Configuration resolves in priority order: CLI flags > environment variables > a `runner.env` file > built-in defaults.

## Tech stack

Java 17, Gradle. `commons-csv` (manifest/parameter parsing), Jackson (`jackson-databind`/`jackson-core`, all JSON handling), `slf4j-simple` (zero-config console logging), `mysql-connector-j` (production DB steps), JUnit 5 + H2 (in-memory DB for tests).

## Running it

Build a runnable fat jar:

```bash
./gradlew fatJar
```

Run it:

```bash
java -jar build/libs/java-regression-runner-0.1.0-all.jar \
  --projectRoot=<dir> --mode=execute --manifests=A.csv,B.csv --threads=3 \
  --protocol=https --server=api.example.com --tenant=default --username=... --password=... \
  --tokenPath=/identity/v1/token --dbUrl=jdbc:mysql://... \
  --failOnTestFailures=false --responseChaining.enabled=true
```

Every flag has a `JR_*` environment-variable equivalent (e.g. `JR_SERVER`, `JR_DB_URL`, `JR_THREADS`), loadable from a `runner.env` file — see `runner.env.example` for the full list.

Modes: `dry-run` (default), `execute`, `render` (rebuild the HTML report from an existing run folder), `analyze` (diff a run folder against prior runs).

For unattended runs:

```bash
./run_overnight.sh start|status|stop|tail|latest-status
```

`deploy/systemd/` has template service/timer units for a nightly scheduled run plus a separate watchdog timer that alerts if the nightly run didn't finish cleanly — copy and adapt the `.env.example` files with real values for your environment.

## Project layout

```
src/main/java/com/pankajpandey/regression/   # engine, assertions, reporting
src/test/java/com/pankajpandey/regression/   # test suite (205 tests)
build.gradle, settings.gradle, HEADER        # build config
check_run_status.sh, run_overnight.sh        # operational scripts
deploy/systemd/                              # service/timer templates
runner.env.example                           # config reference
apisToBeValidated/                           # sample manifest (what APIs to test)
apiInputs/                                   # sample per-API test parameters
fileFromJson/payloadJSON/                    # sample request payloads
fileFromJson/expectedJSON/                   # sample expected responses
```

## Sample test case

`apisToBeValidated/APIsToBeValidated_Sample.csv` wires together one fully
worked example (`CreateUser`, a fictional `POST /api/v1/users` call) across
all four file types the manifest format uses — manifest row, per-API test
parameters, request payload, expected response. Validate it with:

```bash
./gradlew run --args="--projectRoot=. --mode=dry-run --manifests=APIsToBeValidated_Sample.csv"
```

## Test coverage

The execution engine, assertion/comparison layer, chaining, and reporting all have dedicated test classes (205 tests total). The CLI/argument-parsing glue (`App`, `Config`) and manifest loading are currently only exercised indirectly through the integration-style engine tests, not unit-tested in isolation.
