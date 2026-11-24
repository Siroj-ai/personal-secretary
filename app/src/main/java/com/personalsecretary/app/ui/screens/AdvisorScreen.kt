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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.personalsecretary.app.data.ChatMessage
import com.personalsecretary.app.data.ChatRole
import com.personalsecretary.app.data.SecretaryProfile
import com.personalsecretary.app.data.SecretaryVideoSlot
import com.personalsecretary.app.media.SecretaryPresenceBanner
import com.personalsecretary.app.ui.components.formatExecutiveMarkdown
import com.personalsecretary.app.ui.theme.AccentGold
import com.personalsecretary.app.ui.theme.AccentGoldSoft
import com.personalsecretary.app.ui.theme.CardBorder
import com.personalsecretary.app.ui.theme.CardSurface
import com.personalsecretary.app.ui.theme.CardSurfaceElevated
import com.personalsecretary.app.ui.theme.CoralAccent
import com.personalsecretary.app.ui.theme.EmeraldAccent
import com.personalsecretary.app.ui.theme.ObsidianBg
import com.personalsecretary.app.ui.theme.TextMuted
import com.personalsecretary.app.ui.theme.TextPrimary
import com.personalsecretary.app.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun AdvisorScreen(
    profile: SecretaryProfile,
    messages: List<ChatMessage>,
    streamingReply: String,
    isGenerating: Boolean,
    engineStatusLabel: String,
    isGemmaReady: Boolean,
    activeVideoSlot: SecretaryVideoSlot,
    videoSlots: Map<String, String>,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onClearChat: () -> Unit,
    onToggleVoiceBriefing: () -> Unit,
    onToggleVideoMute: () -> Unit,
    onOpenBrainSetup: () -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val quickPrompts = remember {
        listOf(
            "Summarize my recent emails",
            "Brief my schedule & work today",
            "Full financial & health audit",
            "Where am I leaking money?",
            "Check my SSF & Credit Card",
            "Review my steps & watch health"
        )
    }

    LaunchedEffect(messages.size, streamingReply.length) {
        val total = messages.size + if (streamingReply.isNotEmpty()) 1 else 0
        if (total > 0) {
            listState.animateScrollToItem(total - 1)
        }
    }

    val resolvedVideoPath = videoSlots[activeVideoSlot.slotKey] ?: videoSlots[SecretaryVideoSlot.IDLE.slotKey]

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${profile.secretaryName} · Private Advisor",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary
                )
                Text(
                    text = engineStatusLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isGemmaReady) EmeraldAccent else AccentGold
                )
            }
            Row {
                IconButton(onClick = onToggleVoiceBriefing) {
                    Icon(
                        imageVector = if (profile.voiceBriefingEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = "Toggle spoken responses",
                        tint = if (profile.voiceBriefingEnabled) AccentGold else TextMuted
                    )
                }
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Default.DeleteSweep,
                        contentDescription = "Clear conversation",
                        tint = TextMuted
                    )
                }
            }
        }

        if (profile.showVideoPresence && resolvedVideoPath != null) {
            Spacer(Modifier.height(8.dp))
            SecretaryPresenceBanner(
                secretaryName = profile.secretaryName,
                activeSlot = activeVideoSlot,
                videoPath = resolvedVideoPath,
                statusBadge = when {
                    isGenerating && streamingReply.isEmpty() -> "Reviewing Dossier..."
                    isGenerating -> "Briefing Live"
                    else -> engineStatusLabel
                },
                isMuted = profile.videoMuted,
                onToggleMute = onToggleVideoMute,
                height = 132.dp
            )
        }

        if (!isGemmaReady) {
            Spacer(Modifier.height(8.dp))
            Surface(
                onClick = onOpenBrainSetup,
                shape = RoundedCornerShape(12.dp),
                color = AccentGoldSoft,
                border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Deterministic Consultant active. Tap here to load gemma-4-E4B-it.litertlm for full on-device AI reasoning.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AccentGold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick executive prompts
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            quickPrompts.forEach { prompt ->
                AssistChip(
                    onClick = { if (!isGenerating) onSendMessage(prompt) },
                    label = { Text(prompt, style = MaterialTheme.typography.labelMedium) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Chat Transcript
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 6.dp)
        ) {
            if (messages.isEmpty() && streamingReply.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardSurface,
                        border = BorderStroke(1.dp, CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Ready for your consultation, ${profile.userName}.",
                                style = MaterialTheme.typography.titleMedium,
                                color = AccentGold
                            )
                            Text(
                                text = "Ask about your calendar meetings, work priorities, Day Book cash flow, Credit Card & SSF position, or your OnePlus 13 steps and watch health vitals. Everything runs privately on-device.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                ChatBubble(message = msg)
            }

            if (isGenerating) {
                item(key = "streaming_live") {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CardSurfaceElevated,
                        border = BorderStroke(1.dp, AccentGold.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentGold
                                )
                                Text(
                                    text = if (streamingReply.isEmpty()) "Reviewing your financial & health dossier..." else "${profile.secretaryName} is responding",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AccentGold
                                )
                            }
                            if (streamingReply.isNotEmpty()) {
                                Text(
                                    text = formatExecutiveMarkdown(
                                        raw = streamingReply,
                                        boldColor = TextPrimary,
                                        headingColor = AccentGold
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Composer
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Ask your secretary...") },
                shape = RoundedCornerShape(16.dp),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            if (isGenerating) {
                Surface(
                    onClick = onStopGeneration,
                    shape = RoundedCornerShape(14.dp),
                    color = CoralAccent,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = ObsidianBg,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Surface(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            onSendMessage(text)
                        }
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = AccentGold,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = ObsidianBg,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.USER
    val bg = if (isUser) AccentGoldSoft else CardSurfaceElevated
    val border = if (isUser) AccentGold.copy(alpha = 0.55f) else CardBorder

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth(if (isUser) 0.86f else 0.96f)
        ) {
            Column(modifier = Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (isUser) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                } else {
                    val formatted = remember(message.content) {
                        formatExecutiveMarkdown(
                            raw = message.content,
                            boldColor = TextPrimary,
                            headingColor = AccentGold
                        )
                    }
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
                if (!isUser) {
                    val speed = message.tokensPerSec?.let {
                        " · ${String.format(Locale.US, "%.1f", it)} tok/s"
                    }.orEmpty()
                    Text(
                        text = "${message.engineTag}$speed",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}
