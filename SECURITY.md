# Security Policy

## Status: reference implementation — not production-ready

This plugin is a **demo / reference implementation**. Its authentication and
authorization layers are **intentionally stubbed**. Do **not** deploy it as-is
on any TeamCity server whose artifacts you care about.

## Known limitations (by design)

The plugin verifies the AWS Signature V4 request math correctly, but the
surrounding identity and access-control layers are not implemented:

| Area         | Current behavior                                                              | Impact                                                        |
|--------------|------------------------------------------------------------------------------|--------------------------------------------------------------|
| Secret lookup | Returns a fixed, source-visible secret (`"your-secret-key"`) for *any* access key id | Anyone who reads this repository can produce valid signatures |
| Authorization | `checkPermission()` is empty                                                | No per-user / per-build access control                        |
| Privilege     | Every request runs under `SecurityContextEx.runAsSystem`                    | Requests execute with full server privileges                 |
| Interceptors  | The controller is annotated `@SkipInternalInterceptors`                     | TeamCity's own auth interceptors are bypassed                 |

**Combined effect:** a party who can reach `<TeamCity URL>/s3api` can list and
download **any** build artifact on the server — including artifacts that may
contain credentials or other secrets — without authenticating as a real user.

## Risk acceptance

These limitations are **known and accepted** for the sole purpose of publishing
this project as a reference implementation. They are documented here and in the
[README](README.md) so that anyone evaluating the code understands the security
model before deploying it. This acceptance covers **only** the intentional stubs
listed above.

- Accepted: 2026-07-14
- Owner: JetBrains TeamCity team

## Hardening checklist (before any real deployment)

- Replace the hardcoded secret with a real credential store keyed per access key
  id, with **no default** — fail closed when unconfigured.
- Implement `checkPermission()` to authorize each request against the
  authenticated user's permissions for the requested build configuration and
  artifact.
- Remove `runAsSystem`; execute under the authenticated principal's own security
  context.
- Reconsider `@SkipInternalInterceptors` so the platform's authentication and
  authorization interceptors apply.
- Serve only over HTTPS.

## Reporting a vulnerability

The items under [Known limitations](#known-limitations-by-design) are **not**
new vulnerabilities — please do not file reports for them.

For any **other** security issue, please use JetBrains' coordinated disclosure
process rather than a public issue:

- Policy: <https://www.jetbrains.com/legal/docs/terms/coordinated-disclosure/>
- Email: security@jetbrains.com (PGP key available on public key servers;
  fingerprint `054C C087 8CA3 B1F0 DDBF 7A20 EF5A 4244 1A9D 68C8`)

Please include a description and a working proof of concept, and do not disclose
the issue publicly until it has been addressed.
