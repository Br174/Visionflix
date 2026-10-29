package com.streamflixreborn.streamflix.fragments.search

/**
 * LAB181: bounded, independent provider work. These constants apply only to
 * the Italian-provider wall; normal provider searches and playback are preserved.
 */
internal object Lab181ProviderFlowPolicy {
    const val SOURCE_TIMEOUT_MS = 8_000L
    const val METADATA_TIMEOUT_MS = 12_000L
    const val REPAIR_TIMEOUT_MS = 24_000L
    const val REPAIR_COOLDOWN_MS = 10 * 60_000L

    fun allowRecovery(nowMs: Long, lastAttemptMs: Long?): Boolean =
        lastAttemptMs == null || nowMs - lastAttemptMs >= REPAIR_COOLDOWN_MS

    /** A timeout skips only one source in the current scan, never the registry. */
    fun skipCurrentScan(timedOutOrFailed: Boolean, nativeHasMore: Boolean): Boolean =
        timedOutOrFailed || !nativeHasMore
}
