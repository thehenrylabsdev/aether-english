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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MemoryLearningStore : LearningStore, VectorStore {
    private var profile: LearnerProfile? = null
    private val estimateRows = mutableListOf<CefrEstimate>()
    private val srsRows = mutableListOf<SrsItem>()
    private val conversationRows = mutableListOf<ConversationRecord>()
    private val messageRows = mutableListOf<StoredMessage>()
    private val mistakeRows = mutableListOf<Mistake>()
    private val libraryRows = mutableListOf<LibraryItem>()
    private val metricRows = mutableListOf<MetricsSample>()
    private val installRows = mutableListOf<ModelInstall>()
    private val vectorRows = mutableListOf<VectorDocument>()

    override suspend fun getProfile(): LearnerProfile? = profile

    override suspend fun saveProfile(profile: LearnerProfile) {
        val existing = this.profile
        if (existing != null && existing.externalId != profile.externalId) {
            error("profile already exists")
        }
        val minutes = profile.dailyMinutes.coerceIn(Limits.DAILY_MIN_MINUTES, Limits.DAILY_MAX_MINUTES)
        this.profile = profile.copy(dailyMinutes = minutes)
    }

    override suspend fun estimates(): List<CefrEstimate> = estimateRows.toList()

    override suspend fun saveEstimate(estimate: CefrEstimate) {
        estimateRows += estimate
    }

    override suspend fun srsItems(): List<SrsItem> = srsRows.toList()

    override suspend fun saveSrs(item: SrsItem) {
        val index = srsRows.indexOfFirst { it.externalId == item.externalId }
        if (index >= 0) srsRows[index] = item else srsRows += item
    }

    override suspend fun conversations(): List<ConversationRecord> = conversationRows.toList()

    override suspend fun saveConversation(record: ConversationRecord) {
        val index = conversationRows.indexOfFirst { it.externalId == record.externalId }
        if (index >= 0) conversationRows[index] = record else conversationRows += record
    }

    override suspend fun messages(conversationId: String): List<StoredMessage> {
        return messageRows.filter { it.conversationId == conversationId }
    }

    override suspend fun saveMessage(message: StoredMessage) {
        messageRows += message
    }

    override suspend fun mistakes(): List<Mistake> = mistakeRows.toList()

    override suspend fun saveMistake(mistake: Mistake) {
        mistakeRows += mistake
    }

    override suspend fun library(): List<LibraryItem> = libraryRows.toList()

    override suspend fun saveLibrary(item: LibraryItem) {
        require(item.license.isNotBlank()) { "license required" }
        val index = libraryRows.indexOfFirst { it.externalId == item.externalId }
        if (index >= 0) libraryRows[index] = item else libraryRows += item
    }

    override suspend fun metrics(): List<MetricsSample> = metricRows.toList()

    override suspend fun saveMetrics(sample: MetricsSample) {
        metricRows += sample
    }

    override suspend fun modelInstalls(): List<ModelInstall> = installRows.toList()

    override suspend fun saveModelInstall(install: ModelInstall) {
        val index = installRows.indexOfFirst { it.modelId == install.modelId }
        if (index >= 0) installRows[index] = install else installRows += install
    }

    override suspend fun upsert(document: VectorDocument) {
        CosineIndex.requireDimension(document.embedding)
        val index = vectorRows.indexOfFirst { it.externalId == document.externalId }
        val copy = document.copy(embedding = document.embedding.copyOf())
        if (index >= 0) vectorRows[index] = copy else vectorRows += copy
    }

    override suspend fun search(query: FloatArray, limit: Int, filter: VectorFilter): List<VectorHit> {
        return CosineIndex.rank(query, vectorRows.toList(), limit, filter)
    }

    override suspend fun deleteBySource(source: VectorSource, sourceId: String) {
        vectorRows.removeAll { it.source == source && it.sourceId == sourceId }
    }

    override suspend fun count(): Int = vectorRows.size
}

class MemoryBlobs : ModelBlobs {
    private val partial = linkedMapOf<String, ByteArray>()
    private val ready = linkedMapOf<String, ByteArray>()
    val promoted = mutableListOf<String>()

    override fun length(modelId: String): Long = (partial[modelId]?.size ?: 0).toLong()

