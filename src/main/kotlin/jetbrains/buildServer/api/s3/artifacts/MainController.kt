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
import jetbrains.buildServer.api.s3.artifacts.security.S3AccessManager
import jetbrains.buildServer.api.s3.artifacts.security.S3AuthenticationException
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
     * Turns any authentication failure raised while handling an S3 request into an S3-style
     * `<Error>` document with the mapped HTTP status (403), replacing the previous generic 500.
     */
    @ExceptionHandler(S3AuthenticationException::class)
    fun handleAuthenticationFailure(ex: S3AuthenticationException): ResponseEntity<String> {
        val error = toS3Error(ex)
        return ResponseEntity.status(error.httpStatus)
            .contentType(MediaType.APPLICATION_XML)
            .body(error.toXml())
    }

    /**
     * Authenticates the current S3 request and returns the identified user, or throws a typed
     * [S3AuthenticationException] (mapped to an S3 `<Error>` by [handleAuthenticationFailure]).
     */
    private fun authenticate(request: HttpServletRequest, headers: Map<String, String>): SUser {
        val queryString = request.queryString ?: ""
        return accessManager.authenticate(request.method!!, request.requestURI!!, queryString, headers, "")
    }

    @RequestMapping(path = ["/**"], method = [RequestMethod.HEAD])
    fun headRouter(request: HttpServletRequest, response: HttpServletResponse, @RequestHeader headers: Map<String, String>) {
        val user = authenticate(request, headers)
        LOG.debug("S3 HEAD request authenticated as ${user.username}")
        val objectKey = pathToObjectKey(request.requestURI)
        val objectMetadata = handleGetObjectHeadRequest(objectKey)
        response.addHeader("Content-Length", objectMetadata.size.toString())
        response.addHeader("Last-Modified", objectMetadata.lastModified)
        response.status = 200
    }

    private fun handleGetObjectHeadRequest(objectKey: String): ArtifactMetadata {
        return (securityContext as SecurityContextEx).runAsSystem<ArtifactMetadata> {
            getObjectService.getObjectMetadata(objectKey)
        }
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
            handleListObjects(objectKey, bucketRegion, continuationToken, maxKeys, prefix, delimiter, encodingType, fetchOwner, startAfter)
        } else if (objectKey.isNotBlank() && listType.isNullOrBlank()) {
            val artifactObject = handleGetObjectRequest(objectKey)
            val resource = InputStreamResource(artifactObject.content)
            ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"${artifactObject.name}\"")
                .contentLength(artifactObject.size)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource)
        } else {
            LOG.info("ListBuckets: bucketRegion=$bucketRegion, continuationToken=$continuationToken, maxBuckets=$maxBuckets, prefix=$prefix")
            handleListBuckets(bucketRegion, continuationToken, maxBuckets, prefix)
        }
        return result!!
    }

    private fun handleGetObjectRequest(objectKey: String): ArtifactObject {
        return (securityContext as SecurityContextEx).runAsSystem<ArtifactObject> {
            getObjectService.getObject(objectKey)
        }
    }

    private fun handleListBuckets(bucketRegion: String?, continuationToken: String?, maxBuckets: Int?, prefix: String?): ListAllMyBucketsResult {
        return (securityContext as SecurityContextEx).runAsSystem<ListAllMyBucketsResult> {
            listBucketsService.getBuckets(prefix, maxBuckets)
        }
    }

    private fun handleListObjects(
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
        return (securityContext as SecurityContextEx).runAsSystem<ListObjectsResult> {
            if (delimiter.isNullOrBlank()) {
                listObjectsService.getObjects(path, maxKeys, prefix, startAfter, encodingType)
            } else {
                listObjectsService.getObjectsWithDelimeter(path, maxKeys, prefix, delimiter, startAfter, encodingType)
            }
        }
    }
}
