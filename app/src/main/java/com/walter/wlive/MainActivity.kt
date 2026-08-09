package com.walter.wlive

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
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
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var textureView: TextureView
    private lateinit var stats: TextView
    private lateinit var testButton: Button
    private lateinit var viewButton: Button
    private lateinit var saveButton: Button
    private lateinit var resultsButton: Button

    private var mode = StreamMode.SAVING
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var recorder: DirectH264Recorder? = null
    private var currentFile: File? = null
    private var lastFile: File? = null
    private var lastMode: StreamMode? = null
    private var startedAtMs = 0L
    private val results = linkedMapOf<StreamMode, TestResult>()
    private val uiHandler = Handler(Looper.getMainLooper())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private val statsTicker = object : Runnable {
        override fun run() {
            val active = recorder
            if (active != null) {
                updateLiveStats(active)
                uiHandler.postDelayed(this, 500)
            }
        }
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
            if (!hasAudioPermission()) {
                Toast.makeText(this, "Micrófono no autorizado: las pruebas quedarán sin sonido", Toast.LENGTH_LONG).show()
            }
            openCameraWhenReady()
        } else {
            stats.text = "Se necesita permiso de cámara"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startCameraThread()
        buildUi()
        requestNeededPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (cameraThread == null) startCameraThread()
        openCameraWhenReady()
    }

    override fun onPause() {
        if (recorder != null) stopTest()
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(statsTicker)
        stopCameraThread()
        super.onDestroy()
    }

    private fun startCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("WLiveCamera").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try { cameraThread?.join(500) } catch (_: InterruptedException) {}
        cameraThread = null
        cameraHandler = null
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf<String>()
        if (!hasCameraPermission()) needed += Manifest.permission.CAMERA
        if (!hasAudioPermission()) needed += Manifest.permission.RECORD_AUDIO
        if (needed.isEmpty()) openCameraWhenReady() else permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buildUi() {
        val root = FrameLayout(this)
        textureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                    openCameraWhenReady()
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    configureTransform(width, height)
                }
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
            }
        }
        root.addView(textureView)

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

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        listOf("Calidad" to StreamMode.QUALITY, "Ahorro" to StreamMode.SAVING, "Ultra" to StreamMode.ULTRA).forEach { (label, streamMode) ->
            modes.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    if (recorder != null) return@setOnClickListener
                    mode = streamMode
                    stats.text = idleStatusText()
                }
            })
        }
        panel.addView(modes)

        testButton = Button(this).apply {
            text = "INICIAR PRUEBA"
            setOnClickListener { if (recorder == null) startTest() else stopTest() }
        }
        panel.addView(testButton)

        resultsButton = Button(this).apply {
            text = "RESULTADOS (${results.size}/3)"
            isEnabled = false
            setOnClickListener { showResultsDialog() }
        }
        panel.addView(resultsButton)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        viewButton = Button(this).apply { text = "VER ÚLTIMA"; isEnabled = false; setOnClickListener { showLastVideo() } }
        saveButton = Button(this).apply { text = "GUARDAR VIDEO"; isEnabled = false; setOnClickListener { saveLastVideo() } }
        actions.addView(viewButton)
        actions.addView(saveButton)
        panel.addView(actions)

        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
    }

    private fun openCameraWhenReady() {
        if (!hasCameraPermission() || !::textureView.isInitialized || !textureView.isAvailable || cameraDevice != null) return
        val manager = getSystemService(CameraManager::class.java)
        try {
            val id = manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: manager.cameraIdList.firstOrNull() ?: return
            cameraId = id
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createPreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); cameraDevice = null }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); cameraDevice = null
                    runOnUiThread { stats.text = "Error de cámara: $error" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            stats.text = "No se pudo abrir la cámara: ${e.message ?: "error"}"
        }
    }

    private fun createPreviewSession() {
        val camera = cameraDevice ?: return
        val texture = textureView.surfaceTexture ?: return
        texture.setDefaultBufferSize(1280, 720)
        val previewSurface = Surface(texture)
        try {
            captureSession?.close()
            camera.createCaptureSession(listOf(previewSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                        set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    try { session.setRepeatingRequest(request, null, cameraHandler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    runOnUiThread { stats.text = "No se pudo iniciar la vista previa" }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            stats.text = "Error de vista previa: ${e.message ?: "error"}"
        }
    }

    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, 720f, 1280f)
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
        matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        val scale = maxOf(viewHeight / 720f, viewWidth / 1280f)
        matrix.postScale(scale, scale, centerX, centerY)
        matrix.postRotate(90f, centerX, centerY)
        textureView.setTransform(matrix)
    }

    private fun startTest() {
        val camera = cameraDevice ?: run { stats.text = "La cámara todavía no está lista"; return }
        val texture = textureView.surfaceTexture ?: return
        val previewSurface = Surface(texture)
        val testMode = mode
        val finalFile = File(cacheDir, "wlive-${testMode.name.lowercase()}-${System.currentTimeMillis()}.mp4")
        currentFile = finalFile
        startedAtMs = System.currentTimeMillis()
        viewButton.isEnabled = false
        saveButton.isEnabled = false
        testButton.isEnabled = false

        val direct = DirectH264Recorder(
            outputFile = finalFile,
            bitrate = testMode.videoBitrateBps,
            audioEnabled = hasAudioPermission(),
            onError = { message -> runOnUiThread { stats.text = message; testButton.isEnabled = true } }
        )

        try {
            direct.prepare()
            captureSession?.close()
            camera.createCaptureSession(listOf(previewSurface, direct.encoderSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(previewSurface)
                        addTarget(direct.encoderSurface)
                        set(android.hardware.camera2.CaptureRequest.CONTROL_MODE, android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO)
                    }.build()
                    try {
                        session.setRepeatingRequest(request, null, cameraHandler)
                        direct.start()
                        recorder = direct
                        runOnUiThread {
                            testButton.isEnabled = true
                            testButton.text = "DETENER PRUEBA"
                            uiHandler.removeCallbacks(statsTicker)
                            uiHandler.post(statsTicker)
                        }
                    } catch (e: Exception) {
                        direct.abort()
                        runOnUiThread { stats.text = "No se pudo iniciar la prueba: ${e.message ?: "error"}"; testButton.isEnabled = true }
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    direct.abort()
                    runOnUiThread { stats.text = "El teléfono no pudo abrir cámara + encoder a la vez"; testButton.isEnabled = true }
                }
            }, cameraHandler)
        } catch (e: Exception) {
            direct.abort()
            testButton.isEnabled = true
            stats.text = "Error preparando MediaCodec: ${e.message ?: "error"}"
        }
    }

    private fun stopTest() {
        val active = recorder ?: return
        val testMode = mode
        testButton.isEnabled = false
        uiHandler.removeCallbacks(statsTicker)
        try { captureSession?.stopRepeating() } catch (_: Exception) {}

        Thread {
            val outcome = active.stopAndFinalize()
            recorder = null
            runOnUiThread {
                testButton.isEnabled = true
                testButton.text = "INICIAR PRUEBA"
                createPreviewSession()
                if (outcome.error != null) {
                    stats.text = "Error al finalizar: ${outcome.error}"
                    return@runOnUiThread
                }
                val file = outcome.file ?: return@runOnUiThread
                val elapsed = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)) / 1000.0
                val finalBytes = file.length()
                val avgKbps = (finalBytes * 8.0 / 1000.0) / elapsed
                val videoKbps = (outcome.videoBytes * 8.0 / 1000.0) / elapsed
                val result = TestResult(testMode, finalBytes, elapsed, avgKbps, videoKbps, active.audioEnabled)
                results[testMode] = result
                lastFile = file
                lastMode = testMode
                viewButton.isEnabled = true
                saveButton.isEnabled = true
                resultsButton.isEnabled = true
                resultsButton.text = "RESULTADOS (${results.size}/3)"
                stats.text = finalResultText(result)
                if (results.size == StreamMode.entries.size) showResultsDialog()
            }
        }.start()
    }

    private fun finalResultText(result: TestResult): String {
        val quality = results[StreamMode.QUALITY]
        val savings = if (quality != null && result.mode != StreamMode.QUALITY && quality.avgKbps > 0) {
            (1.0 - result.avgKbps / quality.avgKbps) * 100.0
        } else null
        return buildString {
            append("WLive v0.6.0 • ${result.mode.label}\n")
            append("MediaCodec H.264 • 720p • objetivo ${result.mode.targetKbps} kbps\n")
            append(String.format(Locale.US, "Video real: %.0f kbps\n", result.videoKbps))
            append(String.format(Locale.US, "Total archivo: %.2f MB en %.1f s • %.0f kbps\n", result.mb, result.seconds, result.avgKbps))
            append(String.format(Locale.US, "Consumo: %.2f MB/min\n", result.mbPerMinute))
            append("Audio: ${if (result.audioEnabled) "SÍ" else "NO"}\n")
            if (savings != null) append(String.format(Locale.US, "AHORRO vs Calidad: %.1f%%", savings.coerceIn(-999.0, 100.0)))
            else if (result.mode == StreamMode.QUALITY) append("Referencia guardada para comparar")
            else append("Hacé Calidad para calcular ahorro")
        }
    }

    private fun showResultsDialog() {
        if (results.isEmpty()) return
        val quality = results[StreamMode.QUALITY]
        val text = buildString {
            append("COMPARACIÓN WLive v0.6.0\nMediaCodec H.264 • todos 720p\n\n")
            StreamMode.entries.forEach { m ->
                val r = results[m]
                if (r == null) append("${m.label}: pendiente\n\n") else {
                    append("${m.label}\n")
                    append(String.format(Locale.US, "  video %.0f kbps • %.2f MB/min", r.videoKbps, r.mbPerMinute))
                    if (quality != null && m != StreamMode.QUALITY && quality.avgKbps > 0) {
                        val s = (1.0 - r.avgKbps / quality.avgKbps) * 100.0
                        append(String.format(Locale.US, " • ahorro %.1f%%", s.coerceIn(-999.0, 100.0)))
                    }
                    append("\n\n")
                }
            }
            if (results.size == 3 && quality != null) {
                val best = results.values.minByOrNull { it.avgKbps }
                if (best != null) {
                    val s = (1.0 - best.avgKbps / quality.avgKbps) * 100.0
                    append("EVALUACIÓN\nMenor consumo: ${best.mode.label}\n")
                    if (best.mode != StreamMode.QUALITY) append(String.format(Locale.US, "Reduce aprox. %.1f%% frente a Calidad.\n", s.coerceIn(-999.0, 100.0)))
                    append("Ahora el bitrate de video sale del encoder MediaCodec, no del Recorder de CameraX.")
                }
            }
        }
        AlertDialog.Builder(this).setTitle("Resultados de pruebas").setMessage(text)
            .setPositiveButton("CERRAR", null)
            .setNeutralButton("REINICIAR") { _, _ ->
                results.clear(); resultsButton.text = "RESULTADOS (0/3)"; resultsButton.isEnabled = false; stats.text = idleStatusText()
            }.show()
    }

    private fun showLastVideo() {
        val file = lastFile ?: return
        val savedMode = lastMode ?: mode
        val videoView = VideoView(this).apply {
            setVideoPath(file.absolutePath)
            setMediaController(MediaController(this@MainActivity))
            setOnPreparedListener { mp -> mp.isLooping = true; start() }
        }
        AlertDialog.Builder(this).setTitle("Última prueba • ${savedMode.label}").setView(videoView)
            .setPositiveButton("CERRAR", null).setOnDismissListener { videoView.stopPlayback() }.show()
        videoView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.65).toInt())
    }

    private fun saveLastVideo() {
        val file = lastFile ?: return
        val savedMode = lastMode ?: mode
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(this, "Guardar en Galería requiere Android 10 o superior", Toast.LENGTH_LONG).show(); return
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "WLive_${savedMode.name}_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/WLive")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0); contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "Guardado en Películas/WLive", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(this, "Error al guardar: ${e.message ?: "desconocido"}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateLiveStats(active: DirectH264Recorder) {
        val elapsed = ((System.currentTimeMillis() - startedAtMs).coerceAtLeast(1L)) / 1000.0
        val bytes = active.currentBytes()
        val kbps = (bytes * 8.0 / 1000.0) / elapsed
        stats.text = buildString {
            append("WLive v0.6.0 • ${mode.label}\n")
            append("MediaCodec H.264 • 720p • objetivo ${mode.targetKbps} kbps • GRABANDO\n")
            append(String.format(Locale.US, "Datos generados: %.2f MB\n", bytes / 1_048_576.0))
            append(String.format(Locale.US, "Bitrate total aprox.: %.0f kbps\n", kbps))
            append(String.format(Locale.US, "Tiempo: %.1f s", elapsed))
        }
    }

    private fun idleStatusText(): String =
        "WLive v0.6.0 • ${mode.label}\nMediaCodec H.264 • 720p\nBitrate fijado: ${mode.targetKbps} kbps\nAudio + evaluación automática"

    private fun closeCamera() {
        try { captureSession?.close() } catch (_: Exception) {}
        captureSession = null
        try { cameraDevice?.close() } catch (_: Exception) {}
        cameraDevice = null
    }
}

