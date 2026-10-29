package com.streamflixreborn.streamflix.utils

import android.content.Context
import java.security.MessageDigest

/**
 * Persist only genuinely declared audio tracks, scoped by provider, item and
 * selected mirror. Never infer dubbed audio from the TMDb original language.
 */
internal object Lab186AudioEvidenceStore {
    private const val FILE = "verlezza_audio_evidence_v1"

    private fun key(provider: String, id: String, mirror: String): String {
        val bytes = (provider + "\n" + id + "\n" + mirror.trimEnd('/')).toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    fun get(context: Context, provider: String?, id: String, mirror: String): Lab186AudioEvidencePolicy.Evidence {
        if (provider.isNullOrBlank() || id.isBlank()) return Lab186AudioEvidencePolicy.Evidence.UNKNOWN
        val value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(key(provider, id, mirror), null)
        return Lab186AudioEvidencePolicy.Evidence.entries.firstOrNull { it.name == value }
            ?: Lab186AudioEvidencePolicy.Evidence.UNKNOWN
    }

    fun remember(context: Context, provider: String?, id: String, mirror: String,
                 evidence: Lab186AudioEvidencePolicy.Evidence) {
        if (provider.isNullOrBlank() || id.isBlank() ||
            evidence == Lab186AudioEvidencePolicy.Evidence.UNKNOWN) return
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(key(provider, id, mirror), evidence.name).apply()
    }
}
