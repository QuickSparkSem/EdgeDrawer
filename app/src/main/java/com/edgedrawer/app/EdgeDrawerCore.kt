package com.edgedrawer.app

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.*
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// L'ultimo elemento (ALL - Bianco) si posiziona subito sopra la lettera 'A'
enum class AppColorFilter(val swatchColor: Int) {
    MONO(Color.parseColor("#9AA3B2")),
    PURPLE(Color.parseColor("#B36BFF")),
    BLUE(Color.parseColor("#3B9EFF")),
    GREEN(Color.parseColor("#2EE585")),
    YELLOW(Color.parseColor("#FFD23F")),
    RED(Color.parseColor("#FF4B4B")),
    ALL(Color.WHITE)
}

enum class LaunchWindowMode(val title: String) {
    NORMAL("Normale"),
    SPLIT_TOP("Schermo diviso su"),
    POPUP("Pop-up"),
    SPLIT_BOTTOM("Schermo diviso giù"),
    APP_INFO("ⓘ Info App")
}

enum class OneUIAccent(val colorInt: Int) {
    BLUE(Color.parseColor("#3E91FF")),
    PURPLE(Color.parseColor("#9C6BFF")),
    MINT(Color.parseColor("#1ED78D")),
    CORAL(Color.parseColor("#FF5C5C")),
    AMBER(Color.parseColor("#FFB82E")),
    MONO(Color.parseColor("#8E97A8"))
}

data class AppEntry(
    val label: String,
    val shortLabel: String,
    val packageName: String,
    val iconBitmap: Bitmap,
    val firstLetter: Char,
    val installTime: Long,
    val colorCategory: AppColorFilter,
    val isArchived: Boolean = false
)

object HapticHelper {
    enum class Pulse(val ms: Long, val amp: Int) {
        TICK(10L, 145), HOVER(14L, 175), HOLD(26L, 240), CONFIRM(20L, 210)
    }

    fun trigger(context: Context, prefs: PrefsManager, pulse: Pulse) {
        val strength = prefs.hapticStrengthPercent
        if (strength <= 0) return
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        if (!vibrator.hasVibrator()) return

        val ratio = strength / 100f
        val amp = if (vibrator.hasAmplitudeControl()) (pulse.amp * ratio).toInt().coerceIn(1, 255) else VibrationEffect.DEFAULT_AMPLITUDE
        val dur = (pulse.ms * (0.55f + 0.45f * ratio)).toLong().coerceAtLeast(4L)
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(dur, amp)) }
    }
}

