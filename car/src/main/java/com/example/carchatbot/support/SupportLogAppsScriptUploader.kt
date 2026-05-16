package com.example.carchatbot.support

import com.example.carchatbot.BuildConfig
import com.example.carchatbot.runtime.BuildMetadata
import com.example.carchatbot.utils.AppLogger
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

sealed interface SupportLogUploadResult {
    data class Success(val reportId: String) : SupportLogUploadResult
    data object NotConfigured : SupportLogUploadResult
    data class Failure(val message: String) : SupportLogUploadResult
}

@Serializable
data class SupportLogAppsScriptRequest(
    @SerialName("secret") val secret: String,
    @SerialName("fileName") val fileName: String,
    @SerialName("report") val report: String,
    @SerialName("appName") val appName: String,
    @SerialName("appVersion") val appVersion: String,
    @SerialName("buildPublishedAt") val buildPublishedAt: String,
    @SerialName("uploadedAt") val uploadedAt: String
)

@Serializable
data class SupportLogAppsScriptResponse(
    @SerialName("ok") val ok: Boolean = false,
    @SerialName("reportId") val reportId: String? = null,
    @SerialName("error") val error: String? = null
)

@Singleton
class SupportLogAppsScriptUploader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val supportLogExporter: SupportLogExporter,
    private val appLogger: AppLogger
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val uploadClient = okHttpClient.newBuilder()
        .dns(GoogleAppsScriptFallbackDns)
        .followRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun upload(): SupportLogUploadResult = withContext(Dispatchers.IO) {
        val uploadUrl = BuildConfig.SUPPORT_LOG_APPS_SCRIPT_URL.trim()
        val uploadSecret = BuildConfig.SUPPORT_LOG_APPS_SCRIPT_SECRET
        if (uploadUrl.isBlank() || uploadSecret.isBlank()) {
            return@withContext SupportLogUploadResult.NotConfigured
        }

        val report = supportLogExporter.buildReportText()
        val fileName = supportLogExporter.suggestReportFileName()
        val payload = SupportLogAppsScriptRequest(
            secret = uploadSecret,
            fileName = fileName,
            report = report,
            appName = BuildMetadata.displayName(),
            appVersion = BuildMetadata.displayVersion(),
            buildPublishedAt = BuildMetadata.displayPublishedAt(),
            uploadedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        )
        val requestBody = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(uploadUrl)
            .post(requestBody)
            .build()

        appLogger.log(
            "SupportLogAppsScriptUploader",
            "Support log upload started",
            "host=${request.url.host} file=$fileName bytes=${requestBody.contentLength()}"
        )

        for (attempt in 1..MAX_UPLOAD_ATTEMPTS) {
            val result = executeUploadRequest(request, attempt)
            if (result is InternalUploadResult.Success ||
                (result is InternalUploadResult.Failure && (!result.retryable || attempt == MAX_UPLOAD_ATTEMPTS))
            ) {
                return@withContext result.asPublicResult()
            }
            delay(1_500L * attempt)
        }

        SupportLogUploadResult.Failure("network_error")
    }

    private fun executeUploadRequest(
        request: Request,
        attempt: Int
    ): InternalUploadResult {
        return try {
            var currentRequest = request
            var redirectCount = 0
            while (redirectCount <= MAX_REDIRECTS) {
                val response = uploadClient.newCall(currentRequest).execute()
                response.use {
                    if (response.code in 300..399) {
                        if (redirectCount >= MAX_REDIRECTS) {
                            return InternalUploadResult.Failure("too_many_redirects", retryable = false)
                        }
                        val location = response.header("Location")
                            ?: return InternalUploadResult.Failure("redirect_missing_location", retryable = false)
                        val redirectUrl = currentRequest.url.resolve(location)
                            ?: return InternalUploadResult.Failure("redirect_invalid_location", retryable = false)

                        appLogger.log(
                            "SupportLogAppsScriptUploader",
                            "Support log upload redirect followed",
                            "code=${response.code} fromHost=${currentRequest.url.host} toHost=${redirectUrl.host} attempt=$attempt"
                        )
                        currentRequest = Request.Builder()
                            .url(redirectUrl)
                            .get()
                            .build()
                        redirectCount += 1
                    } else {
                        return parseUploadResponse(response, currentRequest, attempt)
                    }
                }
            }
            InternalUploadResult.Failure("too_many_redirects", retryable = false)
        } catch (error: IOException) {
            appLogger.logException(
                "SupportLogAppsScriptUploader",
                error,
                "Support log upload network failed (attempt=$attempt)"
            )
            InternalUploadResult.Failure("network_error", retryable = true)
        } catch (error: Exception) {
            appLogger.logException(
                "SupportLogAppsScriptUploader",
                error,
                "Support log upload failed (attempt=$attempt)"
            )
            InternalUploadResult.Failure(error.javaClass.simpleName, retryable = false)
        }
    }

    private fun parseUploadResponse(
        response: Response,
        request: Request,
        attempt: Int
    ): InternalUploadResult {
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            appLogger.log(
                "SupportLogAppsScriptUploader",
                "Support log upload HTTP failure",
                "code=${response.code} host=${request.url.host} attempt=$attempt"
            )
            return InternalUploadResult.Failure(
                message = "HTTP ${response.code}",
                retryable = response.code == 429 || response.code in 500..599
            )
        }

        val parsed = runCatching {
            json.decodeFromString<SupportLogAppsScriptResponse>(body)
        }.getOrElse { error ->
            appLogger.logException(
                "SupportLogAppsScriptUploader",
                error,
                "Support log upload invalid response code=${response.code} host=${request.url.host} body=${body.take(INVALID_RESPONSE_LOG_LIMIT)}"
            )
            return InternalUploadResult.Failure("invalid_response", retryable = false)
        }

        if (!parsed.ok || parsed.reportId.isNullOrBlank()) {
            return InternalUploadResult.Failure(parsed.error ?: "apps_script_rejected", retryable = false)
        }

        appLogger.log(
            "SupportLogAppsScriptUploader",
            "Support log upload completed",
            "reportId=${parsed.reportId}"
        )
        return InternalUploadResult.Success(parsed.reportId)
    }

    private sealed interface InternalUploadResult {
        data class Success(val reportId: String) : InternalUploadResult
        data class Failure(val message: String, val retryable: Boolean) : InternalUploadResult

        fun asPublicResult(): SupportLogUploadResult {
            return when (this) {
                is Success -> SupportLogUploadResult.Success(reportId)
                is Failure -> SupportLogUploadResult.Failure(message)
            }
        }
    }

    companion object {
        private const val MAX_UPLOAD_ATTEMPTS = 3
        private const val MAX_REDIRECTS = 2
        private const val INVALID_RESPONSE_LOG_LIMIT = 500
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private object GoogleAppsScriptFallbackDns : Dns {
    private val fallbackAddresses = mapOf(
        "script.google.com" to listOf(
            "142.250.197.78",
            "142.250.199.206"
        ),
        "script.googleusercontent.com" to listOf(
            "142.250.197.97",
            "142.250.199.65"
        )
    )

    override fun lookup(hostname: String): List<InetAddress> {
        return try {
            Dns.SYSTEM.lookup(hostname)
        } catch (error: UnknownHostException) {
            fallbackAddresses[hostname.lowercase(Locale.US)]
                ?.map { address -> InetAddress.getByName(address) }
                ?: throw error
        }
    }
}
