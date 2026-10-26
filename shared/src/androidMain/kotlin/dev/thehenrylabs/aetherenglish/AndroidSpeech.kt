/*
 * Copyright 2026 Henry Nguyen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.thehenrylabs.aetherenglish

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * SpeechRecognizer cannot transcribe a saved PCM buffer. [AndroidSpeakSession] records PCM
 * and, only when API 31+ and [SpeechRecognizer.isOnDeviceRecognitionAvailable], listens with
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer]. EXTRA_PREFER_OFFLINE is never set.
 * There is no phoneme alignment and no goodness-of-pronunciation score.
 */
class AndroidSpeakSession(private val context: Context) {
    private val lock = Any()
    private val samples = mutableListOf<Short>()
    private var record: AudioRecord? = null
    private var recognizer: SpeechRecognizer? = null
    private var heard: String? = null

    @Volatile
    private var running: Boolean = false

    fun availability(): SpeechAvailability {
        val recognizerReady = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return SpeechPolicy.onDevice(Build.VERSION.SDK_INT, recognizerReady)
    }

    fun start() {
        stopQuietly()
        synchronized(lock) { samples.clear() }
        heard = null
        running = true
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = max(min, SAMPLE_RATE)
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        record = audio
        audio.startRecording()
        thread(name = "aether-pcm") {
            val chunk = ShortArray(bufferBytes / 2)
            while (running) {
                val count = audio.read(chunk, 0, chunk.size)
                if (count > 0) {
                    synchronized(lock) {
                        for (index in 0 until count) samples += chunk[index]
                    }
                }
            }
        }
        if (availability() == SpeechAvailability.ON_DEVICE && Build.VERSION.SDK_INT >= 31) {
            val speech = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            recognizer = speech
            speech.setRecognitionListener(listener())
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            speech.startListening(intent)
        }
    }

    fun stop(target: String): PronunciationFeedback {
        running = false
        val audio = record
        record = null
        try {
            audio?.stop()
        } catch (_: IllegalStateException) {
            // already stopped
        }
        audio?.release()
        val speech = recognizer
        recognizer = null
        try {
            speech?.stopListening()
        } catch (_: RuntimeException) {
            // recognizer already finished
        }
        speech?.destroy()
        val pcm = synchronized(lock) { samples.toShortArray() }
        return PronunciationAssessor.assess(target, heard, AudioClip(pcm, SAMPLE_RATE))
    }

    private fun stopQuietly() {
        running = false
        record?.release()
        record = null
        recognizer?.destroy()
        recognizer = null
    }

    private fun listener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle?) {
            heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!partial.isNullOrBlank()) heard = partial
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val SAMPLE_RATE: Int = 16_000
    }
}

class AndroidTts(context: Context) : TtsSpeaker {
    private val speaker = TextToSpeech(context.applicationContext) { }

    override suspend fun speak(text: String, languageTag: String): SpeakResult {
        val locale = Locale.forLanguageTag(if (languageTag.isBlank()) "en-US" else languageTag)
        val status = speaker.isLanguageAvailable(locale)
        if (status == TextToSpeech.LANG_MISSING_DATA || status == TextToSpeech.LANG_NOT_SUPPORTED) {
            return SpeakResult.VOICE_MISSING
        }
        speaker.language = locale
        speaker.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aether-tts")
        return SpeakResult.SPOKEN
    }

    override fun stop() {
        speaker.stop()
    }
}

class BufferSpeech(private val context: Context) : SpeechToText {
    override fun availability(languageTag: String): SpeechAvailability {
        val recognizerReady = Build.VERSION.SDK_INT >= 31 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return SpeechPolicy.onDevice(Build.VERSION.SDK_INT, recognizerReady)
    }

    override suspend fun transcribe(languageTag: String, audio: AudioClip): Transcript {
        return Transcript(text = null, availability = SpeechAvailability.UNAVAILABLE)
    }
}
