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

        createTasksTable(db)
        createEmailAlertsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTasksTable(db)
        }
        if (oldVersion < 3) {
            createEmailAlertsTable(db)
        }
    }

    private fun createEmailAlertsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS email_alerts (
                id TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                app_label TEXT NOT NULL,
                sender TEXT NOT NULL,
                subject TEXT NOT NULL,
                snippet TEXT NOT NULL,
                received_at_millis INTEGER NOT NULL,
                tag TEXT NOT NULL,
                is_urgent INTEGER NOT NULL,
                is_read INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_email_received ON email_alerts(received_at_millis DESC)")
    }

    private fun createTasksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS secretary_tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                notes TEXT NOT NULL,
                due_at_millis INTEGER,
                priority TEXT NOT NULL,
                category TEXT NOT NULL,
                recurrence TEXT NOT NULL,
                is_completed INTEGER NOT NULL,
                reminder_enabled INTEGER NOT NULL,
                created_at_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_tasks_due ON secretary_tasks(is_completed, due_at_millis)")
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

    suspend fun updateTransactionDetails(
        txId: String,
        newDescription: String,
        newCategory: String
    ) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("normalized_description", newDescription.trim())
            put("category", newCategory.trim().ifBlank { "Uncategorized" })
            put("auto_categorized", 0)
        }
        writableDatabase.update("transactions", cv, "id = ?", arrayOf(txId))
    }

    /**
     * Re-applies upgraded categorization and deduplication rules to existing rows on app upgrades
     * without overwriting user-edited descriptions or custom non-generic categories.
     */
    suspend fun reclassifyUncategorizedTransactions(): Int = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val updates = mutableListOf<Pair<String, String>>()
        val toDelete = mutableListOf<String>()
        val bestByDebitKey = HashMap<String, Triple<String, Int, String>>()

        db.rawQuery(
            "SELECT id, account_type, date_iso, description, normalized_description, debit, credit, balance, category, auto_categorized FROM transactions ORDER BY sort_order ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val acct = c.getString(1)
                val dateIso = c.getString(2)
                val desc = c.getString(3)
                val norm = c.getString(4).ifBlank { desc }
                val debit = c.getDouble(5)
                val credit = c.getDouble(6)
                val balance = c.getDouble(7)
                val cat = c.getString(8)

                if (acct == AccountType.DEBIT.name && dateIso != "0000-00-00") {
                    val key = "$dateIso|${Math.round(debit * 100.0)}|${Math.round(credit * 100.0)}|${Math.round(balance * 100.0)}"
                    val score = DayBookParserAndHygiene.rowRichnessScore(norm, cat)
                    val prev = bestByDebitKey[key]
                    if (prev != null) {
                        if (score > prev.second) {
                            toDelete += prev.first
                            bestByDebitKey[key] = Triple(id, score, cat)
                        } else {
                            toDelete += id
                            continue
                        }
                    } else {
                        bestByDebitKey[key] = Triple(id, score, cat)
                    }
                }

                val inferred = DayBookParserAndHygiene.inferCategory(norm, debit, credit)
                val isGeneric = cat.isBlank() ||
                    cat.equals("Uncategorized", ignoreCase = true) ||
                    cat.equals("Other", ignoreCase = true)
                when {
                    inferred.equals("Tobacco & Alcohol", ignoreCase = true) && !cat.equals("Tobacco & Alcohol", ignoreCase = true) ->
                        updates += id to "Tobacco & Alcohol"
                    isGeneric && inferred != cat ->
                        updates += id to inferred
                }
            }
        }
        if (updates.isEmpty() && toDelete.isEmpty()) return@withContext 0
        db.beginTransaction()
        try {
            toDelete.forEach { id ->
                db.delete("transactions", "id = ?", arrayOf(id))
            }
            updates.forEach { (id, category) ->
                if (id !in toDelete) {
                    val cv = ContentValues().apply {
                        put("category", category)
                        put("auto_categorized", 1)
                    }
                    db.update("transactions", cv, "id = ?", arrayOf(id))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        updates.size + toDelete.size
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

    suspend fun saveHourlySteps(map: Map<String, List<Long>>) = withContext(Dispatchers.IO) {
        putKv(KEY_HOURLY_STEPS, json.encodeToString(map))
    }

    suspend fun loadHourlySteps(): Map<String, List<Long>> = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_HOURLY_STEPS) ?: return@withContext emptyMap()
        runCatching { json.decodeFromString<Map<String, List<Long>>>(raw) }.getOrDefault(emptyMap())
    }

    suspend fun saveHealthVitals(vitals: HealthVitalsSummary) = withContext(Dispatchers.IO) {
        putKv(KEY_HEALTH_VITALS, json.encodeToString(vitals))
    }

    suspend fun loadHealthVitals(): HealthVitalsSummary = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_HEALTH_VITALS) ?: return@withContext HealthVitalsSummary()
        runCatching { json.decodeFromString<HealthVitalsSummary>(raw) }.getOrDefault(HealthVitalsSummary())
    }

    suspend fun saveMarketSnapshot(snapshot: NepalMarketSnapshot) = withContext(Dispatchers.IO) {
        putKv(KEY_MARKET_SNAPSHOT, json.encodeToString(snapshot))
    }

    suspend fun loadMarketSnapshot(): NepalMarketSnapshot = withContext(Dispatchers.IO) {
        val raw = getKv(KEY_MARKET_SNAPSHOT) ?: return@withContext NepalMarketSnapshot()
        runCatching { json.decodeFromString<NepalMarketSnapshot>(raw) }.getOrDefault(NepalMarketSnapshot())
    }

    suspend fun upsertTask(task: SecretaryTask) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply {
            put("id", task.id)
            put("title", task.title)
            put("notes", task.notes)
            if (task.dueAtMillis != null) put("due_at_millis", task.dueAtMillis) else putNull("due_at_millis")
            put("priority", task.priority.name)
            put("category", task.category.name)
            put("recurrence", task.recurrence.name)
            put("is_completed", if (task.isCompleted) 1 else 0)
            put("reminder_enabled", if (task.reminderEnabled) 1 else 0)
            put("created_at_millis", task.createdAtMillis)
        }
        writableDatabase.insertWithOnConflict("secretary_tasks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    suspend fun loadTasks(): List<SecretaryTask> = withContext(Dispatchers.IO) {
        val out = mutableListOf<SecretaryTask>()
        readableDatabase.rawQuery(
            """
            SELECT id, title, notes, due_at_millis, priority, category, recurrence, is_completed, reminder_enabled, created_at_millis
            FROM secretary_tasks
            ORDER BY is_completed ASC,
                     CASE WHEN due_at_millis IS NULL THEN 1 ELSE 0 END ASC,
                     due_at_millis ASC,
                     created_at_millis DESC
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += SecretaryTask(
                    id = c.getString(0),
                    title = c.getString(1),
                    notes = c.getString(2),
                    dueAtMillis = if (c.isNull(3)) null else c.getLong(3),
                    priority = runCatching { TaskPriority.valueOf(c.getString(4)) }.getOrDefault(TaskPriority.HIGH),
                    category = runCatching { TaskCategory.valueOf(c.getString(5)) }.getOrDefault(TaskCategory.WORK),
                    recurrence = runCatching { TaskRecurrence.valueOf(c.getString(6)) }.getOrDefault(TaskRecurrence.NONE),
                    isCompleted = c.getInt(7) == 1,
                    reminderEnabled = c.getInt(8) == 1,
                    createdAtMillis = c.getLong(9)
                )
            }
        }
        out
    }

    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("secretary_tasks", "id = ?", arrayOf(taskId))
    }

    suspend fun upsertEmailAlert(alert: EmailAlertItem) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("id", alert.id)
            put("package_name", alert.packageName)
            put("app_label", alert.appLabel)
            put("sender", alert.sender)
            put("subject", alert.subject)
            put("snippet", alert.snippet)
            put("received_at_millis", alert.receivedAtMillis)
            put("tag", alert.tag.name)
            put("is_urgent", if (alert.isUrgent) 1 else 0)
            put("is_read", if (alert.isRead) 1 else 0)
        }
        db.insertWithOnConflict("email_alerts", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        // Keep local inbox lean (latest 120 alerts)
        db.execSQL(
            """
            DELETE FROM email_alerts
            WHERE id NOT IN (
                SELECT id FROM email_alerts ORDER BY received_at_millis DESC LIMIT 120
            )
            """.trimIndent()
        )
    }

    suspend fun loadEmailAlerts(limit: Int = 100): List<EmailAlertItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<EmailAlertItem>()
        readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_label, sender, subject, snippet, received_at_millis, tag, is_urgent, is_read
            FROM email_alerts
            ORDER BY received_at_millis DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out += EmailAlertItem(
                    id = c.getString(0),
                    packageName = c.getString(1),
                    appLabel = c.getString(2),
                    sender = c.getString(3),
                    subject = c.getString(4),
                    snippet = c.getString(5),
                    receivedAtMillis = c.getLong(6),
                    tag = runCatching { EmailAlertTag.valueOf(c.getString(7)) }.getOrDefault(EmailAlertTag.GENERAL),
                    isUrgent = c.getInt(8) == 1,
                    isRead = c.getInt(9) == 1
                )
            }
        }
        out
    }

    suspend fun markEmailAlertRead(alertId: String, read: Boolean = true) = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("is_read", if (read) 1 else 0) }
        writableDatabase.update("email_alerts", cv, "id = ?", arrayOf(alertId))
    }

    suspend fun markAllEmailsRead() = withContext(Dispatchers.IO) {
        val cv = ContentValues().apply { put("is_read", 1) }
        writableDatabase.update("email_alerts", cv, null, null)
    }

    suspend fun deleteEmailAlert(alertId: String) = withContext(Dispatchers.IO) {
        writableDatabase.delete("email_alerts", "id = ?", arrayOf(alertId))
    }

    suspend fun clearEmailAlerts() = withContext(Dispatchers.IO) {
        writableDatabase.delete("email_alerts", null, null)
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
            db.delete("secretary_tasks", null, null)
            db.delete("email_alerts", null, null)
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
        private const val DB_VERSION = 3
        private const val KEY_PROFILE = "profile_v1"
        private const val KEY_SSF = "ssf_v1"
        private const val KEY_EARNINGS = "earnings_v1"
        private const val KEY_ABBREV = "abbrev_v1"
        private const val KEY_HYGIENE = "hygiene_v1"
        private const val KEY_VIDEO_SLOTS = "video_slots_v1"
        private const val KEY_LATEST_BRIEFING = "latest_briefing_v1"
        private const val KEY_HOURLY_STEPS = "hourly_steps_v1"
        private const val KEY_HEALTH_VITALS = "health_vitals_v1"
        private const val KEY_MARKET_SNAPSHOT = "nepal_market_v1"
    }
}
