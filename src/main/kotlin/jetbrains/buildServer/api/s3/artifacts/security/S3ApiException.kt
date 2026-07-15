/*
 * Copyright 2000-2025 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.api.s3.artifacts.security

/**
 * Base type for every failure the S3 request pipeline raises and turns into an S3-style `<Error>`
 * response. Sealed so the mapping to an S3 error ([toS3Error]) stays exhaustive: adding a failure
 * mode forces the mapping to be updated.
 *
 * Two families sit under it — authentication ([S3AuthenticationException], HTTP 403) and
 * not-found/not-visible ([S3NotFoundException], HTTP 404) — because those are the only two things
 * the pipeline ever tells a client: "I could not authenticate you", or "that resource is not here".
 */
sealed class S3ApiException(message: String) : RuntimeException(message)

/**
 * Request authentication failed (HTTP 403). Raised by `S3AccessManager.authenticate`.
 *
 * There are deliberately only two concrete failures — a bad signature versus an unresolvable
 * identity — because that is the entire distinction S3 clients are told about.
 */
sealed class S3AuthenticationException(message: String) : S3ApiException(message)

/**
 * The request's identity was established, but the recomputed SigV4 signature did not match the one
 * the client presented (wrong secret, or a tampered/incorrectly-signed request).
 */
class SignatureMismatchException(message: String) : S3AuthenticationException(message)

/**
 * The request's identity could not be established. Covers missing or malformed SigV4 authorization
 * data, an access-key-id that maps to no user (or to the guest user), and a resolved user that has
 * no provisioned secret. Intentionally coarse so the API never discloses which of these it was.
 */
class InvalidAuthDataException(message: String) : S3AuthenticationException(message)

/**
 * The requested bucket or object is not available to the caller (HTTP 404). Raised by
 * `S3AccessManager.authorize` and by the read paths when an artifact cannot be found.
 *
 * A resource the caller may not view and a resource that genuinely does not exist both map here, to
 * the same code, so the API cannot be used to probe the existence of build configurations or
 * artifacts the caller is not allowed to see.
 */
sealed class S3NotFoundException(message: String) : S3ApiException(message)

/** The bucket (build configuration) does not exist, or the caller may not view its project. */
class NoSuchBucketException(message: String) : S3NotFoundException(message)

/** The object (artifact) does not exist within a bucket the caller can otherwise access. */
class NoSuchKeyException(message: String) : S3NotFoundException(message)
