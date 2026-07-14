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
 * Pure mapping from a typed authentication failure to its S3 error code and HTTP status.
 *
 * A bad signature maps to `SignatureDoesNotMatch`; an unresolvable identity (unknown/guest user,
 * no provisioned secret, or malformed authorization data) maps to `InvalidAccessKeyId`. Both are
 * HTTP 403 — authentication failures are never served and never leak which check failed beyond
 * these two codes.
 */
fun toS3Error(ex: S3AuthenticationException): S3Error = when (ex) {
    is SignatureMismatchException -> S3Error(
        code = "SignatureDoesNotMatch",
        message = ex.message ?: "The request signature does not match the provisioned secret.",
        httpStatus = 403
    )
    is InvalidAuthDataException -> S3Error(
        code = "InvalidAccessKeyId",
        message = ex.message ?: "The access key id does not identify a valid user.",
        httpStatus = 403
    )
}

/**
 * Serializes this error as the S3 `<Error>` XML document, escaping text so a message can never
 * break out of its element. An optional [resource] (e.g. the requested path) is included when given.
 */
fun S3Error.toXml(resource: String? = null): String = buildString {
    append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    append("<Error>")
    append("<Code>").append(xmlEscape(code)).append("</Code>")
    append("<Message>").append(xmlEscape(message)).append("</Message>")
    if (resource != null) {
        append("<Resource>").append(xmlEscape(resource)).append("</Resource>")
    }
    append("</Error>")
}

private fun xmlEscape(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
