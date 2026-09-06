package com.cotrix.funkedit.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Генерация титулов/аутро/водяного знака в стиле эдита. */
object TextArt {

    private fun heavyPaint(size: Float): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.ITALIC)
        textSize = size
        textAlign = Paint.Align.CENTER
    }

    fun intro(title: String): Bitmap {
        val w = 1080; val h = 460
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = heavyPaint(200f)
        val tw = p.measureText(title)
        if (tw > w * 0.92f) p.textSize = p.textSize * w * 0.92f / tw
        val x = w / 2f
        val y = h / 2f + p.textSize * 0.35f

        // мягкая серая тень
        p.color = Color.argb(110, 120, 120, 120)
        p.maskFilter = BlurMaskFilter(30f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(title, x, y + 12f, p)
        p.maskFilter = null
        // контур
        p.color = Color.argb(255, 185, 190, 198)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 9f
        c.drawText(title, x, y, p)
        // белая заливка
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        c.drawText(title, x, y, p)
        return b
    }

    fun outro(name: String): Bitmap {
        val w = 1080; val h = 640
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)

        val pTop = heavyPaint(88f)
        pTop.color = Color.WHITE
        c.drawText("EDITED BY", w / 2f, h / 2f - 70f, pTop)

        val p = heavyPaint(170f)
        val tw = p.measureText(name)
        if (tw > w * 0.9f) p.textSize = p.textSize * w * 0.9f / tw
        // синее свечение
        p.color = Color.argb(200, 57, 198, 255)
        p.maskFilter = BlurMaskFilter(40f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(name, w / 2f, h / 2f + 110f, p)
        p.maskFilter = null
        p.color = Color.rgb(57, 198, 255)
        c.drawText(name, w / 2f, h / 2f + 110f, p)
        return b
    }

    fun watermark(name: String): Bitmap {
        val w = 640; val h = 160
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = heavyPaint(84f)
        val tw = p.measureText(name)
        if (tw > w * 0.9f) p.textSize = p.textSize * w * 0.9f / tw
        p.color = Color.BLACK
        p.maskFilter = BlurMaskFilter(10f, BlurMaskFilter.Blur.NORMAL)
        c.drawText(name, w / 2f, h / 2f + 30f, p)
        p.maskFilter = null
        p.color = Color.WHITE
        c.drawText(name, w / 2f, h / 2f + 30f, p)
        return b
    }
}
