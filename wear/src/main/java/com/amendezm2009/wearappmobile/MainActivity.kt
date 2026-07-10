package com.amendezm2009.wearappmobile

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.amendezm2009.wearappmobile.ui.theme.WearAppTheme
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.File
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Asset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.offset
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import android.util.Log
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), SensorEventListener, MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var steps by mutableStateOf(0)
    private val scope = CoroutineScope(Dispatchers.Main)
    private val dataClient by lazy { Wearable.getDataClient(this) }
    private var temperature by mutableStateOf<Float?>(null)
    private var heartRate by mutableStateOf<Float?>(null)
    private var batteryLevel by mutableStateOf<Int?>(null)
    private var temperatureSensor: Sensor? = null
    private var heartRateSensor: Sensor? = null
    private var hasBodySensorsPermission = false

    // Estado de la cámara
    private var cameraStatus by mutableStateOf<CameraStatus>(CameraStatus.IDLE)
    private var isProcessing by mutableStateOf(false)
    private var photoUri by mutableStateOf<String?>(null)
    private var notificationMessage by mutableStateOf<String?>(null)
    private var notificationType by mutableStateOf(NotificationType.INFO)
    private var showNotification by mutableStateOf(false)
    private var isCapturing by mutableStateOf(false)

    enum class NotificationType { SUCCESS, ERROR, INFO }

    enum class CameraStatus {
        IDLE, PREPARING, READY, CAPTURING, CAPTURED, ERROR, CANCELLED
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startSensors()
        } else {
            showNotification("⚠️ Se necesitan permisos", NotificationType.ERROR)
        }
    }

    private val requestBodySensorsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasBodySensorsPermission = isGranted
        if (isGranted) {
            heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        temperatureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
        heartRateSensor = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

        setContent {
            WearAppTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    val swipeState = rememberSwipeToDismissBoxState()
                    SwipeToDismissBox(state = swipeState) { isBackground ->
                        if (!isBackground) {
                            // Pantalla principal
                            MainWearScreen(
                                temperature = temperature,
                                heartRate = heartRate,
                                battery = batteryLevel,
                                cameraStatus = cameraStatus,
                                isProcessing = isProcessing,
                                isCapturing = isCapturing,
                                photoUri = photoUri,
                                onPrepareCamera = { prepareCamera() },
                                onCapturePhoto = { capturePhoto() },
                                onCancelCapture = { cancelCapture() },
                                onSyncClick = { syncSensors() }
                            )
                        } else {
                            // Vista de la foto tomada
                            if (photoUri != null) {
                                PhotoPreviewScreen(photoUri = photoUri!!)
                            } else {
                                Text("No hay foto", modifier = Modifier.padding(16.dp))
                            }
                        }
                    }

                    if (showNotification) {
                        ModernNotification(
                            message = notificationMessage ?: "",
                            type = notificationType,
                            onDismiss = { showNotification = false }
                        )
                    }
                }
            }
        }

        checkPermissions()
        getBatteryLevel()
        Wearable.getMessageClient(this).addListener(this)
        Wearable.getDataClient(this).addListener(this)
    }

    // === SENSORES ===

    private fun checkPermissions() {
        val activityRecognitionGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
        val bodySensorsGranted = ActivityCompat.checkSelfPermission(
            this, Manifest.permission.BODY_SENSORS
        ) == PackageManager.PERMISSION_GRANTED
        hasBodySensorsPermission = bodySensorsGranted

        if (activityRecognitionGranted) {
            startSensors()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        if (!bodySensorsGranted) {
            requestBodySensorsPermissionLauncher.launch(Manifest.permission.BODY_SENSORS)
        } else {
            heartRateSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    private fun startSensors() {
        temperatureSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                temperature = event.values[0]
                // Enviar datos automáticamente cuando cambia la temperatura
                syncSensors()
            }
            Sensor.TYPE_HEART_RATE -> {
                if (hasBodySensorsPermission) {
                    heartRate = event.values[0]
                    // Enviar datos automáticamente cuando cambia el heart rate
                    syncSensors()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun getBatteryLevel() {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryLevel = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        // Enviar batería automáticamente
        syncSensors()
    }

    private fun syncSensors() {
        scope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@MainActivity).connectedNodes.await()
                if (nodes.isEmpty()) {
                    Log.w("WearCamPro", "No hay teléfono conectado")
                    return@launch
                }

                val putDataMapRequest = PutDataMapRequest.create("/sensors")
                with(putDataMapRequest.dataMap) {
                    temperature?.let { putFloat("temperature", it) }
                    heartRate?.let { putFloat("heartRate", it) }
                    batteryLevel?.let { putInt("battery", it) }
                }

                val putDataRequest = putDataMapRequest.asPutDataRequest()
                dataClient.putDataItem(putDataRequest).await()
                Log.d("WearCamPro", "✅ Datos enviados al teléfono")
            } catch (e: Exception) {
                Log.e("WearCamPro", "❌ Error sincronizando: ", e)
            }
        }
    }

    // === CONTROL DE CÁMARA ===

    private fun prepareCamera() {
        if (cameraStatus == CameraStatus.IDLE || cameraStatus == CameraStatus.CANCELLED || cameraStatus == CameraStatus.CAPTURED) {
            cameraStatus = CameraStatus.PREPARING
            isCapturing = false
            showNotification("📷 Preparando cámara...", NotificationType.INFO)
            sendMessageToPhone("/prepare_camera")
        }
    }

    private fun capturePhoto() {
        when (cameraStatus) {
            CameraStatus.READY -> {
                cameraStatus = CameraStatus.CAPTURING
                isProcessing = true
                isCapturing = true
                showNotification("📸 Capturando...", NotificationType.INFO)
                sendMessageToPhone("/capture_photo")
            }
            CameraStatus.PREPARING -> {
                showNotification("⏳ Esperando cámara...", NotificationType.INFO)
            }
            CameraStatus.IDLE -> {
                showNotification("📷 Primero prepara la cámara", NotificationType.INFO)
            }
            CameraStatus.CAPTURED -> {
                showNotification("✅ Foto ya capturada", NotificationType.SUCCESS)
            }
            else -> {
                showNotification("Estado: $cameraStatus", NotificationType.INFO)
            }
        }
    }

    private fun cancelCapture() {
        cameraStatus = CameraStatus.CANCELLED
        isProcessing = false
        isCapturing = false
        photoUri = null
        sendMessageToPhone("/cancel_capture")
        showNotification("⏹️ Captura cancelada", NotificationType.INFO)
    }

    private fun sendMessageToPhone(path: String) {
        val nodeClient = Wearable.getNodeClient(this)
        val messageClient = Wearable.getMessageClient(this)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val nodes = nodeClient.connectedNodes.await()
                if (nodes.isNotEmpty()) {
                    messageClient.sendMessage(nodes[0].id, path, null).await()
                    Log.d("WearCamPro", "✅ Mensaje enviado: $path")
                } else {
                    runOnUiThread {
                        showNotification("⚠️ No hay teléfono conectado", NotificationType.ERROR)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    showNotification("❌ Error: ${e.message}", NotificationType.ERROR)
                }
            }
        }
    }

    // === RECEPCIÓN DE MENSAJES ===

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            "/camera_status" -> {
                val status = String(event.data)
                runOnUiThread {
                    cameraStatus = when (status) {
                        "preparing" -> CameraStatus.PREPARING
                        "ready" -> {
                            isProcessing = false
                            isCapturing = false
                            CameraStatus.READY
                        }
                        "captured" -> {
                            isProcessing = false
                            isCapturing = false
                            CameraStatus.CAPTURED
                        }
                        "cancelled" -> {
                            isProcessing = false
                            isCapturing = false
                            CameraStatus.CANCELLED
                        }
                        "error" -> {
                            isProcessing = false
                            isCapturing = false
                            CameraStatus.ERROR
                        }
                        else -> CameraStatus.IDLE
                    }
                    when (cameraStatus) {
                        CameraStatus.READY -> showNotification("✅ Cámara lista", NotificationType.SUCCESS)
                        CameraStatus.CAPTURED -> showNotification("📸 Foto capturada", NotificationType.SUCCESS)
                        CameraStatus.ERROR -> showNotification("❌ Error en cámara", NotificationType.ERROR)
                        CameraStatus.CANCELLED -> showNotification("⏹️ Captura cancelada", NotificationType.INFO)
                        else -> {}
                    }
                }
            }
        }
    }

    // === RECEPCIÓN DE DATOS (FOTO) ===

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED) {
                val dataItem = event.dataItem
                when {
                    dataItem.uri.path?.compareTo("/photo_image") == 0 -> {
                        val asset = dataItem.assets["photo"]
                        asset?.let {
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val inputStream = Wearable.getDataClient(this@MainActivity)
                                        .getFdForAsset(it).await().inputStream
                                    val tempFile = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
                                    tempFile.outputStream().use { output ->
                                        inputStream.copyTo(output)
                                    }
                                    runOnUiThread {
                                        photoUri = tempFile.absolutePath
                                        isProcessing = false
                                        isCapturing = false
                                        cameraStatus = CameraStatus.CAPTURED
                                        showNotification("✅ Foto recibida", NotificationType.SUCCESS)
                                    }
                                } catch (e: Exception) {
                                    runOnUiThread {
                                        showNotification("❌ Error: ${e.message}", NotificationType.ERROR)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        sensorManager.unregisterListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        Wearable.getDataClient(this).removeListener(this)
    }

    private fun showNotification(message: String, type: NotificationType = NotificationType.INFO) {
        notificationMessage = message
        notificationType = type
        showNotification = true
    }
}