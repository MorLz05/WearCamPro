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
@Composable
fun MainScreen(
    temperature: Float? = null,
    heartRate: Float? = null,
    battery: Int? = null,
    showCamera: Boolean = false,
    cameraReady: Boolean = false,
    isCapturing: Boolean = false,
    activity: MainActivity? = null,
    onOpenGallery: (() -> Unit)? = null
) 
{
    val context = LocalContext.current

    if (showCamera) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        (ctx as? MainActivity)?.let { act ->
                            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                act.startCamera(this)
                            } else {
                                Toast.makeText(ctx, "⚠️ Permiso de cámara no concedido", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Indicador de estado
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black.copy(alpha = 0.7f)
                    ) {
                        Text(
                            text = when {
                                isCapturing -> "📸 Capturando..."
                                cameraReady -> "✅ Cámara lista - Esperando comando"
                                else -> "⏳ Preparando cámara..."
                            },
                            color = Color.White,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Camera,
                        contentDescription = "Logo",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "WearCam Pro",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Control remoto de cámara",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sensores
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📊 Datos del Reloj",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Temperatura
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFD32F2F))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Thermostat, contentDescription = "Temperatura", tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Temperatura",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${temperature?.let { it.roundToInt().toString() } ?: "-"}°C",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Heart Rate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFC62828))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Favorite, contentDescription = "Heart Rate", tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Ritmo Cardíaco",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${heartRate?.toInt()?.toString() ?: "-"} bpm",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Batería
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2E7D32))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.BatteryFull, contentDescription = "Batería", tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Batería del Reloj",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                            Text(
                                text = "${battery?.toString() ?: "-"}%",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Botones
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { onOpenGallery?.invoke() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = "Galería")
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Galería")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "📱 Conectado al reloj",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}
@Composable
fun PhotoGalleryScreen(activity: MainActivity, onBack: () -> Unit) {
    val context = LocalContext.current
    var photos by remember { mutableStateOf(listPhotoFiles(context)) }
    var selectedPhoto by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Galería de Fotos", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (photos.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No hay fotos disponibles")
                }
            }
        } else {
            LazyColumn {
                items(photos) { file ->
                    val bitmap = remember(file) { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(4.dp)
                            .clickable { selectedPhoto = file },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap,
                                    contentDescription = "Foto",
                                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(64.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                file.name,
                                modifier = Modifier.weight(1f),
                                fontSize = 12.sp
                            )
                            IconButton(
                                onClick = {
                                    selectedPhoto = file
                                    showDeleteDialog = true
                                }
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "Borrar", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedPhoto != null && !showDeleteDialog) {
        Dialog(onDismissRequest = { selectedPhoto = null }) {
            val bitmap = BitmapFactory.decodeFile(selectedPhoto!!.absolutePath)?.asImageBitmap()
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "Foto grande",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    contentScale = ContentScale.Fit
                )
            } else {
                Text("No se pudo cargar la imagen")
            }
        }
    }

    if (showDeleteDialog && selectedPhoto != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("¿Borrar foto?") },
            text = { Text("¿Seguro que deseas borrar esta foto?") },
            confirmButton = {
                TextButton(onClick = {
                    selectedPhoto?.delete()
                    photos = listPhotoFiles(context)
                    showDeleteDialog = false
                    selectedPhoto = null
                }) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancelar") }
            }
        )
    }
}

