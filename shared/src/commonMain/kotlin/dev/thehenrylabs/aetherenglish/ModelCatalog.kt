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
        url = "",
        bytes = null,
        sha256 = null,
    )
    val embed: ModelSpec = ModelSpec(
        id = "embed",
        role = ModelRole.EMBEDDING,
        fileName = "embed.litertlm",
        url = "",
        bytes = null,
        sha256 = null,
    )
    val specs: List<ModelSpec> = listOf(chat, embed)

    fun downloadAllowed(spec: ModelSpec): Boolean = spec.sha256 != null && spec.url.isNotBlank()
}
