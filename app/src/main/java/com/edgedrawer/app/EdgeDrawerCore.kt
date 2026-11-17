package com.edgedrawer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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

enum class AppColorFilter(val label: String, val swatchColor: Int) {
    ALL("Tutti", Color.WHITE),
    RED("Rosso", Color.parseColor("#FF4B4B")),
    YELLOW("Giallo", Color.parseColor("#FFD23F")),
    GREEN("Verde", Color.parseColor("#2EE585")),
    BLUE("Blu", Color.parseColor("#3B9EFF")),
    PURPLE("Viola", Color.parseColor("#B36BFF")),
    MONO("Bianco/Nero", Color.parseColor("#9AA3B2"))
}

enum class LaunchWindowMode(val title: String) {
    NORMAL("Normale"),
    POPUP("Pop-up"),
    SPLIT_TOP("Metà sopra"),
    SPLIT_BOTTOM("Metà sotto")
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
    val colorCategory: AppColorFilter
)

object HapticHelper {
    enum class PulseType {
        TICK,
        APP_HOVER,
        LONG_HOLD,
        CONFIRM
    }

    fun trigger(context: Context, prefs: PrefsManager, type: PulseType) {
        val strength = prefs.hapticStrengthPercent.coerceIn(0, 100)
        if (strength <= 0) return

        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

        if (vibrator == null || !vibrator.hasVibrator()) return

        val baseDurationMs = when (type) {
            PulseType.TICK -> 10L
            PulseType.APP_HOVER -> 14L
            PulseType.LONG_HOLD -> 28L
            PulseType.CONFIRM -> 20L
        }

        val baseAmplitude = when (type) {
            PulseType.TICK -> 145
            PulseType.APP_HOVER -> 175
            PulseType.LONG_HOLD -> 240
            PulseType.CONFIRM -> 210
        }

        val ratio = strength / 100f
        val scaledAmplitude = (baseAmplitude * ratio).toInt().coerceIn(1, 255)
        val scaledDuration = (baseDurationMs * (0.55f + 0.45f * ratio)).toLong().coerceAtLeast(4L)

        try {
            if (vibrator.hasAmplitudeControl()) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(scaledDuration, scaledAmplitude)
                )
            } else {
                val fallbackDuration = (baseDurationMs * ratio * 1.4f).toLong().coerceIn(3L, 45L)
                vibrator.vibrate(
                    VibrationEffect.createOneShot(fallbackDuration, VibrationEffect.DEFAULT_AMPLITUDE)
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
        val normalized = Normalizer.normalize(trimmed.substring(0, 1), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase()
        val ch = normalized.firstOrNull() ?: '#'
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
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (sat < 0.22f || value < 0.18f) {
                    counts[AppColorFilter.MONO.ordinal] += 1
                } else {
                    val idx = when {
                        hue < 24f || hue >= 338f -> AppColorFilter.RED.ordinal
                        hue < 68f -> AppColorFilter.YELLOW.ordinal
                        hue < 168f -> AppColorFilter.GREEN.ordinal
                        hue < 258f -> AppColorFilter.BLUE.ordinal
                        else -> AppColorFilter.PURPLE.ordinal
                    }
                    counts[idx] += 3
                }
            }
        }
        if (scaled != bitmap) scaled.recycle()

        var bestIdx = AppColorFilter.MONO.ordinal
        var bestVal = 0
        for (i in 1 until counts.size) {
            if (counts[i] > bestVal) {
                bestVal = counts[i]
                bestIdx = i
            }
        }
        return AppColorFilter.entries[bestIdx]
    }
}

