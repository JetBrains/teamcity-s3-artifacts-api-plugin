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

fun validateRequestSignature(method: String, path: String, queryString: String, headers: Map<String, String>, body: String) {
    val authorization = headers["authorization"] ?: throw SecurityException("Missing Authorization header")
    val xAmzDate = headers["x-amz-date"] ?: throw SecurityException("Missing x-amz-date header")

    val parts = authorization.split(" ")
    if (parts.size < 2 || parts[0] != "AWS4-HMAC-SHA256") {
        throw SecurityException("Invalid Authorization header format")
    }

    val authParams = parts.drop(1).joinToString("") { it }
        .split(",")
        .associate {
            val keyValue = it.trim().split("=")
            val key = keyValue[0]
            val value = keyValue[1]
            key to value
        }

    val credential = authParams["Credential"] ?: throw SecurityException("Missing Credential in Authorization header")
    val providedSignature = authParams["Signature"] ?: throw SecurityException("Missing Signature in Authorization header")

    val credParts = credential.split("/")
    if (credParts.size != 5) {
        throw SecurityException("Invalid Credential format")
    }
    val accessKey = credParts[0]
    val dateStamp = credParts[1]
    val regionName = credParts[2]
    val serviceName = credParts[3]
    val lastPart = credParts[4]

    val secret = lookupSecretForAccessKey(accessKey)
    val canonicalRequest = computeCanonicalRequest(method, path, queryString, headers, body)
    val hashedCanonicalRequest = hashSHA256Hex(canonicalRequest)
    val scope = "$dateStamp/$regionName/$serviceName/$lastPart"
    val stringToSign = listOf("AWS4-HMAC-SHA256", xAmzDate, scope, hashedCanonicalRequest).joinToString("\n")
    if (!validateSignature(secret, dateStamp, regionName, serviceName, stringToSign, providedSignature)) {
        throw SecurityException("Signature mismatch")
    }
}

fun lookupSecretForAccessKey(accessKey: String): String {
    return "your-secret-key"
}
