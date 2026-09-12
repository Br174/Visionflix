package com.streamflixreborn.streamflix.utils

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Build.VERSION
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.streamflixreborn.streamflix.R
import java.util.Locale

class VoiceRecognitionHelper(
    private val fragment: Fragment,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
) {

    private val context = fragment.requireContext()
    private var speechRecognizer: SpeechRecognizer? = null
    private var permissionLauncher: ActivityResultLauncher<String>? = null

    var isListening: Boolean = false
        private set

    /**
     * Non registriamo più il launcher del permesso durante l'apertura della schermata Cerca.
     * Su alcuni Android TV/Samsung questa registrazione anticipata può far terminare l'Activity.
     * Il launcher viene creato solo se l'utente preme davvero il microfono.
     */
    private fun getPermissionLauncher(): ActivityResultLauncher<String> {
        permissionLauncher?.let { return it }

        return fragment.requireActivity().activityResultRegistry.register(
            "voice_record_audio_${fragment.hashCode()}",
            ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (!fragment.isAdded) return@register
            if (isGranted) {
                startRecognition()
            } else {
                onError(context.getString(R.string.voice_error_permission_denied))
            }
        }.also { permissionLauncher = it }
    }

    fun startWithPermissionCheck() {
        val permission = Manifest.permission.RECORD_AUDIO
        when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED -> {
                startRecognition()
            }

            fragment.shouldShowRequestPermissionRationale(permission) -> {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.voice_permission_title))
                    .setMessage(context.getString(R.string.voice_permission_rationale))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        runCatching { getPermissionLauncher().launch(permission) }
                            .onFailure { onError(context.getString(R.string.voice_error_generic)) }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        onError(context.getString(R.string.voice_error_permission_denied))
                    }
                    .show()
            }

            else -> {
                runCatching { getPermissionLauncher().launch(permission) }
                    .onFailure { onError(context.getString(R.string.voice_error_generic)) }
            }
        }
    }

    fun isAvailable(): Boolean {
        return runCatching { SpeechRecognizer.isRecognitionAvailable(context) }
            .getOrDefault(false)
    }

    private fun startRecognition() {
        if (isListening) stopRecognition(keepPermissionLauncher = true)

        speechRecognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrElse {
                onError(context.getString(R.string.voice_error_generic))
                return
            }
            .apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        val activeLang = params?.getString("language")
                        Log.d("VoiceRecognitionHelper", "Riconoscimento attivo in: $activeLang")
                        onListeningStateChanged(true)
                        isListening = true
                    }

                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        onListeningStateChanged(false)
                        isListening = false
                    }

                    override fun onError(error: Int) {
                        onListeningStateChanged(false)
                        isListening = false

                        val msg = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> fragment.getString(R.string.voice_error_no_match)
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fragment.getString(R.string.voice_error_timeout)
                            SpeechRecognizer.ERROR_AUDIO -> fragment.getString(R.string.voice_error_audio)
                            else -> fragment.getString(R.string.voice_error_generic)
                        }
                        onError(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged(false)
                        isListening = false

                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val query = matches?.firstOrNull()?.trim()
                        if (!query.isNullOrEmpty()) {
                            onResult(query)
                        } else {
                            onError(context.getString(R.string.voice_error_no_result))
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }

        val providerLanguage = UserPreferences.currentProvider?.language.orEmpty()
        val languageTag = resolveFullLanguageTag(providerLanguage)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            if (VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, true)
            }
        }

        runCatching { speechRecognizer?.startListening(intent) }
            .onFailure {
                speechRecognizer?.destroy()
                speechRecognizer = null
                isListening = false
                onListeningStateChanged(false)
                onError(context.getString(R.string.voice_error_generic))
            }
    }

    fun stopRecognition() {
        stopRecognition(keepPermissionLauncher = false)
    }

    private fun stopRecognition(keepPermissionLauncher: Boolean) {
        runCatching {
            speechRecognizer?.apply {
                stopListening()
                cancel()
                destroy()
            }
        }
        speechRecognizer = null
        isListening = false
        onListeningStateChanged(false)

        if (!keepPermissionLauncher) {
            runCatching { permissionLauncher?.unregister() }
            permissionLauncher = null
        }
    }

    fun resolveFullLanguageTag(partialTag: String): String {
        val supportedTags = mapOf(
            "en" to "en-US",
            "es" to "es-ES",
            "de" to "de-DE",
            "it" to "it-IT",
            "ar" to "ar-SA",
            "fr" to "fr-FR",
        )

        val normalized = partialTag.lowercase(Locale.ROOT)
        return supportedTags[normalized] ?: Locale.getDefault().toLanguageTag()
    }
}
