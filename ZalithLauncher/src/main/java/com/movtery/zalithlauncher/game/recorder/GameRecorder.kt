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
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
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
import android.view.Surface
import android.view.View
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
import kotlin.math.min

private const val TAG = "GameRecorder"
private const val FRAME_RATE = 30
private const val VIDEO_BIT_RATE = 2_500_000
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

    private val timerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val encodeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var timerJob: Job? = null
    private var videoEncodeJob: Job? = null
    private var audioJob: Job? = null

    @Volatile private var accumulatedMs = 0L
    @Volatile private var resumeTimeMs = 0L

    private var videoCodec: MediaCodec? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var videoTrackIndex = -1

    private var audioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    @Volatile private var audioTrackIndex = -1

    private var muxer: MediaMuxer? = null
    @Volatile private var muxerStarted = false
    private val muxerLock = Any()

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var appContext: Context? = null

    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private val isCapturing = AtomicBoolean(false)

    private var captureBitmap: Bitmap? = null

    @Volatile private var recordingStartNs = 0L
    @Volatile private var muxerStartedNs = 0L
    @Volatile private var captureStartNs = 0L
    @Volatile private var audioStartOffsetUs = 0L
    @Volatile private var totalPausedUs = 0L
    @Volatile private var pauseStartMs = 0L
    @Volatile private var lastVideoPtsUs = 0L

    @Volatile private var pendingUri: android.net.Uri? = null
    @Volatile private var pendingFile: File? = null

    fun start(context: Context, projection: MediaProjection) {
        if (_state.value != RecordingState.IDLE) return

        val view: View? = GameSurfaceRegistry.getView()
        val w: Int = if (view != null) (view.width.coerceAtLeast(2) / 2) * 2 else 720
        val h: Int = if (view != null) (view.height.coerceAtLeast(2) / 2) * 2 else 1280
        val safeW = normalizeVideoDimension(w)
        val safeH = normalizeVideoDimension(h)

        Log.i(TAG, "Starting recorder: requested=${w}x${h}, safe=${safeW}x${safeH}, device=${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")

        try {
            val (uri, file) = createOutputEntry(context)
            pendingUri = uri
            pendingFile = file

            mediaProjection = projection
            appContext = context.applicationContext

            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
            recordingStartNs = 0L
            muxerStartedNs = 0L
            captureStartNs = 0L
            audioStartOffsetUs = 0L
            totalPausedUs = 0L
            lastVideoPtsUs = 0L

            val fd = context.contentResolver.openFileDescriptor(uri, "w")!!.fileDescriptor
            muxer = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, safeW, safeH).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            Log.i(TAG, "Configuring video codec ${safeW}x${safeH} @ ${VIDEO_BIT_RATE}bps")
            videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { c ->
                c.configure(videoFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = c.createInputSurface()
                c.start()
                Log.i(TAG, "Video encoder started, surface=${inputSurface != null}")
            }

            val audioFmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, audioReadChunkSize())
            }

            audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { c ->
                c.configure(audioFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                c.start()
                Log.i(TAG, "Audio encoder started")
            }

            audioRecord = buildAudioRecord(projection)
            micAudioRecord = buildMicAudioRecord()
            _micEnabled.value = false

            captureThread = HandlerThread("GameRecorder-Capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)

            accumulatedMs = 0L
            resumeTimeMs = System.currentTimeMillis()
            _elapsedMs.value = 0L
            _state.value = RecordingState.RECORDING
            startTimerTick()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording: ${e.message}", e)
            cleanup()
            return
        }

        encodeScope.launch {
            try {
                primeVideoTrack()
                primeAudioTrack()

                if (_state.value != RecordingState.RECORDING) {
                    Log.w(TAG, "Recorder stopped before muxer start")
                    cleanup()
                    return@launch
                }

                recordingStartNs = System.nanoTime()
                audioRecord?.startRecording()
                audioStartOffsetUs = (System.nanoTime() - recordingStartNs) / 1_000L

                if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
                    synchronized(muxerLock) {
                        if (muxer != null && !muxerStarted) {
                            muxer!!.start()
                            muxerStarted = true
                            muxerStartedNs = System.nanoTime()
                            Log.i(TAG, "MediaMuxer started (video=$videoTrackIndex, audio=$audioTrackIndex)")
                        }
                    }
                } else {
                    Log.w(TAG, "Priming incomplete (video=$videoTrackIndex audio=$audioTrackIndex); muxer will start when format is ready")
                }

                discardVideoOutput(videoCodec!!)
                startVideoEncodeJob()
                startAudioJob()

                captureStartNs = System.nanoTime()

                isCapturing.set(true)
                scheduleNextFrame()
                virtualDisplay = projection.createVirtualDisplay(
                    "ZalithRecorder",
                    safeW,
                    safeH,
                    context.resources.displayMetrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    inputSurface,
                    null,
                    null
                )
                Log.i(TAG, "VirtualDisplay created for ${safeW}x${safeH}; recorder capture is active")

                playRecordingStartSound()
                Log.i(TAG, "Recording started ${safeW}x${safeH}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed during codec priming or capture setup: ${e.message}", e)
                cleanup()
            }
        }
    }

    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        try {
            isCapturing.set(false)
            pauseStartMs = System.currentTimeMillis()
            accumulatedMs += System.currentTimeMillis() - resumeTimeMs
            timerJob?.cancel(); timerJob = null
            runCatching {
                if (_micEnabled.value && micAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    micAudioRecord?.stop()
                }
            }
            _state.value = RecordingState.PAUSED
            Log.i(TAG, "Recording paused at ${accumulatedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause: ${e.message}", e)
        }
    }

    fun resume() {
        if (_state.value != RecordingState.PAUSED) return
        try {
            totalPausedUs += (System.currentTimeMillis() - pauseStartMs) * 1_000L
            runCatching {
                if (_micEnabled.value && micAudioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
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
            Log.e(TAG, "Failed to resume: ${e.message}", e)
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
            if (mar.recordingState != AudioRecord.RECORDSTATE_RECORDING && state == RecordingState.RECORDING) {
                mar.startRecording()
            }
            _micEnabled.value = true
            Log.i(TAG, "Microphone recording enabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable microphone: ${e.message}", e)
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
            Log.e(TAG, "Failed to disable microphone: ${e.message}", e)
        }
    }

    fun stopAndSave(context: Context) {
        val current = _state.value
        if (current == RecordingState.IDLE || current == RecordingState.STOPPING) return
        _state.value = RecordingState.STOPPING
        isCapturing.set(false)
        timerJob?.cancel(); timerJob = null
        captureHandler?.post { finalise(context) } ?: run { finalise(context) }
    }

    private fun scheduleNextFrame() {
        // Capture is driven by VirtualDisplay -> encoder input surface. This avoids manual frame
        // copying, which is prone to invalid GPU buffers and progressive white frames on low-end devices.
        Log.d(TAG, "scheduleNextFrame() - virtual display capture active")
    }

    @Suppress("DEPRECATION")
    private fun primeVideoTrack() {
        val codec = videoCodec ?: return
        val surface = inputSurface ?: return
        val info = MediaCodec.BufferInfo()

        runCatching {
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) surface.lockHardwareCanvas() else surface.lockCanvas(null)
            } catch (_: Exception) {
                surface.lockCanvas(null)
            }
            canvas?.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
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
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (idx >= 0) codec.releaseOutputBuffer(idx, false)
            }
        }
        Log.w(TAG, "Video codec did not emit FORMAT_CHANGED during priming")
    }

    private fun primeAudioTrack() {
        val ac = audioCodec ?: return
        val info = MediaCodec.BufferInfo()
        val chunkSize = audioReadChunkSize()
        val inputIdx = ac.dequeueInputBuffer(200_000L)
        if (inputIdx >= 0) {
            ac.getInputBuffer(inputIdx)?.apply {
                clear()
                put(ByteArray(chunkSize))
            }
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
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (idx >= 0) ac.releaseOutputBuffer(idx, false)
            }
        }
        Log.w(TAG, "Audio codec did not emit FORMAT_CHANGED during priming")
    }

    private fun discardVideoOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, 0L)
            when {
                idx >= 0 -> codec.releaseOutputBuffer(idx, false)
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
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
            val codec = videoCodec ?: return@launch
            while (isActive && _state.value != RecordingState.IDLE && _state.value != RecordingState.STOPPING) {
                val idx = codec.dequeueOutputBuffer(bufInfo, 10_000L)
                when {
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        synchronized(muxerLock) {
                            if (videoTrackIndex < 0) {
                                videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                            }
                            tryStartMuxerLocked()
                        }
                    }
                    idx >= 0 -> {
                        val isConfig = (bufInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isEos = (bufInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (!isConfig && bufInfo.size > 0) {
                            val adjusted = adjustVideoTimestampUs(bufInfo.presentationTimeUs)
                            if (adjusted >= 0L) {
                                val outputBuffer = codec.getOutputBuffer(idx)
                                if (outputBuffer != null) {
                                    bufInfo.presentationTimeUs = adjusted
                                    synchronized(muxerLock) {
                                        if (muxerStarted && videoTrackIndex >= 0) {
                                            muxer!!.writeSampleData(videoTrackIndex, outputBuffer, bufInfo)
                                        }
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        if (isEos) break
                    }
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> Log.w(TAG, "Unexpected video codec status $idx")
                }
            }
        }
    }

    private fun startAudioJob() {
        audioJob = encodeScope.launch {
            val ar = audioRecord ?: return@launch
            val ac = audioCodec ?: return@launch
            val chunkSize = audioReadChunkSize()
            val pcmBuf = ByteArray(chunkSize)
            val micBuf = ByteArray(chunkSize)
            var totalFrames = 0L

            try {
                while (isActive && _state.value != RecordingState.STOPPING && _state.value != RecordingState.IDLE) {
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
                                amplifyAndMixPcm16Le(
                                    pcmBuf,
                                    micBuf,
                                    minOf(read, micRead),
                                    gameplayGain = 0.8f,
                                    micGain = 1.5f
                                )
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
                        ac.getInputBuffer(inputIdx)?.apply {
                            clear()
                            put(pcmBuf, 0, read)
                        }
                        ac.queueInputBuffer(inputIdx, 0, read, pts.coerceAtLeast(0L), 0)
                    } else {
                        Log.w(TAG, "Audio encoder input buffer unavailable — batch dropped ($framesInBatch frames)")
                    }

                    drainAudioCodec(ac, endOfStream = false)
                }
            } finally {
                val eosIdx = ac.dequeueInputBuffer(5_000L)
                if (eosIdx >= 0) {
                    ac.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainAudioCodec(ac, endOfStream = true)
                runCatching { ar.stop() }
            }
        }
    }

    private fun amplifyAndMixPcm16Le(
        dst: ByteArray,
        src: ByteArray,
        len: Int,
        gameplayGain: Float = 1.0f,
        micGain: Float = 1.0f
    ) {
        var i = 0
        while (i + 1 < len) {
            val gameplayRaw = ((dst[i].toInt() and 0xFF) or ((dst[i + 1].toInt() and 0xFF) shl 8))
            val micRaw = ((src[i].toInt() and 0xFF) or ((src[i + 1].toInt() and 0xFF) shl 8))

            val gameplaySample = (gameplayRaw.toShort().toInt().toFloat() * gameplayGain)
            val micSample = (micRaw.toShort().toInt().toFloat() * micGain)
            val mixed = (gameplaySample + micSample).coerceIn(-32768f, 32767f)
            val out = mixed.toInt()

            dst[i] = (out and 0xFF).toByte()
            dst[i + 1] = ((out ushr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun drainAudioCodec(ac: MediaCodec, endOfStream: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val idx = ac.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer!!.addTrack(ac.outputFormat)
                        }
                        tryStartMuxerLocked()
                    }
                }
                in 0..Int.MAX_VALUE -> {
                    if (info.size > 0) {
                        val outputBuffer = ac.getOutputBuffer(idx)
                        if (outputBuffer != null) {
                            synchronized(muxerLock) {
                                if (muxerStarted && audioTrackIndex >= 0) {
                                    muxer!!.writeSampleData(audioTrackIndex, outputBuffer, info)
                                }
                            }
                        }
                    }
                    ac.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    private fun adjustVideoTimestampUs(presentationTimeUs: Long): Long {
        if (presentationTimeUs < 0L) return -1L
        val safe = presentationTimeUs.coerceAtLeast(0L)
        return if (safe < lastVideoPtsUs) {
            Log.w(TAG, "Dropped non-monotonic video timestamp: $safe < $lastVideoPtsUs")
            -1L
        } else {
            lastVideoPtsUs = safe
            safe
        }
    }

    private fun tryStartMuxerLocked() {
        if (muxerStarted || muxer == null) return
        if (videoTrackIndex >= 0 && audioTrackIndex >= 0) {
            muxer!!.start()
            muxerStarted = true
            muxerStartedNs = System.nanoTime()
            Log.i(TAG, "MediaMuxer started after track registration")
        }
    }

    private fun startTimerTick() {
        timerJob?.cancel()
        timerJob = timerScope.launch {
            while (isActive && _state.value != RecordingState.IDLE && _state.value != RecordingState.STOPPING) {
                if (_state.value == RecordingState.RECORDING) {
                    val value = System.currentTimeMillis() - resumeTimeMs + accumulatedMs
                    _elapsedMs.value = value
                }
                delay(50L)
            }
        }
    }

    private fun playRecordingStartSound() {
        try {
            // Kept intentionally lightweight: do not do anything heavy on low-end devices.
            Log.d(TAG, "Recording start sound skipped on low-power device")
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun finalise(context: Context) {
        try {
            Log.i(TAG, "Finalising recording")
            if (videoCodec != null) {
                val eosIdx = videoCodec!!.dequeueInputBuffer(5_000L)
                if (eosIdx >= 0) {
                    videoCodec!!.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                videoCodec!!.signalEndOfInputStream()
                drainVideoOutput(videoCodec!!)
            }

            if (audioCodec != null) {
                val eosIdx = audioCodec!!.dequeueInputBuffer(5_000L)
                if (eosIdx >= 0) {
                    audioCodec!!.queueInputBuffer(eosIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                drainAudioCodec(audioCodec!!, true)
            }

            synchronized(muxerLock) {
                if (muxerStarted) {
                    try {
                        muxer!!.stop()
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "Muxer stop failed, already in invalid state: ${e.message}")
                    }
                    muxerStarted = false
                    Log.i(TAG, "MediaMuxer stopped")
                }
                muxer?.release()
                muxer = null
            }

            virtualDisplay?.release()
            virtualDisplay = null
            mediaProjection?.stop()
            mediaProjection = null

            inputSurface?.release()
            inputSurface = null
            if (audioRecord != null) {
                try {
                    if (audioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord!!.stop()
                } catch (_: Exception) {
                }
                audioRecord!!.release()
                audioRecord = null
            }
            if (micAudioRecord != null) {
                try {
                    if (micAudioRecord!!.recordingState == AudioRecord.RECORDSTATE_RECORDING) micAudioRecord!!.stop()
                } catch (_: Exception) {
                }
                micAudioRecord!!.release()
                micAudioRecord = null
            }
            videoCodec?.stop()
            videoCodec?.release()
            videoCodec = null
            audioCodec?.stop()
            audioCodec?.release()
            audioCodec = null

            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null

            _state.value = RecordingState.IDLE
            _elapsedMs.value = accumulatedMs

            pendingFile?.let { file ->
                Log.i(TAG, "Recording saved to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalise recording: ${e.message}", e)
        } finally {
            cleanup()
        }
    }

    private fun drainVideoOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val idx = codec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(muxerLock) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer!!.addTrack(codec.outputFormat)
                        }
                        tryStartMuxerLocked()
                    }
                }
                in 0..Int.MAX_VALUE -> {
                    if (info.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(idx)
                        if (outputBuffer != null) {
                            synchronized(muxerLock) {
                                if (muxerStarted && videoTrackIndex >= 0) {
                                    muxer?.writeSampleData(videoTrackIndex, outputBuffer, info)
                                }
                            }
                        }
                    }
                    codec.releaseOutputBuffer(idx, false)
                }
                else -> return
            }
        }
    }

    private fun cleanup() {
        try {
            if (_state.value != RecordingState.IDLE) _state.value = RecordingState.IDLE
            if (videoEncodeJob?.isActive == true) videoEncodeJob?.cancel()
            if (audioJob?.isActive == true) audioJob?.cancel()
            timerJob?.cancel(); timerJob = null

            captureHandler?.removeCallbacksAndMessages(null)
            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null

            try { virtualDisplay?.release() } catch (_: Exception) {}
            virtualDisplay = null

            try { mediaProjection?.stop() } catch (_: Exception) {}
            mediaProjection = null

            try { inputSurface?.release() } catch (_: Exception) {}
            inputSurface = null

            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false

            try { audioRecord?.stop() } catch (_: Exception) {}
            try { audioRecord?.release() } catch (_: Exception) {}
            audioRecord = null

            try { micAudioRecord?.stop() } catch (_: Exception) {}
            try { micAudioRecord?.release() } catch (_: Exception) {}
            micAudioRecord = null

            try { videoCodec?.stop() } catch (_: Exception) {}
            try { videoCodec?.release() } catch (_: Exception) {}
            videoCodec = null

            try { audioCodec?.stop() } catch (_: Exception) {}
            try { audioCodec?.release() } catch (_: Exception) {}
            audioCodec = null

            captureBitmap?.recycle()
            captureBitmap = null
            Log.i(TAG, "Recorder resources cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup failed: ${e.message}", e)
        }
    }

    private fun createOutputEntry(context: Context): Pair<android.net.Uri, File> {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "zalith_recording_$stamp.mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/Zalith")
            }
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create output file entry")
        val file = File(context.getExternalFilesDir(null), fileName)
        return uri to file
    }

    private fun buildAudioRecord(projection: MediaProjection): AudioRecord? {
        return try {
            val sampleRate = AUDIO_SAMPLE_RATE
            val channelConfig = AudioFormat.CHANNEL_IN_STEREO
            val format = AudioFormat.ENCODING_PCM_16BIT
            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, format)
            val bufferSize = minBuf.coerceAtLeast(4096)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                format,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to microphone-only audio capture: ${e.message}")
            null
        }
    }

    private fun buildMicAudioRecord(): AudioRecord? {
        return try {
            val bufferSize = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(4096)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            Log.w(TAG, "Microphone capture unavailable: ${e.message}")
            null
        }
    }

    private fun audioReadChunkSize(): Int {
        val min = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return min.coerceAtLeast(2048) * 2
    }

    private fun normalizeVideoDimension(value: Int): Int {
        val clamped = value.coerceAtLeast(480)
        return when {
            clamped <= 720 -> 720
            clamped <= 960 -> 960
            clamped <= 1080 -> 1080
            else -> 1280
        }
    }
}
