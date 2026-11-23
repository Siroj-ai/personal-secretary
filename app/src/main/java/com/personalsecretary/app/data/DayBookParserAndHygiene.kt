package com.personalsecretary.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

data class ParsedDayBookBundle(
    val transactions: List<TransactionRecord>,
    val ssfSummary: SsfSummary,
    val earnings: List<EarningEntry>,
    val abbreviations: List<AbbreviationRule>,
    val hygieneReport: FinancialHygieneReport
)

object DayBookParserAndHygiene {

    private val json = Json { ignoreUnknownKeys = true }
    private val isoFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun parseAppsScriptBootstrapJson(rawJson: String, syncedLabel: String): ParsedDayBookBundle {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: true
        if (!ok) {
            val err = (root["error"] as? JsonPrimitive)?.contentOrNull ?: "Apps Script returned ok=false"
            throw IllegalStateException(err)
        }
        val dataObj = (root["data"] as? JsonObject) ?: root

        val rawAbbrev = extractRows(dataObj["Abbreviations"])
        val abbreviations = parseAbbreviations(rawAbbrev)

        val rawDebit = extractRows(dataObj["Debit Card"])
        val rawCredit = extractRows(dataObj["Credit Card"])
        val rawSsf = extractRows(dataObj["Social Security Fund"])
        val rawEarnings = extractRows(dataObj["Earnings vs Salary"])

        return buildBundle(
            rawDebitRows = rawDebit,
            rawCreditRows = rawCredit,
            rawSsfRows = rawSsf,
            rawEarningsRows = rawEarnings,
            abbreviations = abbreviations,
            syncedLabel = syncedLabel
        )
    }

    fun parseCsvOrTsvDebitSheet(rawText: String, syncedLabel: String): ParsedDayBookBundle {
        val lines = rawText.lines().filter { it.isNotBlank() }
        val isTsv = lines.firstOrNull()?.contains('\t') == true
        val rows = lines.map { line ->
            if (isTsv) line.split('\t').map { it.trim() } else parseCsvLine(line)
        }
        return buildBundle(
            rawDebitRows = rows,
            rawCreditRows = emptyList(),
            rawSsfRows = emptyList(),
            rawEarningsRows = emptyList(),
            abbreviations = defaultAbbreviations(),
            syncedLabel = syncedLabel
        )
    }

    fun buildBundle(
        rawDebitRows: List<List<String>>,
        rawCreditRows: List<List<String>>,
        rawSsfRows: List<List<String>>,
        rawEarningsRows: List<List<String>>,
        abbreviations: List<AbbreviationRule>,
        syncedLabel: String
    ): ParsedDayBookBundle {
        val activeAbbrev = abbreviations.ifEmpty { defaultAbbreviations() }

        val (debitTxns, rawDebitCount, duplicatesRemoved, autoCategorizedCount, anomalies) =
            cleanAndDeduplicateRows(rawDebitRows, AccountType.DEBIT, activeAbbrev)

        val (creditTxns, _, _, _, _) =
            cleanAndDeduplicateRows(rawCreditRows, AccountType.CREDIT, activeAbbrev)

        val ssf = parseSsf(rawSsfRows)
        val earnings = parseEarnings(rawEarningsRows)

        val hygiene = FinancialHygieneReport(
            rawDebitRows = rawDebitCount,
            uniqueDebitRows = debitTxns.size,
            duplicatesRemoved = duplicatesRemoved,
            autoCategorizedCount = autoCategorizedCount,
            balanceAnomalies = anomalies.takeLast(12),
            ssfRefErrorFixedLocally = ssf.refErrorDetected,
            lastSyncedLabel = syncedLabel
        )

        return ParsedDayBookBundle(
            transactions = debitTxns + creditTxns,
            ssfSummary = ssf,
            earnings = earnings,
            abbreviations = activeAbbrev,
            hygieneReport = hygiene
        )
    }

    private data class CleanedAccountResult(
        val records: List<TransactionRecord>,
        val rawRowCount: Int,
        val duplicatesRemoved: Int,
        val autoCategorizedCount: Int,
        val anomalies: List<BalanceAnomaly>
    )

