package com.example.cameraapp

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class CameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dualVideoRenderer: DualVideoRenderer
) {
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(context)
    
    suspend fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).await()
        
        // Unbind any previous use cases before rebinding
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build()
            
        // Provide our custom SurfaceProvider
        preview.setSurfaceProvider { request ->
            dualVideoRenderer.onSurfaceTextureCreated = { surfaceTexture ->
                // CameraX requires the SurfaceTexture to have a default buffer size matching the request resolution
                surfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(surfaceTexture)
                
                request.provideSurface(surface, mainExecutor) { result ->
                    // Cleanup when CameraX no longer needs this surface
                    surface.release()
                    dualVideoRenderer.stop()
                }
            }
            // Kick off the OpenGL EGL rendering thread
            dualVideoRenderer.start()
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview
            )
        } catch (exc: Exception) {
            exc.printStackTrace()
        }
    }
}

/**
 * Extension function to adapt ListenableFuture to a suspend Coroutine.
 */
suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
    addListener(
        {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        Runnable::run
    )
}
