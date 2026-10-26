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

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object Limits {
    const val EMBEDDING_DIMENSIONS: Int = 768
    const val KV_CACHE_TOKENS: Int = 1024
    const val CHAT_IDLE_UNLOAD_MILLIS: Long = 60_000
    const val DAY_MILLIS: Long = 86_400_000
    const val HISTORY_TURNS: Int = 6
    const val MISTAKE_LINES: Int = 5
    const val FIX_LINES: Int = 3
    const val RETRIEVAL_LIMIT: Int = 4
    const val RETRIEVAL_KEEP: Int = 3
    const val RETRIEVAL_CHARS: Int = 400
    const val TOOL_ROUNDS: Int = 2
    const val UI_FRAMES_PER_SECOND: Int = 20
    const val RAM_NUMERATOR: Int = 3
    const val RAM_DENOMINATOR: Int = 2
    const val DAILY_MIN_MINUTES: Int = 5
    const val DAILY_MAX_MINUTES: Int = 15
    const val REVIEW_MINUTES: Int = 1
    const val SPEAK_MINUTES: Int = 5
    const val READ_MINUTES: Int = 4
    const val PLACEMENT_TURNS: Int = 6
}

object Markers {
    const val FIX_SEPARATOR: String = "---"
    const val FIX_PREFIX: String = "FIX:"
    const val ARROW: String = "=>"
    const val GENERATED_LICENSE: String = "generated-on-device"
    const val ORIGINAL_LICENSE: String = "original-project"
    const val DOCUMENT_PREFIX: String = "task: search result | text: "
    const val QUERY_PREFIX: String = "task: search query | text: "
    const val UPDATE_PROGRESS: String = "UpdateProgress"
    const val RETRIEVE_MEMORY: String = "RetrieveMemory"
    const val GRAMMAR_LOOKUP: String = "GrammarLookup"
    const val GENERATE_EXERCISE: String = "GenerateExercise"
    const val SAVE_TO_KNOWLEDGE_BASE: String = "SaveToKnowledgeBase"
    const val RECORD_PLACEMENT: String = "RecordPlacement"
    const val DO_NOT_COPY: String =
        "Write original teaching text. Do not copy textbooks, exam items, or web articles."
}

enum class CefrLevel { A1, A2, B1, B2, C1, C2 }

enum class LearningGoal { DAILY, WORK, IELTS, TRAVEL, ACADEMIC }

enum class RuntimeKind { LOCAL, CLOUD }

enum class LocalBackend { CPU, GPU }

enum class CloudVendor { OPENAI_COMPATIBLE, GEMINI }

enum class ThermalBand { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY }

enum class EmbeddingTask { SEARCH_DOCUMENT, SEARCH_QUERY, SENTENCE_SIMILARITY }

enum class LlmFailure {
    CANCELLED,
    THROTTLED,
    MODEL_MISSING,
    BACKEND_REJECTED,
    PROVIDER_ERROR,
    CONTEXT_OVERFLOW,
}

enum class SpeechAvailability { ON_DEVICE, UNAVAILABLE }

enum class ToolCaller { MODEL, APP }

enum class SrsKind { VOCAB, CHUNK, STRUCTURE }

enum class FsrsCardState { NEW, REVIEW, RELEARNING }

enum class FsrsRating(val quality: Int) { AGAIN(1), HARD(2), GOOD(3), EASY(4) }

enum class ConversationMode { FREE, ROLEPLAY, PLACEMENT, WRITING }

enum class AuthorRole { USER, TUTOR, CONTEXT }

enum class MistakeKind { GRAMMAR, VOCABULARY, PRONUNCIATION }

enum class VectorSource { NOTE, MISTAKE, SAVED_CONTENT, CONVERSATION_SUMMARY, GRAMMAR, READING }

enum class ModelRole { CHAT, EMBEDDING }

enum class InstallState { MISSING, PARTIAL, READY, FAILED }

enum class LibraryKind { READING, GRAMMAR, EXERCISE }

enum class DailyStepKind { REVIEW, SPEAK, READ }

enum class SpeakResult { SPOKEN, VOICE_MISSING }

enum class OfflineChip { OFFLINE, CLOUD, NEED_MODEL, DOWNLOADING }

fun interface AppClock {
    fun nowMillis(): Long
}

class FixedClock(var millis: Long) : AppClock {
    override fun nowMillis(): Long = millis
}

object Ids {
    fun newId(): String {
        val alphabet = "0123456789abcdef"
        val bytes = Random.Default.nextBytes(16)
        return buildString(36) {
            bytes.forEachIndexed { index, byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
                if (index == 3 || index == 5 || index == 7 || index == 9) append('-')
            }
        }
    }
}

