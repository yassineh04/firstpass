# 0012. Spring Boot 4 for the dashboard API

- Status: Accepted
- Date: 2026-11-06

## Context

The dashboard API runs Spring Boot on AWS Lambda through `aws-serverless-java-container`.

- The current Spring Boot generation is 4 (4.1 at the time of writing) ([Spring Boot](https://spring.io/projects/spring-boot)).
- Spring Boot supports each minor version for at least 12 months ([supported versions](https://github.com/spring-projects/spring-boot/wiki/Supported-Versions)). Version 3.5, the last 3.x line, was released in May 2025, so its open-source support has most likely ended.
- `aws-serverless-java-container` 3.x supports Spring Boot 4 through the `aws-serverless-java-container-springboot4` artifact; Spring Boot 3 stays on the older 2.1.x line ([repository](https://github.com/aws/serverless-java-container)). The library is under the Apache 2.0 license.

## Decision

The `api` module uses Spring Boot 4.x (Spring Framework 7, Jakarta EE 11) with `aws-serverless-java-container` 3.x. Exact versions are pinned in the Gradle version catalog when the module is built, in phase 4.

## Alternatives considered

- **Spring Boot 3.5.** More tutorials available, but no longer maintained for free, and only supported by the older container library line.

## Consequences

- Positive: a maintained framework and library line for the whole project.
- Negative: fewer examples than for Boot 3. The compatibility of springdoc-openapi and of SnapStart with Boot 4 is checked at the start of phase 4.
