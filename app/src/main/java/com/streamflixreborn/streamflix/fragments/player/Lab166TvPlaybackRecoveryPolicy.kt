package com.streamflixreborn.streamflix.fragments.player

import androidx.media3.common.Player

/** LAB166: finite TV buffering recovery and pause-only persistence policy. */
internal object Lab166TvPlaybackRecoveryPolicy {
    const val BUFFERING_TIMEOUT_MS = 20_000L

    enum class Action { RESTART_SOURCE, NEXT_SERVER, STOP }

    fun shouldPersistProgress(
        isPlaying: Boolean,
        playWhenReady: Boolean,
        playbackState: Int,
        hasMediaUri: Boolean,
    ): Boolean =
        !isPlaying && hasMediaUri &&
            (!playWhenReady || playbackState == Player.STATE_ENDED)

    fun actionForBuffering(
        buffering: Boolean,
        playWhenReady: Boolean,
        hasMediaUri: Boolean,
        attempts: Int,
        hasNextServer: Boolean,
    ): Action? {
        if (!buffering || !playWhenReady || !hasMediaUri) return null
        return when {
            attempts <= 0 -> Action.RESTART_SOURCE
            attempts == 1 && hasNextServer -> Action.NEXT_SERVER
            else -> Action.STOP
        }
    }

    fun mayResetRecoveryAfterProgress(currentPositionMs: Long, lastRecoveryPositionMs: Long): Boolean =
        currentPositionMs - lastRecoveryPositionMs >= 90_000L
}
