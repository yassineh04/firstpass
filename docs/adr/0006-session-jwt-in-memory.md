# 0006. Session JWT kept in memory, with silent re-login

- Status: Accepted
- Date: 2026-11-06

## Context

The dashboard is a single-page app on GitHub Pages (`*.github.io`), and the API runs on a Lambda function URL (`*.lambda-url.<region>.on.aws`). They are different sites, so a session cookie set by the API would be a third-party cookie for the dashboard, and browsers block or partition those (Safari blocks them by default). Storing a token in `localStorage` exposes it to any cross-site scripting bug.

## Decision

- After the GitHub sign-in ([ADR 0005](0005-github-app-user-authorization.md)), the API returns a session JWT (HS256, 1 hour, claims `sub`, `login`, `jti`).
- The SPA keeps the JWT only in memory (a Pinia store). It is never written to `localStorage`, `sessionStorage` or a cookie.
- The JWT is bound to a session item in DynamoDB, so logging out really revokes it.
- When the page reloads, the SPA starts the sign-in again. GitHub can send the user straight back when the app is already authorized; this behavior is confirmed in phase 4.

## Alternatives considered

- **JWT in `localStorage`.** Survives reloads, but any XSS can steal it.
- **Cross-site `HttpOnly` cookie.** Safe from XSS, but blocked or partitioned by browsers between `github.io` and `on.aws`.
- **Custom domain for both.** Would make the cookie first-party, but needs a paid domain and DNS setup.

## Consequences

- Positive: the token is never persisted in the browser; logout is a real revocation.
- Negative: a reload costs a round trip to GitHub. Sessions are short (1 hour), so the impact is small.
