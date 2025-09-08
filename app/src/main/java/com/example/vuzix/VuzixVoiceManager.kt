package com.example.vuzix
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import com.vuzix.sdk.speechrecognitionservice.VuzixSpeechClient


class VuzixVoiceManager(private val activity: Activity, private val listener: VoiceCommandListener) {
    private var voiceCommandReceiver: VoiceCommandReceiver? = null


    init {
        try {
            // connect and try to register phrases
            val vuzixSpeechClient = VuzixSpeechClient(activity)
            vuzixSpeechClient.insertPhrase("describe scene")
            vuzixSpeechClient.insertPhrase("ok")
            Log.d("VuzixVoiceManager", "Phrases successfully registered")
        } catch (e: Exception) {
            Log.e("VuzixVoiceManager", "Error while registering phrases: ${e.message}")
        }
    }

    fun register() {
        if(voiceCommandReceiver == null){
            voiceCommandReceiver = VoiceCommandReceiver()
            val filter = IntentFilter(VuzixSpeechClient.ACTION_VOICE_COMMAND)
            activity.registerReceiver(voiceCommandReceiver, filter, Activity.RECEIVER_NOT_EXPORTED)
            Log.d("VuzixVoiceManager", "Receiver registered")
        }
    }

    fun unregister() {
        voiceCommandReceiver?.let {
            activity.unregisterReceiver(it)
            voiceCommandReceiver = null
            Log.d("VuzixVoiceManager", "Receiver desregistrado.")
        }
    }



    private inner class VoiceCommandReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.action == VuzixSpeechClient.ACTION_VOICE_COMMAND) {
                    val phrase = it.getStringExtra(VuzixSpeechClient.PHRASE_STRING_EXTRA)
                    phrase?.let { recognizedPhrase ->
                        listener.onVoiceCommand(recognizedPhrase)
                    }
                }
            }
        }
    }


}