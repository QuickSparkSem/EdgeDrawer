package com.edgedrawer.app

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class WindowLaunchProxyActivity : Activity() {
    companion object {
        const val EXTRA_PKG = "extra_pkg"
        const val EXTRA_MODE = "extra_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        val mode = LaunchWindowMode.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_MODE) } ?: LaunchWindowMode.NORMAL

        if (mode == LaunchWindowMode.APP_INFO) {
            runCatching { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return finish()
        }

        val resolved = packageManager.getLaunchIntentForPackage(pkg) ?: return finish()
        val baseIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg).apply {
            resolved.component?.let { component = it }
        }

        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels

        when (mode) {
            LaunchWindowMode.NORMAL -> {
                runCatching { startActivity(baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }
                finish()
            }
            LaunchWindowMode.PIN_SCREEN -> {
                // Avvia l'app nello stesso Task e blocca lo schermo fino a sblocco con password/impronta
                val pinned = runCatching {
                    startLockTask()
                    startActivity(baseIntent)
                    true
                }.getOrElse {
                    Toast.makeText(this, "Attiva 'Aggiungi alla schermata' in Impostazioni > Sicurezza", Toast.LENGTH_LONG).show()
                    runCatching { startActivity(baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    false
                }
                Handler(Looper.getMainLooper()).postDelayed({ finish() }, if (pinned) 140L else 50L)
            }
            LaunchWindowMode.POPUP -> {
                val bounds = Rect((w * 0.08f).toInt(), (h * 0.16f).toInt(), (w * 0.92f).toInt(), (h * 0.84f).toInt())
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                baseIntent.putExtra("miui.intent.extra.OPEN_IN_FREEFORM", true)
                baseIntent.putExtra("android.activity.launchBounds", bounds)
                val opts = ActivityOptions.makeBasic().apply {
                    setLaunchBounds(bounds)
                    runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(this, 5) }
                }
                runCatching { startActivity(baseIntent, opts.toBundle()) }.onFailure { runCatching { startActivity(baseIntent) } }
                finish()
            }
            LaunchWindowMode.SPLIT_TOP, LaunchWindowMode.SPLIT_BOTTOM -> {
                val isTop = mode == LaunchWindowMode.SPLIT_TOP
                baseIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                val acc = EdgeAccessibilityService.instance
                if (acc != null) {
                    acc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                    Handler(Looper.getMainLooper()).postDelayed({ runCatching { startActivity(baseIntent) }; finish() }, 280L)
                } else {
                    val bounds = if (isTop) Rect(0, 0, w, h / 2) else Rect(0, h / 2, w, h)
                    val opts = ActivityOptions.makeBasic().apply {
                        setLaunchBounds(bounds)
                        runCatching { ActivityOptions::class.java.getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType).invoke(this, if (isTop) 3 else 4) }
                    }
                    runCatching { startActivity(baseIntent, opts.toBundle()) }.onFailure { runCatching { startActivity(baseIntent) } }
                    finish()
                }
            }
            else -> finish()
        }
    }
}

class EdgeOverlayService : Service() {
    companion object {
        const val ACTION_OPEN_DRAWER = "com.edgedrawer.app.ACTION_OPEN_DRAWER"
        const val ACTION_RELOAD_CONFIG = "com.edgedrawer.app.ACTION_RELOAD_CONFIG"
        const val ACTION_CONTEXT_CHANGED = "com.edgedrawer.app.ACTION_CONTEXT_CHANGED"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: PrefsManager
    private lateinit var repository: AppRepository
    private val handler = Handler(Looper.getMainLooper())

    private var triggerView: EdgeTriggerView? = null
    private var drawerView: EdgeDrawerView? = null
    private var isDrawerOpen = false
    private var isImeVisible = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        prefs = PrefsManager(this)
        repository = AppRepository(this, prefs).also { it.startListening() }

        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("edge_drawer_ch", "EdgeDrawer", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = NotificationCompat.Builder(this, "edge_drawer_ch")
            .setContentTitle("EdgeDrawer attivo")
            .setSmallIcon(android.R.drawable.ic_menu_sort_alphabetically)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true).build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(101, notif)
        }
        if (Settings.canDrawOverlays(this)) updateTriggerState()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateTriggerState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        when (intent?.action) {
            ACTION_OPEN_DRAWER -> openDrawer(false, EdgeIndexSlots.RECENT_SLOT_INDEX)
            ACTION_RELOAD_CONFIG -> { repository.rebuildFilteredCaches(); removeTrigger(); updateTriggerState() }
            else -> updateTriggerState()
        }
        return START_STICKY
    }

    private fun updateTriggerState() {
        val suppress = (prefs.disableInLandscape && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
            (prefs.disableOnKeyboard && (isImeVisible || EdgeAccessibilityService.isImePresent)) ||
            (EdgeAccessibilityService.foregroundPkg in prefs.blacklistPackages)

        if (suppress) {
            triggerView?.visibility = View.GONE
            return
        }
        if (triggerView == null) setupTrigger() else {
            triggerView?.visibility = View.VISIBLE
            triggerView?.alpha = if (isDrawerOpen) 0f else 1f
            triggerView?.invalidate()
        }
    }

    private fun setupTrigger() {
        val density = resources.displayMetrics.density
        val m = EdgeBarMetrics.compute(resources.displayMetrics.widthPixels.toFloat(), resources.displayMetrics.heightPixels.toFloat(), density, prefs)
        val handle = EdgeTriggerView(this, prefs)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            handle.setOnApplyWindowInsetsListener { _, insets ->
                isImeVisible = insets.isVisible(WindowInsets.Type.ime())
                updateTriggerState()
                insets
            }
        }

        val params = WindowManager.LayoutParams(
            m.indexWidthPx.toInt(), m.lettersHeightPx.toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = (if (prefs.isRightSide) Gravity.END else Gravity.START) or Gravity.TOP
            x = m.edgeMarginPx.toInt()
            y = m.lettersTopY.toInt()
        }

        var downX = 0f; var downY = 0f; var triggered = false; var ignored = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; triggered = false; ignored = false; true }
                MotionEvent.ACTION_MOVE -> {
                    if (ignored) return@setOnTouchListener false
                    if (!triggered) {
                        val dx = abs(e.rawX - downX); val dy = abs(e.rawY - downY)
                        if (dx >= 10f * density && dx > dy * 0.85f) { ignored = true; return@setOnTouchListener false }
                        if (dy >= 8f * density && dy > dx * 1.45f) {
                            triggered = true
                            HapticHelper.trigger(this, prefs, HapticHelper.Pulse.TICK)
                            openDrawer(true, handle.getSlotAtY(e.y))
                            drawerView?.handleExternalDrag(e.rawX, e.rawY, MotionEvent.ACTION_MOVE)
                        }
                    } else drawerView?.handleExternalDrag(e.rawX, e.rawY, MotionEvent.ACTION_MOVE)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (triggered) drawerView?.handleExternalDrag(e.rawX, e.rawY, e.actionMasked)
                    triggered = false; ignored = false; true
                }
                else -> false
            }
        }
        triggerView = handle
        windowManager.addView(handle, params)
    }

    private fun removeTrigger() {
        triggerView?.let { runCatching { windowManager.removeView(it) } }
        triggerView = null
    }

    private fun openDrawer(fromSwipe: Boolean, initialSlot: Int) {
        if (isDrawerOpen) return
        isDrawerOpen = true
        triggerView?.alpha = 0f

        val overlay = EdgeDrawerView(
            context = this,
            repository = repository,
            prefs = prefs,
            onLaunchApp = { app, mode ->
                runCatching {
                    startActivity(Intent(this, WindowLaunchProxyActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        putExtra(WindowLaunchProxyActivity.EXTRA_PKG, app.packageName)
                        putExtra(WindowLaunchProxyActivity.EXTRA_MODE, mode.name)
                    })
                }
                handler.postDelayed({ closeDrawer() }, 70L)
            },
            onToggleSide = {
                prefs.isRightSide = !prefs.isRightSide
                closeDrawer(); removeTrigger(); updateTriggerState()
                openDrawer(false, EdgeIndexSlots.RECENT_SLOT_INDEX)
            },
            onDismiss = { closeDrawer() }
        )

        drawerView = overlay
        windowManager.addView(overlay, WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ))
        overlay.prepareOpen(fromSwipe, initialSlot)
    }

    private fun closeDrawer() {
        val current = drawerView ?: return
        drawerView = null
        isDrawerOpen = false
        updateTriggerState()
        runCatching { windowManager.removeView(current) }
    }

    override fun onDestroy() {
        closeDrawer(); removeTrigger(); repository.stopListening(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class EdgeAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: EdgeAccessibilityService? = null
        @Volatile var foregroundPkg: String? = null
        @Volatile var isImePresent: Boolean = false
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.takeIf { it != packageName }?.let { foregroundPkg = it }
        val ime = windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } == true
        if (ime != isImePresent || event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            isImePresent = ime
            runCatching { startService(Intent(this, EdgeOverlayService::class.java).setAction(EdgeOverlayService.ACTION_CONTEXT_CHANGED)) }
        }
    }
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && PrefsManager(context).isServiceEnabled && Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, EdgeOverlayService::class.java))
        }
    }
}
