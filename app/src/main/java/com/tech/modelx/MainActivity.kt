package com.tech.modelx

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import android.provider.Settings

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.viewFinder)
        val captureButton: Button = findViewById(R.id.camera_capture_button)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissions()
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }

            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .build()

            imageCapture = ImageCapture.Builder()
                .setResolutionSelector(resolutionSelector)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()


            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this, "Failed to start camera", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        val filename = "ModelX.jpg"
        val folderPath = "Pictures/CameraX"

        val outputOptions: ImageCapture.OutputFileOptions
        val isScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        // Delete existing image (if any)
        deleteExistingImage(filename, folderPath)

        val photoFile = if (isScopedStorage) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folderPath)
            }
            outputOptions = ImageCapture.OutputFileOptions.Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()
            null
        } else {
            val legacyFile = File(getOutputDirectory(), filename)
            outputOptions = ImageCapture.OutputFileOptions.Builder(legacyFile).build()
            legacyFile
        }

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    processAndSaveImage(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(this@MainActivity, "Failed to capture image", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    /**
     * Deletes an existing image with the same filename to allow overwriting.
     */
    private fun deleteExistingImage(filename: String, folderPath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
            val selectionArgs = arrayOf(filename, "$folderPath/")
            contentResolver.delete(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selection, selectionArgs)
        } else {
            val file = File(getOutputDirectory(), filename)
            if (file.exists()) file.delete()
        }
    }

    /**
     * Processes the captured image: rotates it based on EXIF data and adds watermark.
     */
    private fun processAndSaveImage(uri: Uri) {
        val inputStream = contentResolver.openInputStream(uri) ?: return
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        val exif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        } else {
            ExifInterface(uri.path ?: "")
        }

        val rotatedBitmap = rotateImageIfRequired(bitmap, exif)

        alert(uri.toString(), rotatedBitmap)
    }

    /**
     * Rotates image based on EXIF data.
     */
    private fun rotateImageIfRequired(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val orientation = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()

        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90F)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180F)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270F)
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Displays an alert to enter watermark text.
     */
    private fun alert(output: String, rotatedBitmap: Bitmap) {
        val builder = AlertDialog.Builder(this)
        val elan = layoutInflater.inflate(R.layout.edit_text_layout, null)
        val editText = elan.findViewById<EditText>(R.id.username)

        builder.setPositiveButton("Save") { _: DialogInterface, _: Int ->
            val watermark = editText.text.toString()
            val result = mark(rotatedBitmap, watermark)
            saveImage(result)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.delete(Uri.parse(output), null, null)
            } else {
                File(Uri.parse(output).path!!).delete()
            }

            Toast.makeText(this, "Image Saved", Toast.LENGTH_SHORT).show()
        }
        builder.setNegativeButton("Cancel", null)
        builder.setView(elan)
        builder.show()
    }

    /**
     * Adds watermark to the image.
     */
    private fun mark(src: Bitmap, watermark: String): Bitmap {
        var w = src.width
        var h = src.height

        // Ensure 9:16 aspect ratio by swapping width and height if needed
        if (w > h) {
            val temp = h
            h = w
            w = temp
        }

        val result = Bitmap.createBitmap(w, h, src.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(src, 0f, 0f, null)

        val rect = RectF(0F, 200F, (w - 0).toFloat(), (h - 650).toFloat())
        val borderWidth = 500.0f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Draw white border
        paint.color = Color.WHITE
        paint.strokeWidth = borderWidth
        paint.style = Paint.Style.STROKE
        canvas.drawRect(rect, paint)

        // Draw bottom white rectangle for watermark
        paint.strokeWidth = 60F
        paint.style = Paint.Style.FILL
        canvas.drawRect(0F, (h - 650).toFloat(), w.toFloat(), h.toFloat(), paint)

        // Draw watermark text
        paint.color = Color.BLACK
        paint.textSize = 258F
        var textWidth = paint.measureText(watermark)

        // Adjust text size to fit within the width
        while (w - textWidth < 123) {
            paint.textSize -= 1
            textWidth = paint.measureText(watermark)
        }

        canvas.drawText(watermark, (w - textWidth) / 2, h - 500F, paint)

        // Draw "Model Number" text
        paint.textSize = 200F
        paint.color = Color.rgb(120, 120, 120)
        val txt1 = "Model Number"
        canvas.drawText(txt1, (w - paint.measureText(txt1)) / 2, 320F, paint)

        // Draw camera icon
        val drawable: Drawable? = ResourcesCompat.getDrawable(resources, R.drawable.ic_baseline_camera_alt_24, null)
        drawable?.let {
            canvas.drawBitmap(it.toBitmap(), (147 - 24) / 2f, (h - 147).toFloat(), null)
        }

        // Draw ModelX text
        paint.textSize = 65F
        paint.color = Color.BLACK
        canvas.drawText("ModelX", 147F, (h - 92).toFloat(), paint)

        // Draw blue border
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(0, 116, 217)
        canvas.drawRect(10F, 10F, w - 10F, h - 10F, paint)

        return result
    }


    /**
     * Saves the processed image to storage.
     */
    private fun saveImage(bitmap: Bitmap) {
        val isScopedStorage = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val folderPath = "Pictures/CameraX"
        val filename = "ModelX_"+SimpleDateFormat("ddMMyyyy_HHmmss", Locale.US).format(System.currentTimeMillis())
        if (isScopedStorage) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folderPath)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } else {
            val file = File(getOutputDirectory(), "$filename.jpg")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        }
    }

    /**
     * Returns the app's storage directory.
     */
    private fun getOutputDirectory(): File {
        val mediaDir = externalMediaDirs.firstOrNull()?.let {
            File(it, "CameraX").apply { mkdirs() }
        }
        return mediaDir ?: filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            this, it
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        requestPermissionsLauncher.launch(REQUIRED_PERMISSIONS)
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.all { it.value }) {
                startCamera()
            } else {
                showPermissionDialog()
            }
        }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Camera Permission Required")
            .setMessage("This app needs camera access to function. Please grant the permission in settings.")
            .setPositiveButton("Grant Permission") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        // If user has NOT checked "Don't ask again", ask again
                        requestPermissions()
                    } else {
                        // If "Don't ask again" is checked, open app settings
                        openAppSettings()
                    }
                } else {
                    // Directly request permission on older devices
                    requestPermissions()
                }
            }
            .setNegativeButton("Exit") { _, _ ->
                finish()  // Exit the app
            }
            .setCancelable(false)
            .show()
    }


    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
