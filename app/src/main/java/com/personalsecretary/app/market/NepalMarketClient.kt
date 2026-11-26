package com.personalsecretary.app.market

import com.personalsecretary.app.data.NepalIpoItem
import com.personalsecretary.app.data.NepalMarketSnapshot
import com.personalsecretary.app.data.NepseStockPick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

/**
 * Fetches live public Nepal IPO/FPO issues and NEPSE daily secondary market prices
 * from NepaliPaisa's public JSON endpoints so Clara can alert on open IPOs and
 * screen liquid NEPSE secondary share opportunities whenever surplus cash is available.
 */
class NepalMarketClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchMarketSnapshot(): Result<NepalMarketSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val ipoRaw = fetchText(IPO_API_URL)
            val stocksRaw = fetchText(NEPSE_PRICES_URL)

            val ipos = parseIposJson(ipoRaw)
            val (asOfLabel, picks) = parseNepseStocksJson(stocksRaw)

            val openIpos = ipos.filter { it.status.equals("Open", ignoreCase = true) }
            val upcomingIpos = ipos.filter {
                it.status.equals("Nearing", ignoreCase = true) ||
                    it.status.equals("Upcoming", ignoreCase = true) ||
                    it.status.equals("Pipeline", ignoreCase = true)
            }

            NepalMarketSnapshot(
                openIpos = openIpos,
                upcomingIpos = upcomingIpos,
                promisingStocks = picks,
                marketAsOfLabel = asOfLabel.ifBlank { "Live NEPSE" },
                sourceLabel = "NepaliPaisa (NEPSE)",
                syncedAtMillis = System.currentTimeMillis()
            )
        }
    }

    private fun fetchText(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "PersonalSecretary-Android/1.3")
            .header("Accept", "application/json")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Market fetch failed (HTTP ${resp.code})")
            }
            resp.body?.string().orEmpty()
        }
    }

    companion object {
        private const val IPO_API_URL =
            "https://www.nepalipaisa.com/api/GetIpos?stockSymbol=&pageNo=1&itemsPerPage=20&pagePerDisplay=5"
        private const val NEPSE_PRICES_URL =
            "https://www.nepalipaisa.com/api/GetTodaySharePrice?stockSymbol=&pageNo=1&itemsPerPage=400&pagePerDisplay=5"

        private val json = Json { ignoreUnknownKeys = true }

        fun parseIposJson(rawJson: String): List<NepalIpoItem> {
            val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull() ?: return emptyList()
            val result = root["result"] as? JsonObject ?: return emptyList()
            val data = result["data"] as? JsonArray ?: return emptyList()

            val out = mutableListOf<NepalIpoItem>()
            for (el in data) {
                val obj = el as? JsonObject ?: continue
                val company = obj.str("companyName")
                if (company.isBlank()) continue
                val status = obj.str("status")
                if (status.equals("Closed", ignoreCase = true)) continue

                val price = obj.str("pricePerUnit").replace(",", "").toDoubleOrNull() ?: 100.0
                val minUnits = obj.str("minUnits").replace(",", "").toIntOrNull() ?: 10
                val shareType = obj.str("shareType").ifBlank { "ordinary" }
                val sector = obj.str("sectorName").ifBlank { "General" }
                val rating = obj.str("rating")
                val isGeneralPublic = shareType.equals("ordinary", ignoreCase = true) ||
                    shareType.contains("general", ignoreCase = true)

                out += NepalIpoItem(
                    id = obj.str("ipoId").ifBlank { "${obj.str("stockSymbol")}_${shareType}_${obj.str("openingDateAD")}" },
                    companyName = company,
                    stockSymbol = obj.str("stockSymbol").ifBlank { company.take(6).uppercase(Locale.US) },
                    sectorName = sector,
                    shareType = shareType,
                    isGeneralPublic = isGeneralPublic,
                    pricePerUnit = price,
                    minUnits = minUnits,
                    minInvestmentNpr = round2(price * minUnits),
                    rating = rating,
                    openingDateAd = obj.str("openingDateAD"),
                    closingDateAd = obj.str("closingDateAD"),
                    status = status
                )
            }
            // Prioritize Open General Public issues first, then Upcoming General Public, then Local/Migrant
            return out.sortedWith(
                compareByDescending<NepalIpoItem> { it.status.equals("Open", ignoreCase = true) }
                    .thenByDescending { it.isGeneralPublic }
                    .thenBy { it.closingDateAd.ifBlank { "9999-12-31" } }
            ).take(10)
        }

        /**
         * Screens liquid, actively traded NEPSE secondary shares (excluding illiquid spikes,
         * debentures, mutual funds, and promoter shares) for healthy positive momentum and strong turnover.
         */
        fun parseNepseStocksJson(rawJson: String): Pair<String, List<NepseStockPick>> {
            val root = runCatching { json.parseToJsonElement(rawJson).jsonObject }.getOrNull()
                ?: return "" to emptyList()
            val result = root["result"] as? JsonObject ?: return "" to emptyList()
            val stocks = result["stocks"] as? JsonArray ?: return "" to emptyList()

            var asOf = ""
            val candidates = mutableListOf<NepseStockPick>()

            for (el in stocks) {
                val obj = el as? JsonObject ?: continue
                if (asOf.isBlank()) {
                    asOf = obj.str("asOfDateString").ifBlank { obj.str("tradeDate") }
                }
                val symbol = obj.str("stockSymbol").trim()
                val name = obj.str("companyName").trim()
                if (symbol.isBlank() || name.isBlank()) continue

                val lowerName = name.lowercase(Locale.US)
                // Exclude debentures, mutual funds, preference & promoter shares from secondary equity picks
                if (lowerName.contains("debenture") ||
                    lowerName.contains("bond") ||
                    lowerName.contains("mutual fund") ||
                    lowerName.contains("fund") ||
                    lowerName.contains("promoter") ||
                    symbol.endsWith("P", ignoreCase = false) && symbol.length >= 5
                ) continue

                val ltp = obj.dbl("closingPrice")
                val pct = obj.dbl("percentChange")
                val turnover = obj.dbl("amount")
                val txns = obj.intVal("noOfTransactions")
                val volume = obj.intVal("volume")

                // Liquidity & quality floor: Rs. 150+ price, >= Rs. 1 Crore turnover, >= 60 trades
                if (ltp < 150.0 || turnover < 10_000_000.0 || txns < 60) continue

                // Prefer healthy positive momentum (+0.4% to +9.5%) backed by strong institutional turnover
                if (pct in 0.4..9.5) {
                    val turnoverCr = round2(turnover / 10_000_000.0)
                    val score = (turnoverCr * 1.4) + (pct * 2.2) + (txns / 120.0)
                    val tag = when {
                        turnoverCr >= 10.0 && pct >= 1.5 -> "High-Turnover Leader"
                        pct >= 3.5 -> "Strong Momentum"
                        turnoverCr >= 5.0 -> "Liquid Active Mover"
                        else -> "Steady Uptrend"
                    }
                    candidates += NepseStockPick(
                        symbol = symbol,
                        companyName = name,
                        ltp = ltp,
                        percentChange = pct,
                        turnoverNpr = turnover,
                        transactions = txns,
                        volume = volume,
                        signalTag = tag,
                        score = score
                    )
                }
            }

            // Fallback on red market days: pick top high-turnover blue-chip NEPSE leaders holding near flat (>= -1.5%)
            val finalPicks = if (candidates.size >= 4) {
                candidates.sortedByDescending { it.score }.take(6)
            } else {
                val defensive = mutableListOf<NepseStockPick>()
                for (el in stocks) {
                    val obj = el as? JsonObject ?: continue
                    val symbol = obj.str("stockSymbol").trim()
                    val name = obj.str("companyName").trim()
                    val lowerName = name.lowercase(Locale.US)
                    if (symbol.isBlank() || lowerName.contains("debenture") || lowerName.contains("fund") || lowerName.contains("promoter")) continue
                    val ltp = obj.dbl("closingPrice")
                    val pct = obj.dbl("percentChange")
                    val turnover = obj.dbl("amount")
                    val txns = obj.intVal("noOfTransactions")
                    val volume = obj.intVal("volume")
                    if (ltp >= 150.0 && turnover >= 15_000_000.0 && txns >= 80 && pct >= -1.5) {
                        defensive += NepseStockPick(
                            symbol = symbol,
                            companyName = name,
                            ltp = ltp,
                            percentChange = pct,
                            turnoverNpr = turnover,
                            transactions = txns,
                            volume = volume,
                            signalTag = if (pct >= 0.0) "Liquid Market Leader" else "Defensive High-Turnover",
                            score = turnover / 10_000_000.0
                        )
                    }
                }
                (candidates + defensive)
                    .distinctBy { it.symbol }
                    .sortedByDescending { it.score }
                    .take(6)
            }

            return asOf to finalPicks
        }

        private fun JsonObject.str(key: String): String =
            (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

        private fun JsonObject.dbl(key: String): Double =
            (this[key] as? JsonPrimitive)?.let { p ->
                p.doubleOrNull ?: p.contentOrNull?.replace(",", "")?.toDoubleOrNull()
            } ?: 0.0

        private fun JsonObject.intVal(key: String): Int =
            (this[key] as? JsonPrimitive)?.let { p ->
                p.intOrNull ?: p.contentOrNull?.replace(",", "")?.toDoubleOrNull()?.toInt()
            } ?: 0

        private fun round2(v: Double): Double = (v * 100.0).roundToLong() / 100.0
    }
}
