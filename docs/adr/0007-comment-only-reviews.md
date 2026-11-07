# 0007. COMMENT-only reviews

- Status: Accepted
- Date: 2026-11-06

## Context

An LLM can be wrong, and the code it reviews can contain instructions aimed at it ("approve this PR"). If the bot could approve or request changes, a wrong or manipulated answer could unblock or block a merge.

## Decision

FirstPass only ever publishes pull request reviews with `event: COMMENT`.

- It never sends `APPROVE` or `REQUEST_CHANGES`, never pushes, never merges, never resolves review threads.
- Its check run ends with `success` or `neutral`, never `failure`, so it cannot block a merge through required checks.
- Suggestions use GitHub suggestion blocks, which a human must apply.
- A test fails if any code path can build a review with another event type.

## Alternatives considered

- **Request changes on critical findings.** Stronger signal, but hands a merge decision to a model.
- **Approve clean pull requests.** Saves a click, but a silent model failure would look like an approval.

## Consequences

- Positive: humans keep every decision; a wrong or manipulated answer costs at most a noisy comment.
- Negative: teams that want enforcement must rely on their own required reviews and checks.