    override fun append(modelId: String, bytes: ByteArray) {
        val current = partial[modelId] ?: ByteArray(0)
        partial[modelId] = current + bytes
    }

    override fun read(modelId: String): ByteArray = partial[modelId] ?: ByteArray(0)

    override fun reset(modelId: String) {
        partial.remove(modelId)
    }

    override fun promote(modelId: String, sha256: String) {
        ready[modelId] = partial[modelId] ?: ByteArray(0)
        partial.remove(modelId)
        promoted += sha256
    }
}

object ContentDraftParser {
    fun parse(text: String): LibraryItem? {
        val lines = text.lines()
        var kind: LibraryKind? = null
        var cefr: CefrLevel? = null
        var title = ""
        val body = StringBuilder()
        var inBody = false
        lines.forEach { raw ->
            val line = raw.trim()
            if (line == Markers.FIX_SEPARATOR) {
                inBody = true
                return@forEach
            }
            if (inBody) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(raw)
                return@forEach
            }
            when {
                line.startsWith("KIND:") -> kind = runCatching {
                    LibraryKind.valueOf(line.removePrefix("KIND:").trim().uppercase())
                }.getOrNull()
                line.startsWith("CEFR:") -> cefr = runCatching {
                    CefrLevel.valueOf(line.removePrefix("CEFR:").trim().uppercase())
                }.getOrNull()
                line.startsWith("TITLE:") -> title = line.removePrefix("TITLE:").trim()
            }
        }
        val resolvedKind = kind ?: return null
        val resolvedCefr = cefr ?: return null
        if (title.isBlank() || body.isBlank()) return null
        return LibraryItem(
            externalId = Ids.newId(),
            kind = resolvedKind,
            cefr = resolvedCefr,
            title = title,
            body = body.toString().trim(),
            license = Markers.GENERATED_LICENSE,
            attribution = "Drafted on device by the Aether content pipeline.",
            answer = null,
        )
    }
}

object ContentCatalog {
    fun parseItems(json: String): List<LibraryItem> {
        val items = mutableListOf<LibraryItem>()
        val chunks = json.split("\"id\"")
        chunks.drop(1).forEach { chunk ->
            val id = SseParsers.stringField("{\"id\"$chunk", "id") ?: return@forEach
            val kindName = SseParsers.stringField(chunk, "kind") ?: return@forEach
            val cefrName = SseParsers.stringField(chunk, "cefr") ?: return@forEach
            val title = SseParsers.stringField(chunk, "title") ?: return@forEach
            val license = SseParsers.stringField(chunk, "license") ?: ""
            val attribution = SseParsers.stringField(chunk, "attribution") ?: ""
            val body = SseParsers.stringField(chunk, "body") ?: ""
            val answer = SseParsers.stringField(chunk, "answer")
            require(license.isNotBlank()) { "license required" }
            items += LibraryItem(
                externalId = id,
                kind = LibraryKind.valueOf(kindName.uppercase()),
                cefr = CefrLevel.valueOf(cefrName.uppercase()),
                title = title,
                body = body,
                license = license,
                attribution = attribution,
                answer = answer,
            )
        }
        return items
    }

    fun levelFor(profile: LearnerProfile?, estimate: CefrEstimate?): CefrLevel {
        return estimate?.overall ?: profile?.selfReportedCefr ?: CefrLevel.A2
    }
}

class GrammarLookupTool(private val library: suspend () -> List<LibraryItem>) : TutorTool {
    override val spec = ToolSpec(
        name = Markers.GRAMMAR_LOOKUP,
        description = "Look up a local grammar note by rule id or keyword.",
        parametersJson = """{"type":"object","properties":{"ruleId":{"type":"string"}}}""",
    )
    override val callableBy = setOf(ToolCaller.MODEL, ToolCaller.APP)

    override suspend fun run(argumentsJson: String, context: ToolContext): ToolResult {
        val query = SseParsers.stringField(argumentsJson, "ruleId")
            ?: SseParsers.stringField(argumentsJson, "keyword")
            ?: ""
        val match = library().firstOrNull { item ->
            item.kind == LibraryKind.GRAMMAR &&
                (item.externalId == query || item.title.contains(query, ignoreCase = true) || item.body.contains(query, ignoreCase = true))
        }
        val content = if (match == null) "not-found" else "${match.title}\n${match.body}"
        return ToolResult(callId = Markers.GRAMMAR_LOOKUP, name = spec.name, content = content)
    }
}

