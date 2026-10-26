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

@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package dev.thehenrylabs.aetherenglish

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelPaths {
    @Volatile var chat: String? = null
    @Volatile var embed: String? = null
}

class LiteRtLlmProvider(
    private val paths: ModelPaths,
    private val cacheDir: File,
) : LlmProvider {
    override val kind: RuntimeKind = RuntimeKind.LOCAL
    private val gate = Mutex()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private val pendingTools = mutableListOf<ToolResult>()

    override suspend fun prepare(selection: RuntimeSelection) {
        gate.withLock {
            val path = paths.chat
            if (path.isNullOrBlank() || !File(path).isFile) return
            if (engine != null) return
            val backend = if (selection.backend == LocalBackend.GPU) Backend.GPU() else Backend.CPU()
            val opened = withContext(Dispatchers.IO) {
                val created = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = backend,
                        maxNumTokens = Limits.KV_CACHE_TOKENS,
                        cacheDir = cacheDir.absolutePath,
                    ),
                )
                created.initialize()
                created
            }
            engine = opened
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val active = gate.withLock { engine }
        if (active == null) {
            emit(LlmEvent.TurnFailed(LlmFailure.MODEL_MISSING))
            return@flow
        }
        val history = request.messages.dropLast(1).map { turn ->
            when (turn.role) {
                AuthorRole.TUTOR -> Message.model(turn.text)
                AuthorRole.USER -> Message.user(turn.text)
                AuthorRole.CONTEXT -> Message.user("CONTEXT: ${turn.text}")
            }
        }
        val toolNote = pendingTools.joinToString("\n") { result ->
            "${result.name}: ${result.content}"
        }
        pendingTools.clear()
        val latest = request.messages.lastOrNull()?.text.orEmpty()
        val prompt = if (toolNote.isBlank()) latest else "$latest\n$toolNote"
        val config = ConversationConfig(
            systemInstruction = Contents.of(request.systemPrompt),
            initialMessages = history,
            automaticToolCalling = false,
            maxOutputToken = request.maxOutputTokens,
            thinkingConfig = ThinkingConfig(request.thinkingBudget > 0, request.thinkingBudget),
            enableSpeculativeDecoding = request.enableSpeculativeDecoding,
        )
        val session = gate.withLock {
            conversation?.close()
            val created = active.createConversation(config)
            conversation = created
            created
        }
        try {
            session.sendMessageAsync(prompt).collect { message ->
                val thought = message.channels["thought"]
                if (!thought.isNullOrBlank()) emit(LlmEvent.ThoughtDelta(thought))
                val text = message.contents.contents.mapNotNull { content ->
                    (content as? Content.Text)?.text
                }.joinToString("")
                if (text.isNotEmpty()) emit(LlmEvent.TextDelta(text))
                message.toolCalls.forEach { call ->
                    emit(LlmEvent.ToolCallRequested(call.name, call.name, argumentsJson(call.arguments)))
                }
            }
            emit(LlmEvent.TurnCompleted)
        } catch (_: Exception) {
            emit(LlmEvent.TurnFailed(LlmFailure.PROVIDER_ERROR))
        }
    }

    override suspend fun sendToolResults(sessionId: String, results: List<ToolResult>) {
        gate.withLock { pendingTools += results }
    }

    override suspend fun cancel(sessionId: String) {
        gate.withLock { conversation?.cancelProcess() }
    }

    override suspend fun unload() {
        gate.withLock {
            conversation?.close()
            conversation = null
            engine?.close()
            engine = null
        }
    }

    private fun argumentsJson(arguments: Map<String, Any?>): String = buildString {
        append('{')
        arguments.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            append('"')
            append(jsonEscape(key))
            append("\":\"")
            append(jsonEscape(value?.toString() ?: ""))
            append('"')
        }
        append('}')
    }
}

class LiteRtEmbeddingProvider(
    private val paths: ModelPaths,
    private val cacheDir: File,
) : EmbeddingProvider {
    private val gate = Mutex()
    private var engine: EmbeddingEngine? = null

    override val ready: Boolean
        get() {
            val path = paths.embed
            return !path.isNullOrBlank() && File(path).isFile
        }

    override suspend fun embed(text: String, task: EmbeddingTask): FloatArray = gate.withLock {
        val path = paths.embed
        if (path.isNullOrBlank() || !File(path).isFile) error("embedding model missing")
        val active = engine ?: withContext(Dispatchers.IO) {
            val created = EmbeddingEngine(
                EmbeddingEngineConfig(
                    modelPath = path,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath,
                ),
            )
            created.initialize()
            created
        }
        engine = active
        val vector = withContext(Dispatchers.IO) {
            active.computeEmbedding(listOf(InputData.Text(text))).embedding
        }
        if (vector.size != Limits.EMBEDDING_DIMENSIONS) {
            error("expected ${Limits.EMBEDDING_DIMENSIONS} dimensions")
        }
        vector
    }

    override suspend fun unload() {
        gate.withLock {
            engine?.close()
            engine = null
        }
    }
}
