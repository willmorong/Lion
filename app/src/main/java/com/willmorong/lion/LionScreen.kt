package com.willmorong.lion

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.willmorong.lion.data.ImportedItem
import com.willmorong.lion.ui.theme.LionTheme
import kotlin.math.roundToLong

@Composable
fun LionRoute(
    viewModel: LionViewModel = viewModel(factory = LionViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let(viewModel::onDestinationSelected)
    }

    LionScreen(
        state = state,
        onCodeChange = viewModel::updateCode,
        onPickDestination = { folderPicker.launch(state.destination?.uri) },
        onStart = viewModel::startTransfer,
        onCancel = viewModel::cancelTransfer,
        onReset = viewModel::resetSession,
        onViewInFolder = {
            openTransferredItem(
                context = context,
                destinationUri = state.destination?.uri,
                importedItem = state.importedItem,
                transferKind = state.transferKind,
            )
        },
    )
}

@Composable
private fun LionScreen(
    state: LionUiState,
    onCodeChange: (String) -> Unit,
    onPickDestination: () -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onViewInFolder: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
    ) {
        val wideLayout = maxWidth >= 920.dp
        val isTransferStep = state.phase != TransferPhase.Idle
        val pageMaxWidth = if (wideLayout && isTransferStep) 980.dp else 760.dp

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                        ),
                    ),
                ),
        ) {
            DecorativeGlow(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 16.dp, end = 10.dp)
                    .size(280.dp),
                colors = listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    Color.Transparent,
                ),
            )
            DecorativeGlow(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 32.dp, start = 8.dp)
                    .size(320.dp),
                colors = listOf(
                    MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                    Color.Transparent,
                ),
            )

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent,
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(
                            horizontal = if (wideLayout) 36.dp else 20.dp,
                            vertical = 20.dp,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Crossfade(
                        targetState = isTransferStep,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(),
                        label = "lionWizardPage",
                    ) { showTransferPage ->
                        if (showTransferPage) {
                            TransferPage(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .widthIn(max = pageMaxWidth),
                                state = state,
                                wideLayout = wideLayout,
                                onCancel = onCancel,
                                onReset = onReset,
                                onViewInFolder = onViewInFolder,
                            )
                        } else {
                            SetupPage(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight()
                                    .widthIn(max = 920.dp),
                                state = state,
                                wideLayout = wideLayout,
                                onCodeChange = onCodeChange,
                                onPickDestination = onPickDestination,
                                onStart = onStart,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WizardStepStrip(
    currentStep: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WizardStepCard(
            modifier = Modifier.weight(1f),
            number = "1",
            label = "Code + folder",
            stateLabel = if (currentStep == 1) "Current step" else "Complete",
            isActive = currentStep == 1,
            isComplete = currentStep > 1,
        )
        WizardStepCard(
            modifier = Modifier.weight(1f),
            number = "2",
            label = "Transfer",
            stateLabel = if (currentStep == 2) "Current step" else "Up next",
            isActive = currentStep == 2,
            isComplete = false,
        )
    }
}

@Composable
private fun WizardStepCard(
    modifier: Modifier = Modifier,
    number: String,
    label: String,
    stateLabel: String,
    isActive: Boolean,
    isComplete: Boolean,
) {
    val background = when {
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        isComplete -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    val borderColor = when {
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        isComplete -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.3f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = background,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(24.dp),
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive || isComplete) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = number,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else if (isComplete) {
                        MaterialTheme.colorScheme.tertiary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SetupPage(
    modifier: Modifier = Modifier,
    state: LionUiState,
    wideLayout: Boolean,
    onCodeChange: (String) -> Unit,
    onPickDestination: () -> Unit,
    onStart: () -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            WizardStepStrip(currentStep = 1)

            if (wideLayout) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    SetupLeadPanel(
                        modifier = Modifier.weight(0.95f),
                    )
                    SetupFormPanel(
                        modifier = Modifier.weight(1.05f),
                        state = state,
                        onCodeChange = onCodeChange,
                        onPickDestination = onPickDestination,
                        onStart = onStart,
                    )
                }
            } else {
                SetupLeadPanel(modifier = Modifier.fillMaxWidth())
                SetupFormPanel(
                    modifier = Modifier.fillMaxWidth(),
                    state = state,
                    onCodeChange = onCodeChange,
                    onPickDestination = onPickDestination,
                    onStart = onStart,
                )
            }
        }
    }
}

@Composable
private fun SetupLeadPanel(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusPill(
            label = "lion",
            background = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            contentColor = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Step 1: Enter the code and choose a destination",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "This page only asks for the essentials. After you continue, Lion switches to a dedicated transfer page for live progress and the final copy.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DetailChip("One-time code")
            DetailChip("Remembered folder")
        }
    }
}

@Composable
private fun SetupFormPanel(
    modifier: Modifier = Modifier,
    state: LionUiState,
    onCodeChange: (String) -> Unit,
    onPickDestination: () -> Unit,
    onStart: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Wormhole code") },
            placeholder = { Text("7-purple-seabird") },
            singleLine = true,
            supportingText = {
                Text("Paste the code shown by the sending device.")
            },
            shape = RoundedCornerShape(22.dp),
        )

        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Destination folder",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = state.destination?.label ?: "Choose where Lion should copy the finished download",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = state.destination?.let {
                        "Lion will copy the finished receive into ${it.label}, and remember that location for next time."
                    } ?: "Pick a folder once and Lion will keep it ready for future receives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilledTonalButton(
                    onClick = onPickDestination,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        text = if (state.destination == null) {
                            "Choose folder"
                        } else {
                            "Change folder"
                        },
                    )
                }
            }
        }

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canStart,
            shape = RoundedCornerShape(20.dp),
        ) {
            Text("Next")
        }
    }
}

