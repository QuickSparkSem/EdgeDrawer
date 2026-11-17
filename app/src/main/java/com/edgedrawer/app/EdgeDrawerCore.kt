package com.edgedrawer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
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

data class AppEntry(
    val label: String,
    val shortLabel: String,
    val packageName: String,
    val iconBitmap: Bitmap,
    val firstLetter: Char,
    val installTime: Long,
    val colorCategory: AppColorFilter
)

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
            val iconSizePx = (56 * density).toInt().coerceAtLeast(72)
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

            // Pre-indicizza tutte le combinazioni (Lettera, Colore) in memoria per scorrimento istantaneo
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

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("edge_drawer_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var isRightSide: Boolean
        get() = prefs.getBoolean("is_right_side", true)
        set(value) = prefs.edit().putBoolean("is_right_side", value).apply()

    // Trigger alfabetico laterale
    var isTriggerHidden: Boolean
        get() = prefs.getBoolean("trigger_hidden", false)
        set(value) = prefs.edit().putBoolean("trigger_hidden", value).apply()

    var isTriggerBottomCorner: Boolean
        get() = prefs.getBoolean("trigger_bottom_corner", false)
        set(value) = prefs.edit().putBoolean("trigger_bottom_corner", value).apply()

    // Scala altezza/dimensione trigger (35% .. 100%)
    var triggerSizePercent: Int
        get() = prefs.getInt("trigger_size_percent", 85)
        set(value) = prefs.edit().putInt("trigger_size_percent", value).apply()

    // Larghezza area tocco trigger (16 .. 44 dp)
    var triggerWidthDp: Int
        get() = prefs.getInt("trigger_width_dp", 24)
        set(value) = prefs.edit().putInt("trigger_width_dp", value).apply()

    // Opacità trigger (0 .. 100%, default 40% molto trasparente)
    var triggerAlphaPercent: Int
        get() = prefs.getInt("trigger_alpha_percent", 40)
        set(value) = prefs.edit().putInt("trigger_alpha_percent", value).apply()

    // Pannello aperto
    var panelScalePercent: Int
        get() = prefs.getInt("panel_scale_percent", 100)
        set(value) = prefs.edit().putInt("panel_scale_percent", value).apply()

    var panelAlphaPercent: Int
        get() = prefs.getInt("panel_alpha_percent", 82)
        set(value) = prefs.edit().putInt("panel_alpha_percent", value).apply()

    var blurRadius: Int
        get() = prefs.getInt("blur_radius", 50)
        set(value) = prefs.edit().putInt("blur_radius", value).apply()

    var showAppLabels: Boolean
        get() = prefs.getBoolean("show_app_labels", true)
        set(value) = prefs.edit().putBoolean("show_app_labels", value).apply()

    var recentCount: Int
        get() = prefs.getInt("recent_count", 7)
        set(value) = prefs.edit().putInt("recent_count", value).apply()
}
