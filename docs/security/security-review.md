# Security review (SEC-01)

**Date:** 2026-11-14
**Scope:** the backend (`backend/`), the SPA and its nginx (`frontend/`), the Compose stack and CI. Checked against the OWASP Top 10 (2025).
**Out of scope:** production hosting (OPS-02: TLS, HSTS, firewalling, secrets storage) and privacy (SEC-02).

The review read the code, then turned every finding that can regress into an automated check. They're listed under [Guards](#guards).

## Summary

| Area | Status | Notes |
|---|---|---|
| A01 Broken access control | OK | Owner-scoped repositories by construction; 404 for other users' data |
| A02 Security misconfiguration | **Fixed** | Security headers and a strict CSP were missing; added to nginx and the API |
| A03 Software supply chain | **Fixed** | No dependency scanning; added Dependabot and an npm audit gate |
| A04 Cryptographic failures | OK | BCrypt passwords, HS256 JWT with a required ≥ 32-byte secret, hashed tokens |
| A05 Injection | OK | No SQL built from input; Markdown rendered without raw HTML; prompts treat input as data |
| A06 Insecure design | OK | Business rules in deterministic code; AI output validated before use |
| A07 Authentication failures | OK, one known gap | Rotation with reuse detection, rate limits; access tokens outlive account deletion by ≤ 15 min |
| A08 Software or data integrity | OK | Pinned dependencies and lockfiles; AI answers parsed into records only |
| A09 Logging and alerting | Partly | Correlation IDs, no secrets in logs; alerting waits for OPS-04 |
| A10 Mishandling of exceptional conditions | OK | One error path; 500s never leak internals |

## Findings and fixes

### 1. Missing security headers (A02), fixed

nginx served the app with no security headers, and the API had only Spring Security's defaults.

- **The app (`frontend/security-headers.conf`):** nginx now sends:
  - **Content-Security-Policy:** `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`
  - **Other headers:** `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin`, a `Permissions-Policy` that turns off camera, microphone, geolocation, payment and USB, and `Cross-Origin-Opener-Policy: same-origin`.
  - **No version:** `server_tokens off` hides the nginx version.
- **Where they apply:** each location that serves the app includes the headers. That's needed because nginx drops inherited `add_header` in a location with its own (`/assets/` sets `Cache-Control`). The proxied `/api/` deliberately doesn't include them, so API responses carry only the backend's set and never two conflicting ones.
- **No inline script:** the CSP allows no inline scripts. The pre-paint theme script in `index.html` moved to `public/theme-init.js`.
- **React styles:** React sets inline styles through the CSSOM, which `style-src` doesn't restrict. The end-to-end journey runs under this policy with no violations.
- **The API (`SecurityConfig`):**
  - **On top of Spring's defaults** (`nosniff`, `Cache-Control: no-store`, HSTS on HTTPS requests): `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer` and the same `Permissions-Policy`.
  - **A CSP of its own on `/api/**`:** `default-src 'none'; frame-ancestors 'none'; base-uri 'none'`, since a JSON response is never a page.
  - **Swagger UI** (dev only) is outside `/api` and keeps working.
- **HSTS:** it belongs to the HTTPS proxy in front of the containers (OPS-02). Sent over plain HTTP it's ignored, and on `localhost` it would pin developers' browsers to HTTPS.

### 2. No dependency scanning (A03), fixed

- **Dependabot (`.github/dependabot.yml`):** weekly updates for Maven, the frontend's npm packages and the Docker base images; monthly for `e2e/`, Compose and GitHub Actions. Related packages are grouped. Security updates also need "Dependabot security updates" switched on in the repository settings.
- **CI gate:** the frontend job runs `npm audit --omit=dev --audit-level=high`, so a known high or critical vulnerability in code that ships to browsers fails the build. It passed with 0 vulnerabilities.
- **Accepted, dev only:** `npm audit` with dev dependencies reports one moderate advisory, GHSA-g3hv-75wg-m249: a ReDoS in `minimatch` through typescript-eslint.
  - It only runs at lint time on our own files and never ships.
  - The only fix offered is a breaking downgrade of typescript-eslint, so it waits for an upstream release, which Dependabot will propose.
- **No OWASP dependency-check for Maven:** it needs an NVD API key and adds minutes to every build. GitHub's advisory database, behind Dependabot, covers Maven Central too. Revisit if releases need an attested SBOM scan.

### 3. A convention break: the system clock (A10, hygiene), fixed in QA-06

`GlobalExceptionHandler` stamped errors with `Instant.now()`. It now uses the injected `Clock`, and Checkstyle forbids the system clock in app code.

## Checked and fine

- **Access control (A01):**
  - **Repositories:** every user-owned entity's repository extends `OwnedRepository`, which only has owner-scoped methods. `OwnershipGuardTests` fails the build otherwise.
  - **Endpoints:** every endpoint with an ID is covered by `OwnershipAssertions`, and another user's resource is 404, never 403, so IDs can't be probed.
  - **Admin:** `/api/admin/**` needs `ROLE_ADMIN`, checked in the filter chain.
