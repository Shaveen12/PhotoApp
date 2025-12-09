package com.example.photoapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0xFFFF0000.toInt()  // red
    }

    // Rects already mapped to view coordinates
    private val faceRects = mutableListOf<RectF>()

    fun setFaces(rects: List<RectF>) {
        synchronized(faceRects) {
            faceRects.clear()
            faceRects.addAll(rects)
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(faceRects) {
            for (rect in faceRects) {
                canvas.drawRect(rect, paint)
            }
        }
    }
}