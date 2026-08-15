package com.example.screenshare

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var previewImage: ImageView

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                ShareService.updateStatus("Starting capture…")
                val intent = Intent(this, ShareService::class.java)
                    .putExtra(ShareService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ShareService.EXTRA_RESULT_DATA, result.data)
                    .putExtra(
                        ShareService.EXTRA_SERVER_URL,
                        serverUrlInput.text.toString().trim()
                    )
                ContextCompat.startForegroundService(this, intent)
            } else {
                updateStatus("Screen capture permission was not granted.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serverUrlInput = findViewById(R.id.server_url_input)
        toggleButton = findViewById(R.id.toggle_button)
        statusText = findViewById(R.id.status_text)
        previewImage = findViewById(R.id.preview_image)
        serverUrlInput.setText("ws://10.0.2.2:8765/stream")

        toggleButton.setOnClickListener { onToggleShare() }
    }

    override fun onResume() {
        super.onResume()
        ShareService.statusListener = { msg -> runOnUiThread { updateStatus(msg) } }
        ShareService.previewListener = { bmp ->
            previewImage.post {
                if (!isFinishing) previewImage.setImageBitmap(bmp)
            }
        }
        updateStatus(ShareService.status)
        if (ShareService.isStreaming) {
            toggleButton.text = getString(R.string.stop_text)
        } else {
            toggleButton.text = getString(R.string.start_text)
        }
    }

    override fun onPause() {
        super.onPause()
        ShareService.statusListener = null
        ShareService.previewListener = null
    }

    private fun onToggleShare() {
        if (ShareService.isStreaming) {
            updateStatus("Stopping stream…")
            stopService(Intent(this, ShareService::class.java))
            toggleButton.text = getString(R.string.start_text)
        } else {
            requestNotificationPermissionIfNeeded()
            val projectionManager =
                getSystemService(MediaProjectionManager::class.java)
            captureLauncher.launch(
                projectionManager.createScreenCaptureIntent()
            )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun updateStatus(msg: String) {
        statusText.text = msg
    }
}