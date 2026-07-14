# S3 API to access build artifacts

[![official JetBrains project](http://jb.gg/badges/official.svg)](https://github.com/JetBrains#jetbrains-on-github)

> [!WARNING]
> **Reference implementation — not production-ready.** Authentication and
> authorization are intentionally stubbed: the plugin ships with a **hardcoded,
> source-visible secret**, performs **no per-user permission checks**, and runs
> every request **with full server (system) privileges**. Deployed as-is,
> anyone who can reach the endpoint can read **any** build artifact on the
> server, which can expose secrets. **Do not deploy it as-is.** See
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
Configure your client with any access key id and the secret described in
[Security](#security).

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

## Security

> ⚠️ **This is a demo-grade authentication layer — do not deploy it as-is in
> production.**

The plugin performs genuine **AWS Signature V4** verification of the request
math, but the surrounding authentication and authorization are intentionally
stubbed for the demo:

- **Hardcoded secret** — the secret lookup returns a fixed `"your-secret-key"`
  for *any* access key id, so any request signed with that secret is accepted.
- **No permission check** — the permission check is empty; access to build
  configurations and artifacts is **not** authorized per user.
- **Runs as system** — requests execute under `SecurityContextEx.runAsSystem`,
  i.e. with full server privileges.

The API is also **read-only**: only `GET` (ListBuckets, ListObjectsV2,
GetObject) and `HEAD` are implemented. Uploading artifacts (`PUT`/PutObject) is
**not** implemented, despite the wording in the plugin descriptor.

See [SECURITY.md](SECURITY.md) for the full security model, the recorded risk
acceptance, a hardening checklist for real deployments, and how to report
vulnerabilities.

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
