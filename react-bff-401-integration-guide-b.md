# React-to-BFF Authentication Integration Guide

## Decision Summary

The React application does **not** need a mandatory `/api/session` endpoint. The application can rely on a centralized `401 Unauthorized` handler to begin login when a protected BFF API says authentication is required.

React does not own authentication policy. It must not maintain a map of which BFF endpoints are public versus protected. The Spring Boot BFF owns that policy and marks endpoints as public (`permitAll`) or authenticated/authorized.

The BFF owns Cognito/CAS federation, OAuth authorization-code exchange, Cognito JWT storage and refresh, Valkey-backed BFF sessions, and all downstream bearer-token calls. React never receives or manages JWTs.

## Core Contract

### React calls only the BFF

All BFF calls include the browser's opaque BFF session cookie:

```ts
fetch("/api/...", {
  credentials: "include"
});
```

React must not:

- Call Cognito directly
- Construct Cognito or CAS login URLs
- Read, store, decode, refresh, or forward JWTs
- Add `Authorization: Bearer` headers for the end user
- Handle OAuth authorization codes, OAuth `state`, PKCE, SAML, CAS tickets, or BFF session-cookie contents
- Maintain a list of endpoints that require a JWT

### BFF API outcomes

| BFF response | Meaning | React behavior |
|---|---|---|
| `2xx` | Request succeeded; endpoint might be public or authenticated | Process payload normally |
| `401` with `AUTHENTICATION_REQUIRED` | This request needs a valid BFF session and none is usable | Initiate one BFF login navigation; do not retry |
| `403` | User is authenticated but lacks required permission | Display authorization failure; do not login |
| Other `4xx` | Validation, missing resource, conflict, etc. | Handle as normal application error |
| `429` | Rate limit | Use normal retry/backoff UX if appropriate |
| `5xx`, timeout, network failure | Service/infrastructure issue | Show retry/error UX; do not login |

The BFF should return a stable authentication response such as:

```http
HTTP/1.1 401 Unauthorized
Content-Type: application/problem+json
Cache-Control: no-store

{
  "type": "https://app.mydomain.com/problems/authentication-required",
  "title": "Authentication required",
  "status": 401,
  "code": "AUTHENTICATION_REQUIRED"
}
```

Public BFF endpoints must not return `401` simply because a caller has no session. They should return their normal public result. An endpoint that optionally personalizes data should return a public response when unauthenticated and add personalization only when an authenticated BFF session exists.

## Why React Handles `401`

OAuth/Cognito authentication is a browser-navigation flow:

```text
Browser → BFF /auth/login → Cognito → CAS SSO (if configured)
        → Cognito → BFF OAuth callback → React route
```

A redirect generated in response to a background React `fetch()` call does not reliably navigate the visible browser page. It may be followed internally by `fetch()`, yielding login-page HTML or cross-origin/CORS behavior instead of a usable API result.

Therefore:

- BFF `/api/**` calls return `401` for missing/invalid sessions.
- React performs a **top-level browser navigation** to the BFF login endpoint.
- BFF performs all Cognito and CAS redirects thereafter.

React only knows the stable BFF login path, for example `/auth/login`. It does not know Cognito implementation details.

## Required React Implementation

### One shared API wrapper

All BFF requests use one shared wrapper—or the equivalent single Axios response interceptor. No screen/component should implement its own authentication behavior.

```ts
let loginInProgress = false;

function currentLocalRoute(): string {
  return (
    window.location.pathname +
    window.location.search +
    window.location.hash
  );
}

function beginLoginOnce(): void {
  if (loginInProgress) {
    return;
  }

  loginInProgress = true;

  const returnTo = encodeURIComponent(currentLocalRoute());
  window.location.assign(`/auth/login?returnTo=${returnTo}`);
}

export async function bffFetch(
  input: RequestInfo | URL,
  init: RequestInit = {}
): Promise<Response> {
  const response = await fetch(input, {
    ...init,
    credentials: "include",
    headers: {
      Accept: "application/json",
      ...init.headers,
    },
  });

  if (response.status === 401) {
    beginLoginOnce();
    throw new Error("Authentication required; BFF login navigation started");
  }

  return response;
}
```

Usage:

```ts
const response = await bffFetch("/api/search?q=foobar");
const data = await response.json();
```

### Retry policy

A `401` is not transient. React Query, SWR, Axios retry logic, and custom retry helpers must **not retry** it.

A suggested retry policy is:

```ts
function shouldRetry(failureCount: number, error: { status?: number }) {
  if (error.status === 401 || error.status === 403) {
    return false;
  }

  return failureCount < 2;
}
```

The exact retry policy for timeouts/5xx/429 is application-specific, but no retry policy may cause repeated login navigation for a `401`.

## Thundering-Herd Handling

There are two separate concurrency problems. `/api/session` is intentionally not required for either one.

