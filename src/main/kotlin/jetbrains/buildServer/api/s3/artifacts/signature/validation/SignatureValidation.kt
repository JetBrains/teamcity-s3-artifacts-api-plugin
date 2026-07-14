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

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

fun hmacSHA256(data: String, key: ByteArray): ByteArray {
    val algorithm = "HmacSHA256"
    val mac = Mac.getInstance(algorithm)
    mac.init(SecretKeySpec(key, algorithm))
    return mac.doFinal(data.toByteArray(Charsets.UTF_8))
}

fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

fun getSignatureKey(secret: String, dateStamp: String, regionName: String, serviceName: String): ByteArray {
    val kSecret = "AWS4$secret".toByteArray(Charsets.UTF_8)
    val kDate = hmacSHA256(dateStamp, kSecret)
    val kRegion = hmacSHA256(regionName, kDate)
    val kService = hmacSHA256(serviceName, kRegion)
    return hmacSHA256("aws4_request", kService)
}

fun computeExpectedSignature(secret: String, dateStamp: String, regionName: String, serviceName: String, stringToSign: String): String {
    val signingKey = getSignatureKey(secret, dateStamp, regionName, serviceName)
    val signature = hmacSHA256(stringToSign, signingKey)
    return toHex(signature)
}

fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) {
        return false
    }
    var result = 0
    for (i in a.indices) {
        result = result or (a[i].code xor b[i].code)
    }
    return result == 0
}

fun validateSignature(secret: String, dateStamp: String, regionName: String, serviceName: String, stringToSign: String, providedSignature: String): Boolean {
    val expectedSignature = computeExpectedSignature(secret, dateStamp, regionName, serviceName, stringToSign)
    return constantTimeEquals(expectedSignature, providedSignature)
}
