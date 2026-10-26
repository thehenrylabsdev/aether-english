/*
 * Copyright 2026 Henry Nguyen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.thehenrylabs.aetherenglish

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.exhausted
import io.ktor.utils.io.readAvailable

class KtorDownloadTransport(private val client: HttpClient) : DownloadTransport {
    override suspend fun transfer(
        url: String,
        fromByte: Long,
        authorization: String?,
        onStatus: (Int) -> Unit,
        sink: (ByteArray) -> Unit,
    ) {
        try {
            transferHttps(url, fromByte, authorization, onStatus, sink)
        } catch (_: Exception) {
            onStatus(0)
        }
    }

    private suspend fun transferHttps(
        url: String,
        fromByte: Long,
        authorization: String?,
        onStatus: (Int) -> Unit,
        sink: (ByteArray) -> Unit,
    ) {
        var current = url
        var hop = 0
        while (hop < 5) {
            if (!current.startsWith("https://")) {
                onStatus(0)
                return
            }
            var redirect: String? = null
            var finished = false
            client.prepareGet(current) {
                if (fromByte > 0L) header(HttpHeaders.Range, "bytes=$fromByte-")
                if (!authorization.isNullOrBlank() && current.startsWith("https://huggingface.co/")) {
                    header(HttpHeaders.Authorization, authorization)
                }
            }.execute { response ->
                val status = response.status.value
                if (status in 300..399) {
                    redirect = response.headers[HttpHeaders.Location]
                    return@execute
                }
                onStatus(status)
                if (status == 200 || status == 206) {
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(256 * 1024)
                    while (!channel.exhausted()) {
                        val count = channel.readAvailable(buffer)
                        if (count <= 0) break
                        sink(buffer.copyOf(count))
                    }
                }
                finished = true
            }
            if (finished) return
            val location = redirect
            if (location.isNullOrBlank()) {
                onStatus(0)
                return
            }
            current = when {
                location.startsWith("https://") -> location
                location.startsWith("/") -> "https://huggingface.co$location"
                else -> {
                    onStatus(0)
                    return
                }
            }
            hop++
        }
        onStatus(0)
    }
}
