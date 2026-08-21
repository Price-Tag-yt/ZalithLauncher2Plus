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

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GameRecorder"
private const val FRAME_RATE = 30
private const val VIDEO_BIT_RATE = 6_000_000
private const val AUDIO_SAMPLE_RATE = 48_000
private const val AUDIO_BIT_RATE = 128_000
private const val AUDIO_CHANNELS = 2
private const val BYTES_PER_FRAME = 2 * AUDIO_CHANNELS

object GameRecorder {

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _micEnabled = MutableStateFlow(false)
    val micEnabled: StateFlow<Boolean> = _micEnabled.asStateFlow()

    private val timerScope  = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val encodeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var timerJob:       Job? = null
    private var videoEncodeJob: Job? = null
    private var audioJob:       Job? = null

    @Volatile private var accumulatedMs = 0L
    @Volatile private var resumeTimeMs  = 0L

    private var videoCodec:     MediaCodec? = null
    @Volatile private var inputSurface: android.view.Surface? = null
    @Volatile private var videoTrackIndex = -1

    private var audioRecord:    AudioRecord?  = null
    private var micAudioRecord: AudioRecord?  = null
    private var audioCodec:     MediaCodec?   = null
    @Volatile private var audioTrackIndex = -1

    private var muxer:          MediaMuxer?   = null
    @Volatile private var muxerStarted = false
    private val muxerLock = Any()

    private var mediaProjection: MediaProjection? = null

    private var appContext: Context? = null

    private var captureThread:  HandlerThread? = null
    private var captureHandler: Handler?       = null
    private val isCapturing = AtomicBoolean(false)

    private var captureBitmap: Bitmap? = null

    @Volatile private var recordingStartNs      = 0L
    @Volatile private var muxerStartedNs        = 0L
    @Volatile private var captureStartNs        = 0L
    @Volatile private var audioStartOffsetUs    = 0L
    @Volatile private var totalPausedUs         = 0L
    @Volatile private var pauseStartMs          = 0L

    @Volatile private var pendingUri:  android.net.Uri? = null
    @Volatile private var pendingFile: File?            = null

    fun start(context: Context, projection: MediaProjection) {
        if (_state.value != RecordingState.IDLE) return

        val view = GameSurfaceRegistry.getView()
        if (view == null) {
            Log.e(TAG, "No game surface registered — cannot start recording")
            return
        }

        val w = (view.width.coerceAtLeast(2)  / 2) * 2
        val h = (view.height.coerceAtLeast(2) / 2) * 2

        try {
            val (uri, file) = createOutputEntry(context)
            pendingUri  = uri
            pendingFile = file

            mediaProjection = projection
            appContext      = context.applicationContext

            muxerStarted       = false
            videoTrackIndex    = -1
            audioTrackIndex    = -1
            recordingStartNs   = 0L
            muxerStartedNs     = 0L
            captureStartNs     = 0L
            audioStartOffsetUs = 0L
            totalPausedUs      = 0L

            val fd = context.contentResolver.openFileDescriptor(uri, "w")!!.fileDescriptor
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE,    VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE,  FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(videoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
            }

            val audioFmt = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,       AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioReadChunkSize())
            }
            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { c ->
                c.configure(audioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
            }

            audioRecord    = buildAudioRecord(projection)
            micAudioRecord = buildMicAudioRecord()
            _micEnabled.value = false

            captureThread  = HandlerThread("GameRecorder-Capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            accumulatedMs    = 0L
            resumeTimeMs     = System.currentTimeMillis()
            _elapsedMs.value = 0L
            _state.value = RecordingState.RECORDING
            startTimerTick()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}")
            cleanup()
            return
        }

