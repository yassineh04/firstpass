# 0016. Ready to deploy, without a cloud account

- Status: Accepted
- Date: 2026-11-13

## Context

FirstPass was designed to run on AWS always-free allowances (ADR 0001 to 0004, 0011, 0013). Running it there needs an AWS account, and AWS may ask for a payment method at sign-up to verify identity ([AWS Free Tier FAQ](https://aws.amazon.com/free/free-tier-faqs/)). The maintainer's rule is stricter than $0 a month: no payment method on any service, and nothing that can cost money later.

- The AWS Free plan cannot be charged, but the account closes after six months, and sign-up may still ask for a card ([AWS Free Tier](https://aws.amazon.com/free/)).
- The other clouds that run Java functions ask for a payment method as well, and the hosts that need none run JavaScript, not this Kotlin backend.
- FirstPass already reviews real pull requests on this machine: the `local-dev` module runs the webhook intake and the review worker against DynamoDB Local, with a development GitHub App whose webhooks are relayed by smee.

## Decision

FirstPass is kept **ready to deploy** to AWS, but it is not deployed.

- The infrastructure stays fully described in Terraform (`infra/`), and every pull request checks it offline: `terraform validate`, `terraform test` with a mocked provider, the resource allowlist, TFLint and Checkov.
- The Lambda handlers and their `.zip` packages are built and tested in CI.
- Demonstrations run with `local-dev`, on real pull requests of a sandbox repository.
- No AWS account, no HCP Terraform workspace and no deployment workflow are created. Without remote state to store, HCP Terraform is not needed, so this decision supersedes the state part of ADR 0011. The rest of ADR 0011 still holds.
- [docs/deploy.md](../deploy.md) explains how someone with their own AWS account could deploy FirstPass. It has not been run against a real account.

## Alternatives considered

- **AWS Free plan.** It cannot be charged and would allow a public deployment for at most six months, but it may need a card.
- **AWS Paid plan with a zero spend budget.** It allows a lasting deployment, but a budget only warns about a charge, it does not prevent one.
- **A GitHub Action instead of a GitHub App.** It would run on GitHub's free runners with no server at all, but it means redesigning storage, the dashboard and the installation flow in the middle of the project.

## Consequences

- Positive: no payment method anywhere, nothing to monitor or pay later, and the deployment remains possible for anyone with an AWS account.
- Negative: there is no public endpoint. Reviews happen only while `local-dev` runs, and the production checks (the GitHub `ping` delivery, the SQS polling cost, the alarms' notifications) are not measured.
