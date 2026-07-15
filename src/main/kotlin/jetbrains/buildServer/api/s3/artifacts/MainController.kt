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

package jetbrains.buildServer.api.s3.artifacts

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.api.s3.artifacts.dto.ListAllMyBucketsResult
import jetbrains.buildServer.api.s3.artifacts.dto.ListObjectsResult
import jetbrains.buildServer.api.s3.artifacts.services.ArtifactMetadata
import jetbrains.buildServer.api.s3.artifacts.services.ArtifactObject
import jetbrains.buildServer.api.s3.artifacts.services.GetObjectService
import jetbrains.buildServer.api.s3.artifacts.security.NoSuchKeyException
import jetbrains.buildServer.api.s3.artifacts.security.S3AccessManager
import jetbrains.buildServer.api.s3.artifacts.security.S3ApiException
import jetbrains.buildServer.api.s3.artifacts.security.toS3Error
import jetbrains.buildServer.api.s3.artifacts.security.toXml
import jetbrains.buildServer.api.s3.artifacts.services.ListBucketsService
import jetbrains.buildServer.api.s3.artifacts.services.ListObjectsService
import jetbrains.buildServer.controllers.interceptors.SkipInternalInterceptors
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.serverSide.auth.SecurityContext
import jetbrains.buildServer.users.SUser
import org.springframework.core.io.InputStreamResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

