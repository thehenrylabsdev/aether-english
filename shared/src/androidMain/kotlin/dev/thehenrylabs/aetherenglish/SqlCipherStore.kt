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

import android.content.ContentValues
import android.database.Cursor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.zetetic.database.sqlcipher.SQLiteDatabase

class SqlCipherLearningStore(file: File, passphrase: String) : LearningStore, VectorStore {
    private val gate = Mutex()
    private val db: SQLiteDatabase

    init {
        System.loadLibrary("sqlcipher")
        file.parentFile?.mkdirs()
        db = SQLiteDatabase.openOrCreateDatabase(file, passphrase, null, null)
        SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { statement ->
            db.execSQL(statement)
        }
    }

    override suspend fun getProfile(): LearnerProfile? = gate.withLock {
        query("SELECT * FROM profile LIMIT 1", emptyArray()).firstOrNull()?.let(::profile)
    }

    override suspend fun saveProfile(profile: LearnerProfile) = gate.withLock {
        val existing = query("SELECT external_id FROM profile LIMIT 1", emptyArray()).firstOrNull()
        if (existing != null && existing.string("external_id") != profile.externalId) {
            error("profile already exists")
        }
        val minutes = profile.dailyMinutes.coerceIn(Limits.DAILY_MIN_MINUTES, Limits.DAILY_MAX_MINUTES)
        val values = ContentValues()
        values.put("display_name", profile.displayName)
        values.put("native_language", profile.nativeLanguage)
        values.put("goals", profile.goals.joinToString(",") { it.name })
        values.put("daily_minutes", minutes)
        values.put("self_cefr", profile.selfReportedCefr?.name)
        values.put("created_at", profile.createdAtMillis)
        values.put("updated_at", profile.updatedAtMillis)
        replace("profile", "external_id", profile.externalId, values)
    }

    override suspend fun estimates(): List<CefrEstimate> = gate.withLock {
        query("SELECT * FROM estimate ORDER BY assessed_at", emptyArray()).map(::estimate)
    }

    override suspend fun saveEstimate(estimate: CefrEstimate) = gate.withLock {
        val values = ContentValues()
        values.put("assessed_at", estimate.assessedAtMillis)
        values.put("source", estimate.source)
        values.put("overall", estimate.overall.name)
        values.put("reading", estimate.reading.name)
        values.put("writing", estimate.writing.name)
        values.put("listening", estimate.listening.name)
        values.put("speaking", estimate.speaking.name)
        values.put("note", estimate.note)
        replace("estimate", "external_id", estimate.externalId, values)
    }

    override suspend fun srsItems(): List<SrsItem> = gate.withLock {
        query("SELECT * FROM srs_item", emptyArray()).map(::srs)
    }

    override suspend fun saveSrs(item: SrsItem) = gate.withLock {
        val values = ContentValues()
        values.put("kind", item.kind.name)
        values.put("front", item.front)
        values.put("back", item.back)
        values.put("example", item.example)
        values.put("cefr", item.cefr.name)
        values.put("stability", item.stability)
        values.put("difficulty", item.difficulty)
        values.put("scheduled_days", item.scheduledDays)
        values.put("reps", item.reps)
        values.put("lapses", item.lapses)
        values.put("card_state", item.cardState.name)
        values.put("due_at", item.dueAtMillis)
        if (item.lastReviewedAtMillis == null) values.putNull("last_reviewed_at") else values.put("last_reviewed_at", item.lastReviewedAtMillis)
        values.put("source_mistake_id", item.sourceMistakeId)
        replace("srs_item", "external_id", item.externalId, values)
    }

    override suspend fun conversations(): List<ConversationRecord> = gate.withLock {
        query("SELECT * FROM conversation", emptyArray()).map(::conversation)
    }

    override suspend fun saveConversation(record: ConversationRecord) = gate.withLock {
        val values = ContentValues()
        values.put("mode", record.mode.name)
        values.put("scenario_id", record.scenarioId)
        values.put("started_at", record.startedAtMillis)
        if (record.endedAtMillis == null) values.putNull("ended_at") else values.put("ended_at", record.endedAtMillis)
        replace("conversation", "external_id", record.externalId, values)
    }

    override suspend fun messages(conversationId: String): List<StoredMessage> = gate.withLock {
        query(
            "SELECT * FROM message WHERE conversation_id = ? ORDER BY created_at",
            arrayOf(conversationId),
        ).map(::message)
    }

    override suspend fun saveMessage(message: StoredMessage) = gate.withLock {
        val values = ContentValues()
        values.put("conversation_id", message.conversationId)
        values.put("role", message.role.name)
        values.put("text", message.text)
        values.put("created_at", message.createdAtMillis)
        replace("message", "external_id", message.externalId, values)
    }

