package dev.anthonyta.mandosteps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val healthPermissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
    )

    private val requestPermission =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) {
            refresh()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.sync_now).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.battery).setOnClickListener { askBatteryExemption() }

        // Live status: whenever the manual sync finishes, re-read the prefs line.
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(SYNC_NOW)
            .observe(this) { refresh() }

        schedulePeriodic()
        ensurePermission()
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // Every foreground IS a sync: opening the app (or tapping through from
        // the hub's steps row) refreshes the count without hunting for a button.
        syncNow()
    }

    private fun syncNow() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            SYNC_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(connected())
                .build(),
        )
        findViewById<TextView>(R.id.status).text = getString(R.string.syncing)
    }

    private fun schedulePeriodic() {
        // UPDATE, not KEEP: KEEP pins whatever interval was enqueued by the first
        // install forever, so a new APK changing the cadence would silently not.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily-sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(connected())
                .build(),
        )
    }

    private fun ensurePermission() {
        val status = findViewById<TextView>(R.id.status)
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                val client = HealthConnectClient.getOrCreate(this@MainActivity)
                val granted = client.permissionController.getGrantedPermissions()
                // Only what's actually missing: an update that adds a read (sleep)
                // must not re-ask for the one already granted (steps).
                val missing = healthPermissions - granted
                if (missing.isNotEmpty()) requestPermission.launch(missing)
            }
            else -> status.text = getString(R.string.hc_unavailable)
        }
    }

    private fun askBatteryExemption() {
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            )
        )
    }

    private fun refresh() {
        findViewById<TextView>(R.id.status).text = Prefs.last(this)
        val pm = getSystemService(PowerManager::class.java)
        findViewById<Button>(R.id.battery).text =
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                getString(R.string.battery_ok)
            } else {
                getString(R.string.battery_ask)
            }
    }

    private fun connected() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private companion object {
        const val SYNC_NOW = "sync-now"
    }
}
