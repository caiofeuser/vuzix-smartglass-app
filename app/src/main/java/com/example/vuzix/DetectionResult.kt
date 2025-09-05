package com.example.vuzix
import android.graphics.RectF

data class DetectionResult(val box: RectF, val classId: Int)
