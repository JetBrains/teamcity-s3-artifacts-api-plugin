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

import jetbrains.buildServer.serverSide.auth.AccessDeniedException
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Provisioning of per-user S3 secrets.
 *
 * Deliberately mapped outside `/s3api` and NOT annotated `@SkipInternalInterceptors`,
 * so TeamCity's own authentication interceptors run and the caller is a real,
 * session-authenticated user.
 *
 * By default each endpoint acts on the caller (self-service), and the S3 access key id
 * is the caller's username. A server administrator holding the global user-management
 * permission ([Permission.CHANGE_USER]) may instead target another user via the optional
 * `username` request parameter — used for offboarding and incident response. Any other
 * caller is refused when targeting a user other than themselves.
 */
@RestController
@RequestMapping("/s3credentials")
class S3CredentialController(
    private val securityContext: SecurityContext,
    private val userModel: UserModel,
    private val credentialStore: S3CredentialStore
) {
    data class CredentialResponse(val accessKeyId: String, val secretAccessKey: String)

    data class StatusResponse(val accessKeyId: String, val hasSecret: Boolean)

    /**
     * Generates (or rotates) an S3 secret and returns it once. Acts on the caller by
     * default, or on [username] when an admin manages another user. Admin rotation takes
     * effect immediately: the target's previous secret is replaced and stops validating.
     */
    @RequestMapping(method = [RequestMethod.POST], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun generate(@RequestParam(value = "username", required = false) username: String?): CredentialResponse {
        val user = resolveTarget(username)
        val secret = credentialStore.generate(user)
        return CredentialResponse(user.username, secret)
    }

    /**
     * Reports whether the target user currently has a secret, without revealing it. Acts
     * on the caller by default, or on [username] when an admin inspects another user.
     */
    @RequestMapping(method = [RequestMethod.GET], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(@RequestParam(value = "username", required = false) username: String?): StatusResponse {
        val user = resolveTarget(username)
        return StatusResponse(user.username, credentialStore.exists(user))
    }

    /**
     * Revokes an S3 secret, disabling that user's S3 access until it is regenerated. Acts
     * on the caller by default, or on [username] when an admin revokes another user's
     * secret. Admin revocation takes effect immediately: the target's stored secret is
     * removed and stops validating.
     */
    @RequestMapping(method = [RequestMethod.DELETE])
    fun revoke(@RequestParam(value = "username", required = false) username: String?): ResponseEntity<Void> {
        val user = resolveTarget(username)
        credentialStore.revoke(user)
        return ResponseEntity.noContent().build()
    }

    /**
     * Resolves the user an operation acts on.
     *
     * With no [username] (blank, or equal to the caller's own username) the operation is
     * self-service and needs no special permission. Naming a different user requires the
     * caller to hold the global user-management permission ([Permission.CHANGE_USER]);
     * otherwise the request is refused. A named user that does not exist is an error — the
     * request never silently falls back to the caller.
     */
    private fun resolveTarget(username: String?): SUser {
        val caller = currentUser()
        if (username.isNullOrBlank() || username == caller.username) {
            return caller
        }
        val holder = securityContext.authorityHolder
        if (!holder.isPermissionGrantedGlobally(Permission.CHANGE_USER)) {
            throw AccessDeniedException(
                holder,
                "Managing another user's S3 credentials requires the global user-management permission"
            )
        }
        return userModel.findUserAccount(null, username)
            ?: throw IllegalArgumentException("No TeamCity user with username '$username'")
    }

    private fun currentUser(): SUser {
        val holder = securityContext.authorityHolder
        val associated = holder.associatedUser
            ?: throw AccessDeniedException(holder, "Authentication is required to manage S3 credentials")
        if (userModel.isGuestUser(associated)) {
            throw AccessDeniedException(holder, "Guest users cannot provision S3 credentials")
        }
        return userModel.findUserById(associated.id)
            ?: throw AccessDeniedException(holder, "The current user could not be resolved")
    }
}
