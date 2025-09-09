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
        val bitmapArr = bitmapToByteArray(bitmap)
        val requestJson = JSONObject().apply {
            put("type", "detection")
            put("image", Base64.encodeToString(bitmapArr, Base64.NO_WRAP))
        }
        webSocket?.send(requestJson.toString())
    }

    fun sendAudioQuestion(bitmap: Bitmap, audioBytes: ByteArray) {
        val imageBase64 = Base64.encodeToString(bitmapToByteArray(bitmap), Base64.NO_WRAP)
        val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val requestJson = JSONObject().apply {
            put("type", "audio_question")
            put("image", imageBase64)
            put("audio", audioBase64)
        }

        print(audioBase64)
        println(requestJson)

        webSocket?.send(requestJson.toString())
    }

    fun sendQuestion(bitmap: Bitmap, question: String) {
        Log.d("WebSocketManager", "Sending question: $question")
        val bitmapArr = bitmapToByteArray(bitmap)
        val imageBase64 = Base64.encodeToString(bitmapArr, Base64.NO_WRAP)

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
                        listener.onDetectionsReceived(detections)
                    }
                    "llm_answer" -> {
                        val answer = json.getString("text")
                        listener.onLLMAnswerReceived(answer)
                    }
                }
            } catch (e: Exception) {
                Log.e("WebSocketManager", "Erro no parsing do JSON: ${e.message}")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e("WebSocketManager", "Connection Failed: ${t.message}")
            listener.onConnectionFailed()
        }
    }

    private fun bitmapToByteArray(bitmap: Bitmap, quality: Int = 90): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

}