data class LearnerProfile(
    val externalId: String,
    val displayName: String,
    val nativeLanguage: String,
    val goals: List<LearningGoal>,
    val dailyMinutes: Int,
    val selfReportedCefr: CefrLevel?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

data class CefrEstimate(
    val externalId: String,
    val assessedAtMillis: Long,
    val source: String,
    val overall: CefrLevel,
    val reading: CefrLevel,
    val writing: CefrLevel,
    val listening: CefrLevel,
    val speaking: CefrLevel,
    val note: String,
) {
    companion object {
        const val SELF_REPORT: String = "SELF_REPORT"
        const val PLACEMENT: String = "PLACEMENT_CONVERSATION"
    }
}

data class SrsItem(
    val externalId: String,
    val kind: SrsKind,
    val front: String,
    val back: String,
    val example: String,
    val cefr: CefrLevel,
    val stability: Double,
    val difficulty: Double,
    val scheduledDays: Int,
    val reps: Int,
    val lapses: Int,
    val cardState: FsrsCardState,
    val dueAtMillis: Long,
    val lastReviewedAtMillis: Long?,
    val sourceMistakeId: String?,
)

data class ConversationRecord(
    val externalId: String,
    val mode: ConversationMode,
    val scenarioId: String?,
    val startedAtMillis: Long,
    val endedAtMillis: Long?,
)

data class StoredMessage(
    val externalId: String,
    val conversationId: String,
    val role: AuthorRole,
    val text: String,
    val createdAtMillis: Long,
)

data class Mistake(
    val externalId: String,
    val conversationId: String,
    val messageId: String,
    val kind: MistakeKind,
    val original: String,
    val corrected: String,
    val explanation: String,
    val ruleId: String?,
    val createdAtMillis: Long,
)

data class VectorDocument(
    val externalId: String,
    val source: VectorSource,
    val sourceId: String,
    val text: String,
    val embedding: FloatArray,
    val embeddingModelId: String,
    val createdAtMillis: Long,
)

data class VectorFilter(val source: VectorSource?)

data class VectorHit(val document: VectorDocument, val score: Float)

data class ChatTurn(val role: AuthorRole, val text: String)

data class ThermalSnapshot(
    val band: ThermalBand,
    val capturedAtMillis: Long,
    val supported: Boolean,
)

data class GenerationBudget(
    val maxOutputTokens: Int,
    val thinkingBudget: Int,
    val speculativeDecoding: Boolean,
    val metricsInterval: Duration,
    val stop: Boolean,
    val allowSecondRetrieval: Boolean,
)

sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    data class ThoughtDelta(val text: String) : LlmEvent
    data class ToolCallRequested(
        val callId: String,
        val name: String,
        val argumentsJson: String,
    ) : LlmEvent
    data object TurnCompleted : LlmEvent
    data class TurnFailed(val reason: LlmFailure) : LlmEvent
}

data class ToolSpec(val name: String, val description: String, val parametersJson: String)

data class ToolResult(val callId: String, val name: String, val content: String)

data class LlmRequest(
    val sessionId: String,
    val systemPrompt: String,
    val messages: List<ChatTurn>,
    val tools: List<ToolSpec>,
    val maxOutputTokens: Int,
    val thinkingBudget: Int,
    val enableSpeculativeDecoding: Boolean,
)

data class RuntimeSelection(
    val kind: RuntimeKind,
    val backend: LocalBackend,
    val cloudVendor: CloudVendor?,
    val cloudModelId: String,
    val sendPersonalContext: Boolean,
)

data class AudioClip(val pcm: ShortArray, val sampleRate: Int)

data class Transcript(val text: String?, val availability: SpeechAvailability)

data class FixLine(
    val original: String,
    val corrected: String,
    val note: String,
    val ruleId: String?,
)

data class LibraryItem(
    val externalId: String,
    val kind: LibraryKind,
    val cefr: CefrLevel,
    val title: String,
    val body: String,
    val license: String,
    val attribution: String,
    val answer: String?,
)

data class ModelSpec(
    val id: String,
    val role: ModelRole,
    val fileName: String,
    val url: String,
    val bytes: Long?,
    val sha256: String?,
)

data class ModelInstall(
    val modelId: String,
    val state: InstallState,
    val bytesDownloaded: Long,
    val localPath: String?,
    val verifiedSha256: String?,
    val lastError: String?,
)

data class MetricsSample(
    val capturedAtMillis: Long,
    val conversationId: String?,
    val modelId: String,
    val backend: String,
    val thermalBand: ThermalBand,
    val availRamBytes: Long?,
    val ttftMillis: Long?,
    val outputChars: Int,
    val outputTokens: Int?,
    val wallMillis: Long,
    val throttleAction: String,
)

data class DailyStep(val kind: DailyStepKind, val title: String, val minutes: Int, val refId: String?)

data class DailyPath(val steps: List<DailyStep>, val minutes: Int)

sealed interface TutorEvent {
    data class TextDelta(val text: String) : TutorEvent
    data class CorrectionReady(val fixes: List<FixLine>) : TutorEvent
    data object Throttled : TutorEvent
    data object NeedModel : TutorEvent
    data object Completed : TutorEvent
    data class Failed(val reason: LlmFailure) : TutorEvent
    data class ToolRejected(val name: String) : TutorEvent
}

data class PronunciationNote(val code: String, val message: String)

