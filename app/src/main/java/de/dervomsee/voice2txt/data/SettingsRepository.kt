package de.dervomsee.voice2txt.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SILENCE_THRESHOLD = "silence_threshold"
        private const val KEY_DEBUG_PLAY_AUDIO = "debug_play_audio"
        private const val KEY_ENABLE_BANDPASS = "enable_bandpass"
        private const val KEY_LOW_FREQ = "low_freq"
        private const val KEY_HIGH_FREQ = "high_freq"
        private const val DEFAULT_SILENCE_THRESHOLD = 500
        private const val DEFAULT_LOW_FREQ = 300
        private const val DEFAULT_HIGH_FREQ = 3000
    }

    var silenceThreshold: Int
        get() = prefs.getInt(KEY_SILENCE_THRESHOLD, DEFAULT_SILENCE_THRESHOLD)
        set(value) = prefs.edit { putInt(KEY_SILENCE_THRESHOLD, value) }

    var debugPlayAudio: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_PLAY_AUDIO, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_PLAY_AUDIO, value) }

    var enableBandpass: Boolean
        get() = prefs.getBoolean(KEY_ENABLE_BANDPASS, true)
        set(value) = prefs.edit { putBoolean(KEY_ENABLE_BANDPASS, value) }

    var lowFreq: Int
        get() = prefs.getInt(KEY_LOW_FREQ, DEFAULT_LOW_FREQ)
        set(value) = prefs.edit { putInt(KEY_LOW_FREQ, value) }

    var highFreq: Int
        get() = prefs.getInt(KEY_HIGH_FREQ, DEFAULT_HIGH_FREQ)
        set(value) = prefs.edit { putInt(KEY_HIGH_FREQ, value) }
}
