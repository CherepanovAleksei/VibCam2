package com.mrneumann.vibcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View

class DrawingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var haveTouch = false
    lateinit var touchArea: Rect
    var paint = Paint()

    init {
        paint.color = Color.RED
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (2).toFloat()
        haveTouch = false
    }
    fun setHaveTouch(touch:Boolean, rect: Rect){
        haveTouch = touch
        touchArea = rect
    }
    fun draw() {

    }

    override fun onDraw(canvas: Canvas) {
        if(haveTouch){
            canvas.drawRect(
                    touchArea.left.toFloat(),
                    touchArea.top.toFloat(),
                    touchArea.right.toFloat(),
                    touchArea.bottom.toFloat(),
                    paint
            )
        }
    }
}