data class PronunciationFeedback(
    val target: String,
    val transcript: String?,
    val availability: SpeechAvailability,
    val durationMillis: Long,
    val rms: Double,
    val clippingRatio: Double,
    val notes: List<PronunciationNote>,
)

interface LlmProvider {
    val kind: RuntimeKind
    suspend fun prepare(selection: RuntimeSelection)
    fun stream(request: LlmRequest): Flow<LlmEvent>
    suspend fun sendToolResults(sessionId: String, results: List<ToolResult>)
    suspend fun cancel(sessionId: String)
    suspend fun unload()
}

interface EmbeddingProvider {
    val ready: Boolean
    suspend fun embed(text: String, task: EmbeddingTask): FloatArray
    suspend fun unload()
}

interface VectorStore {
    suspend fun upsert(document: VectorDocument)
    suspend fun search(query: FloatArray, limit: Int, filter: VectorFilter): List<VectorHit>
    suspend fun deleteBySource(source: VectorSource, sourceId: String)
    suspend fun count(): Int
}

interface TtsSpeaker {
    suspend fun speak(text: String, languageTag: String): SpeakResult
    fun stop()
}

interface SpeechToText {
    suspend fun transcribe(languageTag: String, audio: AudioClip): Transcript
    fun availability(languageTag: String): SpeechAvailability
}

interface ThermalMonitor {
    fun current(): ThermalSnapshot
    fun changes(): Flow<ThermalSnapshot>
    fun close()
}

interface SecretStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun delete(key: String)
}

interface LearningStore {
    suspend fun getProfile(): LearnerProfile?
    suspend fun saveProfile(profile: LearnerProfile)
    suspend fun estimates(): List<CefrEstimate>
    suspend fun saveEstimate(estimate: CefrEstimate)
    suspend fun srsItems(): List<SrsItem>
    suspend fun saveSrs(item: SrsItem)
    suspend fun conversations(): List<ConversationRecord>
    suspend fun saveConversation(record: ConversationRecord)
    suspend fun messages(conversationId: String): List<StoredMessage>
    suspend fun saveMessage(message: StoredMessage)
    suspend fun mistakes(): List<Mistake>
    suspend fun saveMistake(mistake: Mistake)
    suspend fun library(): List<LibraryItem>
    suspend fun saveLibrary(item: LibraryItem)
    suspend fun metrics(): List<MetricsSample>
    suspend fun saveMetrics(sample: MetricsSample)
    suspend fun modelInstalls(): List<ModelInstall>
    suspend fun saveModelInstall(install: ModelInstall)
}

data class ToolContext(
    val conversationId: String,
    val nowMillis: Long,
    val band: ThermalBand,
    val alreadyRetrieved: Boolean,
)

interface TutorTool {
    val spec: ToolSpec
    val callableBy: Set<ToolCaller>
    suspend fun run(argumentsJson: String, context: ToolContext): ToolResult
}

object EmbeddingPrefixes {
    fun apply(task: EmbeddingTask, text: String): String {
        val body = text.trim()
        val prefix = if (task == EmbeddingTask.SEARCH_QUERY) {
            Markers.QUERY_PREFIX
        } else {
            Markers.DOCUMENT_PREFIX
        }
        return prefix + body
    }
}

object CosineIndex {
    fun requireDimension(vector: FloatArray) {
        require(vector.size == Limits.EMBEDDING_DIMENSIONS) {
            "expected ${Limits.EMBEDDING_DIMENSIONS} dimensions"
        }
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        requireDimension(left)
        requireDimension(right)
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in left.indices) {
            val a = left[index].toDouble()
            val b = right[index].toDouble()
            dot += a * b
            leftNorm += a * a
            rightNorm += b * b
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0f
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).toFloat()
    }

    fun rank(
        query: FloatArray,
        documents: List<VectorDocument>,
        limit: Int,
        filter: VectorFilter,
    ): List<VectorHit> {
        requireDimension(query)
        return documents
            .asSequence()
            .filter { filter.source == null || it.source == filter.source }
            .map { document ->
                requireDimension(document.embedding)
                VectorHit(document, cosine(query, document.embedding))
            }
            .sortedByDescending { it.score }
            .take(limit)
            .toList()
    }
}

object Fsrs5 {
    val WEIGHTS: DoubleArray = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
        1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    )
    const val DESIRED_RETENTION: Double = 0.9
    private const val DECAY: Double = -0.5
    private const val FACTOR: Double = 19.0 / 81.0

    fun initDifficulty(rating: Int, weights: DoubleArray = WEIGHTS): Double {
        val raw = weights[4] - exp(weights[5] * (rating - 1)) + 1.0
        return raw.coerceIn(1.0, 10.0)
    }

    fun nextDifficulty(difficulty: Double, rating: Int, weights: DoubleArray = WEIGHTS): Double {
        val step = difficulty - weights[6] * (rating - 3)
        val mixed = weights[7] * initDifficulty(4, weights) + (1.0 - weights[7]) * step
        return mixed.coerceIn(1.0, 10.0)
    }

    fun retrievability(elapsedDays: Double, stability: Double): Double {
        if (stability <= 0.0) return 0.0
        return (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)
    }

    fun intervalDays(stability: Double, retention: Double = DESIRED_RETENTION): Double {
        if (stability <= 0.0) return 0.0
        return (stability / FACTOR) * (retention.pow(1.0 / DECAY) - 1.0)
    }
}

