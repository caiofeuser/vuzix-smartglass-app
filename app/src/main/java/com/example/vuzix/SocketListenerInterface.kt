package com.example.vuzix

interface SocketListenerInterface {
    fun onDetectionsReceived(detection: List<DetectionResult>)
    fun onLLMAnswerReceived(answer: String)
    fun onConnectionFailed()
}