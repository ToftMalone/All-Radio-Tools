package com.allnetworktools.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }
enum class Units { Metric, Imperial }
enum class SignalDisplay { Dbm, Percent }

data class AppSettings(
    val theme: ThemeMode = ThemeMode.System,
    /** Always on: colours follow the wallpaper (Material You). */
    val dynamicColor: Boolean = true,
    val units: Units = Units.Metric,
    val signal: SignalDisplay = SignalDisplay.Dbm,
    /** Fixed at one second. */
    val refreshSeconds: Float = 1f,
    val haptics: Boolean = true,
    val keepAwake: Boolean = false,
    /** Short tones for a signal found, a lock, a lost lock and a pass ended; off unless switched on. */
    val soundCues: Boolean = false,
    val onboardingDone: Boolean = false,
    /** Permission groups already requested once, to detect a permanent denial. */
    val askedPermissions: Set<String> = emptySet(),
    /** Monthly mobile data allowance in GB, 0 when not set. */
    /** Look for a newer release on GitHub at launch and install it. */
) {
    val refreshMillis: Long get() = (refreshSeconds * 1000).toLong()
}

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val units = stringPreferencesKey("units")
        val signal = stringPreferencesKey("signal")
        val haptics = booleanPreferencesKey("haptics")
        val awake = booleanPreferencesKey("keep_awake")
        val cues = booleanPreferencesKey("sound_cues")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val asked = stringSetPreferencesKey("asked_permissions")
    }

    val settings: Flow<AppSettings> = context.store.data.map { p ->
        val d = AppSettings()
        AppSettings(
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            units = p[K.units]?.let { runCatching { Units.valueOf(it) }.getOrNull() } ?: d.units,
            signal = p[K.signal]?.let { runCatching { SignalDisplay.valueOf(it) }.getOrNull() } ?: d.signal,
            haptics = p[K.haptics] ?: d.haptics,
            keepAwake = p[K.awake] ?: d.keepAwake,
            soundCues = p[K.cues] ?: d.soundCues,
            onboardingDone = p[K.onboarding] ?: d.onboardingDone,
            askedPermissions = p[K.asked] ?: d.askedPermissions,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.store.edit { it[K.theme] = v.name }
    suspend fun setUnits(v: Units) = context.store.edit { it[K.units] = v.name }
    suspend fun setSignal(v: SignalDisplay) = context.store.edit { it[K.signal] = v.name }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[K.haptics] = v }
    suspend fun setKeepAwake(v: Boolean) = context.store.edit { it[K.awake] = v }
    suspend fun setSoundCues(v: Boolean) = context.store.edit { it[K.cues] = v }
    suspend fun setOnboardingDone(done: Boolean = true) = context.store.edit { it[K.onboarding] = done }
    suspend fun markAsked(group: String) = context.store.edit { it[K.asked] = (it[K.asked] ?: emptySet()) + group }
}