class FsrsScheduler(
    private val weights: DoubleArray = Fsrs5.WEIGHTS,
    private val retention: Double = Fsrs5.DESIRED_RETENTION,
) {
    fun review(item: SrsItem, rating: FsrsRating, nowMillis: Long): SrsItem {
        val elapsedDays = if (item.lastReviewedAtMillis == null || item.cardState == FsrsCardState.NEW) {
            0.0
        } else {
            (nowMillis - item.lastReviewedAtMillis).toDouble() / Limits.DAY_MILLIS.toDouble()
        }
        val grade = rating.quality
        val next = if (item.cardState == FsrsCardState.NEW || item.reps == 0) {
            initial(grade)
        } else if (elapsedDays < 1.0) {
            shortTerm(item, grade)
        } else {
            longTerm(item, grade, elapsedDays)
        }
        val days = max(1, Fsrs5.intervalDays(next.stability, retention).roundToInt())
        return item.copy(
            stability = next.stability,
            difficulty = next.difficulty,
            scheduledDays = days,
            reps = item.reps + 1,
            lapses = next.lapses,
            cardState = next.cardState,
            dueAtMillis = nowMillis + days * Limits.DAY_MILLIS,
            lastReviewedAtMillis = nowMillis,
        )
    }

    private data class Next(val stability: Double, val difficulty: Double, val lapses: Int, val cardState: FsrsCardState)

    private fun initial(grade: Int): Next {
        val stability = weights[grade - 1]
        val difficulty = Fsrs5.initDifficulty(grade, weights)
        val lapses = if (grade == 1) 1 else 0
        val state = if (grade == 1) FsrsCardState.RELEARNING else FsrsCardState.REVIEW
        return Next(stability, difficulty, lapses, state)
    }

    private fun shortTerm(item: SrsItem, grade: Int): Next {
        val stability = item.stability * exp(weights[17] * (grade - 3 + weights[18]))
        val difficulty = Fsrs5.nextDifficulty(item.difficulty, grade, weights)
        val lapses = if (grade == 1) item.lapses + 1 else item.lapses
        val state = if (grade == 1) FsrsCardState.RELEARNING else FsrsCardState.REVIEW
        return Next(stability, difficulty, lapses, state)
    }

    private fun longTerm(item: SrsItem, grade: Int, elapsedDays: Double): Next {
        val recall = Fsrs5.retrievability(elapsedDays, item.stability)
        val stability = if (grade == 1) {
            weights[11] * item.difficulty.pow(-weights[12]) *
                ((item.stability + 1.0).pow(weights[13]) - 1.0) *
                exp((1.0 - recall) * weights[14])
        } else {
            val hardPenalty = if (grade == 2) weights[15] else 1.0
            val easyBonus = if (grade == 4) weights[16] else 1.0
            val gain = exp(weights[8]) * (11.0 - item.difficulty) * item.stability.pow(-weights[9]) *
                (exp((1.0 - recall) * weights[10]) - 1.0) * hardPenalty * easyBonus
            item.stability * (1.0 + gain)
        }
        val difficulty = Fsrs5.nextDifficulty(item.difficulty, grade, weights)
        val lapses = if (grade == 1) item.lapses + 1 else item.lapses
        val state = if (grade == 1) FsrsCardState.RELEARNING else FsrsCardState.REVIEW
        return Next(stability, difficulty, lapses, state)
    }
}

object ThermalPolicy {
    fun decide(band: ThermalBand, speculativeRequested: Boolean): GenerationBudget {
        return when (band) {
            ThermalBand.NONE, ThermalBand.LIGHT -> GenerationBudget(
                maxOutputTokens = 512,
                thinkingBudget = 0,
                speculativeDecoding = speculativeRequested,
                metricsInterval = 5.seconds,
                stop = false,
                allowSecondRetrieval = true,
            )
            ThermalBand.MODERATE -> GenerationBudget(
                maxOutputTokens = 256,
                thinkingBudget = 0,
                speculativeDecoding = false,
                metricsInterval = 15.seconds,
                stop = false,
                allowSecondRetrieval = true,
            )
            ThermalBand.SEVERE -> GenerationBudget(
                maxOutputTokens = 128,
                thinkingBudget = 0,
                speculativeDecoding = false,
                metricsInterval = 15.seconds,
                stop = false,
                allowSecondRetrieval = false,
            )
            ThermalBand.CRITICAL, ThermalBand.EMERGENCY -> GenerationBudget(
                maxOutputTokens = 0,
                thinkingBudget = 0,
                speculativeDecoding = false,
                metricsInterval = 15.seconds,
                stop = true,
                allowSecondRetrieval = false,
            )
        }
    }

