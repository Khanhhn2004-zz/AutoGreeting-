package com.example.carchatbot.support

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootReceiverProbeStore
import com.example.carchatbot.boot.StartupDiagnosticsRepository
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.runtime.BuildMetadata
import com.example.carchatbot.utils.AppLogger
import com.example.carchatbot.utils.DeviceUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

@Singleton
class SupportLogExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val startupDiagnosticsRepository: StartupDiagnosticsRepository,
    private val bootPlaybackStateStore: BootPlaybackStateStore,
    private val bootReceiverProbeStore: BootReceiverProbeStore,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val diagnosticsReportRenderer: DiagnosticsReportRenderer,
    private val appLogger: AppLogger
) {

    fun suggestReportFileName(): String = buildReportFileName()

    suspend fun buildShareIntent(): Intent = withContext(Dispatchers.IO) {
        val report = buildReportText()
        val reportFile = writeShareReport(report)
        val attachment = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            reportFile
        )
        val destinationDetails = "file=${reportFile.name} bytes=${reportFile.length()}"

        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            clipData = ClipData.newUri(
                context.contentResolver,
                reportFile.name,
                attachment
            )
            putExtra(Intent.EXTRA_TITLE, reportFile.name)
            putExtra(
                Intent.EXTRA_SUBJECT,
                "CarChatbot diagnostics report - ${BuildMetadata.displayVersion()}"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                "Bao cao chan doan tu ${BuildMetadata.displayName()}. Vui long giu nguyen file dinh kem khi gui."
            )
            putExtra(Intent.EXTRA_STREAM, attachment)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.also { shareIntent ->
            context.packageManager
                .queryIntentActivities(shareIntent, PackageManager.MATCH_DEFAULT_ONLY)
                .asSequence()
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .forEach { packageName ->
                    context.grantUriPermission(
                        packageName,
                        attachment,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            appLogger.log("SupportLogExporter", "Support log share intent prepared", destinationDetails)
        }
    }

    suspend fun exportToUri(destination: Uri) = withContext(Dispatchers.IO) {
        val report = buildReportText()
        val byteCount = report.toByteArray(Charsets.UTF_8).size
        val destinationDetails = "scheme=${destination.scheme} authority=${destination.authority} bytes=$byteCount"
        appLogger.log("SupportLogExporter", "Support log export write started", destinationDetails)

        try {
            val writer = context.contentResolver
                .openOutputStream(destination, "w")
                ?.bufferedWriter(Charsets.UTF_8)
                ?: throw IOException("Unable to open support log export destination: $destination")

            writer.use {
                it.write(report)
                it.flush()
            }
            appLogger.log("SupportLogExporter", "Support log export write completed", destinationDetails)
        } catch (error: Exception) {
            appLogger.logException("SupportLogExporter", error, "Support log export write failed")
            throw error
        }
    }

    private fun writeShareReport(report: String): File {
        val exportDir = prepareShareDirectory()
        return File(exportDir, buildReportFileName()).apply {
            writeText(report, Charsets.UTF_8)
        }
    }

    private fun prepareShareDirectory(): File {
        val rootDir = File(context.filesDir, EXPORT_DIRECTORY_NAME)
        if (rootDir.exists()) {
            rootDir.deleteRecursively()
        }
        return File(rootDir, "latest").apply { mkdirs() }
    }

    suspend fun buildReportText(): String {
        val exportedAt = exportTimestamp()
        val startupMode = userPreferencesRepository.startupMode.first()
        val floatingButtonEnabled = userPreferencesRepository.floatingButtonEnabled.first()
        val isLoggedIn = userPreferencesRepository.isLoggedIn.first()
        val remoteSyncBlocked = userPreferencesRepository.isRemoteSyncBlocked.first()
        val diagnosticsLogs = startupDiagnosticsRepository.snapshot()
        val bootPlaybackState = bootPlaybackStateStore.readState()
        val rawBootReceiverProbe = bootReceiverProbeStore.readLatest()
        val deviceInfo = DeviceUtils.getDeviceInfo(
            context = context,
            appVersion = BuildMetadata.displayVersion(),
            gpsLocation = null
        )
        return diagnosticsReportRenderer.render(
            DiagnosticsReportInput(
                exportedAt = exportedAt,
                startupMode = startupMode,
                floatingButtonEnabled = floatingButtonEnabled,
                isLoggedIn = isLoggedIn,
                remoteSyncBlocked = remoteSyncBlocked,
                diagnosticsLogs = diagnosticsLogs,
                bootPlaybackState = bootPlaybackState,
                rawBootReceiverProbe = rawBootReceiverProbe,
                deviceInfo = deviceInfo,
                supportEmail = SUPPORT_EMAIL,
                appName = BuildMetadata.displayName(),
                appVersion = BuildMetadata.displayVersion(),
                buildPublishedAt = BuildMetadata.displayPublishedAt()
            )
        )
    }

    private fun exportTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    private fun buildReportFileName(): String {
        val stamped = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "chao-xe-diagnostics-$stamped.txt"
    }

    companion object {
        private const val EXPORT_DIRECTORY_NAME = "support-log-export"
        private const val SUPPORT_EMAIL = "quanha191224@gmail.com"
    }
}
