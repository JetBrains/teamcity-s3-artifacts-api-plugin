# Security Policy

## Security model

The plugin authenticates and authorizes **every** S3 request against a real
TeamCity user. A user sees, through the S3 API, exactly the build configurations
and artifacts they could see in the TeamCity UI — no more, no less — gated by a
credential only they hold.

### Authentication (per-user credentials)

- The S3 **access key id is the caller's TeamCity username.** Each user has at
  most one active S3 secret.
- The secret is a strong random value **generated server-side** and stored
  **reversibly-scrambled** against the user's profile using TeamCity's built-in
  scrambling utility, so the server can recompute the SigV4 HMAC. This encoding is
  reversible by necessity — the server must recover the secret to sign — so it
  guards against casual disclosure, **not** against an attacker with access to the
  server's data directory or database. The secret is returned in plaintext
  **once**, at generation time, and never again; there is **no default or shared
  secret** anywhere in the source.
- Requests are verified with AWS Signature V4: the server resolves the username,
  loads and decrypts that user's secret, recomputes the signature, and compares
  it in constant time. Unknown usernames, the guest user, users with no
  provisioned secret, malformed authorization data, and bad signatures are all
  **rejected — the plugin fails closed** and never falls back to a default.

### Credential provisioning

A session-authenticated endpoint at `<TeamCity URL>/s3credentials` — separate
from the S3 endpoint and subject to TeamCity's normal authentication — manages
secrets:

- `POST /s3credentials` — generate or rotate your secret; returns it once.
- `GET /s3credentials` — report whether you have a secret (never returns it).
- `DELETE /s3credentials` — revoke your secret.

Rotating replaces the previous secret immediately; the old one stops validating.
A server administrator holding the **global user-management permission** — the
permission to change other users' accounts — may manage another user's secret by
adding `?username=<user>` to these requests, for offboarding and incident
response. Any other caller is limited to their own secret.

### Authorization

- Each read is authorized against the **authenticated user's own permissions**
  and executed under that user's security context — not the server's.
- Reading a build configuration's artifacts requires both **`VIEW_PROJECT`** and
  **`VIEW_BUILD_RUNTIME_DATA`** on its project. `VIEW_PROJECT` covers normal
  artifacts; `VIEW_BUILD_RUNTIME_DATA` covers hidden/internal artifacts (the
  `.teamcity` directory and internal build data), which the API also serves.
- `ListBuckets` returns only the build configurations the user may view.

### Failure semantics

| Condition                                                    | HTTP | S3 error code          |
|--------------------------------------------------------------|------|------------------------|
| Bad signature                                                | 403  | `SignatureDoesNotMatch` |
| Unknown user / no provisioned secret / malformed auth data   | 403  | `InvalidAccessKeyId`    |
| Bucket missing **or** not visible to the user                | 404  | `NoSuchBucket`          |
| Object missing **or** not visible to the user                | 404  | `NoSuchKey`             |

A permission-denied resource and a genuinely-missing one return the **same**
response, so the API cannot be used to probe the existence of build
configurations or artifacts the caller may not see. All errors use S3-style
`<Error>` XML.

## Scope

- **Read-only.** Only `GET` (ListBuckets, ListObjectsV2, GetObject) and `HEAD`
  are implemented; uploading artifacts (`PUT`) is not.
- **Header-based SigV4 only.** Presigned-URL / query-parameter authentication is
  not supported.
- **One secret per user.** The access key id is the username, so rotation
  replaces the single active secret.

## Operator hardening

The per-user authentication and authorization described above are implemented in
the plugin. Two items remain the operator's responsibility before a real
deployment:

- **Serve over HTTPS.** SigV4 authenticates the request but does not encrypt it;
  without TLS, artifact bytes and request headers travel in the clear.
- **Review the S3 endpoint's interceptor handling.** The S3 controller
  (`/s3api`) is annotated `@SkipInternalInterceptors` because it performs its own
  SigV4 authentication rather than using TeamCity's session interceptors. The
  provisioning controller (`/s3credentials`) does **not** skip them. Confirm this
  split fits your deployment's threat model.

## Reporting a vulnerability

Please use JetBrains' coordinated disclosure process rather than a public issue:

- Policy: <https://www.jetbrains.com/legal/docs/terms/coordinated-disclosure/>
- Email: security@jetbrains.com (PGP key available on public key servers;
  fingerprint `054C C087 8CA3 B1F0 DDBF 7A20 EF5A 4244 1A9D 68C8`)

Please include a description and a working proof of concept, and do not disclose
the issue publicly until it has been addressed.
