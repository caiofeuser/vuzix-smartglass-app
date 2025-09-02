package com.example.vuzix

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.widget.ProgressBar
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    // UI
    private lateinit var textureView: TextureView
    private lateinit var imageView: ImageView
    private lateinit var detectionButton: Button
    private lateinit var askQuestionButton: Button
    private lateinit var stage1Button: Button // RE-ADICIONADO
    private lateinit var feedbackTextView: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var quitButton: Button

    // Cam & Canvas
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread
    private val paint = Paint()
    private var labels = listOf<String>()
    private val colors = listOf(Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.MAGENTA)
    private var capturedBitmapForQuestion: Bitmap? = null

    // Network & Data
    private val okHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null
    private data class DetectionResult(val box: RectF, val label: String)
    private var voiceCommandReceiver: VoiceCommandReceiver? = null
    private var confirmationSoundPlayer: MediaPlayer? = null

    // --- States
    @Volatile
    private var isDetectionRunning = false
    @Volatile
    private var isStage1Detected = false
    @Volatile
    private var isAskingQuestion = false
    @Volatile
    private var isProcessingFrame = false

    // onCreate <=> useEffect(()=>{},[])
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Load the labels.txt
        try {
            labels = assets.open("labels.txt").bufferedReader().readLines()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error loading labels")
        }

        getPermission()

        // Video handlers
        handlerThread = HandlerThread("videoThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        // Get elements
        imageView = findViewById(R.id.imageView)
        feedbackTextView = findViewById(R.id.feedbackTextView)
        textureView = findViewById(R.id.textureView)
        textureView.surfaceTextureListener = textureListener
        loadingIndicator = findViewById(R.id.loadingIndicator)
        detectionButton = findViewById(R.id.detectionButton)
        askQuestionButton = findViewById(R.id.askQuestionButton)
        stage1Button = findViewById(R.id.stage1Button)
        quitButton = findViewById<Button>(R.id.quitButton)

        // Listeners
        quitButton.setOnClickListener { finish() }
        detectionButton.setOnClickListener { toggleDetection() }
        askQuestionButton.setOnClickListener { captureFrameAndAskQuestion() }
        stage1Button.setOnClickListener { view ->
            Toast.makeText(this, "Stage 1 Process Started!", Toast.LENGTH_SHORT).show()
            Handler(Looper.getMainLooper()).postDelayed({
                // This code will run after the delay
                view.visibility = View.GONE
            }, 2000)

        }

        voiceCommandReceiver = VoiceCommandReceiver()
        val filter = IntentFilter(VuzixSpeechClient.ACTION_VOICE_COMMAND)
        registerReceiver(voiceCommandReceiver, filter, RECEIVER_NOT_EXPORTED)
        try {
            val vuzixSpeechClient = VuzixSpeechClient(this)
            vuzixSpeechClient.insertPhrase("describe scene") // A specific command for our new feature
            vuzixSpeechClient.insertPhrase("ok")             // A generic confirmation command
            println(vuzixSpeechClient.phrases)
        } catch (e: Exception) {
            Log.e("VuzixSpeech", "Error registering phrase: ${e.message}")
        }

        confirmationSoundPlayer = MediaPlayer.create(this, R.raw.confirmation_beep)
    }

    private fun captureFrameAndAskQuestion() {
        if (isDetectionRunning) {
            toggleDetection() // Pause detection
        }


        isAskingQuestion = true
        capturedBitmapForQuestion = textureView.bitmap

        if (capturedBitmapForQuestion == null) {
            Toast.makeText(this, "Camera not ready yet.", Toast.LENGTH_SHORT).show()
            isAskingQuestion = false
            return
        }


        VuzixSpeechClient.TriggerVoiceAudio(this, true)
        showFeedbackText("Please say 'describe scene'")
    }

    private fun ensureWebSocketConnection() {
        if (webSocket == null) {
            startWebSocket()
        }
    }

    private fun showFeedbackText(message: String, durationInMillis: Long = 3000) {
        runOnUiThread {
            feedbackTextView.text = message
            feedbackTextView.visibility = View.VISIBLE
            // Agenda para esconder o texto após 'durationInMillis'
            Handler(Looper.getMainLooper()).postDelayed({
                feedbackTextView.visibility = View.GONE
            }, durationInMillis)
        }
    }

    private fun toggleDetection() {
        println("Connecting with the server")
        isDetectionRunning = !isDetectionRunning
        if (isDetectionRunning) {
            detectionButton.text = "Stop Detection"
            if (webSocket == null) {
                startWebSocket()
            }
        } else {
            detectionButton.text = "Detect"
            // Clean the screen
            runOnUiThread {
                drawOverlay(emptyList())
                stage1Button.visibility = View.GONE
                isStage1Detected = false
            }
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(
            surface: SurfaceTexture,
            width: Int,
            height: Int
        ) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            // It's called upon each frame of the camera
            if (isDetectionRunning && !isProcessingFrame) {
                isProcessingFrame = true
                val bitmap = textureView.bitmap ?: run { isProcessingFrame = false; return }
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
                val byteArray = stream.toByteArray()

                val body = JSONObject().apply {
                    put("type", "detection")
                    put("image", Base64.encodeToString(byteArray, Base64.NO_WRAP))
                }
                webSocket?.send(body.toString())
            }
        }
    }

    private inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("WebSocket", "Connected to server!")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                if (json.getString("type") == "detection_results") {

                    val newDetections = mutableListOf<DetectionResult>()
                    val jsonDetections = json.getJSONObject("detections")
                    val boxes = jsonDetections.getJSONArray("boxes")
                    val classes = jsonDetections.getJSONArray("classes")

                    for (i in 0 until boxes.length()) {
                        val boxArray = boxes.getJSONArray(i)
                        val box = RectF(
                            boxArray.getDouble(0).toFloat(), boxArray.getDouble(1).toFloat(),
                            boxArray.getDouble(2).toFloat(), boxArray.getDouble(3).toFloat()
                        )
                        val label = labels.getOrElse(classes.getInt(i)) { "unknown" }
                        newDetections.add(DetectionResult(box, label))
                    }

                    runOnUiThread {
                        if (isDetectionRunning) {
                            handleStationLogic(newDetections)
                            drawOverlay(newDetections)
                        }
                    }
                } else if (json.getString("type") == "llm_answer") {
                    val answer = json.getString("text")
                    runOnUiThread {
                        showFeedbackText(answer, 10000) // Mostra por 10 segundos
                        runOnUiThread { loadingIndicator.visibility = View.GONE }
                    }
                    // Esconde o indicador de carregamento
                    runOnUiThread { loadingIndicator.visibility = View.GONE }

                } else {

                }

            } catch (e: Exception) {
                Log.e("WebSocket", "Error parsing JSON: ${e.message}")
            }
            isProcessingFrame = false
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocket", "Connection Failed: ${t.message}")
            isProcessingFrame = false
        }
    }


    private fun handleStationLogic(detections: List<DetectionResult>) {
        val requiredLabels =
            setOf("cell phone", "cup") //<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
        val detectedLabels = detections.map { it.label }.toSet()

        val conditionMet = detectedLabels.containsAll(requiredLabels)

        if (conditionMet && !isStage1Detected) {
            isStage1Detected = true
            stage1Button.visibility = View.VISIBLE
            confirmationSoundPlayer?.start()
        } else if (!conditionMet && isStage1Detected) {
            isStage1Detected = false
            stage1Button.visibility = View.GONE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceCommandReceiver?.let { unregisterReceiver(it) }
        if (::cameraDevice.isInitialized) cameraDevice.close()
        handlerThread.quitSafely()
        stopWebSocket()
        confirmationSoundPlayer?.release()
    }

    inner class VoiceCommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.action == VuzixSpeechClient.ACTION_VOICE_COMMAND) {
                    val phrase = it.getStringExtra(VuzixSpeechClient.PHRASE_STRING_EXTRA)


                    Log.d("VuzixVoiceDebug", "--> Frase Reconhecida: '$phrase'")
                    Log.d("VuzixVoiceDebug", "--> Modo Pergunta Ativo? $isAskingQuestion")


                    if ( phrase?.equals("describe_scene", ignoreCase = true) == true) {
                        Log.d("VuzixVoiceDebug", "CONDIÇÃO ATENDIDA! Enviando para o servidor...")
                        sendQuestionToServer(phrase) // Agora esta função será chamada
                    }
                    else if (phrase?.equals("ok", ignoreCase = true) == true) {
                        Log.d("VuzixVoiceDebug", "Comando 'ok' recebido.")
                        confirmationSoundPlayer?.start()
                    } else {
                        Log.d("VuzixVoiceDebug", "Frase ouvida, mas não correspondeu a nenhuma condição.")
                    }
                }
            }
        }
    }

    private fun sendQuestionToServer(question: String) {
        // MODIFIED: Garante que a conexão com o servidor está ativa ANTES de enviar.
        ensureWebSocketConnection()

        runOnUiThread { loadingIndicator.visibility = View.VISIBLE }

        val bitmap = capturedBitmapForQuestion ?: run {
            isAskingQuestion = false
            runOnUiThread { loadingIndicator.visibility = View.GONE }
            return
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val byteArray = stream.toByteArray()
        val imageBase64 = Base64.encodeToString(byteArray, Base64.NO_WRAP)

        val formattedQuestion = "Based on this image, please describe the scene in front of me."

        val requestJson = JSONObject().apply {
            put("type", "question")
            put("image", imageBase64)
            put("text", formattedQuestion)
        }

        // Pequeno atraso para dar tempo à conexão de se estabelecer, se foi recém-criada.
        // Esta é uma solução simples; uma mais robusta usaria callbacks de conexão.
        Handler(Looper.getMainLooper()).postDelayed({
            webSocket?.send(requestJson.toString())
            isAskingQuestion = false // Reseta o estado
        }, 500) // Atraso de 500ms
    }

    private fun startWebSocket() {
        Log.e("WebSocket", "Connection Selected")
        val ipAddress = "172.20.10.3"
        val request = Request.Builder().url("ws://$ipAddress:8000/ws").build()
        webSocket = okHttpClient.newWebSocket(request, SocketListener())
    }

    private fun stopWebSocket() {
        webSocket?.close(1000, "Activity Closing")
        webSocket = null
    }

    private fun drawOverlay(detections: List<DetectionResult>) {
        val bitmap = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        paint.textSize = 40f
        paint.strokeWidth = 5f

        detections.forEachIndexed { index, detection ->
            paint.color = colors[index % colors.size]
            paint.style = Paint.Style.STROKE
            val rect = RectF(
                detection.box.left * canvas.width,
                detection.box.top * canvas.height,
                detection.box.right * canvas.width,
                detection.box.bottom * canvas.height
            )
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.FILL
            canvas.drawText(detection.label, rect.left + 10, rect.top + 30, paint)
        }
        imageView.setImageBitmap(bitmap)
    }

    private fun getPermission() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (permissionsToRequest.isNotEmpty()) {
            requestPermissions(permissionsToRequest.toTypedArray(), 101)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.any { it != PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "Permissions required to run the app.", Toast.LENGTH_LONG).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager.openCamera(
            cameraManager.cameraIdList[0],
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surfaceTexture = textureView.surfaceTexture ?: return
                    val surface = Surface(surfaceTexture)
                    val captureRequest =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                        }.build()
                    cameraDevice.createCaptureSession(
                        listOf(surface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.setRepeatingRequest(captureRequest, null, null)
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {}
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {}
                override fun onError(camera: CameraDevice, error: Int) {}
            },
            handler
        )
    }
}