fun listPhotoFiles(context: android.content.Context): List<File> {
    val dir = context.getExternalFilesDir(null)
    return dir?.listFiles { file -> file.name.startsWith("photo_") && file.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() } ?: emptyList()
}
@Composable
fun MainWearScreen(
    temperature: Float?,
    heartRate: Float?,
    battery: Int?,
    cameraStatus: MainActivity.CameraStatus,
    isProcessing: Boolean,
    isCapturing: Boolean,
    photoUri: String?,
    onPrepareCamera: () -> Unit,
    onCapturePhoto: () -> Unit,
    onCancelCapture: () -> Unit,
    onSyncClick: () -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Logo",
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colors.primary
                )
                Text(
                    text = "WearCam Pro",
                    style = MaterialTheme.typography.title1,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    text = "Cámara Remota",
                    style = MaterialTheme.typography.body2,
                    color = Color.Gray
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // === CONTROLES DE CÁMARA ===
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { /* Acción opcional al hacer clic en toda la tarjeta */ }
            )  {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Estado de la cámara
                    val statusText = when (cameraStatus) {
                        MainActivity.CameraStatus.IDLE -> "🔴 Inactiva"
                        MainActivity.CameraStatus.PREPARING -> "🟡 Preparando..."
                        MainActivity.CameraStatus.READY -> "🟢 Lista"
                        MainActivity.CameraStatus.CAPTURING -> "📸 Capturando..."
                        MainActivity.CameraStatus.CAPTURED -> "✅ Capturada"
                        MainActivity.CameraStatus.ERROR -> "❌ Error"
                        MainActivity.CameraStatus.CANCELLED -> "⏹️ Cancelada"
                    }

                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Indicador visual de estado
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(
                                when (cameraStatus) {
                                    MainActivity.CameraStatus.READY -> Color(0xFF4CAF50)
                                    MainActivity.CameraStatus.PREPARING -> Color(0xFFFF9800)
                                    MainActivity.CameraStatus.CAPTURING -> Color(0xFFFF5722)
                                    MainActivity.CameraStatus.CAPTURED -> Color(0xFF2196F3)
                                    MainActivity.CameraStatus.ERROR -> Color(0xFFF44336)
                                    MainActivity.CameraStatus.CANCELLED -> Color(0xFF9E9E9E)
                                    else -> Color(0xFF9E9E9E)
                                }
                            )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Botones
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Botón Principal
                        Button(
                            onClick = {
                                when (cameraStatus) {
                                    MainActivity.CameraStatus.IDLE,
                                    MainActivity.CameraStatus.CANCELLED,
                                    MainActivity.CameraStatus.CAPTURED -> onPrepareCamera()
                                    MainActivity.CameraStatus.READY -> onCapturePhoto()
                                    else -> {}
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                backgroundColor = when (cameraStatus) {
                                    MainActivity.CameraStatus.READY -> Color(0xFF4CAF50)
                                    MainActivity.CameraStatus.PREPARING -> Color(0xFFFF9800)
                                    MainActivity.CameraStatus.CAPTURING -> Color(0xFFFF5722)
                                    MainActivity.CameraStatus.CAPTURED -> Color(0xFF2196F3)
                                    else -> MaterialTheme.colors.primary
                                }
                            ),
                            enabled = cameraStatus != MainActivity.CameraStatus.CAPTURING && !isProcessing,
                            modifier = Modifier.weight(1f)
                        ) {
                            val buttonText = when (cameraStatus) {
                                MainActivity.CameraStatus.IDLE -> "📷 Preparar"
                                MainActivity.CameraStatus.PREPARING -> "⏳ Esperando"
                                MainActivity.CameraStatus.READY -> "📸 Tomar"
                                MainActivity.CameraStatus.CAPTURING -> "⏳ Capturando"
                                MainActivity.CameraStatus.CAPTURED -> "📷 Nueva"
                                MainActivity.CameraStatus.ERROR -> "🔄 Reintentar"
                                MainActivity.CameraStatus.CANCELLED -> "📷 Preparar"
                            }
                            Text(buttonText, modifier = Modifier.padding(horizontal = 4.dp))
                        }

                        // Botón Cancelar
                        if (cameraStatus != MainActivity.CameraStatus.IDLE &&
                            cameraStatus != MainActivity.CameraStatus.CANCELLED &&
                            cameraStatus != MainActivity.CameraStatus.CAPTURED &&
                            cameraStatus != MainActivity.CameraStatus.ERROR) {
                            Button(
                                onClick = onCancelCapture,
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = Color(0xFFD32F2F)
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancelar", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // === SENSORES ===
        item {
            Text(
                text = "📊 Sensores",
                style = MaterialTheme.typography.title2,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        // Temperatura
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFD32F2F))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Thermostat, contentDescription = "Temperatura", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Temperatura",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${temperature?.let { it.roundToInt().toString() } ?: "-"}°C",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Heart Rate
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFC62828))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Favorite, contentDescription = "Heart Rate", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Ritmo Cardíaco",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${heartRate?.toInt()?.toString() ?: "-"} bpm",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Batería
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2E7D32))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.BatteryFull, contentDescription = "Batería", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Batería",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${battery?.toString() ?: "-"}%",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Botón sincronizar manual
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onSyncClick,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF4A148C)
                )
            ) {
                Icon(Icons.Default.Sync, contentDescription = "Sincronizar", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Sincronizar", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}