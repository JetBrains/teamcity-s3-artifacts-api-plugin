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

import jetbrains.buildServer.api.s3.artifacts.credentials.S3CredentialStore
import jetbrains.buildServer.api.s3.artifacts.services.TeamCityAbstractionService
import jetbrains.buildServer.api.s3.artifacts.signature.validation.computeRequestSignature
import jetbrains.buildServer.api.s3.artifacts.signature.validation.constantTimeEquals
import jetbrains.buildServer.api.s3.artifacts.signature.validation.parseSigV4Authorization
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.auth.Permission
import jetbrains.buildServer.users.SUser
import jetbrains.buildServer.users.UserModel
import org.springframework.stereotype.Service

/**
 * Single entry point for the request-time security of the S3 API; the controller delegates all
 * per-request security decisions here.
 *
 * This ticket implements [authenticate], which establishes the caller's identity: it parses the
 * SigV4 authorization data, resolves the access-key-id to a TeamCity user, loads and decrypts that
 * user's provisioned secret, then recomputes the SigV4 signature and compares it in constant time.
 * Every failure path is fail-closed and raises a typed [S3AuthenticationException]; there is no
 * default or fallback secret anywhere.
 *
 * It also authorizes the identified user against a requested bucket ([authorize]) and filters the
 * bucket listing to what the user may see ([filterVisibleBuckets]). A read requires BOTH
 * [Permission.VIEW_PROJECT] and [Permission.VIEW_BUILD_RUNTIME_DATA] on the bucket's project — the
 * former gates normal artifacts, the latter the hidden/internal ones the API also serves. A bucket
 * the user may not view is reported as absent ([NoSuchBucketException]), indistinguishable from one
 * that does not exist, so its existence cannot be probed.
 */
@Service
class S3AccessManager(
    private val userModel: UserModel,
    private val credentialStore: S3CredentialStore,
    private val teamCityAbstractionService: TeamCityAbstractionService
) {
    /**
     * Authenticates an S3 request and returns the [SUser] whose secret it was signed with.
     *
     * @throws SignatureMismatchException the identity resolved, but the presented signature does
     *         not match a signature recomputed from that user's secret.
     * @throws InvalidAuthDataException the identity could not be established: authorization data is
     *         missing or malformed, the access-key-id maps to no (non-guest) user, or that user has
     *         no provisioned secret.
     */
    fun authenticate(
        method: String,
        path: String,
        queryString: String,
        headers: Map<String, String>,
        body: String
    ): SUser {
        val auth = parseSigV4Authorization(headers)
            ?: throw InvalidAuthDataException("Missing or malformed authorization data")

        val user = userModel.findUserAccount(null, auth.accessKeyId)
            ?: throw InvalidAuthDataException("No user matches access key id '${auth.accessKeyId}'")
        if (userModel.isGuestUser(user)) {
            throw InvalidAuthDataException("The guest user cannot authenticate to the S3 API")
        }

        val secret = credentialStore.load(user)
            ?: throw InvalidAuthDataException("No secret is provisioned for the access key id")

        val expectedSignature = computeRequestSignature(secret, auth, method, path, queryString, headers, body)
        if (!constantTimeEquals(expectedSignature, auth.providedSignature)) {
            throw SignatureMismatchException("The request signature does not match the provisioned secret")
        }

        return user
    }

    /**
     * Authorizes [user] against the bucket named by [objectKey] (its first path segment) and returns
     * the resolved build configuration.
     *
     * Requires both [Permission.VIEW_PROJECT] and [Permission.VIEW_BUILD_RUNTIME_DATA] on the
     * bucket's project. A bucket that does not exist and a bucket the user may not view both raise
     * [NoSuchBucketException] — deliberately the same outcome, so a caller cannot tell a hidden
     * build configuration from an absent one.
     */
    fun authorize(user: SUser, objectKey: String): SBuildType {
        val bucketName = objectKey.substringBefore('/')
        val buildType = resolveBucket(bucketName)
        if (!canView(user, buildType)) {
            throw NoSuchBucketException("User '${user.username}' may not view bucket '$bucketName'")
        }
        return buildType
    }

    /**
     * Returns the subset of [candidates] the [user] may view, for `ListBuckets`. Uses the same
     * permission rule as [authorize], so a bucket appears in the listing exactly when the user could
     * go on to read it.
     */
    fun filterVisibleBuckets(user: SUser, candidates: List<SBuildType>): List<SBuildType> =
        candidates.filter { canView(user, it) }

    private fun resolveBucket(bucketName: String): SBuildType =
        try {
            teamCityAbstractionService.getBuildTypeForBucketName(bucketName)
        } catch (e: IllegalArgumentException) {
            throw NoSuchBucketException("No bucket named '$bucketName'")
        }

    private fun canView(user: SUser, buildType: SBuildType): Boolean {
        val projectId = buildType.project.projectId
        return user.isPermissionGrantedForProject(projectId, Permission.VIEW_PROJECT) &&
            user.isPermissionGrantedForProject(projectId, Permission.VIEW_BUILD_RUNTIME_DATA)
    }
}
