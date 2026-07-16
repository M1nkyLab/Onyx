package com.example.cameraapp

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import javax.inject.Inject
import javax.inject.Singleton
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * High-Performance EGL Rendering Loop.
 * Handles zero-copy rendering to prevent latency and dropped frames.
 * Utilizes hardware textures (GL_TEXTURE_EXTERNAL_OES).
 * Orchestrates 3 EGL surfaces: Window (Preview), Encoder 1 (16:9), Encoder 2 (9:16 crop).
 */
@Singleton
class DualVideoRenderer @Inject constructor() : SurfaceTexture.OnFrameAvailableListener {

    private val TAG = "DualVideoRenderer"

    private var renderThread: HandlerThread? = null
    private var renderHandler: Handler? = null

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    // EGL Surfaces
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var pendingPreviewSurface: Surface? = null
    private var encoder16x9Surface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoder9x16Surface: EGLSurface = EGL14.EGL_NO_SURFACE

    // Texture & SurfaceTexture
    private var externalTextureId = -1
    private var cameraSurfaceTexture: SurfaceTexture? = null
    
    private val stMatrix = FloatArray(16)
    
    // Callbacks
    var onSurfaceTextureCreated: ((SurfaceTexture) -> Unit)? = null

    // Simple passthrough vertex/fragment shaders for OES texture rendering
    private val VERTEX_SHADER = """
        uniform mat4 uSTMatrix;
        uniform mat4 uMVPMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTextureCoord;
        varying vec2 vTextureCoord;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vTextureCoord = (uSTMatrix * aTextureCoord).xy;
        }
    """.trimIndent()

    private val FRAGMENT_SHADER = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTextureCoord;
        uniform samplerExternalOES sTexture;
        void main() {
            gl_FragColor = texture2D(sTexture, vTextureCoord);
        }
    """.trimIndent()

    private var programId = -1

    private val vBuffer by lazy {
        val rectCoords = floatArrayOf(-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f)
        ByteBuffer.allocateDirect(rectCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(rectCoords); position(0) }
    }
    
    private val tBuffer by lazy {
        val texCoords = floatArrayOf(0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)
        ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(texCoords); position(0) }
    }

    fun start() {
        renderThread = HandlerThread("GLRenderThread").apply { start() }
        renderHandler = Handler(renderThread!!.looper)
        
        renderHandler?.post {
            initEGL()
            initGL()
        }
    }

    fun stop() {
        renderHandler?.post {
            releaseGL()
            releaseEGL()
        }
        renderThread?.quitSafely()
        renderThread = null
        renderHandler = null
    }

    // Call this from UI to set preview surface
    fun setPreviewSurface(surface: Surface) {
        pendingPreviewSurface = surface
        renderHandler?.post { attachPreviewSurface() }
    }

    private fun attachPreviewSurface() {
        pendingPreviewSurface?.let { surface ->
            if (windowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, windowSurface)
            }
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            windowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        }
    }

    // Call this from MediaEncoderEngine when codec input surface is ready
    fun setEncoder16x9Surface(surface: Surface) {
        renderHandler?.post {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            encoder16x9Surface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        }
    }

    fun setEncoder9x16Surface(surface: Surface) {
        renderHandler?.post {
            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            encoder9x16Surface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)
        }
    }

    private fun initEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1, // Crucial for MediaCodec
            EGL14.EGL_NONE
        )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
    }

    private fun initGL() {
        // Needs a dummy surface to make context current before creating textures
        val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val dummySurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, dummySurface, dummySurface, eglContext)

        // Generate OES texture
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        externalTextureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)

        // Setup texture parameters
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR.toFloat())
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        programId = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)

        cameraSurfaceTexture = SurfaceTexture(externalTextureId).apply {
            setOnFrameAvailableListener(this@DualVideoRenderer, renderHandler)
        }
        
        onSurfaceTextureCreated?.invoke(cameraSurfaceTexture!!)
        pendingPreviewSurface?.let { attachPreviewSurface() }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        cameraSurfaceTexture?.updateTexImage()
        cameraSurfaceTexture?.getTransformMatrix(stMatrix)
        
        val timestamp = cameraSurfaceTexture?.timestamp ?: 0L

        // Render to Preview (Window)
        if (windowSurface != EGL14.EGL_NO_SURFACE) {
            renderToSurface(windowSurface, timestamp, isCropped = false)
        }

        // Render to 16:9 Encoder
        if (encoder16x9Surface != EGL14.EGL_NO_SURFACE) {
            renderToSurface(encoder16x9Surface, timestamp, isCropped = false)
        }

        // Render to 9:16 Encoder (Cropped)
        if (encoder9x16Surface != EGL14.EGL_NO_SURFACE) {
            renderToSurface(encoder9x16Surface, timestamp, isCropped = true)
        }
    }

    private fun renderToSurface(surface: EGLSurface, timestamp: Long, isCropped: Boolean) {
        EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)
        
        // Query surface dimensions and set OpenGL viewport (Critical for fixing 1x1 dummy surface bug)
        val widthArray = IntArray(1)
        val heightArray = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_WIDTH, widthArray, 0)
        EGL14.eglQuerySurface(eglDisplay, surface, EGL14.EGL_HEIGHT, heightArray, 0)
        GLES20.glViewport(0, 0, widthArray[0], heightArray[0])

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(programId)

        val mvpMatrix = FloatArray(16)
        Matrix.setIdentityM(mvpMatrix, 0)
        
        if (isCropped) {
            // Apply Orthogonal texture projection to crop 16:9 to 9:16 center-aligned
            val scaleX = (9f / 16f) / (16f / 9f) 
            Matrix.scaleM(mvpMatrix, 0, scaleX, 1.0f, 1.0f)
        }

        // Load uniforms & attributes
        val positionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
        val texCoordHandle = GLES20.glGetAttribLocation(programId, "aTextureCoord")
        val mvpMatrixHandle = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val stMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
        val sTextureHandle = GLES20.glGetUniformLocation(programId, "sTexture")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTextureId)
        GLES20.glUniform1i(sTextureHandle, 0)

        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(stMatrixHandle, 1, false, stMatrix, 0)

        drawQuad(positionHandle, texCoordHandle)

        // Sync timestamp strictly required for zero-copy encode timing
        EGLExt.eglPresentationTimeANDROID(eglDisplay, surface, timestamp)
        EGL14.eglSwapBuffers(eglDisplay, surface)
    }

    private fun drawQuad(positionHandle: Int, texCoordHandle: Int) {
        vBuffer.position(0)
        tBuffer.position(0)

        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vBuffer)
        GLES20.glEnableVertexAttribArray(positionHandle)

        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, tBuffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, pixelShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun loadShader(shaderType: Int, source: String): Int {
        val shader = GLES20.glCreateShader(shaderType)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    private fun releaseGL() {
        if (programId != -1) {
            GLES20.glDeleteProgram(programId)
            programId = -1
        }
    }

    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, windowSurface)
            if (encoder16x9Surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoder16x9Surface)
            if (encoder9x16Surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, encoder9x16Surface)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        windowSurface = EGL14.EGL_NO_SURFACE
        encoder16x9Surface = EGL14.EGL_NO_SURFACE
        encoder9x16Surface = EGL14.EGL_NO_SURFACE
    }
}
