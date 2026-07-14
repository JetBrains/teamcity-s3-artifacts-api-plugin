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

import jetbrains.buildServer.serverSide.BuildQueryOptions
import jetbrains.buildServer.serverSide.BuildsManager
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildType
import jetbrains.buildServer.serverSide.artifacts.BuildArtifacts
import jetbrains.buildServer.serverSide.artifacts.BuildArtifactsViewMode
import jetbrains.buildServer.util.ItemProcessor
import org.springframework.stereotype.Service

@Service
class TeamCityAbstractionService(
    private val projectManager: ProjectManager,
    private val buildsManager: BuildsManager
) {
    fun getBuildTypeForBucketName(name: String): SBuildType =
        projectManager.projects.asSequence()
            .filterNot { it.isVirtual }
            .flatMap { it.buildTypes.asSequence() }
            .filterNot { it.isVirtual }
            .find { it.externalId == name }
            ?: throw IllegalArgumentException("Bucket with name $name not found")

    fun processBiuldsForType(buildType: SBuildType, processor: ItemProcessor<SBuild>) {
        val options = BuildQueryOptions().apply {
            setBuildTypeId(buildType.internalId)
            setMatchAllBranches(true)
            setIncludeRunning(true)
            setIncludeCanceled(true)
        }
        buildsManager.processBuilds(options, processor)
    }

    fun iterateArtifacts(build: SBuild, processor: BuildArtifacts.BuildArtifactsProcessor) {
        build.getArtifacts(BuildArtifactsViewMode.VIEW_ALL).iterateArtifacts(processor)
    }

    fun iterateArtifactsToPath(build: SBuild, path: List<String>, processor: BuildArtifacts.BuildArtifactsProcessor) {
        build.getArtifacts(BuildArtifactsViewMode.VIEW_ALL).iterateArtifacts(processor)
    }
}
