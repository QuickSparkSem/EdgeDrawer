package com.edgedrawer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import java.text.Normalizer
import java.util.concurrent.Executors

// Ordine pallini dal più alto al più vicino alla 'A':
// l'ultimo elemento (ALL - Bianco) si trova subito sopra la lettera 'A'
enum class AppColorFilter(val label: String, val swatchColor: Int) {
    MONO("Bianco/Nero", Color.parseColor("#9AA3B2")),
    PURPLE("Viola", Color.parseColor("#B36BFF")),
    BLUE("Blu", Color.parseColor("#3B9EFF")),
    GREEN("Verde", Color.parseColor("#2EE585")),
    YELLOW("Giallo", Color.parseColor("#FFD23F")),
    RED("Rosso", Color.parseColor("#FF4B4B")),
    ALL("Tutte le app", Color.WHITE)
}

enum class LaunchWindowMode(val title: String) {
    NORMAL("Normale"),
    SPLIT_TOP("Schermo diviso su"),
    POPUP("Pop-up"),
    SPLIT_BOTTOM("Schermo diviso giù"),
    APP_INFO("ⓘ Info App")
}

enum class OneUIAccent(val label: String, val colorInt: Int) {
    BLUE("Blu One UI", Color.parseColor("#3E91FF")),
    PURPLE("Viola", Color.parseColor("#9C6BFF")),
    MINT("Verde Menta", Color.parseColor("#1ED78D")),
    CORAL("Corallo", Color.parseColor("#FF5C5C")),
    AMBER("Ambra", Color.parseColor("#FFB82E")),
    MONO("Neutro", Color.parseColor("#8E97A8"))
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
    enum class PulseType(val baseMs: Long, val baseAmp: Int) {
        TICK(10L, 145),
        APP_HOVER(14L, 175),
        LONG_HOLD(28L, 245),
        CONFIRM(20L, 210)
    }

    fun trigger(context: Context, prefs: PrefsManager, type: PulseType) {
        val strength = prefs.hapticStrengthPercent.coerceIn(0, 100)
        if (strength <= 0) return

        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) return

        val ratio = strength / 100f
        val amp = (type.baseAmp * ratio).toInt().coerceIn(1, 255)
        val dur = (type.baseMs * (0.55f + 0.45f * ratio)).toLong().coerceAtLeast(4L)

        try {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(VibrationEffect.createOneShot(dur, amp))
            } else {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        (type.baseMs * ratio * 1.4f).toLong().coerceIn(3L, 45L),
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            }
        } catch (_: Exception) {
        }
    }
}

