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

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.io.File
import java.security.SecureRandom
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SwitchingLlm(
    private val local: LiteRtLlmProvider,
    private val secrets: SecretStore,
    private val client: HttpClient,
) : LlmProvider {
    private val gate = Mutex()
    private var active: LlmProvider = local

    override val kind: RuntimeKind
        get() = active.kind

    override suspend fun prepare(selection: RuntimeSelection) {
        gate.withLock {
            val vendor = selection.cloudVendor
            active = if (selection.kind == RuntimeKind.CLOUD && vendor != null) {
                CloudLlmProvider(client, secrets, vendor, selection.cloudModelId, ::safeCloudLog)
            } else {
                local
            }
            active.prepare(selection)
        }
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = flow {
        val provider = gate.withLock { active }
        provider.stream(request).collect { emit(it) }
    }

    override suspend fun sendToolResults(sessionId: String, results: List<ToolResult>) {
        gate.withLock { active }.sendToolResults(sessionId, results)
    }

    override suspend fun cancel(sessionId: String) {
        gate.withLock { active }.cancel(sessionId)
    }

    override suspend fun unload() {
        local.unload()
        gate.withLock { active }.unload()
    }
}

class AndroidRuntime(
    val context: Context,
    val store: SqlCipherLearningStore,
    val secrets: KeystoreSecretStore,
    val paths: ModelPaths,
    val llm: SwitchingLlm,
    val embedding: LiteRtEmbeddingProvider,
    val thermal: AndroidThermalMonitor,
    val tts: AndroidTts,
    val speech: AndroidSpeakSession,
    val blobs: FileModelBlobs,
    val downloads: DownloadTransport,
    val settingsFile: File,
    val clock: AppClock,
) {
    fun availRamBytes(): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem
    }
}

private fun safeCloudLog(line: String) {
    if (!line.startsWith("cloud vendor=")) return
    if ("Bearer" in line || "sk-" in line) return
    Log.i("AetherCloud", line)
}

suspend fun openAndroidRuntime(context: Context): AndroidRuntime {
    val app = context.applicationContext
    val root = app.noBackupFilesDir
    val secrets = KeystoreSecretStore(root)
    val passphrase = secrets.get(DB_PASSPHRASE) ?: newPassphrase().also { secrets.put(DB_PASSPHRASE, it) }
    val store = SqlCipherLearningStore(File(root, "aether.db"), passphrase)
    if (store.library().isEmpty()) {
        val json = app.assets.open("seed.json").bufferedReader().use { it.readText() }
        ContentCatalog.parseItems(json).forEach { item -> store.saveLibrary(item) }
    }
    val paths = ModelPaths()
    val cache = File(root, "litert-cache").apply { mkdirs() }
    val local = LiteRtLlmProvider(paths, cache)
    val embedding = LiteRtEmbeddingProvider(paths, cache)
    val client = HttpClient(OkHttp) {
        expectSuccess = false
    }
    val downloads = HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
    }
    return AndroidRuntime(
        context = app,
        store = store,
        secrets = secrets,
        paths = paths,
        llm = SwitchingLlm(local, secrets, client),
        embedding = embedding,
        thermal = AndroidThermalMonitor(app),
        tts = AndroidTts(app),
        speech = AndroidSpeakSession(app),
        blobs = FileModelBlobs(File(root, "models")),
        downloads = KtorDownloadTransport(downloads),
        settingsFile = File(root, "settings.txt"),
        clock = AppClock { System.currentTimeMillis() },
    )
}

private fun newPassphrase(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { byte -> "%02x".format(byte) }
}

private const val DB_PASSPHRASE: String = "db.passphrase"
