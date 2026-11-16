
package com.lofi.cam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.camera2.interop.Camera2Interop
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.lofi.cam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var selectedResolutionP = 75
    private var selectedFps = 15

    private var isVideoMode = false
    private var isRecording = false

    private val requestPermissions =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Izin kamera dibutuhkan",
                    Toast.LENGTH_LONG
                ).show()
            }

            if (results[Manifest.permission.RECORD_AUDIO] != true) {
                Toast.makeText(
                    this,
                    "Tanpa izin mikrofon, video tidak akan ada audio",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateResolutionLabel()
        updateFpsLabel()
        updateModeLabel()

        binding.btnShutter.setOnClickListener {
            if (isVideoMode) {
                toggleRecording()
            } else {
                takePhoto()
            }
        }

        binding.btnPhotoMode.setOnClickListener { setMode(false) }
        binding.btnVideoMode.setOnClickListener { setMode(true) }

        binding.resSeekBar.max = 150
        binding.resSeekBar.progress = selectedResolutionP
        binding.resSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    selectedResolutionP = progress.coerceIn(1, 150)
                    updateResolutionLabel()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (isVideoMode && !isRecording) rebindCamera()
                }
            }
        )

        binding.fpsSeekBar.min = 5
        binding.fpsSeekBar.max = 30
        binding.fpsSeekBar.progress = selectedFps

        binding.fpsSeekBar.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {

                override fun onProgressChanged(
                    sb: SeekBar?,
                    progress: Int,
                    fromUser: Boolean
                ) {
                    selectedFps = progress.coerceIn(5, 30)
                    updateFpsLabel()
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    if (isVideoMode && !isRecording) rebindCamera()
                }
            }
        )

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    private fun updateResolutionLabel() {
        binding.resLabel.text = "${selectedResolutionP}p"
    }

    private fun setMode(video: Boolean) {
        if (isRecording) {
            Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show()
            return
        }
        if (isVideoMode == video) return
        isVideoMode = video
        updateModeLabel()
        rebindCamera()
    }

    private fun updateFpsLabel() {
        binding.fpsLabel.text = "$selectedFps FPS"
    }

    private fun updateModeLabel() {
        binding.btnPhotoMode.isSelected = !isVideoMode
        binding.btnVideoMode.isSelected = isVideoMode
        binding.btnShutter.isSelected = isVideoMode
        binding.btnShutter.isActivated = isRecording
        binding.btnShutter.contentDescription = when {
            isRecording -> "Hentikan rekaman"
            isVideoMode -> "Mulai merekam video"
            else -> "Ambil foto"
        }
        binding.btnPhotoMode.alpha = if (isVideoMode) 0.55f else 1f
        binding.btnVideoMode.alpha = if (isVideoMode) 1f else 0.55f
        binding.fpsSeekBar.visibility = if (isVideoMode) android.view.View.VISIBLE else android.view.View.GONE
        binding.fpsLabel.visibility = if (isVideoMode) android.view.View.VISIBLE else android.view.View.GONE
        binding.flashLabel.text = if (isVideoMode) "VIDEO • 3GP" else "PHOTO • JPEG"
        binding.resSeekBar.isEnabled = !isRecording
        binding.fpsSeekBar.isEnabled = isVideoMode && !isRecording
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            cameraProvider = providerFuture.get()
            rebindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun rebindCamera() {
        val provider = cameraProvider ?: return

        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            if (isVideoMode) {
                // Record at a camera-supported size first. The selected 1p-150p
                // output size is applied later by Media3 transcoding.
                val recorder = Recorder.Builder().build()
                val captureBuilder = VideoCapture.Builder(recorder)

                // Request the selected frame rate. Hardware may adjust unsupported rates.
                Camera2Interop.Extender(captureBuilder)
                    .setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(selectedFps, selectedFps)
                    )

                val capture = captureBuilder.build()
                val camera = provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    capture
                )

                videoCapture = capture
                imageCapture = null

            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .build()

                provider.bindToLifecycle(
                    this,
                    selector,
                    preview,
                    capture
                )

                imageCapture = capture
                videoCapture = null
            }

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Gagal membuka kamera: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Kamera foto belum siap. Coba lagi.", Toast.LENGTH_SHORT).show()
            return
        }
        val targetHeightP = selectedResolutionP
        val rawFile = File(cacheDir, "photo_" + System.currentTimeMillis() + ".jpg")
        val options = ImageCapture.OutputFileOptions.Builder(rawFile).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    var source: android.graphics.Bitmap? = null
                    var result: android.graphics.Bitmap? = null
                    try {
                        source = BitmapFactory.decodeFile(rawFile.absolutePath)
                            ?: throw IllegalStateException("Foto kamera tidak dapat dibaca")
                        result = LoFiProcessor.process(source, targetHeightP)
                        saveImageToGallery(result)
                    } catch (e: Exception) {
                        val reason = e.localizedMessage?.takeIf { it.isNotBlank() }
                            ?: e.javaClass.simpleName
                        Toast.makeText(
                            this@MainActivity,
                            "Gagal memproses foto: $reason",
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        if (result !== source) result?.recycle()
                        source?.recycle()
                        rawFile.delete()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    rawFile.delete()
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal mengambil foto: " + exception.message,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun toggleRecording() {
        if (isRecording) {
            activeRecording?.stop()
            return
        }

        val capture = videoCapture
        if (capture == null) {
            Toast.makeText(this, "Kamera video belum siap. Coba lagi.", Toast.LENGTH_SHORT).show()
            return
        }

        val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.mp4")
        try {
            var pending = capture.output.prepareRecording(
                this,
                FileOutputOptions.Builder(rawFile).build()
            )
            if (hasAudioPermission()) pending = pending.withAudioEnabled()

            isRecording = true
            updateModeLabel()
            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        updateModeLabel()
                    }
                    is VideoRecordEvent.Finalize -> {
                        isRecording = false
                        activeRecording = null
                        updateModeLabel()
                        if (!event.hasError() && rawFile.exists() && rawFile.length() > 0L) {
                            processRecordedVideo(rawFile)
                        } else {
                            rawFile.delete()
                            Toast.makeText(
                                this,
                                "Rekaman gagal disimpan. Coba lagi.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            isRecording = false
            activeRecording = null
            updateModeLabel()
            rawFile.delete()
            val reason = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Toast.makeText(this, "Gagal mulai merekam: $reason", Toast.LENGTH_LONG).show()
        }
    }

    private fun processRecordedVideo(rawFile: File) {
        val targetHeightP = selectedResolutionP
        val fps = selectedFps
        Toast.makeText(this, "Menyimpan video 3GP...", Toast.LENGTH_SHORT).show()

        Thread {
            var outFile: File? = null
            try {
                outFile = File(cacheDir, "loficam_${System.currentTimeMillis()}.3gp")
                VideoLoFiProcessor.convert(
                    this,
                    rawFile.absolutePath,
                    outFile.absolutePath,
                    targetHeightP,
                    fps
                )
                require(outFile.exists() && outFile.length() > 0L) { "File hasil tidak terbentuk" }
                runOnUiThread {
                    val saved = saveVideoToGallery(outFile)
                    if (!saved) {
                        Toast.makeText(this, "Video gagal disimpan ke galeri", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                outFile?.delete()
                runOnUiThread {
                    val reason = e.localizedMessage?.takeIf { it.isNotBlank() }
                        ?: e.javaClass.simpleName
                    Toast.makeText(this, "Gagal memproses video: $reason", Toast.LENGTH_LONG).show()
                }
            } finally {
                rawFile.delete()
            }
        }.start()
    }

    private fun saveImageToGallery(
        bitmap: android.graphics.Bitmap
    ) {
        val name = "LOFICAM_" +
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.getDefault()
            ).format(java.util.Date())

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/LoFiCam"
                )
            }
        }

        val uri = contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )

        if (uri == null) {
            Toast.makeText(this, "Gagal membuat file foto", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Tidak bisa membuka penyimpanan galeri")
            output.use { stream ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, stream)) {
                    throw IllegalStateException("Gagal mengodekan JPEG")
                }
            }
            Toast.makeText(this, "Foto tersimpan di Pictures/LoFiCam", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            val reason = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Toast.makeText(this, "Gagal menyimpan foto: $reason", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveVideoToGallery(file: File): Boolean {
        val name = "LOFICAM_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(java.util.Date())

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.3gp")
            put(MediaStore.Video.Media.MIME_TYPE, "video/3gpp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LoFiCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return false

        return try {
            val output = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Tidak bisa membuka penyimpanan galeri")
            output.use { stream ->
                file.inputStream().use { input -> input.copyTo(stream) }
            }
            file.delete()
            Toast.makeText(this, "Video tersimpan di galeri", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            Toast.makeText(
                this,
                "Gagal menyimpan video: ${e.localizedMessage ?: e.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
            false
        }
    }

    override fun onDestroy() {
        activeRecording?.stop()
        cameraProvider?.unbindAll()
        super.onDestroy()
    }
}