    override suspend fun mistakes(): List<Mistake> = gate.withLock {
        query("SELECT * FROM mistake", emptyArray()).map(::mistake)
    }

    override suspend fun saveMistake(mistake: Mistake) = gate.withLock {
        val values = ContentValues()
        values.put("conversation_id", mistake.conversationId)
        values.put("message_id", mistake.messageId)
        values.put("kind", mistake.kind.name)
        values.put("original", mistake.original)
        values.put("corrected", mistake.corrected)
        values.put("explanation", mistake.explanation)
        values.put("rule_id", mistake.ruleId)
        values.put("created_at", mistake.createdAtMillis)
        replace("mistake", "external_id", mistake.externalId, values)
    }

    override suspend fun library(): List<LibraryItem> = gate.withLock {
        query("SELECT * FROM library_item", emptyArray()).map(::library)
    }

    override suspend fun saveLibrary(item: LibraryItem) = gate.withLock {
        require(item.license.isNotBlank()) { "license required" }
        val values = ContentValues()
        values.put("kind", item.kind.name)
        values.put("cefr", item.cefr.name)
        values.put("title", item.title)
        values.put("body", item.body)
        values.put("license", item.license)
        values.put("attribution", item.attribution)
        values.put("answer", item.answer)
        replace("library_item", "external_id", item.externalId, values)
    }

    override suspend fun metrics(): List<MetricsSample> = gate.withLock {
        query("SELECT * FROM metric_sample", emptyArray()).map(::metric)
    }

    override suspend fun saveMetrics(sample: MetricsSample) = gate.withLock {
        val values = ContentValues()
        values.put("captured_at", sample.capturedAtMillis)
        values.put("conversation_id", sample.conversationId)
        values.put("model_id", sample.modelId)
        values.put("backend", sample.backend)
        values.put("thermal_band", sample.thermalBand.name)
        if (sample.availRamBytes == null) values.putNull("avail_ram") else values.put("avail_ram", sample.availRamBytes)
        if (sample.ttftMillis == null) values.putNull("ttft_millis") else values.put("ttft_millis", sample.ttftMillis)
        values.put("output_chars", sample.outputChars)
        if (sample.outputTokens == null) values.putNull("output_tokens") else values.put("output_tokens", sample.outputTokens)
        values.put("wall_millis", sample.wallMillis)
        values.put("throttle_action", sample.throttleAction)
        db.insert("metric_sample", null, values)
        Unit
    }

    override suspend fun modelInstalls(): List<ModelInstall> = gate.withLock {
        query("SELECT * FROM model_install", emptyArray()).map(::install)
    }

    override suspend fun saveModelInstall(install: ModelInstall) = gate.withLock {
        val values = ContentValues()
        values.put("state", install.state.name)
        values.put("bytes_downloaded", install.bytesDownloaded)
        values.put("local_path", install.localPath)
        values.put("verified_sha256", install.verifiedSha256)
        values.put("last_error", install.lastError)
        replace("model_install", "model_id", install.modelId, values)
    }

    override suspend fun upsert(document: VectorDocument) = gate.withLock {
        CosineIndex.requireDimension(document.embedding)
        val values = ContentValues()
        values.put("source", document.source.name)
        values.put("source_id", document.sourceId)
        values.put("text", document.text)
        values.put("embedding", floatsToBytes(document.embedding))
        values.put("model_id", document.embeddingModelId)
        values.put("created_at", document.createdAtMillis)
        replace("vector_doc", "external_id", document.externalId, values)
    }

    override suspend fun search(query: FloatArray, limit: Int, filter: VectorFilter): List<VectorHit> = gate.withLock {
        val documents = query("SELECT * FROM vector_doc", emptyArray()).map(::vector)
        CosineIndex.rank(query, documents, limit, filter)
    }

    override suspend fun deleteBySource(source: VectorSource, sourceId: String) = gate.withLock {
        db.delete("vector_doc", "source = ? AND source_id = ?", arrayOf(source.name, sourceId))
        Unit
    }

    override suspend fun count(): Int = gate.withLock {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM vector_doc", emptyArray())
        try {
            if (!cursor.moveToFirst()) 0 else cursor.getInt(0)
        } finally {
            cursor.close()
        }
    }

    private fun replace(table: String, keyColumn: String, key: String, values: ContentValues) {
        db.delete(table, "$keyColumn = ?", arrayOf(key))
        values.put(keyColumn, key)
        val row = db.insert(table, null, values)
        if (row < 0) error("insert failed")
    }

