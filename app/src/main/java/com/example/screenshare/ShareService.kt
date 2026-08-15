package com.example.screenshare

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that captures the device screen with MediaProjection and
 * pushes JPEG frames to a WebSocket server on [EXTRA_SERVER_URL].
 */
class ShareService : Service(), ImageReader.OnImageAvailableListener {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SERVER_URL = "extra_server_url"

        private const val CHANNEL_ID = "screenshare_channel"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "ScreenShare"

        /** Approximate frames per second sent to the server. */
        private const val FRAME_INTERVAL_MS = 100L

        @Volatile
        var status: String = "Idle"
            private set

        @Volatile
        var isStreaming = false
            private set

        @Volatile
        var statusListener: ((String) -> Unit)? = null

        @Volatile
        var previewListener: ((Bitmap) -> Unit)? = null

        fun updateStatus(msg: String) {
            status = msg
            statusListener?.invoke(msg)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var workerThread: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var webSocket: WebSocket? = null

    private var width = 0
    private var height = 0
    private var lastSendTime = 0L
    private var lastPreviewTime = 0L
    private var serverUrl = ""

    private val stopped = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: ""
        if (serverUrl.isBlank()) {
            updateStatus("Server URL is empty.")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            }

        startAsForeground()
        startStreaming(resultCode, resultData)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- setup

    private fun startAsForeground() {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.streaming_ongoing))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startStreaming(resultCode: Int, resultData: Intent?) {
        if (resultData == null) {
            updateStatus("Failed to start: no capture session data.")
            stopSelf()
            return
        }
        val manager = getSystemService(MediaProjectionManager::class.java)

        val newProjection = try {
            manager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            updateStatus("Screen capture was not granted.")
            stopSelf()
            return
        }
        projection = newProjection

        newProjection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by system")
                    cleanup()
                }
            },
            null
        )

        workerThread = HandlerThread("ScreenShareCapturer").also { it.start() }
        workerHandler = Handler(workerThread!!.looper)

        val metrics = resources.displayMetrics
        width = metrics.widthPixels
        height = metrics.heightPixels

        imageReader = ImageReader.newInstance(
            width, height, PixelFormat.RGBA_8888, 2
        )
        imageReader?.setOnImageAvailableListener(this, workerHandler)

        try {
            virtualDisplay = newProjection.createVirtualDisplay(
                "ScreenShare",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface,
                null,
                workerHandler
            )
        } catch (e: Exception) {
            updateStatus("Failed to create virtual display: ${e.message}")
            stopSelf()
            return
        }

        connectWebSocket()
    }

    private fun connectWebSocket() {
        val client = OkHttpClient()
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                val hello = buildString {
                    append("{\"type\":\"stream\"")
                    append(",\"width\":").append(width)
                    append(",\"height\":").append(height)
                    append(",\"device\":\"").append(Build.MODEL).append("\"}")
                }
                ws.send(hello)
                isStreaming = true
                updateStatus("Streaming ${width}x$height to $serverUrl")
            }

            override fun onFailure(
                ws: WebSocket,
                t: Throwable,
                response: Response?
            ) {
                updateStatus("Connection error: ${t.message}")
                cleanup()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                updateStatus("Connection closed by server.")
                cleanup()
            }
        })
    }

    // ------------------------------------------------------------ capture

    override fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val jpeg = imageToJpeg(image)
            val now = SystemClock.elapsedRealtime()

            // Throttle network writes to ~10 fps.
            if (jpeg != null && now - lastSendTime >= FRAME_INTERVAL_MS) {
                webSocket?.send(ByteString.of(*jpeg))
                lastSendTime = now
            }

            // Cheap live preview at ~3 fps.
            if (jpeg != null && now - lastPreviewTime >= 300L) {
                val full = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                val scaled = Bitmap.createScaledBitmap(
                    full, 360, (height * 360f / width).toInt(), true
                )
                if (full !== scaled) full.recycle()
                previewListener?.invoke(scaled)
                lastPreviewTime = now
            }
        } finally {
            image.close()
        }
    }

    private fun imageToJpeg(image: Image): ByteArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width
        val bitmapWidth = width + rowPadding / pixelStride

        val source = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(buffer)
        val cropped = if (bitmapWidth == width) {
            source
        } else {
            Bitmap.createBitmap(source, 0, 0, width, height).also {
                source.recycle()
            }
        }

        val baos = ByteArrayOutputStream()
        val ok = cropped.compress(Bitmap.CompressFormat.JPEG, 70, baos)
        cropped.recycle()
        return if (ok) baos.toByteArray() else null
    }

    // -------------------------------------------------------------- teardown

    private fun cleanup() {
        if (!stopped.compareAndSet(false, true)) return

        isStreaming = false
        updateStatus("Stopped")

        runCatching { webSocket?.send("{\"type\":\"stop\"}") }
        runCatching { webSocket?.close(1000, "client stopped") }
        webSocket = null

        virtualDisplay?.let { runCatching { it.release() } }
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.let { runCatching { it.close() } }
        imageReader = null

        workerHandler = null
        workerThread?.let { runCatching { it.quitSafely() } }
        workerThread = null

        projection?.let { runCatching { it.stop() } }
        projection = null

        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen Share",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}