@Composable
private fun TransferPage(
    modifier: Modifier = Modifier,
    state: LionUiState,
    wideLayout: Boolean,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onViewInFolder: () -> Unit,
) {
    val progressTarget = when {
        state.phase == TransferPhase.Success -> 1f
        state.totalBytes != null && state.totalBytes > 0L -> {
            (state.receivedBytes.toFloat() / state.totalBytes.toFloat()).coerceIn(0f, 1f)
        }

        else -> null
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progressTarget ?: 0f,
        label = "lionProgress",
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(36.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(28.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            WizardStepStrip(currentStep = 2)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatusPill(
                        label = "Step 2 of 2",
                        background = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = state.statusHeadline,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = when (state.phase) {
                            TransferPhase.Success -> "The receive is complete. You can open the saved result directly from here."
                            TransferPhase.Error, TransferPhase.Canceled -> "This page keeps the outcome and recovery actions together so you can return to setup quickly."
                            else -> "This page is dedicated to the live receive: connection, progress, speed, and the final copy into your folder."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                StatusPill(
                    label = state.phase.name.lowercase(),
                    background = phaseBackgroundColor(state.phase),
                    contentColor = phaseContentColor(state.phase),
                )
            }

            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Receive status",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    when {
                        progressTarget != null -> {
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                            )
                        }

                        state.isTransferRunning -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(999.dp)),
                            )
                        }
                    }

                    Text(
                        text = when {
                            state.phase == TransferPhase.Importing ->
                                "The secure download is complete. Lion is copying the result into ${state.destination?.label ?: "your selected folder"}."
                            state.phase == TransferPhase.Success ->
                                "The transfer is fully copied and ready to open."
                            progressTarget != null ->
                                "${(animatedProgress * 100).roundToInt()}% of the download has arrived."
                            state.isTransferRunning ->
                                "Lion is still establishing the receive and waiting for byte counts."
                            else ->
                                "This receive is no longer running."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TransferStatsGrid(
                metrics = buildTransferMetrics(state),
                wideLayout = wideLayout,
            )

            state.importedItem?.let { importedItem ->
                SuccessCallout(
                    importedItem = importedItem,
                    destinationLabel = state.destination?.label,
                )
            }

            state.errorMessage?.let { errorMessage ->
                ErrorCallout(errorMessage = errorMessage)
            }

            when {
                state.isTransferRunning -> {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Cancel transfer")
                    }
                }

                state.phase == TransferPhase.Success -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = onReset,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("Receive another")
                        }
                        Button(
                            onClick = onViewInFolder,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(20.dp),
                        ) {
                            Text("View in Folder")
                        }
                    }
                }

                else -> {
                    Button(
                        onClick = onReset,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text("Back to setup")
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferStatsGrid(
    metrics: List<TransferMetric>,
    wideLayout: Boolean,
) {
    if (wideLayout) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            metrics.chunked(2).forEach { rowMetrics ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowMetrics.forEach { metric ->
                        MetricTile(
                            modifier = Modifier.weight(1f),
                            metric = metric,
                        )
                    }
                    if (rowMetrics.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            metrics.forEach { metric ->
                MetricTile(
                    modifier = Modifier.fillMaxWidth(),
                    metric = metric,
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    modifier: Modifier = Modifier,
    metric: TransferMetric,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.36f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = metric.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = metric.value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            metric.supportingText?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SuccessCallout(
    importedItem: ImportedItem,
    destinationLabel: String?,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.52f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Saved item",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = importedItem.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = destinationLabel?.let { "Copied into $it." }
                    ?: "Copied into your selected folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorCallout(
    errorMessage: String,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
    ) {
        Text(
            text = errorMessage,
            modifier = Modifier.padding(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun StatusPill(
    label: String,
    background: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = background,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

@Composable
private fun DetailChip(
    label: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DecorativeGlow(
    modifier: Modifier = Modifier,
    colors: List<Color>,
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Brush.radialGradient(colors = colors)),
    )
}

private data class TransferMetric(
    val label: String,
    val value: String,
    val supportingText: String? = null,
)

private fun buildTransferMetrics(
    state: LionUiState,
): List<TransferMetric> = buildList {
    add(
        TransferMetric(
            label = "Incoming item",
            value = state.transferName ?: "Waiting for the sender",
            supportingText = "Lion fills this in as soon as the sender offers the download.",
        ),
    )
    add(
        TransferMetric(
            label = "Payload type",
            value = state.transferKind?.replaceFirstChar(Char::uppercase) ?: "Pending",
            supportingText = "File or folder details appear after the secure handshake.",
        ),
    )
    add(
        TransferMetric(
            label = "Destination",
            value = state.destination?.label ?: "Not selected",
            supportingText = "The final copied result lands here.",
        ),
    )
    add(
        TransferMetric(
            label = "Progress",
            value = when {
                state.totalBytes != null ->
                    "${formatBytes(state.receivedBytes)} / ${formatBytes(state.totalBytes)}"
                state.receivedBytes > 0L ->
                    formatBytes(state.receivedBytes)
                state.phase == TransferPhase.Importing ->
                    "Download complete"
                else ->
                    "Waiting for data"
            },
            supportingText = when (state.phase) {
                TransferPhase.Importing -> "Lion is copying the finished download into your folder."
                TransferPhase.Success -> "The copy into your destination folder is complete."
                else -> "Securely received so far."
            },
        ),
    )
    add(
        TransferMetric(
            label = "Speed",
            value = formatSpeed(state.bytesPerSecond),
            supportingText = if (state.isTransferRunning) {
                "Current download speed"
            } else {
                "No active download right now"
            },
        ),
    )
    state.itemCount?.let { itemCount ->
        add(
            TransferMetric(
                label = "Folder items",
                value = "$itemCount files",
                supportingText = "Expanded file count from the offer.",
            ),
        )
    }
    state.expandedBytes?.let { expandedBytes ->
        add(
            TransferMetric(
                label = "Expanded size",
                value = formatBytes(expandedBytes),
                supportingText = "Estimated size once extracted by the sender.",
            ),
        )
    }
}

private fun openTransferredItem(
    context: Context,
    destinationUri: Uri?,
    importedItem: ImportedItem?,
    transferKind: String?,
) {
    val intents = listOfNotNull(
        destinationUri?.let(::buildOpenDestinationIntent),
        importedItem?.uri?.let { buildOpenImportedItemIntent(it, transferKind) },
    )

    val intent = intents.firstOrNull { candidate ->
        candidate.resolveActivity(context.packageManager) != null
    }

    if (intent != null) {
        context.startActivity(intent)
    } else {
        Toast.makeText(context, "Unable to open the saved folder.", Toast.LENGTH_SHORT).show()
    }
}

private fun buildOpenDestinationIntent(
    destinationUri: Uri,
): Intent? {
    val documentUri = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(
            destinationUri,
            DocumentsContract.getTreeDocumentId(destinationUri),
        )
    }.getOrNull() ?: return null

    return Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(documentUri, DocumentsContract.Document.MIME_TYPE_DIR)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

private fun buildOpenImportedItemIntent(
    importedItemUri: Uri,
    transferKind: String?,
): Intent {
    return Intent(Intent.ACTION_VIEW).apply {
        if (transferKind == "directory") {
            setDataAndType(importedItemUri, DocumentsContract.Document.MIME_TYPE_DIR)
        } else {
            setDataAndType(importedItemUri, "*/*")
        }
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

@Composable
private fun phaseBackgroundColor(
    phase: TransferPhase,
): Color = when (phase) {
    TransferPhase.Success -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    TransferPhase.Error -> MaterialTheme.colorScheme.error.copy(alpha = 0.14f)
    TransferPhase.Canceled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    else -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)
}

@Composable
private fun phaseContentColor(
    phase: TransferPhase,
): Color = when (phase) {
    TransferPhase.Success -> MaterialTheme.colorScheme.primary
    TransferPhase.Error -> MaterialTheme.colorScheme.error
    TransferPhase.Canceled -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.colorScheme.secondary
}

private fun formatBytes(value: Long?): String {
    if (value == null) {
        return "Unknown"
    }
    if (value < 1024) {
        return "$value B"
    }

    val units = listOf("KB", "MB", "GB", "TB")
    var scaled = value.toDouble()
    var unitIndex = -1
    while (scaled >= 1024 && unitIndex < units.lastIndex) {
        scaled /= 1024
        unitIndex += 1
    }
    return String.format("%.1f %s", scaled, units[unitIndex])
}

private fun formatSpeed(bytesPerSecond: Double): String {
    if (bytesPerSecond <= 0.0) {
        return "Waiting for data"
    }
    return "${formatBytes(bytesPerSecond.roundToLong())}/s"
}

private fun Float.roundToInt(): Int = (this + 0.5f).toInt()

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun LionScreenPreviewSetup() {
    LionTheme {
        LionScreen(
            state = LionUiState(
                code = "7-purple-seabird",
                destination = SavedDestination(Uri.EMPTY, "Downloads/Lion"),
            ),
            onCodeChange = {},
            onPickDestination = {},
            onStart = {},
            onCancel = {},
            onReset = {},
            onViewInFolder = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun LionScreenPreviewSuccess() {
    LionTheme {
        LionScreen(
            state = LionUiState(
                code = "7-purple-seabird",
                destination = SavedDestination(Uri.EMPTY, "Downloads/Lion"),
                phase = TransferPhase.Success,
                statusHeadline = "Saved to Downloads/Lion",
                statusMessage = "project-assets is ready to open from your selected folder.",
                transferName = "project-assets",
                transferKind = "directory",
                receivedBytes = 35_000_000,
                totalBytes = 35_000_000,
                bytesPerSecond = 0.0,
                itemCount = 142,
                expandedBytes = 94_000_000,
                importedItem = ImportedItem(
                    displayName = "project-assets",
                    uri = Uri.EMPTY,
                ),
            ),
            onCodeChange = {},
            onPickDestination = {},
            onStart = {},
            onCancel = {},
            onReset = {},
            onViewInFolder = {},
        )
    }
}
