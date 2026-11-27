package com.personalsecretary.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.AccountType
import com.personalsecretary.app.data.DailyExpenseSummary
import com.personalsecretary.app.data.DailySpendStatus
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.NepalIpoItem
import com.personalsecretary.app.data.NepseStockPick
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SsfSummary
import com.personalsecretary.app.data.TransactionRecord
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.MonthlyCashFlowChart
import com.personalsecretary.app.ui.components.SectionHeader
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.SkyAccent
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import java.util.Locale
import kotlin.math.sqrt

@Composable
fun FinancesScreen(
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    transactions: List<TransactionRecord>,
    isSyncing: Boolean,
    isScanningMarket: Boolean,
    syncMessage: String?,
    statusToast: String?,
    onSyncNow: () -> Unit,
    onRefreshMarket: () -> Unit,
    onRunSmartAlertsNow: () -> Unit,
    onAddTransaction: (description: String, amount: Double, isDebit: Boolean, category: String, pushRemote: Boolean) -> Unit,
    onUpdateTransaction: (txId: String, description: String, category: String) -> Unit,
    onAskClaraAboutDailyExpenses: () -> Unit,
    onAskClaraAboutInvesting: () -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val cur = profile.currencySymbol
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") } // ALL, UNCAT, DEBIT, CREDIT, ANOMALY
    var showAddDialog by remember { mutableStateOf(false) }
    var editingTx by remember { mutableStateOf<TransactionRecord?>(null) }

    val filteredTxns = remember(transactions, searchQuery, filterMode) {
        transactions
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<TransactionRecord>> { it.value.dateIso }
                    .thenByDescending { it.index }
            )
            .map { it.value }
            .filter { tx ->
                val matchesMode = when (filterMode) {
                    "UNCAT" -> tx.category.equals("Uncategorized", ignoreCase = true)
                    "DEBIT" -> tx.debit > 0.0
                    "CREDIT" -> tx.credit > 0.0
                    "ANOMALY" -> tx.isBalanceAnomaly
                    else -> true
                }
                val q = searchQuery.trim()
                val matchesSearch = q.isEmpty() ||
                    tx.normalizedDescription.contains(q, ignoreCase = true) ||
                    tx.description.contains(q, ignoreCase = true) ||
                    tx.category.contains(q, ignoreCase = true) ||
                    tx.dateIso.contains(q, ignoreCase = true)
                matchesMode && matchesSearch
            }
            .take(120)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 10.dp)
                ) {
                    Text(
                        text = "Finances",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = snapshot.hygieneReport.lastSyncedLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onSyncNow,
                        enabled = !isSyncing,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = if (isSyncing) "Syncing" else "Sync",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    Button(
                        onClick = { showAddDialog = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = "Log",
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }
            }
        }

        if (!syncMessage.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardSurfaceElevated,
                    border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = syncMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (profile.appsScriptUrl.isBlank()) {
                            TextButton(onClick = onNavigateToSetup) { Text("Setup") }
                        }
                    }
                }
            }
        }

        // Balance Sheet Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BalanceStatCard(
                    title = "Debit Liquid",
                    value = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.debitBalance),
                    sub = "${snapshot.hygieneReport.uniqueDebitRows} clean rows",
                    accent = EmeraldAccent,
                    modifier = Modifier.weight(1f)
                )
                BalanceStatCard(
                    title = "Credit Card",
                    value = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.creditCardBalance),
                    sub = "Outstanding balance",
                    accent = CoralAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BalanceStatCard(
                    title = "SSF Net Equity",
                    value = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.ssfSummary.netEquity),
                    sub = "Saved ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.ssfSummary.refundableAmount)} · Loan ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.ssfSummary.totalDueAmount)}",
                    accent = AccentGold,
                    modifier = Modifier.weight(1f)
                )
                BalanceStatCard(
                    title = "Side Earnings",
                    value = ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.earningsTotal),
                    sub = snapshot.earningsMonthLabel.ifBlank { "Earnings vs Salary" },
                    accent = EmeraldAccent,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // Data Hygiene Banner
        if (snapshot.hygieneReport.duplicatesRemoved > 0 || snapshot.hygieneReport.balanceAnomalies.isNotEmpty() || snapshot.hygieneReport.ssfRefErrorFixedLocally) {
            item {
                ExecutiveCard(borderColor = AccentGold.copy(alpha = 0.45f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AccentGold, modifier = Modifier.size(18.dp))
                        Text("Day Book Hygiene Shield", style = MaterialTheme.typography.titleSmall, color = AccentGold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = buildString {
                            if (snapshot.hygieneReport.duplicatesRemoved > 0) {
                                append("• Filtered ${snapshot.hygieneReport.duplicatesRemoved} duplicate rows (${snapshot.hygieneReport.rawDebitRows} → ${snapshot.hygieneReport.uniqueDebitRows}).\n")
                            }
                            if (snapshot.hygieneReport.autoCategorizedCount > 0) {
                                append("• Auto-categorized ${snapshot.hygieneReport.autoCategorizedCount} blank rows locally.\n")
                            }
                            if (snapshot.hygieneReport.balanceAnomalies.isNotEmpty()) {
                                append("• Flagged ${snapshot.hygieneReport.balanceAnomalies.size} running-balance gaps.\n")
                            }
                            if (snapshot.hygieneReport.ssfRefErrorFixedLocally) {
                                append("• Fixed SSF K3 #REF! locally using Refundable − Total Due.")
                            }
                        }.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        if (!statusToast.isNullOrBlank()) {
            item {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = CardSurfaceElevated,
                    border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = statusToast,
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentGold,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }

        // Daily Expenses & Smart Coach Card
        if (snapshot.dailyExpenses14d.isNotEmpty()) {
            item {
                DailyExpensesCoachCard(
                    snapshot = snapshot,
                    currency = cur,
                    onTapTransaction = { editingTx = it },
                    onLogExpense = { showAddDialog = true },
                    onAskClaraAboutDailyExpenses = onAskClaraAboutDailyExpenses
                )
            }
        }

        // Social Security Fund (SSF) Full Breakdown Card
        if (snapshot.ssfSummary.refundableAmount > 0.0 || snapshot.ssfSummary.totalDueAmount > 0.0) {
            item {
                SsfDetailsCard(ssf = snapshot.ssfSummary, currency = cur)
            }
        }

        // Nepal IPO & NEPSE Secondary Investment Radar
        item {
            NepalMarketInvestmentCard(
                snapshot = snapshot,
                currency = cur,
                isScanning = isScanningMarket,
                onRefreshMarket = onRefreshMarket,
                onRunSmartAlertsNow = onRunSmartAlertsNow,
                onAskClaraAboutInvesting = onAskClaraAboutInvesting
            )
        }

        // Monthly Cash Flow
        item {
            ExecutiveCard {
                SectionHeader(
                    title = "Monthly Cash Flow",
                    subtitle = "Deduplicated Debit Card income vs. expenses"
                )
                Spacer(Modifier.height(12.dp))
                MonthlyCashFlowChart(flows = snapshot.monthlyCashFlows)
            }
        }

        // Top Spending Categories (30d)
        if (snapshot.topCategories30d.isNotEmpty()) {
            item {
                ExecutiveCard {
                    SectionHeader(
                        title = "30-Day Spend by Category",
                        subtitle = "Burn rate ${ExecutiveAnalyticsEngine.formatMoney(cur, snapshot.dailyBurnRate30d)}/day"
                    )
                    Spacer(Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        snapshot.topCategories30d.forEach { cat ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(cat.category, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                                    Text(
                                        "${ExecutiveAnalyticsEngine.formatMoney(cur, cat.amount)} (${cat.sharePercent}%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AccentGold,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { (cat.sharePercent / 100.0).toFloat().coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(6.dp),
                                    color = if (cat.category.contains("Tobacco", ignoreCase = true)) CoralAccent else AccentGold,
                                    trackColor = CardBorder
                                )
                            }
                        }
                    }
                }
            }
        }

        // Ledger Search & Filter
        item {
            SectionHeader(
                title = "Deduplicated Ledger",
                subtitle = "Showing ${filteredTxns.size} of ${transactions.size} entries"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search description, category, or YYYY-MM-DD") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "ALL" to "All",
                    "UNCAT" to "Uncat.",
                    "DEBIT" to "Debits",
                    "CREDIT" to "Credits",
                    "ANOMALY" to "Gaps"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = filterMode == key,
                        onClick = { filterMode = key },
                        label = { Text(label, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }

        if (filteredTxns.isEmpty()) {
            item {
                ExecutiveCard {
                    Text(
                        text = if (transactions.isEmpty()) {
                            "No transactions loaded yet. Configure your Day Book Apps Script URL or import a JSON/CSV file in Health -> Secretary Setup."
                        } else {
                            "No transactions match your current filter."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            items(filteredTxns, key = { it.id }) { tx ->
                TransactionRowCard(
                    tx = tx,
                    currency = cur,
                    onClick = { editingTx = tx }
                )
            }
        }
    }

    if (showAddDialog) {
        AddTransactionDialog(
            hasAppsScript = profile.appsScriptUrl.startsWith("https://"),
            onDismiss = { showAddDialog = false },
            onSubmit = { desc, amt, isDebit, cat, pushRemote ->
                showAddDialog = false
                onAddTransaction(desc, amt, isDebit, cat, pushRemote)
            }
        )
    }

    editingTx?.let { target ->
        ClarifyTransactionDialog(
            tx = target,
            currency = cur,
            hasAppsScript = profile.appsScriptUrl.startsWith("https://"),
            onDismiss = { editingTx = null },
            onSave = { newDesc, newCat ->
                editingTx = null
                onUpdateTransaction(target.id, newDesc, newCat)
            }
        )
    }
}

@Composable
private fun BalanceStatCard(
    title: String,
    value: String,
    sub: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    ExecutiveCard(modifier = modifier) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = accent, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall, color = TextSecondary, maxLines = 2)
    }
}

@Composable
private fun SsfDetailsCard(ssf: SsfSummary, currency: String) {
    ExecutiveCard(borderColor = AccentGold.copy(alpha = 0.45f)) {
        val loanSubtitle = if (ssf.loanAppliedPeriod > 0) {
            "${ssf.contributedMonths} months contributed · Loan applied ${ssf.loanAppliedPeriod}x"
        } else {
            "${ssf.contributedMonths} months contributed"
        }
        SectionHeader(
            title = "Social Security Fund (SSF)",
            subtitle = loanSubtitle
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "SAVINGS BREAKDOWN",
            style = MaterialTheme.typography.labelSmall,
            color = EmeraldAccent,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        SsfMetricRow("Refundable Savings", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.refundableAmount), EmeraldAccent, bold = true)
        SsfMetricRow("Pension Amount", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.pensionAmount), TextPrimary)
        SsfMetricRow("Retirement Amount", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.retirementAmount), TextPrimary)
        SsfMetricRow("Posted Return / Interest", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.interestAmount), TextPrimary)
        SsfMetricRow("FY Accrued Interest", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.accruedInterest), AccentGold)

        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = CardBorder)
        Spacer(Modifier.height(10.dp))

        Text(
            text = "SPECIAL LOAN & NET POSITION",
            style = MaterialTheme.typography.labelSmall,
            color = CoralAccent,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        SsfMetricRow("Loan Applied Period", if (ssf.loanAppliedPeriod > 0) "${ssf.loanAppliedPeriod}" else "—", TextPrimary)
        SsfMetricRow("Due Principal", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.duePrincipal), TextPrimary)
        SsfMetricRow("Account Interest Due", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.accountInterest), TextPrimary)
        SsfMetricRow("Total Loan Due", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.totalDueAmount), CoralAccent, bold = true)

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = CardBorder)
        Spacer(Modifier.height(8.dp))

        SsfMetricRow("Net Equity (Savings − Loan)", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.netEquity), EmeraldAccent, bold = true)
        SsfMetricRow("Net Equity (incl. Accrued)", ExecutiveAnalyticsEngine.formatMoney(currency, ssf.netEquityWithAccrued), AccentGold, bold = true)
        SsfMetricRow(
            "Interest Spread (Savings − Loan)",
            ExecutiveAnalyticsEngine.formatSignedMoney(currency, ssf.interestSpread),
            if (ssf.interestSpread >= 0.0) EmeraldAccent else CoralAccent,
            bold = true
        )
    }
}

