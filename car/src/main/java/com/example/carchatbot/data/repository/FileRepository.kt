package com.example.carchatbot.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun saveFile(
        body: ResponseBody,
        fileName: String,
        directoryName: String? = null,
        shouldCommit: suspend (File) -> Boolean = { true }
    ): File? {
        return withContext(Dispatchers.IO) {
            writeAtomicFile(
                fileName = fileName,
                directoryName = directoryName,
                shouldCommit = shouldCommit
            ) { stagedFile ->
                body.byteStream().use { input ->
                    stagedFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    suspend fun replaceFile(
        sourceFile: File,
        fileName: String,
        directoryName: String? = null,
        shouldCommit: suspend (File) -> Boolean = { true }
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                writeAtomicFile(
                    fileName = fileName,
                    directoryName = directoryName,
                    shouldCommit = shouldCommit
                ) { stagedFile ->
                    sourceFile.inputStream().use { input ->
                        stagedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } finally {
                deleteIfExists(sourceFile)
            }
        }
    }

    private suspend fun writeAtomicFile(
        fileName: String,
        directoryName: String?,
        shouldCommit: suspend (File) -> Boolean,
        writeStage: suspend (File) -> Unit
    ): File? {
        val parentDir = if (directoryName.isNullOrBlank()) {
            context.filesDir
        } else {
            File(context.filesDir, directoryName).apply { mkdirs() }
        }
        val targetFile = File(parentDir, fileName)
        val stageFile = File(parentDir, ".${fileName}.stage-${System.nanoTime()}")
        val backupFile = if (targetFile.exists()) {
            File(parentDir, ".${fileName}.backup-${System.nanoTime()}")
        } else {
            null
        }

        return try {
            writeStage(stageFile)
            if (!shouldCommit(stageFile)) {
                android.util.Log.w(
                    "FileRepository",
                    "Atomic write rejected for $fileName in ${parentDir.absolutePath} (stageLength=${stageFile.length()})"
                )
                return null
            }

            if (backupFile != null) {
                targetFile.copyTo(backupFile, overwrite = true)
            }

            moveStageIntoPlace(stageFile, targetFile)
            targetFile
        } catch (e: Exception) {
            android.util.Log.e(
                "FileRepository",
                "Atomic write failed for $fileName in ${parentDir.absolutePath}",
                e
            )
            if (backupFile != null && backupFile.exists()) {
                runCatching {
                    moveStageIntoPlace(backupFile, targetFile)
                }
            }
            null
        } finally {
            deleteIfExists(stageFile)
            deleteIfExists(backupFile)
        }
    }

    private fun moveStageIntoPlace(sourceFile: File, targetFile: File) {
        try {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                sourceFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun deleteIfExists(file: File?) {
        if (file == null) return

        runCatching {
            if (file.exists() && !file.delete()) {
                throw IOException("Unable to delete ${file.absolutePath}")
            }
        }
    }
}
