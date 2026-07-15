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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class S3ErrorTest {

    @Test
    fun `signature mismatch maps to SignatureDoesNotMatch with HTTP 403`() {
        val error = toS3Error(SignatureMismatchException("bad signature"))

        assertEquals("SignatureDoesNotMatch", error.code)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `invalid auth data maps to InvalidAccessKeyId with HTTP 403`() {
        val error = toS3Error(InvalidAuthDataException("unknown user"))

        assertEquals("InvalidAccessKeyId", error.code)
        assertEquals(403, error.httpStatus)
    }

    @Test
    fun `missing or forbidden bucket maps to NoSuchBucket with HTTP 404`() {
        val error = toS3Error(NoSuchBucketException("hidden or absent"))

        assertEquals("NoSuchBucket", error.code)
        assertEquals(404, error.httpStatus)
    }

    @Test
    fun `missing object maps to NoSuchKey with HTTP 404`() {
        val error = toS3Error(NoSuchKeyException("no such artifact"))

        assertEquals("NoSuchKey", error.code)
        assertEquals(404, error.httpStatus)
    }

    @Test
    fun `toXml renders an S3 Error document with code and message`() {
        val xml = S3Error("InvalidAccessKeyId", "no such user", 403).toXml()

        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("<Error>"))
        assertTrue(xml.contains("<Code>InvalidAccessKeyId</Code>"))
        assertTrue(xml.contains("<Message>no such user</Message>"))
        assertTrue(xml.trimEnd().endsWith("</Error>"))
    }

    @Test
    fun `invalid auth data maps to a fixed message that does not disclose which check failed`() {
        val unknownUser = toS3Error(InvalidAuthDataException("no user for access key id"))
        val noSecret = toS3Error(InvalidAuthDataException("user has no provisioned secret"))

        // Same client-facing message regardless of the (distinct) internal cause, so the API
        // cannot be used to probe which usernames exist.
        assertEquals(unknownUser.message, noSecret.message)
    }

    @Test
    fun `toXml escapes markup in the message so it cannot break the document`() {
        val xml = S3Error("SignatureDoesNotMatch", "a < b & c > d", 403).toXml()

        assertTrue(xml.contains("<Message>a &lt; b &amp; c &gt; d</Message>"))
    }
}
