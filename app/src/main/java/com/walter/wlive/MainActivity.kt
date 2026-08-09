package com.walter.wlive

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var stats: TextView
    private var mode = StreamMode.SAVING

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startCamera() else stats.text = "Se necesita permiso de cámara" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(previewView)

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 24, 32, 40)
            setBackgroundColor(0x99000000.toInt())
        }
        stats = TextView(this).apply {
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            text = statusText()
            gravity = Gravity.CENTER
        }
        panel.addView(stats)

        val modes = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(
            "Calidad" to StreamMode.QUALITY,
            "Ahorro" to StreamMode.SAVING,
            "Ultra" to StreamMode.ULTRA
        ).forEach { (label, streamMode) ->
            modes.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    mode = streamMode
                    stats.text = statusText()
                }
            })
        }
        panel.addView(modes)
        panel.addView(Button(this).apply {
            text = "INICIAR PRUEBA"
            setOnClickListener {
                stats.text = statusText() + "\n\nPróximo paso: encoder H.264 + contador real de bytes"
            }
        })

        root.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ))
        setContentView(root)
    }

    private fun statusText(): String = when (mode) {
        StreamMode.QUALITY -> "WLive v0.1.0 • CALIDAD\nObjetivo: 1080p · 30 FPS"
        StreamMode.SAVING -> "WLive v0.1.0 • AHORRO\nObjetivo: 720p · bitrate adaptativo"
        StreamMode.ULTRA -> "WLive v0.1.0 • ULTRA AHORRO\nObjetivo: 540p · bitrate mínimo estable"
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }
}

enum class StreamMode { QUALITY, SAVING, ULTRA }
