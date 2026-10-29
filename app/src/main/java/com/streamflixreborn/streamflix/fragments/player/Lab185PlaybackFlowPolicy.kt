package com.streamflixreborn.streamflix.fragments.player

import androidx.media3.exoplayer.DefaultLoadControl

/**
 * LAB185: bounded prebuffer profiles for slow, variable-rate video servers.
 * An enlarged MAX buffer without a larger MIN buffer does not preload more video.
 * The low-RAM profile avoids allocating excessive buffers on inexpensive TVs.
 */
internal object Lab185PlaybackFlowPolicy {
    data class Profile(
        val minBufferMs: Int, val maxBufferMs: Int,
        val startPlaybackMs: Int, val resumePlaybackMs: Int,
    )

    fun profile(extraBuffering: Boolean, lowRam: Boolean): Profile =
        when {
            !extraBuffering -> Profile(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            lowRam -> Profile(55_000, 75_000, 1_500, 4_000)
            else -> Profile(60_000, 120_000, 1_500, 5_000)
        }

    // The first slow source gets time to recover; subsequent attempts should
    // not trap the user in two more 20-second loading screens.
    fun timeoutForAttempt(attempts: Int): Long =
        if (attempts <= 0) 20_000L else 16_000L
}
