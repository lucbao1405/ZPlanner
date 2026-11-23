package com.example.zplanner

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class AvatarCropView(context: Context, attrs: AttributeSet) : View(context, attrs) {
    private lateinit var bitmap: Bitmap
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val circle = Path()
    private val imageBounds = RectF()
    private var zoom = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val cropSize get() = min(bitmap.width, bitmap.height) / zoom

    fun setBitmap(image: Bitmap) {
        bitmap = image
        centerX = image.width / 2f
        centerY = image.height / 2f
        invalidate()
    }

    fun setZoom(value: Float) {
        zoom = value
        constrainCenter()
    }

    private fun constrainCenter() {
        val half = cropSize / 2f
        centerX = centerX.coerceIn(half, bitmap.width - half)
        centerY = centerY.coerceIn(half, bitmap.height - half)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (::bitmap.isInitialized) drawCrop(canvas, min(width, height).toFloat())
    }

    fun renderAvatar(): Bitmap {
        val result = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
        drawCrop(Canvas(result), result.width.toFloat(), clipCircle = false)
        return result
    }

    private fun drawCrop(canvas: Canvas, size: Float, clipCircle: Boolean = true) {
        val scale = size / cropSize
        val left = size / 2f - centerX * scale
        val top = size / 2f - centerY * scale
        imageBounds.set(left, top, left + bitmap.width * scale, top + bitmap.height * scale)
        val state = canvas.save()
        if (clipCircle) {
            circle.rewind()
            circle.addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            canvas.clipPath(circle)
        }
        canvas.drawBitmap(bitmap, null, imageBounds, paint)
        canvas.restoreToCount(state)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                moveImage(event.x - lastX, event.y - lastY)
                lastX = event.x
                lastY = event.y
            }
            MotionEvent.ACTION_UP -> performClick()
            MotionEvent.ACTION_CANCEL -> Unit
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = width / 20f
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> moveImage(-step, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveImage(step, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> moveImage(0f, -step)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveImage(0f, step)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun moveImage(dx: Float, dy: Float) {
        val unitsPerPixel = cropSize / min(width, height)
        centerX -= dx * unitsPerPixel
        centerY -= dy * unitsPerPixel
        constrainCenter()
    }
}

internal fun loadAvatarBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
    val maxSize = 2048
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
            val scale = maxSize.toFloat() / maxOf(info.size.width, info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    options.inJustDecodeBounds = false
    options.inSampleSize = 1
    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > maxSize) {
        options.inSampleSize *= 2
    }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
    val orientation = runCatching {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull()
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(270f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(270f)
        }
    }
    return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
