package com.rotatefix.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.camera2.CameraManager
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import kotlin.math.hypot
import kotlin.math.max

/**
 * Runs in the background:
 *  1. Notices when any app starts using a camera and shows the floating bubble.
 *  2. Watches the photo library for new pictures in DCIM and rewrites their
 *     EXIF orientation tag to match the bubble (lossless — pixels untouched).
 */
class OrientService : Service() {

    companion object {
        const val ACTION_TOGGLE_BUBBLE = "com.rotatefix.app.TOGGLE_BUBBLE"
        const val ACTION_STOP = "com.rotatefix.app.STOP"
        private const val CHANNEL = "rotatefix"
        private const val NOTIF_ID = 1
        private const val TAG = "RotateFix"

        /** Show the bubble only if the camera stays busy this long (filters face unlock). */
        private const val SHOW_DELAY_MS = 600L
        /** Hide after the camera has been free this long. */
        private const val HIDE_DELAY_MS = 1500L
        /** Photos first seen up to this long after the bubble closes still get fixed. */
        private const val GRACE_MS = 15_000L
        private const val SCAN_DEBOUNCE_MS = 1200L

        @Volatile
        var running = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var worker: HandlerThread
    private lateinit var io: Handler
    private lateinit var wm: WindowManager
    private lateinit var cameras: CameraManager

    private var bubble: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    // Main-thread state
    private val busyCameras = HashSet<String>()
    private var cameraSession = false
    private var manualBubble = false

    // Read from the IO thread
    @Volatile private var mode = Mode.UP
    @Volatile private var bubbleVisible = false
    @Volatile private var lastHiddenAt = 0L

    // IO-thread only: media id -> target rotation (-1 = leave alone)
    private val targets = HashMap<Long, Int>()
    private val startedAtSec = System.currentTimeMillis() / 1000 - 5

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        wm = getSystemService(WindowManager::class.java)
        cameras = getSystemService(CameraManager::class.java)
        worker = HandlerThread("rotatefix-io").also { it.start() }
        io = Handler(worker.looper)

        createChannel()
        startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)

        cameras.registerAvailabilityCallback(cameraCallback, main)
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_BUBBLE -> {
                manualBubble = !(manualBubble || cameraSession)
                if (!manualBubble) cameraSession = false
                refreshBubble()
            }
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { cameras.unregisterAvailabilityCallback(cameraCallback) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(mediaObserver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        removeBubble()
        worker.quitSafely()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- camera

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraUnavailable(cameraId: String) {
            busyCameras.add(cameraId)
            main.removeCallbacks(hideRunnable)
            main.removeCallbacks(showRunnable)
            main.postDelayed(showRunnable, SHOW_DELAY_MS)
        }

        override fun onCameraAvailable(cameraId: String) {
            busyCameras.remove(cameraId)
            if (busyCameras.isEmpty()) {
                main.removeCallbacks(showRunnable)
                main.postDelayed(hideRunnable, HIDE_DELAY_MS)
            }
        }
    }

    private val showRunnable = Runnable {
        if (busyCameras.isNotEmpty() && !cameraSession) {
            cameraSession = true
            mode = Mode.UP          // every camera session starts in portrait
            refreshBubble()
        }
    }

    private val hideRunnable = Runnable {
        if (busyCameras.isEmpty() && cameraSession) {
            cameraSession = false
            refreshBubble()
        }
    }

    // ---------------------------------------------------------------- bubble

    private fun refreshBubble() {
        val want = cameraSession || manualBubble
        if (want && bubble == null) addBubble()
        if (!want && bubble != null) {
            removeBubble()
            lastHiddenAt = System.currentTimeMillis()
            scheduleScan(SCAN_DEBOUNCE_MS)
        }
        bubbleVisible = bubble != null
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addBubble() {
        if (!Settings.canDrawOverlays(this)) return
        val size = dp(56)
        val metrics = resources.displayMetrics
        val saved = Prefs.position(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = saved?.first ?: (metrics.widthPixels - size - dp(16))
            y = saved?.second ?: (metrics.heightPixels / 3)
        }
        val view = BubbleView(this).also { it.mode = mode }
        attachTouch(view, lp)
        try {
            wm.addView(view, lp)
            bubble = view
            params = lp
        } catch (e: Exception) {
            Log.e(TAG, "Could not show bubble", e)
        }
    }

    private fun removeBubble() {
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        params = null
        bubbleVisible = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(view: BubbleView, lp: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longFired = false
        val longPress = Runnable {
            longFired = true
            setMode(if (mode == Mode.OFF) Mode.UP else Mode.OFF)
        }

        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false; longFired = false
                    main.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && hypot(dx, dy) > slop) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(view, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (moved) {
                        Prefs.savePosition(this, lp.x, lp.y)
                    } else if (!longFired) {
                        setMode(
                            when (mode) {
                                Mode.UP -> Mode.RIGHT
                                Mode.RIGHT -> Mode.LEFT
                                Mode.LEFT -> Mode.UP
                                Mode.OFF -> Mode.UP
                            }
                        )
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            true
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        bubble?.let {
            it.mode = m
            it.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        Toast.makeText(this, m.label, Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- photos

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleScan(SCAN_DEBOUNCE_MS)
    }

    private val scanRunnable = Runnable {
        try { scan() } catch (e: Exception) { Log.e(TAG, "Scan failed", e) }
    }

    private fun scheduleScan(delayMs: Long) {
        io.removeCallbacks(scanRunnable)
        io.postDelayed(scanRunnable, delayMs)
    }

    private fun active() =
        bubbleVisible || System.currentTimeMillis() - lastHiddenAt < GRACE_MS

    /** Runs on the IO thread. */
    private fun scan() {
        if (!Environment.isExternalStorageManager()) return
        val since = max(startedAtSec, System.currentTimeMillis() / 1000 - 300)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE
        )
        val selection =
            "${MediaStore.Images.Media.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0"

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val path = c.getString(1) ?: continue
                val mime = c.getString(2) ?: ""
                if (!path.contains("/DCIM/")) continue
                if (mime != "image/jpeg" && mime != "image/png" && mime != "image/webp") continue

                val known = targets[id]
                val target: Int = if (known != null) {
                    known
                } else {
                    val m = mode
                    if (!active() || m == Mode.OFF) {
                        targets[id] = -1
                        continue
                    }
                    val base = readRotation(path) ?: continue   // retry on next change
                    val t = (base + m.extra) % 360
                    targets[id] = t
                    t
                }
                if (target >= 0) fix(path, target)
            }
        }
    }

    private fun readRotation(path: String): Int? =
        try { ExifInterface(path).rotationDegrees } catch (e: Exception) { null }

    private fun fix(path: String, target: Int) {
        try {
            val exif = ExifInterface(path)
            val current = exif.rotationDegrees
            if (current == target) return
            exif.rotate((target - current + 360) % 360)
            exif.saveAttributes()
            MediaScannerConnection.scanFile(this, arrayOf(path), null, null)
            Log.i(TAG, "Set $path to $target°")
        } catch (e: Exception) {
            Log.e(TAG, "Could not fix $path", e)
        }
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "RotateFix", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun servicePending(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code,
            Intent(this, OrientService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val icon = Icon.createWithResource(this, R.drawable.ic_stat)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("RotateFix is on")
            .setContentText("The bubble appears when the camera opens")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(icon, "Show / hide bubble", servicePending(ACTION_TOGGLE_BUBBLE, 1)).build())
            .addAction(Notification.Action.Builder(icon, "Stop", servicePending(ACTION_STOP, 2)).build())
            .build()
    }
}
