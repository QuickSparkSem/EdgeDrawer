package com.edgedrawer.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class AppRepository(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    @Volatile
    private var allApps: List<AppEntry> = emptyList()

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
            val iconSizePx = (48 * density).toInt().coerceAtLeast(64)
            val myPkg = context.packageName

            val loaded = resolved
                .asSequence()
                .filter { it.activityInfo?.packageName != myPkg }
                .distinctBy { it.activityInfo.packageName }
                .mapNotNull { info ->
                    try {
                        val pkg = info.activityInfo.packageName
                        val label = info.loadLabel(pm).toString()
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

            allApps = loaded
            onComplete?.invoke()
        }
    }

    fun getAppsForLetter(letter: Char, colorFilter: AppColorFilter): List<AppEntry> {
        return allApps.filter { app ->
            val letterMatches = if (letter == '#') {
                app.firstLetter == '#'
            } else {
                app.firstLetter == letter
            }
            val colorMatches = (colorFilter == AppColorFilter.ALL || app.colorCategory == colorFilter)
            letterMatches && colorMatches
        }
    }

    fun getRecentApps(limit: Int, colorFilter: AppColorFilter): List<AppEntry> {
        return allApps
            .filter { colorFilter == AppColorFilter.ALL || it.colorCategory == colorFilter }
            .sortedByDescending { it.installTime }
            .take(limit)
    }

    fun hasAppsForLetter(letter: Char, colorFilter: AppColorFilter): Boolean {
        return allApps.any { app ->
            app.firstLetter == letter &&
                (colorFilter == AppColorFilter.ALL || app.colorCategory == colorFilter)
        }
    }

    fun countAppsForColor(colorFilter: AppColorFilter): Int {
        if (colorFilter == AppColorFilter.ALL) return allApps.size
        return allApps.count { it.colorCategory == colorFilter }
    }
}
