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
 * Base type for every request-authentication failure raised by [S3AccessManager].
 *
 * Sealed so the mapping to an S3-style error ([toS3Error]) is exhaustive: adding a new failure
 * mode forces the mapping to be updated. There are deliberately only two concrete failures — a bad
 * signature versus an unresolvable identity — because that is the entire distinction S3 clients
 * are told about.
 */
sealed class S3AuthenticationException(message: String) : RuntimeException(message)

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
