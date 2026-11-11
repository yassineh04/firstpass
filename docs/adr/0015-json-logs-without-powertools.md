# 0015. JSON logs with a small logger, without Powertools

- Status: Accepted
- Date: 2026-11-12

## Context

The Lambdas must write structured logs that CloudWatch Logs Insights can query by field: level, message, delivery ID, repository, pull request, step, duration and provider, never code or secrets. The first plan used the logging module of [Powertools for AWS Lambda (Java)](https://docs.powertools.aws.dev/lambda/java/).

For Java, that module builds on a logging framework (Log4j 2 or Logback) and, for its annotations, on AspectJ weaving. Each adds classes to load at startup and size to the package, which is uploaded directly to Lambda with a 50 MB limit ([Lambda quotas](https://docs.aws.amazon.com/lambda/latest/dg/gettingstarted-limits.html)). FirstPass logs only a few lines per request.

## Decision

A small `JsonLog` class in the `aws` module writes one JSON object per line on standard output: timestamp, level, message and the fields the caller passes. Callers pass identifiers only. Lambda sends standard output to CloudWatch Logs, and Logs Insights reads the fields of each JSON line.

Whether to also turn on Lambda's JSON log format for the runtime's own lines is decided when the logs are reviewed in production (phase 3).

## Alternatives considered

- **Powertools logging.** Ready-made fields and sampling, but a logging framework and AspectJ for a handful of lines per request.
- **SLF4J with a JSON encoder.** A standard API, but still a logging backend and its configuration to package.
- **Plain text lines.** No dependency either, but no field-level queries in Logs Insights.

## Consequences

- Positive: no dependency, nothing to configure, and the fields of every line are visible in one place.
- Negative: no log levels per package and no sampling. If FirstPass ever needs them, this decision can be superseded.
