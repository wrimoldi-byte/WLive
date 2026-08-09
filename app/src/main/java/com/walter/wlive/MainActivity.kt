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
    private lateinit var resultsButton: Button

    private var mode = StreamMode.SAVING
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var currentFile: File? = null
    private var lastFile: File? = null
    private var lastMode: StreamMode? = null
    private var startedAtMs = 0L
    private val results = linkedMapOf<StreamMode, TestResult>()
    private val handler = Handler(Looper.getMainLooper())

    private val statsTicker = object : Runnable {
        override fun run() {
            if (recording != null) {
                updateLiveStats()
                handler.postDelayed(this, 500)
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
            startCamera()
            if (!hasAudioPermission()) {
                Toast.makeText(this, "Micrófono no autorizado: las pruebas quedarán sin sonido", Toast.LENGTH_LONG).show()
            }
        } else {
            stats.text = "Se necesita permiso de cámara"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestNeededPermissions()
    }

    override fun onDestroy() {
        handler.removeCallbacks(statsTicker)
        recording?.stop()
        recording = null
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (!hasAudioPermission()) needed += Manifest.permission.RECORD_AUDIO

        if (needed.isEmpty()) {
            startCamera()
        } else {
            permissionsLauncher.launch(needed.toTypedArray())
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

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
            setPadding(32, 20, 32, 32)
            setBackgroundColor(0xAA000000.toInt())
        }

        stats = TextView(this).apply {
            textSize = 16f
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

        resultsButton = Button(this).apply {
            text = "RESULTADOS (${results.size}/3)"
            isEnabled = false
            setOnClickListener { showResultsDialog() }
        }
        panel.addView(resultsButton)

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
        if (!hasCameraPermission()) return
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
            .setQualitySelector(QualitySelector.from(Quality.HD))
            .setTargetVideoEncodingBitRate(mode.videoBitrateBps)
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

        val testMode = mode
        val file = File(cacheDir, "wlive-${testMode.name.lowercase()}-${System.currentTimeMillis()}.mp4")
        currentFile = file
        startedAtMs = System.currentTimeMillis()
        viewButton.isEnabled = false
        saveButton.isEnabled = false

        val output = FileOutputOptions.Builder(file).build()
        var pendingRecording = capture.output.prepareRecording(this, output)
        val audioEnabled = hasAudioPermission()
        if (audioEnabled) {
            pendingRecording = pendingRecording.withAudioEnabled()
        }

        recording = pendingRecording
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
                        val result = TestResult(testMode, finalBytes, elapsed, avgKbps, audioEnabled)
                        results[testMode] = result

                        recording = null
                        lastFile = file.takeIf { it.exists() && it.length() > 0 }
                        lastMode = testMode
                        testButton.text = "INICIAR PRUEBA"
                        viewButton.isEnabled = lastFile != null
                        saveButton.isEnabled = lastFile != null
                        resultsButton.isEnabled = results.isNotEmpty()
                        resultsButton.text = "RESULTADOS (${results.size}/3)"
                        stats.text = finalResultText(result)

                        if (results.size == StreamMode.entries.size) {
                            showResultsDialog()
                        }
                    }
                }
            }
    }

    private fun stopTest() {
        recording?.stop()
    }

    private fun finalResultText(result: TestResult): String {
        val quality = results[StreamMode.QUALITY]
        val savings = if (quality != null && result.mode != StreamMode.QUALITY && quality.avgKbps > 0) {
            ((1.0 - result.avgKbps / quality.avgKbps) * 100.0)
        } else null

        return buildString {
            append("WLive v0.5.0 • ${result.mode.label}\n")
            append("720p • objetivo ${result.mode.targetKbps} kbps\n")
            append(String.format(Locale.US, "Archivo real: %.2f MB en %.1f s\n", result.mb, result.seconds))
            append(String.format(Locale.US, "Bitrate real: %.0f kbps\n", result.avgKbps))
            append(String.format(Locale.US, "Consumo normalizado: %.2f MB/min\n", result.mbPerMinute))
            append("Audio: ${if (result.audioEnabled) "SÍ" else "NO"}\n")
            if (savings != null) {
                append(String.format(Locale.US, "AHORRO vs Calidad: %.1f%%", savings.coerceIn(-999.0, 100.0)))
            } else if (result.mode == StreamMode.QUALITY) {
                append("Referencia guardada para comparar")
            } else {
                append("Hacé una prueba en Calidad para calcular el ahorro")
            }
        }
    }

    private fun showResultsDialog() {
        if (results.isEmpty()) return
        val quality = results[StreamMode.QUALITY]
        val text = buildString {
            append("COMPARACIÓN WLive v0.5.0\n")
            append("Todos los modos: 720p\n\n")
            StreamMode.entries.forEach { streamMode ->
                val result = results[streamMode]
                if (result == null) {
                    append("${streamMode.label}: pendiente\n\n")
                } else {
                    append("${streamMode.label}\n")
                    append(String.format(Locale.US, "  %.2f MB/min • %.0f kbps", result.mbPerMinute, result.avgKbps))
                    if (quality != null && streamMode != StreamMode.QUALITY && quality.avgKbps > 0) {
                        val savings = (1.0 - result.avgKbps / quality.avgKbps) * 100.0
                        append(String.format(Locale.US, " • ahorro %.1f%%", savings.coerceIn(-999.0, 100.0)))
                    }
                    append("\n\n")
                }
            }
            if (results.size == StreamMode.entries.size && quality != null) {
                val best = results.values.minByOrNull { it.avgKbps }
                if (best != null) {
                    val bestSavings = (1.0 - best.avgKbps / quality.avgKbps) * 100.0
                    append("EVALUACIÓN\n")
                    append("Menor consumo: ${best.mode.label}\n")
                    if (best.mode != StreamMode.QUALITY) {
                        append(String.format(Locale.US, "Reduce aprox. %.1f%% de datos frente a Calidad.\n", bestSavings.coerceIn(-999.0, 100.0)))
                    }
                    append("Compará visualmente VER ÚLTIMA para decidir si la calidad sigue siendo aceptable.")
                }
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Resultados de pruebas")
            .setMessage(text)
            .setPositiveButton("CERRAR", null)
            .setNeutralButton("REINICIAR") { _, _ ->
                results.clear()
                resultsButton.text = "RESULTADOS (0/3)"
                resultsButton.isEnabled = false
                stats.text = idleStatusText()
            }
            .show()
    }

    private fun showLastVideo() {
        val file = lastFile ?: return
        val savedMode = lastMode ?: mode
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
            .setTitle("Última prueba • ${savedMode.label}")
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
        val savedMode = lastMode ?: mode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Guardar en Galería requiere Android 10 o superior en esta versión", Toast.LENGTH_LONG).show()
            return
        }

        val name = "WLive_${savedMode.name}_${System.currentTimeMillis()}.mp4"
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
                // Disponible en Galería.
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
            append("WLive v0.5.0 • ${mode.label}\n")
            append("720p • objetivo ${mode.targetKbps} kbps • GRABANDO\n")
            append(String.format(Locale.US, "Datos: %.2f MB\n", mb))
            append(String.format(Locale.US, "Bitrate real: %.0f kbps\n", avgKbps))
            append(String.format(Locale.US, "Tiempo: %.1f s\n", elapsed))
            append("Audio: ${if (hasAudioPermission()) "SÍ" else "NO"}")
        }
    }

    private fun idleStatusText(): String =
        "WLive v0.5.0 • ${mode.label}\n720p • objetivo ${mode.targetKbps} kbps\nAudio: ${if (hasAudioPermission()) "SÍ" else "NO"}\nLa app compara automáticamente las pruebas"
}

data class TestResult(
    val mode: StreamMode,
    val bytes: Long,
    val seconds: Double,
    val avgKbps: Double,
    val audioEnabled: Boolean
) {
    val mb: Double get() = bytes / 1_048_576.0
    val mbPerMinute: Double get() = (avgKbps * 1000.0 / 8.0 * 60.0) / 1_048_576.0
}

enum class StreamMode(
    val label: String,
    val targetKbps: Int,
    val videoBitrateBps: Int
) {
    QUALITY("CALIDAD", 4000, 4_000_000),
    SAVING("AHORRO", 2000, 2_000_000),
    ULTRA("ULTRA AHORRO", 900, 900_000)
}