    private fun query(sql: String, args: Array<String>): List<Row> {
        val cursor = db.rawQuery(sql, args)
        try {
            val rows = mutableListOf<Row>()
            while (cursor.moveToNext()) rows += Row(cursor)
            return rows
        } finally {
            cursor.close()
        }
    }

    private fun profile(row: Row) = LearnerProfile(
        externalId = row.string("external_id"),
        displayName = row.string("display_name"),
        nativeLanguage = row.string("native_language"),
        goals = row.string("goals").split(",").filter { it.isNotBlank() }.map { LearningGoal.valueOf(it) },
        dailyMinutes = row.int("daily_minutes"),
        selfReportedCefr = row.optional("self_cefr")?.let { CefrLevel.valueOf(it) },
        createdAtMillis = row.long("created_at"),
        updatedAtMillis = row.long("updated_at"),
    )

    private fun estimate(row: Row) = CefrEstimate(
        externalId = row.string("external_id"),
        assessedAtMillis = row.long("assessed_at"),
        source = row.string("source"),
        overall = CefrLevel.valueOf(row.string("overall")),
        reading = CefrLevel.valueOf(row.string("reading")),
        writing = CefrLevel.valueOf(row.string("writing")),
        listening = CefrLevel.valueOf(row.string("listening")),
        speaking = CefrLevel.valueOf(row.string("speaking")),
        note = row.string("note"),
    )

    private fun srs(row: Row) = SrsItem(
        externalId = row.string("external_id"),
        kind = SrsKind.valueOf(row.string("kind")),
        front = row.string("front"),
        back = row.string("back"),
        example = row.string("example"),
        cefr = CefrLevel.valueOf(row.string("cefr")),
        stability = row.double("stability"),
        difficulty = row.double("difficulty"),
        scheduledDays = row.int("scheduled_days"),
        reps = row.int("reps"),
        lapses = row.int("lapses"),
        cardState = FsrsCardState.valueOf(row.string("card_state")),
        dueAtMillis = row.long("due_at"),
        lastReviewedAtMillis = row.optionalLong("last_reviewed_at"),
        sourceMistakeId = row.optional("source_mistake_id"),
    )

    private fun conversation(row: Row) = ConversationRecord(
        externalId = row.string("external_id"),
        mode = ConversationMode.valueOf(row.string("mode")),
        scenarioId = row.optional("scenario_id"),
        startedAtMillis = row.long("started_at"),
        endedAtMillis = row.optionalLong("ended_at"),
    )

    private fun message(row: Row) = StoredMessage(
        externalId = row.string("external_id"),
        conversationId = row.string("conversation_id"),
        role = AuthorRole.valueOf(row.string("role")),
        text = row.string("text"),
        createdAtMillis = row.long("created_at"),
    )

    private fun mistake(row: Row) = Mistake(
        externalId = row.string("external_id"),
        conversationId = row.string("conversation_id"),
        messageId = row.string("message_id"),
        kind = MistakeKind.valueOf(row.string("kind")),
        original = row.string("original"),
        corrected = row.string("corrected"),
        explanation = row.string("explanation"),
        ruleId = row.optional("rule_id"),
        createdAtMillis = row.long("created_at"),
    )

    private fun library(row: Row) = LibraryItem(
        externalId = row.string("external_id"),
        kind = LibraryKind.valueOf(row.string("kind")),
        cefr = CefrLevel.valueOf(row.string("cefr")),
        title = row.string("title"),
        body = row.string("body"),
        license = row.string("license"),
        attribution = row.string("attribution"),
        answer = row.optional("answer"),
    )

    private fun metric(row: Row) = MetricsSample(
        capturedAtMillis = row.long("captured_at"),
        conversationId = row.optional("conversation_id"),
        modelId = row.string("model_id"),
        backend = row.string("backend"),
        thermalBand = ThermalBand.valueOf(row.string("thermal_band")),
        availRamBytes = row.optionalLong("avail_ram"),
        ttftMillis = row.optionalLong("ttft_millis"),
        outputChars = row.int("output_chars"),
        outputTokens = row.optionalInt("output_tokens"),
        wallMillis = row.long("wall_millis"),
        throttleAction = row.string("throttle_action"),
    )

    private fun install(row: Row) = ModelInstall(
        modelId = row.string("model_id"),
        state = InstallState.valueOf(row.string("state")),
        bytesDownloaded = row.long("bytes_downloaded"),
        localPath = row.optional("local_path"),
        verifiedSha256 = row.optional("verified_sha256"),
        lastError = row.optional("last_error"),
    )

