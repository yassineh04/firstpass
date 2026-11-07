# 0005. Dashboard sign-in through GitHub App user authorization

- Status: Accepted
- Date: 2026-11-06

## Context

The dashboard must show each user only the installations and repositories they can access, and only repository admins may change settings. That information lives in GitHub and is available with a token acting for the user.

A GitHub App user access token is an OAuth token ([user access tokens](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app/generating-a-user-access-token-for-a-github-app)); GitHub does not document OpenID Connect ID tokens for this flow. Amazon Cognito user pools document native social sign-in for Google, Facebook, Amazon and Apple, not GitHub ([Cognito social identity providers](https://docs.aws.amazon.com/cognito/latest/developerguide/cognito-user-pools-social-idp.html)).

## Decision

The dashboard uses the GitHub App's own user authorization flow:

1. The API creates a random `state` (stored 10 minutes) and redirects to GitHub's authorization page.
2. On the callback, the API checks the `state` and exchanges the code for a user token; the client secret never leaves the server.
3. With that token, it reads the user, their installations and the repositories they can access, with their permission level.
4. It creates a 1-hour session holding those permissions, then discards the GitHub token: it is never stored.
5. It redirects to the dashboard with a one-time exchange code (valid 60 seconds), which the dashboard trades for a session JWT ([ADR 0006](0006-session-jwt-in-memory.md)).

Authorization is checked on every request from the session's permissions, never from a value sent by the client.

## Alternatives considered

- **Amazon Cognito.** Managed users and tokens, but GitHub is not a native provider, so it would need a custom bridge, and the GitHub permissions are needed anyway.
- **A separate OAuth app.** A second app to register and secure; the GitHub App can already authorize users.

## Consequences

- Positive: no extra identity service; permissions come straight from GitHub; no GitHub token is stored.
- Negative: permissions are a snapshot taken at sign-in; a change on GitHub shows after the next sign-in (at most 1 hour later).