@RestController
@SkipInternalInterceptors
@RequestMapping("/s3api")
class MainController(
    private val securityContext: SecurityContext,
    private val accessManager: S3AccessManager,
    private val listBucketsService: ListBucketsService,
    private val listObjectsService: ListObjectsService,
    private val getObjectService: GetObjectService
) {
    companion object {
        private val LOG: Logger = Logger.getInstance(MainController::class.java)
    }

    /**
     * Turns any [S3ApiException] raised while handling an S3 request into an S3-style `<Error>`
     * document with the mapped HTTP status — 403 for authentication failures, 404 for a
     * missing/forbidden bucket or object — replacing the previous generic 500.
     */
    @ExceptionHandler(S3ApiException::class)
    fun handleS3Failure(ex: S3ApiException): ResponseEntity<String> {
        val error = toS3Error(ex)
        return ResponseEntity.status(error.httpStatus)
            .contentType(MediaType.APPLICATION_XML)
            .body(error.toXml())
    }

    /**
     * Authenticates the current S3 request and returns the identified user, or throws a typed
     * [S3ApiException] (mapped to an S3 `<Error>` by [handleS3Failure]).
     */
    private fun authenticate(request: HttpServletRequest, headers: Map<String, String>): SUser {
        val queryString = request.queryString ?: ""
        return accessManager.authenticate(request.method!!, request.requestURI!!, queryString, headers, "")
    }

    /**
     * Runs [action] under [user]'s own security context instead of the system's, so artifact access
     * is evaluated as that user. Replaces the former `runAsSystem` execution on the read paths.
     */
    private fun <T> runAs(user: SUser, action: () -> T): T =
        (securityContext as SecurityContextEx).runAs<T>(user) { action() }

    @RequestMapping(path = ["/**"], method = [RequestMethod.HEAD])
    fun headRouter(request: HttpServletRequest, response: HttpServletResponse, @RequestHeader headers: Map<String, String>) {
        val user = authenticate(request, headers)
        LOG.debug("S3 HEAD request authenticated as ${user.username}")
        val objectKey = pathToObjectKey(request.requestURI)
        accessManager.authorize(user, objectKey)
        val objectMetadata = handleGetObjectHeadRequest(user, objectKey)
        response.addHeader("Content-Length", objectMetadata.size.toString())
        response.addHeader("Last-Modified", objectMetadata.lastModified)
        response.status = 200
    }

    private fun handleGetObjectHeadRequest(user: SUser, objectKey: String): ArtifactMetadata =
        serveObject(user, objectKey) { getObjectService.getObjectMetadata(objectKey) }

    /**
     * Fetches a single object under [user]'s security context. The artifact services signal a
     * missing (or malformed-key) artifact with [IllegalArgumentException]; ONLY that is translated
     * into a 404 [NoSuchKeyException], so a genuine miss is indistinguishable from a hidden object
     * while any other failure still surfaces as a 500 rather than a misleading 404.
     */
    private fun <T> serveObject(user: SUser, objectKey: String, fetch: () -> T): T =
        try {
            runAs(user) { fetch() }
        } catch (e: IllegalArgumentException) {
            LOG.debug("Object '$objectKey' not found or key malformed: ${e.message}")
            throw NoSuchKeyException("No object for key '$objectKey'")
        }

    @RequestMapping(path = ["/**"], method = [RequestMethod.GET], produces = ["application/xml"])
    fun router(
        request: HttpServletRequest,
        @RequestParam(value = "bucket-region", required = false) bucketRegion: String?,
        @RequestParam(value = "list-type", required = false) listType: String?,
        @RequestParam(value = "continuation-token", required = false) continuationToken: String?,
        @RequestParam(value = "max-buckets", required = false) maxBuckets: Int?,
        @RequestParam(value = "max-keys", required = false) maxKeys: Int?,
        @RequestParam(value = "prefix", required = false) prefix: String?,
        @RequestParam(value = "delimiter", required = false) delimiter: String?,
        @RequestParam(value = "encoding-type", required = false) encodingType: String?,
        @RequestParam(value = "fetch-owner", required = false) fetchOwner: Boolean?,
        @RequestParam(value = "start-after", required = false) startAfter: String?,
        @RequestHeader headers: Map<String, String>
    ): Any {
        val user = authenticate(request, headers)
        LOG.debug("S3 GET request authenticated as ${user.username}")
        val objectKey = pathToObjectKey(request.requestURI)
        val result = if (listType == "2") {
            accessManager.authorize(user, objectKey)
            handleListObjects(user, objectKey, bucketRegion, continuationToken, maxKeys, prefix, delimiter, encodingType, fetchOwner, startAfter)
        } else if (objectKey.isNotBlank() && listType.isNullOrBlank()) {
            accessManager.authorize(user, objectKey)
            val artifactObject = handleGetObjectRequest(user, objectKey)
            val resource = InputStreamResource(artifactObject.content)
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"${artifactObject.name}\"")
                .contentLength(artifactObject.size)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource)
        } else {
            LOG.info("ListBuckets: bucketRegion=$bucketRegion, continuationToken=$continuationToken, maxBuckets=$maxBuckets, prefix=$prefix")
            handleListBuckets(user, bucketRegion, continuationToken, maxBuckets, prefix)
        }
        return result!!
    }

    private fun handleGetObjectRequest(user: SUser, objectKey: String): ArtifactObject =
        serveObject(user, objectKey) { getObjectService.getObject(objectKey) }

    private fun handleListBuckets(user: SUser, bucketRegion: String?, continuationToken: String?, maxBuckets: Int?, prefix: String?): ListAllMyBucketsResult =
        runAs(user) {
            listBucketsService.getBuckets(prefix, maxBuckets) { candidates ->
                accessManager.filterVisibleBuckets(user, candidates)
            }
        }

    private fun handleListObjects(
        user: SUser,
        path: String,
        bucketRegion: String?,
        continuationToken: String?,
        maxKeys: Int?,
        prefix: String?,
        delimiter: String?,
        encodingType: String?,
        fetchOwner: Boolean?,
        startAfter: String?
    ): ListObjectsResult {
        LOG.info("ListObjects: path=$path, bucketRegion=$bucketRegion, continuationToken=$continuationToken, maxKeys=$maxKeys, prefix=$prefix, delimiter=$delimiter, encodingType=$encodingType, fetchOwner=$fetchOwner, startAfter=$startAfter")
        return runAs(user) {
            if (delimiter.isNullOrBlank()) {
                listObjectsService.getObjects(path, maxKeys, prefix, startAfter, encodingType)
            } else {
                listObjectsService.getObjectsWithDelimeter(path, maxKeys, prefix, delimiter, startAfter, encodingType)
            }
        }
    }
}
