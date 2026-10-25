package com.streamflixreborn.streamflix.fragments.player

import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test

class Lab166TvPlaybackRecoveryPolicyTest {
    private val policy = Lab166TvPlaybackRecoveryPolicy

    @Test fun bufferingDoesNotWriteToDatabase() {
        assertFalse(policy.shouldPersistProgress(false, true, Player.STATE_BUFFERING, true))
        assertFalse(policy.shouldPersistProgress(false, true, Player.STATE_IDLE, true))
        assertFalse(policy.shouldPersistProgress(true, true, Player.STATE_READY, true))
    }

    @Test fun explicitPauseAndMovieEndStillSaveProgress() {
        assertTrue(policy.shouldPersistProgress(false, false, Player.STATE_READY, true))
        assertTrue(policy.shouldPersistProgress(false, true, Player.STATE_ENDED, true))
        assertFalse(policy.shouldPersistProgress(false, false, Player.STATE_READY, false))
    }

    @Test fun stallRecoveryIsFiniteAndCanUseAnotherServer() {
        assertEquals(policy.Action.RESTART_SOURCE,
            policy.actionForBuffering(true, true, true, 0, true))
        assertEquals(policy.Action.NEXT_SERVER,
            policy.actionForBuffering(true, true, true, 1, true))
        assertEquals(policy.Action.STOP,
            policy.actionForBuffering(true, true, true, 2, true))
        assertEquals(policy.Action.STOP,
            policy.actionForBuffering(true, true, true, 1, false))
    }

    @Test fun stoppedPausedAndUninitializedPlaybackCannotAutoRestart() {
        assertNull(policy.actionForBuffering(false, true, true, 0, true))
        assertNull(policy.actionForBuffering(true, false, true, 0, true))
        assertNull(policy.actionForBuffering(true, true, false, 0, true))
    }

    @Test fun recoveryBudgetOnlyResetsAfterStableContentProgress() {
        assertFalse(policy.mayResetRecoveryAfterProgress(25_000L, 12_000L))
        assertTrue(policy.mayResetRecoveryAfterProgress(105_000L, 12_000L))
        assertFalse(policy.mayResetRecoveryAfterProgress(0L, 100_000L))
    }
}
