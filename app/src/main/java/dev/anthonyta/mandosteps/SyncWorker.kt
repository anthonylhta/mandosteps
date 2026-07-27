package dev.anthonyta.mandosteps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads yesterday's and today's step totals from Health Connect and POSTs each to
 * the hub's ingest route. Yesterday rides along on every run so a day where the
 * phone was off self-heals — the route overwrites same-day entries.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE)
            return fail(ctx, "Health Connect unavailable")

        val client = HealthConnectClient.getOrCreate(ctx)
        val perm = HealthPermission.getReadPermission(StepsRecord::class)
        if (perm !in client.permissionController.getGrantedPermissions())
            return fail(ctx, "steps permission not granted — open the app")

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val report = StringBuilder()

        for (day in listOf(today.minusDays(1), today)) {
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val steps = try {
                val agg = client.aggregate(
                    AggregateRequest(
                        setOf(StepsRecord.COUNT_TOTAL),
                        TimeRangeFilter.between(start, end),
                    )
                )
                (agg[StepsRecord.COUNT_TOTAL] ?: 0L).coerceIn(0L, MAX_DAILY_STEPS)
            } catch (e: Exception) {
                return retryOrFail(ctx, "Health Connect read failed: ${e.message}")
            }

            val code = try {
                post(day, steps)
            } catch (e: Exception) {
                return retryOrFail(ctx, "POST failed: ${e.message}")
            }
            if (code != 200) return retryOrFail(ctx, "POST $day → HTTP $code")
            report.append("$day: $steps ✓  ")
        }

        Prefs.setLast(ctx, "${stamp()} · ${report.toString().trim()}")
        return Result.success()
    }

    private fun post(day: LocalDate, steps: Long): Int {
        val conn = URL(BuildConfig.INGEST_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.INGEST_SECRET}")
            conn.setRequestProperty("Content-Type", "application/json")
            val body = """{"steps": $steps, "date": "$day"}"""
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun fail(ctx: Context, why: String): Result {
        Prefs.setLast(ctx, "${stamp()} · $why")
        return Result.failure()
    }

    private fun retryOrFail(ctx: Context, why: String): Result =
        if (runAttemptCount < 3) Result.retry() else fail(ctx, why)

    private fun stamp(): String =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("MMM d HH:mm"))

    companion object {
        /** Mirrors the route's MAX_DAILY_STEPS guard — anything above would 404. */
        const val MAX_DAILY_STEPS = 300_000L
    }
}

object Prefs {
    private const val FILE = "mandosteps"

    fun setLast(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString("last", value).apply()

    fun last(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString("last", null) ?: "never synced"
}
