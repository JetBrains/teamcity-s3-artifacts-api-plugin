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

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.api.s3.artifacts.dto.CommonPrefix
import jetbrains.buildServer.api.s3.artifacts.dto.ListObjectsResult
import jetbrains.buildServer.api.s3.artifacts.dto.ObjectData
import jetbrains.buildServer.serverSide.BuildsManager
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.artifacts.BuildArtifact
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts.BuildArtifactsProcessor.Continuation
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.format.DateTimeFormatter

fun sanitizeBuildNumber(buildNumber: String): String = buildNumber.replace("/", "_")

@Service
class ListObjectsService(
    private val projectManager: ProjectManager,
    private val buildsManager: BuildsManager,
    private val teamCityAbstractionService: TeamCityAbstractionService
) {
    companion object {
        private val LOG: Logger = Logger.getInstance(ListObjectsService::class.java)

        private const val DEFAULT_DELIMITER = "/"
    }

    fun getObjects(name: String, maxKeys: Int?, prefix: String?, startAfter: String?, encodingType: String?): ListObjectsResult {
        val buildType = teamCityAbstractionService.getBuildTypeForBucketName(name)
        var objects = mutableListOf<ObjectData>()
        var isTruncated = false
        val prefixParts = prefix?.split(DEFAULT_DELIMITER) ?: emptyList()
        val filePathToMatch = if (prefixParts.size == 1) emptyList() else prefixParts.drop(1)
        var index = 0
        teamCityAbstractionService.processBiuldsForType(buildType) { build ->
            val buildNumber = sanitizeBuildNumber(build.buildNumber)
            if (prefixParts.size > 0) {
                if (buildNumber == prefixParts[index]) {
                    index++
                    val iterator = ArtifactIterator(buildNumber, objects, maxKeys ?: Int.MAX_VALUE, index, filePathToMatch)
                    teamCityAbstractionService.iterateArtifacts(build, iterator)
                    isTruncated = iterator.isTruncated
                    index += iterator.index
                    return@processBiuldsForType false
                }
            } else {
                val iterator = ArtifactIterator(buildNumber, objects, maxKeys ?: Int.MAX_VALUE)
                teamCityAbstractionService.iterateArtifacts(build, iterator)
                isTruncated = iterator.isTruncated
            }
            !isTruncated
        }
        objects = objects.distinctBy { it.key }.toMutableList()
        return ListObjectsResult(
            name = name,
            prefix = prefix ?: "",
            isTruncated = isTruncated,
            keyCount = objects.size,
            contents = objects.ifEmpty { null },
            maxKeys = maxKeys ?: Int.MAX_VALUE,
            startAfter = startAfter,
            encodingType = encodingType
        )
    }

    fun getObjectsWithDelimeter(name: String, maxKeys: Int?, prefix: String?, delimiter: String, startAfter: String?, encodingType: String?): ListObjectsResult {
        val buildType = teamCityAbstractionService.getBuildTypeForBucketName(name)
        var objects = mutableListOf<ObjectData>()
        var commonPrefixes: MutableList<CommonPrefix>? = null
        var isTruncated = false
        if (prefix.isNullOrBlank() || prefix == delimiter) {
            teamCityAbstractionService.processBiuldsForType(buildType) { build ->
                if (commonPrefixes == null) {
                    commonPrefixes = mutableListOf()
                }
                commonPrefixes?.add(CommonPrefix(build.buildNumber.replace("/", "_") + "/")) ?: true
            }
        } else {
            val prefixParts = prefix.split(delimiter)
            val filePathToMatch = if (prefixParts.size == 1) emptyList() else prefixParts.drop(1)
            var index = 0
            teamCityAbstractionService.processBiuldsForType(buildType) { build ->
                val buildNumber = sanitizeBuildNumber(build.buildNumber)
                if (buildNumber == prefixParts[index]) {
                    index++
                    teamCityAbstractionService.iterateArtifacts(build) { artifact ->
                        if (objects.size + (commonPrefixes?.size ?: 0) >= (maxKeys ?: Int.MAX_VALUE)) {
                            isTruncated = true
                            return@iterateArtifacts Continuation.BREAK
                        }
                        if (index < prefixParts.size) {
                            if (artifact.isDirectory && artifact.name == prefixParts[index]) {
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
                        if (artifact.isDirectory && artifact.name.isNotBlank()) {
                            if (commonPrefixes == null) {
                                commonPrefixes = mutableListOf()
                            }
                            commonPrefixes?.add(CommonPrefix("$buildNumber$delimiter${artifact.relativePath.replace("/", delimiter)}/"))
                            return@iterateArtifacts Continuation.SKIP_CHILDREN
                        }
                        if (artifact.isFile) {
                            objects.add(
                                ObjectData(
                                    key = "$buildNumber$delimiter${artifact.relativePath.replace("/", delimiter)}",
                                    lastModified = Instant.ofEpochMilli(artifact.timestamp).let { DateTimeFormatter.ISO_INSTANT.format(it) },
                                    size = artifact.size
                                )
                            )
                        }
                        Continuation.CONTINUE
                    }
                    return@processBiuldsForType false
                }
                true
            }
        }
        commonPrefixes = commonPrefixes?.distinctBy { it.prefix }?.toMutableList()
        objects = objects.distinctBy { it.key }.toMutableList()
        return ListObjectsResult(
            name = name,
            prefix = prefix ?: "",
            isTruncated = isTruncated,
            keyCount = objects.size + (commonPrefixes?.size ?: 0),
            contents = objects.ifEmpty { null },
            maxKeys = maxKeys ?: Int.MAX_VALUE,
            commonPrefixes = commonPrefixes,
            delimiter = delimiter,
            startAfter = startAfter,
            encodingType = encodingType
        )
    }
}

class ArtifactIterator(
    val buildNumber: String,
    val objects: MutableList<ObjectData>,
    val maxKeys: Int,
    var index: Int = 0,
    val prefixParts: List<String> = emptyList(),
    val filePathToMatch: List<String> = emptyList()
) : BuildArtifacts.BuildArtifactsProcessor {
    var isTruncated: Boolean = false

    override fun processBuildArtifact(artifact: BuildArtifact): Continuation {
        if (objects.size >= maxKeys) {
            isTruncated = true
            return Continuation.BREAK
        }
        if (index < prefixParts.size) {
            if (artifact.isDirectory && artifact.name == prefixParts[index]) {
                index++
                return Continuation.CONTINUE
            }
            if (artifact.isDirectory && artifact.name.isNotBlank()) {
                return Continuation.SKIP_CHILDREN
            }
            return Continuation.CONTINUE
        }
        if (artifact.name.isNotBlank() && filePathToMatch.isNotEmpty()) {
            if (artifact.relativePath.split("/").dropLast(1) != filePathToMatch) {
                return Continuation.BREAK
            }
        }
        if (artifact.isFile) {
            objects.add(
                ObjectData(
                    key = "$buildNumber/${artifact.relativePath}",
                    lastModified = Instant.ofEpochMilli(artifact.timestamp).let { DateTimeFormatter.ISO_INSTANT.format(it) },
                    size = artifact.size
                )
            )
        }
        return Continuation.CONTINUE
    }
}
