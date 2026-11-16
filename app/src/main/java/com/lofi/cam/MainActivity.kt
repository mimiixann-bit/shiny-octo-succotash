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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import com.lofi.cam.databinding.ActivityMainBinding
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private val resolutionPresets = listOf(120, 180, 240, 300, 360, 480)
    private var resolutionIndex = 2
    private var selectedFps = 15

    private var isVideoMode = false
    private var isRecording = false

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Toast.makeText(this, "Izin kamera dibutuhkan", Toast.LENGTH_LONG).show()
            }
            if (results[Manifest.permission.RECORD_AUDIO] != true) {
                Toast.makeText(this, "Tanpa izin mikrofon, video tidak akan ada audio", Toast.LENGTH_LONG).show()
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
            if (isVideoMode) toggleRecording() else takePhoto()
        }

        binding.resLabel.setOnClickListener {
            resolutionIndex = (resolutionIndex + 1) % resolutionPresets.size
            updateResolutionLabel()
        }

        binding.btnModeToggle.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show()
            } else {
                isVideoMode = !isVideoMode
                updateModeLabel()
                rebindCamera()
            }
        }

        binding.fpsSeekBar.progress = selectedFps
        binding.fpsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedFps = progress.coerceIn(8, 25)
                updateFpsLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun updateResolutionLabel() {
        binding.resLabel.text = "${resolutionPresets[resolutionIndex]}p"
    }

    private fun updateFpsLabel() {
        binding.fpsLabel.text = "$selectedFps FPS"
    }

    private fun updateModeLabel() {
        binding.btnModeToggle.text = if (isVideoMode) "VIDEO" else "PHOTO"
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
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
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                provider.bindToLifecycle(this, selector, preview, capture)
                videoCapture = capture
                imageCapture = null
            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
                videoCapture = null
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val targetHeightP = resolutionPresets[resolutionIndex]

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    image.close()

                    val result = LoFiProcessor.process(bitmap, targetHeightP)
                    saveImageToGallery(result)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(
                        this@MainActivity,
                        "Gagal mengambil foto: ${exception.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun toggleRecording() {
        if (isRecording) {
            activeRecording?.stop()
            activeRecording = null
            isRecording = false
            return
        }

        val capture = videoCapture ?: return
        val rawFile = File(cacheDir, "raw_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(rawFile).build()

        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            if (event is VideoRecordEvent.Finalize) {
                isRecording = false
                if (!event.hasError()) {
                    processRecordedVideo(rawFile)
                } else {
                    Toast.makeText(this, "Rekaman gagal", Toast.LENGTH_LONG).show()
                }
            }
        }
        isRecording = true
    }

    private fun processRecordedVideo(rawFile: File) {
        val targetHeightP = resolutionPresets[resolutionIndex]
        val fps = selectedFps
        Toast.makeText(this, "Memproses video...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val outFile = File(cacheDir, "loficam_${System.currentTimeMillis()}.3gp")
                VideoLoFiProcessor.convert(rawFile.absolutePath, outFile.absolutePath, targetHeightP, fps)
                rawFile.delete()
                runOnUiThread { saveVideoToGallery(outFile) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal memproses video: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun saveImageToGallery(bitmap: android.graphics.Bitmap) {
        val name = "LOFICAM_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LoFiCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                val stream = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, stream)
                out.write(stream.toByteArray())
            }
            Toast.makeText(this, "Foto tersimpan di galeri", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, "Gagal menyimpan foto", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveVideoToGallery(file: File) {
        val name = "LOFICAM_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/3gpp")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/LoFiCam")
            }
        }

        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            file.delete()
            Toast.makeText(this, "Video tersimpan di galeri", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, "Gagal menyimpan video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        cameraProvider?.unbindAll()
    }
}it)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            file.delete()
            Toast.makeText(this, "Video tersimpan di galeri", Toast.LENGTH_SHORT).show()
        } ?: run {
            Toast.makeText(this, "Gagal menyimpan video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activeRecording?.stop()
        cameraProvider?.unbindAll()
    }
}
