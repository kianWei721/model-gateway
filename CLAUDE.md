# CLAUDE.md

## Build and test

JDK 25 is required (virtual threads; `java.version=25`). The machine default JDK is 8, so set
`JAVA_HOME` on every Maven call:

```powershell
$env:JAVA_HOME="C:\Users\韦其能\.jdks\openjdk-25.0.2"; mvn -B test
```

Run one class: `mvn -B test "-Dtest=GatewayFlowTest"` (quote the `-D` argument in PowerShell).
After changing a public signature run `mvn clean test`: incremental compiles can keep stale test classes.

## Stack notes that bite

- Spring Boot 4.1 with **Jackson 3** (`tools.jackson.*`): `asString()` not `asText()`, `isString()`
  not `isTextual()`, and missing primitive record fields fail deserialization (use boxed types).
- Web starter is `spring-boot-starter-webmvc`. Tests read the port from `local.server.port`.
- `application.yml` has a second document active on `!test` with the demo catalog; tests use
  `application-test.yml` pointing at `FakeUpstream` via the `fake.base` property.
- H2 in MySQL mode runs the real Flyway scripts in tests. Avoid reserved words as aliases
  (`unknown` broke the usage report).

## Architecture

Single module. Components are plain classes wired in `config/GatewayConfiguration`; keep them
Spring-free so they stay unit-testable.

- `catalog` validates config into `Catalog` (host allowlist, https, prices, routes).
- `routing.RoutePlanner` filters candidates (data residency, tool support), never reorders.
- `upstream` adapters translate protocols and classify failures; they never retry.
- `core.GatewayService` owns failover, breaker, concurrency cap, key leasing, budget reservation
  and ledger writes. Failure handling is driven by `UpstreamFailure.Kind` flags, not status codes.
- `ledger` is the source of truth for money; `billing.BudgetService` rebuilds from it.

## Rules

- Never retry or fail over on `billingUnknown` kinds: the vendor may already have charged.
- Never route OVERSEAS unless the client key has `allowOverseas`.
- Never return or log provider secrets; use fingerprints.
- Prices other than Claude's in `application.yml` are placeholders.