    private fun cleanAndDeduplicateRows(
        rows: List<List<String>>,
        accountType: AccountType,
        abbreviations: List<AbbreviationRule>
    ): CleanedAccountResult {
        if (rows.isEmpty()) {
            return CleanedAccountResult(emptyList(), 0, 0, 0, emptyList())
        }

        val startIndex = if (rows.first().firstOrNull()?.contains("Date", ignoreCase = true) == true) 1 else 0
        var rawValidCount = 0
        var duplicatesRemoved = 0
        var autoCategorizedCount = 0

        val seenKeys = LinkedHashMap<String, TransactionRecord>()

        for (i in startIndex until rows.size) {
            val row = rows[i]
            if (row.size < 5) continue

            val rawDate = row[0].trim()
            val desc = row[1].trim()
            val debit = parseMoney(row[2])
            val credit = parseMoney(row[3])
            val balance = parseMoney(row[4])
            val sheetCat = if (row.size >= 6) row[5].trim() else ""

            val hasDateOrOpening = rawDate.isNotEmpty() || desc.contains("Opening Balance", ignoreCase = true)
            if (!hasDateOrOpening && debit == 0.0 && credit == 0.0) continue

            rawValidCount++

            val dateIso = if (rawDate.isEmpty()) "0000-00-00" else parseToIsoDate(rawDate)
            val dedupKey = "${accountType.name}|$dateIso|${desc.lowercase(Locale.US)}|${toCents(debit)}|${toCents(credit)}|${toCents(balance)}"

            val existing = seenKeys[dedupKey]
            if (existing != null) {
                duplicatesRemoved++
                if (existing.category.isBlank() && sheetCat.isNotBlank()) {
                    seenKeys[dedupKey] = existing.copy(category = sheetCat, autoCategorized = false)
                }
                continue
            }

            val normalizedDesc = normalizeDescription(desc, abbreviations)
            val resolvedCategory: String
            val wasAutoCat: Boolean
            if (sheetCat.isNotBlank()) {
                resolvedCategory = sheetCat
                wasAutoCat = false
            } else {
                resolvedCategory = inferCategory(normalizedDesc, debit, credit)
                wasAutoCat = true
                autoCategorizedCount++
            }

            seenKeys[dedupKey] = TransactionRecord(
                id = "${accountType.name.lowercase(Locale.US)}_${seenKeys.size}_$dateIso",
                accountType = accountType,
                dateIso = dateIso,
                rawDate = rawDate,
                description = desc,
                normalizedDescription = normalizedDesc,
                debit = debit,
                credit = credit,
                balance = balance,
                category = resolvedCategory,
                autoCategorized = wasAutoCat
            )
        }

        // Preserve sheet order within identical dates, while ordering dates chronologically
        val indexed = seenKeys.values.toList().mapIndexed { idx, rec -> idx to rec }
        val sorted = indexed.sortedWith(compareBy<Pair<Int, TransactionRecord>> { it.second.dateIso }.thenBy { it.first })
            .map { it.second }

        // Running balance verification
        val anomalies = mutableListOf<BalanceAnomaly>()
        val verified = mutableListOf<TransactionRecord>()
        var prevBal: Double? = null

        for (rec in sorted) {
            var isAnomaly = false
            var diff = 0.0
            if (prevBal != null && accountType == AccountType.DEBIT) {
                val expected = round2(prevBal - rec.debit + rec.credit)
                diff = round2(rec.balance - expected)
                if (abs(diff) > 0.05) {
                    isAnomaly = true
                    anomalies += BalanceAnomaly(
                        dateIso = rec.dateIso,
                        description = rec.normalizedDescription.ifBlank { "(No description)" },
                        expectedBalance = expected,
                        actualBalance = rec.balance,
                        difference = diff
                    )
                }
            }
            prevBal = rec.balance
            verified += rec.copy(isBalanceAnomaly = isAnomaly, balanceDiff = diff)
        }

        return CleanedAccountResult(
            records = verified,
            rawRowCount = rawValidCount,
            duplicatesRemoved = duplicatesRemoved,
            autoCategorizedCount = autoCategorizedCount,
            anomalies = anomalies
        )
    }

    fun normalizeDescription(description: String, rules: List<AbbreviationRule>): String {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase(Locale.US)
        for (rule in rules) {
            val abbr = rule.abbreviation.trim().lowercase(Locale.US)
            if (abbr.isNotEmpty() && (lower == abbr || lower.startsWith("$abbr ") || lower.startsWith("$abbr-"))) {
                val fixedFull = rule.fullWord.replace("Cigarrette", "Cigarette", ignoreCase = true)
                return if (lower == abbr) fixedFull else "$fixedFull (${trimmed})"
            }
        }
        return trimmed.replace("Cigarrette", "Cigarette", ignoreCase = true)
    }

