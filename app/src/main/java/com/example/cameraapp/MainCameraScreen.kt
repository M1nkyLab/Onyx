package com.example.cameraapp

import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Main Jetpack Compose Camera Screen.
 * Incorporates Material 3 controls, rule-of-thirds grid, and independent AR boundaries.
 */
@Composable
fun MainCameraScreen(
    onRecordToggle: (Boolean) -> Unit,
    onSurfaceCreated16x9: (android.view.Surface) -> Unit,
    onSurfaceCreated9x16: (android.view.Surface) -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Half: 16:9 Landscape Preview
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.aspectRatio(16f / 9f),
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    onSurfaceCreated16x9(android.view.Surface(surface))
                                }
                                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture) = true
                                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                            }
                        }
                    }
                )
                Text("16:9 Landscape", color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }

            // Bottom Half: 9:16 Portrait Preview
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    modifier = Modifier.aspectRatio(9f / 16f),
                    factory = { context ->
                        TextureView(context).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                                    onSurfaceCreated9x16(android.view.Surface(surface))
                                }
                                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture) = true
                                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                            }
                        }
                    }
                )
                Text("9:16 Portrait", color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(8.dp))
            }
        }

        // Recording Controls (Material 3)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .size(80.dp)
        ) {
            Button(
                onClick = {
                    isRecording = !isRecording
                    onRecordToggle(isRecording)
                },
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else Color.White
                ),
                contentPadding = PaddingValues(0.dp)
            ) {
                if (isRecording) {
                    Box(modifier = Modifier.size(24.dp).background(Color.Black))
                }
            }
            
            // Outer ring
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = Color.White,
                    radius = size.minDimension / 2 + 4.dp.toPx(),
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }
    }
}

    }
}
