package com.example.egglance

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.egglance.ml.CustomMobilenetv2Model
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.example.egglance.database.AppDatabase
import com.example.egglance.database.AppDao

//database
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.example.egglance.database.Scan
import com.example.egglance.database.ScanSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

//For image saving
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var result: TextView
    private lateinit var confidence: TextView
    private lateinit var imageView: ImageView
    private lateinit var picture: Button
    private lateinit var liveButton: Button
    private lateinit var livePreview: TextureView

    private var cameraDevice: CameraDevice? = null
    private var cameraSession: CameraCaptureSession? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var liveDetectionEnabled = false
    private var waitingForLivePermission = false
    private var isClassifyingLiveFrame = false
    private val liveDetectionIntervalMs = 1000L

    //Database variables
    private lateinit var database: AppDatabase
    private lateinit var appDao: AppDao

    //For saving Scans in the scan database
    private var currentSession: ScanSession? = null
    private val confidenceThreshold = 0.85f //Confidence threshold
                                            // accepted by the system
                                            // to save in the database

    // Debounce for live capture
    private var lastSavedTimestamp = 0L
    private val debounceInterval = 5000L // saves the scan every 5 second interval

    private val imageSize = 224
    private val classes = arrayOf(
        "Healthy Leaf",
        "Insect Pest Disease",
        "Leaf Spot Disease",
        "Mosaic Virus Disease",
        "Small Leaf Disease",
        "Wilt Disease"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //database setup
        database = AppDatabase.getDatabase(this)
        appDao = database.appDao()

        result = findViewById(R.id.result)
        confidence = findViewById(R.id.confidence)
        imageView = findViewById(R.id.imageView)
        picture = findViewById(R.id.button)
        liveButton = findViewById(R.id.liveButton)
        livePreview = findViewById(R.id.livePreview)

        livePreview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (liveDetectionEnabled) {
                    openCameraForLiveDetection()
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                stopLiveDetection()
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        picture.setOnClickListener {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startNewSession()
                val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startActivityForResult(cameraIntent, 1)
            } else {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            }
        }

        liveButton.setOnClickListener {
            if (liveDetectionEnabled) {
                endCurrentSession()
                stopLiveDetection()
            } else if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startNewSession()
                startLiveDetection()
            } else {
                waitingForLivePermission = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 100)
            }
        }
    }

    override fun onPause() {
        stopLiveDetection()
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        endCurrentSession()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (waitingForLivePermission) {
                waitingForLivePermission = false
                startLiveDetection()
            }
        } else if (requestCode == 100) {
            waitingForLivePermission = false
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1 && resultCode == RESULT_OK) {
            val image = data?.extras?.get("data") as? Bitmap ?: return
            val scaledImage = Bitmap.createScaledBitmap(image, imageSize, imageSize, false)

            imageView.setImageBitmap(scaledImage)
            classifyImage(scaledImage)

            //Added the endCurrentSession functions for the database
            endCurrentSession()
        }
        else {
            endCurrentSession()
        }
    }

    private fun startLiveDetection() {
        liveDetectionEnabled = true
        imageView.visibility = View.GONE
        livePreview.visibility = View.VISIBLE
        liveButton.text = "Stop Live Detection"
        result.text = "Starting live detection..."
        confidence.text = ""
        startCameraThread()

        if (livePreview.isAvailable) {
            openCameraForLiveDetection()
        }
    }

    private fun stopLiveDetection() {
        liveDetectionEnabled = false
        isClassifyingLiveFrame = false
        cameraHandler?.removeCallbacksAndMessages(null)
        cameraSession?.close()
        cameraSession = null
        cameraDevice?.close()
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null

        if (::liveButton.isInitialized) {
            liveButton.text = "Start Live Detection"
        }
        if (::livePreview.isInitialized) {
            livePreview.visibility = View.GONE
        }
        if (::imageView.isInitialized) {
            imageView.visibility = View.VISIBLE
        }
    }

    private fun startCameraThread() {
        if (cameraThread != null) return

        cameraThread = HandlerThread("LiveDetectionCamera").also {
            it.start()
            cameraHandler = Handler(it.looper)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCameraForLiveDetection() {
        val manager = getSystemService(CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList.firstOrNull() ?: return

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCameraPreview()
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                cameraDevice = null
                Toast.makeText(this@MainActivity, "Could not open camera", Toast.LENGTH_SHORT).show()
            }
        }, cameraHandler)
    }

    private fun startCameraPreview() {
        val texture = livePreview.surfaceTexture ?: return
        texture.setDefaultBufferSize(imageSize, imageSize)

        val surface = Surface(texture)
        val previewRequest = cameraDevice
            ?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            ?.apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            ?: return

        cameraDevice?.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    cameraSession = session
                    session.setRepeatingRequest(previewRequest.build(), null, cameraHandler)
                    scheduleLiveClassification()
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Toast.makeText(this@MainActivity, "Could not start live preview", Toast.LENGTH_SHORT).show()
                    stopLiveDetection()
                }
            },
            cameraHandler
        )
    }

    private fun scheduleLiveClassification() {
        cameraHandler?.postDelayed(object : Runnable {
            override fun run() {
                if (!liveDetectionEnabled) return

                runOnUiThread {
                    classifyLivePreviewFrame()
                }
                cameraHandler?.postDelayed(this, liveDetectionIntervalMs)
            }
        }, liveDetectionIntervalMs)
    }

    private fun classifyLivePreviewFrame() {
        if (isClassifyingLiveFrame || !livePreview.isAvailable) return

        val frame = livePreview.bitmap ?: return
        val scaledImage = Bitmap.createScaledBitmap(frame, imageSize, imageSize, false)
        isClassifyingLiveFrame = true
        classifyImage(scaledImage)
        isClassifyingLiveFrame = false
    }

    private fun classifyImage(image: Bitmap) {
        try {
            val model = CustomMobilenetv2Model.newInstance(applicationContext)
            val inputFeature0 = TensorBuffer.createFixedSize(
                intArrayOf(1, imageSize, imageSize, 3),
                DataType.FLOAT32
            )
            val byteBuffer = ByteBuffer.allocateDirect(4 * imageSize * imageSize * 3)
            byteBuffer.order(ByteOrder.nativeOrder())

            val pixels = IntArray(imageSize * imageSize)
            image.getPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

            for (pixel in pixels) {
                byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
                byteBuffer.putFloat((pixel and 0xFF) / 255.0f)
            }

            inputFeature0.loadBuffer(byteBuffer)

            val outputs = model.process(inputFeature0)
            val confidences = outputs.outputFeature0AsTensorBuffer.floatArray
            val maxPos = confidences.indices.maxByOrNull { confidences[it] } ?: 0

            result.text = classes.getOrElse(maxPos) { "Class ${maxPos + 1}" }
            confidence.text = confidences.mapIndexed { index, score ->
                "${classes.getOrElse(index) { "Class ${index + 1}" }}: ${"%.1f".format(score * 100)}%"
            }.joinToString("\n")

            model.close()

            //For image and AR database data saving logic
            val topConfidence = confidences[maxPos]

            if (topConfidence >= confidenceThreshold) {
                val now = System.currentTimeMillis()

                if (liveDetectionEnabled) {
                    // Live capture — debounced
                    if (now - lastSavedTimestamp >= debounceInterval) {
                        lastSavedTimestamp = now
                        saveScanToDatabase(image, topConfidence)
                    }
                } else {
                    // Camera capture
                    saveScanToDatabase(image, topConfidence)
                }
            }
        } catch (e: IOException) {
            Toast.makeText(this, "Could not load TFLite model", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveImage(bitmap: Bitmap, filename: String): String {
        val file = File(filesDir, filename)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        return file.absolutePath
    }

    private fun saveScanToDatabase(bitmap: Bitmap, confidence: Float) {
        val session = currentSession ?: return

        val filename = "scan_${System.currentTimeMillis()}.jpg"
        val imagePath = saveImage(bitmap, filename)

        val scan = Scan(
            parentSessionID = session.sessionID,
            imagePath = imagePath,
            confidenceScore = confidence
        )

        lifecycleScope.launch(Dispatchers.IO) {
            appDao.insertScan(scan)

            val updatedSession = session.copy(
                totalLeaves = session.totalLeaves + 1
            )
            currentSession = updatedSession
            appDao.updateSession(updatedSession)
        }
    }

    //ScanSession entity logic for database
    private fun startNewSession() {
        endCurrentSession()

        val session = ScanSession()
        currentSession = session
        lastSavedTimestamp = 0L // reset debounce on new session

        lifecycleScope.launch(Dispatchers.IO) {
            appDao.insertSession(session)
        }
    }

    private fun endCurrentSession() {
        val session = currentSession ?: return
        val closedSession = session.copy(
            endingTimestamp = System.currentTimeMillis()
        )
        currentSession = null

        lifecycleScope.launch(Dispatchers.IO) {
            appDao.updateSession(closedSession)
        }
    }
}
