# M1 runnable surface

## Problem and context

The released M1.0 slice proves the Claude Code subprocess contract with a Java fixture, but it has no long-running application entry point or HTTP surface. A user cannot start Hearth and observe health or the verified Worker contract. The next slice needs a small executable Spring Boot surface while preserving the control-plane/data-plane and Worker process boundary.

## SourceRef

- `docs/07-roadmap.md`
- `docs/10-dependencies.md`
- `docs/01-architecture.md`
- `docs/12-worker.md`
- `AGENTS.md`

## Decision under consideration

Add a Java 21 / Spring Boot 4.0.7 `hearth-api` module with Actuator health and a read-only M1 status endpoint. The endpoint reports application readiness and the already verified M1.0 Worker contract evidence through an application-owned boundary. The API module must not spawn agent processes or depend on Python. Real Worker daemon transport, Gateway byte-level proxying, persistence, and real Claude CLI execution remain later slices.

## Explicit non-goals

- No Spring AI dependency in this M1 slice; Spring AI remains limited to the later memory and MCP modules.
- No database, Flyway migration, authentication, CSRF, or production session persistence.
- No direct `ProcessBuilder` use in `hearth-api`.
- No claim that the fixture proves compatibility with a real Claude Code version.
- No Web UI or external network listener; bind to loopback for local demonstration.

## Risks and open questions

- The status endpoint is demonstration evidence, not a production health model; later slices must distinguish liveness, readiness, and Worker connectivity.
- The exact local authenticated Worker transport and process lifecycle remain to be specified before M1.2 production implementation.
- Spring Boot 4 dependency resolution may require the configured Maven repository to be reachable.
