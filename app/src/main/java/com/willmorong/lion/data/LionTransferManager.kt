package com.willmorong.lion.data

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

sealed interface LionTransferEvent {
    data class Status(
        val stage: String,
        val message: String,
    ) : LionTransferEvent

    data class Offer(
        val kind: String,
        val name: String,
        val totalBytes: Long,
        val itemCount: Int,
        val expandedBytes: Long,
    ) : LionTransferEvent

    data class Progress(
        val receivedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Double,
    ) : LionTransferEvent

    data class Complete(
        val kind: String,
        val name: String,
        val tempPath: String,
        val scratchRoot: String,
        val sha256: String,
    ) : LionTransferEvent

    data class Error(
        val message: String,
    ) : LionTransferEvent

    data object Canceled : LionTransferEvent
}

interface LionPythonCallback {
    fun onStatus(stage: String, message: String)

    fun onOffer(kind: String, name: String, totalBytes: Long, itemCount: Int, expandedBytes: Long)

    fun onProgress(receivedBytes: Long, totalBytes: Long, bytesPerSecond: Double)

    fun onComplete(kind: String, name: String, tempPath: String, scratchRoot: String, sha256: String)

    fun onError(message: String)

    fun onCanceled()
}

class LionTransferManager(
    private val context: Context,
) {
    private val lock = Any()

    @Volatile
    private var transferRunning = false

    fun start(
        code: String,
        eventSink: (LionTransferEvent) -> Unit,
    ): Result<Unit> {
        val trimmedCode = code.trim()
        if (trimmedCode.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter the wormhole code from the sender."))
        }

        synchronized(lock) {
            if (transferRunning) {
                return Result.failure(IllegalStateException("A transfer is already running."))
            }
            transferRunning = true
        }

        val scratchRoot = File(
            context.cacheDir,
            "lion-receive/${System.currentTimeMillis()}",
        ).apply { mkdirs() }

        val terminalEventSent = AtomicBoolean(false)

        val callback = object : LionPythonCallback {
            override fun onStatus(stage: String, message: String) {
                eventSink(LionTransferEvent.Status(stage, message))
            }

            override fun onOffer(
                kind: String,
                name: String,
                totalBytes: Long,
                itemCount: Int,
                expandedBytes: Long,
            ) {
                eventSink(
                    LionTransferEvent.Offer(
                        kind = kind,
                        name = name,
                        totalBytes = totalBytes,
                        itemCount = itemCount,
                        expandedBytes = expandedBytes,
                    ),
                )
            }

            override fun onProgress(receivedBytes: Long, totalBytes: Long, bytesPerSecond: Double) {
                eventSink(
                    LionTransferEvent.Progress(
                        receivedBytes = receivedBytes,
                        totalBytes = totalBytes,
                        bytesPerSecond = bytesPerSecond,
                    ),
                )
            }

            override fun onComplete(
                kind: String,
                name: String,
                tempPath: String,
                scratchRoot: String,
                sha256: String,
            ) {
                if (terminalEventSent.compareAndSet(false, true)) {
                    finishTransfer()
                    eventSink(
                        LionTransferEvent.Complete(
                            kind = kind,
                            name = name,
                            tempPath = tempPath,
                            scratchRoot = scratchRoot,
                            sha256 = sha256,
                        ),
                    )
                }
            }

            override fun onError(message: String) {
                if (terminalEventSent.compareAndSet(false, true)) {
                    finishTransfer()
                    scratchRoot.deleteRecursively()
                    eventSink(LionTransferEvent.Error(message.ifBlank { "Transfer failed." }))
                }
            }

            override fun onCanceled() {
                if (terminalEventSent.compareAndSet(false, true)) {
                    finishTransfer()
                    scratchRoot.deleteRecursively()
                    eventSink(LionTransferEvent.Canceled)
                }
            }
        }

        return runCatching {
            ensurePythonStarted()
            Python.getInstance()
                .getModule("lion_receiver")
                .callAttr("start_receive", trimmedCode, scratchRoot.absolutePath, callback)
            Unit
        }.onFailure { error ->
            finishTransfer()
            scratchRoot.deleteRecursively()
            eventSink(
                LionTransferEvent.Error(
                    error.message ?: "Unable to start the wormhole receiver.",
                ),
            )
        }
    }

    fun cancel() {
        synchronized(lock) {
            if (!transferRunning) {
                return
            }
        }

        runCatching {
            ensurePythonStarted()
            Python.getInstance()
                .getModule("lion_receiver")
                .callAttr("cancel_receive")
        }
    }

    private fun ensurePythonStarted() {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
    }

    private fun finishTransfer() {
        synchronized(lock) {
            transferRunning = false
        }
    }
}
