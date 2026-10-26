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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DownloadRow(
    val running: Boolean,
    val bytes: Long,
    val total: Long,
    val error: String?,
)

class DeviceSettings {
    var backend: LocalBackend = LocalBackend.CPU
    var preferCloud: Boolean = false
    var vendor: CloudVendor = CloudVendor.GEMINI
    var cloudModelId: String = ""
    var sendPersonalContext: Boolean = false
    var speculative: Boolean = false
    var chatPath: String = ""
    var embedPath: String = ""
    var gpuFailed: Boolean = false
}

class AetherModel(val runtime: AndroidRuntime, val copy: Copy) : ViewModel() {
    var loaded by mutableStateOf(false)
    var profile by mutableStateOf<LearnerProfile?>(null)
    var path by mutableStateOf(DailyPath(emptyList(), 0))
    var due by mutableStateOf<List<SrsItem>>(emptyList())
    var library by mutableStateOf<List<LibraryItem>>(emptyList())
    var dashboard by mutableStateOf<Dashboard?>(null)
    var transcript by mutableStateOf("")
    var fixes by mutableStateOf<List<FixLine>>(emptyList())
    var banner by mutableStateOf("")
    var chip by mutableStateOf(OfflineChip.NEED_MODEL)
    var thermal by mutableStateOf(runtime.thermal.current())
    var feedback by mutableStateOf<PronunciationFeedback?>(null)
    var voiceNote by mutableStateOf("")
    var recording by mutableStateOf(false)
    var settings by mutableStateOf(DeviceSettings())
    var keySaved by mutableStateOf(false)
    var huggingfaceSaved by mutableStateOf(false)
    var downloads by mutableStateOf<Map<String, DownloadRow>>(emptyMap())
    private var conversationId: String? = null
    private var idle: Job? = null

    private val orchestrator = AgentOrchestrator(
        llm = runtime.llm,
        embedding = runtime.embedding,
        vectors = runtime.store,
        thermal = runtime.thermal,
        tools = listOf(
            GrammarLookupTool { runtime.store.library() },
            GenerateExerciseTool(),
            UpdateProgressTool(),
            RetrieveMemoryTool(runtime.embedding, runtime.store),
        ),
        metrics = runtime.store,
        clock = runtime.clock,
    )

    init {
        viewModelScope.launch {
            settings = readSettings()
            applyPaths()
            profile = runtime.store.getProfile()
            refresh()
            loaded = true
        }
        viewModelScope.launch {
            runtime.thermal.changes().collect { snapshot ->
                thermal = snapshot
                if (snapshot.band == ThermalBand.CRITICAL || snapshot.band == ThermalBand.EMERGENCY) {
                    runtime.llm.unload()
                    runtime.embedding.unload()
                }
            }
        }
    }

    fun saveProfile(
        name: String,
        nativeLanguage: String,
        goals: List<LearningGoal>,
        minutes: Int,
        selfCefr: CefrLevel?,
    ) {
        viewModelScope.launch {
            val now = runtime.clock.nowMillis()
            val existing = runtime.store.getProfile()
            val saved = SaveLearnerProfile(runtime.store).invoke(
                LearnerProfile(
                    externalId = existing?.externalId ?: Ids.newId(),
                    displayName = name.trim(),
                    nativeLanguage = nativeLanguage.trim().ifBlank { "vi" },
                    goals = goals,
                    dailyMinutes = minutes,
                    selfReportedCefr = selfCefr,
                    createdAtMillis = existing?.createdAtMillis ?: now,
                    updatedAtMillis = now,
                ),
            )
            if (selfCefr != null) {
                runtime.store.saveEstimate(
                    CefrEstimate(
                        externalId = Ids.newId(),
                        assessedAtMillis = now,
                        source = CefrEstimate.SELF_REPORT,
                        overall = selfCefr,
                        reading = selfCefr,
                        writing = selfCefr,
                        listening = selfCefr,
                        speaking = selfCefr,
                        note = copy.cefrDisclaimer,
                    ),
                )
            }
            profile = saved
            refresh()
        }
    }

    fun confirmPlacement(level: CefrLevel, confirmed: Boolean) {
        viewModelScope.launch {
            RecordPlacement(runtime.store, runtime.clock).invoke(level, confirmed)
            refresh()
        }
    }

    fun grade(item: SrsItem, rating: FsrsRating) {
        viewModelScope.launch {
            GradeSrsItem(runtime.store, FsrsScheduler()).invoke(item.externalId, rating, runtime.clock.nowMillis())
            refresh()
        }
    }

