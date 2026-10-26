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

object ModelCatalog {
    val chat: ModelSpec = ModelSpec(
        id = "chat",
        role = ModelRole.CHAT,
        fileName = "chat.litertlm",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/1caa527c1e88b55f046fcd8d2b4265606680663d/gemma-4-E2B-it.litertlm",
        bytes = 2_588_147_712L,
        sha256 = null,
        repo = "litert-community/gemma-4-E2B-it-litert-lm",
        revision = "1caa527c1e88b55f046fcd8d2b4265606680663d",
        remoteFile = "gemma-4-E2B-it.litertlm",
        gated = false,
    )
    val embed: ModelSpec = ModelSpec(
        id = "embed",
        role = ModelRole.EMBEDDING,
        fileName = "embed.litertlm",
        url = "https://huggingface.co/litert-community/embeddinggemma-2-740m-litert-lm/resolve/24d962e906c7d332c6428e71c9676855024569e2/embeddinggemma-2-740m.litertlm",
        bytes = 484_622_336L,
        sha256 = null,
        repo = "litert-community/embeddinggemma-2-740m-litert-lm",
        revision = "24d962e906c7d332c6428e71c9676855024569e2",
        remoteFile = "embeddinggemma-2-740m.litertlm",
        gated = false,
    )
    val specs: List<ModelSpec> = listOf(chat, embed)

    fun downloadAllowed(spec: ModelSpec): Boolean {
        return spec.repo.isNotBlank() && spec.revision.isNotBlank() && spec.remoteFile.isNotBlank()
    }
}