### A. Many React API calls with no BFF session

Example: application startup mounts several components that call public and protected endpoints concurrently.

```text
/api/public/config       → 200 (public)
/api/dashboard           → 401 (protected, no BFF session)
/api/notifications       → 401 (protected, no BFF session)
/api/preferences         → 401 (protected, no BFF session)
/api/orders              → 401 (protected, no BFF session)
```

Required behavior:

1. The BFF returns fast `401` responses for protected APIs with no usable session.
2. The first completed `401` reaches the shared React wrapper.
3. `loginInProgress` changes from `false` to `true`.
4. React performs exactly one `window.location.assign("/auth/login?returnTo=...")` navigation.
5. Every later concurrent `401` detects `loginInProgress === true`, does not navigate, and fails only its local request.
6. The browser leaves the page for the BFF/Cognito login flow.

This is acceptable without `/api/session`. There can be one initial wave of 401 responses, but it causes only one user-visible login action and no repeated OAuth redirects.

### B. Many BFF calls need Cognito token refresh

Example: the Valkey session exists, but the server-held Cognito access token is expired or close to expiry when several BFF requests arrive.

This is handled entirely by the BFF:

```text
Requests A, B, C arrive with a refresh-needed access token
        ↓
BFF key: bff:refresh-lock:<session-or-authorized-client-id>
        ↓
A acquires short Valkey distributed lock
        ↓
A refreshes at Cognito and atomically saves new token state
        ↓
B and C wait/reload Valkey state
        ↓
All requests use refreshed token and call downstream APIs
```

Required BFF behavior:

- Use a short-lived distributed Valkey lock/single-flight operation keyed by BFF session ID or OAuth authorized-client ID.
- Only the lock holder calls Cognito refresh.
- Other requests wait briefly or poll/reload the updated token state.
- Store the refreshed authorized-client/token record atomically.
- Release the lock only if the process still owns it; use a TTL to protect against a crashed holder.
- On permanent refresh failure, invalidate the BFF session and return `401` to React.

React does not manage or know about token refresh.

### Optional cross-tab protection

The module-level `loginInProgress` variable protects one JavaScript page instance. If cross-tab coordination is desired, use an optional `sessionStorage` flag or `BroadcastChannel` message. This is not required for the initial implementation.

```ts
const LOGIN_FLAG = "bff.login.in.progress";

function beginLoginOnce(): void {
  if (loginInProgress || sessionStorage.getItem(LOGIN_FLAG) === "true") {
    return;
  }

  loginInProgress = true;
  sessionStorage.setItem(LOGIN_FLAG, "true");

  const returnTo = encodeURIComponent(currentLocalRoute());
  window.location.assign(`/auth/login?returnTo=${returnTo}`);
}
```

Clear the marker after a successful page reload and authenticated API result.

## `returnTo` Behavior

`returnTo` preserves the user’s React deep link after successful authentication.

If the current browser URL is:

```text
https://app.mydomain.com/search?q=foobar&page=4&sort=recent
```

React derives only the local route:

```text
/search?q=foobar&page=4&sort=recent
```

and starts login with:

```text
/auth/login?returnTo=%2Fsearch%3Fq%3Dfoobar%26page%3D4%26sort%3Drecent
```

After Cognito login, the BFF validates and stores the local destination, creates the Valkey-backed BFF session, and redirects the browser to:

```text
/search?q=foobar&page=4&sort=recent
```

### React responsibility

React should always construct `returnTo` this way:

```ts
const returnTo =
  window.location.pathname +
  window.location.search +
  window.location.hash;
```

Then encode once when placing it in the `/auth/login` query string.

React must not send:

- Full/absolute application URLs
- Cognito or CAS URLs
- External URLs
- Tokens, user IDs, claims, or authorization data

### BFF responsibility

The BFF must validate `returnTo`; React must not be trusted to enforce security. The BFF accepts only local relative SPA destinations and rejects external or ambiguous values, including:

```text
https://evil.example/    rejected
//evil.example/          rejected
/\\evil.example/         rejected
javascript:...           rejected
```

The BFF should validate the path (for example, accepted SPA route prefixes) while preserving normal query strings and fragments. Query values are ordinary application input; they do not need individual `returnTo` allowlist entries once the destination is confirmed to be local.

## BFF Responsibilities

### Access policy remains server-side

