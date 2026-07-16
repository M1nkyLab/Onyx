package com.example.cameraapp

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Size
import android.view.Surface
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val renderer: DualVideoRenderer,
    private val encoderEngine: MediaEncoderEngine
) : ViewModel() {

    private var cameraResolution: Size? = null
    
    private var lastSavedFile16x9: String? = null
    private var lastSavedFile9x16: String? = null

    fun onPreviewSurface16x9Created(surface: Surface) {
        renderer.setPreviewSurface16x9(surface)
    }

    fun onPreviewSurface9x16Created(surface: Surface) {
        renderer.setPreviewSurface9x16(surface)
    }

    fun onResolutionResolved(size: Size) {
        cameraResolution = size
    }

    fun toggleRecording(isRecording: Boolean) {
        if (isRecording) {
            startRecording()
        } else {
            stopRecording()
        }
    }

    private fun startRecording() {
        // Save the files directly into the Movies directory (often monitored by gallery/photos)
        val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val timestamp = System.currentTimeMillis()
        val out16x9 = File(moviesDir, "DualCam_16x9_$timestamp.mp4").absolutePath
        val out9x16 = File(moviesDir, "DualCam_9x16_$timestamp.mp4").absolutePath
        
        lastSavedFile16x9 = out16x9
        lastSavedFile9x16 = out9x16

        // Use the highest available camera resolution, falling back to 1080p if unresolved.
        var width = cameraResolution?.width ?: 1920
        var height = cameraResolution?.height ?: 1080
        
        // Cap at 1080p for dual encoding to prevent hardware crash
        val isLandscape = width >= height
        val longSide = if (isLandscape) width else height
        if (longSide > 1920) {
            width = if (isLandscape) 1920 else 1080
            height = if (isLandscape) 1080 else 1920
        }
        
        val fps = 30

        // 1. Prepare codecs and dual muxers
        encoderEngine.prepare(out16x9, out9x16, width, height, fps)

        // 2. Map MediaCodec input surfaces to our DualVideoRenderer target destinations
        encoderEngine.inputSurface16x9?.let { renderer.setEncoder16x9Surface(it) }
        encoderEngine.inputSurface9x16?.let { renderer.setEncoder9x16Surface(it) }

        // 3. Initiate the capture loop
        encoderEngine.startRecording()
    }

    private fun stopRecording() {
        encoderEngine.stopRecording()
        
        // Scan files so they immediately appear in the Photos app
        val filesToScan = listOfNotNull(lastSavedFile16x9, lastSavedFile9x16).toTypedArray()
        if (filesToScan.isNotEmpty()) {
            MediaScannerConnection.scanFile(context, filesToScan, arrayOf("video/mp4")) { path, uri ->
                // Media scanned
            }
        }
    }
}
