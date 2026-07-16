package com.example.cameraapp

import android.os.Environment
import android.view.Surface
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val renderer: DualVideoRenderer,
    private val encoderEngine: MediaEncoderEngine
) : ViewModel() {

    fun onPreviewSurfaceCreated(surface: Surface) {
        // Feed the Compose AndroidView window surface to the hardware renderer
        renderer.setPreviewSurface(surface)
    }

    fun toggleRecording(isRecording: Boolean) {
        if (isRecording) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    private fun startRecording() {
        // Save the files directly into the Movies directory
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val timestamp = System.currentTimeMillis()
        val out16x9 = File(moviesDir, "DualCam_16x9_$timestamp.mp4").absolutePath
        val out9x16 = File(moviesDir, "DualCam_9x16_$timestamp.mp4").absolutePath

        // 1. Prepare codecs and dual muxers
        encoderEngine.prepare(out16x9, out9x16)

        // 2. Map MediaCodec input surfaces to our DualVideoRenderer target destinations
        encoderEngine.inputSurface16x9?.let { renderer.setEncoder16x9Surface(it) }
        encoderEngine.inputSurface9x16?.let { renderer.setEncoder9x16Surface(it) }

        // 3. Initiate the capture loop
        encoderEngine.startRecording()
    }

    private fun stopRecording() {
        encoderEngine.stopRecording()
    }
}
