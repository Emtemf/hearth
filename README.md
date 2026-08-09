# Hearth

Hearth is a self-hosted orchestration and observability platform for AI coding agents. The project is designed to coordinate tools such as Claude Code, Codex, Gemini CLI, and opencode while keeping session routing, evidence, budgets, lifecycle control, and memory under the operator's control.

> **Status:** architecture and specification phase. The repository currently contains design documents and project constraints; the runtime and Web UI are not implemented yet.

## Goals

- Observe complete agent sessions through a byte-transparent model gateway.
- Route each session to an explicitly configured, wire-compatible model endpoint.
- Coordinate specialized agents with bounded budgets, loop prevention, and human escalation.
- Require independently verifiable artifacts for material completion claims through G4C+E.
- Run agents across a hive of authenticated worker machines without exposing provider credentials.
- Add durable memory, schedules, and messaging integrations without allowing agents to create uncontrolled automation.

## Milestones

- **M1 — Observable sessions:** launch Claude Code through Hearth and inspect the system prompt, transcript, token usage, and recording gaps in the Web UI.
- **M2 — Multi-agent execution:** assign a real coding task to collaborating agents and inspect their A2A messages, evidence, budget use, and cancellation lifecycle.
- **M3 — Assistant automation:** trigger tasks and reminders through messaging integrations, with durable schedules, memory retrieval, and human decision gates.

See [the roadmap](docs/07-roadmap.md) for executable acceptance criteria.

## Architectural boundaries

- Java 21, Spring Boot 4, Spring MVC, and virtual threads.
- PostgreSQL is the durable source of truth; Redis is never the orchestration store.
- The gateway streams response bytes without buffering the full SSE response.
- Spring AI is limited to memory, embeddings, pgvector integration, and the Hearth MCP server; it is not used as the gateway.
- Agent processes are launched only through the `WorkerClient` abstraction.
- Provider credentials come from environment variables or a secret manager and are never placed in source files or process arguments.
- M1 supports only wire-compatible upstream routing; cross-protocol translation requires an explicit adapter.

The full project constitution is in [AGENTS.md](AGENTS.md).

## Documentation

Start with:

- [Stack decisions](docs/00-stack-decision.md)
- [Architecture](docs/01-architecture.md)
- [Module boundaries](docs/02-modules.md)
- [Database schema](docs/03-schema.md)
- [REST API](docs/05-rest-api.md)
- [G4C+E task model](docs/09-g4c.md)
- [Dependencies](docs/10-dependencies.md)
- [Resilience](docs/11-resilience.md)
- [Worker architecture](docs/12-worker.md)
- [Web UI design](docs/13-ui.md)
- [Automation](docs/14-automation.md)

## Security status

Hearth is not ready for production or untrusted networks. The M1 design binds local services to loopback and still requires local authentication and CSRF protection. Multi-machine deployments require a private encrypted network or trusted TLS plus application-level authentication.

Never commit real credentials. Copy `.env.example` to a local ignored `.env` file only when implementation begins.

## License

Licensed under the [Apache License 2.0](LICENSE).
