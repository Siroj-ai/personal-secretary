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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddTask
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.CalendarEventItem
import com.personalsecretary.app.data.EmailAlertItem
import com.personalsecretary.app.data.EmailAlertTag
import com.personalsecretary.app.data.ExecutiveAnalyticsEngine
import com.personalsecretary.app.data.ExecutiveSnapshot
import com.personalsecretary.app.data.SecretaryTask
import com.personalsecretary.app.data.TaskCategory
import com.personalsecretary.app.data.TaskPriority
import com.personalsecretary.app.data.TaskRecurrence
import com.personalsecretary.app.ui.components.ExecutiveCard
import com.personalsecretary.app.ui.components.SectionHeader
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.SkyAccent
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun ScheduleScreen(
    snapshot: ExecutiveSnapshot,
    tasks: List<SecretaryTask>,
    emailAlerts: List<EmailAlertItem>,
    calendarPermissionGranted: Boolean,
    notificationAccessGranted: Boolean,
    statusToast: String?,
    onRequestCalendarPermission: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    onRefreshSchedule: () -> Unit,
    onAddTask: (
        title: String,
        notes: String,
        dueAtMillis: Long?,
        priority: TaskPriority,
        category: TaskCategory,
        recurrence: TaskRecurrence,
        reminderEnabled: Boolean
    ) -> Unit,
    onToggleTask: (String) -> Unit,
    onDeleteTask: (String) -> Unit,
    onMarkEmailRead: (String) -> Unit,
    onMarkAllEmailsRead: () -> Unit,
    onConvertEmailToTask: (EmailAlertItem) -> Unit,
    onDeleteEmailAlert: (String) -> Unit,
    onClearEmailAlerts: () -> Unit,
    onAskClaraAboutSchedule: () -> Unit,
    onAskClaraAboutInbox: () -> Unit
) {
    var subTab by remember { mutableStateOf(0) } // 0 = Agenda & Tasks, 1 = Email Inbox
    var filter by remember { mutableStateOf("OPEN") } // OPEN, TODAY, WORK, FINANCE, DONE
    var emailFilter by remember { mutableStateOf("ALL") } // ALL, UNREAD, WORK, BILL, MEETING
    var showAddDialog by remember { mutableStateOf(false) }
    var presetTitle by remember { mutableStateOf("") }
    var presetCategory by remember { mutableStateOf(TaskCategory.WORK) }
    var presetRecurrence by remember { mutableStateOf(TaskRecurrence.NONE) }

    val nowMillis = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val tomorrowStart = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    val filteredTasks = remember(tasks, filter) {
        when (filter) {
            "OPEN" -> tasks.filter { !it.isCompleted }
            "TODAY" -> tasks.filter { !it.isCompleted && (it.dueAtMillis == null || it.dueAtMillis < tomorrowStart) }
            "WORK" -> tasks.filter { !it.isCompleted && it.category == TaskCategory.WORK }
            "FINANCE" -> tasks.filter { !it.isCompleted && it.category == TaskCategory.FINANCE }
            "DONE" -> tasks.filter { it.isCompleted }
            else -> tasks
        }
    }

    val filteredEmails = remember(emailAlerts, emailFilter) {
        when (emailFilter) {
            "UNREAD" -> emailAlerts.filter { !it.isRead }
            "WORK" -> emailAlerts.filter { it.tag == EmailAlertTag.WORK_ACTION }
            "BILL" -> emailAlerts.filter { it.tag == EmailAlertTag.BILL_FINANCE }
            "MEETING" -> emailAlerts.filter { it.tag == EmailAlertTag.MEETING_SCHEDULE }
            else -> emailAlerts
        }
    }

    val futureEvents = remember(snapshot.upcomingEvents7d, snapshot.todayEvents) {
        val todayKeys = snapshot.todayEvents.map { "${it.id}_${it.startMillis}" }.toSet()
        snapshot.upcomingEvents7d.filterNot { "${it.id}_${it.startMillis}" in todayKeys }.take(8)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        PrimaryTabRow(
            selectedTabIndex = subTab,
            containerColor = CardSurface,
            contentColor = AccentGold
        ) {
            Tab(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                text = { Text("Agenda & Tasks", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            )
            val unreadBadge = if (snapshot.unreadEmailsCount > 0) " (${snapshot.unreadEmailsCount})" else ""
            Tab(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                text = { Text("Email Inbox$unreadBadge", style = MaterialTheme.typography.labelMedium, maxLines = 1) }
            )
        }

        if (subTab == 1) {
            EmailInboxContent(
                emails = filteredEmails,
                totalCount = emailAlerts.size,
                unreadCount = snapshot.unreadEmailsCount,
                urgentCount = snapshot.urgentEmailsCount,
                emailFilter = emailFilter,
                onSelectFilter = { emailFilter = it },
                notificationAccessGranted = notificationAccessGranted,
                statusToast = statusToast,
                onOpenNotificationAccessSettings = onOpenNotificationAccessSettings,
                onOpenAppInfoSettings = onOpenAppInfoSettings,
                onRefresh = onRefreshSchedule,
                onMarkRead = onMarkEmailRead,
                onMarkAllRead = onMarkAllEmailsRead,
                onConvertToTask = onConvertEmailToTask,
                onDelete = onDeleteEmailAlert,
                onClearAll = onClearEmailAlerts,
                onAskClaraAboutInbox = onAskClaraAboutInbox
            )
            return@Column
        }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header Row
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
                        text = "Schedule",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Calendar & reminders",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onRefreshSchedule,
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Sync", maxLines = 1, softWrap = false)
                    }
                    Button(
                        onClick = {
                            presetTitle = ""
                            presetCategory = TaskCategory.WORK
                            presetRecurrence = TaskRecurrence.NONE
                            showAddDialog = true
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Task", maxLines = 1, softWrap = false)
                    }
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

        // Executive Schedule Summary Triad
        item {
            ExecutiveCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    ScheduleStatTile(
                        label = "Events Today",
                        value = snapshot.todayEvents.size.toString(),
                        accent = SkyAccent,
                        modifier = Modifier.weight(1f)
                    )
                    ScheduleStatTile(
                        label = "Open Tasks",
                        value = snapshot.pendingTasks.size.toString(),
                        accent = AccentGold,
                        modifier = Modifier.weight(1f)
                    )
                    ScheduleStatTile(
                        label = "Overdue",
                        value = snapshot.overdueTasksCount.toString(),
                        accent = if (snapshot.overdueTasksCount > 0) CoralAccent else EmeraldAccent,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onAskClaraAboutSchedule,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Ask Clara to Prioritize My Day", maxLines = 1, softWrap = false)
                }
            }
        }

        // Phone Calendar Agenda Card (Google / Work Calendar synced on OnePlus 13)
        item {
            ExecutiveCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = AccentGold, modifier = Modifier.size(20.dp))
                    SectionHeader(
                        title = "Phone Calendar Agenda",
                        subtitle = if (calendarPermissionGranted) {
                            "${snapshot.todayEvents.size} today · ${snapshot.upcomingEvents7d.size} in next 7 days"
                        } else {
                            "Syncs Google & Work calendars on-device"
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (!calendarPermissionGranted) {
                    Text(
                        text = "Grant read-only Calendar permission so Clara can view your synced meetings and factor them into daily briefings and work reminders.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onRequestCalendarPermission,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Connect Phone Calendar", maxLines = 1, softWrap = false)
                    }
                } else if (snapshot.todayEvents.isEmpty() && futureEvents.isEmpty()) {
                    Text(
                        text = "No calendar meetings found for the next 7 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (snapshot.todayEvents.isNotEmpty()) {
                            Text(
                                text = "TODAY",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentGold,
                                fontWeight = FontWeight.SemiBold
                            )
                            snapshot.todayEvents.take(6).forEach { ev ->
                                CalendarEventRow(event = ev, isToday = true)
                            }
                        }
                        if (futureEvents.isNotEmpty()) {
                            if (snapshot.todayEvents.isNotEmpty()) Spacer(Modifier.height(4.dp))
                            Text(
                                text = "UPCOMING (NEXT 7 DAYS)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontWeight = FontWeight.SemiBold
                            )
                            futureEvents.take(5).forEach { ev ->
                                CalendarEventRow(event = ev, isToday = false)
                            }
                        }
                    }
                }
            }
        }

        // Quick-add templates + Work/Personal Tasks Header
        item {
            SectionHeader(
                title = "Work Tasks & Reminders",
                subtitle = "Exact-time alerts persist across phone restarts"
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    Triple("Credit Card Bill Due", TaskCategory.FINANCE, TaskRecurrence.MONTHLY),
                    Triple("Work Follow-up", TaskCategory.WORK, TaskRecurrence.NONE),
                    Triple("Weekly Status Report", TaskCategory.WORK, TaskRecurrence.WEEKLY),
                    Triple("Evening Walk Check", TaskCategory.HEALTH, TaskRecurrence.DAILY)
                ).forEach { (title, cat, rec) ->
                    OutlinedButton(
                        onClick = {
                            presetTitle = title
                            presetCategory = cat
                            presetRecurrence = rec
                            showAddDialog = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "OPEN" to "Open",
                    "TODAY" to "Due Today",
                    "WORK" to "Work",
                    "FINANCE" to "Finance",
                    "DONE" to "Completed"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = filter == key,
                        onClick = { filter = key },
                        label = { Text(label, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }

        if (filteredTasks.isEmpty()) {
            item {
                ExecutiveCard {
                    Text(
                        text = if (filter == "DONE") {
                            "No completed tasks yet."
                        } else {
                            "No tasks in this view. Tap '+ Task' or a quick template above to set a work, bill, or health reminder."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            items(filteredTasks, key = { it.id }) { task ->
                val isOverdue = !task.isCompleted && task.dueAtMillis != null && task.dueAtMillis < nowMillis
                val isDueToday = !task.isCompleted && task.dueAtMillis != null &&
                    task.dueAtMillis >= todayStart && task.dueAtMillis < tomorrowStart
                TaskRowCard(
                    task = task,
                    isOverdue = isOverdue,
                    isDueToday = isDueToday,
                    onToggle = { onToggleTask(task.id) },
                    onDelete = { onDeleteTask(task.id) }
                )
            }
        }
    }
    }

    if (showAddDialog) {
        AddTaskDialog(
            initialTitle = presetTitle,
            initialCategory = presetCategory,
            initialRecurrence = presetRecurrence,
            onDismiss = { showAddDialog = false },
            onSubmit = { title, notes, dueMillis, priority, category, recurrence, reminder ->
                showAddDialog = false
                onAddTask(title, notes, dueMillis, priority, category, recurrence, reminder)
            }
        )
    }
}

@Composable
private fun ScheduleStatTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun CalendarEventRow(event: CalendarEventItem, isToday: Boolean) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, if (isToday) SkyAccent.copy(alpha = 0.4f) else CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val timeStr = when {
                isToday -> ExecutiveAnalyticsEngine.formatEventTimeRange(event)
                event.allDay -> "${ExecutiveAnalyticsEngine.formatMillisDateOnly(event.startMillis)} · All day"
                else -> ExecutiveAnalyticsEngine.formatMillisDateTime(event.startMillis)
            }
            val meta = buildList {
                add(timeStr)
                if (event.location.isNotBlank()) add(event.location)
                if (event.calendarName.isNotBlank()) add(event.calendarName)
            }.joinToString(" · ")
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = if (isToday) SkyAccent else TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TaskRowCard(
    task: SecretaryTask,
    isOverdue: Boolean,
    isDueToday: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val priorityColor = when (task.priority) {
        TaskPriority.URGENT -> CoralAccent
        TaskPriority.HIGH -> AccentGold
        TaskPriority.NORMAL -> SkyAccent
    }
    val border = when {
        isOverdue -> CoralAccent.copy(alpha = 0.55f)
        isDueToday -> AccentGold.copy(alpha = 0.45f)
        else -> CardBorder
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = if (task.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                    contentDescription = "Toggle done",
                    tint = if (task.isCompleted) EmeraldAccent else priorityColor
                )
            }
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (task.isCompleted) TextMuted else TextPrimary,
                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (task.notes.isNotBlank()) {
                    Text(
                        text = task.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "${task.category.label} · ${task.priority.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = priorityColor,
                        fontWeight = FontWeight.Medium
                    )
                    if (task.dueAtMillis != null) {
                        val dueText = ExecutiveAnalyticsEngine.formatMillisDateTime(task.dueAtMillis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (task.reminderEnabled && !task.isCompleted) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = if (isOverdue) CoralAccent else AccentGold,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                            Text(
                                text = if (isOverdue) "Overdue · $dueText" else dueText,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOverdue) CoralAccent else TextMuted
                            )
                        }
                    }
                    if (task.recurrence != TaskRecurrence.NONE) {
                        Icon(
                            imageVector = Icons.Default.Repeat,
                            contentDescription = task.recurrence.label,
                            tint = TextMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete task",
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun AddTaskDialog(
    initialTitle: String,
    initialCategory: TaskCategory,
    initialRecurrence: TaskRecurrence,
    onDismiss: () -> Unit,
    onSubmit: (
        title: String,
        notes: String,
        dueAtMillis: Long?,
        priority: TaskPriority,
        category: TaskCategory,
        recurrence: TaskRecurrence,
        reminderEnabled: Boolean
    ) -> Unit
) {
    val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm") }
    val zone = remember { ZoneId.systemDefault() }
    val defaultDue = remember {
        LocalDateTime.now(zone).plusHours(1).withSecond(0).withNano(0)
    }

    var title by remember { mutableStateOf(initialTitle) }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(initialCategory) }
    var priority by remember { mutableStateOf(TaskPriority.HIGH) }
    var recurrence by remember { mutableStateOf(initialRecurrence) }
    var dueText by remember { mutableStateOf(defaultDue.format(fmt)) }
    var reminderEnabled by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule Work / Reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task or Reminder Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Details / Context (optional)") },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskCategory.entries.forEach { cat ->
                        FilterChip(
                            selected = category == cat,
                            onClick = { category = cat },
                            label = { Text(cat.label, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskPriority.entries.forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = p },
                            label = { Text(p.label, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                OutlinedTextField(
                    value = dueText,
                    onValueChange = { dueText = it },
                    label = { Text("Due Date & Time (YYYY-MM-DD HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val now = LocalDateTime.now(zone).withSecond(0).withNano(0)
                    listOf(
                        "In 1h" to now.plusHours(1).format(fmt),
                        "Today 5 PM" to LocalDateTime.of(LocalDate.now(zone), LocalTime.of(17, 0)).format(fmt),
                        "Tom 9 AM" to LocalDateTime.of(LocalDate.now(zone).plusDays(1), LocalTime.of(9, 0)).format(fmt),
                        "Next Mon" to LocalDateTime.of(LocalDate.now(zone).with(TemporalAdjusters.next(DayOfWeek.MONDAY)), LocalTime.of(9, 0)).format(fmt),
                        "No time" to ""
                    ).forEach { (lbl, value) ->
                        FilterChip(
                            selected = dueText == value,
                            onClick = { dueText = value },
                            label = { Text(lbl, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TaskRecurrence.entries.forEach { r ->
                        FilterChip(
                            selected = recurrence == r,
                            onClick = { recurrence = r },
                            label = { Text(r.label, maxLines = 1, softWrap = false) }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Push notification alert", style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                    Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = title.trim()
                    if (clean.isEmpty()) return@Button
                    val dueMillis = dueText.trim().takeIf { it.isNotEmpty() }?.let { raw ->
                        runCatching {
                            LocalDateTime.parse(raw, fmt).atZone(zone).toInstant().toEpochMilli()
                        }.getOrNull()
                    }
                    onSubmit(clean, notes.trim(), dueMillis, priority, category, recurrence, reminderEnabled)
                }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun EmailInboxContent(
    emails: List<EmailAlertItem>,
    totalCount: Int,
    unreadCount: Int,
    urgentCount: Int,
    emailFilter: String,
    onSelectFilter: (String) -> Unit,
    notificationAccessGranted: Boolean,
    statusToast: String?,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppInfoSettings: () -> Unit,
    onRefresh: () -> Unit,
    onMarkRead: (String) -> Unit,
    onMarkAllRead: () -> Unit,
    onConvertToTask: (EmailAlertItem) -> Unit,
    onDelete: (String) -> Unit,
    onClearAll: () -> Unit,
    onAskClaraAboutInbox: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = "Email Inbox",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Private reader · OTPs auto-filtered",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onRefresh,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Refresh", maxLines = 1, softWrap = false)
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

        // Notification Access Status & Privacy Card
        item {
            ExecutiveCard(
                borderColor = if (notificationAccessGranted) EmeraldAccent.copy(alpha = 0.45f) else AccentGold.copy(alpha = 0.55f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (notificationAccessGranted) Icons.Default.Shield else Icons.Default.Email,
                        contentDescription = null,
                        tint = if (notificationAccessGranted) EmeraldAccent else AccentGold,
                        modifier = Modifier.size(20.dp)
                    )
                    SectionHeader(
                        title = if (notificationAccessGranted) "Email Listener Active" else "Enable On-Device Email Reader",
                        subtitle = if (notificationAccessGranted) {
                            "$unreadCount unread · $urgentCount urgent · Gmail, Outlook & Edison Mail"
                        } else {
                            "Reads only email app notifications locally — zero cloud servers"
                        }
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!notificationAccessGranted) {
                    Text(
                        text = "1) On ColorOS 17 / Android 17, if 'Personal Secretary' is greyed out as a Restricted setting, tap Unlock App Info below -> top-right menu (⋮) -> Allow restricted settings.\n2) Tap Enable Access and turn on Personal Secretary Email Reader.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onOpenNotificationAccessSettings,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Enable Access", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(
                            onClick = onOpenAppInfoSettings,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unlock App Info", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Text(
                        text = "Privacy Shield: Only allowlisted email apps (Gmail, Outlook, Edison Mail, etc.) are captured. OTPs, 2FA codes, SMS, and chat apps are automatically ignored.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onAskClaraAboutInbox,
                            colors = ButtonDefaults.buttonColors(containerColor = AccentGold, contentColor = ObsidianBg),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Summarize Inbox", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                        if (unreadCount > 0) {
                            OutlinedButton(
                                onClick = onMarkAllRead,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Read All", maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }

        // Filter Chips + Clear
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    "ALL" to "All ($totalCount)",
                    "UNREAD" to "Unread ($unreadCount)",
                    "BILL" to "Bills",
                    "WORK" to "Work",
                    "MEETING" to "Meetings"
                ).forEach { (key, label) ->
                    FilterChip(
                        selected = emailFilter == key,
                        onClick = { onSelectFilter(key) },
                        label = { Text(label, maxLines = 1, softWrap = false) }
                    )
                }
                if (totalCount > 0) {
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = CoralAccent, maxLines = 1, softWrap = false)
                    }
                }
            }
        }

        if (emails.isEmpty()) {
            item {
                ExecutiveCard {
                    Text(
                        text = if (notificationAccessGranted) {
                            "No email notifications captured in this filter yet. New Gmail, Outlook, or Edison Mail alerts will appear here automatically."
                        } else {
                            "Enable Notification Access above to start capturing incoming Gmail, Outlook & Edison Mail alerts privately on-device."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted
                    )
                }
            }
        } else {
            items(emails, key = { it.id }) { item ->
                EmailAlertRowCard(
                    alert = item,
                    onMarkRead = { onMarkRead(item.id) },
                    onConvertToTask = { onConvertToTask(item) },
                    onDelete = { onDelete(item.id) }
                )
            }
        }
    }
}

@Composable
private fun EmailAlertRowCard(
    alert: EmailAlertItem,
    onMarkRead: () -> Unit,
    onConvertToTask: () -> Unit,
    onDelete: () -> Unit
) {
    val tagColor = when (alert.tag) {
        EmailAlertTag.BILL_FINANCE -> CoralAccent
        EmailAlertTag.WORK_ACTION -> AccentGold
        EmailAlertTag.MEETING_SCHEDULE -> SkyAccent
        EmailAlertTag.GENERAL -> TextMuted
    }
    val border = when {
        !alert.isRead && alert.isUrgent -> CoralAccent.copy(alpha = 0.55f)
        !alert.isRead -> AccentGold.copy(alpha = 0.45f)
        else -> CardBorder
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = CardSurfaceElevated,
        border = BorderStroke(1.dp, border),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${alert.appLabel} · ${alert.tag.label}${if (alert.isUrgent) " · URGENT" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = tagColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = ExecutiveAnalyticsEngine.formatMillisDateTime(alert.receivedAtMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
            }
            Text(
                text = alert.sender,
                style = MaterialTheme.typography.labelMedium,
                color = if (alert.isRead) TextMuted else TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = alert.subject,
                style = MaterialTheme.typography.titleSmall,
                color = if (alert.isRead) TextSecondary else TextPrimary,
                fontWeight = if (alert.isRead) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (alert.snippet.isNotBlank()) {
                Text(
                    text = alert.snippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onConvertToTask,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.AddTask, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Task", style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                    }
                    if (!alert.isRead) {
                        TextButton(
                            onClick = onMarkRead,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("Mark Read", style = MaterialTheme.typography.labelMedium, color = AccentGold)
                        }
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete alert",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
