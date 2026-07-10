package com.amendezm2009.wearappmobile

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.wearable.*
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.google.android.gms.wearable.MessageClient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.viewinterop.AndroidView
import android.view.ViewGroup
import android.util.Log
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Alignment.Companion.CenterHorizontally
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavHost
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.WbSunny
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(), DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private val dataClient by lazy { Wearable.getDataClient(this) }

    // Sensores
    private var temperature by mutableStateOf<Float?>(null)
    private var heartRate by mutableStateOf<Float?>(null)
    private var battery by mutableStateOf<Int?>(null)

    // Cámara
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var photoFile: File? = null
    private var showCamera by mutableStateOf(false)
    private var cameraReady by mutableStateOf(false)
    private var isCapturing by mutableStateOf(false)

    private val CAMERA_PERMISSION_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }

        setContent {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = "main") {
                composable("main") {
                    MainScreen(
                        temperature = temperature,
                        heartRate = heartRate,
                        battery = battery,
                        showCamera = showCamera,
                        cameraReady = cameraReady,
                        isCapturing = isCapturing,
                        activity = this@MainActivity,
                        onOpenGallery = { navController.navigate("gallery") }
                    )
                }
                composable("gallery") {
                    PhotoGalleryScreen(
                        activity = this@MainActivity,
                        onBack = { navController.popBackStack() }
                    )
                }
            }
        }

        dataClient.addListener(this)
        Wearable.getMessageClient(this).addListener(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso de cámara concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                if (dataItem.uri.path?.compareTo("/sensors") == 0) {
                    DataMapItem.fromDataItem(dataItem).dataMap.apply {
                        val temperatureVal = if (containsKey("temperature")) getFloat("temperature") else null
                        val heartRateVal = if (containsKey("heartRate")) getFloat("heartRate") else null
                        val batteryVal = if (containsKey("battery")) getInt("battery") else null

                        runOnUiThread {
                            temperature = temperatureVal
                            heartRate = heartRateVal
                            battery = batteryVal
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dataClient.removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        cameraExecutor.shutdown()
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/prepare_camera" -> {
                runOnUiThread {
                    showCamera = true
                    cameraReady = false
                    isCapturing = false
                    sendCameraStatus("preparing")
                    Toast.makeText(this, "📷 Abriendo cámara...", Toast.LENGTH_SHORT).show()
                }
            }
            "/capture_photo" -> {
                runOnUiThread {
                    if (imageCapture != null) {
                        isCapturing = true
                        takePhoto()
                    } else {
                        Toast.makeText(this, "⚠️ Cámara no lista", Toast.LENGTH_SHORT).show()
                        sendCameraStatus("error")
                    }
                }
            }
            "/cancel_capture" -> {
                runOnUiThread {
                    showCamera = false
                    cameraReady = false
                    isCapturing = false
                    imageCapture = null
                    sendCameraStatus("cancelled")
                    Toast.makeText(this, "⏹️ Captura cancelada", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendCameraStatus(status: String) {
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                nodes.forEach { node ->
                    messageClient.sendMessage(node.id, "/camera_status", status.toByteArray())
                }
            } catch (e: Exception) {
                Log.e("WearCamPro", "Error enviando estado: ", e)
            }
        }
    }

    fun startCamera(previewView: PreviewView) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build()
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()

                    cameraProvider.unbindAll()
                    val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.bindToLifecycle(
                        this as LifecycleOwner,
                        cameraSelector,
                        preview,
                        imageCapture
                    )
                    preview.setSurfaceProvider(previewView.surfaceProvider)

                    cameraReady = true
                    sendCameraStatus("ready")
                    Toast.makeText(this, "✅ Cámara lista", Toast.LENGTH_SHORT).show()

                } catch (e: Exception) {
                    Log.e("WearCamPro", "Error configurando cámara: ", e)
                    sendCameraStatus("error")
                    Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            Log.e("WearCamPro", "Error general: ", e)
            sendCameraStatus("error")
            Toast.makeText(this, "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: run {
            sendCameraStatus("error")
            return
        }

        photoFile = File(
            getExternalFilesDir(null),
            "photo_${System.currentTimeMillis()}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile!!).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    sendPhotoToWatch()
                    sendCameraStatus("captured")
                    isCapturing = false
                    // Cerrar cámara después de capturar
                    showCamera = false
                    cameraReady = false
                    // En lugar de imageCapture = null, solo lo dejamos como está
                    // La cámara se cerrará al ocultar la vista
                    Toast.makeText(this@MainActivity, "📸 Foto capturada y enviada", Toast.LENGTH_SHORT).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing = false
                    sendCameraStatus("error")
                    Toast.makeText(this@MainActivity, "❌ Error: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun sendPhotoToWatch() {
        photoFile?.let { file ->
            try {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                val bytes = stream.toByteArray()

                val asset = Asset.createFromBytes(bytes)
                val dataMap = PutDataMapRequest.create("/photo_image").apply {
                    dataMap.putAsset("photo", asset)
                    dataMap.putLong("timestamp", System.currentTimeMillis())
                }.asPutDataRequest()

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        dataClient.putDataItem(dataMap).await()
                        Log.i("WearCamPro", "✅ Foto enviada al reloj")
                    } catch (e: Exception) {
                        Log.e("WearCamPro", "❌ Error enviando foto: ", e)
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "❌ Error al enviar foto", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("WearCamPro", "❌ Error procesando foto: ", e)
            }
        }
    }
}