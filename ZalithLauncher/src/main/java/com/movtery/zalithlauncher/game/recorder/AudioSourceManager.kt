/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG_AUDIO = "AudioSourceManager"

internal const val REC_AUDIO_SAMPLE_RATE = 48_000
internal const val REC_AUDIO_CHANNELS = 2
internal const val REC_BYTES_PER_FRAME = 2 * REC_AUDIO_CHANNELS

class AudioSourceManager(
    private val context: Context,
    private val projection: MediaProjection?
) {
    var gameAudioRecord: AudioRecord? = null
    var microphoneAudioRecord: AudioRecord? = null

    private var gameAudioStarted = false
    private var microphoneStarted = false

    var supportsAudioPlaybackCapture = false
        private set
    var supportsMicrophoneCapture = false
        private set

    init {
        supportsAudioPlaybackCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    fun initialize(): Boolean {
        Log.i(TAG_AUDIO, "=== Initialize audio sources ===")

        if (supportsAudioPlaybackCapture && projection != null) {
            Log.i(TAG_AUDIO, "Trying game audio via AudioPlaybackCapture")
            if (!initializeGameAudio()) {
                Log.w(TAG_AUDIO, "Game audio init failed; continuing with microphone fallback")
            }
        } else {
            Log.w(TAG_AUDIO, "AudioPlaybackCapture unavailable or projection null")
        }

        if (initializeMicrophone()) {
            supportsMicrophoneCapture = true
            Log.i(TAG_AUDIO, "Microphone capture ready")
        } else {
            Log.w(TAG_AUDIO, "Microphone capture unavailable")
        }

        val ready = gameAudioRecord != null || microphoneAudioRecord != null
        Log.i(TAG_AUDIO, "=== Audio sources ready: game=${gameAudioRecord != null}, mic=${microphoneAudioRecord != null} ===")
        return ready
    }

    private fun initializeGameAudio(): Boolean {
        if (projection == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        return try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(REC_AUDIO_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(
                REC_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
                .coerceAtLeast(8192) * 2

            gameAudioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            } else {
                AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(captureConfig)
                    .build()
            }

            Log.i(TAG_AUDIO, "Game audio record created successfully (buffer=$bufferSize)")
            true
        } catch (e: Exception) {
            Log.e(TAG_AUDIO, "Failed to create game audio record: ${e.message}", e)
            gameAudioRecord?.release()
            gameAudioRecord = null
            false
        }
    }

    private fun initializeMicrophone(): Boolean {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG_AUDIO, "RECORD_AUDIO permission missing")
            return false
        }

        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                REC_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(8192) * 2

            microphoneAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                REC_AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            Log.i(TAG_AUDIO, "Microphone record created successfully (buffer=$bufferSize)")
            true
        } catch (e: Exception) {
            Log.e(TAG_AUDIO, "Failed to create microphone record: ${e.message}", e)
            microphoneAudioRecord?.release()
            microphoneAudioRecord = null
            false
        }
    }

    fun startRecording(): Boolean {
        var started = false

        if (gameAudioRecord != null) {
            try {
                if (gameAudioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    gameAudioRecord!!.startRecording()
                    gameAudioStarted = true
                    Log.i(TAG_AUDIO, "Game internal audio started")
                }
                started = true
            } catch (e: Exception) {
                Log.e(TAG_AUDIO, "Failed to start game audio: ${e.message}", e)
                gameAudioStarted = false
            }
        }

        if (microphoneAudioRecord != null) {
            try {
                if (microphoneAudioRecord!!.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    microphoneAudioRecord!!.startRecording()
                    microphoneStarted = true
                    Log.i(TAG_AUDIO, "Microphone started")
                }
                started = true
            } catch (e: Exception) {
                Log.e(TAG_AUDIO, "Failed to start microphone: ${e.message}", e)
                microphoneStarted = false
            }
        }

        return started
    }

    fun stopRecording() {
        try {
            if (gameAudioRecord != null && gameAudioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                gameAudioRecord!!.stop()
            }
        } catch (_: Exception) {}
        gameAudioStarted = false

        try {
            if (microphoneAudioRecord != null && microphoneAudioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                microphoneAudioRecord!!.stop()
            }
        } catch (_: Exception) {}
        microphoneStarted = false
    }

    fun release() {
        Log.i(TAG_AUDIO, "Release audio resources")
        stopRecording()
        try { gameAudioRecord?.release() } catch (_: Exception) {}
        gameAudioRecord = null
        try { microphoneAudioRecord?.release() } catch (_: Exception) {}
        microphoneAudioRecord = null
        gameAudioStarted = false
        microphoneStarted = false
    }

    fun readGameAudio(buffer: ByteArray, offset: Int, size: Int): Int {
        if (gameAudioRecord == null) return 0
        return try {
            gameAudioRecord!!.read(buffer, offset, size)
        } catch (e: Exception) {
            Log.w(TAG_AUDIO, "Game audio read failed: ${e.message}")
            0
        }
    }

    fun readMicrophoneAudio(buffer: ByteArray, offset: Int, size: Int): Int {
        if (microphoneAudioRecord == null) return 0
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                microphoneAudioRecord!!.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                microphoneAudioRecord!!.read(buffer, offset, size, AudioRecord.READ_NON_BLOCKING)
            }
        } catch (e: Exception) {
            Log.w(TAG_AUDIO, "Microphone read failed: ${e.message}")
            0
        }
    }

    fun isGameAudioActive(): Boolean =
        gameAudioRecord != null && gameAudioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun isMicrophoneActive(): Boolean =
        microphoneAudioRecord != null && microphoneAudioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING

    fun hasAnyAudio(): Boolean = gameAudioRecord != null || microphoneAudioRecord != null

    fun mixPcm16Stereo(
        gameBytes: ByteArray,
        micBytes: ByteArray,
        gameLen: Int,
        micLen: Int,
        out: ByteArray,
        outOffset: Int,
        gameGain: Float = 0.9f,
        micGain: Float = 1.3f
    ) {
        val safeLen = minOf(gameLen, micLen)
        var i = 0
        var outIndex = outOffset

        while (i + 1 < safeLen) {
            val gameSample = readInt16LE(gameBytes, i)
            val micSample = readInt16LE(micBytes, i)
            val mixed = ((gameSample.toFloat() * gameGain) + (micSample.toFloat() * micGain))
                .coerceIn(-32768f, 32767f)
                .toInt()
            writeInt16LE(out, outIndex, mixed)
            i += 2
            outIndex += 2
        }

        if (gameLen > 0 && micLen == 0) {
            System.arraycopy(gameBytes, 0, out, outOffset, gameLen.coerceAtMost(out.size - outOffset))
        } else if (micLen > 0 && gameLen == 0) {
            System.arraycopy(micBytes, 0, out, outOffset, micLen.coerceAtMost(out.size - outOffset))
        }
    }

    private fun readInt16LE(buf: ByteArray, offset: Int): Int {
        val lo = buf[offset].toInt() and 0xFF
        val hi = buf[offset + 1].toInt() and 0xFF
        return (hi shl 8) or lo
    }

    private fun writeInt16LE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
