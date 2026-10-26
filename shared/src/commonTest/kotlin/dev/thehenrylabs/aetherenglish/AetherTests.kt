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
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class FsrsSchedulerTest {
    private val scheduler = FsrsScheduler()
    private val now = 1_700_000_000_000L

    private fun fresh(): SrsItem = SrsItem(
        externalId = "card",
        kind = SrsKind.VOCAB,
        front = "go",
        back = "went",
        example = "I went home.",
        cefr = CefrLevel.A2,
        stability = 0.0,
        difficulty = 0.0,
        scheduledDays = 0,
        reps = 0,
        lapses = 0,
        cardState = FsrsCardState.NEW,
        dueAtMillis = now,
        lastReviewedAtMillis = null,
        sourceMistakeId = null,
    )

    @Test
    fun goodFirstReviewUsesFsrsStabilityAndSchedulesThreeDays() {
        val graded = scheduler.review(fresh(), FsrsRating.GOOD, now)
        assertEquals(Fsrs5.WEIGHTS[2], graded.stability)
        assertEquals(3, graded.scheduledDays)
        assertEquals(0, graded.lapses)
        assertEquals(1, graded.reps)
        assertEquals(now + 3 * Limits.DAY_MILLIS, graded.dueAtMillis)
        assertTrue(graded.difficulty in 1.0..10.0)
    }

    @Test
    fun againOnANewCardIncreasesLapsesAndIsDueTomorrow() {
        val graded = scheduler.review(fresh(), FsrsRating.AGAIN, now)
        assertEquals(1, graded.lapses)
        assertEquals(1, graded.scheduledDays)
        assertEquals(0, graded.reps - 1)
        assertEquals(FsrsCardState.RELEARNING, graded.cardState)
        assertTrue(graded.difficulty in 1.0..10.0)
    }

    @Test
    fun secondGoodReviewExtendsTheInterval() {
        val first = scheduler.review(fresh(), FsrsRating.GOOD, now)
        val second = scheduler.review(first, FsrsRating.GOOD, first.dueAtMillis)
        assertEquals(11, second.scheduledDays)
        assertTrue(second.stability > first.stability)
        assertEquals(0, second.lapses)
    }

    @Test
    fun againAfterSuccessIncreasesLapses() {
        val first = scheduler.review(fresh(), FsrsRating.GOOD, now)
        val again = scheduler.review(first, FsrsRating.AGAIN, first.dueAtMillis)
        assertEquals(1, again.lapses)
        assertEquals(1, again.scheduledDays)
        assertTrue(again.stability < first.stability)
    }

    @Test
    fun everyFirstRatingKeepsDifficultyInsideTheScale() {
        FsrsRating.entries.forEach { rating ->
            val graded = scheduler.review(fresh(), rating, now)
            assertTrue(graded.difficulty in 1.0..10.0)
            assertTrue(graded.stability > 0.0)
        }
    }
}

class DueQueueTest {
    @Test
    fun futureCardsStayHiddenAndNewCardsAreDueNow() = runTest {
        val store = MemoryLearningStore()
        val now = 1_700_000_000_000L
        store.saveSrs(card("later", now + Limits.DAY_MILLIS))
        store.saveSrs(card("now", now))
        val due = GetDueReviews(store).invoke(now, 10)
        assertEquals(listOf("now"), due.map { it.externalId })
    }

    private fun card(id: String, due: Long) = SrsItem(
        externalId = id,
        kind = SrsKind.VOCAB,
        front = id,
        back = "back",
        example = "",
        cefr = CefrLevel.A1,
        stability = 0.0,
        difficulty = 0.0,
        scheduledDays = 0,
        reps = 0,
        lapses = 0,
        cardState = FsrsCardState.NEW,
        dueAtMillis = due,
        lastReviewedAtMillis = null,
        sourceMistakeId = null,
    )
}

class FixParserTest {
    @Test
    fun missingSeparatorYieldsNothing() {
        assertTrue(FixParser.parse("You said went, good.").isEmpty())
    }

