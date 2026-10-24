package com.streamflixreborn.streamflix.utils

/** Routes a user's personal TMDb credential without exposing or logging its value.
 * Both a v3 API key and a v4 API Read Access Token can authorize TMDb v3 API calls.
 */
internal object Lab164TmdbCredentialRouting {
    sealed class Credential {
        data class V3(val key: String) : Credential()
        data class V4(val token: String) : Credential()
        data object Missing : Credential()
    }

    fun hasKey(personal: String, embedded: String): Boolean =
        resolve(personal, embedded) != Credential.Missing

    fun resolve(personal: String, embedded: String): Credential {
        val selected = personal.trim().takeUnless { it.isEmpty() || it.equals("dummy", true) }
            ?: embedded.trim().takeUnless { it.isEmpty() || it.equals("dummy", true) }
            ?: return Credential.Missing

        val token = if (selected.startsWith("Bearer ", ignoreCase = true))
            selected.substringAfter(' ').trim() else selected
        return if (token.startsWith("eyJ") && token.split('.').size == 3)
            Credential.V4(token)
        else Credential.V3(selected)
    }
}