    fun inferCategory(description: String, debit: Double, credit: Double): String {
        val d = description.lowercase(Locale.US)
        if (credit > 0.0 && debit == 0.0) {
            return when {
                d.contains("salary") -> "Income"
                d.contains("ibft") || d.contains("accountft") || d.contains("nimb") -> "Transfers & Payments"
                else -> "Income"
            }
        }
        return when {
            d.contains("salary") -> "Income"
            d.contains("cigarette") || d.contains("cigarrette") || d.contains("surya") ||
                d.contains("rum") || d.contains("beer") || d.contains("wine") || d.contains("khukuri") ||
                d == "cg" || d == "cig" || d == "ciggy" || d == "pilot" -> "Tobacco & Alcohol"
            d.contains("khaja") || d.contains("kha") || d.contains("chiya") || d.contains("tea") ||
                d.contains("momo") || d.contains("coke") || d.contains("curd") || d.contains("dahi") ||
                d.contains("milk") || d.contains("dudh") || d.contains("apple") || d.contains("kiwi") ||
                d.contains("lays") || d.contains("biscuit") || d.contains("choco") || d.contains("cake") ||
                d.contains("cak") || d.contains("chowmein") || d.contains("samosa") || d.contains("paneer") ||
                d.contains("vegetable") || d.contains("fruit") || d.contains("egg") -> "Food & Dining"
            d.contains("atm wdl") || d.contains("monthly household") || d.contains("house") ||
                d.contains("hair") || d.contains("beauty") || d.contains("mop") || d.contains("cocopeat") ||
                d.contains("pant") || d.contains("slipper") -> "Household & Personal"
            d.contains("pathao") || d.contains("indrive") || d.contains("taxi") ||
                d.contains("bus") || d.contains("petrol") || d.contains("fuel") -> "Transport"
            d.contains("recharge") || d.contains("netflix") || d.contains("google") ||
                d.contains("internet") || d.contains("electricity") || d.contains("water") -> "Utilities & Subscriptions"
            d.contains("trucare") || d.contains("usg") || d.contains("medical") ||
                d.contains("pharmacy") || d.contains("lens") || d.contains("hospital") ||
                d.contains("follow up") || d.contains("treatment") -> "Medical"
            d.contains("card payment") || d.contains("credit card") -> "Credit Card Payment"
            d.contains("ibft") || d.contains("accountft") || d.contains("transfer") -> "Transfers & Payments"
            d.contains("charge") || d.contains("fee") -> "Bank Charges"
            else -> "Uncategorized"
        }
    }

    private fun parseSsf(rows: List<List<String>>): SsfSummary {
        val dataRow = rows.firstOrNull { row ->
            row.isNotEmpty() && parseMoney(row[0]) > 0.0
        } ?: return SsfSummary()

        val refundable = parseMoney(dataRow.getOrNull(0))
        val months = parseMoney(dataRow.getOrNull(1)).toInt()
        val pension = parseMoney(dataRow.getOrNull(2))
        val retirement = parseMoney(dataRow.getOrNull(3))
        val interest = parseMoney(dataRow.getOrNull(4))
        val accrued = parseMoney(dataRow.getOrNull(5))
        val loanPeriod = parseMoney(dataRow.getOrNull(6)).toInt()
        val duePrincipal = parseMoney(dataRow.getOrNull(7))
        val accountInterest = parseMoney(dataRow.getOrNull(8))
        val totalDue = parseMoney(dataRow.getOrNull(9)).let { if (it > 0.0) it else round2(duePrincipal + accountInterest) }
        val rawDiff = dataRow.getOrNull(10)?.trim() ?: ""
        val refErr = rawDiff.contains("#REF", ignoreCase = true) || rawDiff.contains("#ERROR", ignoreCase = true)
        val computedDiff = round2(refundable - totalDue)
        val interestSpread = parseMoney(dataRow.getOrNull(11)).let {
            if (it != 0.0) it else round2(accrued - accountInterest)
        }

        return SsfSummary(
            refundableAmount = refundable,
            contributedMonths = months,
            pensionAmount = pension,
            retirementAmount = retirement,
            interestAmount = interest,
            accruedInterest = accrued,
            loanAppliedPeriod = loanPeriod,
            duePrincipal = duePrincipal,
            accountInterest = accountInterest,
            totalDueAmount = totalDue,
            netEquity = computedDiff,
            netEquityWithAccrued = round2(refundable + accrued - totalDue),
            interestSpread = interestSpread,
            refErrorDetected = refErr
        )
    }