    @Test
    fun brokenLinesAreDroppedAndThreeLinesIsTheCap() {
        val text = """
            Note
            ---
            FIX: go => went | past | past-simple-regular
            not a fix
            FIX: broken line without arrow | note | -
            FIX: a => b | n | -
            FIX: c => d | n | rule
            FIX: e => f | n | -
        """.trimIndent()
        val fixes = FixParser.parse(text)
        assertEquals(3, fixes.size)
        assertEquals("go", fixes[0].original)
        assertEquals("went", fixes[0].corrected)
        assertEquals("past-simple-regular", fixes[0].ruleId)
        assertNull(fixes[1].ruleId)
    }
}

class PromptBuilderTest {
    @Test
    fun promptCarriesCefrAndAtMostFiveMistakes() {
        val profile = profile()
        val mistakes = (1..6).map { index ->
            Mistake("m$index", "c", "msg", MistakeKind.GRAMMAR, "a$index", "b$index", "n", null, 0L)
        }
        val prompt = PromptBuilder.system(profile, estimate(CefrLevel.B1), mistakes, ConversationMode.FREE)
        assertTrue(prompt.contains("B1"))
        assertFalse(prompt.contains("a1 =>"))
        assertTrue(prompt.contains("a2 =>"))
        assertTrue(prompt.contains("a6 =>"))
    }

    @Test
    fun historyKeepsSixDialogueTurnsPlusContext() {
        val history = (1..8).map { ChatTurn(if (it % 2 == 0) AuthorRole.TUTOR else AuthorRole.USER, "t$it") }
        val clipped = PromptBuilder.clip(history, listOf(ChatTurn(AuthorRole.CONTEXT, "note")))
        assertEquals(AuthorRole.CONTEXT, clipped.first().role)
        assertEquals(6, clipped.count { it.role != AuthorRole.CONTEXT })
        assertEquals("t8", clipped.last().text)
    }
}

class ThermalPolicyTest {
    @Test
    fun criticalStopsAndModerateCutsTokens() {
        val stop = ThermalPolicy.decide(ThermalBand.CRITICAL, speculativeRequested = true)
        assertTrue(stop.stop)
        assertFalse(stop.speculativeDecoding)
        val moderate = ThermalPolicy.decide(ThermalBand.MODERATE, speculativeRequested = true)
        assertEquals(256, moderate.maxOutputTokens)
        assertFalse(moderate.speculativeDecoding)
        assertEquals(0, moderate.thinkingBudget)
        val calm = ThermalPolicy.decide(ThermalBand.NONE, speculativeRequested = true)
        assertEquals(512, calm.maxOutputTokens)
        assertTrue(calm.speculativeDecoding)
        assertFalse(calm.stop)
        val severe = ThermalPolicy.decide(ThermalBand.SEVERE, speculativeRequested = true)
        assertEquals(128, severe.maxOutputTokens)
        assertFalse(severe.allowSecondRetrieval)
    }
}

class RamGateTest {
    @Test
    fun memoryBelowThreeHalvesRejectsTheFile() {
        assertFalse(RamGate.allows(availMem = 149, fileBytes = 100))
        assertTrue(RamGate.allows(availMem = 150, fileBytes = 100))
        val rejected = RuntimePlanner.decide(false, false, 1_000, 1_000, true, false)
        assertTrue(rejected.rejectedByRam)
        assertTrue(rejected.needModel)
        val cpu = RuntimePlanner.decide(false, false, 5_000, 1_000, true, gpuFailed = true)
        assertEquals(LocalBackend.CPU, cpu.backend)
    }
}

class CloudLogTest {
    @Test
    fun summaryOmitsTheKeyAndThePrompt() {
        val key = "sk-test-key-should-not-appear"
        val line = CloudCallLogger.line("gemini", 42)
        assertFalse(line.contains(key))
        assertFalse(line.contains("Bearer"))
        assertTrue(line.contains("42"))
        assertTrue(line.contains("gemini"))
    }

