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
 * An S3-style error: the client-facing error [code], a human-readable [message], and the
 * [httpStatus] to return. Mirrors the fields of the S3 `<Error>` document.
 */
data class S3Error(val code: String, val message: String, val httpStatus: Int)

/**
 * Pure mapping from a typed [S3ApiException] to its S3 error code and HTTP status.
 *
 * Authentication (403): a bad signature maps to `SignatureDoesNotMatch`; an unresolvable identity
 * (unknown/guest user, no provisioned secret, or malformed authorization data) maps to
 * `InvalidAccessKeyId`. Not-found (404): a missing/forbidden bucket maps to `NoSuchBucket`, a
 * missing object to `NoSuchKey`.
 *
 * The client-facing [S3Error.message] is a fixed string per code, deliberately NOT the exception's
 * own (more specific) message. Within `InvalidAccessKeyId`, "unknown user", "no secret provisioned",
 * and "malformed auth data" must be indistinguishable to the caller; likewise `NoSuchBucket` must
 * look identical whether the bucket is genuinely absent or merely hidden by permissions — so the
 * API cannot be used to probe which usernames or build configurations exist. The specific exception
 * messages are retained for server-side logs.
 */
fun toS3Error(ex: S3ApiException): S3Error = when (ex) {
    is SignatureMismatchException -> S3Error(
        code = "SignatureDoesNotMatch",
        message = "The request signature we calculated does not match the signature you provided.",
        httpStatus = 403
    )
    is InvalidAuthDataException -> S3Error(
        code = "InvalidAccessKeyId",
        message = "The access key id you provided does not identify a user with a provisioned S3 secret.",
        httpStatus = 403
    )
    is NoSuchBucketException -> S3Error(
        code = "NoSuchBucket",
        message = "The specified bucket does not exist.",
        httpStatus = 404
    )
    is NoSuchKeyException -> S3Error(
        code = "NoSuchKey",
        message = "The specified key does not exist.",
        httpStatus = 404
    )
}

/**
 * Serializes this error as the S3 `<Error>` XML document, escaping text so a message can never
 * break out of its element.
 */
fun S3Error.toXml(): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    append("<Error>")
    append("<Code>").append(xmlEscape(code)).append("</Code>")
    append("<Message>").append(xmlEscape(message)).append("</Message>")
    append("</Error>")
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
