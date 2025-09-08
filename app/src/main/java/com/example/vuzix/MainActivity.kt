package com.example.vuzix

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient

class MainActivity : AppCompatActivity(), SocketListenerInterface, VoiceCommandListener {
    // UI
    private lateinit var textureView: TextureView
    private lateinit var detectionButton: Button
    private lateinit var askQuestionButton: Button
    private lateinit var stage1Button: Button
    private lateinit var feedbackTextView: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var quitButton: Button
    // Cam & Canvas
    private lateinit var cameraManager: CameraManager
    private lateinit var cameraDevice: CameraDevice
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread
    private var labels = listOf<String>()
    private var capturedBitmapForQuestion: Bitmap? = null
    // Network & Data
    private lateinit var webSocketManager: WebSocketManager
    private lateinit var overlayManager: OverlayManager
    private var voiceCommandManager: VuzixVoiceManager? = null
    private var confirmationSoundPlayer: MediaPlayer? = null
    private lateinit var speechResultLauncher: ActivityResultLauncher<Intent>

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

// Managers
        webSocketManager = WebSocketManager(this)
        overlayManager = OverlayManager(findViewById(R.id.imageView))
        voiceCommandManager = VuzixVoiceManager(this, this)

        confirmationSoundPlayer = MediaPlayer.create(this, R.raw.confirmation_beep)

// Get elements
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
                view.visibility = View.GONE
            }, 2000)
        }
//        speechResultLauncher =
//            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
//                if (result.resultCode == RESULT_OK && result.data != null) {
//                    val speechData: Intent? = result.data
//                    // A IA de voz retorna uma lista de possíveis transcrições, pegamos a primeira (a mais provável)
//                    val results =
//                        speechData?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
//                    if (!results.isNullOrEmpty()) {
//                        val transcribedText = results[0]
//                        Log.d("SpeechRecognizer", "Texto Transcrito: $transcribedText")
//
//                        // Agora que temos o texto, enviamos para o servidor
//
//                         sendQuestionToServer(transcribedText)
//                    }
//                } else {
//                    // O usuário cancelou ou ocorreu um erro
//                    Log.d("SpeechRecognizer", "Reconhecimento de voz cancelado ou falhou.")
//                }
//            }
    }


    private fun toggleDetection() {
        isDetectionRunning = !isDetectionRunning

        if (isDetectionRunning) {
            webSocketManager.ensureConnection()
            detectionButton.text = "Stop Detection"
        } else {
            detectionButton.text = "Detect"
// Clean the screen
            runOnUiThread {
                overlayManager.clear()
                stage1Button.visibility = View.GONE
                isStage1Detected = false
            }

        }

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

        println("capture this bitmap: $capturedBitmapForQuestion")
        VuzixSpeechClient.TriggerVoiceAudio(this, true)
        showFeedbackText("Please say 'describe scene'")
    }

    override fun onDetectionsReceived(detections: List<DetectionResult>) {
        runOnUiThread {
            if (isDetectionRunning) {
                handleStationLogic(detections)
                overlayManager.draw(detections, labels)
            }
        }
    }

    private fun showLlmAnswerDialog(answer: String) {
        AlertDialog.Builder(this)
            .setTitle("AI Assistant")
            .setMessage(answer)
            .setPositiveButton("OK") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }


    override fun onLLMAnswerReceived(answer: String) {
        println("llm answer received: $answer")
        runOnUiThread {
            loadingIndicator.visibility = View.GONE
            showLlmAnswerDialog(answer)
        }
    }

    override fun onConnectionFailed() {
        println("isDetectionRunning ${isDetectionRunning}")
        runOnUiThread {
            Toast.makeText(this, "Connection Failed", Toast.LENGTH_SHORT).show()
            if (isDetectionRunning) toggleDetection()
        }
    }

    private fun handleStationLogic(detections: List<DetectionResult>) {
        val requiredLabels = setOf("cell phone", "cup") // <==
        val detectedLabels = detections.map { detection ->
            labels.getOrElse(detection.classId) { "unknown" }
        }.toSet()

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
        webSocketManager.stop()
        voiceCommandManager?.unregister()
        confirmationSoundPlayer?.release()
        if (::cameraDevice.isInitialized) cameraDevice.close()
        handlerThread.quitSafely()
    }

    override fun onResume() {
        super.onResume()
        // This is called every time your app becomes the active screen.
        // tells the voice manager to start listening.
        voiceCommandManager?.register()
    }

    override fun onPause() {
        super.onPause()
        // This is called every time your app goes into the background.
        // tells the voice manager to stop listening to save battery.
        voiceCommandManager?.unregister()
    }

    private fun getPermission() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
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

    override fun onVoiceCommand(phrase: String) {
        webSocketManager.ensureConnection()
        Log.d("MainActivity", "Voice command received from manager: $phrase")
        if (phrase.equals("describe_scene", ignoreCase = true)) {
            val bitmap = capturedBitmapForQuestion
            println("bit map is null? : ${bitmap == null}")
            if (bitmap != null) {
                loadingIndicator.visibility = View.VISIBLE
                showFeedbackText("Analyzing image...", 5000)
                val question = "Describe in detail what you see in this image."
                webSocketManager.sendQuestion(bitmap, question)
            } else {
                showFeedbackText("Error: Image was not captured.")
            }
        } else if (phrase.equals("ok", ignoreCase = true)) {
            Log.d("MainActivity", "OK received")
        } else {
            Log.d("MainActivity", "Unknown voice command: $phrase")
        }
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
            if (isDetectionRunning && !isProcessingFrame) {
                isProcessingFrame = true
                val bitmap = textureView.bitmap ?: run {
                    isProcessingFrame = false
                    return
                }

                webSocketManager.sendDetectionImage(bitmap)
                isProcessingFrame = false

            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {

        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        cameraManager.openCamera( cameraManager.cameraIdList[0], object : CameraDevice.StateCallback() {
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
                        }, handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {}
                override fun onError(camera: CameraDevice, error: Int) {}
            }, handler
        )
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
}