    fun mapAndroidStatus(status: Int): ThermalBand = when (status) {
        0 -> ThermalBand.NONE
        1 -> ThermalBand.LIGHT
        2 -> ThermalBand.MODERATE
        3 -> ThermalBand.SEVERE
        4 -> ThermalBand.CRITICAL
        else -> ThermalBand.EMERGENCY
    }
}

object RamGate {
    fun allows(availMem: Long, fileBytes: Long): Boolean {
        return availMem >= fileBytes * Limits.RAM_NUMERATOR / Limits.RAM_DENOMINATOR
    }
}

data class RuntimeDecision(
    val kind: RuntimeKind?,
    val backend: LocalBackend?,
    val needModel: Boolean,
    val rejectedByRam: Boolean,
)

object RuntimePlanner {
    fun decide(
        preferCloud: Boolean,
        hasCloudKey: Boolean,
        availMem: Long,
        modelBytes: Long?,
        modelReady: Boolean,
        gpuFailed: Boolean,
    ): RuntimeDecision {
        if (preferCloud && hasCloudKey) {
            return RuntimeDecision(RuntimeKind.CLOUD, null, needModel = false, rejectedByRam = false)
        }
        if (modelBytes != null && !RamGate.allows(availMem, modelBytes)) {
            return RuntimeDecision(null, null, needModel = true, rejectedByRam = true)
        }
        if (!modelReady) {
            return RuntimeDecision(null, null, needModel = true, rejectedByRam = false)
        }
        val backend = if (gpuFailed) LocalBackend.CPU else LocalBackend.GPU
        return RuntimeDecision(RuntimeKind.LOCAL, backend, needModel = false, rejectedByRam = false)
    }
}

object FixParser {
    fun parse(text: String): List<FixLine> {
        val separatorAt = when {
            text.startsWith(Markers.FIX_SEPARATOR) -> 0
            else -> {
                val found = text.indexOf("\n${Markers.FIX_SEPARATOR}")
                if (found < 0) -1 else found + 1
            }
        }
        if (separatorAt < 0) return emptyList()
        val tail = text.substring(separatorAt).lineSequence().drop(1)
        val parsed = mutableListOf<FixLine>()
        for (raw in tail) {
            if (parsed.size == Limits.FIX_LINES) break
            val line = raw.trim()
            if (!line.startsWith(Markers.FIX_PREFIX)) continue
            val body = line.removePrefix(Markers.FIX_PREFIX).trim()
            val arrow = body.indexOf(Markers.ARROW)
            if (arrow < 0) continue
            val original = body.substring(0, arrow).trim()
            val rest = body.substring(arrow + Markers.ARROW.length).trim()
            val parts = rest.split("|")
            if (parts.size < 3) continue
            val corrected = parts[0].trim()
            val note = parts[1].trim()
            val rule = parts[2].trim()
            if (original.isEmpty() || corrected.isEmpty()) continue
            parsed += FixLine(
                original = original,
                corrected = corrected,
                note = note,
                ruleId = if (rule == "-" || rule.isEmpty()) null else rule,
            )
        }
        return parsed
    }
}

object PromptBuilder {
    fun system(
        profile: LearnerProfile,
        estimate: CefrEstimate?,
        mistakes: List<Mistake>,
        mode: ConversationMode,
    ): String {
        val level = estimate?.overall ?: profile.selfReportedCefr ?: CefrLevel.A2
        val recent = mistakes.takeLast(Limits.MISTAKE_LINES).joinToString("\n") { mistake ->
            "${mistake.kind}: ${mistake.original} => ${mistake.corrected}"
        }
        return buildString {
            append("You are Aether, an English tutor. Reply briefly at CEFR ")
            append(level.name)
            append(". Learner goals: ")
            append(profile.goals.joinToString(",") { it.name })
            append(". Daily minutes: ")
            append(profile.dailyMinutes)
            append(". Mode: ")
            append(mode.name)
            append(".\n")
            if (recent.isNotEmpty()) {
                append("Recent mistakes:\n")
                append(recent)
                append("\n")
            }
            append("If there is a language error, add up to ")
            append(Limits.FIX_LINES)
            append(" lines after a ")
            append(Markers.FIX_SEPARATOR)
            append(" line, each shaped as ")
            append(Markers.FIX_PREFIX)
            append(" original ")
            append(Markers.ARROW)
            append(" corrected | short note | ruleId or -")
        }
    }

    fun clip(history: List<ChatTurn>, context: List<ChatTurn>): List<ChatTurn> {
        val dialogue = history.filter { it.role == AuthorRole.USER || it.role == AuthorRole.TUTOR }
        val kept = if (dialogue.size <= Limits.HISTORY_TURNS) dialogue else dialogue.takeLast(Limits.HISTORY_TURNS)
        return context + kept
    }
}

object CloudCallLogger {
    fun line(vendor: String, promptChars: Int): String {
        return "cloud vendor=$vendor promptChars=$promptChars"
    }
}