class AppRepository(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var allApps: List<AppEntry> = emptyList()

    @Volatile
    private var letterCache: Map<Pair<Char, AppColorFilter>, List<AppEntry>> = emptyMap()

    @Volatile
    private var recentCache: Map<AppColorFilter, List<AppEntry>> = emptyMap()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            reloadAppsAsync()
        }
    }

    fun startListening() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            packageReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        reloadAppsAsync()
    }

    fun stopListening() {
        try {
            context.unregisterReceiver(packageReceiver)
        } catch (_: Exception) {
        }
    }

    fun reloadAppsAsync(onComplete: (() -> Unit)? = null) {
        executor.execute {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            val density = context.resources.displayMetrics.density
            val iconSizePx = (58 * density).toInt().coerceAtLeast(76)
            val myPkg = context.packageName

            val loaded = resolved
                .asSequence()
                .filter { it.activityInfo?.packageName != myPkg }
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    try {
                        val pkg = info.activityInfo.packageName
                        val label = info.loadLabel(pm).toString()
                        val shortLabel = if (label.length > 11) label.take(10) + "…" else label
                        val drawable = info.loadIcon(pm)
                        val bitmap = IconColorAnalyzer.drawableToBitmap(drawable, iconSizePx)
                        val colorCat = IconColorAnalyzer.detectDominantColor(bitmap)
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

                        AppEntry(
                            label = label,
                            shortLabel = shortLabel,
                            packageName = pkg,
                            iconBitmap = bitmap,
                            firstLetter = IconColorAnalyzer.normalizeFirstLetter(label),
                            installTime = installTime,
                            colorCategory = colorCat
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                .sortedBy { it.label.lowercase() }
                .toList()

            val newLetterCache = HashMap<Pair<Char, AppColorFilter>, List<AppEntry>>()
            val newRecentCache = HashMap<AppColorFilter, List<AppEntry>>()
            val sortedByRecent = loaded.sortedByDescending { it.installTime }

            for (color in AppColorFilter.entries) {
                val filteredByColor = if (color == AppColorFilter.ALL) {
                    loaded
                } else {
                    loaded.filter { it.colorCategory == color }
                }
                newRecentCache[color] = if (color == AppColorFilter.ALL) {
                    sortedByRecent
                } else {
                    sortedByRecent.filter { it.colorCategory == color }
                }
                val grouped = filteredByColor.groupBy { it.firstLetter }
                for (ch in 'A'..'Z') {
                    newLetterCache[ch to color] = grouped[ch] ?: emptyList()
                }
            }

            allApps = loaded
            letterCache = newLetterCache
            recentCache = newRecentCache
            onComplete?.invoke()
        }
    }

    fun getAppsForLetter(letter: Char, colorFilter: AppColorFilter): List<AppEntry> {
        return letterCache[letter to colorFilter] ?: emptyList()
    }

    fun getRecentApps(limit: Int, colorFilter: AppColorFilter): List<AppEntry> {
        return (recentCache[colorFilter] ?: emptyList()).take(limit)
    }

    fun hasAppsForLetter(letter: Char, colorFilter: AppColorFilter): Boolean {
        return !(letterCache[letter to colorFilter].isNullOrEmpty())
    }
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

    fun getAccentColor(): Int {
        val entries = OneUIAccent.entries
        return entries[accentIndex.coerceIn(0, entries.lastIndex)].colorInt
    }

    fun isNightThemeActive(): Boolean {
        return when (themeMode) {
            1 -> false
            2 -> true
            else -> {
                val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                uiMode == Configuration.UI_MODE_NIGHT_YES
            }
        }
    }

    var isTriggerHidden: Boolean
        get() = prefs.getBoolean("trigger_hidden", false)
        set(value) = prefs.edit().putBoolean("trigger_hidden", value).apply()

    var isTriggerTextBlack: Boolean
        get() = prefs.getBoolean("trigger_text_black", false)
        set(value) = prefs.edit().putBoolean("trigger_text_black", value).apply()

    var barHeightPercent: Int
        get() = prefs.getInt("bar_height_percent", 78)
        set(value) = prefs.edit().putInt("bar_height_percent", value).apply()

    var verticalPositionPercent: Int
        get() = prefs.getInt("vertical_position_percent", 55)
        set(value) = prefs.edit().putInt("vertical_position_percent", value).apply()

    var triggerWidthDp: Int
        get() = prefs.getInt("trigger_width_dp", 26)
        set(value) = prefs.edit().putInt("trigger_width_dp", value).apply()

    var triggerAlphaPercent: Int
        get() = prefs.getInt("trigger_alpha_percent", 55)
        set(value) = prefs.edit().putInt("trigger_alpha_percent", value).apply()

    // Due slider separati per spaziatura lettere alfabeto e spaziatura pallini colore
    var letterSpacingExtraDp: Int
        get() = prefs.getInt("letter_spacing_extra_dp", 2).coerceIn(0, 14)
        set(value) = prefs.edit().putInt("letter_spacing_extra_dp", value.coerceIn(0, 14)).apply()

    var colorDotSpacingExtraDp: Int
        get() = prefs.getInt("color_dot_spacing_extra_dp", 4).coerceIn(0, 18)
        set(value) = prefs.edit().putInt("color_dot_spacing_extra_dp", value.coerceIn(0, 18)).apply()

    // Direzione lista app: true = dal basso verso l'alto (mano piccola), false = dall'alto verso il basso
    var isAppsBottomToTop: Boolean
        get() = prefs.getBoolean("apps_bottom_to_top", true)
        set(value) = prefs.edit().putBoolean("apps_bottom_to_top", value).apply()

    var panelScalePercent: Int
        get() = prefs.getInt("panel_scale_percent", 100)
        set(value) = prefs.edit().putInt("panel_scale_percent", value).apply()

    var panelBgAlphaPercent: Int
        get() = prefs.getInt("panel_bg_alpha_percent", 88).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("panel_bg_alpha_percent", value.coerceIn(0, 100)).apply()

    var panelBgOppositeAlphaPercent: Int
        get() = prefs.getInt("panel_bg_opposite_alpha_percent", 10).coerceIn(0, 100)
        set(value) = prefs.edit().putInt("panel_bg_opposite_alpha_percent", value.coerceIn(0, 100)).apply()

    var indexBarAlphaPercent: Int
        get() = prefs.getInt("index_bar_alpha_percent", 80)
        set(value) = prefs.edit().putInt("index_bar_alpha_percent", value).apply()

    var blurRadius: Int
        get() = prefs.getInt("blur_radius", 60)
        set(value) = prefs.edit().putInt("blur_radius", value).apply()

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
