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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
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
            ACTION_OPEN_DRAWER -> openDrawer(
                fromContinuousSwipe = false,
                initialSlot = 0,
                initialRawY = 300f
            )
            ACTION_RELOAD_CONFIG -> {
                removeTriggerHandle()
                setupTriggerHandle()
            }
            else -> {
                if (triggerView == null) {
                    setupTriggerHandle()
                } else {
                    triggerView?.invalidate()
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
            description = "Mantiene attiva la gesture verticale A-Z"
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
            .setContentText("Scorri in alto o in basso sul bordo per aprire l'indice A-Z")
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

    /**
     * Posiziona il trigger esattamente allineato alla stessa posizione verticale e altezza
     * dell'alfabeto del pannello, attivandosi con uno scorrimento verticale (alto/basso)
     * così da non cozzare mai con la gesture Indietro di sistema.
     */
    private fun setupTriggerHandle() {
        if (triggerView != null) return

        val density = resources.displayMetrics.density
        val screenH = resources.displayMetrics.heightPixels
        val scale = (prefs.panelScalePercent / 100f).coerceIn(0.65f, 1.35f)

        val widthPx = ((prefs.triggerWidthDp * density).coerceAtLeast(24f * density) * scale).toInt()
        val heightRatio = (prefs.barHeightPercent / 100f).coerceIn(0.30f, 0.90f)
        val lettersHeightPx = (screenH * heightRatio).toInt()

        val pad = 6f * density
        val letterStepH = (lettersHeightPx - pad * 2f) / EdgeIndexSlots.LETTERS.size
        val totalBarHeightPx = (pad * 2f + letterStepH * EdgeIndexSlots.TOTAL_SLOTS).toInt()

        val topSafeMargin = (28f * density).toInt()
        val bottomSafeMargin = (16f * density).toInt()
        val availableSpan = (screenH - topSafeMargin - bottomSafeMargin - totalBarHeightPx).coerceAtLeast(0)
        val posRatio = (prefs.verticalPositionPercent / 100f).coerceIn(0f, 1f)
        val barTopY = topSafeMargin + (availableSpan * posRatio).toInt()

        val handle = EdgeTriggerView(this, prefs)

        val params = WindowManager.LayoutParams(
            widthPx,
            lettersHeightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.isRightSide) Gravity.END else Gravity.START) or Gravity.TOP
            x = (6f * density).toInt()
            y = barTopY
        }

        var downY = 0f
        var triggeredInThisTouch = false
        val verticalTriggerThreshold = 8f * density

        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    triggeredInThisTouch = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!triggeredInThisTouch) {
                        val dy = abs(event.rawY - downY)
                        // Attivazione tramite scorrimento verticale (su/giù) lungo il bordo
                        if (dy >= verticalTriggerThreshold) {
                            triggeredInThisTouch = true
                            handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            val startSlot = handle.getSlotAtY(event.y)
                            openDrawer(
                                fromContinuousSwipe = true,
                                initialSlot = startSlot,
                                initialRawY = event.rawY
                            )
                        }
                    } else {
                        drawerView?.handleExternalDrag(event.rawX, event.rawY, MotionEvent.ACTION_MOVE)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (triggeredInThisTouch) {
                        drawerView?.handleExternalDrag(event.rawX, event.rawY, event.action)
                    } else {
                        // Tocco rapido su una lettera del bordo -> apre su quella lettera
                        handle.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        val tappedSlot = handle.getSlotAtY(event.y)
                        openDrawer(
                            fromContinuousSwipe = false,
                            initialSlot = tappedSlot,
                            initialRawY = event.rawY
                        )
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

    private fun openDrawer(
        fromContinuousSwipe: Boolean,
        initialSlot: Int,
        initialRawY: Float
    ) {
        if (isDrawerOpen) return
        isDrawerOpen = true

        // Nasconde il trigger sottostante mentre il pannello è aperto per evitare due alfabeti sovrapposti
        triggerView?.visibility = View.INVISIBLE

        val overlay = EdgeDrawerView(
            context = this,
            repository = repository,
            prefs = prefs,
            onLaunchApp = { appEntry, windowMode ->
                closeDrawer()
                launchTargetApp(appEntry.packageName, windowMode)
            },
            onDismiss = {
                closeDrawer()
            }
        )

        val blurVal = prefs.blurRadius.coerceIn(0, 120)
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurVal > 0) {
            flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurVal > 0) {
            params.blurBehindRadius = blurVal
        }

        drawerView = overlay
        windowManager.addView(overlay, params)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurVal > 0) {
            windowManager.updateViewLayout(overlay, params)
        }

        overlay.post {
            overlay.prepareOpen(fromContinuousSwipe, initialSlot, initialRawY)
        }
    }

    private fun closeDrawer() {
        val current = drawerView
        drawerView = null
        isDrawerOpen = false
        triggerView?.visibility = View.VISIBLE
        if (current != null) {
            try {
                windowManager.removeView(current)
            } catch (_: Exception) {
            }
        }
    }

    private fun launchTargetApp(packageName: String, mode: LaunchWindowMode) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: return

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        when (mode) {
            LaunchWindowMode.NORMAL -> {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
                startActivity(launchIntent)
            }

            LaunchWindowMode.POPUP -> {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                )
                launchIntent.putExtra("miui.intent.extra.OPEN_IN_FREEFORM", true)

                val marginX = (screenW * 0.10f).toInt()
                val marginY = (screenH * 0.18f).toInt()
                val popupBounds = Rect(marginX, marginY, screenW - marginX, screenH - marginY)

                val options = createWindowModeOptions(windowingMode = 5, bounds = popupBounds)
                startActivity(launchIntent, options.toBundle())
            }

            LaunchWindowMode.SPLIT_TOP -> {
                val acc = EdgeAccessibilityService.instance
                if (acc != null) {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    )
                    startActivity(launchIntent)
                    mainHandler.postDelayed({
                        acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    }, 240L)
                } else {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    )
                    val topBounds = Rect(0, 0, screenW, screenH / 2)
                    val options = createWindowModeOptions(windowingMode = 3, bounds = topBounds)
                    startActivity(launchIntent, options.toBundle())
                }
            }

            LaunchWindowMode.SPLIT_BOTTOM -> {
                val acc = EdgeAccessibilityService.instance
                if (acc != null) {
                    acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    mainHandler.postDelayed({
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                        )
                        startActivity(launchIntent)
                    }, 220L)
                } else {
                    launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT
                    )
                    val bottomBounds = Rect(0, screenH / 2, screenW, screenH)
                    val options = createWindowModeOptions(windowingMode = 4, bounds = bottomBounds)
                    startActivity(launchIntent, options.toBundle())
                }
            }
        }
    }

    private fun createWindowModeOptions(windowingMode: Int, bounds: Rect): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(bounds)
        try {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            method.invoke(options, windowingMode)
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
                val serviceIntent = Intent(context, EdgeOverlayService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