object SseParsers {
    fun openAi(body: String): List<LlmEvent> = parseChunks(body) { json ->
        val content = stringField(json, "content")
        val toolName = stringField(json, "name")
        val arguments = stringField(json, "arguments")
        val callId = stringField(json, "id")
        when {
            content != null -> listOf(LlmEvent.TextDelta(content))
            toolName != null -> listOf(
                LlmEvent.ToolCallRequested(callId ?: "call", toolName, arguments ?: "{}"),
            )
            else -> emptyList()
        }
    }

    fun gemini(body: String): List<LlmEvent> = parseChunks(body) { json ->
        val text = stringField(json, "text")
        if (text != null) listOf(LlmEvent.TextDelta(text)) else emptyList()
    }

    private fun parseChunks(body: String, mapChunk: (String) -> List<LlmEvent>): List<LlmEvent> {
        val events = mutableListOf<LlmEvent>()
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (!line.startsWith("data:")) return@forEach
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") return@forEach
            events += mapChunk(payload)
        }
        events += LlmEvent.TurnCompleted
        return events
    }

    fun stringField(json: String, key: String): String? {
        val needle = "\"$key\""
        val keyAt = json.indexOf(needle)
        if (keyAt < 0) return null
        val colon = json.indexOf(':', keyAt + needle.length)
        if (colon < 0) return null
        var index = colon + 1
        while (index < json.length && json[index].isWhitespace()) index++
        if (index >= json.length || json[index] != '"') return null
        index++
        val out = StringBuilder()
        while (index < json.length) {
            val char = json[index]
            if (char == '\\' && index + 1 < json.length) {
                out.append(json[index + 1])
                index += 2
                continue
            }
            if (char == '"') return out.toString()
            out.append(char)
            index++
        }
        return null
    }
}

object Sha256 {
    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
    )

    fun hex(bytes: ByteArray): String {
        val digest = digest(bytes)
        val alphabet = "0123456789abcdef"
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    fun digest(message: ByteArray): ByteArray {
        val bitLength = message.size.toLong() * 8L
        val withOne = message.size + 1
        val pad = (56 - withOne % 64 + 64) % 64
        val padded = ByteArray(withOne + pad + 8)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (shift in 7 downTo 0) {
            padded[padded.size - 8 + (7 - shift)] = ((bitLength ushr (shift * 8)) and 0xff).toByte()
        }
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19
        val w = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                w[index] = ((padded[base].toInt() and 0xff) shl 24) or
                    ((padded[base + 1].toInt() and 0xff) shl 16) or
                    ((padded[base + 2].toInt() and 0xff) shl 8) or
                    (padded[base + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val s0 = w[index - 15].rotateRight(7) xor w[index - 15].rotateRight(18) xor (w[index - 15] ushr 3)
                val s1 = w[index - 2].rotateRight(17) xor w[index - 2].rotateRight(19) xor (w[index - 2] ushr 10)
                w[index] = w[index - 16] + s0 + w[index - 7] + s1
            }
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7
            for (index in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[index] + w[index]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
            offset += 64
        }
        return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).flatMap { word ->
            listOf(
                (word ushr 24).toByte(),
                (word ushr 16).toByte(),
                (word ushr 8).toByte(),
                word.toByte(),
            )
        }.toByteArray()
    }

    private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}

object DownloadResume {
    fun restart(statusCode: Int): Boolean = statusCode == 200
}

data class RangeResponse(val status: Int, val body: ByteArray)

interface RangeClient {
    suspend fun get(url: String, fromByte: Long): RangeResponse
}

interface ModelBlobs {
    fun length(modelId: String): Long
    fun append(modelId: String, bytes: ByteArray)
    fun read(modelId: String): ByteArray
    fun reset(modelId: String)
    fun promote(modelId: String, sha256: String)
}

class ModelDownloader(private val client: RangeClient, private val blobs: ModelBlobs) {
    suspend fun download(spec: ModelSpec): InstallState {
        val expected = spec.sha256 ?: return InstallState.FAILED
        var guard = 0
        while (guard < 8) {
            guard++
            val have = blobs.length(spec.id)
            val response = client.get(spec.url, have)
            if (DownloadResume.restart(response.status)) {
                blobs.reset(spec.id)
                blobs.append(spec.id, response.body)
            } else if (response.status == 206 || (response.status == 200 && have == 0L)) {
                blobs.append(spec.id, response.body)
            } else {
                return InstallState.FAILED
            }
            val digest = Sha256.hex(blobs.read(spec.id))
            if (digest == expected) {
                blobs.promote(spec.id, digest)
                return InstallState.READY
            }
            if (response.body.isEmpty()) return InstallState.FAILED
        }
        return InstallState.FAILED
    }
}

object SpeechPolicy {
    fun onDevice(sdkInt: Int, recognizerAvailable: Boolean): SpeechAvailability {
        return if (sdkInt >= 31 && recognizerAvailable) {
            SpeechAvailability.ON_DEVICE
        } else {
            SpeechAvailability.UNAVAILABLE
        }
    }
}

