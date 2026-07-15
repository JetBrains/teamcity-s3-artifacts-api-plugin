# S3 API to access build artifacts

[![official JetBrains project](http://jb.gg/badges/official.svg)](https://github.com/JetBrains#jetbrains-on-github)

> [!IMPORTANT]
> Access is per-user: each request is signed with a TeamCity user's own S3
> secret and authorized against that user's permissions, so a user sees only the
> artifacts they could see in the TeamCity UI. Serve the endpoint over **HTTPS** —
> SigV4 authenticates the request but does not encrypt it. See
> [SECURITY.md](SECURITY.md) and the [Security](#security) section below.

TeamCity server-side plugin that exposes build artifacts over an
**S3-compatible HTTP API**. Any standard S3 client or SDK (`aws s3 ls`,
`aws s3 cp`, `aws s3api list-objects-v2`, backup tools, object browsers, …) can
traverse and download TeamCity build artifacts without a TeamCity-specific
integration.

The plugin maps TeamCity concepts onto the S3 object model:

| TeamCity            | S3                |
|---------------------|-------------------|
| Build configuration | Bucket            |
| Build number        | Object key prefix |
| Artifact path       | Object key        |

## Compatibility

The plugin is built against the TeamCity 2025.03 API (Kotlin 2.1.10, fetched by
the build).

## Installation

Build the plugin (see [Contributing guidelines](#contributing-guidelines)) to
produce `target/teamcity-artifacts-s3-plugin.zip`, then install it as an
[additional TeamCity plugin](https://www.jetbrains.com/help/teamcity/installing-additional-plugins.html):
upload the zip via **Administration | Plugins**, or drop it into
`<TeamCity Data Directory>/plugins/`, and enable it. The plugin supports runtime
(re)loading (`allow-runtime-reload="true"`).

## Usage

Point any S3 client at the plugin endpoint (`<TeamCity URL>/s3api`) and address
artifacts using a build configuration (external) id as the bucket, the build
number as the first key segment, and the artifact path as the rest of the key.

Authenticate as yourself: your **access key id is your TeamCity username**, and
the secret is the personal S3 secret you provision (see
[Provisioning credentials](#provisioning-credentials)). Any region works — it is
used only to build the SigV4 signature scope.

```bash
export AWS_ACCESS_KEY_ID="<your TeamCity username>"
export AWS_SECRET_ACCESS_KEY="<your provisioned S3 secret>"
export AWS_DEFAULT_REGION="us-east-1"   # any value; used only in the SigV4 scope
```

List the artifacts of a build, just like the build's **Artifacts** tab:

```bash
aws --endpoint-url "$TEAMCITY_URL/s3api" \
    s3 ls "s3://TestProject_Build/1/"
```

List objects with the standard `ListObjectsV2` operation — every object prefixed
by build `1`:

```bash
aws --endpoint-url "$TEAMCITY_URL/s3api" \
    s3api list-objects-v2 --bucket TestProject_Build --prefix "1/"
```

Download an artifact (for example, a build log) with a standard object copy:

```bash
aws --endpoint-url "$TEAMCITY_URL/s3api" \
    s3 cp "s3://TestProject_Build/1/build.log" ./build.log
```

## Provisioning credentials

Before using the S3 API, provision a personal S3 secret. The provisioning
endpoint lives at `<TeamCity URL>/s3credentials`, separate from the S3 endpoint,
and uses TeamCity's normal authentication — call it as you would any
authenticated TeamCity endpoint (an existing session, HTTP Basic, or a bearer
token). It returns JSON, and your access key id is always your TeamCity username.

```bash
# Generate (or rotate) your secret — the secret is returned exactly once
curl -X POST -u "<user>:<password>" "$TEAMCITY_URL/s3credentials"
# {"accessKeyId":"<user>","secretAccessKey":"<secret>"}

# Check whether you currently have a secret (never returns the secret itself)
curl -u "<user>:<password>" "$TEAMCITY_URL/s3credentials"
# {"accessKeyId":"<user>","hasSecret":true}

# Revoke your secret, disabling your S3 access until you regenerate
curl -X DELETE -u "<user>:<password>" "$TEAMCITY_URL/s3credentials"
```

Rotating replaces your previous secret immediately — the old one stops working.

Server administrators holding the global user-management permission can rotate or
revoke another user's secret (for offboarding or incident response) by adding
`?username=<user>`:

```bash
curl -X POST   -u "<admin>:<password>" "$TEAMCITY_URL/s3credentials?username=<user>"
curl -X DELETE -u "<admin>:<password>" "$TEAMCITY_URL/s3credentials?username=<user>"
```

## Security

Access is per-user and mirrors what each user can already see in the TeamCity UI.

- **Authentication.** Each request is authenticated with AWS Signature V4. The
  access key id is a **TeamCity username**; the server loads that user's
  provisioned secret, recomputes the signature, and compares it. There is **no
  default or shared secret** — an unknown or guest username, a username with no
  provisioned secret, or a bad signature is rejected (fail closed).
- **Authorization.** Each request is authorized against the **authenticated
  user's own permissions** and executed under that user's security context (not
  the server's). Reading a build configuration's artifacts requires both
  `VIEW_PROJECT` and `VIEW_BUILD_RUNTIME_DATA` on its project. `ListBuckets`
  returns only the build configurations the user may view.
- **Honest failures.** Authentication failures return HTTP **403**
  (`SignatureDoesNotMatch` or `InvalidAccessKeyId`). A bucket or object the user
  may not view returns HTTP **404** (`NoSuchBucket` / `NoSuchKey`) — identical to
  a genuinely missing resource, so the API never reveals the existence of things
  the caller is not allowed to see. All errors use S3-style `<Error>` XML.

The API is **read-only**: only `GET` (ListBuckets, ListObjectsV2, GetObject) and
`HEAD` are implemented; uploading artifacts (`PUT`) is not.

Serve the endpoint over **HTTPS** in any real deployment. See
[SECURITY.md](SECURITY.md) for the full security model and the remaining operator
hardening steps.

## License

Apache 2.0

## Feedback

Please feel free to post feedback in the repository
[issues](https://youtrack.jetbrains.com/issues/TW).

## Contributing guidelines

Follow the general instructions to
[build TeamCity plugins using Maven](https://plugins.jetbrains.com/docs/teamcity/developing-plugins-using-maven.html).

```bash
# build and package the plugin (produces target/teamcity-artifacts-s3-plugin.zip)
mvn clean package

# deploy the packed plugin to a local TeamCity installation and start the server + agent
mvn tc-sdk:start

# stop the locally running server and agent
mvn tc-sdk:stop
```
