package com.edgedrawer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class EdgeOverlayService : Service() {

    companion object {
        const val ACTION_OPEN_DRAWER = "com.edgedrawer.app.ACTION_OPEN_DRAWER"
        const val ACTION_RELOAD_CONFIG = "com.edgedrawer.app.ACTION_RELOAD_CONFIG"
        private const val CHANNEL_ID = "edge_drawer_channel"
        private const val NOTIFICATION_ID = 101
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PrefsManager
    private lateinit var repository: AppRepository

    private var triggerView: View? = null
    private var drawerView: EdgeDrawerView? = null
    private var isDrawerOpen = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        repository = AppRepository(this)
        repository.startListening()

        startAsForeground()
        if (Settings.canDrawOverlays(this)) {
            setupTriggerHandle()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_OPEN_DRAWER -> openDrawer(fromContinuousSwipe = false, initialY = 300f)
            ACTION_RELOAD_CONFIG -> {
                removeTriggerHandle()
                setupTriggerHandle()
            }
            else -> {
                if (triggerView == null) {
                    setupTriggerHandle()
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "EdgeDrawer Gesture",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene attiva la gesture laterale A-Z"
        }
        manager.createNotificationChannel(channel)

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EdgeDrawer attivo")
            .setContentText("Scorri dal bordo per aprire l'indice A-Z")
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupTriggerHandle() {
        if (triggerView != null) return

        val density = resources.displayMetrics.density
        val widthPx = (prefs.triggerWidthDp * density).toInt()
        val heightPx = (resources.displayMetrics.heightPixels * 0.65f).toInt()

        val handle = View(this).apply {
            // Leggerissima linea guida quasi invisibile sul bordo
            setBackgroundColor(Color.argb(28, 255, 255, 255))
        }

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.isRightSide) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        }

        var downX = 0f
        var downY = 0f
        var triggeredInThisTouch = false
        val swipeThreshold = 16 * density

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    triggeredInThisTouch = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggeredInThisTouch) {
                        val dx = if (prefs.isRightSide) downX - event.rawX else event.rawX - downX
                        val dy = abs(event.rawY - downY)
                        if (dx > swipeThreshold && dx > dy * 0.7f) {
                            triggeredInThisTouch = true
                            openDrawer(fromContinuousSwipe = true, initialY = event.rawY)
                        }
                    } else {
                        drawerView?.handleExternalDrag(event.rawX, event.rawY, MotionEvent.ACTION_MOVE)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (triggeredInThisTouch) {
                        drawerView?.handleExternalDrag(event.rawX, event.rawY, event.action)
                    }
                    triggeredInThisTouch = false
                    true
                }
                else -> false
            }
        }

        triggerView = handle
        windowManager.addView(handle, params)
    }

    private fun removeTriggerHandle() {
        triggerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        triggerView = null
    }

    private fun openDrawer(fromContinuousSwipe: Boolean, initialY: Float) {
        if (isDrawerOpen) return
        isDrawerOpen = true

        val overlay = EdgeDrawerView(
            context = this,
            repository = repository,
            prefs = prefs,
            onLaunchApp = { appEntry ->
                closeDrawer()
                launchTargetApp(appEntry.packageName)
            },
            onDismiss = {
                closeDrawer()
            }
        )

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.blurRadius > 0) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.blurRadius > 0) {
            params.blurBehindRadius = prefs.blurRadius
        }

        drawerView = overlay
        overlay.alpha = 0f
        windowManager.addView(overlay, params)

        overlay.post {
            overlay.prepareOpen(fromContinuousSwipe, initialY)
            overlay.animate().alpha(1f).setDuration(140L).start()
        }
    }

    private fun closeDrawer() {
        val current = drawerView ?: return
        drawerView = null
        isDrawerOpen = false
        try {
            windowManager.removeView(current)
        } catch (_: Exception) {
        }
    }

    private fun launchTargetApp(packageName: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
    }

    override fun onDestroy() {
        closeDrawer()
        removeTriggerHandle()
        repository.stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
