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

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.api.s3.artifacts.credentials.S3CredentialStore
import jetbrains.buildServer.api.s3.artifacts.signature.validation.SigV4AuthData
import jetbrains.buildServer.api.s3.artifacts.signature.validation.computeRequestSignature
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class S3AccessManagerTest {

    private val userModel = mockk<UserModel>()
    private val credentialStore = mockk<S3CredentialStore>()
    private val accessManager = S3AccessManager(userModel, credentialStore)

    private val method = "GET"
    private val path = "/s3api/"
    private val queryString = "list-type=2"
    private val body = ""
    private val dateStamp = "20250714"
    private val amzDate = "20250714T120000Z"
    private val region = "us-east-1"
    private val service = "s3"

    /**
     * Builds a real SigV4-signed request for [username] using [secret], returning the header map an
     * S3 client would send. The signature is computed with the same pure functions the manager uses
     * to verify it, so a request built with the user's true secret must authenticate.
     */
    private fun signedHeaders(username: String, secret: String): Map<String, String> {
        val baseHeaders = mapOf(
            "host" to "buildserver.example.com",
            "x-amz-date" to amzDate,
            "x-amz-content-sha256" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        )
        val auth = SigV4AuthData(
            accessKeyId = username,
            dateStamp = dateStamp,
            region = region,
            service = service,
            terminator = "aws4_request",
            amzDate = amzDate,
            providedSignature = ""
        )
        val signature = computeRequestSignature(secret, auth, method, path, queryString, baseHeaders, body)
        val authorization = "AWS4-HMAC-SHA256 " +
            "Credential=$username/$dateStamp/$region/$service/aws4_request, " +
            "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
            "Signature=$signature"
        // The Authorization header is excluded from the canonical request, so adding it here does
        // not change the signature the manager recomputes.
        return baseHeaders + ("authorization" to authorization)
    }

    private fun authenticate(headers: Map<String, String>): SUser =
        accessManager.authenticate(method, path, queryString, headers, body)

    @Test
    fun `a request signed with the user's provisioned secret authenticates as that user`() {
        val secret = "Sbq1mQ0jf3wYd2p7Rr8kLxZa4Vt6Nc9Ug5He0Ip"
        val user = mockk<SUser>()
        every { userModel.findUserAccount(null, "alice") } returns user
        every { userModel.isGuestUser(user) } returns false
        every { credentialStore.load(user) } returns secret

        val authenticated = authenticate(signedHeaders("alice", secret))

        assertSame(user, authenticated)
    }

    @Test
    fun `a request signed with any other secret is rejected as SignatureDoesNotMatch`() {
        val user = mockk<SUser>()
        every { userModel.findUserAccount(null, "alice") } returns user
        every { userModel.isGuestUser(user) } returns false
        // The stored secret differs from the one the request was signed with.
        every { credentialStore.load(user) } returns "the-actually-provisioned-secret"

        val headers = signedHeaders("alice", "a-different-secret-the-attacker-used")

        assertThrows<SignatureMismatchException> { authenticate(headers) }
    }

    @Test
    fun `an access key id that maps to no user is rejected as InvalidAccessKeyId`() {
        every { userModel.findUserAccount(null, "ghost") } returns null

        val headers = signedHeaders("ghost", "irrelevant-secret")

        assertThrows<InvalidAuthDataException> { authenticate(headers) }
    }

    @Test
    fun `a guest user is rejected as InvalidAccessKeyId`() {
        val guest = mockk<SUser>()
        every { userModel.findUserAccount(null, "guest") } returns guest
        every { userModel.isGuestUser(guest) } returns true

        val headers = signedHeaders("guest", "irrelevant-secret")

        assertThrows<InvalidAuthDataException> { authenticate(headers) }
    }

    @Test
    fun `a user with no provisioned secret is rejected as InvalidAccessKeyId`() {
        val user = mockk<SUser>()
        every { userModel.findUserAccount(null, "alice") } returns user
        every { userModel.isGuestUser(user) } returns false
        every { credentialStore.load(user) } returns null

        val headers = signedHeaders("alice", "some-secret")

        assertThrows<InvalidAuthDataException> { authenticate(headers) }
    }

    @Test
    fun `a request with no Authorization header is rejected as InvalidAccessKeyId`() {
        val headers = mapOf("host" to "buildserver.example.com", "x-amz-date" to amzDate)

        assertThrows<InvalidAuthDataException> { authenticate(headers) }
    }

    @Test
    fun `a request with malformed Authorization data is rejected as InvalidAccessKeyId`() {
        val headers = mapOf(
            "host" to "buildserver.example.com",
            "x-amz-date" to amzDate,
            "authorization" to "AWS4-HMAC-SHA256 Credential=alice/too-few-parts, Signature=deadbeef"
        )

        assertThrows<InvalidAuthDataException> { authenticate(headers) }
    }
}