    private fun vector(row: Row) = VectorDocument(
        externalId = row.string("external_id"),
        source = VectorSource.valueOf(row.string("source")),
        sourceId = row.string("source_id"),
        text = row.string("text"),
        embedding = bytesToFloats(row.blob("embedding")),
        embeddingModelId = row.string("model_id"),
        createdAtMillis = row.long("created_at"),
    )

    private class Row(cursor: Cursor) {
        private val values = buildMap {
            for (index in 0 until cursor.columnCount) {
                val name = cursor.getColumnName(index)
                val type = cursor.getType(index)
                put(
                    name,
                    if (cursor.isNull(index) || type == Cursor.FIELD_TYPE_BLOB) {
                        null
                    } else {
                        cursor.getString(index)
                    },
                )
            }
        }
        private val blobs = buildMap {
            for (index in 0 until cursor.columnCount) {
                val name = cursor.getColumnName(index)
                if (!cursor.isNull(index) && cursor.getType(index) == Cursor.FIELD_TYPE_BLOB) {
                    put(name, cursor.getBlob(index))
                }
            }
        }

        fun string(name: String): String = values[name] ?: ""
        fun optional(name: String): String? = values[name]
        fun int(name: String): Int = values[name]?.toInt() ?: 0
        fun optionalInt(name: String): Int? = values[name]?.toInt()
        fun long(name: String): Long = values[name]?.toLong() ?: 0L
        fun optionalLong(name: String): Long? = values[name]?.toLong()
        fun double(name: String): Double = values[name]?.toDouble() ?: 0.0
        fun blob(name: String): ByteArray = blobs[name] ?: ByteArray(0)
    }

    private companion object {
        const val SCHEMA: String = """
            CREATE TABLE IF NOT EXISTS profile (
                external_id TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                native_language TEXT NOT NULL,
                goals TEXT NOT NULL,
                daily_minutes INTEGER NOT NULL,
                self_cefr TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS estimate (
                external_id TEXT PRIMARY KEY,
                assessed_at INTEGER NOT NULL,
                source TEXT NOT NULL,
                overall TEXT NOT NULL,
                reading TEXT NOT NULL,
                writing TEXT NOT NULL,
                listening TEXT NOT NULL,
                speaking TEXT NOT NULL,
                note TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS srs_item (
                external_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                front TEXT NOT NULL,
                back TEXT NOT NULL,
                example TEXT NOT NULL,
                cefr TEXT NOT NULL,
                stability REAL NOT NULL,
                difficulty REAL NOT NULL,
                scheduled_days INTEGER NOT NULL,
                reps INTEGER NOT NULL,
                lapses INTEGER NOT NULL,
                card_state TEXT NOT NULL,
                due_at INTEGER NOT NULL,
                last_reviewed_at INTEGER,
                source_mistake_id TEXT
            );
            CREATE TABLE IF NOT EXISTS conversation (
                external_id TEXT PRIMARY KEY,
                mode TEXT NOT NULL,
                scenario_id TEXT,
                started_at INTEGER NOT NULL,
                ended_at INTEGER
            );
            CREATE TABLE IF NOT EXISTS message (
                external_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS mistake (
                external_id TEXT PRIMARY KEY,
                conversation_id TEXT NOT NULL,
                message_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                original TEXT NOT NULL,
                corrected TEXT NOT NULL,
                explanation TEXT NOT NULL,
                rule_id TEXT,
                created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS library_item (
                external_id TEXT PRIMARY KEY,
                kind TEXT NOT NULL,
                cefr TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                license TEXT NOT NULL,
                attribution TEXT NOT NULL,
                answer TEXT
            );
            CREATE TABLE IF NOT EXISTS metric_sample (
                captured_at INTEGER NOT NULL,
                conversation_id TEXT,
                model_id TEXT NOT NULL,
                backend TEXT NOT NULL,
                thermal_band TEXT NOT NULL,
                avail_ram INTEGER,
                ttft_millis INTEGER,
                output_chars INTEGER NOT NULL,
                output_tokens INTEGER,
                wall_millis INTEGER NOT NULL,
                throttle_action TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS model_install (
                model_id TEXT PRIMARY KEY,
                state TEXT NOT NULL,
                bytes_downloaded INTEGER NOT NULL,
                local_path TEXT,
                verified_sha256 TEXT,
                last_error TEXT
            );
            CREATE TABLE IF NOT EXISTS vector_doc (
                external_id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                source_id TEXT NOT NULL,
                text TEXT NOT NULL,
                embedding BLOB NOT NULL,
                model_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            );
        """

        fun floatsToBytes(values: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            values.forEach { buffer.putFloat(it) }
            return buffer.array()
        }

        fun bytesToFloats(bytes: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }
        }
    }
}
