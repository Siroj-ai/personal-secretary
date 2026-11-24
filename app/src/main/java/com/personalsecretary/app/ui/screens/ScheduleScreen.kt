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
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
    calendarPermissionGranted: Boolean,
    statusToast: String?,
    onRequestCalendarPermission: () -> Unit,
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
    onAskClaraAboutSchedule: () -> Unit
) {
    var filter by remember { mutableStateOf("OPEN") } // OPEN, TODAY, WORK, FINANCE, DONE
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

    val futureEvents = remember(snapshot.upcomingEvents7d, snapshot.todayEvents) {
        val todayKeys = snapshot.todayEvents.map { "${it.id}_${it.startMillis}" }.toSet()
        snapshot.upcomingEvents7d.filterNot { "${it.id}_${it.startMillis}" in todayKeys }.take(8)
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
                        text = "Schedule & Work",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Calendar agenda & exact-time reminders",
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
            val timeStr = if (isToday) {
                ExecutiveAnalyticsEngine.formatEventTimeRange(event)
            } else {
                ExecutiveAnalyticsEngine.formatMillisDateTime(event.startMillis)
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
