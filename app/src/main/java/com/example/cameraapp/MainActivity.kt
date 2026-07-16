package com.example.cameraapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var cameraManager: CameraManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startCameraBinding()
        } else {
            Toast.makeText(this, "Camera and Audio permissions are required.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            MainCameraScreen(
                onRecordToggle = { isRecording ->
                    viewModel.toggleRecording(isRecording)
                },
                onSurfaceCreated = { surface ->
                    viewModel.onPreviewSurfaceCreated(surface)
                }
            )
        }

        checkPermissionsAndStart()
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        
        val permissionsArray = permissions.toTypedArray()
        val allGranted = permissionsArray.all { 
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED 
        }
        
        if (allGranted) {
            startCameraBinding()
        } else {
            requestPermissionLauncher.launch(permissionsArray)
        }
    }

    private fun startCameraBinding() {
        // Binds the CameraX preview to the activity lifecycle
        cameraManager.onResolutionResolved = { size ->
            viewModel.onResolutionResolved(size)
        }
        lifecycleScope.launch {
            cameraManager.startCamera(this@MainActivity)
        }
    }
}