object PaceHeuristic {
    const val MILLIS_PER_WORD: Long = 400
    const val QUIET_RMS: Double = 0.01
    const val LOUD_RMS: Double = 0.45
    const val CLIP_RATIO: Double = 0.01
    const val SHORT_FACTOR: Double = 0.6
    const val LONG_FACTOR: Double = 1.8
}

object PronunciationAssessor {
    fun assess(target: String, transcript: String?, audio: AudioClip): PronunciationFeedback {
        val samples = audio.pcm
        val duration = if (audio.sampleRate <= 0 || samples.isEmpty()) {
            0L
        } else {
            samples.size.toLong() * 1000L / audio.sampleRate.toLong()
        }
        var energy = 0.0
        var clipped = 0
        samples.forEach { sample ->
            val unit = sample.toDouble() / 32768.0
            energy += unit * unit
            if (abs(sample.toInt()) >= 32760) clipped++
        }
        val rms = if (samples.isEmpty()) 0.0 else sqrt(energy / samples.size)
        val clippingRatio = if (samples.isEmpty()) 0.0 else clipped.toDouble() / samples.size.toDouble()
        val notes = mutableListOf<PronunciationNote>()
        if (samples.isEmpty() || rms < PaceHeuristic.QUIET_RMS) {
            notes += PronunciationNote("quiet", "The recording energy is very low.")
        } else if (rms > PaceHeuristic.LOUD_RMS) {
            notes += PronunciationNote("loud", "The recording energy is very high.")
        }
        if (clippingRatio >= PaceHeuristic.CLIP_RATIO) {
            notes += PronunciationNote("clipping", "Part of the recording is clipped at full scale.")
        }
        val words = target.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val expected = words.size * PaceHeuristic.MILLIS_PER_WORD
        if (duration > 0 && expected > 0 && duration < expected * PaceHeuristic.SHORT_FACTOR) {
            notes += PronunciationNote("pace-short", "Shorter than a calm reading of this sentence.")
        } else if (duration > expected * PaceHeuristic.LONG_FACTOR) {
            notes += PronunciationNote("pace-long", "Longer than a calm reading of this sentence.")
        }
        if (transcript == null) {
            notes += PronunciationNote("recognition-unavailable", "On-device recognition was not available.")
        } else {
            val alignment = alignWords(normalize(target), normalize(transcript))
            if (alignment.missing.isNotEmpty()) {
                notes += PronunciationNote("missing-words", "Missing: ${alignment.missing.joinToString(" ")}")
            }
            if (alignment.extra.isNotEmpty()) {
                notes += PronunciationNote("extra-words", "Extra: ${alignment.extra.joinToString(" ")}")
            }
        }
        return PronunciationFeedback(
            target = target,
            transcript = transcript,
            availability = if (transcript == null) SpeechAvailability.UNAVAILABLE else SpeechAvailability.ON_DEVICE,
            durationMillis = duration,
            rms = rms,
            clippingRatio = clippingRatio,
            notes = notes,
        )
    }

    fun normalize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s']"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }

    private data class Alignment(val missing: List<String>, val extra: List<String>)

    private fun alignWords(target: List<String>, heard: List<String>): Alignment {
        val rows = target.size + 1
        val cols = heard.size + 1
        val cost = Array(rows) { IntArray(cols) }
        for (row in 0 until rows) cost[row][0] = row
        for (col in 0 until cols) cost[0][col] = col
        for (row in 1 until rows) {
            for (col in 1 until cols) {
                val sub = cost[row - 1][col - 1] + if (target[row - 1] == heard[col - 1]) 0 else 1
                cost[row][col] = min(sub, min(cost[row - 1][col] + 1, cost[row][col - 1] + 1))
            }
        }
        val missing = mutableListOf<String>()
        val extra = mutableListOf<String>()
        var row = target.size
        var col = heard.size
        while (row > 0 || col > 0) {
            if (row > 0 && col > 0 && target[row - 1] == heard[col - 1] && cost[row][col] == cost[row - 1][col - 1]) {
                row--
                col--
            } else if (row > 0 && col > 0 && cost[row][col] == cost[row - 1][col - 1] + 1) {
                missing += target[row - 1]
                extra += heard[col - 1]
                row--
                col--
            } else if (row > 0 && cost[row][col] == cost[row - 1][col] + 1) {
                missing += target[row - 1]
                row--
            } else {
                extra += heard[col - 1]
                col--
            }
        }
        missing.reverse()
        extra.reverse()
        return Alignment(missing, extra)
    }
}

object OfflineIndicator {
    fun chip(kind: RuntimeKind?, modelReady: Boolean, downloading: Boolean, cloudSelected: Boolean): OfflineChip {
        return when {
            downloading -> OfflineChip.DOWNLOADING
            cloudSelected || kind == RuntimeKind.CLOUD -> OfflineChip.CLOUD
            modelReady && kind == RuntimeKind.LOCAL -> OfflineChip.OFFLINE
            else -> OfflineChip.NEED_MODEL
        }
    }
}