class GenerateExerciseTool : TutorTool {
    override val spec = ToolSpec(
        name = Markers.GENERATE_EXERCISE,
        description = "Return an exercise draft. Do not write the database.",
        parametersJson = """{"type":"object","properties":{"prompt":{"type":"string"}}}""",
    )
    override val callableBy = setOf(ToolCaller.MODEL)

    override suspend fun run(argumentsJson: String, context: ToolContext): ToolResult {
        val prompt = SseParsers.stringField(argumentsJson, "prompt") ?: "Write one sentence."
        return ToolResult(
            callId = Markers.GENERATE_EXERCISE,
            name = spec.name,
            content = "prompt=$prompt; answer=; ruleId=-",
        )
    }
}

class RetrieveMemoryTool(private val embedding: EmbeddingProvider, private val vectors: VectorStore) : TutorTool {
    override val spec = ToolSpec(
        name = Markers.RETRIEVE_MEMORY,
        description = "Search the local library.",
        parametersJson = """{"type":"object","properties":{"query":{"type":"string"}}}""",
    )
    override val callableBy = setOf(ToolCaller.MODEL)

    override suspend fun run(argumentsJson: String, context: ToolContext): ToolResult {
        if (context.alreadyRetrieved || !context.band.allowsSecondRetrieval()) {
            return ToolResult(spec.name, spec.name, "retrieval-refused")
        }
        val query = SseParsers.stringField(argumentsJson, "query") ?: ""
        val vector = embedding.embed(EmbeddingPrefixes.apply(EmbeddingTask.SEARCH_QUERY, query), EmbeddingTask.SEARCH_QUERY)
        val hits = vectors.search(vector, Limits.RETRIEVAL_LIMIT, VectorFilter(null))
        val text = hits.take(Limits.RETRIEVAL_KEEP).joinToString("\n") { it.document.text.take(Limits.RETRIEVAL_CHARS) }
        return ToolResult(spec.name, spec.name, text)
    }
}

class UpdateProgressTool : TutorTool {
    override val spec = ToolSpec(Markers.UPDATE_PROGRESS, "Write SRS or the profile. App only.", "{}")
    override val callableBy = setOf(ToolCaller.APP)
    override suspend fun run(argumentsJson: String, context: ToolContext): ToolResult {
        return ToolResult(spec.name, spec.name, "app-only")
    }
}

private fun ThermalBand.allowsSecondRetrieval(): Boolean {
    return this != ThermalBand.SEVERE && this != ThermalBand.CRITICAL && this != ThermalBand.EMERGENCY
}

data class TutorTurn(
    val sessionId: String,
    val mode: ConversationMode,
    val userText: String,
    val history: List<ChatTurn>,
    val profile: LearnerProfile,
    val estimate: CefrEstimate?,
    val mistakes: List<Mistake>,
    val runtime: RuntimeSelection,
    val modelReady: Boolean,
    val embeddingReady: Boolean,
    val speculativeRequested: Boolean,
    val modelId: String,
    val availRamBytes: Long?,
)

