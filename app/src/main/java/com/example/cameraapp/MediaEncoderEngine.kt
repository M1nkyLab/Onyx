package com.example.cameraapp

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media Encoder Engine for dual-format (16:9 and 9:16) 4K 60FPS video recording.
 * Initializes HEVC (H.265) video codecs and a unified AAC audio codec.
 * Routes raw inputs and dispatches identical encoded audio blocks to two distinct MediaMuxers.
 * Ensures zero-copy pipeline on the video side by exposing input surfaces.
 */
@Singleton
class MediaEncoderEngine @Inject constructor() {

    private val TAG = "MediaEncoderEngine"

    private var videoCodec16x9: MediaCodec? = null
    private var videoCodec9x16: MediaCodec? = null
    private var audioCodec: MediaCodec? = null

    private var muxer16x9: MediaMuxer? = null
    private var muxer9x16: MediaMuxer? = null

    private var videoTrackIndex16x9 = -1
    private var videoTrackIndex9x16 = -1
    private var audioTrackIndex16x9 = -1
    private var audioTrackIndex9x16 = -1

    private var muxer16x9Started = false
    private var muxer9x16Started = false

    var inputSurface16x9: Surface? = null
        private set
    var inputSurface9x16: Surface? = null
        private set

    private var audioRecord: AudioRecord? = null
    private var audioThread: HandlerThread? = null
    private var audioHandler: Handler? = null
    private var isRecording = false

    private val bufferInfo = MediaCodec.BufferInfo()

