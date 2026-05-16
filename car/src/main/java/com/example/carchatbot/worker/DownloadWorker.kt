package com.example.carchatbot.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.repository.SoundAssetManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val soundAssetManager: SoundAssetManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "sound_download_work"
        private const val DEFAULT_TAG = "download_sound"

        fun createRequest(reasonTag: String = DEFAULT_TAG): OneTimeWorkRequest {
            return OneTimeWorkRequestBuilder<DownloadWorker>()
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    1,
                    java.util.concurrent.TimeUnit.MINUTES
                )
                .addTag(DEFAULT_TAG)
                .addTag(reasonTag)
                .build()
        }
    }

    override suspend fun doWork(): Result {
        System.out.println("!!! DownloadWorker: Work started - checking logs !!!")
        android.util.Log.d("DownloadWorker", "Work started")
        val userId = userPreferencesRepository.userId.first()
        if (userId.isNullOrEmpty()) {
            return Result.failure()
        }

        return try {
            val token = userPreferencesRepository.accessToken.first()
            if (token.isNullOrEmpty()) {
                android.util.Log.e("DownloadWorker", "Token is missing, cannot download sounds")
                return Result.failure()
            }

            android.util.Log.d("DownloadWorker", "Downloading hello sound for user: $userId")
            soundAssetManager.refreshManagedSound(1)
            if (soundAssetManager.ensureSoundAvailable(1) == null) {
                android.util.Log.e("DownloadWorker", "Failed to refresh hello sound")
                return Result.retry()
            }

            android.util.Log.d("DownloadWorker", "Downloading goodbye sound for user: $userId")
            soundAssetManager.refreshManagedSound(2)
            if (soundAssetManager.ensureSoundAvailable(2) == null) {
                android.util.Log.e("DownloadWorker", "Failed to refresh goodbye sound")
                return Result.retry()
            }

            Result.success()
        } catch (e: retrofit2.HttpException) {
            android.util.Log.e("DownloadWorker", "HTTP Error: ${e.code()} ${e.message()}", e)
            if (e.code() in 400..499) {
                // Client error (e.g. 401 Unauthorized, 404 Not Found) - Do not retry
                Result.failure()
            } else {
                // Server error - Retry
                Result.retry()
            }
        } catch (e: Throwable) {
            android.util.Log.e("DownloadWorker", "Critical Download error", e)
            if (e is Exception) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }
}