    @Test
    fun mockRequestDoesNotEchoTheKeyIntoTheLog() = runTest {
        val key = "sk-live-secret-value"
        val logs = mutableListOf<String>()
        val engine = MockEngine {
            respond("data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = HttpClient(engine)
        val prompt = "hello $key"
        logs += CloudCallLogger.line("openai-compatible", prompt.length)
        client.post("https://example.test/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $key")
            setBody(prompt)
        }
        assertEquals("Bearer $key", engine.requestHistory.first().headers[HttpHeaders.Authorization])
        assertTrue(logs.all { key !in it && prompt !in it })
        client.close()
    }
}

class LlmEventContractTest {
    @Test
    fun openAiAndGeminiFixturesShareTheTextContract() {
        val openAi = """
            data: {"choices":[{"delta":{"content":"Hi"}}]}

            data: [DONE]
        """.trimIndent()
        val gemini = """
            data: {"candidates":[{"content":{"parts":[{"text":"Hi"}]}}]}
        """.trimIndent()
        val left = SseParsers.openAi(openAi)
        val right = SseParsers.gemini(gemini)
        assertEquals(listOf(LlmEvent.TextDelta("Hi"), LlmEvent.TurnCompleted), left)
        assertEquals(left, right)
    }

    @Test
    fun openAiToolCallIsVisible() {
        val body = """
            data: {"choices":[{"delta":{"tool_calls":[{"id":"1","function":{"name":"RetrieveMemory","arguments":"{}"}}]}}]}
        """.trimIndent()
        val events = SseParsers.openAi(body)
        val tool = events.filterIsInstance<LlmEvent.ToolCallRequested>().single()
        assertEquals(Markers.RETRIEVE_MEMORY, tool.name)
    }
}

class CosineIndexTest {
    @Test
    fun matchingVectorRanksFirstAndWrongWidthIsRejected() = runTest {
        val query = FloatArray(Limits.EMBEDDING_DIMENSIONS) { if (it == 0) 1f else 0f }
        val near = query.copyOf()
        val far = FloatArray(Limits.EMBEDDING_DIMENSIONS) { if (it == 1) 1f else 0f }
        val store = MemoryLearningStore()
        store.upsert(document("far", far))
        store.upsert(document("near", near))
        val hits = store.search(query, 2, VectorFilter(null))
        assertEquals("near", hits.first().document.externalId)
        assertFailsWith<IllegalArgumentException> {
            store.upsert(document("bad", FloatArray(4)))
        }
    }

    private fun document(id: String, embedding: FloatArray) = VectorDocument(
        externalId = id,
        source = VectorSource.NOTE,
        sourceId = id,
        text = id,
        embedding = embedding,
        embeddingModelId = "embedding",
        createdAtMillis = 0L,
    )
}

class PronunciationAssessorTest {
    @Test
    fun feedbackUsesMeasuredSignalsAndNoPercentScore() {
        val pcm = ShortArray(16_000) { index -> if (index % 50 == 0) 32000 else 1000 }
        val feedback = PronunciationAssessor.assess("hello there", "hello", AudioClip(pcm, 16_000))
        val rendered = feedback.notes.joinToString(" ") { it.message }
        assertTrue(rendered.contains("Missing"))
        assertFalse(rendered.contains("%"))
        assertTrue(feedback.durationMillis in 900L..1100L)
        assertNull(feedback.notes.firstOrNull { it.code == "score" })
    }

    @Test
    fun silenceAndClippingAreReportedFromThePcm() {
        val quiet = PronunciationAssessor.assess("hello", null, AudioClip(ShortArray(1600), 16_000))
        assertTrue(quiet.notes.any { it.code == "quiet" })
        assertTrue(quiet.notes.any { it.code == "recognition-unavailable" })
        val clipped = ShortArray(1600) { 32767 }
        val loud = PronunciationAssessor.assess("hello", "hello", AudioClip(clipped, 16_000))
        assertTrue(loud.notes.any { it.code == "clipping" })
    }
}

class RetrievalGateTest {
    @Test
    fun severeBandAndEmptyStoreDoNotEmbed() = runTest {
        val embedding = MapEmbedding(FloatArray(Limits.EMBEDDING_DIMENSIONS) { 1f })
        val store = MemoryLearningStore()
        val llm = FakeLlmProvider(listOf(listOf(LlmEvent.TextDelta("ok"), LlmEvent.TurnCompleted)))
        val orchestrator = orchestrator(llm, embedding, store, ThermalBand.SEVERE)
        orchestrator.run(turn(modelReady = true, embeddingReady = true)).toList()
        assertTrue(embedding.calls.isEmpty())
        val calm = orchestrator(FakeLlmProvider(listOf(listOf(LlmEvent.TurnCompleted))), embedding, store, ThermalBand.NONE)
        calm.run(turn(modelReady = true, embeddingReady = true)).toList()
        assertTrue(embedding.calls.isEmpty())
    }

