package dev.anthonyta.mandosteps

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Reads the last [WINDOW_DAYS] days of step totals plus the sleep of the same
 * nights from Health Connect and POSTs each to the hub's ingest routes. A week
 * rides along on every run so a stretch where Samsung kept the job asleep
 * self-heals on the next run that gets through — the routes overwrite same-day
 * entries.
 */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (HealthConnectClient.getSdkStatus(ctx) != HealthConnectClient.SDK_AVAILABLE)
            return fail(ctx, "Health Connect unavailable")

        val client = HealthConnectClient.getOrCreate(ctx)
        val granted = client.permissionController.getGrantedPermissions()
        if (HealthPermission.getReadPermission(StepsRecord::class) !in granted)
            return fail(ctx, "steps permission not granted — open the app")

        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = today.minusDays(WINDOW_DAYS - 1L)
        val report = StringBuilder()

        // One grouped read for the whole window rather than a read per day.
        val totals = try {
            client.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    setOf(StepsRecord.COUNT_TOTAL),
                    TimeRangeFilter.between(first.atStartOfDay(), today.plusDays(1).atStartOfDay()),
                    Period.ofDays(1),
                )
            ).associate { it.startTime.toLocalDate() to it.result[StepsRecord.COUNT_TOTAL] }
        } catch (e: Exception) {
            return retryOrFail(ctx, "Health Connect read failed: ${e.message}")
        }

        var stepDays = 0
        for (day in window(first, today)) {
            // A day Health Connect holds nothing for (phone off, before install) is
            // skipped, never posted as 0 — the same rule sleep follows.
            val steps = (totals[day] ?: continue).coerceIn(0L, MAX_DAILY_STEPS)
            val code = try {
                post(BuildConfig.INGEST_URL, """{"steps": $steps, "date": "$day"}""")
            } catch (e: Exception) {
                return retryOrFail(ctx, "POST failed: ${e.message}")
            }
            if (code != 200) return retryOrFail(ctx, "POST $day → HTTP $code")
            stepDays++
        }
        report.append("steps $stepDays days ✓ (today ${totals[today] ?: 0})  ")

        // Sleep runs only once the step posts have landed, so nothing below can
        // cost a good steps run. A missing grant is not a failure: the permission
        // arrives with an APK update, and an owner who never re-opens the app must
        // keep getting steps regardless.
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) !in granted) {
            report.append("sleep: grant in app")
            return done(ctx, report)
        }

        val nights = try {
            // From the evening before the first day: that night ends on its morning.
            val filter = TimeRangeFilter.between(
                first.minusDays(1).atStartOfDay(zone).toInstant(),
                Instant.now(),
            )
            val sessions = mutableListOf<SleepSessionRecord>()
            var page: String? = null
            do {
                val res = client.readRecords(
                    ReadRecordsRequest(SleepSessionRecord::class, filter, pageToken = page)
                )
                sessions += res.records
                page = res.pageToken
            } while (page != null)
            sessions
                // A session belongs to the day it ENDED — the morning you woke.
                .groupBy { it.endTime.atZone(zone).toLocalDate() }
                .mapValues { (_, sessions) ->
                    sessions.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }
                        .coerceIn(0L, MAX_NIGHT_MINUTES)
                }
        } catch (e: Exception) {
            report.append("sleep read failed: ${e.message}")
            return retryOrFail(ctx, report.toString().trim())
        }

        var sleepNights = 0
        for (day in window(first, today)) {
            // No session for a day is skipped, never posted as 0 — an absent
            // record means "unknown", not "slept nothing".
            val minutes = nights[day] ?: continue
            val code = try {
                post(SLEEP_URL, """{"minutes": $minutes, "date": "$day"}""")
            } catch (e: Exception) {
                report.append("sleep POST failed: ${e.message}")
                return retryOrFail(ctx, report.toString().trim())
            }
            if (code != 200) {
                report.append("sleep POST $day → HTTP $code")
                return retryOrFail(ctx, report.toString().trim())
            }
            sleepNights++
        }
        report.append("sleep $sleepNights nights ✓ (since $first)")

        return done(ctx, report)
    }

    /** Every day from [first] to [last], inclusive, oldest first. */
    private fun window(first: LocalDate, last: LocalDate): Sequence<LocalDate> =
        generateSequence(first) { it.plusDays(1) }.takeWhile { it <= last }

    private fun post(url: String, body: String): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.INGEST_SECRET}")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun done(ctx: Context, report: StringBuilder): Result {
        Prefs.setLast(ctx, "${stamp()} · ${report.toString().trim()}")
        return Result.success()
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
        /** How many days, ending today, every run re-posts: a week of missed runs heals. */
        const val WINDOW_DAYS = 7

        /** Mirrors the route's MAX_DAILY_STEPS guard — anything above would 404. */
        const val MAX_DAILY_STEPS = 300_000L

        /** Mirrors the sleep route's ceiling — a night can't exceed a day. */
        const val MAX_NIGHT_MINUTES = 1440L

        /**
         * The sleep ingest sits beside the steps one, so it's derived rather than
         * a second BuildConfig field — the build command stays a single secret.
         */
        val SLEEP_URL: String = BuildConfig.INGEST_URL.replaceAfterLast('/', "sleep")
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
