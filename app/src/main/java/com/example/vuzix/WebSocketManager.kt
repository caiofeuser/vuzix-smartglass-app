package com.example.vuzix
import android.graphics.*
import android.util.Base64
import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
class WebSocketManager(private val listener: SocketListenerInterface) {
    private val okHttpClient = OkHttpClient()
    private var webSocket: WebSocket? = null

    fun stop() {
        webSocket?.close(1000, "Activity Closing")
        webSocket = null
    }
    fun start() {
        Log.e("WebSocket", "Connection Selected")
        val ipAddress = "172.20.10.3"
        val request = Request.Builder().url("ws://$ipAddress:8000/ws").build()
        webSocket = okHttpClient.newWebSocket(request, SocketListener())
    }

    fun sendDetectionImage(bitmap: Bitmap) {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, stream)
        val requestJson = JSONObject().apply {
            put("type", "detection")
            put("image", Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))
        }
        webSocket?.send(requestJson.toString())
    }
    fun sendQuestion(bitmap: Bitmap, question: String = "Describe the image") {
        Log.d("WebSocketManager", "Sending question: $question")
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        val imageBase64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put("type", "question")
            put("image", imageBase64)
            put("text", question)
        }
        webSocket?.send(requestJson.toString())
    }

    fun ensureConnection() {
        if (webSocket == null) {
            start()
        }
    }
    private fun parseDetections(json: JSONObject): List<DetectionResult> {
        val newDetections = mutableListOf<DetectionResult>()
        try {
            val jsonDetections = json.getJSONObject("detections")
            val boxes = jsonDetections.getJSONArray("boxes")
            val classes = jsonDetections.getJSONArray("classes")

            for (i in 0 until boxes.length()) {
                val boxArray = boxes.getJSONArray(i)
                val box = RectF(
                    boxArray.getDouble(0).toFloat(), boxArray.getDouble(1).toFloat(),
                    boxArray.getDouble(2).toFloat(), boxArray.getDouble(3).toFloat()
                )
                newDetections.add(DetectionResult(box, classes.getInt(i)))
            }
        } catch (e: Exception) {
            Log.e("WebSocketManager", "Erro no parseDetections: ${e.message}")
        }
        return newDetections
    }

    inner class SocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d("WebSocketManager", "Connected with the server")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val json = JSONObject(text)
                when (json.getString("type")) {
                    "detection_results" -> {
                        val detections = parseDetections(json)
                        listener.onDetectionsReceived(detections) // CALL THE CALLBACK
                    }
                    "llm_answer" -> {
                        val answer = json.getString("text")
                        listener.onLLMAnswerReceived(answer) // CALL THE CALLBACK
                    }
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Erro no parsing do JSON: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocketManager", "Falha na conexão: ${t.message}")
            listener.onConnectionFailed() // CALL THE CALLBACK
        }
    }

}