class AgentOrchestrator(
    private val llm: LlmProvider,
    private val embedding: EmbeddingProvider,
    private val vectors: VectorStore,
    private val thermal: ThermalMonitor,
    private val tools: List<TutorTool>,
    private val metrics: LearningStore,
    private val clock: AppClock,
) {
    private val mutex = Mutex()

    fun run(turn: TutorTurn): Flow<TutorEvent> = flow {
        mutex.withLock {
            val started = clock.nowMillis()
            val snapshot = thermal.current()
            val budget = ThermalPolicy.decide(snapshot.band, turn.speculativeRequested)
            if (budget.stop) {
                emit(TutorEvent.Throttled)
                embedding.unload()
                record(turn, snapshot, budget, started, 0, null, "stop")
                return@withLock
            }
            if (turn.runtime.kind == RuntimeKind.LOCAL && !turn.modelReady) {
                emit(TutorEvent.NeedModel)
                return@withLock
            }
            val contextNotes = retrieve(turn, snapshot.band)
            if (contextNotes.isNotEmpty()) embedding.unload()
            llm.prepare(turn.runtime)
            val clipped = PromptBuilder.clip(turn.history, contextNotes)
            val messages = clipped + ChatTurn(AuthorRole.USER, turn.userText)
            val system = PromptBuilder.system(turn.profile, turn.estimate, turn.mistakes, turn.mode)
            val modelTools = tools.filter { ToolCaller.MODEL in it.callableBy }.map { it.spec }
            var rounds = 0
            var text = ""
            var firstTokenAt: Long? = null
            var failure: LlmFailure? = null
            var retrieved = contextNotes.isNotEmpty()
            var continueTurn = true
            while (continueTurn) {
                val request = LlmRequest(
                    sessionId = turn.sessionId,
                    systemPrompt = system,
                    messages = messages,
                    tools = modelTools,
                    maxOutputTokens = budget.maxOutputTokens,
                    thinkingBudget = budget.thinkingBudget,
                    enableSpeculativeDecoding = budget.speculativeDecoding,
                )
                var toolCall: LlmEvent.ToolCallRequested? = null
                llm.stream(request).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            if (firstTokenAt == null) firstTokenAt = clock.nowMillis()
                            text += event.text
                            emit(TutorEvent.TextDelta(event.text))
                        }
                        is LlmEvent.ThoughtDelta -> Unit
                        is LlmEvent.ToolCallRequested -> toolCall = event
                        is LlmEvent.TurnFailed -> failure = event.reason
                        LlmEvent.TurnCompleted -> Unit
                    }
                }
                val call = toolCall
                if (failure != null) {
                    emit(TutorEvent.Failed(failure!!))
                    continueTurn = false
                } else if (call != null && rounds < Limits.TOOL_ROUNDS) {
                    val tool = tools.firstOrNull { it.spec.name == call.name }
                    val allowed = tool != null && ToolCaller.MODEL in tool.callableBy &&
                        !(call.name == Markers.RETRIEVE_MEMORY && (retrieved || !budget.allowSecondRetrieval))
                    if (!allowed || tool == null) {
                        emit(TutorEvent.ToolRejected(call.name))
                        continueTurn = false
                    } else {
                        rounds++
                        val result = tool.run(
                            call.argumentsJson,
                            ToolContext(turn.sessionId, clock.nowMillis(), snapshot.band, retrieved),
                        )
                        if (call.name == Markers.RETRIEVE_MEMORY) retrieved = true
                        llm.sendToolResults(turn.sessionId, listOf(result))
                    }
                } else {
                    continueTurn = false
                }
            }
            val fixes = FixParser.parse(text)
            if (fixes.isNotEmpty()) emit(TutorEvent.CorrectionReady(fixes))
            if (failure == null) emit(TutorEvent.Completed)
            val ttft = firstTokenAt?.let { it - started }
            record(turn, snapshot, budget, started, text.length, ttft, if (budget.stop) "stop" else snapshot.band.name)
        }
    }

    private suspend fun retrieve(turn: TutorTurn, band: ThermalBand): List<ChatTurn> {
        val cloudBlocked = turn.runtime.kind == RuntimeKind.CLOUD && !turn.runtime.sendPersonalContext
        if (cloudBlocked || band == ThermalBand.SEVERE || band == ThermalBand.CRITICAL || band == ThermalBand.EMERGENCY) {
            return emptyList()
        }
        if (!turn.embeddingReady || !embedding.ready || vectors.count() == 0) return emptyList()
        val vector = embedding.embed(
            EmbeddingPrefixes.apply(EmbeddingTask.SEARCH_QUERY, turn.userText),
            EmbeddingTask.SEARCH_QUERY,
        )
        val hits = vectors.search(vector, Limits.RETRIEVAL_LIMIT, VectorFilter(null)).take(Limits.RETRIEVAL_KEEP)
        return hits.map { hit -> ChatTurn(AuthorRole.CONTEXT, hit.document.text.take(Limits.RETRIEVAL_CHARS)) }
    }

    private suspend fun record(
        turn: TutorTurn,
        snapshot: ThermalSnapshot,
        budget: GenerationBudget,
        started: Long,
        chars: Int,
        ttft: Long?,
        action: String,
    ) {
        metrics.saveMetrics(
            MetricsSample(
                capturedAtMillis = clock.nowMillis(),
                conversationId = turn.sessionId,
                modelId = turn.modelId,
                backend = when (turn.runtime.kind) {
                    RuntimeKind.CLOUD -> "CLOUD"
                    RuntimeKind.LOCAL -> turn.runtime.backend.name
                },
                thermalBand = snapshot.band,
                availRamBytes = turn.availRamBytes,
                ttftMillis = ttft,
                outputChars = chars,
                outputTokens = null,
                wallMillis = clock.nowMillis() - started,
                throttleAction = action,
            ),
        )
        if (budget.stop) llm.unload()
    }
}