@Composable
private fun SsfMetricRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color,
    bold: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = valueColor,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun NepalMarketInvestmentCard(
    snapshot: ExecutiveSnapshot,
    currency: String,
    isScanning: Boolean,
    onRefreshMarket: () -> Unit,
    onRunSmartAlertsNow: () -> Unit,
    onAskClaraAboutInvesting: () -> Unit
) {
    val mkt = snapshot.marketSnapshot
    val surplus = snapshot.investableSurplus
    val hasSurplus = surplus >= 2000.0

    ExecutiveCard(borderColor = if (hasSurplus && mkt.hasMarketData) EmeraldAccent.copy(alpha = 0.45f) else CardBorder) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.TrendingUp,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(20.dp)
                )
                Column {
                    Text(
                        text = "Nepal IPO & NEPSE",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = mkt.marketAsOfLabel
                            .removePrefix("As of ")
                            .substringBefore("|")
                            .trim()
                            .ifBlank { "Live NepaliPaisa / NEPSE" },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            OutlinedButton(
                onClick = onRefreshMarket,
                enabled = !isScanning,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AccentGold)
                    Spacer(Modifier.width(5.dp))
                    Text("Scan...", maxLines = 1, softWrap = false)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan Web", maxLines = 1, softWrap = false)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardSurfaceElevated,
            border = BorderStroke(1.dp, if (hasSurplus) EmeraldAccent.copy(alpha = 0.4f) else CardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Investable Surplus", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text(
                        text = if (hasSurplus) {
                            "Ready for IPOs (Rs. 1,000+) & liquid NEPSE shares"
                        } else {
                            "Build cash buffer & clear CC before investing"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                Text(
                    text = ExecutiveAnalyticsEngine.formatMoney(currency, surplus),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (hasSurplus) EmeraldAccent else TextMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        val combinedIpos = (mkt.openIpos + mkt.upcomingIpos).take(4)
        if (combinedIpos.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "OPEN & UPCOMING NEPAL IPOS",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGold,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                combinedIpos.forEach { ipo -> IpoCompactRow(ipo = ipo, currency = currency) }
            }
        }

        if (mkt.promisingStocks.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "PROMISING LIQUID NEPSE SHARES",
                style = MaterialTheme.typography.labelSmall,
                color = SkyAccent,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mkt.promisingStocks.take(5).forEach { st -> NepsePickRow(pick = st, currency = currency) }
            }
        } else if (!isScanning) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Tap 'Scan Web' to fetch live open Nepal IPOs and high-liquidity NEPSE stocks.",
                style = MaterialTheme.typography.bodySmall,
                color = TextMuted
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onAskClaraAboutInvesting,
                colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Ask Clara to Pick", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = onRunSmartAlertsNow,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
            ) {
                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Alert Check", maxLines = 1, softWrap = false)
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Live NepaliPaisa/NEPSE screening for research only — verify fundamentals before investing.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun IpoCompactRow(ipo: NepalIpoItem, currency: String) {
    val isOpen = ipo.status.equals("Open", ignoreCase = true)
    val badgeColor = if (isOpen && ipo.isGeneralPublic) EmeraldAccent else if (isOpen) AccentGold else SkyAccent
    val audience = if (ipo.isGeneralPublic) "General Public" else ipo.shareType

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, if (isOpen && ipo.isGeneralPublic) EmeraldAccent.copy(alpha = 0.4f) else CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${ipo.companyName} (${ipo.stockSymbol})",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 6.dp)
                )
                Text(
                    text = if (isOpen) "OPEN" else ipo.status.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    fontWeight = FontWeight.Bold
                )
            }
            val rat = if (ipo.rating.isNotBlank()) " · ${ipo.rating}" else ""
            Text(
                text = "$audience · Min ${ExecutiveAnalyticsEngine.formatMoney(currency, ipo.minInvestmentNpr)} · Closes ${ipo.closingDateAd}$rat",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun NepsePickRow(pick: NepseStockPick, currency: String) {
    val pctColor = if (pick.percentChange >= 0.0) EmeraldAccent else CoralAccent
    val cr = String.format(Locale.US, "%.1f", pick.turnoverNpr / 10_000_000.0)

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "${pick.symbol} · ${pick.signalTag}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${pick.companyName} · Rs. ${cr} Cr (${pick.transactions} trades)",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = ExecutiveAnalyticsEngine.formatMoney(currency, pick.ltp),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = String.format(Locale.US, "%+.2f%%", pick.percentChange),
                    style = MaterialTheme.typography.labelSmall,
                    color = pctColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun TransactionRowCard(
    tx: TransactionRecord,
    currency: String,
    onClick: () -> Unit
) {
    val isDebit = tx.debit > 0.0
    val isUncat = tx.category.equals("Uncategorized", ignoreCase = true)
    val amountText = if (isDebit) {
        "-${ExecutiveAnalyticsEngine.formatMoney(currency, tx.debit)}"
    } else {
        "+${ExecutiveAnalyticsEngine.formatMoney(currency, tx.credit)}"
    }
    val amountColor = if (isDebit) CoralAccent else EmeraldAccent

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(
            1.dp,
            when {
                tx.isBalanceAnomaly -> CoralAccent.copy(alpha = 0.5f)
                isUncat -> AccentGold.copy(alpha = 0.45f)
                else -> CardBorder
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tx.normalizedDescription.ifBlank { "(No description)" },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${tx.dateIso} · ${tx.category}${if (isUncat) " · Tap to label" else if (tx.autoCategorized) " (auto)" else ""}${if (tx.accountType == AccountType.CREDIT) " · CC" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isUncat) AccentGold else TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (tx.isBalanceAnomaly) {
                    Text(
                        text = "Balance gap: ${ExecutiveAnalyticsEngine.formatSignedMoney(currency, tx.balanceDiff)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = CoralAccent
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(amountText, style = MaterialTheme.typography.titleSmall, color = amountColor, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Bal ${ExecutiveAnalyticsEngine.formatMoney(currency, tx.balance)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
        }
    }
}

@Composable
private fun ClarifyTransactionDialog(
    tx: TransactionRecord,
    currency: String,
    hasAppsScript: Boolean,
    onDismiss: () -> Unit,
    onSave: (description: String, category: String) -> Unit
) {
    var desc by remember(tx.id) { mutableStateOf(tx.normalizedDescription.ifBlank { tx.description }) }
    var category by remember(tx.id) { mutableStateOf(tx.category) }
    val categories = listOf(
        "Food & Dining",
        "Personal & Lifestyle",
        "Household & Personal",
        "Tobacco & Alcohol",
        "Alcohol",
        "Groceries",
        "Healthcare",
        "Transport",
        "Utilities & Subscriptions",
        "Financial & Investments",
        "Transfers",
        "Income"
    )
    val amtLabel = if (tx.debit > 0.0) {
        "-${ExecutiveAnalyticsEngine.formatMoney(currency, tx.debit)}"
    } else {
        "+${ExecutiveAnalyticsEngine.formatMoney(currency, tx.credit)}"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clarify Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${tx.dateIso} · $amtLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = AccentGold
                )
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("What was this for?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Expense Type / Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = category.equals(cat, ignoreCase = true),
                            onClick = { category = cat },
                            label = { Text(cat.substringBefore(" &").substringBefore(" "), maxLines = 1, softWrap = false) }
                        )
                    }
                }
                if (hasAppsScript) {
                    Text(
                        text = "Syncs description & expense type directly to Google Sheets.",
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldAccent
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(desc.trim().ifBlank { tx.normalizedDescription }, category.trim().ifBlank { "Uncategorized" }) }
            ) { Text(if (hasAppsScript) "Save & Sync" else "Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun AddTransactionDialog(
    hasAppsScript: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (description: String, amount: Double, isDebit: Boolean, category: String, pushRemote: Boolean) -> Unit
) {
    var desc by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var isDebit by remember { mutableStateOf(true) }
    var category by remember { mutableStateOf("Food & Dining") }
    var pushRemote by remember { mutableStateOf(hasAppsScript) }

    val quickCategories = listOf(
        "Food & Dining", "Household & Personal", "Transport",
        "Medical", "Tobacco & Alcohol", "Utilities & Subscriptions", "Income"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = isDebit,
                        onClick = { isDebit = true },
                        label = { Text("Expense (Debit)", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = !isDebit,
                        onClick = { isDebit = false; category = "Income" },
                        label = { Text("Income (Credit)", maxLines = 1, softWrap = false) }
                    )
                }
                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it },
                    label = { Text("Amount (Rs.)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCategories.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.substringBefore(" "), maxLines = 1, softWrap = false) }
                        )
                    }
                }
                if (hasAppsScript) {
                    FilterChip(
                        selected = pushRemote,
                        onClick = { pushRemote = !pushRemote },
                        label = { Text(if (pushRemote) "Sync to Day Book Sheet: ON" else "Save locally only") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amountText.trim().replace(",", "").toDoubleOrNull() ?: return@Button
                    if (amt > 0.0 && desc.isNotBlank()) {
                        onSubmit(desc.trim(), amt, isDebit, category.trim(), pushRemote)
                    }
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DailyExpensesCoachCard(
    snapshot: ExecutiveSnapshot,
    currency: String,
    onTapTransaction: (TransactionRecord) -> Unit,
    onLogExpense: () -> Unit,
    onAskClaraAboutDailyExpenses: () -> Unit
) {
    val all14 = snapshot.dailyExpenses14d
    if (all14.isEmpty()) return

    val defaultDateIso = remember(all14) {
        val todayRec = all14.lastOrNull()
        if (todayRec != null && todayRec.totalSpend > 0.0) {
            todayRec.dateIso
        } else {
            all14.lastOrNull { it.totalSpend > 0.0 }?.dateIso ?: todayRec?.dateIso.orEmpty()
        }
    }
    var selectedDateIso by remember(defaultDateIso) { mutableStateOf(defaultDateIso) }
    val selectedDay = all14.firstOrNull { it.dateIso == selectedDateIso } ?: all14.last()
    val last7 = all14.takeLast(7)
    val dailyCap = snapshot.dailyBudgetAllowance

    val statusColor = when (selectedDay.status) {
        DailySpendStatus.NO_SPEND, DailySpendStatus.UNDER_BUDGET -> EmeraldAccent
        DailySpendStatus.WATCH -> AccentGold
        DailySpendStatus.OVER_BUDGET -> CoralAccent
    }

    ExecutiveCard(
        borderColor = if (dailyCap > 0.0 && snapshot.avgDailySpend7d > dailyCap * 1.15) {
            CoralAccent.copy(alpha = 0.45f)
        } else {
            EmeraldAccent.copy(alpha = 0.4f)
        }
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "Daily Expenses & Coach",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Cap ${currency} ${String.format(Locale.US, "%,.0f", dailyCap)}/d · 7d avg ${currency} ${String.format(Locale.US, "%,.0f", snapshot.avgDailySpend7d)}/d",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = onLogExpense,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Log", maxLines = 1, softWrap = false)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Interactive 7-day bar chart with dashed Daily Cap guide line (sqrt-scaled so spike days don't flatten normal days)
        val chartMaxSqrt = remember(last7, dailyCap) {
            val peak = last7.maxOfOrNull { it.totalSpend } ?: 0.0
            sqrt(maxOf(peak, dailyCap * 1.25, 100.0))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(126.dp)
        ) {
            if (dailyCap > 0.0) {
                val capRatio = (sqrt(dailyCap) / chartMaxSqrt).toFloat().coerceIn(0.10f, 0.90f)
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp, bottom = 26.dp)
                        .fillMaxHeight()
                ) {
                    val y = size.height * (1f - capRatio)
                    drawLine(
                        color = AccentGold.copy(alpha = 0.65f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 2.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                last7.forEach { day ->
                    val isSelected = day.dateIso == selectedDay.dateIso
                    val barColor = when (day.status) {
                        DailySpendStatus.NO_SPEND -> CardBorder
                        DailySpendStatus.UNDER_BUDGET -> EmeraldAccent
                        DailySpendStatus.WATCH -> AccentGold
                        DailySpendStatus.OVER_BUDGET -> CoralAccent
                    }
                    val fraction = if (day.totalSpend <= 0.0) {
                        0.06f
                    } else {
                        (sqrt(day.totalSpend) / chartMaxSqrt).toFloat().coerceIn(0.10f, 1f)
                    }
                    Surface(
                        onClick = { selectedDateIso = day.dateIso },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) AccentGold.copy(alpha = 0.14f) else ObsidianBg.copy(alpha = 0.0f),
                        border = if (isSelected) BorderStroke(1.dp, AccentGold.copy(alpha = 0.7f)) else null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 2.dp, vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = compactAmountLabel(day.totalSpend),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) AccentGold else TextMuted,
                                maxLines = 1,
                                softWrap = false,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.56f)
                                        .fillMaxHeight(fraction)
                                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                        .background(barColor)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = day.dayShortLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isSelected) TextPrimary else TextMuted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Scrollable 14-day selector strip (newest first)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            all14.reversed().forEach { day ->
                val isSelected = day.dateIso == selectedDay.dateIso
                val chipLabel = when (day.dayShortLabel) {
                    "Today" -> "Today"
                    "Yest" -> "Yest"
                    else -> day.dateIso.takeLast(5)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedDateIso = day.dateIso },
                    label = {
                        Text(
                            text = if (day.totalSpend > 0.0) "$chipLabel · ${compactAmountLabel(day.totalSpend)}" else chipLabel,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Selected Day Breakdown Panel
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = CardSurfaceElevated,
            border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "${selectedDay.dateIso} (${selectedDay.dayShortLabel})",
                            style = MaterialTheme.typography.titleSmall,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${selectedDay.status.label} · ${selectedDay.txCount} expense${if (selectedDay.txCount == 1) "" else "s"}" +
                                if (selectedDay.totalIncome > 0.0) " · +${ExecutiveAnalyticsEngine.formatMoney(currency, selectedDay.totalIncome)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = ExecutiveAnalyticsEngine.formatMoney(currency, selectedDay.totalSpend),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (selectedDay.totalSpend > 0.0) CoralAccent else EmeraldAccent,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (selectedDay.advice.isNotBlank()) {
                    Text(
                        text = selectedDay.advice,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                if (selectedDay.transactions.isNotEmpty()) {
                    HorizontalDivider(color = CardBorder)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        selectedDay.transactions.take(6).forEach { tx ->
                            val isUncat = tx.category.equals("Uncategorized", ignoreCase = true)
                            val isDebit = tx.debit > 0.0
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onTapTransaction(tx) }
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = tx.normalizedDescription.ifBlank { "(No description)" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (isUncat) "${tx.category} · Tap to label" else tx.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isUncat) AccentGold else TextMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = if (isDebit) {
                                        "-${ExecutiveAnalyticsEngine.formatMoney(currency, tx.debit)}"
                                    } else {
                                        "+${ExecutiveAnalyticsEngine.formatMoney(currency, tx.credit)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isDebit) CoralAccent else EmeraldAccent,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        if (selectedDay.transactions.size > 6) {
                            Text(
                                text = "+${selectedDay.transactions.size - 6} more in Deduplicated Ledger below",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                        }
                    }
                }
            }
        }

        // Personalized Daily Suggestions Panel
        if (snapshot.dailyCoachingHeadline.isNotBlank() || snapshot.dailyCoachingSuggestions.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "DAILY EXPENSE SUGGESTIONS",
                style = MaterialTheme.typography.labelSmall,
                color = AccentGold,
                fontWeight = FontWeight.SemiBold
            )
            if (snapshot.dailyCoachingHeadline.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = snapshot.dailyCoachingHeadline,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                snapshot.dailyCoachingSuggestions.take(3).forEach { tip ->
                    Text(
                        text = "• $tip",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onAskClaraAboutDailyExpenses,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Ask Clara for Daily Plan", maxLines = 1, softWrap = false, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun compactAmountLabel(amount: Double): String {
    return when {
        amount <= 0.0 -> "0"
        amount >= 100_000.0 -> String.format(Locale.US, "%.0fk", amount / 1000.0)
        amount >= 10_000.0 -> String.format(Locale.US, "%.0fk", amount / 1000.0)
        amount >= 1_000.0 -> String.format(Locale.US, "%.1fk", amount / 1000.0)
        else -> amount.toInt().toString()
    }
}
