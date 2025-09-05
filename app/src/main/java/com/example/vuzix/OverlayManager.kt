package com.example.vuzix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.ImageView

class OverlayManager (private val imageView: ImageView){
    private val paint = Paint().apply {
        textSize = 40f
        strokeWidth = 3f
    }

    private val colors = listOf(Color.BLUE, Color.GREEN, Color.RED, Color.CYAN, Color.MAGENTA)

    fun draw(detectionResult: List<DetectionResult>, labels: List<String>){
        if (imageView.width == 0 || imageView.height == 0 ) return

        val bitmap = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        detectionResult.forEachIndexed { index, detection ->
            paint.color = colors[index % colors.size]
            paint.style = Paint.Style.STROKE

            // Denormalize coordinates for the screen
            val rect = RectF(
                detection.box.left * canvas.width,
                detection.box.top * canvas.height,
                detection.box.right * canvas.width,
                detection.box.bottom * canvas.height
            )
            canvas.drawRect(rect, paint)

            val label = labels.getOrElse(detection.classId) { "unknown" }

            paint.style = Paint.Style.FILL
            canvas.drawText(label, rect.left + 10, rect.top + 30, paint)
        }
        imageView.setImageBitmap(bitmap)
    }

    fun clear() {
        if (imageView.width > 0 && imageView.height > 0) {
            val bitmap = Bitmap.createBitmap(imageView.width, imageView.height, Bitmap.Config.ARGB_8888)
            imageView.setImageBitmap(bitmap)
        }
    }


}