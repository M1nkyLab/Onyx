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
    onSurfaceCreated: (android.view.Surface) -> Unit
) {
    var isRecording by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // AndroidView for the Window Surface (Preview)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                TextureView(context).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                            onSurfaceCreated(android.view.Surface(surface))
                        }
                        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
                            return true
                        }
                        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
                    }
                }
            }
        )

        // Rule-of-Thirds Grid Indicator
        RuleOfThirdsGrid()

        // 9:16 Aspect Ratio Boundary Overlay (Portrait)
        NineSixteenBoundary()

        // 16:9 Aspect Ratio Boundary Overlay (Landscape)
        SixteenNineBoundary()

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

@Composable
fun RuleOfThirdsGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)

        // Vertical lines
        drawLine(color, Offset(width / 3, 0f), Offset(width / 3, height), strokeWidth)
        drawLine(color, Offset(width * 2 / 3, 0f), Offset(width * 2 / 3, height), strokeWidth)

        // Horizontal lines
        drawLine(color, Offset(0f, height / 3), Offset(width, height / 3), strokeWidth)
        drawLine(color, Offset(0f, height * 2 / 3), Offset(width, height * 2 / 3), strokeWidth)
    }
}

@Composable
fun NineSixteenBoundary() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Calculate 9:16 bounds inside current screen
        val targetRatio = 9f / 16f
        val currentRatio = width / height
        
        val boundaryWidth = if (currentRatio > targetRatio) height * targetRatio else width
        val boundaryHeight = if (currentRatio > targetRatio) height else width / targetRatio
        
        val startX = (width - boundaryWidth) / 2
        val startY = (height - boundaryHeight) / 2
        
        val color = Color.Yellow.copy(alpha = 0.5f)
        val strokeWidth = 2.dp.toPx()

        // Draw boundary rectangle
        drawRect(
            color = color,
            topLeft = Offset(startX, startY),
            size = androidx.compose.ui.geometry.Size(boundaryWidth, boundaryHeight),
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
fun SixteenNineBoundary() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Calculate 16:9 bounds inside current screen
        val targetRatio = 16f / 9f
        val currentRatio = width / height
        
        val boundaryWidth = if (currentRatio > targetRatio) height * targetRatio else width
        val boundaryHeight = if (currentRatio > targetRatio) height else width / targetRatio
        
        val startX = (width - boundaryWidth) / 2
        val startY = (height - boundaryHeight) / 2
        
        // Use a different color (e.g., Cyan) for landscape to distinguish from the yellow portrait boundary
        val color = Color.Cyan.copy(alpha = 0.5f)
        val strokeWidth = 2.dp.toPx()

        // Draw boundary rectangle
        drawRect(
            color = color,
            topLeft = Offset(startX, startY),
            size = androidx.compose.ui.geometry.Size(boundaryWidth, boundaryHeight),
            style = Stroke(width = strokeWidth)
        )
    }
}
