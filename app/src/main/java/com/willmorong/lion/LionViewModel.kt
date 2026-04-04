package com.willmorong.lion

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.willmorong.lion.data.DocumentTreeImporter
import com.willmorong.lion.data.ImportedItem
import com.willmorong.lion.data.LionTransferEvent
import com.willmorong.lion.data.LionTransferManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedDestination(
    val uri: Uri,
    val label: String,
)

enum class TransferPhase {
    Idle,
    Connecting,
    Waiting,
    Verifying,
    Receiving,
    Importing,
    Success,
    Error,
    Canceled,
}

data class LionUiState(
    val code: String = "",
    val destination: SavedDestination? = null,
    val phase: TransferPhase = TransferPhase.Idle,
    val statusHeadline: String = "Ready to receive",
    val statusMessage: String = "Enter a wormhole code and pick a destination folder.",
    val transferName: String? = null,
    val transferKind: String? = null,
    val receivedBytes: Long = 0,
    val totalBytes: Long? = null,
    val bytesPerSecond: Double = 0.0,
    val itemCount: Int? = null,
    val expandedBytes: Long? = null,
    val importedItem: ImportedItem? = null,
    val errorMessage: String? = null,
) {
    val isTransferRunning: Boolean
        get() = phase in setOf(
            TransferPhase.Connecting,
            TransferPhase.Waiting,
            TransferPhase.Verifying,
            TransferPhase.Receiving,
            TransferPhase.Importing,
        )

    val canStart: Boolean
        get() = code.isNotBlank() && destination != null && !isTransferRunning
}

class LionViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val transferManager = LionTransferManager(appContext)
    private val importer = DocumentTreeImporter(appContext)

    private val _uiState = MutableStateFlow(LionUiState())
    val uiState: StateFlow<LionUiState> = _uiState.asStateFlow()

    init {
        restoreDestination()
    }

    fun updateCode(code: String) {
        _uiState.update {
            it.copy(
                code = code,
                errorMessage = if (it.phase == TransferPhase.Error) null else it.errorMessage,
            )
        }
    }

    fun onDestinationSelected(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
        }

        val label = resolveDestinationLabel(uri)
        prefs.edit()
            .putString(KEY_DESTINATION_URI, uri.toString())
            .putString(KEY_DESTINATION_LABEL, label)
            .apply()

        _uiState.update {
            it.copy(
                destination = SavedDestination(uri, label),
                errorMessage = null,
            )
        }
    }

    fun startTransfer() {
        val code = _uiState.value.code.trim()
        val destination = _uiState.value.destination ?: return

        _uiState.update {
            it.copy(
                code = code,
                phase = TransferPhase.Connecting,
                statusHeadline = "Connecting to the relay",
                statusMessage = "Joining the wormhole and waiting for the sender.",
                transferName = null,
                transferKind = null,
                receivedBytes = 0,
                totalBytes = null,
                bytesPerSecond = 0.0,
                itemCount = null,
                expandedBytes = null,
                importedItem = null,
                errorMessage = null,
            )
        }

        val startResult = transferManager.start(code) { event ->
            viewModelScope.launch {
                handleTransferEvent(event, destination)
            }
        }

        startResult.exceptionOrNull()?.let { error ->
            _uiState.update {
                it.copy(
                    phase = TransferPhase.Error,
                    statusHeadline = "Unable to start",
                    statusMessage = "Check the code and try again.",
                    errorMessage = error.message ?: "Unable to start the transfer.",
                )
            }
        }
    }

    fun cancelTransfer() {
        if (!_uiState.value.isTransferRunning) {
            return
        }

        _uiState.update {
            it.copy(
                statusHeadline = "Canceling transfer",
                statusMessage = "Stopping the wormhole session.",
            )
        }
        transferManager.cancel()
    }

    fun resetSession() {
        if (_uiState.value.isTransferRunning) {
            return
        }

        _uiState.update { state ->
            state.copy(
                code = if (state.phase == TransferPhase.Success) "" else state.code,
                phase = TransferPhase.Idle,
                statusHeadline = "Ready to receive",
                statusMessage = "Enter a wormhole code and pick a destination folder.",
                transferName = null,
                transferKind = null,
                receivedBytes = 0,
                totalBytes = null,
                bytesPerSecond = 0.0,
                itemCount = null,
                expandedBytes = null,
                importedItem = null,
                errorMessage = null,
            )
        }
    }

    private suspend fun handleTransferEvent(
        event: LionTransferEvent,
        destination: SavedDestination,
    ) {
        when (event) {
            is LionTransferEvent.Status -> {
                _uiState.update {
                    it.copy(
                        phase = event.stage.toPhase(),
                        statusHeadline = event.stage.toHeadline(),
                        statusMessage = event.message,
                        errorMessage = null,
                    )
                }
            }

            is LionTransferEvent.Offer -> {
                _uiState.update {
                    it.copy(
                        transferName = event.name,
                        transferKind = event.kind,
                        totalBytes = event.totalBytes,
                        itemCount = event.itemCount.takeIf { count -> count > 0 },
                        expandedBytes = event.expandedBytes.takeIf { bytes -> bytes > 0 },
                        statusHeadline = if (event.kind == "directory") {
                            "Folder offer accepted"
                        } else {
                            "File offer accepted"
                        },
                        statusMessage = "Preparing the transfer path and starting download.",
                    )
                }
            }

            is LionTransferEvent.Progress -> {
                _uiState.update {
                    it.copy(
                        phase = TransferPhase.Receiving,
                        statusHeadline = "Receiving ${it.transferKind ?: "payload"}",
                        statusMessage = "Writing encrypted chunks into Lion’s inbox.",
                        receivedBytes = event.receivedBytes,
                        totalBytes = event.totalBytes,
                        bytesPerSecond = event.bytesPerSecond,
                    )
                }
            }

            is LionTransferEvent.Complete -> {
                importDownloadedPath(event, destination)
            }

            is LionTransferEvent.Error -> {
                _uiState.update {
                    it.copy(
                        phase = TransferPhase.Error,
                        statusHeadline = "Transfer failed",
                        statusMessage = "Lion couldn’t finish receiving this wormhole.",
                        errorMessage = event.message,
                        bytesPerSecond = 0.0,
                    )
                }
            }

            LionTransferEvent.Canceled -> {
                _uiState.update {
                    it.copy(
                        phase = TransferPhase.Canceled,
                        statusHeadline = "Transfer canceled",
                        statusMessage = "You stopped this receive session before it finished.",
                        bytesPerSecond = 0.0,
                    )
                }
            }
        }
    }

    private suspend fun importDownloadedPath(
        event: LionTransferEvent.Complete,
        destination: SavedDestination,
    ) {
        _uiState.update {
            it.copy(
                phase = TransferPhase.Importing,
                statusHeadline = "Saving into ${destination.label}",
                statusMessage = "Copying the completed transfer into your chosen folder.",
                bytesPerSecond = 0.0,
            )
        }

        val result = withContext(Dispatchers.IO) {
            runCatching {
                importer.importPath(File(event.tempPath), destination.uri)
            }.also {
                File(event.scratchRoot).deleteRecursively()
            }
        }

        result.onSuccess { imported ->
            _uiState.update {
                it.copy(
                    phase = TransferPhase.Success,
                    statusHeadline = "Saved to ${destination.label}",
                    statusMessage = "${imported.displayName} is ready to open from your selected folder.",
                    importedItem = imported,
                    bytesPerSecond = 0.0,
                    errorMessage = null,
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    phase = TransferPhase.Error,
                    statusHeadline = "Import failed",
                    statusMessage = "The download completed, but Lion couldn’t copy it into the selected folder.",
                    errorMessage = error.message ?: "Unable to save the received item.",
                    bytesPerSecond = 0.0,
                )
            }
        }
    }

    private fun restoreDestination() {
        val uriString = prefs.getString(KEY_DESTINATION_URI, null) ?: return
        val uri = Uri.parse(uriString)
        val persisted = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!persisted) {
            prefs.edit().remove(KEY_DESTINATION_URI).remove(KEY_DESTINATION_LABEL).apply()
            return
        }

        val label = prefs.getString(KEY_DESTINATION_LABEL, null) ?: resolveDestinationLabel(uri)
        _uiState.update {
            it.copy(destination = SavedDestination(uri, label))
        }
    }

    private fun resolveDestinationLabel(uri: Uri): String {
        DocumentFile.fromTreeUri(appContext, uri)?.name
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        val documentId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull().orEmpty()

        return documentId.substringAfterLast(':', "Selected folder")
            .ifBlank { "Selected folder" }
    }

    private fun String.toPhase(): TransferPhase = when (this) {
        "connecting" -> TransferPhase.Connecting
        "waiting" -> TransferPhase.Waiting
        "verifying" -> TransferPhase.Verifying
        "receiving" -> TransferPhase.Receiving
        else -> TransferPhase.Connecting
    }

    private fun String.toHeadline(): String = when (this) {
        "connecting" -> "Connecting to the relay"
        "waiting" -> "Waiting for the sender"
        "verifying" -> "Securing the session"
        "receiving" -> "Receiving data"
        else -> "Receiving"
    }

    companion object {
        private const val PREFS_NAME = "lion.preferences"
        private const val KEY_DESTINATION_URI = "destination_uri"
        private const val KEY_DESTINATION_LABEL = "destination_label"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LionViewModel(this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application)
            }
        }
    }
}
