package com.ramiro.iosdroid

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Descarga el clima y vuelve a dibujar los widgets. */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = Prefs(ctx)
        var place = prefs.place

        // Si el lugar viene del GPS, se actualiza cuando te moviste más de ~3 km.
        if (place == null || place.auto) {
            LocationHelper.lastKnown(ctx)?.let { loc ->
                val moved = place == null || LocationHelper.distanceKm(place.lat, place.lon, loc.latitude, loc.longitude) > 3.0
                if (moved) {
                    val name = LocationHelper.cityName(ctx, loc.latitude, loc.longitude)
                        ?: place?.name?.takeIf { it.isNotBlank() }
                        ?: ctx.getString(R.string.my_location)
                    place = Place(name, loc.latitude, loc.longitude, auto = true).also { prefs.place = it }
                }
            }
        }

        val target = place ?: run {
            WidgetRenderer.updateAll(ctx)
            return Result.success()
        }

        return try {
            prefs.weather = OpenMeteo.forecast(target.lat, target.lon, prefs.unit)
            WidgetRenderer.updateAll(ctx)
            Result.success()
        } catch (e: Exception) {
            WidgetRenderer.updateAll(ctx)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val PERIODIC = "weather_periodic"
        const val NOW = "weather_now"

        /** Versión de la tarea periódica: al cambiarla, se reemplaza la programada en versiones anteriores. */
        private const val PERIODIC_VERSION = 2
        private const val PERIODIC_MINUTES = 60L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        // La periódica espera a que haya batería: con batería baja el clima puede esperar.
        private val periodicConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        fun schedule(context: Context) {
            val prefs = Prefs(context)
            val policy = if (prefs.weatherScheduleVersion == PERIODIC_VERSION) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.UPDATE
            val request = PeriodicWorkRequestBuilder<WeatherWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES, 15, TimeUnit.MINUTES)
                .setConstraints(periodicConstraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, policy, request)
            prefs.weatherScheduleVersion = PERIODIC_VERSION
        }

        fun refreshNow(context: Context) {
            Prefs(context).lastRefreshRequest = System.currentTimeMillis()
            val request = OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }

        /**
         * Pide datos nuevos solo si los actuales son viejos (30 min; 90 min con batería baja) y no
         * se pidió hace poco. Lo llaman el reloj y los widgets al mostrarse.
         */
        fun refreshIfStale(context: Context) {
            val prefs = Prefs(context)
            val now = System.currentTimeMillis()
            val weather = prefs.weather
            val maxAge = TimeUnit.MINUTES.toMillis(if (Power.low(context)) 90 else 30)
            val stale = weather == null || weather.unit != prefs.unit || now - weather.updatedAt > maxAge
            if (stale && now - prefs.lastRefreshRequest > TimeUnit.MINUTES.toMillis(5)) refreshNow(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(NOW)
            }
        }
    }
}
