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

object HuggingFaceSecret {
    const val NAME: String = "huggingface"
}

object HuggingFaceUrls {
    fun raw(spec: ModelSpec): String = fileUrl(spec, "raw")

    fun resolve(spec: ModelSpec): String = fileUrl(spec, "resolve")

    private fun fileUrl(spec: ModelSpec, kind: String): String {
        return "https://huggingface.co/${spec.repo}/$kind/${spec.revision}/${spec.remoteFile}"
    }
}

object LfsPointer {
    private val oid = Regex("""oid sha256:([0-9a-fA-F]{64})""")
    private val size = Regex("""(?m)^size (\d+)\s*$""")

    fun parse(text: String): Pair<String, Long>? {
        if (!text.contains("git-lfs.github.com/spec/v1")) return null
        val digest = oid.find(text)?.groupValues?.get(1)?.lowercase() ?: return null
        val bytes = size.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        if (bytes < 0L) return null
        return digest to bytes
    }
}

interface DownloadTransport {
    suspend fun transfer(
        url: String,
        fromByte: Long,
        authorization: String?,
        onStatus: (Int) -> Unit,
        sink: (ByteArray) -> Unit,
    )
}

class HuggingFaceDownloader(
    private val transport: DownloadTransport,
    private val blobs: ModelBlobs,
    private val token: suspend () -> String? = { null },
    private val onProgress: (Long, Long) -> Unit = { _, _ -> },
) {
    suspend fun download(spec: ModelSpec): ModelInstall {
        if (!ModelCatalog.downloadAllowed(spec)) return failed(spec, "download")
        val auth = authorization()
        val raw = HuggingFaceUrls.raw(spec)
        val resolve = HuggingFaceUrls.resolve(spec)
        if (!raw.startsWith("https://") || !resolve.startsWith("https://")) return failed(spec, "download")
        var pointerStatus = -1
        var pointer = ByteArray(0)
        var overflow = false
        transport.transfer(raw, 0, auth, { pointerStatus = it }) { chunk ->
            if (pointer.size + chunk.size > 4096) {
                overflow = true
            } else if (!overflow) {
                pointer += chunk
            }
        }
        if (pointerStatus == 401 || pointerStatus == 403) return tokenFailure(spec)
        if (pointerStatus != 200 || overflow) return failed(spec, "download")
        val parsed = LfsPointer.parse(pointer.decodeToString()) ?: return failed(spec, "download")
        return pull(spec, resolve, auth, parsed.first, parsed.second)
    }

    private suspend fun authorization(): String? {
        val value = token()?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (value.startsWith("Bearer ")) value else "Bearer $value"
    }

    private suspend fun pull(
        spec: ModelSpec,
        url: String,
        auth: String?,
        oid: String,
        total: Long,
    ): ModelInstall {
        var hasher = Sha256.Digester()
        blobs.consumePartial(spec.id) { hasher.update(it) }
        var guard = 0
        while (blobs.length(spec.id) < total) {
            if (guard++ >= 32) return checksum(spec)
            val offset = blobs.length(spec.id)
            var status = -1
            var received = 0L
            transport.transfer(url, offset, auth, { code ->
                status = code
                if (code == 200 && offset > 0L) {
                    blobs.reset(spec.id)
                    hasher = Sha256.Digester()
                }
            }) { chunk ->
                if (status != 200 && status != 206) return@transfer
                blobs.append(spec.id, chunk)
                hasher.update(chunk)
                received += chunk.size.toLong()
                onProgress(blobs.length(spec.id), total)
                if (blobs.length(spec.id) > total) return@transfer
            }
            if (status == 401 || status == 403) return tokenFailure(spec)
            if (status != 200 && status != 206) return failed(spec, "download")
            if (blobs.length(spec.id) > total || received == 0L) return checksum(spec)
        }
        val digest = hasher.hex()
        if (digest != oid) return checksum(spec)
        blobs.promote(spec.id, digest)
        onProgress(total, total)
        return ModelInstall(spec.id, InstallState.READY, total, blobs.readyPath(spec.id), digest, null)
    }

    private fun checksum(spec: ModelSpec): ModelInstall {
        blobs.reset(spec.id)
        return failed(spec, "checksum")
    }

    private fun tokenFailure(spec: ModelSpec): ModelInstall {
        return ModelInstall(spec.id, InstallState.FAILED, blobs.length(spec.id), null, null, "huggingface-token")
    }

    private fun failed(spec: ModelSpec, error: String): ModelInstall {
        return ModelInstall(spec.id, InstallState.FAILED, blobs.length(spec.id), null, null, error)
    }
}
