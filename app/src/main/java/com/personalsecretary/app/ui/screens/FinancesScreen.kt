package com.personalsecretary.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.AccountType
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
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
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary

@Composable
fun FinancesScreen(
    snapshot: ExecutiveSnapshot,
    profile: SecretaryProfile,
    transactions: List<TransactionRecord>,
    isSyncing: Boolean,
    syncMessage: String?,
    onSyncNow: () -> Unit,
    onAddTransaction: (description: String, amount: Double, isDebit: Boolean, category: String, pushRemote: Boolean) -> Unit,
    onNavigateToSetup: () -> Unit
) {
    val cur = profile.currencySymbol
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("ALL") } // ALL, DEBIT, CREDIT, ANOMALY
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredTxns = remember(transactions, searchQuery, filterMode) {
        transactions
            .asReversed()
            .filter { tx ->
                val matchesMode = when (filterMode) {
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

        // Social Security Fund (SSF) Full Breakdown Card
        if (snapshot.ssfSummary.refundableAmount > 0.0 || snapshot.ssfSummary.totalDueAmount > 0.0) {
            item {
                SsfDetailsCard(ssf = snapshot.ssfSummary, currency = cur)
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "All", "DEBIT" to "Debits", "CREDIT" to "Credits", "ANOMALY" to "Gaps").forEach { (key, label) ->
                    FilterChip(
                        selected = filterMode == key,
                        onClick = { filterMode = key },
                        label = { Text(label) }
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
                TransactionRowCard(tx = tx, currency = cur)
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
private fun TransactionRowCard(tx: TransactionRecord, currency: String) {
    val isDebit = tx.debit > 0.0
    val amountText = if (isDebit) {
        "-${ExecutiveAnalyticsEngine.formatMoney(currency, tx.debit)}"
    } else {
        "+${ExecutiveAnalyticsEngine.formatMoney(currency, tx.credit)}"
    }
    val amountColor = if (isDebit) CoralAccent else EmeraldAccent

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, if (tx.isBalanceAnomaly) CoralAccent.copy(alpha = 0.5f) else CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = tx.normalizedDescription.ifBlank { "(No description)" },
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = "${tx.dateIso} · ${tx.category}${if (tx.autoCategorized) " (auto)" else ""}${if (tx.accountType == AccountType.CREDIT) " · CC" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
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