    @Test
    fun aStoredVectorIsInjectedAsContext() = runTest {
        val vector = FloatArray(Limits.EMBEDDING_DIMENSIONS) { 0.1f }
        val embedding = MapEmbedding(vector)
        val store = MemoryLearningStore()
        store.upsert(
            VectorDocument("doc", VectorSource.MISTAKE, "m", "yesterday I walked", vector, "embedding", 0L),
        )
        val llm = FakeLlmProvider(listOf(listOf(LlmEvent.TextDelta("yes"), LlmEvent.TurnCompleted)))
        orchestrator(llm, embedding, store, ThermalBand.LIGHT).run(turn(true, true)).toList()
        val context = llm.requests.single().messages.filter { it.role == AuthorRole.CONTEXT }
        assertEquals("yesterday I walked", context.single().text)
        assertEquals(1, embedding.calls.size)
    }
}

class AgentOrchestratorTest {
    @Test
    fun fakeProviderStreamsAndRejectsUpdateProgress() = runTest {
        val store = MemoryLearningStore()
        val llm = FakeLlmProvider(
            listOf(
                listOf(
                    LlmEvent.TextDelta("Hello"),
                    LlmEvent.ToolCallRequested("1", Markers.UPDATE_PROGRESS, "{}"),
                    LlmEvent.TurnCompleted,
                ),
            ),
        )
        val events = orchestrator(llm, MapEmbedding(FloatArray(Limits.EMBEDDING_DIMENSIONS)), store, ThermalBand.NONE)
            .run(turn(true, false))
            .toList()
        assertTrue(events.any { it is TutorEvent.TextDelta })
        assertTrue(events.any { it is TutorEvent.ToolRejected && it.name == Markers.UPDATE_PROGRESS })
        assertTrue(llm.requests.single().tools.none { it.name == Markers.UPDATE_PROGRESS })
        assertEquals(0, store.srsItems().size)
    }

    @Test
    fun criticalBandDoesNotCallTheModel() = runTest {
        val llm = FakeLlmProvider(listOf(listOf(LlmEvent.TextDelta("no"))))
        val events = orchestrator(llm, MapEmbedding(FloatArray(1)), MemoryLearningStore(), ThermalBand.CRITICAL)
            .run(turn(true, false))
            .toList()
        assertTrue(events.single() is TutorEvent.Throttled)
        assertTrue(llm.requests.isEmpty())
        assertEquals(1, llm.unloads)
    }

    @Test
    fun localRuntimeWithoutAModelStops() = runTest {
        val llm = FakeLlmProvider(emptyList())
        val events = orchestrator(llm, MapEmbedding(FloatArray(1)), MemoryLearningStore(), ThermalBand.NONE)
            .run(turn(false, false))
            .toList()
        assertTrue(events.single() is TutorEvent.NeedModel)
        assertTrue(llm.requests.isEmpty())
    }
}

class Sha256Test {
    @Test
    fun abcMatchesTheKnownDigest() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hex("abc".encodeToByteArray()),
        )
    }
}

class ModelDownloadTest {
    @Test
    fun partialRangeThenFullDigestPromotesAndABadByteFails() = runTest {
        val payload = "aether-model".encodeToByteArray()
        val digest = Sha256.hex(payload)
        val blobs = MemoryBlobs()
        var calls = 0
        val client = object : RangeClient {
            override suspend fun get(url: String, fromByte: Long): RangeResponse {
                calls++
                return if (calls == 1) {
                    RangeResponse(206, payload.copyOfRange(0, 4))
                } else {
                    RangeResponse(206, payload.copyOfRange(4, payload.size))
                }
            }
        }
        val spec = ModelSpec("gemma", ModelRole.CHAT, "model.litertlm", "https://example.test/m", payload.size.toLong(), digest)
        assertEquals(InstallState.READY, ModelDownloader(client, blobs).download(spec))
        assertEquals(digest, blobs.promoted.single())
        val broken = MemoryBlobs()
        val badClient = object : RangeClient {
            override suspend fun get(url: String, fromByte: Long): RangeResponse = RangeResponse(200, "nope".encodeToByteArray())
        }
        assertEquals(InstallState.FAILED, ModelDownloader(badClient, broken).download(spec))
    }
}

