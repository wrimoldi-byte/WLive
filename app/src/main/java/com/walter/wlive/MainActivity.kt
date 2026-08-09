package com.walter.wlive

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var stats: TextView
    private lateinit var testButton: Button
    private lateinit var viewButton: Button
    private lateinit var saveButton: Button

    private var mode = StreamMode.SAVING
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentFile: File? = null
    private var lastFile: File? = null
    private var startedAtMs = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val statsTicker = object : Runnable {
        override fun run() {
            if (recording != null) {
                updateLiveStats()
                handler.postDelayed(this, 500)
            }
        }
    }

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else stats.text = "Se necesita permiso de cámara"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTicker)
        recording?.stop()
        recording = null
        super.onDestroy()
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
            setBackgroundColor(0xAA000000.toInt())
        }

        stats = TextView(this).apply {
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            text = idleStatusText()
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
                    if (recording != null) return@setOnClickListener
                    mode = streamMode
                    configureCamera()
                    stats.text = idleStatusText()
                }
            })
        }
        panel.addView(modes)

        testButton = Button(this).apply {
            text = "INICIAR PRUEBA"
            setOnClickListener {
                if (recording == null) startTest() else stopTest()
            }
        }
        panel.addView(testButton)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        viewButton = Button(this).apply {
            text = "VER ÚLTIMA"
            isEnabled = false
            setOnClickListener { showLastVideo() }
        }
        saveButton = Button(this).apply {
            text = "GUARDAR VIDEO"
            isEnabled = false
            setOnClickListener { saveLastVideo() }
        }
        actions.addView(viewButton)
        actions.addView(saveButton)
        panel.addView(actions)

        root.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        )
        setContentView(root)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            previewView.post { configureCamera() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun configureCamera() {
        val provider = cameraProvider ?: return
        if (recording != null) return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(mode.quality))
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        try {
            provider.unbindAll()
            val viewport = previewView.viewPort
            if (viewport != null) {
                val group = UseCaseGroup.Builder()
                    .setViewPort(viewport)
                    .addUseCase(preview)
                    .addUseCase(videoCapture!!)
                    .build()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, group)
            } else {
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    videoCapture
                )
            }
        } catch (e: Exception) {
            stats.text = "No se pudo configurar la cámara: ${e.message ?: "error"}"
        }
    }

    private fun startTest() {
        val capture = videoCapture ?: run {
            stats.text = "La cámara todavía no está lista"
            return
        }

        val file = File(cacheDir, "wlive-${mode.name.lowercase()}-${System.currentTimeMillis()}.mp4")
        currentFile = file
        startedAtMs = System.currentTimeMillis()
        viewButton.isEnabled = false
        saveButton.isEnabled = false

        val output = FileOutputOptions.Builder(file).build()
        recording = capture.output
            .prepareRecording(this, output)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        testButton.text = "DETENER PRUEBA"
                        handler.removeCallbacks(statsTicker)
                        handler.post(statsTicker)
                    }

                    is VideoRecordEvent.Finalize -> {
                        handler.removeCallbacks(statsTicker)
                        val finalBytes = file.length()
                        val elapsed = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)) / 1000.0
                        val avgKbps = (finalBytes * 8.0 / 1000.0) / elapsed
                        recording = null
                        lastFile = file.takeIf { it.exists() && it.length() > 0 }
                        testButton.text = "INICIAR PRUEBA"
                        viewButton.isEnabled = lastFile != null
                        saveButton.isEnabled = lastFile != null
                        stats.text = buildString {
                            append("WLive v0.3.0 • ${mode.label}\n")
                            append("Prueba finalizada\n")
                            append(String.format(Locale.US, "Archivo: %.2f MB\n", finalBytes / 1_048_576.0))
                            append(String.format(Locale.US, "Bitrate medio real: %.0f kbps\n", avgKbps))
                            append(String.format(Locale.US, "Duración: %.1f s\n", elapsed))
                            append("Podés VER o GUARDAR esta prueba")
                        }
                    }
                }
            }
    }

    private fun stopTest() {
        recording?.stop()
    }

    private fun showLastVideo() {
        val file = lastFile ?: return
        val videoView = VideoView(this).apply {
            setVideoPath(file.absolutePath)
            val controller = MediaController(this@MainActivity)
            setMediaController(controller)
            setOnPreparedListener { mp ->
                mp.isLooping = true
                start()
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Última prueba • ${mode.label}")
            .setView(videoView)
            .setPositiveButton("CERRAR", null)
            .setOnDismissListener { videoView.stopPlayback() }
            .show()
        videoView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.heightPixels * 0.65).toInt()
        )
    }

    private fun saveLastVideo() {
        val file = lastFile ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Guardar en Galería requiere Android 10 o superior en esta versión", Toast.LENGTH_LONG).show()
            return
        }

        val name = "WLive_${mode.name}_${System.currentTimeMillis()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WLive")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "No se pudo crear el video en Galería", Toast.LENGTH_LONG).show()
            return
        }

        try {
            contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "Guardado en Películas/WLive", Toast.LENGTH_LONG).show()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(packageManager) != null) {
                // Queda disponible en Galería; no lo abrimos automáticamente.
            }
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Error al guardar: ${e.message ?: "desconocido"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLiveStats() {
        val file = currentFile ?: return
        val bytes = file.length()
        val elapsed = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)) / 1000.0
        val avgKbps = (bytes * 8.0 / 1000.0) / elapsed
        val mb = bytes / 1_048_576.0

        stats.text = buildString {
            append("WLive v0.3.0 • ${mode.label}\n")
            append("${mode.target} • GRABANDO\n")
            append(String.format(Locale.US, "Datos generados: %.2f MB\n", mb))
            append(String.format(Locale.US, "Bitrate medio real: %.0f kbps\n", avgKbps))
            append(String.format(Locale.US, "Tiempo: %.1f s", elapsed))
        }
    }

    private fun idleStatusText(): String =
        "WLive v0.3.0 • ${mode.label}\n${mode.target}\nMismo encuadre en los tres modos"
}

enum class StreamMode(
    val label: String,
    val target: String,
    val quality: Quality
) {
    QUALITY("CALIDAD", "Objetivo: 1080p", Quality.FHD),
    SAVING("AHORRO", "Objetivo: 720p", Quality.HD),
    ULTRA("ULTRA AHORRO", "Objetivo: 480p", Quality.SD)
}