The BFF must own public versus protected endpoint policy. Example Spring Security structure:

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(
                "/auth/login",
                "/auth/logout",
                "/oauth2/**",
                "/login/**",
                "/health"
            ).permitAll()

            .requestMatchers(HttpMethod.GET, "/api/public/**").permitAll()
            .requestMatchers(HttpMethod.GET, "/api/search/suggestions").permitAll()

            .requestMatchers("/api/**").authenticated()
            .anyRequest().denyAll()
        )
        .exceptionHandling(ex -> ex
            .defaultAuthenticationEntryPointFor(
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                new AntPathRequestMatcher("/api/**")
            )
        )
        .oauth2Login(Customizer.withDefaults())
        .oauth2Client(Customizer.withDefaults());

    return http.build();
}
```

Specific public rules must precede the broad protected `/api/**` rule.

### Login and callback lifecycle

1. React receives a protected-API `401` and performs one top-level navigation to `/auth/login?returnTo=...`.
2. BFF validates `returnTo` and saves it only in server-side state for the OAuth transaction.
3. BFF redirects the browser to Cognito’s authorization endpoint.
4. Cognito federates to CAS as configured; an existing CAS SSO session generally makes that step non-interactive.
5. Cognito returns an authorization code to the fixed BFF callback.
6. BFF exchanges the code, saves OAuth token state and BFF session state in Valkey, and sets an opaque `HttpOnly`, `Secure` BFF session cookie.
7. BFF redirects to the validated local React route.

### Downstream API proxying

On an authenticated call, the BFF:

1. Resolves the BFF session from the browser cookie and Valkey.
2. Loads a usable server-held Cognito access token; refreshes it through the BFF single-flight mechanism if needed.
3. Calls the downstream API with `Authorization: Bearer <access-token>`.
4. Returns only the permitted/appropriate API payload to React.

React never receives the Cognito JWT.

## End-to-End Flow

```mermaid
sequenceDiagram
    participant R as React SPA
    participant B as Spring Boot BFF
    participant V as Valkey
    participant C as Cognito / CAS SSO
    participant D as Downstream API

    par React starts concurrent calls
        R->>B: GET /api/public/config
        R->>B: GET /api/dashboard, credentials: include
        R->>B: GET /api/notifications, credentials: include
    end

    B-->>R: 200 public config
    B->>V: Load BFF session
    V-->>B: No valid session
    B-->>R: 401 AUTHENTICATION_REQUIRED
    B-->>R: 401 AUTHENTICATION_REQUIRED

    R->>R: First 401 sets loginInProgress = true
    R->>B: Top-level GET /auth/login?returnTo=/search?...
    R->>R: Later 401s do not navigate

    B-->>R: 302 Cognito authorization endpoint
    R->>C: Browser completes Cognito / CAS SSO flow
    C-->>B: OAuth callback with code + state
    B->>V: Save BFF session + server-held token state
    B-->>R: Set HttpOnly session cookie; 302 validated returnTo

    R->>B: GET /api/dashboard, credentials: include
    B->>V: Read BFF session + token state
    V-->>B: Valid session and token
    B->>D: Downstream API call with bearer access token
    D-->>B: 200 payload
    B-->>R: 200 application-safe JSON
```

## Delivery Checklist

### React team

- [ ] Route every BFF request through `bffFetch()` or one Axios interceptor.
- [ ] Include `credentials: "include"` on every BFF request.
- [ ] On the first `401`, set `loginInProgress` and perform exactly one top-level navigation to `/auth/login?returnTo=...`.
- [ ] On later concurrent `401` responses, do not start another navigation.
- [ ] Construct `returnTo` from `pathname + search + hash`; URL-encode it once.
- [ ] Do not retry `401` requests.
- [ ] Treat `403` as an authorization failure, not a login signal.
- [ ] Treat 5xx/timeouts/network failures as normal resilience/error states, not a login signal.
- [ ] Do not add Cognito, CAS, JWT, OAuth, or endpoint authorization-mapping logic to React.
- [ ] No `/api/session` endpoint is required for the initial implementation.

### BFF team

- [ ] Define public and protected API routes only in Spring Security.
- [ ] Return fast structured `401` responses, not OAuth redirects, for unauthenticated `/api/**` calls.
- [ ] Expose the BFF-owned `/auth/login` navigation endpoint.
- [ ] Validate `returnTo` as a local relative SPA destination and persist the validated value server-side during login.
- [ ] Complete Cognito OAuth code flow and CAS federation server-side.
- [ ] Store opaque session state and OAuth token state in Valkey; expose only an `HttpOnly`, `Secure` session cookie to the browser.
- [ ] Implement Valkey distributed single-flight/locking for concurrent access-token refresh.
- [ ] On permanent refresh failure or invalid server-held token, invalidate the BFF session and return `401`.
- [ ] Normalize downstream authentication failure to BFF `401`; preserve downstream `403` as `403` when appropriate.

## Final Model

```text
Public BFF call                 → BFF returns normal public payload
Protected BFF call + session    → BFF proxies downstream call with server-held Cognito JWT
Protected BFF call + no session → BFF returns 401
First React 401                 → One navigation to BFF /auth/login
Other concurrent React 401s     → No extra redirects
Expired server token            → BFF refreshes once using Valkey lock
Refresh failure                 → BFF returns 401; React begins one login navigation
```
