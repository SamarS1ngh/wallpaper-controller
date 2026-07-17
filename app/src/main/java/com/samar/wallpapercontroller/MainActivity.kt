package com.samar.wallpapercontroller

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.samar.wallpapercontroller.WallpaperStore.cyclingEnabled
import com.samar.wallpapercontroller.WallpaperStore.intervalMinutes
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var homePreview: ImageView
    private lateinit var homeEmptyLabel: TextView
    private lateinit var lockGrid: RecyclerView
    private lateinit var lockEmptyLabel: TextView
    private lateinit var cycleButton: MaterialButton
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
        lockGrid = findViewById(R.id.lockGrid)
        lockEmptyLabel = findViewById(R.id.lockEmptyLabel)
        cycleButton = findViewById(R.id.cycleButton)
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
                LockCycleWorker.advanceNow(this)
                toast(getString(R.string.lock_advanced))
            }
        }
        cycleButton.setOnClickListener {
            if (cyclingEnabled) stopCycling() else startCycling()
        }

        setUpIntervalDropdown()
        refreshHomePreview()
        refreshLockGrid()
        renderCycleButton()
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
            if (cyclingEnabled) {
                LockCycleWorker.start(this, intervalMinutes)
            }
        }
    }

    private fun startCycling() {
        if (WallpaperStore.lockFiles(this).isEmpty()) {
            toast(getString(R.string.lock_set_empty))
            return
        }
        cyclingEnabled = true
        LockCycleWorker.advanceNow(this)
        LockCycleWorker.start(this, intervalMinutes)
        renderCycleButton()
        toast(getString(R.string.cycling_started))
    }

    private fun stopCycling() {
        cyclingEnabled = false
        LockCycleWorker.stop(this)
        renderCycleButton()
    }

    private fun renderCycleButton() {
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
                    homeEmptyLabel.isVisible(false)
                }
            }
        } else {
            homeEmptyLabel.isVisible(true)
        }
    }

    private fun refreshLockGrid() {
        val files = WallpaperStore.lockFiles(this)
        thumbAdapter.submit(files)
        lockEmptyLabel.isVisible(files.isEmpty())
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun TextView.isVisible(visible: Boolean) {
    visibility = if (visible) TextView.VISIBLE else TextView.GONE
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
