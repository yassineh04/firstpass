# 0014. Low-level DynamoDB client with explicit mappers

- Status: Accepted
- Date: 2026-11-11

## Context

ADR 0004 puts every entity in one DynamoDB table. The plan was to map entities with the DynamoDB Enhanced Client of the AWS SDK for Java v2. Writing the persistence adapter showed three problems:

- The daily quota needs an `UpdateItem` that adds 1 to a counter only while it is under the limit: `ADD reviews :one` with the condition `attribute_not_exists(reviews) OR reviews < :limit`. The Enhanced Client's `updateItem` writes the attributes of an object. The developer guide documents `ADD`-style changes only through extensions, such as the atomic counter extension or a custom extension that returns an `UpdateExpression` ([extensions](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ddb-en-client-extensions-custom.html)).
- Immutable classes need a builder class with one setter per attribute ([immutable data classes](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/ddb-en-client-use-immut.html)). The core models are Kotlin data classes without builders, and core must not depend on the SDK.
- The table holds 12 kinds of items in one key space. Each store reads and writes a few attributes of its own entity, which plain attribute maps express directly.

## Decision

The persistence adapter uses the low-level `DynamoDbClient`. Each store maps its core model to and from attribute maps in a few lines, with shared helpers for values, keys and reads. Conditional writes, atomic counters, queries and batch writes use the DynamoDB expressions documented in the [developer guide](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Expressions.html).

The client uses the URLConnection HTTP client. The SDK adds the Apache 5 and Netty clients by default; both are excluded ([URLConnection HTTP client](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/http-configuration-url.html)).

## Alternatives considered

- **Enhanced Client with a custom extension for the counters.** It keeps typed schemas, but it needs one builder class per entity, an extension for the quota, and two ways of writing to the same table.
- **Enhanced Client for reads and simple writes, low-level client for counters.** Two mapping styles for one table make the attribute names easier to get wrong.
- **AWS CRT HTTP client.** AWS recommends it where it fits, but it ships native code and makes the package larger. URLConnection is enough for a few calls per job.

## Consequences

- Positive: every expression sent to DynamoDB is visible in the code; there is no reflection or bean introspection at startup; the persistence adapter has one SDK dependency besides the HTTP client.
- Negative: attribute names are strings repeated in each store's mapper. Tests against DynamoDB Local, started by Testcontainers, cover every mapping, condition and query.
