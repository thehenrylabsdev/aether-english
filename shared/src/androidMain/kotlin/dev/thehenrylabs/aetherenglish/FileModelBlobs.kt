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

import java.io.File

class FileModelBlobs(private val directory: File) : ModelBlobs {
    init {
        directory.mkdirs()
    }

    fun readyFile(modelId: String): File = File(directory, "$modelId.litertlm")

    override fun length(modelId: String): Long {
        val partial = partialFile(modelId)
        return if (partial.exists()) partial.length() else 0L
    }

    override fun append(modelId: String, bytes: ByteArray) {
        partialFile(modelId).appendBytes(bytes)
    }

    override fun read(modelId: String): ByteArray {
        val partial = partialFile(modelId)
        return if (partial.exists()) partial.readBytes() else ByteArray(0)
    }

    override fun reset(modelId: String) {
        partialFile(modelId).delete()
    }

    override fun promote(modelId: String, sha256: String) {
        val ready = readyFile(modelId)
        partialFile(modelId).copyTo(ready, overwrite = true)
        partialFile(modelId).delete()
        File(directory, "$modelId.sha256").writeText(sha256)
    }

    override fun readyPath(modelId: String): String = readyFile(modelId).absolutePath

    override fun consumePartial(modelId: String, consumer: (ByteArray) -> Unit) {
        val partial = partialFile(modelId)
        if (!partial.exists()) return
        partial.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) consumer(buffer.copyOf(count))
            }
        }
    }

    private fun partialFile(modelId: String): File = File(directory, "$modelId.partial")
}