object IconColorAnalyzer {
    fun normalizeFirstLetter(label: String): Char {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return '#'
        val ch = Normalizer.normalize(trimmed.substring(0, 1), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase()
            .firstOrNull() ?: '#'
        return if (ch in 'A'..'Z') ch else '#'
    }

    fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun detectDominantColor(bitmap: Bitmap): AppColorFilter {
        val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val counts = IntArray(AppColorFilter.entries.size)
        val hsv = FloatArray(3)

        for (x in 4 until 28) {
            for (y in 4 until 28) {
                val pixel = scaled.getPixel(x, y)
                if (Color.alpha(pixel) < 140) continue
                Color.colorToHSV(pixel, hsv)
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
        }
        if (scaled != bitmap) scaled.recycle()

        var bestIdx = AppColorFilter.MONO.ordinal
        var bestVal = 0
        for (i in counts.indices) {
            if (AppColorFilter.entries[i] == AppColorFilter.ALL) continue
            if (counts[i] > bestVal) {
                bestVal = counts[i]
                bestIdx = i
            }
        }
        return AppColorFilter.entries[bestIdx]
    }
}

class AppRepository(private val context: Context, private val prefs: PrefsManager) {
    companion object {
        // Valore ufficiale di PackageManager.MATCH_ARCHIVED_PACKAGES in Android 15+ (compatibile anche con compileSdk 34)
        private const val FLAG_MATCH_ARCHIVED = 1L shl 32
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var allInstalledApps: List<AppEntry> = emptyList()

    @Volatile
    private var visibleApps: List<AppEntry> = emptyList()

    @Volatile
    private var letterCache: Map<Pair<Char, AppColorFilter>, List<AppEntry>> = emptyMap()

    @Volatile
    private var recentCache: Map<AppColorFilter, List<AppEntry>> = emptyMap()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) = reloadAppsAsync()
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(context, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        reloadAppsAsync()
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
    }

    private fun isApplicationArchived(appInfo: ApplicationInfo?): Boolean {
        if (appInfo == null) return false
        return try {
            ApplicationInfo::class.java.getField("isArchived").getBoolean(appInfo)
        } catch (_: Exception) {
            try {
                (ApplicationInfo::class.java.getMethod("isArchived").invoke(appInfo) as? Boolean) == true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun reloadAppsAsync(onComplete: (() -> Unit)? = null) {
        executor.execute {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }

            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val archivedBit = if (Build.VERSION.SDK_INT >= 35) FLAG_MATCH_ARCHIVED else 0L
                val flags = PackageManager.MATCH_ALL.toLong() or archivedBit
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(flags))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            }

            val iconSizePx = (64 * context.resources.displayMetrics.density).toInt().coerceAtLeast(80)
            val myPkg = context.packageName
            val loadedMap = LinkedHashMap<String, AppEntry>()

            for (info in resolved) {
                try {
                    val pkg = info.activityInfo?.packageName ?: continue
                    if (pkg == myPkg || loadedMap.containsKey(pkg)) continue

                    val label = info.loadLabel(pm).toString()
                    val isArchived = isApplicationArchived(info.activityInfo?.applicationInfo)
                    val bitmap = IconColorAnalyzer.drawableToBitmap(info.loadIcon(pm), iconSizePx)
                    val installTime = try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L)).firstInstallTime
                        } else {
                            @Suppress("DEPRECATION")
                            pm.getPackageInfo(pkg, 0).firstInstallTime
                        }
                    } catch (_: Exception) {
                        0L
                    }

                    loadedMap[pkg] = AppEntry(
                        label = label,
                        shortLabel = if (label.length > 11) label.take(10) + "…" else label,
                        packageName = pkg,
                        iconBitmap = bitmap,
                        firstLetter = IconColorAnalyzer.normalizeFirstLetter(label),
                        installTime = installTime,
                        colorCategory = IconColorAnalyzer.detectDominantColor(bitmap),
                        isArchived = isArchived
                    )
                } catch (_: Exception) {
                }
            }