class GetDueReviews(private val store: LearningStore) {
    suspend fun invoke(nowMillis: Long, limit: Int): List<SrsItem> {
        return store.srsItems().filter { it.dueAtMillis <= nowMillis }.sortedBy { it.dueAtMillis }.take(limit)
    }
}

class GradeSrsItem(private val store: LearningStore, private val scheduler: FsrsScheduler) {
    suspend fun invoke(itemId: String, grade: FsrsRating, nowMillis: Long): SrsItem {
        val item = store.srsItems().first { it.externalId == itemId }
        val updated = scheduler.review(item, grade, nowMillis)
        store.saveSrs(updated)
        return updated
    }
}

class SaveLearnerProfile(private val store: LearningStore) {
    suspend fun invoke(profile: LearnerProfile): LearnerProfile {
        store.saveProfile(profile)
        return store.getProfile() ?: profile
    }
}

class SaveFix(
    private val store: LearningStore,
    private val embedding: EmbeddingProvider,
    private val vectors: VectorStore,
    private val clock: AppClock,
) {
    suspend fun invoke(fix: FixLine, conversationId: String, messageId: String, kind: MistakeKind): SrsItem {
        val now = clock.nowMillis()
        val mistake = Mistake(
            externalId = Ids.newId(),
            conversationId = conversationId,
            messageId = messageId,
            kind = kind,
            original = fix.original,
            corrected = fix.corrected,
            explanation = fix.note,
            ruleId = fix.ruleId,
            createdAtMillis = now,
        )
        store.saveMistake(mistake)
        val card = SrsItem(
            externalId = Ids.newId(),
            kind = if (kind == MistakeKind.PRONUNCIATION) SrsKind.CHUNK else if (kind == MistakeKind.GRAMMAR) SrsKind.STRUCTURE else SrsKind.VOCAB,
            front = fix.original,
            back = fix.corrected,
            example = fix.note,
            cefr = CefrLevel.A2,
            stability = 0.0,
            difficulty = 0.0,
            scheduledDays = 0,
            reps = 0,
            lapses = 0,
            cardState = FsrsCardState.NEW,
            dueAtMillis = now,
            lastReviewedAtMillis = null,
            sourceMistakeId = mistake.externalId,
        )
        store.saveSrs(card)
        if (embedding.ready) {
            val text = "${fix.original} => ${fix.corrected}. ${fix.note}"
            val vector = embedding.embed(EmbeddingPrefixes.apply(EmbeddingTask.SEARCH_DOCUMENT, text), EmbeddingTask.SEARCH_DOCUMENT)
            vectors.upsert(
                VectorDocument(
                    externalId = Ids.newId(),
                    source = VectorSource.MISTAKE,
                    sourceId = mistake.externalId,
                    text = text,
                    embedding = vector,
                    embeddingModelId = "embedding",
                    createdAtMillis = now,
                ),
            )
            embedding.unload()
        }
        return card
    }
}

class SaveSelection(
    private val store: LearningStore,
    private val embedding: EmbeddingProvider,
    private val vectors: VectorStore,
    private val clock: AppClock,
) {
    suspend fun invoke(text: String, sourceId: String): VectorDocument? {
        if (!embedding.ready) return null
        val vector = embedding.embed(EmbeddingPrefixes.apply(EmbeddingTask.SEARCH_DOCUMENT, text), EmbeddingTask.SEARCH_DOCUMENT)
        val document = VectorDocument(
            externalId = Ids.newId(),
            source = VectorSource.SAVED_CONTENT,
            sourceId = sourceId,
            text = text,
            embedding = vector,
            embeddingModelId = "embedding",
            createdAtMillis = clock.nowMillis(),
        )
        vectors.upsert(document)
        embedding.unload()
        store.saveLibrary(
            LibraryItem(
                externalId = document.externalId,
                kind = LibraryKind.READING,
                cefr = CefrLevel.A2,
                title = text.take(48),
                body = text,
                license = Markers.ORIGINAL_LICENSE,
                attribution = "Saved selection",
                answer = null,
            ),
        )
        return document
    }
}