        encodeScope.launch {
            try {
                primeVideoTrack()
                primeAudioTrack()

                if (_state.value != RecordingState.RECORDING) {
                    cleanup(); return@launch
                }

                recordingStartNs = System.nanoTime()
                audioRecord!!.startRecording()
                audioStartOffsetUs = (System.nanoTime() - recordingStartNs) / 1_000L

                if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                    synchronized(muxerLock) {
                        muxer!!.start()
                        muxerStarted  = true
                        muxerStartedNs = System.nanoTime()
                        Log.i(TAG, "MediaMuxer pre-started (video=$videoTrackIndex, audio=$audioTrackIndex)")
                    }
                } else {
                    Log.w(TAG, "Priming incomplete (video=$videoTrackIndex audio=$audioTrackIndex) — encode jobs will register remaining tracks")
                }

                discardVideoOutput(videoCodec!!)

                startVideoEncodeJob()
                startAudioJob()

                captureStartNs = System.nanoTime()

                isCapturing.set(true)
                scheduleNextFrame()

                playRecordingStartSound()

                Log.i(TAG, "Recording started ${w}x${h} — audio via AudioPlaybackCapture")
            } catch (e: Exception) {
                Log.e(TAG, "Failed during codec priming: ${e.message}")
                cleanup()
            }
        }
    }

    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        try {
            isCapturing.set(false)
            pauseStartMs   = System.currentTimeMillis()
            accumulatedMs += System.currentTimeMillis() - resumeTimeMs
            timerJob?.cancel(); timerJob = null
            runCatching {
                if (_micEnabled.value &&
                    micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING
                ) {
                    micAudioRecord?.stop()
                }
            }
            _state.value = RecordingState.PAUSED
            Log.i(TAG, "Recording paused at ${accumulatedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause: ${e.message}")
        }
    }

    fun resume() {
        if (_state.value != RecordingState.PAUSED) return
        try {
            totalPausedUs += (System.currentTimeMillis() - pauseStartMs) * 1_000L
            runCatching {
                if (_micEnabled.value &&
                    micAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING
                ) {
                    micAudioRecord?.startRecording()
                }
            }
            isCapturing.set(true)
            resumeTimeMs = System.currentTimeMillis()
            startTimerTick()
            _state.value = RecordingState.RECORDING
            scheduleNextFrame()
            Log.i(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume: ${e.message}")
        }
    }

    fun toggleMicrophone() {
        if (_micEnabled.value) disableMicrophone() else enableMicrophone()
    }

    private fun enableMicrophone() {
        val state = _state.value
        if (state != RecordingState.RECORDING && state != RecordingState.PAUSED) return
        val mar = micAudioRecord ?: run {
            Log.w(TAG, "Microphone AudioRecord not available")
            return
        }
        try {
            if (mar.recordingState != AudioRecord.RECORDSTATE_RECORDING &&
                state == RecordingState.RECORDING
            ) {
                mar.startRecording()
            }
            _micEnabled.value = true
            Log.i(TAG, "Microphone recording enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable microphone: ${e.message}")
        }
    }

    private fun disableMicrophone() {
        _micEnabled.value = false
        val mar = micAudioRecord ?: return
        try {
            if (mar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                mar.stop()
            }
            Log.i(TAG, "Microphone recording disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disable microphone: ${e.message}")
        }
    }

    fun stopAndSave(context: Context) {
        val current = _state.value
        if (current == RecordingState.IDLE || current == RecordingState.STOPPING) return
        _state.value = RecordingState.STOPPING
        isCapturing.set(false)
        timerJob?.cancel(); timerJob = null
        captureHandler?.post { finalise(context) }
            ?: run { finalise(context) }
    }

    // ============ FIX 1 : NOUVEAU primeVideoTrack() ============
    @Suppress("DEPRECATION")
    private fun primeVideoTrack() {
        val codec   = videoCodec   ?: return
        val surface = inputSurface ?: return
        val info    = MediaCodec.BufferInfo()

        runCatching {
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surface.lockHardwareCanvas()
                else surface.lockCanvas(null)
            } catch (_: Exception) { surface.lockCanvas(null) }
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
            surface.unlockCanvasAndPost(canvas)
        }

        repeat(500) {
            when (val idx = codec.dequeueOutputBuffer(info, 5_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                    discardVideoOutput(codec)
                    Log.i(TAG, "Video track primed (index=$videoTrackIndex)")
                    return
                }
                else -> if (idx >= 0) codec.releaseOutputBuffer(idx, false)
            }
        }
        Log.w(TAG, "Video codec did not emit FORMAT_CHANGED during priming")
    }

    private fun primeAudioTrack() {
        val ac    = audioCodec ?: return
        val info  = MediaCodec.BufferInfo()
        val chunkSize = audioReadChunkSize()
        val inputIdx = ac.dequeueInputBuffer(200_000L)
        if (inputIdx >= 0) {
            ac.getInputBuffer(inputIdx)!!.apply { clear(); put(ByteArray(chunkSize)) }
            ac.queueInputBuffer(inputIdx, 0, chunkSize, 0L, 0)
        }
        repeat(200) {
            when (val idx = ac.dequeueOutputBuffer(info, 10_000L)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioTrackIndex = muxer!!.addTrack(ac.outputFormat)
                    Log.i(TAG, "Audio track primed (index=$audioTrackIndex)")
                    discardAudioOutput(ac)
                    return
                }
                else -> if (idx >= 0) ac.releaseOutputBuffer(idx, false)
            }
        }
        Log.w(TAG, "Audio codec did not emit FORMAT_CHANGED during priming")
    }

    private fun discardAudioOutput(ac: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = ac.dequeueOutputBuffer(info, 0L)
            if (idx >= 0) ac.releaseOutputBuffer(idx, false) else return
        }
    }

    private fun startVideoEncodeJob() {
        videoEncodeJob = encodeScope.launch {
            val bufInfo = MediaCodec.BufferInfo()
            val codec   = videoCodec ?: return@launch
            while (isActive) {
                val idx = codec.dequeueOutputBuffer(bufInfo, 10_000L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (!muxerStarted) {
                                videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                                tryStartMuxerLocked()
                            }
                        }
                    }
                    idx >= 0 -> {
                        val isConfig = bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        val isEos    = bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM  != 0
                        if (!isConfig && bufInfo.size > 0) {
                            val adjusted = adjustVideoTimestampUs(bufInfo.presentationTimeUs)
                            if (adjusted >= 0) {
                                val buf = codec.getOutputBuffer(idx)!!
                                bufInfo.presentationTimeUs = adjusted
                                synchronized(muxerLock) {
                                    if (muxerStarted)
                                        muxer!!.writeSampleData(videoTrackIndex, buf, bufInfo)
                                }
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (isEos) break
                    }
                }
            }
        }
    }

    // ============ FIX 2 : NOUVEAU startAudioJob() ============
    private fun startAudioJob() {
        audioJob = encodeScope.launch {
            val ar        = audioRecord ?: return@launch
            val ac        = audioCodec  ?: return@launch
            val chunkSize = audioReadChunkSize()
            val pcmBuf    = ByteArray(chunkSize)
            val micBuf    = ByteArray(chunkSize)

            var totalFrames = 0L

            try {
                while (isActive &&
                    _state.value != RecordingState.STOPPING &&
                    _state.value != RecordingState.IDLE
                ) {
                    if (_state.value == RecordingState.PAUSED) {
                        delay(30L)
                        continue
                    }

                    val read = ar.read(pcmBuf, 0, chunkSize)
                    if (read <= 0) continue

                    if (_micEnabled.value) {
                        val mar = micAudioRecord
                        if (mar != null && mar.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            val micRead = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                mar.read(micBuf, 0, read, AudioRecord.READ_BLOCKING)
                            } else {
                                mar.read(micBuf, 0, read, AudioRecord.READ_NON_BLOCKING)
                            }
                            if (micRead > 0) {
                                amplifyAndMixPcm16Le(pcmBuf, micBuf, minOf(read, micRead), 
                                    gameplayGain = 0.8f, micGain = 1.5f)
                            }
                        }
                    }

                    val framesInBatch = read.toLong() / BYTES_PER_FRAME
                    val pts = audioStartOffsetUs + totalFrames * 1_000_000L / AUDIO_SAMPLE_RATE
                    totalFrames += framesInBatch

                    var inputIdx = ac.dequeueInputBuffer(5_000L)
                    if (inputIdx < 0) {
                        drainAudioCodec(ac, endOfStream = false)
                        inputIdx = ac.dequeueInputBuffer(10_000L)
                    }
                    if (inputIdx >= 0) {
                        ac.getInputBuffer(inputIdx)!!.apply { clear(); put(pcmBuf, 0, read) }
                        ac.queueInputBuffer(inputIdx, 0, read, pts.coerceAtLeast(0L), 0)
                    } else {
                        Log.w(TAG, "Audio encoder input buffer unavailable — batch dropped ($framesInBatch frames)")
                    }

                    drainAudioCodec(ac, endOfStream = false)
                }
            } finally {
                val eosIdx = ac.dequeueInputBuffer(5_000L)
                if (eosIdx >= 0)
                    ac.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drainAudioCodec(ac, endOfStream = true)
                runCatching { ar.stop() }
            }
        }
    }

    // ============ FIX 3 : NOUVELLE fonction amplifyAndMixPcm16Le() ============
    private fun amplifyAndMixPcm16Le(
        dst: ByteArray,
        src: ByteArray,
        len: Int,
        gameplayGain: Float = 1.0f,
        micGain: Float = 1.0f
    ) {
        var i = 0
        while (i + 1 < len) {
            val gameplayRaw = ((dst[i].toInt() and 0xFF) or ((
