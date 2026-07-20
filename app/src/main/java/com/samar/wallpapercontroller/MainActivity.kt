package com.samar.wallpapercontroller

import android.app.Dialog
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import com.samar.wallpapercontroller.WallpaperStore.homeSpan
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var homePreview: ImageView
    private lateinit var homeEmptyLabel: TextView
    private lateinit var homeSpanSwitch: MaterialSwitch
    private lateinit var liveStatusLabel: TextView
    private lateinit var enableLiveButton: MaterialButton
    private lateinit var lockGrid: RecyclerView
    private lateinit var lockEmptyLabel: TextView
    private lateinit var cycleButton: MaterialButton
    private lateinit var thumbAdapter: ThumbAdapter

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
        liveStatusLabel = findViewById(R.id.liveStatusLabel)
        enableLiveButton = findViewById(R.id.enableLiveButton)
        lockGrid = findViewById(R.id.lockGrid)
        lockEmptyLabel = findViewById(R.id.lockEmptyLabel)
        cycleButton = findViewById(R.id.cycleButton)

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
        enableLiveButton.setOnClickListener { openLiveWallpaperPicker() }

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

        refreshHomePreview()
        refreshLockGrid()
        renderCycleControls()
    }

    override fun onResume() {
        super.onResume()
        renderLiveStatus()
    }

    private fun renderLiveStatus() {
        val active = HomeWallpaperService.isActive(this)
        liveStatusLabel.setText(
            if (active) R.string.live_status_active else R.string.live_status_inactive
        )
        enableLiveButton.visibility = if (active) View.GONE else View.VISIBLE
    }

    private fun openLiveWallpaperPicker() {
        val direct = runCatching {
            startActivity(HomeWallpaperService.pickerIntent(this))
        }
        if (direct.isFailure) {
            runCatching {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            }.onFailure { toast(getString(R.string.live_picker_missing)) }
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

    /**
     * Cycling runs only inside the live wallpaper engine, so enabling it also
     * pushes the user to set the live wallpaper when it isn't already active —
     * otherwise nothing would advance.
     */
    private fun startCycling() {
        if (WallpaperStore.lockFiles(this).isEmpty()) {
            toast(getString(R.string.lock_set_empty))
            return
        }
        cyclingEnabled = true
        executor.execute { runCatching { WallpaperStore.advanceLockWallpaper(this) } }
        renderCycleControls()
        if (HomeWallpaperService.isActive(this)) {
            toast(getString(R.string.cycling_started))
        } else {
            toast(getString(R.string.cycling_needs_live))
            openLiveWallpaperPicker()
        }
    }

    private fun stopCycling() {
        cyclingEnabled = false
        renderCycleControls()
    }

    private fun renderCycleControls() {
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
