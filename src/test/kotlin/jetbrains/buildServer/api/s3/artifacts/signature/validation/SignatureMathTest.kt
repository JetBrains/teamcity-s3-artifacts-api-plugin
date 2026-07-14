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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SignatureMathTest {

    // SHA-256 of the empty string (well-known S3 empty-payload hash) and of "abc" (FIPS 180-2).
    private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `hashSHA256Hex matches known SHA-256 vectors`() {
        assertEquals(emptyHash, hashSHA256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hashSHA256Hex("abc"))
    }

    @Test
    fun `hmacSHA256 matches RFC 4231 test case 1`() {
        // key = 20 bytes of 0x0b, data = "Hi There"
        val key = ByteArray(20) { 0x0b }
        val mac = toHex(hmacSHA256("Hi There", key))
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", mac)
    }

    @Test
    fun `computeCanonicalRequest produces the expected canonical document`() {
        val headers = mapOf(
            "host" to "tc.example.com",
            "x-amz-date" to "20250714T120000Z",
            "x-amz-content-sha256" to emptyHash,
            // Excluded from the canonical request; present to prove it is filtered out.
            "authorization" to "AWS4-HMAC-SHA256 Credential=alice/20250714/us-east-1/s3/aws4_request"
        )

        val canonical = computeCanonicalRequest(
            method = "GET",
            path = "/s3api/",
            queryString = "prefix=builds&list-type=2",
            headers = headers,
            body = ""
        )

        val expected =
            "GET\n" +
            "/s3api/\n" +
            "list-type=2&prefix=builds\n" +
            "host:tc.example.com\n" +
            "x-amz-content-sha256:$emptyHash\n" +
            "x-amz-date:20250714T120000Z\n" +
            "\n" +
            "host;x-amz-content-sha256;x-amz-date\n" +
            emptyHash
        assertEquals(expected, canonical)
    }

    @Test
    fun `computeExpectedSignature is deterministic and validateSignature round-trips`() {
        val secret = "wJalrXUtnFEMI-EXAMPLE"
        val stringToSign = "AWS4-HMAC-SHA256\n20250714T120000Z\n20250714/us-east-1/s3/aws4_request\n$emptyHash"

        val sig = computeExpectedSignature(secret, "20250714", "us-east-1", "s3", stringToSign)
        val again = computeExpectedSignature(secret, "20250714", "us-east-1", "s3", stringToSign)

        assertEquals(64, sig.length)
        assertEquals(sig, again)
        assertTrue(validateSignature(secret, "20250714", "us-east-1", "s3", stringToSign, sig))
        assertFalse(validateSignature("other-secret", "20250714", "us-east-1", "s3", stringToSign, sig))
    }

    @Test
    fun `computeRequestSignature depends on the secret`() {
        val auth = SigV4AuthData("alice", "20250714", "us-east-1", "s3", "aws4_request", "20250714T120000Z", "")
        val headers = mapOf("host" to "tc.example.com", "x-amz-date" to "20250714T120000Z")

        val a = computeRequestSignature("secret-a", auth, "GET", "/s3api/", "list-type=2", headers, "")
        val b = computeRequestSignature("secret-b", auth, "GET", "/s3api/", "list-type=2", headers, "")

        assertNotEquals(a, b)
        assertEquals(a, computeRequestSignature("secret-a", auth, "GET", "/s3api/", "list-type=2", headers, ""))
    }

    @Test
    fun `parseSigV4Authorization extracts credential scope and signature`() {
        val headers = mapOf(
            "x-amz-date" to "20250714T120000Z",
            "authorization" to "AWS4-HMAC-SHA256 " +
                "Credential=alice/20250714/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-date, Signature=abcdef123456"
        )

        val auth = parseSigV4Authorization(headers)!!

        assertEquals("alice", auth.accessKeyId)
        assertEquals("20250714", auth.dateStamp)
        assertEquals("us-east-1", auth.region)
        assertEquals("s3", auth.service)
        assertEquals("aws4_request", auth.terminator)
        assertEquals("20250714T120000Z", auth.amzDate)
        assertEquals("abcdef123456", auth.providedSignature)
    }

    @Test
    fun `parseSigV4Authorization returns null for missing or malformed input`() {
        assertNull(parseSigV4Authorization(emptyMap()))
        assertNull(parseSigV4Authorization(mapOf("authorization" to "AWS4-HMAC-SHA256 Credential=a/b/c/d/e, Signature=x")))
        assertNull(
            parseSigV4Authorization(
                mapOf(
                    "x-amz-date" to "20250714T120000Z",
                    "authorization" to "Basic dXNlcjpwYXNz"
                )
            )
        )
        assertNull(
            parseSigV4Authorization(
                mapOf(
                    "x-amz-date" to "20250714T120000Z",
                    "authorization" to "AWS4-HMAC-SHA256 Credential=alice/20250714/us-east-1, Signature=x"
                )
            )
        )
    }
}
