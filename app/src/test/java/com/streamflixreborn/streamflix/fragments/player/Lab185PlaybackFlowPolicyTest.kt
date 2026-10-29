package com.streamflixreborn.streamflix.fragments.player

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.*
import org.junit.Test

class Lab185PlaybackFlowPolicyTest {
    @Test fun disabledBufferRetainsOriginalMedia3Thresholds() {
        val b = Lab185PlaybackFlowPolicy.profile(false, false)
        assertEquals(DefaultLoadControl.DEFAULT_MIN_BUFFER_MS, b.minBufferMs)
        assertEquals(DefaultLoadControl.DEFAULT_MAX_BUFFER_MS, b.maxBufferMs)
        assertEquals(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, b.startPlaybackMs)
        assertEquals(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS, b.resumePlaybackMs)
    }

    @Test fun extraBufferRaisesRealMinimumWithinFiniteMemoryBudget() {
        val base = Lab185PlaybackFlowPolicy.profile(false, false)
        val tv = Lab185PlaybackFlowPolicy.profile(true, false)
        val low = Lab185PlaybackFlowPolicy.profile(true, true)
        assertTrue(tv.minBufferMs > base.minBufferMs)
        assertTrue(tv.maxBufferMs >= tv.minBufferMs)
        assertTrue(low.maxBufferMs <= tv.maxBufferMs)
        assertTrue(low.minBufferMs <= low.maxBufferMs)
        assertTrue(tv.resumePlaybackMs >= tv.startPlaybackMs)
        assertTrue(tv.maxBufferMs <= 120_000)
    }

    @Test fun recoveryIsBoundedAndShortensOnlyAfterFirstStall() {
        assertEquals(20_000L, Lab185PlaybackFlowPolicy.timeoutForAttempt(0))
        assertEquals(16_000L, Lab185PlaybackFlowPolicy.timeoutForAttempt(1))
        assertEquals(16_000L, Lab185PlaybackFlowPolicy.timeoutForAttempt(2))
    }
}
