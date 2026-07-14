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

package jetbrains.buildServer.api.s3.artifacts.services

import jetbrains.buildServer.serverSide.artifacts.BuildArtifact
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts.BuildArtifactsProcessor.Continuation
import jetbrains.buildServer.serverSide.setupFromUrl.SetupFromUrlExtension
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeFormatter

data class ArtifactMetadata(
    val key: String,
    val size: Long,
    val lastModified: String
)

data class ArtifactObject(
    val key: String,
    val name: String,
    val size: Long,
    val lastModified: String,
    val content: InputStream
)

@Service
class GetObjectService(
    private val teamCityAbstractionService: TeamCityAbstractionService,
    private val setupFromUrlExtension: SetupFromUrlExtension
) {
    fun getObjectMetadata(objectKey: String): ArtifactMetadata {
        val buildArtifact = findBuildArtifact(objectKey)
            ?: throw IllegalArgumentException("Object with key $objectKey not found")
        return ArtifactMetadata(
            objectKey,
            buildArtifact.size,
            Instant.ofEpochMilli(buildArtifact.timestamp).let { DateTimeFormatter.ISO_INSTANT.format(it) }
        )
    }

    fun getObject(objectKey: String): ArtifactObject {
        val buildArtifact = findBuildArtifact(objectKey)
            ?: throw IllegalArgumentException("Object with key $objectKey not found")
        return ArtifactObject(
            objectKey,
            buildArtifact.name,
            buildArtifact.size,
            Instant.ofEpochMilli(buildArtifact.timestamp).let { DateTimeFormatter.ISO_INSTANT.format(it) },
            buildArtifact.inputStream
        )
    }

    private fun findBuildArtifact(objectKey: String): BuildArtifact? {
        val objectKeyParts = objectKey.split("/")
        if (objectKeyParts.size < 3) {
            throw IllegalArgumentException("Object with key $objectKey not found")
        }
        val bucketName = objectKeyParts.first()
        val fileName = objectKeyParts.last()
        val prefixPath = objectKeyParts.drop(1).dropLast(1)
        val filePathToMatch = objectKeyParts.drop(2).dropLast(1)
        var index = 0
        var buildArtifact: BuildArtifact? = null
        val buildType = teamCityAbstractionService.getBuildTypeForBucketName(bucketName)
        teamCityAbstractionService.processBiuldsForType(buildType) { build ->
            val buildNumber = sanitizeBuildNumber(build.buildNumber)
            if (buildNumber == prefixPath[index]) {
                index++
                teamCityAbstractionService.iterateArtifacts(build) { artifact ->
                    if (index < prefixPath.size) {
                        if (artifact.isDirectory && artifact.name == prefixPath[index]) {
                            index++
                            return@iterateArtifacts Continuation.CONTINUE
                        }
                        if (artifact.isDirectory && artifact.name.isNotBlank()) {
                            return@iterateArtifacts Continuation.SKIP_CHILDREN
                        }
                        return@iterateArtifacts Continuation.CONTINUE
                    }
                    if (artifact.name.isNotBlank()) {
                        if (artifact.relativePath.split("/").dropLast(1) != filePathToMatch) {
                            return@iterateArtifacts Continuation.BREAK
                        }
                    }
                    if (artifact.isFile && artifact.name == fileName) {
                        buildArtifact = artifact
                        return@iterateArtifacts Continuation.BREAK
                    }
                    if (artifact.isDirectory && artifact.name.isNotBlank()) {
                        return@iterateArtifacts Continuation.SKIP_CHILDREN
                    }
                    Continuation.CONTINUE
                }
                return@processBiuldsForType false
            }
            true
        }
        return buildArtifact
    }
}