class RecordPlacement(private val store: LearningStore, private val clock: AppClock) {
    suspend fun invoke(level: CefrLevel, confirmed: Boolean): CefrEstimate? {
        if (!confirmed) return null
        val estimate = CefrEstimate(
            externalId = Ids.newId(),
            assessedAtMillis = clock.nowMillis(),
            source = CefrEstimate.PLACEMENT,
            overall = level,
            reading = level,
            writing = level,
            listening = level,
            speaking = level,
            note = "Confirmed placement estimate",
        )
        store.saveEstimate(estimate)
        return estimate
    }
}

class BuildDailyPath(private val store: LearningStore) {
    suspend fun invoke(nowMillis: Long, modelReady: Boolean): DailyPath {
        val profile = store.getProfile()
        val budget = profile?.dailyMinutes ?: Limits.DAILY_MIN_MINUTES
        val level = ContentCatalog.levelFor(profile, store.estimates().maxByOrNull { it.assessedAtMillis })
        val steps = mutableListOf<DailyStep>()
        var used = 0
        val due = store.srsItems().count { it.dueAtMillis <= nowMillis }
        if (due > 0 && used + Limits.REVIEW_MINUTES <= budget) {
            steps += DailyStep(DailyStepKind.REVIEW, "Due cards", Limits.REVIEW_MINUTES, null)
            used += Limits.REVIEW_MINUTES
        }
        if (modelReady && used + Limits.SPEAK_MINUTES <= budget) {
            steps += DailyStep(DailyStepKind.SPEAK, "Speak", Limits.SPEAK_MINUTES, null)
            used += Limits.SPEAK_MINUTES
        }
        val reading = store.library().firstOrNull { it.kind == LibraryKind.READING && it.cefr == level }
            ?: store.library().firstOrNull { it.kind == LibraryKind.READING }
        if (reading != null && used + Limits.READ_MINUTES <= budget) {
            steps += DailyStep(DailyStepKind.READ, reading.title, Limits.READ_MINUTES, reading.externalId)
            used += Limits.READ_MINUTES
        }
        return DailyPath(steps, used)
    }
}

class DraftLearningContent(
    private val llm: LlmProvider,
    private val store: LearningStore,
) {
    fun prompt(outline: String, level: CefrLevel): String {
        return "CEFR ${level.name}. Outline: $outline. ${Markers.DO_NOT_COPY} Reply with KIND, CEFR, TITLE, then ${Markers.FIX_SEPARATOR}, then the body."
    }

    suspend fun invoke(outline: String, level: CefrLevel, sessionId: String): LibraryItem? {
        val request = LlmRequest(
            sessionId = sessionId,
            systemPrompt = prompt(outline, level),
            messages = listOf(ChatTurn(AuthorRole.USER, outline)),
            tools = emptyList(),
            maxOutputTokens = 512,
            thinkingBudget = 0,
            enableSpeculativeDecoding = false,
        )
        val text = StringBuilder()
        llm.stream(request).collect { event ->
            if (event is LlmEvent.TextDelta) text.append(event.text)
        }
        val item = ContentDraftParser.parse(text.toString()) ?: return null
        store.saveLibrary(item)
        return item
    }
}

class LoadDashboard(private val store: LearningStore, private val clock: AppClock) {
    suspend fun invoke(zoneOffsetMillis: Long): Dashboard {
        val now = clock.nowMillis()
        val learned = store.srsItems().count { it.reps >= 1 }
        val since = now - 14L * Limits.DAY_MILLIS
        val mistakes = store.mistakes().filter { it.createdAtMillis >= since }
        val counts = MistakeKind.entries.associateWith { kind -> mistakes.count { it.kind == kind } }
        val days = linkedSetOf<Long>()
        store.srsItems().mapNotNull { it.lastReviewedAtMillis }.forEach { stamp ->
            days += localDay(stamp, zoneOffsetMillis)
        }
        store.conversations().forEach { record ->
            if (record.endedAtMillis != null || store.messages(record.externalId).any { it.role == AuthorRole.TUTOR }) {
                days += localDay(record.startedAtMillis, zoneOffsetMillis)
            }
        }
        val today = localDay(now, zoneOffsetMillis)
        var streak = 0
        var cursor = today
        while (cursor in days) {
            streak++
            cursor -= 1
        }
        val estimate = store.estimates().maxByOrNull { it.assessedAtMillis }
        return Dashboard(estimate, learned, counts, streak)
    }

