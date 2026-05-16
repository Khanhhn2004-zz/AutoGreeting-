package com.example.carchatbot.audio

import android.media.AudioAttributes
import android.media.AudioManager

object PlaybackAudioProfile {
    // Some automotive head units only expose audible output on the media route.
    val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    const val focusGain: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    const val legacyStreamType: Int = AudioManager.STREAM_MUSIC
}