- **SQL injection (A05):**
  - **Queries:** all JDBC calls use `?` or `:named` parameters, JPQL uses parameters, and the search fragments use the Criteria API.
  - **Built-up SQL:** the few fragments built in code (a partition column, optional `and … in (:ids)` filters, constant `@Query` concatenations) are chosen from constants, never from input.
  - **Sorting:** `JpaSort.unsafe` only ever receives names from a whitelist (`SORTABLE`); anything else is a 400.
  - **Search text:** `q` escapes `%` and `_`.
- **XSS (A05):**
  - **React** escapes all text. There's no `dangerouslySetInnerHTML`, `innerHTML`, `eval` or `new Function` in the app.
  - **Markdown notes** render through react-markdown without `rehype-raw`, and its URL filter drops `javascript:` links. `GrammarTopicPage.test.tsx` checks `<script>`, `<img onerror>` and `javascript:` links.
  - **AI text** is rendered as text or that same Markdown.
- **Prompt injection (A05, AI-07):**
  - **Input:** learner input goes into prompts as delimited, escaped and length-limited data, with invisible characters and personal data stripped. Suspicious input is still sent, but its answer isn't cached for others.
  - **Output:** answers are only parsed into validated records and never executed. `NoCodeExecutionTests` forbids script engines, `Runtime.exec`, reflection loading and Java deserialization.
- **Input validation:**
  - **Bodies:** every request body is `@Valid` with Bean Validation limits.
  - **The one exception:** the catalog's optional `{status}`, which is a single enum, and the domain refuses MASTERED.
  - **Paths and uploads:** path IDs are typed `UUID`, so malformed IDs are 400 before any query. CSV import is capped at 2 MB and 2000 rows.
- **CSRF:** disabled on purpose. The API authenticates with a header, and the only cookie (the refresh token) is `HttpOnly; Secure; SameSite=Strict; Path=/api/auth`.
- **CORS:**
  - **Origins:** an explicit list (`CORS_ALLOWED_ORIGINS`), with no wildcard and nothing by default in prod.
  - **Compose fix (QA-05):** Compose sets the list from `FRONTEND_PORT`. Before that, POSTs from a changed port got 403.
- **Authentication (A07):**
  - **Passwords:** BCrypt through Spring's delegating encoder. The policy requires 10+ characters, at most 72 bytes, and nothing from the common-password list.
  - **Tokens:**
    - Access tokens last 15 minutes and are kept in memory only.
    - Refresh tokens rotate; reusing one revokes its whole family.
    - Email and reset tokens are single-use, stored hashed, and short-lived.
  - **Rate limits:** login allows 10 per minute and registration 5 per hour, per IP, enforced before password hashing.
  - **Enumeration:** a password-reset request always answers 202, so it reveals nothing.
- **Cryptography (A04):**
  - **JWT secret:** required in prod, and a short one fails at startup.
  - **Stored tokens:** hashed with SHA-256.
  - **Secrets:** they never enter the repo or images (`.gitignore`, `.dockerignore`). The AI key's `toString()` is masked.
- **Error handling (A10):** every error goes through `GlobalExceptionHandler` as RFC 7807. A 500 never contains the exception message, which is logged with the correlation ID instead.
- **Actuator:** only `health` is exposed. Prod never shows details; dev shows details for local debugging.
- **SSRF:** the only outbound call is to the AI provider, at a fixed base URL from configuration. User input never builds a URL the server fetches.

## Known gaps and follow-ups

- **Access tokens outlive deletion (A07):**
  - After an account is deleted or disabled, its access token works for up to 15 minutes. Deleted accounts get 401 from `UserProfileService.currentUser`, but other endpoints rely on the token.
  - Refresh is blocked at once.
  - Fix if needed: a short denylist or a per-user token version.
- **Rate limits are per instance:** the buckets live in each instance's memory. Move them to Redis or JDBC before scaling out (noted in CLAUDE.md).
- **Health is public:** `/actuator/health` is reachable through nginx. Prod shows no details, but OPS-02 should still keep it internal or behind the load balancer.
- **Alerting (A09):** logs carry correlation IDs and never secrets or prompts, but nothing alerts on 5xx spikes, repeated 401s or AI failures yet. That's OPS-04.
- **HSTS and TLS** belong to the deployment (OPS-02).

## Guards

These keep the fixes from regressing:

| Guard | Checks |
|---|---|
| `SecurityHardeningTests` (backend) | API headers on success, 401 and 403; the API CSP only on `/api`; SQL meta-characters in `q` stay literal and owner-scoped; non-whitelisted sort names are 400; malformed IDs are 400 |
| `contentSecurityPolicy.test.ts` (frontend) | The CSP allows `'self'` scripts only (no `unsafe-inline` or `unsafe-eval`) and forbids framing; `index.html` has no inline script, style or handler; every served nginx location includes the headers and proxied ones don't |
| `learner-journey.spec.ts` (e2e) | The whole learner journey runs under the real CSP with no violations |
| CI `npm audit --omit=dev --audit-level=high` | No known high or critical vulnerability in shipped npm packages |
| Dependabot | Weekly or monthly update pull requests for every ecosystem |
| Existing | `OwnershipGuardTests`, `OwnershipAssertions`, `NoCodeExecutionTests`, `PromptSafetyTests`, the Markdown XSS test, and Checkstyle's clock and logging rules (QA-06) |
