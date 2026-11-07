# 0008. Read the configuration from the base branch

- Status: Accepted
- Date: 2026-11-06

## Context

Each repository can tune FirstPass with a `.firstpass.yml` file: language, minimum severity, excluded paths, maximum comments, custom instructions for the model. If the file were read from the pull request branch, a pull request could change the rules of its own review, for example exclude the file it modifies or inject instructions into the prompt.

## Decision

The worker reads `.firstpass.yml` from the pull request's base branch only. Settings are merged in this order: built-in defaults, then dashboard settings, then the file.

- An invalid file does not stop the review: defaults apply and the review summary shows the validation error.
- The file can disable reviews, but it can never enable a private repository; that needs an admin's recorded consent in the dashboard.
- Custom instructions are capped at 1,000 characters and placed after the fixed rules of the prompt.

## Alternatives considered

- **Read from the pull request branch.** Lets authors try new settings in the same pull request, but lets any pull request weaken its own review.
- **Dashboard only.** Safe, but configuration would no longer live with the code.

## Consequences

- Positive: a pull request cannot loosen the review it receives.
- Negative: a change to `.firstpass.yml` takes effect only after it is merged into the base branch.