class ContentPipelineTest {
    @Test
    fun draftIsStoredWithGeneratedLicenseAndCopyBan() = runTest {
        val store = MemoryLearningStore()
        val script = """
            KIND: reading
            CEFR: A2
            TITLE: Market morning
            ---
            Mia buys two apples and asks the price.
        """.trimIndent()
        val llm = FakeLlmProvider(listOf(listOf(LlmEvent.TextDelta(script), LlmEvent.TurnCompleted)))
        val pipeline = DraftLearningContent(llm, store)
        assertTrue(pipeline.prompt("food", CefrLevel.A2).contains(Markers.DO_NOT_COPY))
        val saved = pipeline.invoke("food", CefrLevel.A2, "s")
        assertNotNull(saved)
        assertEquals(Markers.GENERATED_LICENSE, saved.license)
        assertEquals(LibraryKind.READING, store.library().single().kind)
    }

    @Test
    fun grammarLookupReadsTheLocalNote() = runTest {
        val store = MemoryLearningStore()
        store.saveLibrary(
            LibraryItem(
                "past-simple-regular",
                LibraryKind.GRAMMAR,
                CefrLevel.A2,
                "Past simple",
                "Add ed.",
                Markers.ORIGINAL_LICENSE,
                "Aether 2026",
                null,
            ),
        )
        val tool = GrammarLookupTool { store.library() }
        val result = tool.run("""{"ruleId":"past-simple-regular"}""", ToolContext("c", 0L, ThermalBand.NONE, false))
        assertTrue(result.content.contains("Add ed."))
    }
}

class LocaleAndPathTest {
    @Test
    fun localeAndDisclaimer() {
        assertEquals(UiLanguage.VI, LocaleChoice.languageOf("vi"))
        assertEquals(UiLanguage.VI, LocaleChoice.languageOf("vi-VN"))
        assertEquals(UiLanguage.EN, LocaleChoice.languageOf("en-US"))
        assertEquals(UiLanguage.EN, LocaleChoice.languageOf("fr"))
        assertTrue(Copy.of(UiLanguage.EN).cefrDisclaimer.contains("not a certificate"))
        assertTrue(Copy.of(UiLanguage.VI).cefrDisclaimer.contains("không phải chứng chỉ"))
    }

    @Test
    fun speechStaysOnDeviceOnlyFromApi31() {
        assertEquals(SpeechAvailability.UNAVAILABLE, SpeechPolicy.onDevice(24, true))
        assertEquals(SpeechAvailability.UNAVAILABLE, SpeechPolicy.onDevice(33, false))
        assertEquals(SpeechAvailability.ON_DEVICE, SpeechPolicy.onDevice(31, true))
    }

    @Test
    fun dailyPathWithoutAModelStillHasCardsAndReading() = runTest {
        val store = seededStore()
        val path = BuildDailyPath(store).invoke(1_700_000_000_000L, modelReady = false)
        assertTrue(path.steps.any { it.kind == DailyStepKind.REVIEW })
        assertTrue(path.steps.any { it.kind == DailyStepKind.READ })
        assertTrue(path.steps.none { it.kind == DailyStepKind.SPEAK })
    }

    @Test
    fun savingAFixCreatesADueCardAndSkipsWhenPlacementIsDeclined() = runTest {
        val store = seededStore()
        val clock = FixedClock(1_700_000_000_000L)
        val card = SaveFix(store, MapEmbedding(FloatArray(Limits.EMBEDDING_DIMENSIONS)), store, clock).invoke(
            FixLine("go", "went", "past", "past-simple-regular"),
            "c",
            "m",
            MistakeKind.GRAMMAR,
        )
        assertEquals(clock.millis, card.dueAtMillis)
        assertEquals(2, store.srsItems().size)
        assertEquals(1, store.count())
        assertNull(RecordPlacement(store, clock).invoke(CefrLevel.B1, confirmed = false))
        assertTrue(store.estimates().none { it.source == CefrEstimate.PLACEMENT })
        assertNotNull(RecordPlacement(store, clock).invoke(CefrLevel.B1, confirmed = true))
    }