    private fun selectVideoMimeType(width: Int, height: Int): String {
        val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
        for (info in codecList.codecInfos) {
            if (info.isEncoder && info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)) {
                val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
                val videoCaps = caps.videoCapabilities
                if (videoCaps != null && videoCaps.isSizeSupported(width, height)) {
                    return MediaFormat.MIMETYPE_VIDEO_HEVC
                }
            }
        }
        return MediaFormat.MIMETYPE_VIDEO_AVC
    }

    fun prepare(outputPath16x9: String, outputPath9x16: String, sourceWidth: Int, sourceHeight: Int, targetFps: Int) {
        val mimeType = selectVideoMimeType(sourceWidth, sourceHeight)

        // Calculate dynamic bitrates based on resolution and fps (0.25 bits/pixel/frame)
        val bitrate16x9 = (sourceWidth * sourceHeight * targetFps * 0.25).toInt()

        // 16:9 format
        val format16x9 = MediaFormat.createVideoFormat(mimeType, sourceWidth, sourceHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate16x9)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        
        videoCodec16x9 = MediaCodec.createEncoderByType(mimeType)
        videoCodec16x9?.configure(format16x9, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface16x9 = videoCodec16x9?.createInputSurface()

        // 9:16 format (portrait mathematical crop)
        val isLandscape = sourceWidth > sourceHeight
        val landscapeWidth = if (isLandscape) sourceWidth else sourceHeight
        val landscapeHeight = if (isLandscape) sourceHeight else sourceWidth

        var portraitWidth = (landscapeHeight * 9) / 16
        portraitWidth = if (portraitWidth % 2 != 0) portraitWidth - 1 else portraitWidth
        var portraitHeight = landscapeHeight
        portraitHeight = if (portraitHeight % 2 != 0) portraitHeight - 1 else portraitHeight

        val bitrate9x16 = (portraitWidth * portraitHeight * targetFps * 0.25).toInt()

        val format9x16 = MediaFormat.createVideoFormat(mimeType, portraitWidth, portraitHeight).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate9x16)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        
        videoCodec9x16 = MediaCodec.createEncoderByType(mimeType)
        videoCodec9x16?.configure(format9x16, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface9x16 = videoCodec9x16?.createInputSurface()

        // Prepare Unified Audio Codec (AAC, 48kHz, 128kbps stereo)
        val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        audioCodec?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

        // Prepare Muxers
        muxer16x9 = MediaMuxer(outputPath16x9, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer9x16 = MediaMuxer(outputPath9x16, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun startRecording() {
        isRecording = true
        videoCodec16x9?.start()
        videoCodec9x16?.start()
        audioCodec?.start()

        // Setup AudioRecord
        val minBufferSize = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        try {
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT, minBufferSize * 2)
            audioRecord?.startRecording()
        } catch (e: SecurityException) {
            Log.e(TAG, "Audio recording permission denied")
        }

        audioThread = HandlerThread("AudioRecordThread").apply { start() }
        audioHandler = Handler(audioThread!!.looper)
        
        audioHandler?.post { audioLoop(minBufferSize) }
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioThread?.quitSafely()
        audioThread = null
        
        // Drain remaining buffers and stop codecs
        // (Simplified for brevity, production code requires proper EOS signaling and draining loop)
        videoCodec16x9?.stop()
        videoCodec16x9?.release()
        
        videoCodec9x16?.stop()
        videoCodec9x16?.release()
        
        audioCodec?.stop()
        audioCodec?.release()

        if (muxer16x9Started) {
            muxer16x9?.stop()
            muxer16x9?.release()
        }
        if (muxer9x16Started) {
            muxer9x16?.stop()
            muxer9x16?.release()
        }
        
        muxer16x9Started = false
        muxer9x16Started = false
    }

    /**
     * Polls AudioRecord, feeds MediaCodec, reads encoded audio, and writes identical blocks to both muxers.
     */
    private fun audioLoop(bufferSize: Int) {
        val audioBuffer = ByteArray(bufferSize)
        while (isRecording) {
            val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0
            if (bytesRead > 0) {
                // 1. Feed raw audio to codec
                val inputBufferIndex = audioCodec?.dequeueInputBuffer(-1) ?: -1
                if (inputBufferIndex >= 0) {
                    val inputBuffer = audioCodec?.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(audioBuffer, 0, bytesRead)
                    val pts = System.nanoTime() / 1000
                    audioCodec?.queueInputBuffer(inputBufferIndex, 0, bytesRead, pts, 0)
                }
            }

            // 2. Drain encoded audio and write to both muxers
            drainAudio()
            
            // 3. Drain video codecs to prevent freeze and save files
            drainVideo(videoCodec16x9, muxer16x9, true)
            drainVideo(videoCodec9x16, muxer9x16, false)
        }
    }

    private fun drainAudio() {
        var outputBufferIndex = audioCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
        while (outputBufferIndex >= 0) {
            val encodedData = audioCodec?.getOutputBuffer(outputBufferIndex)
            if (encodedData != null && bufferInfo.size > 0) {
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                // Write identical audio packets to both muxers to ensure precise sync
                if (muxer16x9Started && audioTrackIndex16x9 >= 0) {
                    muxer16x9?.writeSampleData(audioTrackIndex16x9, encodedData, bufferInfo)
                }
                if (muxer9x16Started && audioTrackIndex9x16 >= 0) {
                    // Reset position since previous write consumed the buffer position
                    encodedData.position(bufferInfo.offset) 
                    muxer9x16?.writeSampleData(audioTrackIndex9x16, encodedData, bufferInfo)
                }
            }
            audioCodec?.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = audioCodec?.dequeueOutputBuffer(bufferInfo, 0) ?: -1
        }
    }

    private fun drainVideo(codec: MediaCodec?, muxer: MediaMuxer?, is16x9: Boolean) {
        if (codec == null || muxer == null) return
        var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        
        if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            val newFormat = codec.outputFormat
            if (is16x9) {
                videoTrackIndex16x9 = muxer.addTrack(newFormat)
                muxer.start() 
                muxer16x9Started = true
            } else {
                videoTrackIndex9x16 = muxer.addTrack(newFormat)
                muxer.start()
                muxer9x16Started = true
            }
        }
        
        while (outputBufferIndex >= 0) {
            val encodedData = codec.getOutputBuffer(outputBufferIndex)
            if (encodedData != null && bufferInfo.size > 0) {
                encodedData.position(bufferInfo.offset)
                encodedData.limit(bufferInfo.offset + bufferInfo.size)

                if (is16x9 && muxer16x9Started && videoTrackIndex16x9 >= 0) {
                    muxer.writeSampleData(videoTrackIndex16x9, encodedData, bufferInfo)
                } else if (!is16x9 && muxer9x16Started && videoTrackIndex9x16 >= 0) {
                    muxer.writeSampleData(videoTrackIndex9x16, encodedData, bufferInfo)
                }
            }
            codec.releaseOutputBuffer(outputBufferIndex, false)
            outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }
}
