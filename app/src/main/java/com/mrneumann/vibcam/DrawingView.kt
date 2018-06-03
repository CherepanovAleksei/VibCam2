package com.mrneumann.vibcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class DrawingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var haveTouch = false
    var touchArea = Rect(0, 0, 0, 0)
    var paint = Paint()

    init {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2F
        haveTouch = false
    }

    fun setHaveTouch(rect: Rect, color: Int) {
        touchArea = rect
        paint.color = color
    }

    fun setColor(newColor: Int) {
        paint.color = newColor
    }

    override fun onDraw(canvas: Canvas) {
            canvas.drawRect(
                    touchArea.left.toFloat(),
                    touchArea.top.toFloat(),
                    touchArea.right.toFloat(),
                    touchArea.bottom.toFloat(),
                    paint
            )
//        }
    }
}