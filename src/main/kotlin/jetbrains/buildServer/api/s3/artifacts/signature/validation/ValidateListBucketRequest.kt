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

package jetbrains.buildServer.api.s3.artifacts.signature.validation

import java.security.MessageDigest

fun hashSHA256Hex(data: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(data.toByteArray(Charsets.UTF_8))
    return toHex(hashBytes!!)
}

fun computeCanonicalRequest(method: String, path: String, queryString: String, headers: Map<String, String>, body: String): String {
    val canonicalQueryString = queryString.split("&")
        .filterNot { it.isNullOrBlank() }
        .sortedBy { it }
        .joinToString("&")

    val filteredHeaders = headers.entries
        .filter {
            it.key.startsWith("x-amz-", ignoreCase = true) ||
                it.key.equals("host", ignoreCase = true) ||
                it.key.equals("content-type", ignoreCase = true)
        }
        .sortedBy { it.key }

    val canonicalHeaders = filteredHeaders
        .map { (k, v) -> "${k.lowercase()}:${v.trim()}" }
        .joinToString("\n") + '\n'

    val signedHeaders = filteredHeaders
        .map { it.key.lowercase() }
        .joinToString(";")

    val hashedPayload = hashSHA256Hex(body)

    return listOf(method, path, canonicalQueryString, canonicalHeaders, signedHeaders, hashedPayload).joinToString("\n")
}

/**
 * The SigV4 authorization data extracted from a request's `Authorization` and `x-amz-date`
 * headers. Carries everything needed to recompute the request signature, but not the secret
 * itself (that is resolved separately from the identified user's credential).
 */
data class SigV4AuthData(
    /** The SigV4 access-key-id, which is the caller's TeamCity username. */
    val accessKeyId: String,
    val dateStamp: String,
    val region: String,
    val service: String,
    /** The credential-scope terminator, normally `aws4_request`. */
    val terminator: String,
    /** The `x-amz-date` timestamp used in the string-to-sign. */
    val amzDate: String,
    /** The signature the client presented, to be compared against the recomputed one. */
    val providedSignature: String
)

/**
 * Parses the SigV4 `Authorization` header (together with `x-amz-date`) into [SigV4AuthData].
 *
 * Returns `null` for every shape of malformed or missing input — absent headers, an unexpected
 * algorithm, or a credential that is not the expected `accessKey/date/region/service/terminator`
 * form. Callers treat a `null` result as an unresolvable identity (fail closed); no distinction is
 * made between the malformed cases so the API never reveals why authentication failed.
 */
fun parseSigV4Authorization(headers: Map<String, String>): SigV4AuthData? {
    val authorization = headers["authorization"] ?: return null
    val amzDate = headers["x-amz-date"] ?: return null

    val parts = authorization.split(" ")
    if (parts.size < 2 || parts[0] != "AWS4-HMAC-SHA256") {
        return null
    }

    val authParams = parts.drop(1).joinToString("") { it }
        .split(",")
        .mapNotNull {
            val keyValue = it.trim().split("=", limit = 2)
            if (keyValue.size != 2) null else keyValue[0] to keyValue[1]
        }
        .toMap()

    val credential = authParams["Credential"] ?: return null
    val providedSignature = authParams["Signature"] ?: return null

    val credParts = credential.split("/")
    if (credParts.size != 5) {
        return null
    }

    return SigV4AuthData(
        accessKeyId = credParts[0],
        dateStamp = credParts[1],
        region = credParts[2],
        service = credParts[3],
        terminator = credParts[4],
        amzDate = amzDate,
        providedSignature = providedSignature
    )
}

/**
 * Recomputes the hex SigV4 request signature for the given secret and parsed auth data, using the
 * same canonical-request / string-to-sign construction AWS SDKs use. Pure: given identical inputs
 * it always yields the same signature, and it depends on the secret only through the signing key.
 */
fun computeRequestSignature(
    secret: String,
    auth: SigV4AuthData,
    method: String,
    path: String,
    queryString: String,
    headers: Map<String, String>,
    body: String
): String {
    val canonicalRequest = computeCanonicalRequest(method, path, queryString, headers, body)
    val hashedCanonicalRequest = hashSHA256Hex(canonicalRequest)
    val scope = "${auth.dateStamp}/${auth.region}/${auth.service}/${auth.terminator}"
    val stringToSign = listOf("AWS4-HMAC-SHA256", auth.amzDate, scope, hashedCanonicalRequest).joinToString("\n")
    return computeExpectedSignature(secret, auth.dateStamp, auth.region, auth.service, stringToSign)
}
