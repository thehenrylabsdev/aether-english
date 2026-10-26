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
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.readUTF8Line
import kotlinx.io.readByteArray
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

expect fun platformStoreLabel(): String

fun jsonEscape(value: String): String = buildString {
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(char)
        }
    }
}

class CloudLlmProvider(
    private val client: HttpClient,
    private val secrets: SecretStore,
    private val vendor: CloudVendor,
    private val modelId: String,
    private val logs: (String) -> Unit,
) : LlmProvider {
    override val kind: RuntimeKind = RuntimeKind.CLOUD

    override suspend fun prepare(selection: RuntimeSelection) = Unit

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val secretName = if (vendor == CloudVendor.GEMINI) "cloud.gemini" else "cloud.openai"
        val key = secrets.get(secretName)
        if (key.isNullOrBlank()) {
            emit(LlmEvent.TurnFailed(LlmFailure.PROVIDER_ERROR))
            return@flow
        }
        val promptChars = request.systemPrompt.length + request.messages.sumOf { it.text.length }
        logs(CloudCallLogger.line(vendor.name, promptChars))
        val url = if (vendor == CloudVendor.GEMINI) {
            "https://generativelanguage.googleapis.com/v1beta/models/${jsonEscape(modelId)}:streamGenerateContent?alt=sse"
        } else {
            "https://api.openai.com/v1/chat/completions"
        }
        val body = if (vendor == CloudVendor.GEMINI) geminiBody(request) else openAiBody(request)
        val statement = client.preparePost(url) {
            if (vendor == CloudVendor.GEMINI) {
                header("x-goog-api-key", key)
            } else {
                header(HttpHeaders.Authorization, "Bearer $key")
            }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        statement.execute { response ->
            val channel = response.bodyAsChannel()
            val payload = StringBuilder()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                payload.append(line).append('\n')
            }
            val events = if (vendor == CloudVendor.GEMINI) {
                SseParsers.gemini(payload.toString())
            } else {
                SseParsers.openAi(payload.toString())
            }
            events.forEach { emit(it) }
        }
    }

    override suspend fun sendToolResults(sessionId: String, results: List<ToolResult>) = Unit

    override suspend fun cancel(sessionId: String) = Unit

    override suspend fun unload() = Unit

    private fun openAiBody(request: LlmRequest): String {
        val messages = buildString {
            append("{\"role\":\"system\",\"content\":\"")
            append(jsonEscape(request.systemPrompt))
            append("\"}")
            request.messages.forEach { turn ->
                val role = if (turn.role == AuthorRole.TUTOR) "assistant" else "user"
                append(",{\"role\":\"")
                append(role)
                append("\",\"content\":\"")
                append(jsonEscape(turn.text))
                append("\"}")
            }
        }
        return "{\"model\":\"${jsonEscape(modelId)}\",\"stream\":true,\"messages\":[$messages]}"
    }

    private fun geminiBody(request: LlmRequest): String {
        val text = buildString {
            append(request.systemPrompt)
            request.messages.forEach { turn ->
                append('\n')
                append(turn.role.name)
                append(": ")
                append(turn.text)
            }
        }
        return "{\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":\"${jsonEscape(text)}\"}]}]}"
    }
}

class KtorRangeClient(private val client: HttpClient) : RangeClient {
    override suspend fun get(url: String, fromByte: Long): RangeResponse {
        val response = client.prepareGet(url) {
            if (fromByte > 0) header(HttpHeaders.Range, "bytes=$fromByte-")
        }.execute { httpResponse ->
            RangeResponse(httpResponse.status.value, httpResponse.bodyAsChannel().readRemainingBytes())
        }
        return response
    }
}

private suspend fun io.ktor.utils.io.ByteReadChannel.readRemainingBytes(): ByteArray {
    return readRemaining().readByteArray()
}
