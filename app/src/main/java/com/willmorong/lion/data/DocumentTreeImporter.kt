package com.willmorong.lion.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.File

data class ImportedItem(
    val displayName: String,
    val uri: Uri,
)

class DocumentTreeImporter(
    private val context: Context,
) {
    fun importPath(
        source: File,
        treeUri: Uri,
    ): ImportedItem {
        require(source.exists()) { "Nothing was downloaded." }

        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: error("The selected destination folder is no longer available.")

        return if (source.isDirectory) {
            importDirectory(source, root)
        } else {
            importFile(source, root)
        }
    }

    private fun importDirectory(
        source: File,
        parent: DocumentFile,
    ): ImportedItem {
        val targetName = uniqueName(parent, source.name, directory = true)
        val targetDir = parent.createDirectory(targetName)
            ?: error("Unable to create $targetName.")

        copyDirectoryContents(source, targetDir)
        return ImportedItem(targetName, targetDir.uri)
    }

    private fun copyDirectoryContents(
        sourceDir: File,
        targetDir: DocumentFile,
    ) {
        sourceDir.listFiles()
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
            .forEach { child ->
                if (child.isDirectory) {
                    val childDir = targetDir.createDirectory(child.name)
                        ?: error("Unable to create ${child.name}.")
                    copyDirectoryContents(child, childDir)
                } else {
                    val output = targetDir.createFile(mimeTypeFor(child), child.name)
                        ?: error("Unable to create ${child.name}.")
                    copyFile(child, output)
                }
            }
    }

    private fun importFile(
        source: File,
        parent: DocumentFile,
    ): ImportedItem {
        val targetName = uniqueName(parent, source.name, directory = false)
        val targetFile = parent.createFile(mimeTypeFor(source), targetName)
            ?: error("Unable to create $targetName.")

        copyFile(source, targetFile)
        return ImportedItem(targetName, targetFile.uri)
    }

    private fun copyFile(
        source: File,
        target: DocumentFile,
    ) {
        val resolver = context.contentResolver
        source.inputStream().use { input ->
            resolver.openOutputStream(target.uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("Unable to open ${target.name} for writing.")
        }
    }

    private fun uniqueName(
        parent: DocumentFile,
        originalName: String,
        directory: Boolean,
    ): String {
        var candidate = originalName
        var index = 2

        while (parent.findFile(candidate) != null) {
            candidate = if (directory || "." !in originalName) {
                "$originalName ($index)"
            } else {
                val base = originalName.substringBeforeLast(".")
                val extension = originalName.substringAfterLast(".")
                "$base ($index).$extension"
            }
            index += 1
        }

        return candidate
    }

    private fun mimeTypeFor(file: File): String {
        val extension = file.extension.lowercase()
        return MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }
}
