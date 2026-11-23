package com.personalsecretary.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecretaryDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE transactions (
                id TEXT PRIMARY KEY,
                account_type TEXT NOT NULL,
                date_iso TEXT NOT NULL,
                raw_date TEXT NOT NULL,
                description TEXT NOT NULL,
                normalized_description TEXT NOT NULL,
                debit REAL NOT NULL,
                credit REAL NOT NULL,
                balance REAL NOT NULL,
                category TEXT NOT NULL,
                auto_categorized INTEGER NOT NULL,
                is_balance_anomaly INTEGER NOT NULL,
                balance_diff REAL NOT NULL,
                sort_order INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_tx_account_date ON transactions(account_type, date_iso)")

        db.execSQL(
            """
            CREATE TABLE daily_steps (
                date_iso TEXT PRIMARY KEY,
                steps INTEGER NOT NULL,
                source TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE mental_checkins (
                id TEXT PRIMARY KEY,
                timestamp_millis INTEGER NOT NULL,
                date_iso TEXT NOT NULL,
                mood INTEGER NOT NULL,
                energy INTEGER NOT NULL,
                stress INTEGER NOT NULL,
                sleep_hours REAL NOT NULL,
                tags_json TEXT NOT NULL,
                note TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_checkins_ts ON mental_checkins(timestamp_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id TEXT PRIMARY KEY,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                engine_tag TEXT NOT NULL,
                tokens_per_sec REAL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_chat_ts ON chat_messages(timestamp_millis ASC)")

        db.execSQL(
            """
            CREATE TABLE app_kv (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive future migrations go here.
    }

    suspend fun saveProfile(profile: SecretaryProfile) = withContext(Dispatchers.IO) {
        putKv(KEY_PROFILE, json.encodeToString(profile))
    }

    suspend fun loadProfile(): SecretaryProfile = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_PROFILE) ?: return@withContext SecretaryProfile()
        runCatching { json.decodeFromString<SecretaryProfile>(raw) }.getOrDefault(SecretaryProfile())
    }

    suspend fun saveVideoSlotPath(slot: SecretaryVideoSlot, path: String?) = withContext(Dispatchers.IO) {
        val current = loadVideoSlots().toMutableMap()
        if (path.isNullOrBlank()) current.remove(slot.slotKey) else current[slot.slotKey] = path
        putKv(KEY_VIDEO_SLOTS, json.encodeToString(current))
    }

    suspend fun loadVideoSlots(): Map<String, String> = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_VIDEO_SLOTS) ?: return@withContext emptyMap()
        runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun saveLatestBriefing(text: String) = withContext(Dispatchers.IO) {
        putKv(KEY_LATEST_BRIEFING, text)
    }

    suspend fun loadLatestBriefing(): String = withContext(Dispatchers.IO) {
        getKv(KEY_LATEST_BRIEFING) ?: ""
    }

    suspend fun saveFinancialBundle(
        transactions: List<TransactionRecord>,
        ssf: SsfSummary,
        earnings: List<EarningEntry>,
        abbreviations: List<AbbreviationRule>,
        hygiene: FinancialHygieneReport
    ) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            transactions.forEachIndexed { index, tx ->
                val cv = ContentValues().apply {
                    put("id", tx.id)
                    put("account_type", tx.accountType.name)
                    put("date_iso", tx.dateIso)
                    put("raw_date", tx.rawDate)
                    put("description", tx.description)
                    put("normalized_description", tx.normalizedDescription)
                    put("debit", tx.debit)
                    put("credit", tx.credit)
                    put("balance", tx.balance)
                    put("category", tx.category)
                    put("auto_categorized", if (tx.autoCategorized) 1 else 0)
                    put("is_balance_anomaly", if (tx.isBalanceAnomaly) 1 else 0)
                    put("balance_diff", tx.balanceDiff)
                    put("sort_order", index)
                }
                db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            putKvTx(db, KEY_SSF, json.encodeToString(ssf))
            putKvTx(db, KEY_EARNINGS, json.encodeToString(earnings))
            putKvTx(db, KEY_ABBREV, json.encodeToString(abbreviations))
            putKvTx(db, KEY_HYGIENE, json.encodeToString(hygiene))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun appendSingleTransaction(tx: TransactionRecord) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val nextSort = db.rawQuery("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM transactions", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val cv = ContentValues().apply {
            put("id", tx.id)
            put("account_type", tx.accountType.name)
            put("date_iso", tx.dateIso)
            put("raw_date", tx.rawDate)
            put("description", tx.description)
            put("normalized_description", tx.normalizedDescription)
            put("debit", tx.debit)
            put("credit", tx.credit)
            put("balance", tx.balance)
            put("category", tx.category)
            put("auto_categorized", if (tx.autoCategorized) 1 else 0)
            put("is_balance_anomaly", if (tx.isBalanceAnomaly) 1 else 0)
            put("balance_diff", tx.balanceDiff)
            put("sort_order", nextSort)
        }
        db.insertWithOnConflict("transactions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun loadTransactions(): List<TransactionRecord> = withContext(Dispatchers.IO) {
        val out = mutableListOf<TransactionRecord>()
        readableDatabase.rawQuery(
            "SELECT id, account_type, date_iso, raw_date, description, normalized_description, debit, credit, balance, category, auto_categorized, is_balance_anomaly, balance_diff FROM transactions ORDER BY sort_order ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += TransactionRecord(
                    id = c.getString(0),
                    accountType = runCatching { AccountType.valueOf(c.getString(1)) }.getOrDefault(AccountType.DEBIT),
                    dateIso = c.getString(2),
                    rawDate = c.getString(3),
                    description = c.getString(4),
                    normalizedDescription = c.getString(5),
                    debit = c.getDouble(6),
                    credit = c.getDouble(7),
                    balance = c.getDouble(8),
                    category = c.getString(9),
                    autoCategorized = c.getInt(10) == 1,
                    isBalanceAnomaly = c.getInt(11) == 1,
                    balanceDiff = c.getDouble(12)
                )
            }
        }
        out
    }

    suspend fun loadSsfSummary(): SsfSummary = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_SSF) ?: return@withContext SsfSummary()
        runCatching { json.decodeFromString<SsfSummary>(raw) }.getOrDefault(SsfSummary())
    }

    suspend fun loadEarnings(): List<EarningEntry> = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_EARNINGS) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<EarningEntry>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun loadAbbreviations(): List<AbbreviationRule> = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_ABBREV) ?: return@withContext emptyList()
        runCatching { json.decodeFromString<List<AbbreviationRule>>(raw) }.getOrDefault(emptyList())
    }

    suspend fun loadHygieneReport(): FinancialHygieneReport = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_HYGIENE) ?: return@withContext FinancialHygieneReport()
        runCatching { json.decodeFromString<FinancialHygieneReport>(raw) }.getOrDefault(FinancialHygieneReport())
    }

    suspend fun upsertStepRecords(records: List<DailyStepRecord>) = withContext(Dispatchers.IO) {
        if (records.isEmpty()) return@withContext
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { rec ->
                val cv = ContentValues().apply {
                    put("date_iso", rec.dateIso)
                    put("steps", rec.steps)
                    put("source", rec.source.name)
                    put("updated_at", rec.updatedAtMillis)
                }
                db.insertWithOnConflict("daily_steps", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun loadStepRecords(limitDays: Int = 60): List<DailyStepRecord> = withContext(Dispatchers.IO) {
        val out = mutableListOf<DailyStepRecord>()
        readableDatabase.rawQuery(
            "SELECT date_iso, steps, source, updated_at FROM daily_steps ORDER BY date_iso DESC LIMIT ?",
            arrayOf(limitDays.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += DailyStepRecord(
                    dateIso = c.getString(0),
                    steps = c.getLong(1),
                    source = runCatching { StepSource.valueOf(c.getString(2)) }.getOrDefault(StepSource.HARDWARE_SENSOR),
                    updatedAtMillis = c.getLong(3)
                )
            }
        }
        out.reversed()
    }

    suspend fun saveCheckIn(checkIn: MentalCheckIn) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", checkIn.id)
            put("timestamp_millis", checkIn.timestampMillis)
            put("date_iso", checkIn.dateIso)
            put("mood", checkIn.mood)
            put("energy", checkIn.energy)
            put("stress", checkIn.stress)
            put("sleep_hours", checkIn.sleepHours)
            put("tags_json", json.encodeToString(checkIn.tags))
            put("note", checkIn.note)
        }
        writableDatabase.insertWithOnConflict("mental_checkins", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun deleteCheckIn(id: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("mental_checkins", "id = ?", arrayOf(id))
    }

    suspend fun loadCheckIns(limit: Int = 60): List<MentalCheckIn> = withContext(Dispatchers.IO) {
        val out = mutableListOf<MentalCheckIn>()
        readableDatabase.rawQuery(
            "SELECT id, timestamp_millis, date_iso, mood, energy, stress, sleep_hours, tags_json, note FROM mental_checkins ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val tags = runCatching { json.decodeFromString<List<String>>(c.getString(7)) }.getOrDefault(emptyList())
                out += MentalCheckIn(
                    id = c.getString(0),
                    timestampMillis = c.getLong(1),
                    dateIso = c.getString(2),
                    mood = c.getInt(3),
                    energy = c.getInt(4),
                    stress = c.getInt(5),
                    sleepHours = c.getFloat(6),
                    tags = tags,
                    note = c.getString(8)
                )
            }
        }
        out
    }

    suspend fun saveChatMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", message.id)
            put("role", message.role.name)
            put("content", message.content)
            put("timestamp_millis", message.timestampMillis)
            put("engine_tag", message.engineTag)
            if (message.tokensPerSec != null) put("tokens_per_sec", message.tokensPerSec) else putNull("tokens_per_sec")
        }
        writableDatabase.insertWithOnConflict("chat_messages", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun loadChatMessages(limit: Int = 80): List<ChatMessage> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, role, content, timestamp_millis, engine_tag, tokens_per_sec FROM chat_messages ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += ChatMessage(
                    id = c.getString(0),
                    role = runCatching { ChatRole.valueOf(c.getString(1)) }.getOrDefault(ChatRole.ASSISTANT),
                    content = c.getString(2),
                    timestampMillis = c.getLong(3),
                    engineTag = c.getString(4),
                    tokensPerSec = if (c.isNull(5)) null else c.getFloat(5)
                )
            }
        }
        out.reversed()
    }

    suspend fun clearChatHistory() = withContext(Dispatchers.IO) {
        writableDatabase.delete("chat_messages", null, null)
    }

    suspend fun wipeAllPersonalData() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("transactions", null, null)
            db.delete("daily_steps", null, null)
            db.delete("mental_checkins", null, null)
            db.delete("chat_messages", null, null)
            db.delete("app_kv", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun getStringValue(key: String): String? = withContext(Dispatchers.IO) { getKv(key) }
    suspend fun putStringValue(key: String, value: String) = withContext(Dispatchers.IO) { putKv(key, value) }

    private fun getKv(key: String): String? {
        return readableDatabase.rawQuery("SELECT v FROM app_kv WHERE k = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun putKv(key: String, value: String) {
        putKvTx(writableDatabase, key, value)
    }

    private fun putKvTx(db: SQLiteDatabase, key: String, value: String) {
        val cv = ContentValues().apply {
            put("k", key)
            put("v", value)
        }
        db.insertWithOnConflict("app_kv", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        private const val DB_NAME = "personal_secretary.db"
        private const val DB_VERSION = 1
        private const val KEY_PROFILE = "profile_v1"
        private const val KEY_SSF = "ssf_v1"
        private const val KEY_EARNINGS = "earnings_v1"
        private const val KEY_ABBREV = "abbrev_v1"
        private const val KEY_HYGIENE = "hygiene_v1"
        private const val KEY_VIDEO_SLOTS = "video_slots_v1"
        private const val KEY_LATEST_BRIEFING = "latest_briefing_v1"
    }
}
