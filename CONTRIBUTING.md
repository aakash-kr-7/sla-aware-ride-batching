# Engineering Conventions

This document defines the working standards and development conventions for this codebase. It applies to every incremental change.

## Code Structure & Design

- **Feature-first packaging**: Keep business code in its domain (e.g. `booking`, `batching`, `dispatch`). Small features keep domain types, services and repositories together; use subpackages only when they clarify a boundary (`api`, `dto`, `constraint`, `evaluator`). Shared HTTP concerns belong in `api`; local bootstrap code belongs in `dev`.
- **Constructor injection**: Use explicit constructors with `final` fields for dependency injection. Avoid `@Autowired` field injection.
- **DTOs vs persistence entities**: Keep external API contracts strictly separated from persistence entities. Use Java `record`s for immutable request/response DTOs and value objects.
- **No Lombok**: Write standard Java constructors, accessors, and records directly. Avoid adding Lombok unless a concrete, justified need arises.
- **Domain-specific exceptions**: Use specific exceptions (e.g. `UnserviceableZoneException`, `BookingNotFoundException`) for API failure semantics. Expected duplicate requests return the existing booking rather than an exception.
- **Configuration over magic constants**: Externalize operational parameters (batching windows, pickup SLA limits, detour thresholds, driver cool-off durations) into typed configuration properties rather than inline literals.
- **No speculative abstractions**: Prefer the simplest direct implementation. Do not introduce interfaces, factories, events, or layers until a concrete requirement demands them.

## Code Style & Comments

- **Java conventions**: Standard naming (`PascalCase` types, `camelCase` methods/fields, `UPPER_SNAKE_CASE` constants). 4-space indentation.
- **Comments explain *why***: Code comments must explain non-obvious algorithms, race conditions, concurrency safeguards, domain invariants, or trade-offs. Never narrate what readable Java code is already doing; avoid decorative Javadoc boilerplate.
- **Tone**: Keep naming and error messages direct and technical. Avoid vague buzzwords ("robust", "comprehensive", "enterprise-grade").

## Testing

- **Behaviour-focused naming**: Name tests after the scenario and invariant being verified (e.g. `shouldRejectBookingWhenPickupZoneIsNotServiceable`, `concurrentSubmissionsProduceSingleBooking`), not the target method name.
- **PostgreSQL integration tests**: Persistence constraints, locking, transaction isolation, and deduplication guarantees must be tested against real PostgreSQL (via Testcontainers), not H2 or mock repositories.
- **Failure paths**: Tests must assert negative paths, constraint violations, and mapped error responses alongside happy paths.

## Definition of Done for an Increment

An increment is complete only when:

1. **Implementation**: Satisfies the single requested requirement without opportunistically building future phases.
2. **Tests**: All behavioural unit tests and relevant PostgreSQL integration tests pass cleanly without suppressing failures or mocking database constraints.
3. **Failure behaviour**: Explicit domain errors and appropriate HTTP statuses are returned and verified for invalid inputs and violated invariants.
4. **Docs updated**: Update affected contracts in `docs/` and README limitations when a decision or behaviour changes. Keep implemented behaviour distinct from requirements and proposed work.
5. **Manual verification**: Changes are verified with runnable tests or commands, noting exact results.

Use the Maven wrapper with JDK 17 or newer and Docker running: `./mvnw test`, then `./mvnw clean verify` (Windows: `./mvnw.cmd`). Benchmarks live in `src/benchmark/java` and compile only with `-Pbenchmark`; they are not normal tests. Never commit populated environment files or credentials. Preserve applied Flyway history; fresh-schema blockers require explicit explanation.
