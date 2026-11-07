# 0010. Hash routing for the dashboard on GitHub Pages

- Status: Accepted
- Date: 2026-11-06

## Context

The dashboard is a Vue single-page app hosted for free on GitHub Pages. GitHub Pages serves static files and lets a site define a custom 404 page; it documents no rewrite rules ([custom 404 page](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-custom-404-page-for-your-github-pages-site)). With history-mode routing, opening a deep link such as `/repos/42` directly would hit a file that does not exist.

## Decision

The dashboard uses Vue Router's hash history (`createWebHashHistory`): routes look like `/#/repos/42`. The sign-in callback returns to `/#/auth?code=…`.

## Alternatives considered

- **History mode with a `404.html` redirect trick.** Clean URLs, but every deep link first returns a 404 status, which is confusing for tools and users.
- **Another static host with rewrites.** Possible, but adds a provider; S3 with CloudFront is excluded by the $0 rule.

## Consequences

- Positive: deep links and reloads work on GitHub Pages with no trick.
- Negative: URLs contain `#/`. In-page anchors cannot use the URL hash, so skip links and section links move focus with JavaScript instead.
