# M1 runnable surface tasks

## Implementation slices

- [x] Add `hearth-api` Spring Boot 4.0.7 module with Actuator.
- [x] Add a loopback-only application entry point and `/actuator/health`.
- [x] Add read-only `/api/v1/m1/status` exposing fixture contract evidence without secrets.
- [x] Add application-side boundary types that do not expose `ProcessBuilder` to the API module.
- [x] Add focused MVC tests for health and status responses.
- [x] Run `mvn test`, documentation validation, and `git diff --check`.
- [x] Run the application and capture curl evidence for both endpoints.

## Evidence

- `mvn -q -pl hearth-api test` passed with two MVC tests.
- `python3 scripts/validate_docs.py` passed with 44 Markdown files checked.
- `git diff --check` passed.
- Live `curl http://127.0.0.1:4517/actuator/health` returned `{"groups":["liveness","readiness"],"status":"UP"}`.
- Live `curl http://127.0.0.1:4517/api/v1/m1/status` returned fixture evidence with `workerContract=fixture_verified`, `processReusePolicy=STREAMING_STDIN`, and `realCliProbe=not_run`.


## Acceptance

- [ ] `mvn -pl hearth-api test` passes.
- [ ] The application starts on `127.0.0.1:4517` without Python, Postgres, or API keys.
- [ ] `/actuator/health` returns HTTP 200 and status `UP`.
- [ ] `/api/v1/m1/status` returns `data` with M1.0 contract status and no credential value.
- [ ] The API module contains no `ProcessBuilder` use.
- [ ] The response does not claim that a real Claude CLI probe passed.

## Later gates

- [ ] M1.1: real Gateway fixture, credential rewriting, streaming and Exchange recording.
- [ ] M1.2: authenticated WorkerClient transport, daemon lifecycle, process generation, and real child UID/permissions.
