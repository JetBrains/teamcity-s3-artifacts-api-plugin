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

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement

@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "ListBucketResult")
data class ListObjectsResult(
    @JacksonXmlProperty(localName = "Name") val name: String,
    @JacksonXmlProperty(localName = "Prefix") val prefix: String,
    @JacksonXmlProperty(localName = "KeyCount") val keyCount: Int,
    @JacksonXmlProperty(localName = "MaxKeys") val maxKeys: Int? = null,
    @JacksonXmlProperty(localName = "Delimiter") val delimiter: String? = null,
    @JacksonXmlProperty(localName = "IsTruncated") val isTruncated: Boolean,
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "Contents")
    val contents: List<ObjectData>? = null,
    @JacksonXmlElementWrapper(useWrapping = false)
    @JacksonXmlProperty(localName = "CommonPrefixes")
    val commonPrefixes: List<CommonPrefix>? = null,
    @JacksonXmlProperty(localName = "ContinuationToken") val continuationToken: String? = null,
    @JacksonXmlProperty(localName = "NextContinuationToken") val nextContinuationToken: String? = null,
    @JacksonXmlProperty(localName = "StartAfter") val startAfter: String? = null,
    @JacksonXmlProperty(localName = "EncodingType") val encodingType: String? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ObjectData(
    @JacksonXmlProperty(localName = "Key") val key: String,
    @JacksonXmlProperty(localName = "LastModified") val lastModified: String,
    @JacksonXmlProperty(localName = "Size") val size: Long,
    @JacksonXmlProperty(localName = "ETag") val eTag: String? = null,
    @JacksonXmlProperty(localName = "StorageClass") val storageClass: String? = "STANDARD"
)

data class CommonPrefix(
    @JacksonXmlProperty(localName = "Prefix") val prefix: String
)
