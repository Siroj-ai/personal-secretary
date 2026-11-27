package com.personalsecretary.app

import com.personalsecretary.app.data.AccountType
import com.personalsecretary.app.data.DailySpendStatus
import com.personalsecretary.app.data.DayBookParserAndHygiene
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.FinancialHygieneReport
import com.personalsecretary.app.data.NepalIpoItem
import com.personalsecretary.app.data.NepalMarketSnapshot
import com.personalsecretary.app.data.NepseStockPick
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SsfSummary
import com.personalsecretary.app.data.TransactionRecord
import com.personalsecretary.app.market.NepalMarketClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NepalMarketAndDailySpendTest {

    @Test
    fun parsesLiveNepalIposAndScreensPromisingNepseStocks() {
        val sampleIpoJson = """
            {
              "statusCode": 200,
              "result": {
                "data": [
                  {
                    "ipoId": 509,
                    "companyName": "Sample Growth Fund",
                    "stockSymbol": "SGF",
                    "sectorName": "Mutual Fund",
                    "shareType": "ordinary",
                    "pricePerUnit": "10",
                    "minUnits": "100",
                    "rating": "",
                    "openingDateAD": "2026-11-25",
                    "closingDateAD": "2026-11-30",
                    "status": "Open"
                  },
                  {
                    "ipoId": 513,
                    "companyName": "Pharma Lab Ltd",
                    "stockSymbol": "PLL",
                    "sectorName": "Others",
                    "shareType": "local",
                    "pricePerUnit": "100",
                    "minUnits": "10",
                    "rating": "CARE-NP AAA(Is)",
                    "openingDateAD": "2026-11-26",
                    "closingDateAD": "2026-12-10",
                    "status": "Open"
                  },
                  {
                    "ipoId": 508,
                    "companyName": "Closed Issue",
                    "stockSymbol": "CLD",
                    "sectorName": "Hydro Power",
                    "shareType": "ordinary",
                    "pricePerUnit": "100",
                    "minUnits": "10",
                    "status": "Closed"
                  }
                ]
              }
            }
        """.trimIndent()

        val ipos = NepalMarketClient.parseIposJson(sampleIpoJson)
        assertEquals(2, ipos.size) // Closed issue filtered out
        assertEquals("SGF", ipos.first().stockSymbol) // Open General Public prioritized over Local
        assertTrue(ipos.first().isGeneralPublic)
        assertEquals(1000.0, ipos.first().minInvestmentNpr, 0.01)
        assertFalse(ipos[1].isGeneralPublic)

        val sampleStocksJson = """
            {
              "statusCode": 200,
              "result": {
                "stocks": [
                  {
                    "stockSymbol": "NABIL",
                    "companyName": "Nabil Bank Limited",
                    "closingPrice": 560.0,
                    "percentChange": 2.45,
                    "amount": 145000000.0,
                    "noOfTransactions": 420,
                    "volume": 258900,
                    "asOfDateString": "As of Wed, 25 Nov 2026 | 03:00:00 PM"
                  },
                  {
                    "stockSymbol": "NABILBD89",
                    "companyName": "Nabil Debenture 2089",
                    "closingPrice": 1020.0,
                    "percentChange": 3.0,
                    "amount": 50000000.0,
                    "noOfTransactions": 100,
                    "volume": 49000
                  },
                  {
                    "stockSymbol": "TINY",
                    "companyName": "Illiquid Micro Co",
                    "closingPrice": 200.0,
                    "percentChange": 9.0,
                    "amount": 250000.0,
                    "noOfTransactions": 5,
                    "volume": 1250
                  }
                ]
              }
            }
        """.trimIndent()

        val (asOf, picks) = NepalMarketClient.parseNepseStocksJson(sampleStocksJson)
        assertTrue(asOf.contains("25 Nov 2026"))
        assertEquals(1, picks.size)
        assertEquals("NABIL", picks.first().symbol)
        assertEquals("High-Turnover Leader", picks.first().signalTag)
    }

    @Test
    fun dossierIncludesYesterdaySpendEvenWithOldCreditCardTailAndTriggersSmartInsights() {
        val today = LocalDate.of(2026, 11, 26)
        assertEquals("Tobacco & Alcohol", DayBookParserAndHygiene.inferCategory("cigare", 55.0, 0.0))
        assertEquals("Financial & Investments", DayBookParserAndHygiene.inferCategory("DP-NABIL STOCK DEALER", 200.0, 0.0))
        assertEquals("Groceries", DayBookParserAndHygiene.inferCategory("Milk and Curd", 140.0, 0.0))
        assertEquals("Home & Garden", DayBookParserAndHygiene.inferCategory("HOUSE EXPENSES", 10000.0, 0.0))
        assertEquals("Transportation", DayBookParserAndHygiene.inferCategory("Pathao ride", 180.0, 0.0))
        assertEquals("Healthcare", DayBookParserAndHygiene.inferCategory("Pharmacy medicine", 350.0, 0.0))
        assertEquals("Salary & Income", DayBookParserAndHygiene.inferCategory("Monthly Salary", 0.0, 45000.0))

        // Debit transactions followed by old Credit Card rows at the tail
        val txns = listOf(
            TransactionRecord("d1", AccountType.DEBIT, "2026-11-24", "11/24/2026", "cigare", "cigare", 250.0, 0.0, 96200.0, "Tobacco & Alcohol"),
            TransactionRecord("d2", AccountType.DEBIT, "2026-11-25", "11/25/2026", "DP-NABIL", "DP-NABIL STOCK DEALER", 200.0, 0.0, 96000.0, "Financial & Investments"),
            TransactionRecord("d3", AccountType.DEBIT, "2026-11-25", "11/25/2026", "REF-990123", "REF-990123", 150.0, 0.0, 95850.0, "Uncategorized"),
            TransactionRecord("d4", AccountType.DEBIT, "2026-11-25", "11/25/2026", "City Salon", "City Salon", 500.0, 0.0, 95350.0, "Household & Personal"),
            // Old Credit Card rows at the tail
            TransactionRecord("c1", AccountType.CREDIT, "2025-11-26", "11/26/2025", "Shoes", "Shoes", 900.0, 0.0, -4000.0, "Household & Personal"),
            TransactionRecord("c2", AccountType.CREDIT, "2025-12-10", "12/10/2025", "Gift", "Gift", 1500.0, 0.0, -5500.0, "Uncategorized"),
            TransactionRecord("c3", AccountType.CREDIT, "2026-05-31", "5/31/2026", "Card Fee", "Card Fee", 4500.0, 0.0, -10000.0, "Uncategorized")
        )

        val market = NepalMarketSnapshot(
            openIpos = listOf(
                NepalIpoItem("509", "Sample Growth Fund", "SGF", "Mutual Fund", "ordinary", true, 10.0, 100, 1000.0, "", "2026-11-25", "2026-11-30", "Open")
            ),
            promisingStocks = listOf(
                NepseStockPick("NABIL", "Nabil Bank Limited", 560.0, 2.45, 145_000_000.0, 420, 258900, "High-Turnover Leader", 30.0)
            ),
            marketAsOfLabel = "As of Wed, 25 Nov 2026"
        )

        val profile = SecretaryProfile(userName = "Client", monthlySpendBudget = 35000.0)
        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = txns,
            ssf = SsfSummary(),
            earnings = emptyList(),
            hygiene = FinancialHygieneReport(uniqueDebitRows = 4),
            steps = emptyList(),
            hourlyStepsByDate = emptyMap(),
            profile = profile,
            today = today,
            marketSnapshot = market
        )

        assertEquals(0.0, snap.todaySpend, 0.01)
        assertEquals(850.0, snap.yesterdaySpend, 0.01)
        assertEquals(250.0, snap.tobaccoAlcohol7d, 0.01)
        assertEquals(1, snap.uncategorizedRecentCount)
        assertEquals(50350.0, snap.investableSurplus, 0.01)

        assertTrue(snap.insights.any { it.id == "habit_spend" })
        assertTrue(snap.insights.any { it.id == "uncategorized_clarify" })
        assertTrue(snap.insights.any { it.id == "nepal_ipo_opportunity" })

        val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(
            snapshot = snap,
            profile = profile,
            recentTransactions = txns,
            userQuery = "What was my spending yesterday?",
            today = today
        )
        assertTrue(dossier.contains("Today: 2026-11-26"))
        assertTrue(dossier.contains("Yesterday: 2026-11-25"))
        assertTrue(dossier.contains("Yesterday (2026-11-25) Spending: Rs. 850.00 (3 txns)"))
        assertTrue(dossier.contains("DP-NABIL STOCK DEALER"))
        assertTrue(dossier.contains("REF-990123"))
        assertTrue(dossier.contains("City Salon"))
        assertTrue(dossier.contains("NEPAL IPO & NEPSE INVESTMENT RADAR"))
        assertTrue(dossier.contains("Sample Growth Fund"))
        assertTrue(dossier.contains("NABIL"))

        val detReply = ExecutiveAnalyticsEngine.buildDeterministicReply(
            userQuery = "What was my spending yesterday?",
            snapshot = snap,
            profile = profile,
            transactions = txns,
            today = today
        )
        assertTrue(detReply.contains("850.00"))
        assertTrue(detReply.contains("City Salon"))
        assertTrue(detReply.contains("REF-990123"))
    }

    @Test
    fun computesDailyExpenses14dAndTailoredCoachingSuggestions() {
        val today = LocalDate.of(2026, 11, 26)
        val profile = SecretaryProfile(userName = "Client", monthlySpendBudget = 30000.0) // Rs. 1,000/day cap
        val txns = listOf(
            TransactionRecord("t1", AccountType.DEBIT, "2026-11-22", "11/22/2026", "Groceries", "Groceries", 2500.0, 0.0, 80000.0, "Food & Dining"),
            TransactionRecord("t2", AccountType.DEBIT, "2026-11-24", "11/24/2026", "cigare", "cigare", 180.0, 0.0, 79820.0, "Tobacco & Alcohol"),
            TransactionRecord("t3", AccountType.DEBIT, "2026-11-25", "11/25/2026", "Lunch", "Lunch", 450.0, 0.0, 79370.0, "Food & Dining"),
            TransactionRecord("t4", AccountType.DEBIT, "2026-11-25", "11/25/2026", "REF-555", "REF-555", 150.0, 0.0, 79220.0, "Uncategorized")
        )

        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = txns,
            ssf = SsfSummary(),
            earnings = emptyList(),
            hygiene = FinancialHygieneReport(uniqueDebitRows = 4),
            steps = emptyList(),
            hourlyStepsByDate = emptyMap(),
            profile = profile,
            today = today
        )

        assertEquals(1000.0, snap.dailyBudgetAllowance, 0.01)
        assertEquals(14, snap.dailyExpenses14d.size)

        val day22 = snap.dailyExpenses14d.first { it.dateIso == "2026-11-22" }
        val day24 = snap.dailyExpenses14d.first { it.dateIso == "2026-11-24" }
        val day25 = snap.dailyExpenses14d.first { it.dateIso == "2026-11-25" }
        val day26 = snap.dailyExpenses14d.first { it.dateIso == "2026-11-26" }

        assertEquals(DailySpendStatus.OVER_BUDGET, day22.status)
        assertTrue(day22.advice.contains("Exceeded daily cap"))
        assertEquals(180.0, day24.tobaccoAlcoholSpend, 0.01)
        assertTrue(day24.advice.contains("Tobacco & Alcohol"))
        assertEquals(DailySpendStatus.UNDER_BUDGET, day25.status)
        assertEquals(2, day25.txCount)
        assertEquals(1, day25.uncategorizedCount)
        assertEquals(DailySpendStatus.NO_SPEND, day26.status)
        assertEquals("Today", day26.dayShortLabel)

        assertTrue(snap.dailyCoachingHeadline.isNotBlank())
        assertTrue(snap.dailyCoachingSuggestions.size >= 2)
        assertTrue(snap.dailyCoachingSuggestions.any { it.contains("Tobacco & Alcohol") })
        assertTrue(snap.dailyCoachingSuggestions.any { it.contains("2026-11-22") })

        val dailyReply = ExecutiveAnalyticsEngine.buildDeterministicReply(
            userQuery = "Analyze my daily expenses",
            snapshot = snap,
            profile = profile,
            transactions = txns,
            today = today
        )
        assertTrue(dailyReply.contains("Daily Expense Coach"))
        assertTrue(dailyReply.contains("2026-11-25"))
        assertTrue(dailyReply.contains("Suggestions:"))

        val verified = ExecutiveAnalyticsEngine.buildVerifiedFactsBlock(
            userQuery = "What was my spending yesterday?",
            snapshot = snap,
            profile = profile,
            transactions = txns,
            today = today
        )
        assertTrue(verified.contains("2026-11-25"))
        assertTrue(verified.contains("600.00"))
        assertTrue(verified.contains("Lunch"))

        val generalVerified = ExecutiveAnalyticsEngine.buildVerifiedFactsBlock(
            userQuery = "Give me a quick executive overview",
            snapshot = snap,
            profile = profile,
            transactions = txns,
            today = today
        )
        assertTrue(generalVerified.contains("79,220.00"))
        assertTrue(generalVerified.contains("1,000.00/d"))

        assertEquals(
            "Yesterday 2026-11-25: Rs. 850.00, today Rs. 0.00, min Rs. 1,000.00, budget 445.2%",
            ExecutiveAnalyticsEngine.sanitizeGemmaNumericGlitches(
                "Yesterday 2026-1111-25: Rs. 850.0.000, today Rs. 0.000.00, min Rs. 1,0000.00, budget 44445.2%"
            )
        )

        // Exact real-world GPU FP16 comma-group stutters (Rs. 1,7300.00 -> Rs. 1,730.00; Rs.1,1666.7 -> Rs. 1,166.67)
        val refCorpus = "Yesterday: Rs. 1,730.00 | Daily Cap: Rs. 1,166.67 | Investable Surplus: Rs. 100,183.98"
        assertEquals(
            "Yesterday's Rs. 1,730.00 exceeded the Rs. 1,166.67 daily target with Rs. 100,183.98 surplus.",
            ExecutiveAnalyticsEngine.sanitizeGemmaNumericGlitches(
                "Yesterday's Rs. 1,7300.00 exceeded the Rs.1,1666.7 daily target with Rs. 1000,1839.898 surplus.",
                refCorpus
            )
        )
        // Structural fallback even without reference corpus
        assertEquals(
            "Spend was Rs. 1,730.00.",
            ExecutiveAnalyticsEngine.sanitizeGemmaNumericGlitches("Spend was Rs. 1,7300.00.")
        )
    }

    @Test
    fun deduplicatesEditedBankRowsByDateAmountBalanceAndPreservesTobaccoAlerts() {
        val rawDebit = listOf(
            listOf("Transaction Date", "Description", "Debit", "Credit", "Balance", "Category"),
            // Edited human rows
            listOf("11/25/2026", "Mobile Recharge", "100", "", "80900.00", "Utilities & Subscriptions"),
            listOf("11/25/2026", "Sample Rum + Pilot Cigarrette", "720", "", "80180.00", "Other"),
            // Re-appended raw bank duplicates at exact same (Date, Debit, Credit, Balance)
            listOf("11/25/2026", "99887766", "100", "", "80900.00", ""),
            listOf("11/25/2026", "mmm", "720", "", "80180.00", ""),
            // Genuine second Rs. 100 spend on same day has DIFFERENT balance -> MUST NOT be dropped!
            listOf("11/25/2026", "khaja", "100", "", "80080.00", "Food & Dining")
        )

        val bundle = DayBookParserAndHygiene.buildBundle(
            rawDebitRows = rawDebit,
            rawCreditRows = emptyList(),
            rawSsfRows = emptyList(),
            rawEarningsRows = emptyList(),
            abbreviations = DayBookParserAndHygiene.defaultAbbreviations(),
            syncedLabel = "Test"
        )

        // 5 raw rows -> 3 unique rows (2 edited duplicates removed, genuine 2nd Rs. 100 kept!)
        assertEquals(3, bundle.transactions.size)
        assertEquals(2, bundle.hygieneReport.duplicatesRemoved)
        assertEquals(920.0, bundle.transactions.sumOf { it.debit }, 0.01)

        // Kept richer human descriptions over numeric/truncated bank codes
        assertTrue(bundle.transactions.any { it.description == "Mobile Recharge" })
        assertTrue(bundle.transactions.any { it.description == "Sample Rum + Pilot Cigarrette" })
        assertFalse(bundle.transactions.any { it.description == "99887766" })
        assertFalse(bundle.transactions.any { it.description == "mmm" })

        // Rum + Cigarette refined from generic 'Other' into Tobacco & Alcohol, while explicit Google Sheets categories preserved
        val rumTx = bundle.transactions.first { it.description.contains("Rum") }
        assertEquals("Tobacco & Alcohol", rumTx.category)
        val foodTx = bundle.transactions.first { it.description == "khaja" }
        assertEquals("Food & Dining", foodTx.category)

        // 'Other' sheet category auto-upgraded to Food & Dining for 'khaja'
        val khajaTx = bundle.transactions.first { it.description == "khaja" }
        assertEquals("Food & Dining", khajaTx.category)
    }
}
