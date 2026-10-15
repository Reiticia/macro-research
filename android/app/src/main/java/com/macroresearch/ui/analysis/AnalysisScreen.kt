package com.macroresearch.ui.analysis

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.macroresearch.R
import com.macroresearch.data.MacroRepository
import com.macroresearch.data.model.AnalysisReport
import com.macroresearch.data.model.EconomicEvent
import com.macroresearch.data.model.MarketResponse
import com.macroresearch.data.model.MarketSnapshot
import com.macroresearch.data.model.TransmissionStep
import com.macroresearch.data.model.hasEventTimeArrived
import com.macroresearch.ui.AiAnalysisState
import com.macroresearch.ui.AnalysisViewModel
import com.macroresearch.ui.common.LoadingHint
import com.macroresearch.ui.common.analysisSummaryLabel
import com.macroresearch.ui.common.appLocale
import com.macroresearch.ui.common.assetLabel
import com.macroresearch.ui.common.expectedRationaleLabel
import com.macroresearch.ui.common.localizedChange as formatChange
import com.macroresearch.ui.common.localizedName
import com.macroresearch.ui.common.localizedValue as value
import com.macroresearch.ui.common.macroSignalLabel
import com.macroresearch.ui.common.statusLabel
import com.macroresearch.ui.theme.AssetDown
import com.macroresearch.ui.theme.AssetUp
import com.macroresearch.ui.theme.Dovish
import com.macroresearch.ui.theme.Hawkish
import com.macroresearch.ui.theme.ResearchLayout
import com.macroresearch.ui.theme.TabularStyle
import com.macroresearch.ui.viewModelFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(id: Long, repository: MacroRepository, onBack: () -> Unit) {
    val vm: AnalysisViewModel = viewModel(key = "analysis-$id", factory = viewModelFactory { AnalysisViewModel(id, repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val ai by vm.ai.collectAsStateWithLifecycle()
    val settings by repository.translationSettings.collectAsStateWithLifecycle()
    val languageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val method by repository.analysisMethod.collectAsStateWithLifecycle()
    val dataSource by repository.dataSourceSettings.collectAsStateWithLifecycle()
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(id, languageTag, method, settings.configured, dataSource) {
        if (settings.configured) {
            vm.loadAi(languageTag)
        } else if (dataSource.baseUrl.isNotBlank()) {
            vm.loadAi(languageTag)
        }
    }
    LaunchedEffect(settings.configured, method) { vm.loadConversation() }
    LaunchedEffect(id, languageTag, lifecycleState, state.event, ai.analysis?.revision) {
        vm.scheduleWindowEndRefresh(languageTag, lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            state.event?.localizedName(LocalConfiguration.current.locales[0]) ?: stringResource(R.string.analysis),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                )
                HorizontalDivider(
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        },
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }
            state.event == null -> Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
                Text(stringResource(R.string.event_load_failed))
                Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
            }
            state.report == null -> AnalysisPending(state.event!!, Modifier.padding(padding), state.error, vm::refresh)
            else -> AnalysisContent(
                event = state.event!!,
                report = state.report!!,
                market = state.market,
                ai = ai,
                aiConfigured = settings.configured,
                sharedFeedbackEnabled = dataSource.configured,
                onGenerateAi = { vm.generateAiAnalysis(languageTag) },
                onFeedback = { vm.feedback(languageTag, it) },
                onRefreshShared = { vm.refreshSharedAi(languageTag) },
                conversation = conversation,
                onAsk = { vm.askFollowUp(languageTag, it) },
                onResetConversation = vm::resetConversation,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun AnalysisPending(event: EconomicEvent, modifier: Modifier, error: String?, retry: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        val historical = event.status == "historical"
        if (!historical) CircularProgressIndicator()
        Text(
            stringResource(if (historical) R.string.historical_pending else R.string.observing_market),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Text(stringResource(R.string.current_status, statusLabel(event.status)), color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(if (historical) R.string.historical_pending_body else R.string.analysis_pending_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = retry) { Text(stringResource(R.string.retry)) }
    }
}

@Composable
private fun AnalysisContent(
    event: EconomicEvent,
    report: AnalysisReport,
    market: MarketResponse?,
    ai: AiAnalysisState,
    aiConfigured: Boolean,
    sharedFeedbackEnabled: Boolean,
    onGenerateAi: () -> Unit,
    onFeedback: (String) -> Unit,
    onRefreshShared: () -> Unit,
    conversation: com.macroresearch.ui.AiConversationState,
    onAsk: (String) -> Unit,
    onResetConversation: () -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier.fillMaxSize().padding(horizontal = ResearchLayout.pagePadding),
        contentPadding = PaddingValues(vertical = ResearchLayout.gap),
        verticalArrangement = Arrangement.spacedBy(ResearchLayout.gap),
    ) {
        item { ResultCard(event, report) }
        item { SignalCard(report) }
        item { ExpectedCard(report) }
        item { ObservedTable(report) }
        if (report.historical != null) {
            item { HistoricalCoverageCard(report.historical) }
        } else {
            item { ReactionTimeline(event, market) }
        }
        item {
            AiAnalysisCard(
                event,
                ai,
                aiConfigured,
                sharedFeedbackEnabled,
                onGenerateAi,
                onFeedback,
                onRefreshShared,
                conversation,
                onAsk,
                onResetConversation,
            )
        }
        item {
            Text(
                analysisSummaryLabel(report.summary),
                Modifier.padding(bottom = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .width(3.5.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun AiAnalysisCard(
    event: EconomicEvent,
    ai: AiAnalysisState,
    configured: Boolean,
    sharedFeedbackEnabled: Boolean,
    onGenerate: () -> Unit,
    onFeedback: (String) -> Unit,
    onRefreshShared: () -> Unit,
    conversation: com.macroresearch.ui.AiConversationState,
    onAsk: (String) -> Unit,
    onResetConversation: () -> Unit,
) {
    var question by remember(event.id) { mutableStateOf("") }
    var feedbackText by remember(ai.analysis?.revision, ai.analysis?.method) { mutableStateOf("") }
    var now by remember(event.id) { mutableStateOf(Instant.now()) }
    LaunchedEffect(event.id) { while (true) { now = Instant.now(); delay(1_000) } }
    val eventTimeReached = hasEventTimeArrived(event.eventTime, now)
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(stringResource(R.string.ai_analysis))
                if (configured && ai.analysis != null && !ai.loading) {
                    TextButton(onClick = onGenerate) { Text(stringResource(R.string.ai_reanalyze)) }
                }
                if (!configured && ai.analysis != null && !ai.loading) {
                    TextButton(onClick = onRefreshShared) { Text(stringResource(R.string.retry)) }
                }
            }
            if (eventTimeReached && event.actual == null) {
                AiHint(stringResource(R.string.ai_missing_release_data))
            }
            when {
                !eventTimeReached -> AiHint(stringResource(R.string.ai_waiting_for_event))
                ai.loading -> LoadingHint()
                ai.analysis == null && !configured -> AiHint(stringResource(R.string.ai_shared_pending))
                ai.analysis == null -> {
                    AiHint(stringResource(R.string.ai_analysis_hint))
                    Button(
                        onClick = onGenerate,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(ResearchLayout.innerCorner),
                    ) {
                        Text(stringResource(R.string.ai_analyze))
                    }
                }
                else -> {
                    val analysis = ai.analysis
                    if (analysis.rateLimited) {
                        AiHint(
                            stringResource(
                                R.string.ai_rate_limited,
                                analysis.retryAfterSeconds ?: 0L,
                            ),
                        )
                    }
                    if (analysis.chain.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(ResearchLayout.innerCorner),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.ai_chain),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                analysis.chain.forEach { step -> ChainStepRow(step) }
                            }
                        }
                    }
                    AiSection(stringResource(R.string.ai_data_analysis), analysis.dataAnalysis)
                    AiSection(stringResource(R.string.ai_market_outlook), analysis.marketOutlook)
                    analysis.risks?.takeIf(String::isNotBlank)?.let { AiSection(stringResource(R.string.ai_risks), it) }
                    Text(
                        stringResource(R.string.ai_generated_at, generatedAtLabel(analysis.generatedAt), analysis.model, analysis.revision),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (configured) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(stringResource(R.string.ai_conversation), fontWeight = FontWeight.Bold)
                        conversation.messages.forEach { message ->
                            Text(
                                if (message.role == "user") stringResource(R.string.ai_you, message.content)
                                else stringResource(R.string.ai_assistant, message.content),
                            )
                        }
                        conversation.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        OutlinedTextField(
                            value = question,
                            onValueChange = { if (it.length <= 2000) question = it },
                            label = { Text(stringResource(R.string.ai_followup_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !conversation.loading,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onAsk(question); question = "" },
                                enabled = question.isNotBlank() && !conversation.loading,
                            ) {
                                Text(stringResource(if (conversation.loading) R.string.ai_followup_loading else R.string.ai_followup_send))
                            }
                            if (conversation.messages.isNotEmpty()) {
                                TextButton(onClick = onResetConversation) {
                                    Text(stringResource(R.string.ai_reset_conversation))
                                }
                            }
                        }
                    }
                    analysis.usageSummary()?.let { usage ->
                        Text(
                            stringResource(R.string.ai_usage, usage),
                            style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            ai.error?.let {
                Text(
                    stringResource(R.string.ai_analysis_failed, it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (!configured && ai.analysis != null && !ai.loading) {
                AiHint(stringResource(R.string.ai_shared_note))
                if (sharedFeedbackEnabled) {
                    if (ai.feedbackSent) {
                        AiHint(stringResource(R.string.ai_feedback_sent))
                    } else {
                        OutlinedTextField(
                            value = feedbackText,
                            onValueChange = { if (it.length <= 2000) feedbackText = it },
                            label = { Text(stringResource(R.string.ai_feedback)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(onClick = { onFeedback(feedbackText) }, enabled = feedbackText.isNotBlank()) {
                            Text(stringResource(R.string.ai_feedback))
                        }
                    }
                }
            }
            if (configured && ai.error != null && ai.analysis != null) {
                TextButton(onClick = onGenerate) { Text(stringResource(R.string.retry)) }
            }
        }
    }
}

@Composable
private fun AiHint(text: String) = Text(
    text,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    style = MaterialTheme.typography.bodySmall,
)

@Composable
private fun AiSection(title: String, body: String) {
    if (body.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChainStepRow(step: TransmissionStep) {
    val arrow = when (step.direction) {
        "up" -> "↑"
        "down" -> "↓"
        else -> "→"
    }
    val color = when (step.direction) {
        "up" -> AssetUp
        "down" -> AssetDown
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val verdict = step.verdict?.let { value ->
        when (value) {
            "confirmed" -> "✓" to AssetUp
            "contradicted" -> "✗" to AssetDown
            else -> "?" to MaterialTheme.colorScheme.onSurfaceVariant
        }
    }
    val verdictLabel = step.verdict?.let {
        stringResource(
            when (it) {
                "confirmed" -> R.string.verdict_confirmed
                "contradicted" -> R.string.verdict_contradicted
                else -> R.string.verdict_unobserved
            },
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${step.from} $arrow ${step.to}", fontWeight = FontWeight.Bold, color = color, style = MaterialTheme.typography.bodyMedium)
                verdict?.let { (glyph, tint) ->
                    Text(
                        glyph,
                        color = tint,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.semantics { verdictLabel?.let { contentDescription = it } },
                    )
                }
            }
            if (step.rationale.isNotBlank()) {
                Text(step.rationale, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun generatedAtLabel(iso: String): String {
    val locale = appLocale()
    return remember(iso, locale) {
        runCatching {
            Instant.parse(iso).atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale))
        }.getOrDefault(iso)
    }
}

@Composable
private fun ResultCard(event: EconomicEvent, report: AnalysisReport) {
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SectionHeader(stringResource(R.string.data_results))
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
            ) {
                MetricCell(stringResource(R.string.actual), event.value(event.actual), Modifier.weight(1f), true)
                VerticalDividerLine()
                MetricCell(stringResource(R.string.consensus), event.value(event.consensus), Modifier.weight(1f))
                VerticalDividerLine()
                MetricCell(stringResource(R.string.previous), event.value(event.previous), Modifier.weight(1f))
                VerticalDividerLine()
                MetricCell(
                    stringResource(R.string.surprise),
                    report.rawSurprise?.let { (if (it.startsWith("-")) "" else "+") + it + if (event.unit == "%") "%" else "" } ?: "--",
                    Modifier.weight(1f),
                    true,
                )
            }
        }
    }
}

@Composable
private fun VerticalDividerLine() {
    Box(
        modifier = Modifier
            .width(0.5.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun MetricCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) = Column(
    modifier = modifier.padding(horizontal = 10.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        value,
        style = MaterialTheme.typography.titleSmall.merge(TabularStyle),
        fontWeight = FontWeight.ExtraBold,
        color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
    )
}

@Composable
private fun SignalCard(report: AnalysisReport) {
    val hawkish = report.macroSignal.contains("hawkish")
    val color = if (hawkish) Hawkish else if (report.macroSignal.contains("dovish")) Dovish else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = .08f).compositeOver(MaterialTheme.colorScheme.surface),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, color.copy(alpha = .45f)),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(ResearchLayout.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                macroSignalLabel(report.macroSignal),
                style = MaterialTheme.typography.titleLarge,
                color = color,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                stringResource(R.string.signal_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpectedCard(report: AnalysisReport) {
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeader(stringResource(R.string.expected_reactions))
            report.expectedReactions.forEach { reaction ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        assetLabel(reaction.symbol),
                        Modifier.width(96.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (reaction.direction == "up") "↑" else if (reaction.direction == "down") "↓" else "→",
                        color = if (reaction.direction == "up") AssetUp else if (reaction.direction == "down") AssetDown else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp),
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        expectedRationaleLabel(reaction.rationale),
                        Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun ObservedTable(report: AnalysisReport) {
    val comparisons = report.comparisons.associateBy { it.symbol }
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            Modifier.padding(ResearchLayout.cardPadding).horizontalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionHeader(stringResource(R.string.observed_reactions))
            Row(Modifier.width(760.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.asset), Modifier.width(100.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(1, 5, 15, 30, 60).forEach {
                    Text(
                        stringResource(R.string.minutes_short, it),
                        Modifier.width(100.dp),
                        style = MaterialTheme.typography.labelSmall.merge(TabularStyle),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(stringResource(R.string.conformity), Modifier.width(100.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            report.observedReactions.forEach { reaction ->
                Row(
                    Modifier.width(760.dp).padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val values = listOf(
                        assetLabel(reaction.symbol),
                        formatChange(reaction.change1m, reaction.reactionUnit),
                        formatChange(reaction.change5m, reaction.reactionUnit),
                        formatChange(reaction.change15m, reaction.reactionUnit),
                        formatChange(reaction.change30m, reaction.reactionUnit),
                        formatChange(reaction.change60m, reaction.reactionUnit),
                    )
                    values.forEachIndexed { index, value ->
                        Text(
                            value,
                            Modifier.width(100.dp),
                            style = if (index == 0) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                            fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (index > 0 && value.startsWith("+")) AssetUp else if (index > 0 && value.startsWith("-")) AssetDown else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    val conforms = comparisons[reaction.symbol]?.conforms
                    Text(
                        if (conforms == null) "--" else stringResource(if (conforms) R.string.conforms else R.string.diverges),
                        Modifier.width(100.dp),
                        fontWeight = if (conforms != null) FontWeight.Bold else FontWeight.Normal,
                        color = if (conforms == true) AssetUp else if (conforms == false) AssetDown else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReactionTimeline(event: EconomicEvent, market: MarketResponse?) {
    var minute by remember { mutableFloatStateOf(5f) }
    val samples = market?.snapshots.orEmpty()
    Card(
        shape = RoundedCornerShape(ResearchLayout.cardCorner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(ResearchLayout.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionHeader(stringResource(R.string.reaction_timeline))
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        stringResource(if (minute < 0) R.string.before_release else R.string.after_release, abs(minute.toInt())),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium.merge(TabularStyle),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Slider(value = minute, onValueChange = { minute = it }, valueRange = -5f..60f, steps = 64)
            listOf("gold", "dxy", "us2y", "nasdaq100", "bitcoin").forEach { symbol ->
                val change = timelineChange(event.eventTime, samples.filter { it.symbol == symbol }, minute.toInt(), symbol in setOf("us2y", "us10y"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(assetLabel(symbol), fontWeight = FontWeight.SemiBold)
                    Text(
                        formatChange(change, if (symbol.startsWith("us") && symbol.endsWith("y")) "basis_points" else "percent"),
                        style = MaterialTheme.typography.bodyMedium.merge(TabularStyle),
                        fontWeight = if (change != null) FontWeight.Bold else FontWeight.Normal,
                        color = when {
                            change == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            change >= 0 -> AssetUp
                            else -> AssetDown
                        },
                    )
                }
            }
        }
    }
}

private fun timelineChange(eventTime: String, values: List<MarketSnapshot>, minute: Int, yield: Boolean): Double? {
    val event = runCatching { Instant.parse(eventTime) }.getOrNull() ?: return null
    val baselineTarget = event.minusSeconds(60)
    val target = event.plusSeconds(minute * 60L)
    val baseline = values.minByOrNull { abs(Duration.between(baselineTarget, Instant.parse(it.timestamp)).seconds) } ?: return null
    val sample = values.minByOrNull { abs(Duration.between(target, Instant.parse(it.timestamp)).seconds) } ?: return null
    if (abs(Duration.between(target, Instant.parse(sample.timestamp)).seconds) > 180 || baseline.price == 0.0) return null
    return if (yield) (sample.price - baseline.price) * 100 else (sample.price - baseline.price) / baseline.price * 100
}
