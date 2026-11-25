package com.personalsecretary.app

import com.personalsecretary.app.data.AccountType
import com.personalsecretary.app.data.DailyStepRecord
import com.personalsecretary.app.data.DayBookParserAndHygiene
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.StepSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class DayBookAndAnalyticsTest {

    @Test
    fun deduplicatesRowsNormalizesAbbreviationsAndFixesSsfRefError() {
        val sampleJson = """
            {
              "ok": true,
              "data": {
                "Abbreviations": [
                  ["Abbreviation", "Full Word"],
                  ["cg", "Cigarrette"],
                  ["ATM WDL", "Monthly Household Expenses"]
                ],
                "Debit Card": [
                  ["Transaction Date", "Description", "Debit", "Credit", "Balance", "Category"],
                  ["11/19/2026", "Monthly Salary", "", "30000.00", "30100.00", ""],
                  ["11/20/2026", "cg", "180", "", "29920.00", ""],
                  ["11/20/2026", "cg", "180", "", "29920.00", ""],
                  ["11/20/2026", "cg", "180", "", "29920.00", ""],
                  ["11/22/2026", "Merchant 1001", "500", "", "44420.00", ""]
                ],
                "Credit Card": [
                  ["Transaction Date", "Description", "Debit", "Credit", "Balance"],
                  ["5/31/2026", "Statement Adjustment", "5000", "", "-12500.00"]
                ],
                "Social Security Fund": [
                  ["Savings(EXAMPLE)"],
                  ["Refundable Amount", "Contributed Months", "Pension Amount", "Retirement Amount", "Interest/Return Amount", "Accrued Interest", "Loan Applied Period", "Due Principal", "Account Interest", "Total Due Amount", "Savings vs Loan Difference", "Savings Interest vs Loan Interest"],
                  ["400000.00", "48", "100000.00", "260000.00", "40000.00", "5000.00", "4", "150000.00", "3000.00", "153000.00", "#REF!", "2000.00"]
                ],
                "Earnings vs Salary": [
                  ["Salary", "Income", "Sources"],
                  ["Month", "November", ""],
                  ["", "50,000.00", "Client A"],
                  ["", "25000", "Client B"]
                ]
              }
            }
        """.trimIndent()

        val bundle = DayBookParserAndHygiene.parseAppsScriptBootstrapJson(sampleJson, "Test Sync")

        assertEquals(5, bundle.hygieneReport.rawDebitRows)
        assertEquals(3, bundle.hygieneReport.uniqueDebitRows)
        assertEquals(2, bundle.hygieneReport.duplicatesRemoved)

        val debitTxns = bundle.transactions.filter { it.accountType == AccountType.DEBIT }
        val cigRow = debitTxns.first { it.debit == 180.0 }
        assertEquals("Cigarette", cigRow.normalizedDescription)
        assertEquals("Tobacco & Alcohol", cigRow.category)

        assertEquals(1, bundle.hygieneReport.balanceAnomalies.size)
        assertEquals(15000.0, bundle.hygieneReport.balanceAnomalies.first().difference, 0.01)

        assertTrue(bundle.ssfSummary.refErrorDetected)
        assertEquals(247000.00, bundle.ssfSummary.netEquity, 0.01)
        assertEquals(4, bundle.ssfSummary.loanAppliedPeriod)
        assertEquals(100000.00, bundle.ssfSummary.pensionAmount, 0.01)
        assertEquals(260000.00, bundle.ssfSummary.retirementAmount, 0.01)
        assertEquals(40000.00, bundle.ssfSummary.interestAmount, 0.01)
        assertEquals(5000.00, bundle.ssfSummary.accruedInterest, 0.01)
        assertEquals(252000.00, bundle.ssfSummary.netEquityWithAccrued, 0.01)
        assertEquals(2000.00, bundle.ssfSummary.interestSpread, 0.01)

        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = bundle.transactions,
            ssf = bundle.ssfSummary,
            earnings = bundle.earnings,
            hygiene = bundle.hygieneReport,
            steps = emptyList(),
            hourlyStepsByDate = emptyMap(),
            profile = SecretaryProfile(userName = "Client")
        )
        val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(snap, SecretaryProfile(userName = "Client"), bundle.transactions)
        assertTrue(dossier.contains("Applied Period: 4"))
        assertTrue(dossier.contains("Pension Rs. 100,000.00"))
        assertTrue(dossier.contains("FY Accrued Interest Rs. 5,000.00"))

        assertEquals(2, bundle.earnings.size)
        assertEquals(75000.00, bundle.earnings.sumOf { it.amount }, 0.01)
    }

    @Test
    fun computesFinancialAndPhysicalSnapshotWithSamsungMetrics() {
        val txs = DayBookParserAndHygiene.parseCsvOrTsvDebitSheet(
            """
            Transaction Date,Description,Debit,Credit,Balance,Category
            11/20/2026,khaja,200,,10000,Food & Dining
            11/21/2026,Surya Red,3500,,6500,Tobacco & Alcohol
            11/22/2026,khaja,150,,6350,Food & Dining
            """.trimIndent(),
            "CSV Test"
        )
        val steps = listOf(
            DailyStepRecord("2026-11-20", 9200, StepSource.HEALTH_CONNECT),
            DailyStepRecord("2026-11-21", 4000, StepSource.HEALTH_CONNECT),
            DailyStepRecord("2026-11-22", 8500, StepSource.HEALTH_CONNECT)
        )
        val hourly = mapOf(
            "2026-11-22" to List(24) { hr -> if (hr == 18) 3200L else if (hr == 9) 1800L else 0L }
        )

        val profile = SecretaryProfile(userName = "Client", dailyStepGoal = 8000, monthlySpendBudget = 50000.0)
        val snap = ExecutiveAnalyticsEngine.computeSnapshot(
            transactions = txs.transactions,
            ssf = txs.ssfSummary,
            earnings = txs.earnings,
            hygiene = txs.hygieneReport,
            steps = steps,
            hourlyStepsByDate = hourly,
            profile = profile,
            today = LocalDate.of(2026, 11, 22)
        )

        assertEquals(6350.0, snap.debitBalance, 0.01)
        assertEquals(8500L, snap.todaySteps)
        assertEquals(21700L, snap.weeklyStepsTotal)
        assertEquals(18, snap.peakHourToday)
        assertTrue(snap.todayMetrics.distanceKm > 6.0)
        assertTrue(snap.todayMetrics.caloriesKcal > 300)
        assertTrue(snap.insights.any { it.id == "habit_spend" })
        assertTrue(snap.insights.any { it.id == "steps_hit" })

        val dossier = ExecutiveAnalyticsEngine.buildGemmaDossier(snap, profile, txs.transactions)
        assertTrue(dossier.contains("EXECUTIVE CLIENT DOSSIER"))
        assertTrue(dossier.contains("6,350.00"))
        assertTrue(dossier.contains("8500 / 8000"))
        assertTrue(dossier.contains("6 PM"))
    }

    @Test
    fun stripsUtf8BomFromAppsScriptJsonAndCsvExports() {
        val bomJson = "\uFEFF" + """
            {
              "ok": true,
              "data": {
                "Debit Card": [
                  ["Transaction Date", "Description", "Debit", "Credit", "Balance", "Category"],
                  ["11/24/2026", "khaja", "250", "", "50000.00", "Food & Dining"]
                ],
                "Credit Card": [
                  ["Transaction Date", "Description", "Debit", "Credit", "Balance"],
                  ["11/24/2026", "Balance", "0", "0", "-4500.00"]
                ],
                "Social Security Fund": [],
                "Earnings vs Salary": []
              }
            }
        """.trimIndent()

        assertTrue(DayBookParserAndHygiene.looksLikeJson(bomJson))
        val bundle = DayBookParserAndHygiene.parseAppsScriptBootstrapJson(bomJson, "BOM Test")
        assertEquals(1, bundle.hygieneReport.uniqueDebitRows)
        val debit = bundle.transactions.first { it.accountType == AccountType.DEBIT }
        assertEquals(50000.00, debit.balance, 0.01)
        val credit = bundle.transactions.first { it.accountType == AccountType.CREDIT }
        assertEquals(-4500.00, credit.balance, 0.01)
    }
}
