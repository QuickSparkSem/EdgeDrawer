package com.edgedrawer.app

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var triggerView: EdgeTriggerView? = null
    private var drawerView: EdgeDrawerView? = null
    private var isDrawerOpen = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        repository = AppRepository(this)
        repository.startListening()

        startAsForeground()
        if (Settings.canDrawOverlays(this)) setupTriggerHandle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_OPEN_DRAWER -> openDrawer(fromContinuousSwipe = false, initialSlot = EdgeIndexSlots.RECENT_SLOT_INDEX)
            ACTION_RELOAD_CONFIG -> {
                removeTriggerHandle()
                setupTriggerHandle()
            }
            else -> {
                if (triggerView == null) setupTriggerHandle() else triggerView?.invalidate()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EdgeDrawer Gesture", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene attiva la gesture verticale A-Z"
            }
        )

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("EdgeDrawer attivo")
            .setContentText("Scorri in alto o in basso sul bordo per aprire l'indice A-Z")
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun setupTriggerHandle() {
        if (triggerView != null) return

        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels.toFloat()
        val screenH = resources.displayMetrics.heightPixels.toFloat()
        val m = EdgeBarMetrics.compute(screenW, screenH, density, prefs)
        val handle = EdgeTriggerView(this, prefs)

        val params = WindowManager.LayoutParams(
            m.indexWidthPx.toInt(),
            m.lettersHeightPx.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.isRightSide) Gravity.END else Gravity.START) or Gravity.TOP
            x = 0
            y = m.lettersTopY.toInt()
        }

        var downX = 0f
        var downY = 0f
        var triggeredInThisTouch = false
        var ignoredHorizontalGesture = false
        val verticalTriggerThreshold = 8f * density
        val horizontalCancelThreshold = 10f * density

        handle.setOnTouchListener { _, event ->
            val currentRawX = event.rawX
            val currentRawY = event.rawY

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = currentRawX
                    downY = currentRawY
                    triggeredInThisTouch = false
                    ignoredHorizontalGesture = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ignoredHorizontalGesture) return@setOnTouchListener false

                    if (!triggeredInThisTouch) {
                        val dx = abs(currentRawX - downX)
                        val dy = abs(currentRawY - downY)

                        if (dx >= horizontalCancelThreshold && dx > dy * 0.85f) {
                            ignoredHorizontalGesture = true
                            return@setOnTouchListener false
                        }

                        if (dy >= verticalTriggerThreshold && dy > dx * 1.45f) {
                            triggeredInThisTouch = true
                            HapticHelper.trigger(this, prefs, HapticHelper.PulseType.TICK)
                            openDrawer(fromContinuousSwipe = true, initialSlot = handle.getSlotAtY(event.y))
                        }
                    } else {
                        drawerView?.handleExternalDrag(currentRawX, currentRawY, MotionEvent.ACTION_MOVE)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (triggeredInThisTouch) {
                        drawerView?.handleExternalDrag(currentRawX, currentRawY, event.actionMasked)
                    }
                    triggeredInThisTouch = false
                    ignoredHorizontalGesture = false
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

    private fun openDrawer(fromContinuousSwipe: Boolean, initialSlot: Int) {
        if (isDrawerOpen) return
        isDrawerOpen = true
        triggerView?.alpha = 0f

        val overlay = EdgeDrawerView(
            context = this,
            repository = repository,
            prefs = prefs,
            onLaunchApp = { appEntry, windowMode ->
                launchTargetApp(appEntry.packageName, windowMode)
                mainHandler.postDelayed({ closeDrawer() }, 85L)
            },
            onDismiss = { closeDrawer() }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        drawerView = overlay
        windowManager.addView(overlay, params)
        overlay.prepareOpen(fromContinuousSwipe, initialSlot)
    }

    private fun closeDrawer() {
        val current = drawerView
        drawerView = null
        isDrawerOpen = false
        triggerView?.alpha = 1f
        if (current != null) {
            try {
                windowManager.removeView(current)
            } catch (_: Exception) {
            }
        }
    }

    private fun createBaseLaunchIntent(packageName: String): Intent? {
        val resolved = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        val component = resolved.component ?: return resolved
        return Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setComponent(component)
            setPackage(packageName)
        }
    }

    private fun launchTargetApp(packageName: String, mode: LaunchWindowMode) {
        val baseIntent = createBaseLaunchIntent(packageName) ?: return
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        when (mode) {
            LaunchWindowMode.NORMAL -> {
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                try {
                    startActivity(baseIntent)
                } catch (_: Exception) {
                }
            }

            LaunchWindowMode.POPUP -> {
                val marginX = (screenW * 0.08f).toInt()
                val marginY = (screenH * 0.16f).toInt()
                val popupBounds = Rect(marginX, marginY, screenW - marginX, screenH - marginY)

                val popupIntent = Intent(baseIntent).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                            Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    )
                    putExtra("miui.intent.extra.OPEN_IN_FREEFORM", true)
                    putExtra("android.activity.launchBounds", popupBounds)
                }

                val freeformOptions = createWindowModeOptions(windowingMode = 5, bounds = popupBounds)
                try {
                    startActivity(popupIntent, freeformOptions.toBundle())
                } catch (_: Exception) {
                    try {
                        startActivity(popupIntent, ActivityOptions.makeBasic().apply { setLaunchBounds(popupBounds) }.toBundle())
                    } catch (_: Exception) {
                        try {
                            startActivity(popupIntent)
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            LaunchWindowMode.SPLIT_TOP, LaunchWindowMode.SPLIT_BOTTOM -> {
                val isTop = mode == LaunchWindowMode.SPLIT_TOP
                val bounds = if (isTop) Rect(0, 0, screenW, screenH / 2) else Rect(0, screenH / 2, screenW, screenH)
                val acc = EdgeAccessibilityService.instance

                if (acc != null) {
                    acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    mainHandler.postDelayed({
                        val splitIntent = Intent(baseIntent).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                            )
                        }
                        try {
                            startActivity(splitIntent)
                        } catch (_: Exception) {
                        }
                    }, 260L)
                } else {
                    val splitIntent = Intent(baseIntent).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        )
                    }
                    val opts = createWindowModeOptions(windowingMode = if (isTop) 3 else 4, bounds = bounds)
                    try {
                        startActivity(splitIntent, opts.toBundle())
                    } catch (_: Exception) {
                        try {
                            startActivity(splitIntent)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun createWindowModeOptions(windowingMode: Int, bounds: Rect): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(bounds)
        try {
            ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                .invoke(options, windowingMode)
        } catch (_: Exception) {
        }
        return options
    }

    override fun onDestroy() {
        closeDrawer()
        removeTriggerHandle()
        repository.stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class EdgeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: EdgeAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)
            if (prefs.isServiceEnabled && Settings.canDrawOverlays(context)) {
                ContextCompat.startForegroundService(context, Intent(context, EdgeOverlayService::class.java))
            }
        }
    }
}
