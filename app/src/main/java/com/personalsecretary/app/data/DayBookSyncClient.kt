package com.personalsecretary.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Serializable
private data class AddTxRequestBody(
    val action: String = "add_transaction",
    val sheetName: String,
    val date: String,
    val description: String,
    val debit: Double? = null,
    val credit: Double? = null,
    val balance: Double,
    val category: String = "",
    val clientRequestId: String = ""
)

@Serializable
private data class UpdateTxRequestBody(
    val action: String = "update_transaction",
    val sheetName: String,
    val dateIso: String,
    val debit: Double,
    val credit: Double,
    val balance: Double,
    val newDescription: String,
    val newCategory: String
)

class DayBookSyncClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun syncFromAppsScript(rawUrl: String): Result<ParsedDayBookBundle> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawUrl.trim()
            require(trimmed.startsWith("https://")) {
                "Apps Script URL must start with https://"
            }
            val urlWithQuery = if (trimmed.contains("?")) {
                if (trimmed.contains("action=")) trimmed else "$trimmed&action=bootstrap"
            } else {
                "$trimmed?action=bootstrap"
            }

            val req = Request.Builder().url(urlWithQuery).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Sync failed (HTTP ${resp.code})")
                }
                if (body.trimStart().startsWith("<")) {
                    throw IllegalStateException(
                        "Apps Script returned HTML instead of JSON. Ensure Web App access is set to 'Anyone' or redeploy."
                    )
                }
                val ts = SimpleDateFormat("MMM d, hh:mm a", Locale.getDefault()).format(Date())
                DayBookParserAndHygiene.parseAppsScriptBootstrapJson(body, "Apps Script ($ts)")
            }
        }
    }

    suspend fun syncFromPublicSheetCsv(spreadsheetId: String, gid: String = "0"): Result<ParsedDayBookBundle> =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = extractSpreadsheetId(spreadsheetId)
                require(id.isNotBlank()) { "Please enter a valid Google Spreadsheet ID or URL." }
                val exportUrl = "https://docs.google.com/spreadsheets/d/$id/export?format=csv&gid=$gid"
                val req = Request.Builder().url(exportUrl).get().build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.trimStart().startsWith("<")) {
                        throw IllegalStateException(
                            "Sheet is private (requires your Apps Script Web App URL or a CSV/TSV file import)."
                        )
                    }
                    val ts = SimpleDateFormat("MMM d, hh:mm a", Locale.getDefault()).format(Date())
                    DayBookParserAndHygiene.parseCsvOrTsvDebitSheet(body, "Sheet CSV ($ts)")
                }
            }
        }

    suspend fun postTransactionToAppsScript(
        rawUrl: String,
        sheetName: String,
        dateDisplay: String,
        description: String,
        amount: Double,
        isDebit: Boolean,
        newBalance: Double,
        category: String,
        requestId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawUrl.trim()
            require(trimmed.startsWith("https://")) { "Configure an Apps Script Web App URL first." }
            val payload = AddTxRequestBody(
                sheetName = sheetName,
                date = dateDisplay,
                description = description,
                debit = if (isDebit) amount else null,
                credit = if (!isDebit) amount else null,
                balance = newBalance,
                category = category,
                clientRequestId = requestId
            )
            val body = json.encodeToString(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(trimmed).post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.trimStart().startsWith("<")) {
                    throw IllegalStateException("Remote write failed (HTTP ${resp.code}). Saved locally instead.")
                }
                text
            }
        }
    }

    suspend fun postTransactionUpdateToAppsScript(
        rawUrl: String,
        sheetName: String,
        dateIso: String,
        debit: Double,
        credit: Double,
        balance: Double,
        newDescription: String,
        newCategory: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = rawUrl.trim()
            require(trimmed.startsWith("https://")) { "Configure an Apps Script Web App URL first." }
            val payload = UpdateTxRequestBody(
                sheetName = sheetName,
                dateIso = dateIso,
                debit = debit,
                credit = credit,
                balance = balance,
                newDescription = newDescription,
                newCategory = newCategory
            )
            val body = json.encodeToString(payload)
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(trimmed).post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful || text.trimStart().startsWith("<")) {
                    throw IllegalStateException("Sheet sync failed (HTTP ${resp.code}).")
                }
                val parsed = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val ok = (parsed?.get("ok") as? JsonPrimitive)?.booleanOrNull ?: true
                if (!ok) {
                    val err = (parsed["error"] as? JsonPrimitive)?.contentOrNull ?: "Sheet update rejected"
                    throw IllegalStateException(err)
                }
                (parsed?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Synced to Google Sheets"
            }
        }
    }

    companion object {
        fun extractSpreadsheetId(input: String): String {
            val trimmed = input.trim()
            val marker = "/spreadsheets/d/"
            val idx = trimmed.indexOf(marker)
            return if (idx >= 0) {
                trimmed.substring(idx + marker.length).substringBefore("/").substringBefore("?")
            } else {
                trimmed
            }
        }
    }
}
