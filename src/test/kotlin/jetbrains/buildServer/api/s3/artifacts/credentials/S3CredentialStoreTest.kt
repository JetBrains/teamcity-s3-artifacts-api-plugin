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

package jetbrains.buildServer.api.s3.artifacts.credentials

import io.mockk.every
import io.mockk.mockk
import jetbrains.buildServer.users.PropertyKey
import jetbrains.buildServer.users.SUser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class S3CredentialStoreTest {

    private val credentialStore = S3CredentialStore()

    /**
     * A mocked user whose property get/set/delete are backed by an in-memory map,
     * so we exercise the real encryption round-trip through a realistic property store.
     */
    private fun userWithBackingStore(): SUser {
        val user = mockk<SUser>(relaxed = true)
        val backing = mutableMapOf<String, String>()
        every { user.setUserProperty(any(), any()) } answers {
            backing[firstArg<PropertyKey>().key] = secondArg()
        }
        every { user.getPropertyValue(any()) } answers {
            backing[firstArg<PropertyKey>().key]
        }
        every { user.deleteUserProperty(any()) } answers {
            backing.remove(firstArg<PropertyKey>().key)
            Unit
        }
        return user
    }

    @Test
    fun `generated secret round-trips through storage`() {
        val user = userWithBackingStore()

        val secret = credentialStore.generate(user)

        assertTrue(secret.isNotBlank())
        assertEquals(secret, credentialStore.load(user))
    }

    @Test
    fun `secret is stored encrypted at rest, never as plaintext`() {
        val user = userWithBackingStore()

        val secret = credentialStore.generate(user)
        val stored = user.getPropertyValue(S3CredentialStore.SECRET_PROPERTY_KEY)

        assertNotNull(stored)
        assertNotEquals(secret, stored)
    }

    @Test
    fun `rotation replaces the previous secret`() {
        val user = userWithBackingStore()

        val first = credentialStore.generate(user)
        val second = credentialStore.generate(user)

        assertNotEquals(first, second)
        assertEquals(second, credentialStore.load(user))
    }

    @Test
    fun `revoke removes the secret so load fails closed`() {
        val user = userWithBackingStore()
        credentialStore.generate(user)
        assertTrue(credentialStore.exists(user))

        credentialStore.revoke(user)

        assertFalse(credentialStore.exists(user))
        assertNull(credentialStore.load(user))
    }

    @Test
    fun `load returns null and exists is false when nothing is provisioned`() {
        val user = userWithBackingStore()

        assertNull(credentialStore.load(user))
        assertFalse(credentialStore.exists(user))
    }
}
