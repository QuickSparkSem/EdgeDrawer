package com.edgedrawer.app

import android.content.Context

class PrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("edge_drawer_prefs", Context.MODE_PRIVATE)

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean("service_enabled", false)
        set(value) = prefs.edit().putBoolean("service_enabled", value).apply()

    var isRightSide: Boolean
        get() = prefs.getBoolean("is_right_side", true)
        set(value) = prefs.edit().putBoolean("is_right_side", value).apply()

    var blurRadius: Int
        get() = prefs.getInt("blur_radius", 55)
        set(value) = prefs.edit().putInt("blur_radius", value).apply()

    var triggerWidthDp: Int
        get() = prefs.getInt("trigger_width_dp", 22)
        set(value) = prefs.edit().putInt("trigger_width_dp", value).apply()

    var recentCount: Int
        get() = prefs.getInt("recent_count", 7)
        set(value) = prefs.edit().putInt("recent_count", value).apply()
}