    fun send(text: String, mode: ConversationMode) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val selection = selection()
            if (selection.kind == RuntimeKind.CLOUD && !hasCloudKey()) {
                banner = copy.keyMissing
                return@launch
            }
            banner = if (selection.kind == RuntimeKind.CLOUD) copy.cloudLeavesDevice else ""
            fixes = emptyList()
            val id = conversation(mode)
            val modelReady = File(settings.chatPath).isFile
            idle?.cancel()
            SendUtterance(runtime.store, orchestrator, runtime.clock).invoke(
                conversationId = id,
                text = trimmed,
                mode = mode,
                runtime = selection,
                modelReady = modelReady,
                embeddingReady = runtime.embedding.ready &&
                    (selection.kind == RuntimeKind.LOCAL || selection.sendPersonalContext),
                speculativeRequested = settings.speculative,
                modelId = if (selection.kind == RuntimeKind.CLOUD) selection.cloudModelId else ModelCatalog.chat.id,
                availRamBytes = runtime.availRamBytes(),
            ).collect { event ->
                when (event) {
                    is TutorEvent.TextDelta -> transcript += event.text
                    is TutorEvent.CorrectionReady -> fixes = event.fixes
                    TutorEvent.Throttled -> banner = copy.thermalStopped
                    TutorEvent.NeedModel -> banner = copy.needModel
                    is TutorEvent.Failed -> banner = event.reason.name
                    is TutorEvent.ToolRejected -> banner = event.name
                    TutorEvent.Completed -> refresh()
                }
            }
            idle = viewModelScope.launch {
                delay(Limits.CHAT_IDLE_UNLOAD_MILLIS)
                runtime.llm.unload()
                runtime.embedding.unload()
            }
        }
    }

    fun saveFix(fix: FixLine, kind: MistakeKind) {
        viewModelScope.launch {
            val id = conversationId ?: return@launch
            SaveFix(runtime.store, runtime.embedding, runtime.store, runtime.clock)
                .invoke(fix, id, Ids.newId(), kind)
            refresh()
        }
    }

    fun saveSelection(text: String, sourceId: String) {
        viewModelScope.launch {
            SaveSelection(runtime.store, runtime.embedding, runtime.store, runtime.clock).invoke(text, sourceId)
            banner = if (runtime.embedding.ready) copy.save else copy.needModel
        }
    }

    fun draft(outline: String, level: CefrLevel) {
        viewModelScope.launch {
            val selection = selection()
            if (selection.kind == RuntimeKind.CLOUD && !hasCloudKey()) {
                banner = copy.keyMissing
                return@launch
            }
            if (selection.kind == RuntimeKind.LOCAL && !File(settings.chatPath).isFile) {
                banner = copy.needModel
                return@launch
            }
            runtime.llm.prepare(selection)
            val item = DraftLearningContent(runtime.llm, runtime.store).invoke(outline, level, Ids.newId())
            banner = if (item == null) copy.needModel else item.title
            refresh()
        }
    }

    fun speak(text: String) {
        viewModelScope.launch {
            val result = runtime.tts.speak(text, "en-US")
            voiceNote = if (result == SpeakResult.VOICE_MISSING) copy.voiceMissing else ""
        }
    }

    fun startRecording() {
        recording = true
        feedback = null
        runtime.speech.start()
    }

    fun stopRecording(target: String) {
        recording = false
        feedback = runtime.speech.stop(target)
    }

    fun updateSettings(next: DeviceSettings) {
        settings = next
        applyPaths()
        viewModelScope.launch {
            writeSettings(next)
            refresh()
        }
    }

    fun saveKey(value: String) {
        viewModelScope.launch {
            val name = if (settings.vendor == CloudVendor.GEMINI) "cloud.gemini" else "cloud.openai"
            if (value.isBlank()) runtime.secrets.delete(name) else runtime.secrets.put(name, value.trim())
            keySaved = !value.isBlank()
            refresh()
        }
    }

    fun saveHuggingFaceToken(value: String) {
        viewModelScope.launch {
            val trimmed = value.trim()
            if (trimmed.isBlank()) runtime.secrets.delete(HuggingFaceSecret.NAME) else runtime.secrets.put(HuggingFaceSecret.NAME, trimmed)
            huggingfaceSaved = trimmed.isNotBlank()
        }
    }

    fun downloadModel(spec: ModelSpec) {
        if (!ModelCatalog.downloadAllowed(spec) || downloads[spec.id]?.running == true) return
        viewModelScope.launch {
            downloads = downloads + (spec.id to DownloadRow(true, 0, spec.bytes ?: 0L, null))
            val outcome = HuggingFaceDownloader(
                transport = runtime.downloads,
                blobs = runtime.blobs,
                token = { runtime.secrets.get(HuggingFaceSecret.NAME) },
                onProgress = { done, total ->
                    downloads = downloads + (spec.id to DownloadRow(true, done, total, null))
                },
            ).download(spec)
            val path = outcome.localPath
            if (outcome.state == InstallState.READY && !path.isNullOrBlank()) {
                if (spec.role == ModelRole.CHAT) settings.chatPath = path
                if (spec.role == ModelRole.EMBEDDING) settings.embedPath = path
                updateSettings(settings)
            }
            downloads = downloads + (
                spec.id to DownloadRow(
                    running = false,
                    bytes = outcome.bytesDownloaded,
                    total = spec.bytes ?: outcome.bytesDownloaded,
                    error = if (outcome.state == InstallState.READY) null else outcome.lastError,
                )
                )
            refresh()
        }
    }

    private suspend fun refresh() {
        val now = runtime.clock.nowMillis()
        val modelReady = File(settings.chatPath).isFile
        path = BuildDailyPath(runtime.store).invoke(now, modelReady && !settings.preferCloud)
        due = GetDueReviews(runtime.store).invoke(now, 20)
        library = runtime.store.library()
        dashboard = LoadDashboard(runtime.store, runtime.clock).invoke(zoneOffset())
        chip = OfflineIndicator.chip(
            kind = if (settings.preferCloud) RuntimeKind.CLOUD else RuntimeKind.LOCAL,
            modelReady = modelReady,
            downloading = downloads.values.any { it.running },
            cloudSelected = settings.preferCloud,
        )
        keySaved = hasCloudKey()
        huggingfaceSaved = !runtime.secrets.get(HuggingFaceSecret.NAME).isNullOrBlank()
    }

    private suspend fun conversation(mode: ConversationMode): String {
        val existing = conversationId
        if (existing != null) return existing
        val id = Ids.newId()
        runtime.store.saveConversation(
            ConversationRecord(id, mode, null, runtime.clock.nowMillis(), null),
        )
        conversationId = id
        return id
    }

    private suspend fun selection(): RuntimeSelection {
        val bytes = ModelCatalog.chat.bytes
        val ready = File(settings.chatPath).isFile
        val decision = RuntimePlanner.decide(
            preferCloud = settings.preferCloud,
            hasCloudKey = hasCloudKey(),
            availMem = runtime.availRamBytes(),
            modelBytes = bytes,
            modelReady = ready,
            gpuFailed = settings.gpuFailed || settings.backend == LocalBackend.CPU,
        )
        val kind = when {
            settings.preferCloud && hasCloudKey() -> RuntimeKind.CLOUD
            decision.rejectedByRam -> RuntimeKind.LOCAL
            decision.kind == RuntimeKind.LOCAL -> RuntimeKind.LOCAL
            else -> RuntimeKind.LOCAL
        }
        if (decision.rejectedByRam) banner = copy.needModel
        return RuntimeSelection(
            kind = kind,
            backend = settings.backend,
            cloudVendor = settings.vendor,
            cloudModelId = settings.cloudModelId,
            sendPersonalContext = settings.sendPersonalContext,
        )
    }

    private suspend fun hasCloudKey(): Boolean {
        val name = if (settings.vendor == CloudVendor.GEMINI) "cloud.gemini" else "cloud.openai"
        return !runtime.secrets.get(name).isNullOrBlank()
    }

    private fun applyPaths() {
        runtime.paths.chat = settings.chatPath.ifBlank { null }
        runtime.paths.embed = settings.embedPath.ifBlank { null }
    }

    private fun readSettings(): DeviceSettings {
        val file = runtime.settingsFile
        if (!file.exists()) return DeviceSettings()
        val map = file.readLines().mapNotNull { line ->
            val cut = line.indexOf('=')
            if (cut <= 0) null else line.substring(0, cut) to line.substring(cut + 1)
        }.toMap()
        return DeviceSettings().apply {
            backend = enumOr(map["backend"], LocalBackend.CPU)
            preferCloud = map["preferCloud"] == "true"
            vendor = enumOr(map["vendor"], CloudVendor.GEMINI)
            cloudModelId = map["cloudModelId"].orEmpty()
            sendPersonalContext = map["sendPersonalContext"] == "true"
            speculative = map["speculative"] == "true"
            chatPath = map["chatPath"].orEmpty()
            embedPath = map["embedPath"].orEmpty()
            gpuFailed = map["gpuFailed"] == "true"
        }
    }

    private fun writeSettings(value: DeviceSettings) {
        runtime.settingsFile.parentFile?.mkdirs()
        runtime.settingsFile.writeText(
            listOf(
                "backend=${value.backend.name}",
                "preferCloud=${value.preferCloud}",
                "vendor=${value.vendor.name}",
                "cloudModelId=${value.cloudModelId}",
                "sendPersonalContext=${value.sendPersonalContext}",
                "speculative=${value.speculative}",
                "chatPath=${value.chatPath}",
                "embedPath=${value.embedPath}",
                "gpuFailed=${value.gpuFailed}",
            ).joinToString("\n"),
        )
    }

    private fun zoneOffset(): Long = TimeZone.getDefault().getOffset(runtime.clock.nowMillis()).toLong()

    private inline fun <reified T : Enum<T>> enumOr(raw: String?, fallback: T): T {
        return enumValues<T>().firstOrNull { it.name == raw } ?: fallback
    }
}
