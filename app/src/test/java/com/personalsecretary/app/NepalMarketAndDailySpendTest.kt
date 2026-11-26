package com.personalsecretary.app

import com.personalsecretary.app.data.AccountType
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
        assertEquals("Investments & Brokerage", DayBookParserAndHygiene.inferCategory("DP-NABIL STOCK DEALER", 200.0, 0.0))

        // Debit transactions followed by old Credit Card rows at the tail
        val txns = listOf(
            TransactionRecord("d1", AccountType.DEBIT, "2026-11-24", "11/24/2026", "cigare", "cigare", 250.0, 0.0, 96200.0, "Tobacco & Alcohol"),
            TransactionRecord("d2", AccountType.DEBIT, "2026-11-25", "11/25/2026", "DP-NABIL", "DP-NABIL STOCK DEALER", 200.0, 0.0, 96000.0, "Investments & Brokerage"),
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
}
