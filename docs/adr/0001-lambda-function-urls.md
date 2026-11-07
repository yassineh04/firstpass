# 0001. Lambda function URLs instead of API Gateway

- Status: Accepted
- Date: 2026-11-06

## Context

FirstPass needs two public HTTPS endpoints: one for GitHub webhooks and one for the dashboard API. The project must cost $0 per month after AWS credits expire.

- API Gateway is billed per request; its free usage was historically limited to the first 12 months of an account.
- A Lambda function URL is a dedicated HTTPS endpoint for a function. The Lambda pricing page bills functions on requests and duration and shows no separate charge for function URLs ([Lambda pricing](https://aws.amazon.com/lambda/pricing/)).
- With `AuthType: NONE`, Lambda performs no authentication and anyone with the URL can invoke the function, once the resource-based policy grants public access ([function URL auth](https://docs.aws.amazon.com/lambda/latest/dg/urls-auth.html)).

## Decision

Both the webhook handler and the dashboard API are exposed through function URLs with `AuthType: NONE`, attached to a `live` alias.

- The webhook handler verifies the `X-Hub-Signature-256` HMAC on the raw body, in constant time, before doing anything else. An invalid signature gets `401`.
- The API checks the session JWT on every route except sign-in and health, and allows CORS only from the dashboard origin.

## Alternatives considered

- **API Gateway (HTTP or REST API).** Throttling, WAF, usage plans and custom domains, but billed per request once the free usage ends.
- **Application Load Balancer.** Billed per hour.
- **Function URL with `AuthType: AWS_IAM`.** GitHub cannot sign webhook requests with AWS Signature Version 4.

## Consequences

- Positive: no extra service and no extra cost; one less component to configure.
- Negative: no WAF, no per-client throttling and no native custom domain.
- Negative: anyone can call the URLs. Invalid requests are rejected in milliseconds, but each one still counts as a Lambda request (1 million free per month). Requests above the concurrency limit get `429` ([function URL configuration](https://docs.aws.amazon.com/lambda/latest/dg/urls-configuration.html)). Invocation counts are watched in phase 3.
