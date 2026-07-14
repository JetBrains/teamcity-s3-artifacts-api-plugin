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
import jetbrains.buildServer.api.s3.artifacts.dto.Bucket
import jetbrains.buildServer.api.s3.artifacts.dto.Buckets
import jetbrains.buildServer.api.s3.artifacts.dto.ListAllMyBucketsResult
import jetbrains.buildServer.api.s3.artifacts.dto.Owner
import jetbrains.buildServer.serverSide.ProjectManager
import org.springframework.stereotype.Service

@Service
class ListBucketsService(
    private val projectManager: ProjectManager
) {
    companion object {
        private val LOG: Logger = Logger.getInstance(ListBucketsService::class.java)

        private const val DEFAULT_REGION = "default"
        private const val DEFAULT_CREATION_DATE = "2025-01-01T00:00:00.000Z"
        private const val DEFAULT_OWNER_ID = "0"
        private const val DEFAULT_OWNER_NAME = "superuser"
    }

    fun getBuckets(prefix: String?, maxBuckets: Int?): ListAllMyBucketsResult {
        val buildTypes = projectManager.projects.asSequence()
            .filterNot { it.isVirtual }
            .flatMap { it.buildTypes.asSequence() }
            .filterNot { it.isVirtual }
            .distinctBy { it.internalId }
            .filter { it.name.startsWith(prefix ?: "") }
            .take(maxBuckets ?: Int.MAX_VALUE)
            .toList()
            .sortedBy { it.externalId }

        val buckets = Buckets(buildTypes.map { Bucket(it.externalId, DEFAULT_CREATION_DATE, DEFAULT_REGION) })
        return ListAllMyBucketsResult(buckets, Owner(DEFAULT_OWNER_ID, DEFAULT_OWNER_NAME), prefix = prefix)
    }
}
