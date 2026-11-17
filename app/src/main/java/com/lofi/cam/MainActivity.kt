package com.lofi.cam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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
    private var camera: Camera? = null
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var selectedFps = 15
    private var qualityPercent = 50
    private var noiseEnabled = true
    private var dateMarkEnabled = true

    private var pixelEnabled = true
    private var pixelResolution = 50

    private val filterPresets = listOf(
        LoFiProcessor.FilterType.NORMAL,
        LoFiProcessor.FilterType.SEPIA,
        LoFiProcessor.FilterType.BW,
        LoFiProcessor.FilterType.WARM,
        LoFiProcessor.FilterType.COOL
    )
    private var filterIndex = 0

    private var lensFacing = CameraSelector.LENS_FACING_BACK

    // 0 = OFF, 1 = ON, 2 = AUTO
    private var flashState = 0

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

        updateFpsLabel()
        updateModeLabel()
        updateQualityLabel()
        updateNoiseLabel()
        updateDateLabel()
        updateFilterLabel()
        updatePixelLabel()
        updatePixelVisibility()
        updateFlashLabel()

        setupScaleGesture()

        binding.btnShutter.setOnClickListener {
            if (isVideoMode) toggleRecording() else takePhoto()
        }

        binding.btnFilterToggle.setOnClickListener {
            filterIndex = (filterIndex + 1) % filterPresets.size
            updateFilterLabel()
        }

        binding.btnPixelToggle.setOnClickListener {
            pixelEnabled = !pixelEnabled
            updatePixelLabel()
            updatePixelVisibility()
        }

        binding.btnFlipCamera.setOnClickListener {
            if (isRecording) {
                Toast.makeText(this, "Hentikan rekaman dulu", Toast.LENGTH_SHORT).show()
            } else {
                lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
                rebindCamera()
            }
        }

        binding.btnFlash.setOnClickListener {
            flashState = (flashState + 1) % 3
            updateFlashLabel()
            applyFlashState()
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

        binding.qualitySeekBar.progress = qualityPercent
        binding.qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                qualityPercent = progress.coerceIn(10, 100)
                updateQualityLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.pixelResSeekBar.progress = pixelResolution
        binding.pixelResSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                pixelResolution = progress.coerceIn(1, 150)
                updatePixelResLabel()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                camera?.cameraControl?.setLinearZoom(progress / 100f)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        binding.btnNoiseToggle.setOnClickListener {
            noiseEnabled = !noiseEnabled
            updateNoiseLabel()
        }

        binding.btnDateToggle.setOnClickListener {
            dateMarkEnabled = !dateMarkEnabled
            updateDateLabel()
        }

        if (hasCameraPermission()) {
            startCamera()
        } else {
            requestPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    private fun setupScaleGesture() {
        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    val newRatio = currentRatio * detector.scaleFactor
                    camera?.cameraControl?.setZoomRatio(newRatio)
                    return true
                }
            }
        )
        binding.previewView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun updateFpsLabel() {
        binding.fpsLabel.text = "$selectedFps FPS"
    }

    private fun updateModeLabel() {
        binding.btnModeToggle.text = if (isVideoMode) "VIDEO" else "PHOTO"
    }

    private fun updateQualityLabel() {
        binding.qualityLabel.text = "Q:$qualityPercent%"
    }

    private fun updateNoiseLabel() {
        binding.btnNoiseToggle.text = if (noiseEnabled) "NOISE:ON" else "NOISE:OFF"
    }

    private fun updateDateLabel() {
        binding.btnDateToggle.text = if (dateMarkEnabled) "DATE:ON" else "DATE:OFF"
    }

    private fun updateFilterLabel() {
        binding.btnFilterToggle.text = filterPresets[filterIndex].name
    }

    private fun updatePixelLabel() {
        binding.btnPixelToggle.text = if (pixelEnabled) "PIXEL:ON" else "PIXEL:OFF"
    }

    private fun updatePixelResLabel() {
        binding.pixelResLabel.text = "${pixelResolution}p"
    }

    private fun updatePixelVisibility() {
        val visibility = if (pixelEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.pixelResLabel.visibility = visibility
        binding.pixelResSeekBar.visibility = visibility
        updatePixelResLabel()
    }

    private fun updateFlashLabel() {
        binding.btnFlash.text = when (flashState) {
            1 -> "FLASH:ON"
            2 -> "FLASH:AUTO"
            else -> "FLASH:OFF"
        }
    }

    private fun applyFlashState() {
        if (isVideoMode) {
            // Video tidak punya mode auto per-frame; ON & AUTO sama-sama
            // menyalakan senter (torch) selama preview/rekam.
            camera?.cameraControl?.enableTorch(flashState != 0)
        } else {
            imageCapture?.flashMode = when (flashState) {
                1 -> ImageCapture.FLASH_MODE_ON
                2 -> ImageCapture.FLASH_MODE_AUTO
                else -> ImageCapture.FLASH_MODE_OFF
            }
        }
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
        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        try {
            camera = if (isVideoMode) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.SD))
                    .build()
                val capture = VideoCapture.withOutput(recorder)
                val cam = provider.bindToLifecycle(this, selector, preview, capture)
                videoCapture = capture
                imageCapture = null
                cam
            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val cam = provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
                videoCapture = null
                cam
            }
            camera?.cameraControl?.setLinearZoom(binding.zoomSeekBar.progress / 100f)
            applyFlashState()
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka kamera: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val targetHeightP = pixelResolution
        val pixelOn = pixelEnabled
        val filter = filterPresets[filterIndex]

        capture.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    image.close()

                    val result = LoFiProcessor.process(
                        bitmap, targetHeightP,
                        pixelEnabled = pixelOn,
                        filter = filter,
                        noiseEnabled = noiseEnabled,
                        stampTimestamp = dateMarkEnabled
                    )
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
            binding.btnShutter.setBackgroundResource(R.drawable.shutter_button)
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
                binding.btnShutter.setBackgroundResource(R.drawable.shutter_button)
                if (!event.hasError()) {
                    processRecordedVideo(rawFile)
                } else {
                    Toast.makeText(this, "Rekaman gagal", Toast.LENGTH_LONG).show()
                }
            }
        }
        isRecording = true
        binding.btnShutter.setBackgroundResource(R.drawable.shutter_button_recording)
    }

    private fun processRecordedVideo(rawFile: File) {
        val targetHeightP = pixelResolution
        val pixelOn = pixelEnabled
        val fps = selectedFps
        val quality = qualityPercent
        val noise = noiseEnabled
        val dateMark = dateMarkEnabled
        val filter = filterPresets[filterIndex]
        Toast.makeText(this, "Memproses video...", Toast.LENGTH_SHORT).show()

        Thread {
            try {
                val outFile = File(cacheDir, "loficam_${System.currentTimeMillis()}.3gp")
                VideoLoFiProcessor.convert(
                    rawFile.absolutePath, outFile.absolutePath, targetHeightP, fps,
                    pixelEnabled = pixelOn,
                    filter = filter,
                    qualityPercent = quality,
                    noiseEnabled = noise,
                    dateMarkEnabled = dateMark
                )
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
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, qualityPercent, stream)
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
}
