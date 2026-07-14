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

package jetbrains.buildServer.api.s3.artifacts.dto

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JacksonXmlRootElement(localName = "ListAllMyBucketsResult")
data class ListAllMyBucketsResult(
    @JacksonXmlProperty(localName = "Buckets") val buckets: Buckets,
    @JacksonXmlProperty(localName = "Owner") val owner: Owner,
    @JacksonXmlProperty(localName = "ContinuationToken") val continuationToken: String? = null,
    @JacksonXmlProperty(localName = "Prefix") val prefix: String? = null
)

data class Buckets(
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Bucket")
    val bucket: List<Bucket>
)

data class Bucket(
    @JacksonXmlProperty(localName = "Name") val name: String,
    @JacksonXmlProperty(localName = "CreationDate") val creationDate: String,
    @JacksonXmlProperty(localName = "BucketRegion") val bucketRegion: String? = null
)

data class Owner(
    @JacksonXmlProperty(localName = "ID") val id: String,
    @JacksonXmlProperty(localName = "DisplayName") val displayName: String
)
