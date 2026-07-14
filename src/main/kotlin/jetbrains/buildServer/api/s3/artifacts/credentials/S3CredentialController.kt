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
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RestController

/**
 * Self-service management of the caller's own S3 secret.
 *
 * Deliberately mapped outside `/s3api` and NOT annotated `@SkipInternalInterceptors`,
 * so TeamCity's own authentication interceptors run and the caller is a real,
 * session-authenticated user. The S3 access key id is always the caller's username.
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

    /** Generates (or rotates) the caller's S3 secret and returns it once. */
    @RequestMapping(method = [RequestMethod.POST], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun generate(): CredentialResponse {
        val user = currentUser()
        val secret = credentialStore.generate(user)
        return CredentialResponse(user.username, secret)
    }

    /** Reports whether the caller currently has a secret, without revealing it. */
    @RequestMapping(method = [RequestMethod.GET], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun status(): StatusResponse {
        val user = currentUser()
        return StatusResponse(user.username, credentialStore.exists(user))
    }

    /** Revokes the caller's S3 secret, disabling their S3 access until regenerated. */
    @RequestMapping(method = [RequestMethod.DELETE])
    fun revoke(): ResponseEntity<Void> {
        val user = currentUser()
        credentialStore.revoke(user)
        return ResponseEntity.noContent().build()
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
