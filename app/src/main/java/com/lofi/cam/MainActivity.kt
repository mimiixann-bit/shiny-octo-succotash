
package com.lofi.cam

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.location.LocationManager
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.ACCESS_COARSE_LOCATION
import androidx.exifinterface.media.ExifInterface
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
    private var selectedVideoFormat = "3GP"

    private var isVideoMode = false
    private var isRecording = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var filterIndex = 0
    private var hdMode = false
    private var geotagEnabled = false
    private var currentCamera: Camera? = null
    private val recordingSegments = mutableListOf<File>()
    private var switchLensAfterSegment = false
    private var stopAfterSegment = false
    private val filters = arrayOf("RETRO", "WARM", "B&W", "COOL", "ORIGINAL")

    private val requestLocationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        geotagEnabled = results[ACCESS_FINE_LOCATION] == true || results[ACCESS_COARSE_LOCATION] == true
        binding.btnGeo.text = if (geotagEnabled) "GEO ON" else "GEO OFF"
    }

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
        binding.btnGeo.setOnClickListener {
            if (geotagEnabled) {
                geotagEnabled = false
                binding.btnGeo.text = "GEO OFF"
            } else if (hasLocationPermission()) {
                geotagEnabled = true
                binding.btnGeo.text = "GEO ON"
            } else {
                requestLocationPermission.launch(arrayOf(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION))
            }
        }
        binding.btnLens.setOnClickListener { switchLens() }
        binding.btnFlash.setOnClickListener { cycleFlash() }
        binding.btnGallery.setOnClickListener { openLatestMedia() }
        binding.btnFilter.setOnClickListener {
            filterIndex = (filterIndex + 1) % filters.size
            binding.btnFilter.text = filters[filterIndex]
        }
        binding.btnHd.setOnClickListener {
            hdMode = !hdMode
            binding.btnHd.text = if (hdMode) "HD ON" else "HD OFF"
            binding.resSeekBar.isEnabled = !hdMode && !isRecording
        }
        binding.zoomSeekBar.max = 100
        binding.zoomSeekBar.progress = 0
        binding.zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val maxZoom = currentCamera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
                val ratio = 1f + (maxZoom - 1f) * progress / 100f
                currentCamera?.cameraControl?.setZoomRatio(ratio)
                binding.zoomLabel.text = String.format(Locale.US, "%.1fx", ratio)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        binding.formatGroup.check(R.id.format3gp)
        binding.formatGroup.setOnCheckedChangeListener { _, checkedId ->
            selectedVideoFormat = if (checkedId == R.id.formatMp4) "MP4" else "3GP"
            updateModeLabel()
        }

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
        binding.formatGroup.visibility = if (isVideoMode) android.view.View.VISIBLE else android.view.View.GONE
        binding.flashLabel.text = if (isVideoMode) "VIDEO • $selectedVideoFormat" else "PHOTO • JPEG"
        binding.btnFlash.isEnabled = lensFacing == CameraSelector.LENS_FACING_BACK
        binding.btnFilter.visibility = if (isVideoMode) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnGeo.text = if (geotagEnabled) "GEO ON" else "GEO OFF"
        binding.resSeekBar.isEnabled = !hdMode && !isRecording
        binding.fpsSeekBar.isEnabled = isVideoMode && !isRecording
        binding.formatGroup.isEnabled = isVideoMode && !isRecording
        binding.format3gp.isEnabled = isVideoMode && !isRecording
        binding.formatMp4.isEnabled = isVideoMode && !isRecording
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

        val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

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

                currentCamera = camera
                videoCapture = capture
                imageCapture = null

            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    )
                    .setFlashMode(flashMode)
                    .build()

                currentCamera = provider.bindToLifecycle(
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
        val targetHeightP = if (hdMode) 0 else selectedResolutionP
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
                        result = if (hdMode && filterIndex == 4) source.copy(source.config ?: android.graphics.Bitmap.Config.ARGB_8888, false) else LoFiProcessor.process(source, if (targetHeightP == 0) source.height else targetHeightP, filterIndex)
                        saveImageToGallery(requireNotNull(result))
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
            stopAfterSegment = true
            activeRecording?.stop()
            return
        }
        if (videoCapture == null) {
            Toast.makeText(this, "Kamera video belum siap. Coba lagi.", Toast.LENGTH_SHORT).show()
            return
        }
        recordingSegments.clear()
        switchLensAfterSegment = false
        stopAfterSegment = false
        isRecording = true
        updateModeLabel()
        startRecordingSegment()
    }

    private fun startRecordingSegment() {
        val capture = videoCapture ?: run {
            finishRecordingWithError("Kamera tidak tersedia setelah pergantian lensa")
            return
        }
        val rawFile = File(cacheDir, "segment_${System.currentTimeMillis()}.mp4")
        try {
            var pending = capture.output.prepareRecording(this, FileOutputOptions.Builder(rawFile).build())
            if (hasAudioPermission()) pending = pending.withAudioEnabled()
            activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecording = true
                        updateModeLabel()
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        if (event.hasError() || !rawFile.exists() || rawFile.length() == 0L) {
                            rawFile.delete()
                            finishRecordingWithError("Rekaman gagal disimpan")
                            return@start
                        }
                        recordingSegments.add(rawFile)
                        if (stopAfterSegment) {
                            stopAfterSegment = false
                            isRecording = false
                            updateModeLabel()
                            processRecordedVideos(recordingSegments.toList())
                        } else if (switchLensAfterSegment) {
                            switchLensAfterSegment = false
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            binding.btnLens.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"
                            rebindCamera()
                            startRecordingSegment()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            rawFile.delete()
            finishRecordingWithError(e.localizedMessage ?: "Tidak dapat memulai segmen rekaman")
        }
    }

    private fun finishRecordingWithError(message: String) {
        isRecording = false
        activeRecording = null
        switchLensAfterSegment = false
        stopAfterSegment = false
        recordingSegments.forEach { it.delete() }
        recordingSegments.clear()
        updateModeLabel()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun processRecordedVideos(segments: List<File>) {
        val targetHeightP = if (hdMode) 0 else selectedResolutionP
        val format = selectedVideoFormat
        Toast.makeText(this, "Menggabungkan video $format...", Toast.LENGTH_SHORT).show()
        Thread {
            var outFile: File? = null
            try {
                outFile = File(cacheDir, "loficam_${System.currentTimeMillis()}.${format.lowercase(Locale.ROOT)}")
                VideoLoFiProcessor.convertSegments(
                    this,
                    segments.map { it.absolutePath },
                    outFile.absolutePath,
                    targetHeightP,
                    format
                )
                require(outFile.exists() && outFile.length() > 0L) { "File hasil tidak terbentuk" }
                runOnUiThread {
                    if (!saveVideoToGallery(outFile, format)) {
                        Toast.makeText(this, "Video gagal disimpan ke galeri", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                outFile?.delete()
                segments.forEach { it.delete() }
                runOnUiThread {
                    Toast.makeText(this, "Gagal menggabungkan video: ${e.localizedMessage ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
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
            if (geotagEnabled && hasLocationPermission()) addGeoTag(uri)
            Toast.makeText(this, "Foto tersimpan di Pictures/LoFiCam", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            val reason = e.localizedMessage?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            Toast.makeText(this, "Gagal menyimpan foto: $reason", Toast.LENGTH_LONG).show()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun addGeoTag(uri: Uri) {
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
            }.maxByOrNull { it.time } ?: return
            val temp = File(cacheDir, "geo_" + System.currentTimeMillis() + ".jpg")
            contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            val exif = ExifInterface(temp.absolutePath)
            exif.setLatLong(location.latitude, location.longitude)
            exif.saveAttributes()
            contentResolver.openOutputStream(uri, "wt")?.use { output -> temp.inputStream().use { it.copyTo(output) } }
            temp.delete()
        } catch (_: Exception) { }
    }

    private fun switchLens() {
        if (isRecording) {
            if (activeRecording == null || switchLensAfterSegment) return
            switchLensAfterSegment = true
            Toast.makeText(this, "Mengganti kamera dan melanjutkan rekaman...", Toast.LENGTH_SHORT).show()
            activeRecording?.stop()
            return
        }
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
            CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        binding.btnLens.text = if (lensFacing == CameraSelector.LENS_FACING_FRONT) "FRONT" else "BACK"
        rebindCamera()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        binding.btnFlash.text = if (isVideoMode) {
            if (flashMode == ImageCapture.FLASH_MODE_OFF) "TORCH OFF" else "TORCH ON"
        } else when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"
            ImageCapture.FLASH_MODE_ON -> "FLASH ON"
            else -> "FLASH OFF"
        }
        currentCamera?.let { camera ->
            if (isVideoMode && camera.cameraInfo.hasFlashUnit())
                camera.cameraControl.enableTorch(flashMode != ImageCapture.FLASH_MODE_OFF)
        }
        imageCapture?.flashMode = flashMode
    }

    private fun openLatestMedia() {
        val image = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), "${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf("Pictures/LoFiCam/"), "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null
        }
        val video = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.RELATIVE_PATH}=?",
            arrayOf("Movies/LoFiCam/"), "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c ->
            if (c.moveToFirst()) Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()) else null
        }
        val uri = image ?: video
        if (uri == null) startActivity(Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        else startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, if (image != null) "image/*" else "video/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun saveVideoToGallery(file: File, format: String): Boolean {
        val name = "LOFICAM_" + SimpleDateFormat(
            "yyyyMMdd_HHmmss",
            Locale.getDefault()
        ).format(java.util.Date())

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$name.${format.lowercase(Locale.ROOT)}")
            put(MediaStore.Video.Media.MIME_TYPE, if (format == "MP4") "video/mp4" else "video/3gpp")
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
