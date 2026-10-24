package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object ProviderEndpointRefreshScheduler {
    private const val UNIQUE_WORK = "visionflix_provider_endpoint_refresh"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ProviderEndpointRefreshWorker>(
            6,
            TimeUnit.HOURS,
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }
}

class ProviderEndpointRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            ProviderEndpointRegistry.refreshIfNeeded(
                providers = Provider.providers.keys,
                force = true,
            )
            Result.success()
        } catch (error: Exception) {
            Log.w("ProviderEndpointRefresh", "Aggiornamento provider non riuscito", error)
            Result.retry()
        }
    }
}