class DirectH264Recorder(
    private val outputFile: File,
    private val bitrate: Int,
    val audioEnabled: Boolean,
    private val onError: (String) -> Unit
) {
    private val width = 1280
    private val height = 720
    private var videoCodec: MediaCodec? = null
    lateinit var encoderSurface: Surface
        private set
    private var videoMuxer: MediaMuxer? = null
    private var audioRecorder: MediaRecorder? = null
    private var drainThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val videoTemp = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".video.mp4")
    private val audioTemp = File(outputFile.parentFile, outputFile.nameWithoutExtension + ".audio.m4a")
    @Volatile private var videoBytesWritten = 0L

    data class Outcome(val file: File?, val videoBytes: Long, val error: String?)

    fun prepare() {
        outputFile.delete(); videoTemp.delete(); audioTemp.delete()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            if (Build.VERSION.SDK_INT >= 21) setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = createInputSurface()
        }
        videoMuxer = MediaMuxer(videoTemp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        if (audioEnabled) {
            audioRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(44_100)
                setOutputFile(audioTemp.absolutePath)
                prepare()
            }
        }
    }

    fun start() {
        val codec = videoCodec ?: error("Codec no preparado")
        running.set(true)
        codec.start()
        drainThread = Thread { drainVideo(codec) }.also { it.start() }
        if (audioEnabled) {
            try { audioRecorder?.start() } catch (e: Exception) { onError("No se pudo iniciar audio: ${e.message ?: "error"}") }
        }
    }

    fun currentBytes(): Long = videoTemp.length() + if (audioEnabled) audioTemp.length() else 0L

    fun stopAndFinalize(): Outcome {
        var error: String? = null
        try {
            if (audioEnabled) {
                try { audioRecorder?.stop() } catch (_: Exception) {}
                try { audioRecorder?.release() } catch (_: Exception) {}
                audioRecorder = null
            }
            videoCodec?.signalEndOfInputStream()
            running.set(false)
            drainThread?.join(3000)
            try { videoCodec?.stop() } catch (_: Exception) {}
            try { videoCodec?.release() } catch (_: Exception) {}
            videoCodec = null
            try { encoderSurface.release() } catch (_: Exception) {}
            try { videoMuxer?.stop() } catch (_: Exception) {}
            try { videoMuxer?.release() } catch (_: Exception) {}
            videoMuxer = null

            videoBytesWritten = videoTemp.length()
            if (audioEnabled && audioTemp.exists() && audioTemp.length() > 0L) {
                muxTracks(videoTemp, audioTemp, outputFile)
            } else {
                videoTemp.copyTo(outputFile, overwrite = true)
            }
        } catch (e: Exception) {
            error = e.message ?: e.javaClass.simpleName
        } finally {
            videoTemp.delete(); audioTemp.delete()
        }
        return Outcome(outputFile.takeIf { error == null && it.exists() && it.length() > 0L }, videoBytesWritten, error)
    }

    fun abort() {
        running.set(false)
        try { audioRecorder?.reset(); audioRecorder?.release() } catch (_: Exception) {}
        try { videoCodec?.stop(); videoCodec?.release() } catch (_: Exception) {}
        try { if (::encoderSurface.isInitialized) encoderSurface.release() } catch (_: Exception) {}
        try { videoMuxer?.release() } catch (_: Exception) {}
        videoTemp.delete(); audioTemp.delete(); outputFile.delete()
    }

    private fun drainVideo(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        try {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!running.get()) continue
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = videoMuxer!!.addTrack(codec.outputFormat)
                        videoMuxer!!.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null && info.size > 0 && muxerStarted && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            videoMuxer!!.writeSampleData(trackIndex, buffer, info)
                        }
                        val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(index, false)
                        if (eos) break
                    }
                }
            }
        } catch (e: Exception) {
            onError("Error codificando H.264: ${e.message ?: "error"}")
        }
    }

    private fun muxTracks(video: File, audio: File, out: File) {
        val videoExtractor = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val videoTrackSrc = (0 until videoExtractor.trackCount).first { videoExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audioTrackSrc = (0 until audioExtractor.trackCount).first { audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            videoExtractor.selectTrack(videoTrackSrc)
            audioExtractor.selectTrack(audioTrackSrc)
            val videoTrackDst = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackSrc))
            val audioTrackDst = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackSrc))
            muxer.start()
            copyTrack(videoExtractor, muxer, videoTrackDst)
            copyTrack(audioExtractor, muxer, audioTrackDst)
            muxer.stop()
        } finally {
            videoExtractor.release(); audioExtractor.release(); muxer.release()
        }
    }

    private fun copyTrack(extractor: MediaExtractor, muxer: MediaMuxer, dstTrack: Int) {
        val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(dstTrack, buffer, info)
            extractor.advance()
        }
    }
}

data class TestResult(
    val mode: StreamMode,
    val bytes: Long,
    val seconds: Double,
    val avgKbps: Double,
    val videoKbps: Double,
    val audioEnabled: Boolean
) {
    val mb: Double get() = bytes / 1_048_576.0
    val mbPerMinute: Double get() = if (seconds > 0.0) mb * 60.0 / seconds else 0.0
}

enum class StreamMode(val label: String, val targetKbps: Int, val videoBitrateBps: Int) {
    QUALITY("CALIDAD", 4000, 4_000_000),
    SAVING("AHORRO", 2000, 2_000_000),
    ULTRA("ULTRA AHORRO", 900, 900_000)
}
