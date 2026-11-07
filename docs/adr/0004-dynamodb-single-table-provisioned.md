# 0004. DynamoDB single-table design in provisioned mode

- Status: Accepted
- Date: 2026-11-06

## Context

The project must cost $0 per month after AWS credits expire. Managed relational databases (RDS, Aurora) are not in the always-free allowances. The DynamoDB free tier gives 25 GB of storage, 25 write capacity units and 25 read capacity units per month, and it is tied to provisioned capacity and the Standard table class ([DynamoDB pricing](https://aws.amazon.com/dynamodb/pricing/provisioned/)).

All access patterns are known in advance: about a dozen, from "repositories of an installation" to "reviews of a pull request".

## Decision

One table, `firstpass`, with a composite key (`pk`, `sk`), one global secondary index (`gsi1pk`, `gsi1sk`) and a TTL attribute `expiresAt`.

- Provisioned mode: 5 RCU and 5 WCU for the table, 5 and 5 for the index, 10 and 10 in total. No auto scaling, never on-demand.
- Every screen and every worker step maps to a `GetItem` or a `Query`; no `Scan`.
- Point-in-time recovery is off (it is billed).
- Code rejects items whose `expiresAt` has passed, because TTL deletion can lag by days.

## Alternatives considered

- **PostgreSQL on RDS or Aurora.** Flexible queries, but not free over time, and it needs a VPC.
- **DynamoDB on-demand.** No capacity planning, but outside the free tier.
- **One table per entity.** Easier to read, but more capacity to provision under the same free limits.

## Consequences

- Positive: free within the allowances, single-digit-millisecond reads, TTL cleans old data.
- Negative: no ad-hoc queries; a new screen may need a new key design. Bursts above 5 WCU are throttled; the SDK retries, and review writes are batched.
