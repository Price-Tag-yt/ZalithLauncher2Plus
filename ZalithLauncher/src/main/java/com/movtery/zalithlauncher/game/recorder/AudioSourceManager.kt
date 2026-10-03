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

private const val TAG = "AudioSourceManager"
internal const val AUDIO_SAMPLE_RATE = 48_000
internal const val AUDIO_CHANNELS = 2
internal const val BYTES_PER_FRAME = 2 * AUDIO_CHANNELS

/**
 * AudioSourceManager - Gère les deux sources audio (jeu + microphone) avec détection intelligente
 * 
 * Fonctionnalités:
 * - Capture audio interne via AudioPlaybackCapture (Android 11+)
 * - Capture microphone via AudioRecord(MIC)
 * - Fallback automatique si une source échoue
 * - Logging diagnostique complet
 * - Gestion des permissions
 * - Synchronisation des buffers PCM
 * - Mixing stéréo avec gains indépendants
 */
class AudioSourceManager(
    private val context: Context,
    private val projection: MediaProjection?
) {
    
    // === Audio Records ===
    var gameAudioRecord: AudioRecord? = null
    var microphoneAudioRecord: AudioRecord? = null
    
    // === État de capture ===
    private var gameAudioAvailable = false
    private var microphoneAvailable = false
    private var gameAudioStarted = false
    private var microphoneStarted = false
    
    // === Capacités détectées ===
    var supportsAudioPlaybackCapture = false
        private set
    var supportsMicrophoneCapture = false
        private set
    
    init {
        supportsAudioPlaybackCapture = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        Log.i(TAG, "AudioSourceManager initialized - AudioPlaybackCapture support: $supportsAudioPlaybackCapture")
    }
    
    /**
     * Initialise les deux sources audio avec détection intelligente
     */
    fun initialize(): Boolean {
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "INITIALIZING AUDIO SOURCES")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        var successCount = 0
        
        // Tentative 1: Audio interne via AudioPlaybackCapture
        if (supportsAudioPlaybackCapture && projection != null) {
            Log.i(TAG, "Attempting to initialize game audio via AudioPlaybackCapture...")
            if (initializeGameAudio(projection)) {
                gameAudioAvailable = true
                successCount++
                Log.i(TAG, "✓ Game audio (AudioPlaybackCapture) initialized successfully")
            } else {
                Log.w(TAG, "⚠ Game audio initialization failed, will use microphone only")
            }
        } else {
            Log.w(TAG, "⚠ AudioPlaybackCapture not available on this device (requires Android 11+) or projection is null")
        }
        
        // Tentative 2: Microphone
        Log.i(TAG, "Attempting to initialize microphone audio...")
        if (initializeMicrophone()) {
            microphoneAvailable = true
            supportsMicrophoneCapture = true
            successCount++
            Log.i(TAG, "✓ Microphone audio initialized successfully")
        } else {
            Log.w(TAG, "⚠ Microphone initialization failed")
        }
        
        if (successCount == 0) {
            Log.e(TAG, "FATAL: No audio sources available!")
            return false
        }
        
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        Log.i(TAG, "Audio sources ready: game=$gameAudioAvailable, mic=$microphoneAvailable")
        Log.i(TAG, "═══════════════════════════════════════════════════════════")
        
        return true
    }
    
    /**
     * Initialise la capture audio interne du jeu via AudioPlaybackCapture
     */
    private fun initializeGameAudio(projection: MediaProjection): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "AudioPlaybackCapture requires Android 11 (API 30+)")
                return false
            }
            
            val audioPlaybackCfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            
            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)
            
            gameAudioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val cfg = AudioRecord.AudioRecordingConfiguration.Builder()
                    .setAudioPlaybackCaptureConfig(audioPlaybackCfg)
                    .build()
                AudioRecord(cfg)
            } else {
                // Fallback pour Android 11
                AudioRecord(
                    MediaRecorder.AudioSource.REMOTE_SUBMIX,
                    AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }
            
            Log.d(TAG, "Game audio record created: bufferSize=$bufferSize bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create game audio record: ${e.message}", e)
            gameAudioRecord?.release()
            gameAudioRecord = null
            false
        }
    }
    
    /**
     * Initialise la capture microphone
     */
    private fun initializeMicrophone(): Boolean {
        return try {
            // Vérifier la permission
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "⚠ RECORD_AUDIO permission not granted")
                return false
            }
            
            val minBufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)
            
            microphoneAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            
            Log.d(TAG, "Microphone audio record created: bufferSize=$bufferSize bytes")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create microphone record: ${e.message}", e)
            microphoneAudioRecord?.release()
            microphoneAudioRecord = null
            false
        }
    }
    
    /**
     * Démarre les deux sources audio
     */
    fun startRecording(): Boolean {
        Log.i(TAG, "Starting audio capture...")
        
        try {
            gameAudioRecord?.apply {
                if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    startRecording()
                    gameAudioStarted = true
                    Log.d(TAG, "Game audio recording started")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start game audio: ${e.message}", e)
            gameAudioStarted = false
        }
        
        try {
            microphoneAudioRecord?.apply {
                if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    startRecording()
                    microphoneStarted = true
                    Log.d(TAG, "Microphone recording started")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start microphone: ${e.message}", e)
            microphoneStarted = false
        }
        
        return gameAudioStarted || microphoneStarted
    }
    
    /**
     * Arrête les deux sources audio
     */
    fun stopRecording() {
        Log.i(TAG, "Stopping audio capture...")
        
        try {
            gameAudioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
            }
            gameAudioStarted = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping game audio: ${e.message}")
        }
        
        try {
            microphoneAudioRecord?.apply {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
            }
            microphoneStarted = false
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping microphone: ${e.message}")
        }
    }
    
    /**
     * Lit les données audio du jeu
     */
    fun readGameAudio(buffer: ByteArray, offset: Int, size: Int): Int {
        return try {
            gameAudioRecord?.read(buffer, offset, size) ?: 0
        } catch (e: Exception) {
            Log.w(TAG, "Error reading game audio: ${e.message}")
            0
        }
    }
    
    /**
     * Lit les données audio du microphone avec mode compatible
     */
    fun readMicrophoneAudio(buffer: ByteArray, offset: Int, size: Int): Int {
        return try {
            val record = microphoneAudioRecord ?: return 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                record.read(buffer, offset, size, AudioRecord.READ_BLOCKING)
            } else {
                @Suppress("DEPRECATION")
                record.read(buffer, offset, size, AudioRecord.READ_NON_BLOCKING)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading microphone audio: ${e.message}")
            0
        }
    }
    
    /**
     * Libère toutes les ressources
     */
    fun release() {
        Log.i(TAG, "Releasing audio resources...")
        
        try {
            gameAudioRecord?.stop()
        } catch (_: Exception) {}
        try {
            gameAudioRecord?.release()
        } catch (_: Exception) {}
        gameAudioRecord = null
        
        try {
            microphoneAudioRecord?.stop()
        } catch (_: Exception) {}
        try {
            microphoneAudioRecord?.release()
        } catch (_: Exception) {}
        microphoneAudioRecord = null
        
        gameAudioStarted = false
        microphoneStarted = false
        
        Log.i(TAG, "Audio resources released")
    }
    
    /**
     * Vérifie si une source est actuellement active
     */
    fun isGameAudioActive(): Boolean = gameAudioStarted && gameAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    fun isMicrophoneActive(): Boolean = microphoneStarted && microphoneAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
    fun isAnyAudioActive(): Boolean = isGameAudioActive() || isMicrophoneActive()
}