enum class UiLanguage { EN, VI }

object LocaleChoice {
    fun languageOf(languageTag: String): UiLanguage {
        val language = languageTag.lowercase().substringBefore('-').substringBefore('_')
        return if (language == "vi") UiLanguage.VI else UiLanguage.EN
    }
}

data class Copy(
    val appName: String,
    val cefrDisclaimer: String,
    val continueLabel: String,
    val home: String,
    val review: String,
    val chat: String,
    val read: String,
    val more: String,
    val speak: String,
    val write: String,
    val progress: String,
    val library: String,
    val settings: String,
    val save: String,
    val skip: String,
    val send: String,
    val again: String,
    val hard: String,
    val good: String,
    val easy: String,
    val needModel: String,
    val cloudLeavesDevice: String,
    val speechUnavailable: String,
    val voiceMissing: String,
    val offline: String,
    val cloud: String,
    val downloading: String,
    val thermalNormal: String,
    val thermalShorter: String,
    val thermalStopped: String,
    val thermalUnavailable: String,
    val draft: String,
    val record: String,
    val stop: String,
    val keyMissing: String,
    val unverifiedModel: String,
    val pronunciationLimit: String,
    val downloadUnavailable: String,
) {
    companion object {
        fun of(language: UiLanguage): Copy = if (language == UiLanguage.VI) VI else EN

        val EN: Copy = Copy(
            appName = "Aether",
            cefrDisclaimer = "This level is an estimate for practice and leveling, not a certificate.",
            continueLabel = "Continue",
            home = "Home",
            review = "Review",
            chat = "Chat",
            read = "Read",
            more = "More",
            speak = "Speak",
            write = "Write",
            progress = "Progress",
            library = "Library",
            settings = "Settings",
            save = "Save",
            skip = "Skip",
            send = "Send",
            again = "Again",
            hard = "Hard",
            good = "Good",
            easy = "Easy",
            needModel = "A local model is required for this chat.",
            cloudLeavesDevice = "This chat turn leaves the device and is sent to the provider you chose.",
            speechUnavailable = "On-device speech recognition is not available. Audio was not sent anywhere.",
            voiceMissing = "Install an English voice on this device. Aether will not download one.",
            offline = "Offline",
            cloud = "Cloud",
            downloading = "Downloading",
            thermalNormal = "Normal",
            thermalShorter = "Shorter replies while the device is warm",
            thermalStopped = "Stopped because the device is hot",
            thermalUnavailable = "No thermal reading",
            draft = "Draft",
            record = "Record",
            stop = "Stop",
            keyMissing = "Save a provider key before a cloud turn. Aether will not switch to on-device silently.",
            unverifiedModel = "This local file has no published checksum, so it stays unverified.",
            pronunciationLimit = "Notes use word alignment, loudness, clipping, and pace. There is no speaking score, phoneme grade, or certificate.",
            downloadUnavailable = "Download stays off until a checksum is published.",
        )

        val VI: Copy = Copy(
            appName = "Aether",
            cefrDisclaimer = "Mức này là ước lượng để luyện tập và xếp bài, không phải chứng chỉ.",
            continueLabel = "Tiếp tục",
            home = "Trang chủ",
            review = "Ôn tập",
            chat = "Hội thoại",
            read = "Đọc",
            more = "Thêm",
            speak = "Nói",
            write = "Viết",
            progress = "Tiến độ",
            library = "Thư viện",
            settings = "Cài đặt",
            save = "Lưu",
            skip = "Bỏ qua",
            send = "Gửi",
            again = "Lại",
            hard = "Khó",
            good = "Tốt",
            easy = "Dễ",
            needModel = "Cần một model trên máy cho hội thoại này.",
            cloudLeavesDevice = "Lượt chat này rời khỏi máy và được gửi tới nhà cung cấp bạn đã chọn.",
            speechUnavailable = "Máy không có nhận dạng giọng nói trên máy. Âm thanh không được gửi đi.",
            voiceMissing = "Hãy cài giọng tiếng Anh trên máy. Aether không tự tải giọng.",
            offline = "Ngoại tuyến",
            cloud = "Đám mây",
            downloading = "Đang tải",
            thermalNormal = "Bình thường",
            thermalShorter = "Câu trả lời ngắn hơn vì máy đang ấm",
            thermalStopped = "Đã dừng vì máy đang nóng",
            thermalUnavailable = "Không có số đo nhiệt",
            draft = "Soạn",
            record = "Ghi âm",
            stop = "Dừng",
            keyMissing = "Hãy lưu khóa nhà cung cấp trước lượt đám mây. Aether không tự chuyển về máy.",
            unverifiedModel = "Tệp trên máy chưa có checksum đã công bố, nên chưa được xác minh.",
            pronunciationLimit = "Ghi chú dựa trên khớp từ, độ lớn, cắt đỉnh và nhịp. Không có điểm nói, điểm âm vị, hay chứng chỉ.",
            downloadUnavailable = "Nút tải ở trạng thái tắt cho đến khi có checksum.",
        )
    }
}
