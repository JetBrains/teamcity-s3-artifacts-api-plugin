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
import io.mockk.verify
import jetbrains.buildServer.serverSide.auth.AccessDeniedException
import jetbrains.buildServer.serverSide.auth.AuthorityHolder
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import jetbrains.buildServer.users.UserSet
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Covers the admin-gated seam of the provisioning endpoint: an admin holding the
 * global user-management permission ([Permission.CHANGE_USER]) may act on another
 * user, while everyone else stays limited to their own secret.
 */
class S3CredentialControllerTest {

    private val securityContext = mockk<SecurityContext>()
    private val holder = mockk<AuthorityHolder>()
    private val userModel = mockk<UserModel>(relaxed = true)
    private val credentialStore = mockk<S3CredentialStore>(relaxed = true)

    private val controller = S3CredentialController(securityContext, userModel, credentialStore)

    private fun mockUser(id: Long, name: String): SUser {
        val user = mockk<SUser>(relaxed = true)
        every { user.id } returns id
        every { user.username } returns name
        return user
    }

    /** Wires the security context so [caller] is the authenticated, non-guest caller. */
    private fun authenticateAs(caller: SUser, admin: Boolean) {
        every { securityContext.authorityHolder } returns holder
        every { holder.associatedUser } returns caller
        every { holder.isPermissionGrantedGlobally(Permission.CHANGE_USER) } returns admin
        every { userModel.isGuestUser(any()) } returns false
        every { userModel.findUserById(any()) } returns caller
    }

    /** Makes [users] resolvable by username through UserModel.getAllUsers(). */
    private fun registerUsers(vararg users: SUser) {
        val set = mockk<UserSet<SUser>>()
        every { set.users } returns users.toSet()
        every { userModel.allUsers } returns set
    }

    @Test
    fun `admin can rotate another user's secret, and the access key id is the target`() {
        val admin = mockUser(1, "admin")
        val target = mockUser(2, "bob")
        authenticateAs(admin, admin = true)
        registerUsers(admin, target)
        every { credentialStore.generate(target) } returns "TARGET-SECRET"

        val response = controller.generate("bob")

        assertEquals("bob", response.accessKeyId)
        assertEquals("TARGET-SECRET", response.secretAccessKey)
        verify(exactly = 1) { credentialStore.generate(target) }
        verify(exactly = 0) { credentialStore.generate(admin) }
    }

    @Test
    fun `admin can revoke another user's secret`() {
        val admin = mockUser(1, "admin")
        val target = mockUser(2, "bob")
        authenticateAs(admin, admin = true)
        registerUsers(admin, target)

        val response = controller.revoke("bob")

        assertEquals(204, response.statusCode.value())
        verify(exactly = 1) { credentialStore.revoke(target) }
        verify(exactly = 0) { credentialStore.revoke(admin) }
    }

    @Test
    fun `admin status reports on the target user`() {
        val admin = mockUser(1, "admin")
        val target = mockUser(2, "bob")
        authenticateAs(admin, admin = true)
        registerUsers(admin, target)
        every { credentialStore.exists(target) } returns true

        val response = controller.status("bob")

        assertEquals("bob", response.accessKeyId)
        assertTrue(response.hasSecret)
        verify(exactly = 1) { credentialStore.exists(target) }
    }

    @Test
    fun `non-admin cannot manage another user and the store is never touched`() {
        val alice = mockUser(1, "alice")
        authenticateAs(alice, admin = false)

        assertThrows<AccessDeniedException> { controller.generate("bob") }
        assertThrows<AccessDeniedException> { controller.revoke("bob") }
        assertThrows<AccessDeniedException> { controller.status("bob") }

        verify(exactly = 0) { credentialStore.generate(any()) }
        verify(exactly = 0) { credentialStore.revoke(any()) }
        verify(exactly = 0) { credentialStore.exists(any()) }
    }

    @Test
    fun `self-service is unaffected when no target is given and needs no admin permission`() {
        val alice = mockUser(1, "alice")
        authenticateAs(alice, admin = false)
        every { credentialStore.generate(alice) } returns "SELF-SECRET"
        every { credentialStore.exists(alice) } returns true

        val generated = controller.generate(null)
        assertEquals("alice", generated.accessKeyId)
        assertEquals("SELF-SECRET", generated.secretAccessKey)

        val status = controller.status(null)
        assertEquals("alice", status.accessKeyId)
        assertTrue(status.hasSecret)

        val revoked = controller.revoke(null)
        assertEquals(204, revoked.statusCode.value())

        verify(exactly = 1) { credentialStore.generate(alice) }
        verify(exactly = 1) { credentialStore.revoke(alice) }
        verify(exactly = 0) { holder.isPermissionGrantedGlobally(any()) }
    }

    @Test
    fun `naming yourself as the target is self-service and needs no admin permission`() {
        val alice = mockUser(1, "alice")
        authenticateAs(alice, admin = false)
        every { credentialStore.generate(alice) } returns "SELF-SECRET"

        val generated = controller.generate("alice")

        assertEquals("alice", generated.accessKeyId)
        verify(exactly = 1) { credentialStore.generate(alice) }
        verify(exactly = 0) { holder.isPermissionGrantedGlobally(any()) }
    }
}