class AppRepository(private val context: Context, private val prefs: PrefsManager) {
    companion object {
        private const val FLAG_MATCH_ARCHIVED = 1L shl 32
    }

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var allApps: List<AppEntry> = emptyList()
    @Volatile private var letterCache: Map<Pair<Char, AppColorFilter>, List<AppEntry>> = emptyMap()
    @Volatile private var recentCache: Map<AppColorFilter, List<AppEntry>> = emptyMap()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) = reloadAppsAsync()
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        reloadAppsAsync()
    }

    fun stopListening() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    fun reloadAppsAsync() {
        executor.execute {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val flags = PackageManager.MATCH_ALL.toLong() or (if (Build.VERSION.SDK_INT >= 35) FLAG_MATCH_ARCHIVED else 0L)
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            }

            val iconPx = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(80)
            val map = LinkedHashMap<String, AppEntry>()

            for (info in resolved) {
                val pkg = info.activityInfo?.packageName ?: continue
                if (pkg == context.packageName || map.containsKey(pkg)) continue
                runCatching {
                    val label = info.loadLabel(pm).toString()
                    val bmp = toBitmap(info.loadIcon(pm), iconPx)
                    val time = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L)).firstInstallTime
                    } else {
                        @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0).firstInstallTime
                    }
                    map[pkg] = createEntry(label, pkg, bmp, time, isArchived(info.activityInfo?.applicationInfo))
                }
            }

            if (Build.VERSION.SDK_INT >= 35) {
                runCatching {
                    pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(FLAG_MATCH_ARCHIVED)).forEach { pkgInfo ->
                        val pkg = pkgInfo.packageName ?: return@forEach
                        val appInfo = pkgInfo.applicationInfo ?: return@forEach
                        if (pkg != context.packageName && !map.containsKey(pkg) && isArchived(appInfo)) {
                            val label = pm.getApplicationLabel(appInfo).toString()
                            val bmp = toBitmap(pm.getApplicationIcon(appInfo), iconPx)
                            map[pkg] = createEntry(label, pkg, bmp, pkgInfo.firstInstallTime, true)
                        }
                    }
                }
            }

            allApps = map.values.sortedBy { it.label.lowercase() }
            rebuildFilteredCaches()
        }
    }

    fun rebuildFilteredCaches() {
        val hidden = prefs.hiddenPackages
        val visible = allApps.filter { it.packageName !in hidden }
        val byRecent = visible.sortedByDescending { it.installTime }
        val newLetters = HashMap<Pair<Char, AppColorFilter>, List<AppEntry>>()
        val newRecents = HashMap<AppColorFilter, List<AppEntry>>()

        for (color in AppColorFilter.entries) {
            val filtered = if (color == AppColorFilter.ALL) visible else visible.filter { it.colorCategory == color }
            newRecents[color] = if (color == AppColorFilter.ALL) byRecent else byRecent.filter { it.colorCategory == color }
            val grouped = filtered.groupBy { it.firstLetter }
            for (ch in 'A'..'Z') newLetters[ch to color] = grouped[ch].orEmpty()
        }
        letterCache = newLetters
        recentCache = newRecents
    }

    fun getAllInstalledApps(): List<AppEntry> = allApps
    fun getAppsForLetter(ch: Char, color: AppColorFilter): List<AppEntry> = letterCache[ch to color].orEmpty()
    fun getRecentApps(limit: Int, color: AppColorFilter): List<AppEntry> = recentCache[color].orEmpty().take(limit)
    fun hasAppsForLetter(ch: Char, color: AppColorFilter): Boolean = !letterCache[ch to color].isNullOrEmpty()

    private fun createEntry(label: String, pkg: String, bmp: Bitmap, time: Long, archived: Boolean) = AppEntry(
        label = label,
        shortLabel = if (label.length > 11) label.take(10) + "…" else label,
        packageName = pkg,
        iconBitmap = bmp,
        firstLetter = normalizeLetter(label),
        installTime = time,
        colorCategory = detectColor(bmp),
        isArchived = archived
    )

    private fun isArchived(info: ApplicationInfo?): Boolean = info != null && (
        runCatching { ApplicationInfo::class.java.getField("isArchived").getBoolean(info) }.getOrNull()
            ?: runCatching { ApplicationInfo::class.java.getMethod("isArchived").invoke(info) as? Boolean }.getOrNull()
            ?: false
    )

    private fun normalizeLetter(label: String): Char {
        val c = Normalizer.normalize(label.trim().take(1), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase().firstOrNull() ?: '#'
        return if (c in 'A'..'Z') c else '#'
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return Bitmap.createScaledBitmap(d.bitmap, size, size, true)
        return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
        }
    }

    private fun detectColor(bmp: Bitmap): AppColorFilter {
        val scaled = Bitmap.createScaledBitmap(bmp, 32, 32, true)
        val counts = IntArray(AppColorFilter.entries.size)
        val hsv = FloatArray(3)
        for (x in 4 until 28) for (y in 4 until 28) {
            val p = scaled.getPixel(x, y)
            if (Color.alpha(p) < 140) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.22f || hsv[2] < 0.18f) {
                counts[AppColorFilter.MONO.ordinal] += 1
            } else {
                val idx = when {
                    hsv[0] < 24f || hsv[0] >= 338f -> AppColorFilter.RED.ordinal
                    hsv[0] < 68f -> AppColorFilter.YELLOW.ordinal
                    hsv[0] < 168f -> AppColorFilter.GREEN.ordinal
                    hsv[0] < 258f -> AppColorFilter.BLUE.ordinal
                    else -> AppColorFilter.PURPLE.ordinal
                }
                counts[idx] += 3
            }
        }
        if (scaled != bmp) scaled.recycle()
        return AppColorFilter.entries
            .filter { it != AppColorFilter.ALL }
            .maxByOrNull { counts[it.ordinal] } ?: AppColorFilter.MONO
    }
}

class PrefsManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("edge_drawer_prefs", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    }

    private fun int(key: String, def: Int, min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getInt(key, def).coerceIn(min, max)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = prefs.edit().putInt(key, value.coerceIn(min, max)).apply()
    }

    private fun strSet(key: String) = object : ReadWriteProperty<Any?, Set<String>> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = prefs.getStringSet(key, emptySet()).orEmpty()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Set<String>) = prefs.edit().putStringSet(key, value).apply()
    }

    var isServiceEnabled by bool("service_enabled", false)
    var isRightSide by bool("is_right_side", true)
    var themeMode by int("theme_mode", 0, 0, 2)
    var accentIndex by int("accent_index", 0, 0, OneUIAccent.entries.lastIndex)

    var disableInLandscape by bool("disable_in_landscape", true)
    var disableOnKeyboard by bool("disable_on_keyboard", true)
    var blacklistPackages by strSet("blacklist_packages")
    var hiddenPackages by strSet("hidden_packages")

    var triggerCustomColor by int("trigger_custom_color", Color.WHITE)
    var triggerShadowDepthPercent by int("trigger_shadow_depth_percent", 75, 0, 100)
    var triggerEdgeOffsetDp by int("trigger_edge_offset_dp", 0, 0, 20)
    var isTriggerHidden by bool("trigger_hidden", false)
    var barHeightPercent by int("bar_height_percent", 78, 30, 90)
    var verticalPositionPercent by int("vertical_position_percent", 55, 0, 100)
    var triggerWidthDp by int("trigger_width_dp", 24, 18, 46)
    var triggerAlphaPercent by int("trigger_alpha_percent", 65, 0, 100)
    var letterSpacingExtraDp by int("letter_spacing_extra_dp", 2, 0, 14)
    var colorDotSpacingExtraDp by int("color_dot_spacing_extra_dp", 4, 0, 18)

    var isAppsBottomToTop by bool("apps_bottom_to_top", true)
    var showAppLabels by bool("show_app_labels", true)
    var panelScalePercent by int("panel_scale_percent", 100, 65, 140)
    var appLabelSizeSp by int("app_label_size_sp", 11, 8, 18)
    var indexScalePercent by int("index_scale_percent", 100, 65, 140)

    var panelBgAlphaPercent by int("panel_bg_alpha_percent", 88, 0, 100)
    var panelBgOppositeAlphaPercent by int("panel_bg_opposite_alpha_percent", 10, 0, 100)
    var indexBarAlphaPercent by int("index_bar_alpha_percent", 82, 0, 100)
    var autoScrollEdgeZoneDp by int("auto_scroll_edge_zone_dp", 64, 20, 150)
    var autoScrollAppsPerSec by int("auto_scroll_apps_per_sec", 6, 1, 18)
    var appSpacingDp by int("app_spacing_dp", 12, 2, 32)
    var labelDistanceDp by int("label_distance_dp", 4, 0, 18)
    var appsFromIndexDp by int("apps_from_index_dp", 14, 4, 44)
    var waveStrengthPercent by int("wave_strength_percent", 75, 0, 140)
    var hapticStrengthPercent by int("haptic_strength_percent", 65, 0, 100)
    var recentCount by int("recent_count", 20, 10, 100)

    fun getAccentColor(): Int = OneUIAccent.entries[accentIndex].colorInt
    fun isNightThemeActive(): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}