    private fun parseEarnings(rows: List<List<String>>): List<EarningEntry> {
        if (rows.isEmpty()) return emptyList()
        var currentMonth = ""
        val out = mutableListOf<EarningEntry>()
        for (row in rows) {
            val col0 = row.getOrNull(0)?.trim() ?: ""
            val col1 = row.getOrNull(1)?.trim() ?: ""
            val col2 = row.getOrNull(2)?.trim() ?: ""
            if (col0.equals("Month", ignoreCase = true) && col1.isNotEmpty()) {
                currentMonth = col1
                continue
            }
            val amt = parseMoney(col1)
            if (amt > 0.0 && col2.isNotEmpty() && !col2.equals("Sources", ignoreCase = true)) {
                out += EarningEntry(
                    monthLabel = currentMonth.ifBlank { "Recorded" },
                    source = col2,
                    amount = amt
                )
            }
        }
        return out
    }

    private fun parseAbbreviations(rows: List<List<String>>): List<AbbreviationRule> {
        val out = mutableListOf<AbbreviationRule>()
        for (row in rows) {
            if (row.size < 2) continue
            val k = row[0].trim()
            val v = row[1].trim()
            if (k.isEmpty() || k.equals("Abbreviation", ignoreCase = true)) continue
            out += AbbreviationRule(k, v)
        }
        return out
    }

    private fun extractRows(element: JsonElement?): List<List<String>> {
        val arr = element as? JsonArray ?: return emptyList()
        return arr.mapNotNull { rowEl ->
            val rowArr = rowEl as? JsonArray ?: return@mapNotNull null
            rowArr.map { cell ->
                when (cell) {
                    is JsonPrimitive -> cell.contentOrNull ?: ""
                    else -> cell.toString()
                }
            }
        }
    }

    fun parseToIsoDate(raw: String): String {
        val clean = raw.trim()
        if (clean.isEmpty()) return "0000-00-00"

        // Already ISO yyyy-MM-dd (or starts with ISO timestamp)
        if (clean.length >= 10 && clean[4] == '-' && clean[7] == '-') {
            return clean.substring(0, 10)
        }

        // Google Sheets numeric serial date (days since 1899-12-30)
        val serial = clean.toDoubleOrNull()
        if (serial != null && serial in 30000.0..70000.0) {
            val date = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
            return date.format(isoFormatter)
        }

        // M/d/yyyy or M-d-yyyy
        val sep = if (clean.contains('/')) '/' else if (clean.contains('-')) '-' else null
        if (sep != null) {
            val parts = clean.split(sep).map { it.trim() }
            if (parts.size >= 3) {
                val p0 = parts[0].toIntOrNull()
                val p1 = parts[1].toIntOrNull()
                val rawYear = parts[2].takeWhile { it.isDigit() }
                val year = when (rawYear.length) {
                    2 -> 2000 + (rawYear.toIntOrNull() ?: 0)
                    4 -> rawYear.toIntOrNull() ?: 0
                    else -> 0
                }
                if (p0 != null && p1 != null && year in 2000..2100 && p0 in 1..12 && p1 in 1..31) {
                    return String.format(Locale.US, "%04d-%02d-%02d", year, p0, p1)
                }
            }
        }
        return clean
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                ch == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += sb.toString().trim()
                    sb.clear()
                }
                else -> sb.append(ch)
            }
            i++
        }
        result += sb.toString().trim()
        return result
    }

    fun parseMoney(raw: String?): Double {
        if (raw.isNullOrBlank()) return 0.0
        val cleaned = raw.trim()
            .replace(",", "")
            .replace("Rs.", "", ignoreCase = true)
            .replace("NPR", "", ignoreCase = true)
            .replace(" ", "")
        if (cleaned.startsWith("#")) return 0.0
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    private fun toCents(value: Double): Long = (value * 100.0).roundToLong()

    fun round2(value: Double): Double = (value * 100.0).roundToLong() / 100.0

    fun defaultAbbreviations(): List<AbbreviationRule> = listOf(
        AbbreviationRule("cg", "Cigarette"),
        AbbreviationRule("cig", "Cigarette"),
        AbbreviationRule("Pilot", "Cigarette"),
        AbbreviationRule("ciggy", "Cigarette"),
        AbbreviationRule("ATM WDL", "Monthly Household Expenses")
    )
}
