package com.samar.wallpapercontroller

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
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
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.samar.wallpapercontroller.WallpaperStore.cycleMode
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
    private lateinit var modeDropdown: AutoCompleteTextView
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

    private val modeOptions = listOf(
        CycleMode.INTERVAL to "Fixed interval",
        CycleMode.ON_UNLOCK to "Every unlock",
        CycleMode.MANUAL to "Manual only",
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
            try {
                WallpaperStore.importLock(this, uris)
                runOnUiThread { refreshLockGrid() }
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.error_generic, e.message)) }
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
        modeDropdown = findViewById(R.id.modeDropdown)
        intervalLayout = findViewById(R.id.intervalLayout)
        intervalDropdown = findViewById(R.id.intervalDropdown)

        thumbAdapter = ThumbAdapter { file ->
            WallpaperStore.removeLock(this, file)
            refreshLockGrid()
        }
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

        setUpModeDropdown()
        setUpIntervalDropdown()
        refreshHomePreview()
        refreshLockGrid()
        renderCycleControls()
    }

    private fun setUpModeDropdown() {
        val labels = modeOptions.map { it.second }
        modeDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        val current = modeOptions.indexOfFirst { it.first == cycleMode }.coerceAtLeast(0)
        modeDropdown.setText(labels[current], false)
        modeDropdown.setOnItemClickListener { _, _, position, _ ->
            val wasCycling = cyclingEnabled
            if (wasCycling) stopCycling()
            cycleMode = modeOptions[position].first
            renderCycleControls()
            if (wasCycling && cycleMode != CycleMode.MANUAL) startCycling()
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
            if (cyclingEnabled && cycleMode == CycleMode.INTERVAL) {
                LockCycleWorker.start(this, intervalMinutes)
            }
        }
    }

    private fun startCycling() {
        if (WallpaperStore.lockFiles(this).isEmpty()) {
            toast(getString(R.string.lock_set_empty))
            return
        }
        when (cycleMode) {
            CycleMode.INTERVAL -> {
                LockCycleWorker.advanceNow(this)
                LockCycleWorker.start(this, intervalMinutes)
            }
            CycleMode.ON_UNLOCK -> {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                UnlockCycleService.start(this)
            }
            CycleMode.MANUAL -> return
        }
        cyclingEnabled = true
        renderCycleControls()
        toast(getString(R.string.cycling_started))
    }

    private fun stopCycling() {
        cyclingEnabled = false
        LockCycleWorker.stop(this)
        UnlockCycleService.stop(this)
        renderCycleControls()
    }

    private fun renderCycleControls() {
        intervalLayout.visibility =
            if (cycleMode == CycleMode.INTERVAL) View.VISIBLE else View.GONE
        cycleButton.visibility =
            if (cycleMode == CycleMode.MANUAL) View.GONE else View.VISIBLE
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