    @Test
    fun offlineChipStaysOfflineForAReadyLocalRuntime() {
        assertEquals(OfflineChip.OFFLINE, OfflineIndicator.chip(RuntimeKind.LOCAL, true, false, false))
        assertEquals(OfflineChip.CLOUD, OfflineIndicator.chip(RuntimeKind.CLOUD, true, false, true))
        assertEquals(OfflineChip.NEED_MODEL, OfflineIndicator.chip(RuntimeKind.LOCAL, false, false, false))
    }
}

class ContentSeedTest {
    @Test
    fun seedKeepsLicenseAndGrammarNote() {
        val dir = System.getProperty("aether.contentDir")
        val json = File(dir, "seed.json").readText()
        val items = ContentCatalog.parseItems(json)
        assertTrue(items.all { it.license == Markers.ORIGINAL_LICENSE })
        assertTrue(items.any { it.externalId == "past-simple-regular" && it.kind == LibraryKind.GRAMMAR })
        assertTrue(items.any { it.kind == LibraryKind.READING && it.cefr == CefrLevel.A1 })
        assertTrue(items.any { it.kind == LibraryKind.READING && it.cefr == CefrLevel.B2 })
        assertTrue(items.any { it.kind == LibraryKind.EXERCISE })
    }
}

private fun profile() = LearnerProfile(
    externalId = "p",
    displayName = "Minh",
    nativeLanguage = "vi",
    goals = listOf(LearningGoal.DAILY),
    dailyMinutes = 10,
    selfReportedCefr = CefrLevel.A2,
    createdAtMillis = 0L,
    updatedAtMillis = 0L,
)

private fun estimate(level: CefrLevel) = CefrEstimate(
    "e", 0L, CefrEstimate.SELF_REPORT, level, level, level, level, level, "estimate",
)

private fun turn(modelReady: Boolean, embeddingReady: Boolean) = TutorTurn(
    sessionId = "s",
    mode = ConversationMode.FREE,
    userText = "past tense",
    history = emptyList(),
    profile = profile(),
    estimate = estimate(CefrLevel.A2),
    mistakes = emptyList(),
    runtime = RuntimeSelection(RuntimeKind.LOCAL, LocalBackend.CPU, null, "", false),
    modelReady = modelReady,
    embeddingReady = embeddingReady,
    speculativeRequested = false,
    modelId = "gemma4-e2b-it",
    availRamBytes = 4_000_000_000L,
)

private fun orchestrator(
    llm: LlmProvider,
    embedding: EmbeddingProvider,
    store: MemoryLearningStore,
    band: ThermalBand,
): AgentOrchestrator {
    return AgentOrchestrator(
        llm = llm,
        embedding = embedding,
        vectors = store,
        thermal = FakeThermal(band),
        tools = listOf(
            GrammarLookupTool { store.library() },
            GenerateExerciseTool(),
            UpdateProgressTool(),
            RetrieveMemoryTool(embedding, store),
        ),
        metrics = store,
        clock = FixedClock(1_700_000_000_000L),
    )
}

private suspend fun seededStore(): MemoryLearningStore {
    val store = MemoryLearningStore()
    store.saveProfile(profile())
    store.saveSrs(
        SrsItem(
            "due", SrsKind.VOCAB, "tea", "trà", "", CefrLevel.A1, 0.0, 0.0, 0, 0, 0,
            FsrsCardState.NEW, 1_700_000_000_000L, null, null,
        ),
    )
    store.saveLibrary(
        LibraryItem(
            "read-a2", LibraryKind.READING, CefrLevel.A2, "Market morning", "Mia buys apples.",
            Markers.ORIGINAL_LICENSE, "Aether 2026", null,
        ),
    )
    return store
}
