package com.samar.wallpapercontroller

import android.Manifest
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.samar.wallpapercontroller.WallpaperStore.cycleOnInterval
import com.samar.wallpapercontroller.WallpaperStore.cycleOnUnlock
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import com.samar.wallpapercontroller.WallpaperStore.homeSpan
import com.samar.wallpapercontroller.WallpaperStore.intervalMinutes
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var homePreview: ImageView
    private lateinit var homeEmptyLabel: TextView
    private lateinit var homeSpanSwitch: MaterialSwitch
    private lateinit var lockGrid: RecyclerView
    private lateinit var lockEmptyLabel: TextView
    private lateinit var cycleButton: MaterialButton
    private lateinit var checkInterval: MaterialCheckBox
    private lateinit var checkUnlock: MaterialCheckBox
    private lateinit var intervalLayout: TextInputLayout
    private lateinit var intervalDropdown: AutoCompleteTextView
    private lateinit var thumbAdapter: ThumbAdapter

    private val intervalOptions = listOf(
        15L to "15 minutes",
        30L to "30 minutes",
        60L to "1 hour",
        180L to "3 hours",
        360L to "6 hours",
        1440L to "24 hours",
    )

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* cycling works either way; the permission only makes the status notification visible */ }

    private val pickHome = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        executor.execute {
            try {
                WallpaperStore.importHome(this, uri)
                WallpaperStore.setHomeWallpaper(this)
                runOnUiThread {
                    refreshHomePreview()
                    toast(getString(R.string.home_applied))
                }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.error_generic, e.message)) }
            }
        }
    }

    private val pickLock = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        executor.execute {
            val result = WallpaperStore.importLock(this, uris)
            runOnUiThread {
                refreshLockGrid()
                if (result.duplicates > 0) {
                    toast(getString(R.string.import_duplicates, result.duplicates))
                } else if (result.imported < uris.size) {
                    toast(getString(R.string.import_partial, result.imported, uris.size))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        homePreview = findViewById(R.id.homePreview)
        homeEmptyLabel = findViewById(R.id.homeEmptyLabel)
        homeSpanSwitch = findViewById(R.id.homeSpanSwitch)
        lockGrid = findViewById(R.id.lockGrid)
        lockEmptyLabel = findViewById(R.id.lockEmptyLabel)
        cycleButton = findViewById(R.id.cycleButton)
        checkInterval = findViewById(R.id.checkInterval)
        checkUnlock = findViewById(R.id.checkUnlock)
        intervalLayout = findViewById(R.id.intervalLayout)
        intervalDropdown = findViewById(R.id.intervalDropdown)

        thumbAdapter = ThumbAdapter(
            onView = { file -> showImageViewer(file) },
            onRemove = { file ->
                WallpaperStore.removeLock(this, file)
                refreshLockGrid()
            },
        )
        lockGrid.layoutManager = GridLayoutManager(this, 3)
        lockGrid.adapter = thumbAdapter

        findViewById<MaterialButton>(R.id.pickHomeButton).setOnClickListener {
            pickHome.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.pickLockButton).setOnClickListener {
            pickLock.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        findViewById<MaterialButton>(R.id.clearLockButton).setOnClickListener {
            WallpaperStore.clearLock(this)
            stopCycling()
            refreshLockGrid()
        }
        findViewById<MaterialButton>(R.id.nextNowButton).setOnClickListener {
            if (WallpaperStore.lockFiles(this).isEmpty()) {
                toast(getString(R.string.lock_set_empty))
            } else {
                executor.execute {
                    runCatching { WallpaperStore.advanceLockWallpaper(this) }
                }
                toast(getString(R.string.lock_advanced))
            }
        }
        cycleButton.setOnClickListener {
            if (cyclingEnabled) stopCycling() else startCycling()
        }
        findViewById<MaterialButton>(R.id.diagnosticsButton).setOnClickListener {
            showDiagnostics()
        }

        homeSpanSwitch.isChecked = homeSpan
        homeSpanSwitch.setOnCheckedChangeListener { _, checked ->
            homeSpan = checked
            if (WallpaperStore.homeFile(this).exists()) {
                executor.execute {
                    runCatching { WallpaperStore.setHomeWallpaper(this) }
                    runOnUiThread { toast(getString(R.string.home_applied)) }
                }
            }
        }

        setUpModeChecks()
        setUpIntervalDropdown()
        refreshHomePreview()
        refreshLockGrid()
        renderCycleControls()

        // An app update, OEM kill, or force-stop stops cycling without touching
        // the saved state; re-sync reality with that state on every open.
        if (cyclingEnabled) {
            CycleLog.log(this, "app open: resync")
            UnlockWatchdogWorker.start(this)
            if (cycleOnInterval) LockCycleWorker.ensure(this, intervalMinutes)
            if (cycleOnUnlock) {
                ensureUnkillable()
                UnlockCycleService.start(this)
            }
        }
    }

    private fun setUpModeChecks() {
        checkInterval.isChecked = cycleOnInterval
        checkUnlock.isChecked = cycleOnUnlock
        checkInterval.setOnCheckedChangeListener { _, checked ->
            cycleOnInterval = checked
            if (cyclingEnabled) {
                if (checked) {
                    LockCycleWorker.start(this, intervalMinutes)
                } else {
                    LockCycleWorker.stop(this)
                }
            }
            afterModeChange()
        }
        checkUnlock.setOnCheckedChangeListener { _, checked ->
            cycleOnUnlock = checked
            if (checked) ensureUnkillable()
            if (cyclingEnabled) {
                if (checked) {
                    ensureNotificationPermission()
                    UnlockCycleService.start(this)
                } else {
                    UnlockCycleService.stop(this)
                }
            }
            afterModeChange()
        }
    }

    /** Keeps state consistent after a mode checkbox flips: no modes left → stop cycling. */
    private fun afterModeChange() {
        if (cyclingEnabled && !cycleOnInterval && !cycleOnUnlock) {
            stopCycling()
        } else {
            renderCycleControls()
        }
    }

    /**
     * Moto's battery manager ("SleepMode") kills the unlock service unless the
     * app is exempt from battery optimization. Asks the system for the
     * exemption once; no-op if already granted.
     */
    private fun ensureUnkillable() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
            toast(getString(R.string.battery_exemption_why))
        }
    }

    private fun showDiagnostics() {
        val text = TextView(this).apply {
            text = CycleLog.read(this@MainActivity)
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setPadding(32, 16, 32, 16)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics_title)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showImageViewer(file: java.io.File) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val image = ImageView(this).apply {
            setBackgroundColor(Color.BLACK)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(image)
        dialog.show()
        executor.execute {
            val bitmap = decodeThumb(file.path, 2048)
            runOnUiThread { image.setImageBitmap(bitmap) }
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setUpIntervalDropdown() {
        val labels = intervalOptions.map { it.second }
        intervalDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        val current = intervalOptions.indexOfFirst { it.first == intervalMinutes }
            .takeIf { it >= 0 } ?: 1
        intervalDropdown.setText(labels[current], false)
        intervalDropdown.setOnItemClickListener { _, _, position, _ ->
            intervalMinutes = intervalOptions[position].first
            if (cyclingEnabled && cycleOnInterval) {
                LockCycleWorker.start(this, intervalMinutes)
            }
        }
    }

    private fun startCycling() {
        if (WallpaperStore.lockFiles(this).isEmpty()) {
            toast(getString(R.string.lock_set_empty))
            return
        }
        if (!cycleOnInterval && !cycleOnUnlock) {
            toast(getString(R.string.no_mode_selected))
            return
        }
        if (cycleOnInterval) {
            LockCycleWorker.advanceNow(this)
            LockCycleWorker.start(this, intervalMinutes)
        }
        if (cycleOnUnlock) {
            ensureNotificationPermission()
            ensureUnkillable()
            UnlockCycleService.start(this)
        }
        UnlockWatchdogWorker.start(this)
        cyclingEnabled = true
        renderCycleControls()
        toast(getString(R.string.cycling_started))
    }

    private fun stopCycling() {
        cyclingEnabled = false
        LockCycleWorker.stop(this)
        UnlockCycleService.stop(this)
        UnlockWatchdogWorker.stop(this)
        renderCycleControls()
    }

    private fun renderCycleControls() {
        intervalLayout.visibility = if (cycleOnInterval) View.VISIBLE else View.GONE
        cycleButton.visibility =
            if (cycleOnInterval || cycleOnUnlock) View.VISIBLE else View.GONE
        cycleButton.text = getString(
            if (cyclingEnabled) R.string.stop_cycling else R.string.start_cycling
        )
    }

    private fun refreshHomePreview() {
        val file = WallpaperStore.homeFile(this)
        if (file.exists()) {
            executor.execute {
                val bitmap = decodeThumb(file.path, 512)
                runOnUiThread {
                    homePreview.setImageBitmap(bitmap)
                    homeEmptyLabel.visibility = View.GONE
                }
            }
        } else {
            homeEmptyLabel.visibility = View.VISIBLE
        }
    }

    private fun refreshLockGrid() {
        val files = WallpaperStore.lockFiles(this)
        thumbAdapter.submit(files)
        lockEmptyLabel.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun decodeThumb(path: String, targetSize: Int): android.graphics.Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetSize &&
        bounds.outHeight / (sample * 2) >= targetSize
    ) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}
