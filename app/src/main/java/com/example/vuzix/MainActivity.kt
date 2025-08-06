package com.example.vuzix // Or your actual package name

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity // Correct import

class MainActivity : AppCompatActivity() { // Correctly extending AppCompatActivity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Ensure activity_main.xml exists in res/layout
    }
}