            // Integrazione extra per pacchetti archiviati dal sistema su Android 15/16+
            if (Build.VERSION.SDK_INT >= 35) {
                try {
                    val allPkgs = pm.getInstalledPackages(
                        PackageManager.PackageInfoFlags.of(FLAG_MATCH_ARCHIVED)
                    )
                    for (pkgInfo in allPkgs) {
                        val pkg = pkgInfo.packageName ?: continue
                        if (pkg == myPkg || loadedMap.containsKey(pkg)) continue
                        val appInfo = pkgInfo.applicationInfo ?: continue
                        if (isApplicationArchived(appInfo)) {
                            val label = pm.getApplicationLabel(appInfo).toString()
                            val bitmap = IconColorAnalyzer.drawableToBitmap(pm.getApplicationIcon(appInfo), iconSizePx)
                            loadedMap[pkg] = AppEntry(
                                label = label,
                                shortLabel = if (label.length > 11) label.take(10) + "…" else label,
                                packageName = pkg,
                                iconBitmap = bitmap,
                                firstLetter = IconColorAnalyzer.normalizeFirstLetter(label),
                                installTime = pkgInfo.firstInstallTime,
                                colorCategory = IconColorAnalyzer.detectDominantColor(bitmap),
                                isArchived = true
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }

            allInstalledApps = loadedMap.values.sortedBy { it.label.lowercase() }
            rebuildFilteredCaches()
            onComplete?.invoke()
        }
    }

    fun rebuildFilteredCaches() {
        val hiddenSet = prefs.hiddenPackages
        val visible = allInstalledApps.filter { it.packageName !in hiddenSet }

        val newLetterCache = HashMap<Pair<Char, AppColorFilter>, List<AppEntry>>()
        val newRecentCache = HashMap<AppColorFilter, List<AppEntry>>()
        val sortedByRecent = visible.sortedByDescending { it.installTime }

        for (color in AppColorFilter.entries) {
            val filtered = if (color == AppColorFilter.ALL) visible else visible.filter { it.colorCategory == color }
            newRecentCache[color] = if (color == AppColorFilter.ALL) sortedByRecent else sortedByRecent.filter { it.colorCategory == color }
            val grouped = filtered.groupBy { it.firstLetter }
            for (ch in 'A'..'Z') {
                newLetterCache[ch to color] = grouped[ch] ?: emptyList()
            }
        }

        visibleApps = visible
        letterCache = newLetterCache
        recentCache = newRecentCache
    }

    fun getAllInstalledApps(): List<AppEntry> = allInstalledApps

    fun getAppsForLetter(letter: Char, colorFilter: AppColorFilter): List<AppEntry> =
        letterCache[letter to colorFilter] ?: emptyList()

    fun getRecentApps(limit: Int, colorFilter: AppColorFilter): List<AppEntry> =
        (recentCache[colorFilter] ?: emptyList()).take(limit)

    fun getAllAppsByColor(colorFilter: AppColorFilter): List<AppEntry> =
        if (colorFilter == AppColorFilter.ALL) visibleApps else visibleApps.filter { it.colorCategory == colorFilter }

    fun hasAppsForLetter(letter: Char, colorFilter: AppColorFilter): Boolean =
        !(letterCache[letter to colorFilter].isNullOrEmpty())
}

class PrefsManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("edge_drawer_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var isRightSide: Boolean
        get() = prefs.getBoolean("is_right_side", true)
        set(value) = prefs.edit().putBoolean("is_right_side", value).apply()

    var themeMode: Int
        get() = prefs.getInt("theme_mode", 0)
        set(value) = prefs.edit().putInt("theme_mode", value).apply()

    var accentIndex: Int
        get() = prefs.getInt("accent_index", 0)
        set(value) = prefs.edit().putInt("accent_index", value).apply()

    fun getAccentColor(): Int =
        OneUIAccent.entries[accentIndex.coerceIn(0, OneUIAccent.entries.lastIndex)].colorInt

    fun isNightThemeActive(): Boolean = when (themeMode) {
        1 -> false
        2 -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // Protezioni contestuali
    var disableInLandscape: Boolean
        get() = prefs.getBoolean("disable_in_landscape", true)
        set(value) = prefs.edit().putBoolean("disable_in_landscape", value).apply()

    var disableOnKeyboard: Boolean
        get() = prefs.getBoolean("disable_on_keyboard", true)
        set(value) = prefs.edit().putBoolean("disable_on_keyboard", value).apply()

    // App in cui disabilitare il trigger (Lista nera)
    var blacklistPackages: Set<String>
        get() = prefs.getStringSet("blacklist_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("blacklist_packages", value).apply()

    // App nascoste dall'elenco del drawer
    var hiddenPackages: Set<String>
        get() = prefs.getStringSet("hidden_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("hidden_packages", value).apply()

    // Trigger: colore a spettro e profondità ombra
    var triggerCustomColor: Int
        get() = prefs.getInt("trigger_custom_color", Color.WHITE)
        set(value) = prefs.edit().putInt("trigger_custom_color", value).apply()

    var triggerShadowDepthPercent: Int
        get() = prefs.getInt("trigger_shadow_depth_percent", 75).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("trigger_shadow_depth_percent", value.coerceIn(0, 100)).apply()

    // Distanza indice trigger dal lato del display (0 dp = sotto il bordo fisico)
    var triggerEdgeOffsetDp: Int
        get() = prefs.getInt("trigger_edge_offset_dp", 0).coerceIn(0, 20)
        set(value) = prefs.edit().putInt("trigger_edge_offset_dp", value.coerceIn(0, 20)).apply()

    var isTriggerHidden: Boolean
        get() = prefs.getBoolean("trigger_hidden", false)
        set(value) = prefs.edit().putBoolean("trigger_hidden", value).apply()

    var barHeightPercent: Int
        get() = prefs.getInt("bar_height_percent", 78)
        set(value) = prefs.edit().putInt("bar_height_percent", value).apply()

    var verticalPositionPercent: Int
        get() = prefs.getInt("vertical_position_percent", 55)
        set(value) = prefs.edit().putInt("vertical_position_percent", value).apply()

    var triggerWidthDp: Int
        get() = prefs.getInt("trigger_width_dp", 24)
        set(value) = prefs.edit().putInt("trigger_width_dp", value).apply()

    var triggerAlphaPercent: Int
        get() = prefs.getInt("trigger_alpha_percent", 65)
        set(value) = prefs.edit().putInt("trigger_alpha_percent", value).apply()

    var letterSpacingExtraDp: Int
        get() = prefs.getInt("letter_spacing_extra_dp", 2).coerceIn(0, 14)
        set(value) = prefs.edit().putInt("letter_spacing_extra_dp", value.coerceIn(0, 14)).apply()

    var colorDotSpacingExtraDp: Int
        get() = prefs.getInt("color_dot_spacing_extra_dp", 4).coerceIn(0, 18)
        set(value) = prefs.edit().putInt("color_dot_spacing_extra_dp", value.coerceIn(0, 18)).apply()

    var isAppsBottomToTop: Boolean
        get() = prefs.getBoolean("apps_bottom_to_top", true)
        set(value) = prefs.edit().putBoolean("apps_bottom_to_top", value).apply()

    // TRE SCALE SEPARATE:
    // 1. Scala colonna app e icone
    var panelScalePercent: Int
        get() = prefs.getInt("panel_scale_percent", 100).coerceIn(65, 140)
        set(value) = prefs.edit().putInt("panel_scale_percent", value.coerceIn(65, 140)).apply()

    // 2. Dimensione etichette nomi app (sp)
    var appLabelSizeSp: Int
        get() = prefs.getInt("app_label_size_sp", 11).coerceIn(8, 18)
        set(value) = prefs.edit().putInt("app_label_size_sp", value.coerceIn(8, 18)).apply()

    // 3. Dimensione indice alfabetico (coerente tra trigger e pannello aperto)
    var indexScalePercent: Int
        get() = prefs.getInt("index_scale_percent", 100).coerceIn(65, 140)
        set(value) = prefs.edit().putInt("index_scale_percent", value.coerceIn(65, 140)).apply()

    var panelBgAlphaPercent: Int
        get() = prefs.getInt("panel_bg_alpha_percent", 88).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("panel_bg_alpha_percent", value.coerceIn(0, 100)).apply()

    var panelBgOppositeAlphaPercent: Int
        get() = prefs.getInt("panel_bg_opposite_alpha_percent", 10).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("panel_bg_opposite_alpha_percent", value.coerceIn(0, 100)).apply()

    var indexBarAlphaPercent: Int
        get() = prefs.getInt("index_bar_alpha_percent", 82)
        set(value) = prefs.edit().putInt("index_bar_alpha_percent", value).apply()

    var colorWaveStrengthPercent: Int
        get() = prefs.getInt("color_wave_strength_percent", 65).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("color_wave_strength_percent", value.coerceIn(0, 100)).apply()

    var autoScrollEdgeZoneDp: Int
        get() = prefs.getInt("auto_scroll_edge_zone_dp", 64).coerceIn(20, 150)
        set(value) = prefs.edit().putInt("auto_scroll_edge_zone_dp", value.coerceIn(20, 150)).apply()

    var autoScrollAppsPerSec: Int
        get() = prefs.getInt("auto_scroll_apps_per_sec", 6).coerceIn(1, 18)
        set(value) = prefs.edit().putInt("auto_scroll_apps_per_sec", value.coerceIn(1, 18)).apply()

    var appSpacingDp: Int
        get() = prefs.getInt("app_spacing_dp", 12)
        set(value) = prefs.edit().putInt("app_spacing_dp", value).apply()

    var labelDistanceDp: Int
        get() = prefs.getInt("label_distance_dp", 4)
        set(value) = prefs.edit().putInt("label_distance_dp", value).apply()

    var appsFromIndexDp: Int
        get() = prefs.getInt("apps_from_index_dp", 14)
        set(value) = prefs.edit().putInt("apps_from_index_dp", value).apply()

    var waveStrengthPercent: Int
        get() = prefs.getInt("wave_strength_percent", 75)
        set(value) = prefs.edit().putInt("wave_strength_percent", value).apply()

    var hapticStrengthPercent: Int
        get() = prefs.getInt("haptic_strength_percent", 65).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("haptic_strength_percent", value.coerceIn(0, 100)).apply()

    var showAppLabels: Boolean
        get() = prefs.getBoolean("show_app_labels", true)
        set(value) = prefs.edit().putBoolean("show_app_labels", value).apply()

    var recentCount: Int
        get() = prefs.getInt("recent_count", 20).coerceIn(10, 100)
        set(value) = prefs.edit().putInt("recent_count", value.coerceIn(10, 100)).apply()
}
