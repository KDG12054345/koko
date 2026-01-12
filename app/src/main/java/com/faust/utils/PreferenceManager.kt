package com.faust.utils

import android.content.Context
import android.content.SharedPreferences
import com.faust.models.UserTier

class PreferenceManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREF_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREF_NAME = "faust_prefs"
        private const val KEY_USER_TIER = "user_tier"
        private const val KEY_CURRENT_POINTS = "current_points"
        private const val KEY_LAST_MINING_TIME = "last_mining_time"
        private const val KEY_LAST_MINING_APP = "last_mining_app"
        private const val KEY_LAST_RESET_TIME = "last_reset_time"
        private const val KEY_IS_SERVICE_RUNNING = "is_service_running"
    }

    // User Tier
    fun getUserTier(): UserTier {
        val tierName = prefs.getString(KEY_USER_TIER, UserTier.FREE.name) ?: UserTier.FREE.name
        return try {
            UserTier.valueOf(tierName)
        } catch (e: IllegalArgumentException) {
            UserTier.FREE
        }
    }

    fun setUserTier(tier: UserTier) {
        prefs.edit().putString(KEY_USER_TIER, tier.name).apply()
    }

    // Current Points
    fun getCurrentPoints(): Int {
        return prefs.getInt(KEY_CURRENT_POINTS, 0)
    }

    fun setCurrentPoints(points: Int) {
        prefs.edit().putInt(KEY_CURRENT_POINTS, points.coerceAtLeast(0)).apply()
    }

    fun addPoints(points: Int) {
        val current = getCurrentPoints()
        setCurrentPoints(current + points)
    }

    fun subtractPoints(points: Int) {
        val current = getCurrentPoints()
        setCurrentPoints((current - points).coerceAtLeast(0))
    }

    // Mining Time Tracking
    fun getLastMiningTime(): Long {
        return prefs.getLong(KEY_LAST_MINING_TIME, 0)
    }

    fun setLastMiningTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_MINING_TIME, time).apply()
    }

    fun getLastMiningApp(): String? {
        return prefs.getString(KEY_LAST_MINING_APP, null)
    }

    fun setLastMiningApp(packageName: String?) {
        prefs.edit().putString(KEY_LAST_MINING_APP, packageName).apply()
    }

    // Weekly Reset
    fun getLastResetTime(): Long {
        return prefs.getLong(KEY_LAST_RESET_TIME, 0)
    }

    fun setLastResetTime(time: Long) {
        prefs.edit().putLong(KEY_LAST_RESET_TIME, time).apply()
    }

    // Service State
    fun isServiceRunning(): Boolean {
        return prefs.getBoolean(KEY_IS_SERVICE_RUNNING, false)
    }

    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_IS_SERVICE_RUNNING, running).apply()
    }

    // Clear all preferences (for testing/reset)
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
