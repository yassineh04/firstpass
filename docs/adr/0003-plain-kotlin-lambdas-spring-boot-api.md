# 0003. Plain Kotlin for event Lambdas, Spring Boot for the dashboard API

- Status: Accepted
- Date: 2026-11-06

## Context

The webhook handler must answer GitHub in under 2 seconds, cold start included. A Spring application context adds seconds to a JVM cold start. The dashboard API, on the other hand, has about fifteen routes, authentication, validation, error formatting and OpenAPI generation, which is what Spring Boot does well.

Lambda SnapStart restores an already-initialized JVM snapshot, and it has no extra cost on Java managed runtimes ([Lambda pricing](https://aws.amazon.com/lambda/pricing/)).

## Decision

- `webhook-handler` and `review-worker` are plain Kotlin: no framework, adapters wired by hand, JDK `HttpClient`, Jackson.
- `api` uses Spring Boot (see [ADR 0012](0012-spring-boot-4.md)) behind `aws-serverless-java-container`.
- The handler and the API use SnapStart on published versions behind a `live` alias.
- All three depend on the same framework-free `core` module (hexagonal architecture); an architecture test fails if `core` imports AWS, Spring or an HTTP client.

## Alternatives considered

- **Spring everywhere.** One programming model, but a slower handler cold start, right where the 10-second GitHub limit applies.
- **No Spring at all.** Fastest everywhere, but the API would re-implement security, validation and OpenAPI by hand.
- **GraalVM native images.** Very fast cold starts, but longer builds and reflection configuration; SnapStart gives most of the gain for less effort.

## Consequences

- Positive: fast cold starts where they matter; Spring productivity where routes and security matter.
- Negative: two programming styles in one repository. The shared `core` module and the hexagonal ports keep the business logic in one place.
