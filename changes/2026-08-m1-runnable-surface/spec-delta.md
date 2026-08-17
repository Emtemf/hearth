# M1 runnable surface spec delta

## ADD / MODIFY

- `docs/07-roadmap.md`: M1 runnable demonstration includes a loopback Spring Boot API with Actuator health and a read-only M1 status endpoint; it does not claim full M1 acceptance.
- `docs/10-dependencies.md`: the current reactor adds `hearth-api` with Spring Boot 4.0.7 and Actuator; Spring AI remains excluded from M1.
- `docs/01-architecture.md`: the local API demonstration exposes status only and cannot spawn agent processes; Worker execution remains behind the WorkerClient boundary.
- `docs/12-worker.md`: the M1 runnable surface may report fixture contract evidence through an application boundary, while production Worker transport and daemon lifecycle remain later work.
- Root Maven reactor: add `hearth-api`.

## Compatibility

- No database migration, provider wire protocol, or production Worker transport change.
- New local HTTP endpoints bind to loopback and are additive.
- `/actuator/health` is the standard Spring Boot health response; `/api/v1/m1/status` is a read-only demonstration endpoint.