    private fun localDay(millis: Long, zoneOffsetMillis: Long): Long {
        return floorDiv(millis + zoneOffsetMillis, Limits.DAY_MILLIS)
    }

    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        val remainder = value % divisor
        return if (remainder != 0L && ((value < 0L) != (divisor < 0L))) quotient - 1 else quotient
    }
}

data class Dashboard(
    val estimate: CefrEstimate?,
    val learnedCards: Int,
    val mistakesByKind: Map<MistakeKind, Int>,
    val streakDays: Int,
)

class SendUtterance(
    private val store: LearningStore,
    private val orchestrator: AgentOrchestrator,
    private val clock: AppClock,
) {
    fun invoke(
        conversationId: String,
        text: String,
        mode: ConversationMode,
        runtime: RuntimeSelection,
        modelReady: Boolean,
        embeddingReady: Boolean,
        speculativeRequested: Boolean,
        modelId: String,
        availRamBytes: Long?,
    ): Flow<TutorEvent> = flow {
        val profile = store.getProfile() ?: error("profile required")
        val history = store.messages(conversationId).map { ChatTurn(it.role, it.text) }
        store.saveMessage(
            StoredMessage(Ids.newId(), conversationId, AuthorRole.USER, text, clock.nowMillis()),
        )
        val turn = TutorTurn(
            sessionId = conversationId,
            mode = mode,
            userText = text,
            history = history,
            profile = profile,
            estimate = store.estimates().maxByOrNull { it.assessedAtMillis },
            mistakes = store.mistakes().takeLast(Limits.MISTAKE_LINES),
            runtime = runtime,
            modelReady = modelReady,
            embeddingReady = embeddingReady,
            speculativeRequested = speculativeRequested,
            modelId = modelId,
            availRamBytes = availRamBytes,
        )
        val tutor = StringBuilder()
        orchestrator.run(turn).collect { event ->
            if (event is TutorEvent.TextDelta) tutor.append(event.text)
            emit(event)
            if (event is TutorEvent.Completed || event is TutorEvent.Failed || event is TutorEvent.Throttled || event is TutorEvent.NeedModel) {
                if (tutor.isNotEmpty()) {
                    store.saveMessage(
                        StoredMessage(Ids.newId(), conversationId, AuthorRole.TUTOR, tutor.toString(), clock.nowMillis()),
                    )
                }
            }
        }
    }
}

class FakeLlmProvider(scripts: List<List<LlmEvent>>) : LlmProvider {
    private val queue = ArrayDeque(scripts)
    val requests = mutableListOf<LlmRequest>()
    val toolResults = mutableListOf<List<ToolResult>>()
    var unloads: Int = 0
    override val kind: RuntimeKind = RuntimeKind.LOCAL
    override suspend fun prepare(selection: RuntimeSelection) = Unit
    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        requests += request
        val script = if (queue.isEmpty()) listOf(LlmEvent.TurnCompleted) else queue.removeFirst()
        script.forEach { emit(it) }
    }
    override suspend fun sendToolResults(sessionId: String, results: List<ToolResult>) {
        toolResults += results
    }
    override suspend fun cancel(sessionId: String) = Unit
    override suspend fun unload() {
        unloads++
    }
}

class MapEmbedding(private val vector: FloatArray, override var ready: Boolean = true) : EmbeddingProvider {
    val calls = mutableListOf<String>()
    override suspend fun embed(text: String, task: EmbeddingTask): FloatArray {
        calls += text
        return vector.copyOf()
    }
    override suspend fun unload() = Unit
}

class FakeThermal(var band: ThermalBand, var supported: Boolean = true) : ThermalMonitor {
    override fun current(): ThermalSnapshot = ThermalSnapshot(band, 0L, supported)
    override fun changes(): Flow<ThermalSnapshot> = flow { }
    override fun close() = Unit
}
