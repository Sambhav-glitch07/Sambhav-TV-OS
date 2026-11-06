package com.sambhav.tvos

import android.animation.ValueAnimator
import android.app.Activity
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// =====================================================================================
//  Sambhav TV OS  -  wallpaper + glass dock launcher for Android TV (API 28+)
//  Native Views only. Every surface is drawn with Canvas. No WebView, Compose or
//  third party libraries.
//
//  Optional: drop your own picture at app/src/main/res/drawable-nodpi/wallpaper.jpg
//  and it is used automatically instead of the built in sky scene.
// =====================================================================================

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val TILE_BLEED = 26f
private val TAU = (2.0 * PI).toFloat()
private val SPOTIFY_GREEN = 0xFF1ED760.toInt()

// =====================================================================================
//  SECTION 1 - Design tokens, glass engine, glyphs, discovery, focus engine
// =====================================================================================

object Fonts {
    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

object Palette {
    val black = 0xFF040406.toInt()
    val charcoal = 0xFF0E0F13.toInt()
    val silver = 0xFF8C96AB.toInt()

    fun secondary(accent: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(accent, hsv)
        hsv[0] = (hsv[0] + 32f) % 360f
        hsv[1] = min(1f, hsv[1] * 0.9f)
        hsv[2] = max(0.42f, hsv[2] * 0.68f)
        return Color.HSVToColor(hsv)
    }

    fun darken(color: Int, amount: Float): Int = Ui.lerpColor(color or (0xFF shl 24), Color.BLACK, amount)

    fun lighten(color: Int, amount: Float): Int = Ui.lerpColor(color or (0xFF shl 24), Color.WHITE, amount)
}

object Ui {
    private var unit = 1f

    /** All layout values are authored for a 1920x1080 canvas and scaled to the real display height. */
    fun init(context: Context) {
        unit = context.resources.displayMetrics.heightPixels / 1080f
    }

    fun s(v: Float): Float = v * unit
    fun s(v: Int): Float = v * unit
    fun px(v: Int): Int = max(1, (v * unit).roundToInt())

    fun alpha(color: Int, a: Float): Int =
        (color and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f).roundToInt() shl 24)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * f).roundToInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).roundToInt()
        )
    }

    fun textPaint(sizePx: Float, color: Int, face: Typeface, letterSpacing: Float = 0f): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        p.textSize = sizePx
        p.color = color
        p.typeface = face
        p.letterSpacing = letterSpacing
        return p
    }

    fun baseline(paint: Paint, centerY: Float): Float = centerY - (paint.ascent() + paint.descent()) / 2f

    fun makeText(
        context: Context, text: String, sizePx: Float, color: Int, face: Typeface, spacing: Float = 0f
    ): TextView {
        val tv = TextView(context)
        tv.text = text
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        tv.setTextColor(color)
        tv.typeface = face
        tv.letterSpacing = spacing
        tv.includeFontPadding = false
        return tv
    }
}

fun View.findFocusGlass(predicate: (FocusGlassView) -> Boolean): FocusGlassView? {
    if (this is FocusGlassView && predicate(this)) return this
    if (this is ViewGroup) {
        for (i in 0 until childCount) {
            val found = getChildAt(i).findFocusGlass(predicate)
            if (found != null) return found
        }
    }
    return null
}

object BitmapUtil {
    private val srcRect = Rect()

    /** Center-crop draw (UI thread only). */
    fun drawCropped(c: Canvas, bmp: Bitmap, dst: RectF, paint: Paint) {
        val bw = bmp.width
        val bh = bmp.height
        val dstRatio = dst.width() / dst.height()
        val srcRatio = bw.toFloat() / bh.toFloat()
        if (srcRatio > dstRatio) {
            val nw = (bh * dstRatio).toInt()
            val l = (bw - nw) / 2
            srcRect.set(l, 0, l + nw, bh)
        } else {
            val nh = (bw / dstRatio).toInt()
            val t = (bh - nh) / 2
            srcRect.set(0, t, bw, t + nh)
        }
        c.drawBitmap(bmp, srcRect, dst, paint)
    }

    fun fromDrawable(source: Drawable, w: Int, h: Int): Bitmap {
        val d = source.constantState?.newDrawable()?.mutate() ?: source
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, w, h)
        d.draw(canvas)
        return bmp
    }

    /** Saturation weighted dominant hue, normalised so it always works as a light colour. */
    fun dominantColor(bmp: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bmp, 20, 20, true)
        val pixels = IntArray(400)
        small.getPixels(pixels, 0, 20, 0, 0, 20, 20)
        if (small !== bmp) small.recycle()
        val hsv = FloatArray(3)
        var sumX = 0f
        var sumY = 0f
        var sumS = 0f
        var sumV = 0f
        var total = 0f
        for (p in pixels) {
            val a = Color.alpha(p) / 255f
            if (a < 0.5f) continue
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.15f) continue
            val weight = a * hsv[1] * hsv[1] * (0.3f + hsv[2])
            val rad = Math.toRadians(hsv[0].toDouble())
            sumX += (cos(rad) * weight).toFloat()
            sumY += (sin(rad) * weight).toFloat()
            sumS += hsv[1] * weight
            sumV += hsv[2] * weight
            total += weight
        }
        if (total < 0.4f) return Palette.silver
        val hue = ((Math.toDegrees(atan2(sumY.toDouble(), sumX.toDouble())) + 360.0) % 360.0).toFloat()
        hsv[0] = hue
        hsv[1] = (sumS / total).coerceIn(0.45f, 0.9f)
        hsv[2] = (sumV / total).coerceIn(0.55f, 0.95f)
        return Color.HSVToColor(hsv)
    }
}

// =====================================================================================
//  SECTION 2 - Glass rendering engine
// =====================================================================================

object GrainTexture {
    private var cached: BitmapShader? = null

    fun shader(): BitmapShader {
        cached?.let { return it }
        val size = 96
        val px = IntArray(size * size)
        val rnd = java.util.Random(7L)
        for (i in px.indices) {
            val v = 128 + rnd.nextInt(128)
            px[i] = Color.argb(rnd.nextInt(22), v, v, v)
        }
        val bmp = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        cached = shader
        return shader
    }
}

/**
 * Layered glass: soft shadow, translucent body, accent bloom, grain, diagonal specular sheen,
 * graduated rim light and a focus lift. Rendering is split into base and gloss so media can sit
 * between the two layers, exactly like content under a glass pane.
 */
class GlassSurface(private val tone: Tone) {
    enum class Tone { SMOKED, FROST, BRIGHT }

    val rect = RectF()
    var shadowEnabled = true
    private var radius = 0f
    private var accent = 0
    private val tmp = RectF()
    private val clipPath = Path()
    private val sheenPath = Path()
    private val underPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val grainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val liftPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        underPaint.color = Color.argb(160, 10, 11, 15)
        grainPaint.shader = GrainTexture.shader()
        edgePaint.style = Paint.Style.STROKE
        innerPaint.style = Paint.Style.STROKE
        innerPaint.color = Color.argb(26, 255, 255, 255)
        ringPaint.style = Paint.Style.STROKE
        ringPaint.color = Color.WHITE
        liftPaint.color = Color.WHITE
        shadowPaint.color = Color.BLACK
    }

    fun layout(l: Float, t: Float, r: Float, b: Float, cornerRadius: Float, accentColor: Int = 0) {
        if (r - l <= 0f || b - t <= 0f) return
        rect.set(l, t, r, b)
        radius = cornerRadius
        accent = accentColor
        val w = r - l
        val h = b - t
        clipPath.reset()
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)

        edgePaint.strokeWidth = max(1.2f, Ui.s(1.6f))
        innerPaint.strokeWidth = max(1f, Ui.s(1f))
        ringPaint.strokeWidth = max(2f, Ui.s(2.5f))

        fillPaint.shader = when (tone) {
            Tone.SMOKED -> LinearGradient(
                0f, t, 0f, b, Color.argb(206, 36, 39, 47), Color.argb(228, 14, 15, 19), Shader.TileMode.CLAMP
            )
            Tone.FROST -> LinearGradient(
                0f, t, 0f, b, Color.argb(98, 255, 255, 255), Color.argb(46, 222, 230, 245), Shader.TileMode.CLAMP
            )
            Tone.BRIGHT -> LinearGradient(
                0f, t, 0f, b, Color.argb(244, 252, 252, 254), Color.argb(226, 212, 218, 229), Shader.TileMode.CLAMP
            )
        }

        sheenPath.reset()
        sheenPath.moveTo(l, t)
        sheenPath.lineTo(l + w * 0.70f, t)
        sheenPath.lineTo(l + w * 0.26f, b)
        sheenPath.lineTo(l, b)
        sheenPath.close()
        val sheenAlpha = if (tone == Tone.BRIGHT) 40 else 46
        sheenPaint.shader = LinearGradient(
            l, t, l + w * 0.55f, t + h * 0.75f,
            Color.argb(sheenAlpha, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
        )

        edgePaint.shader = LinearGradient(
            0f, t, 0f, b,
            intArrayOf(Color.argb(176, 255, 255, 255), Color.argb(34, 255, 255, 255), Color.argb(92, 255, 255, 255)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )

        if (accent != 0) {
            glowPaint.shader = RadialGradient(
                l + w * 0.30f, t + h * 0.18f, max(w, h) * 0.95f,
                intArrayOf(Ui.alpha(accent, 0.44f), Ui.alpha(accent, 0.14f), Ui.alpha(accent, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
            )
        }
    }

    fun drawBase(c: Canvas, focus: Float, fillAlpha: Int = 255) {
        if (rect.width() <= 0f) return
        if (shadowEnabled) drawShadow(c, focus)
        if (tone == Tone.FROST) c.drawRoundRect(rect, radius, radius, underPaint)
        fillPaint.alpha = fillAlpha
        c.drawRoundRect(rect, radius, radius, fillPaint)
        if (accent != 0) {
            c.save()
            c.clipPath(clipPath)
            c.drawRect(rect, glowPaint)
            c.restore()
        }
    }

    fun drawGloss(c: Canvas, focus: Float) {
        if (rect.width() <= 0f) return
        c.save()
        c.clipPath(clipPath)
        c.drawRect(rect, grainPaint)
        c.drawPath(sheenPath, sheenPaint)
        if (focus > 0f && tone != Tone.BRIGHT) {
            liftPaint.alpha = (focus * 34f).toInt()
            c.drawRect(rect, liftPaint)
        }
        c.restore()
        c.drawRoundRect(rect, radius, radius, edgePaint)
        tmp.set(rect)
        val inset = edgePaint.strokeWidth * 1.4f
        tmp.inset(inset, inset)
        c.drawRoundRect(tmp, max(0f, radius - inset), max(0f, radius - inset), innerPaint)
        if (focus > 0.01f) {
            ringPaint.alpha = (focus * 150f).toInt()
            c.drawRoundRect(rect, radius, radius, ringPaint)
        }
    }

    fun clipTo(c: Canvas) {
        c.clipPath(clipPath)
    }

    private fun drawShadow(c: Canvas, focus: Float) {
        val layers = if (focus > 0.05f) 6 else 4
        val spread = Ui.s(6f + 10f * focus)
        val dy = Ui.s(5f + 9f * focus)
        shadowPaint.alpha = (15f + 14f * focus).toInt()
        for (i in layers downTo 1) {
            val e = spread * i / layers
            tmp.set(rect.left - e, rect.top - e + dy, rect.right + e, rect.bottom + e + dy)
            c.drawRoundRect(tmp, radius + e, radius + e, shadowPaint)
        }
    }
}

// =====================================================================================
//  SECTION 3 - Vector glyphs, brand mark and icon plates
// =====================================================================================

enum class Glyph { PLAY, GRID, INFO, WIFI, DISPLAY, SOUND, BLUETOOTH, CLOCK, SLIDERS, REFRESH, CLEAR, GEAR, INPUT, PREV, NEXT, PAUSE }

object GlyphPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val box = RectF()

    init {
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
    }

    fun draw(c: Canvas, glyph: Glyph, cx: Float, cy: Float, size: Float, color: Int) {
        val h = size / 2f
        paint.color = color
        paint.strokeWidth = max(1.5f, size * 0.085f)
        paint.style = Paint.Style.STROKE
        when (glyph) {
            Glyph.PLAY -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = size * 0.06f
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.78f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.78f)
                path.lineTo(cx + h * 0.82f, cy)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.GRID -> {
                paint.style = Paint.Style.FILL
                val gap = h * 0.14f
                val cell = h * 0.8f
                for (i in 0..1) for (j in 0..1) {
                    val l = if (i == 0) cx - gap - cell else cx + gap
                    val t = if (j == 0) cy - gap - cell else cy + gap
                    box.set(l, t, l + cell, t + cell)
                    c.drawRoundRect(box, cell * 0.28f, cell * 0.28f, paint)
                }
            }
            Glyph.INFO -> {
                c.drawCircle(cx, cy, h * 0.95f, paint)
                c.drawLine(cx, cy - h * 0.05f, cx, cy + h * 0.5f, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, cy - h * 0.42f, size * 0.05f, paint)
            }
            Glyph.WIFI -> {
                val oy = cy + h * 0.55f
                for (k in 1..3) {
                    val r = h * 0.42f * k
                    box.set(cx - r, oy - r, cx + r, oy + r)
                    c.drawArc(box, 225f, 90f, false, paint)
                }
                paint.style = Paint.Style.FILL
                c.drawCircle(cx, oy, size * 0.055f, paint)
            }
            Glyph.DISPLAY -> {
                box.set(cx - h, cy - h * 0.72f, cx + h, cy + h * 0.38f)
                c.drawRoundRect(box, h * 0.16f, h * 0.16f, paint)
                c.drawLine(cx, cy + h * 0.38f, cx, cy + h * 0.78f, paint)
                c.drawLine(cx - h * 0.42f, cy + h * 0.8f, cx + h * 0.42f, cy + h * 0.8f, paint)
            }
            Glyph.SOUND -> {
                path.reset()
                path.moveTo(cx - h * 0.95f, cy - h * 0.3f)
                path.lineTo(cx - h * 0.5f, cy - h * 0.3f)
                path.lineTo(cx - h * 0.02f, cy - h * 0.75f)
                path.lineTo(cx - h * 0.02f, cy + h * 0.75f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.3f)
                path.lineTo(cx - h * 0.95f, cy + h * 0.3f)
                path.close()
                c.drawPath(path, paint)
                val ax = cx - h * 0.02f
                for (k in 1..2) {
                    val r = h * 0.5f * k
                    box.set(ax - r, cy - r, ax + r, cy + r)
                    c.drawArc(box, -40f, 80f, false, paint)
                }
            }
            Glyph.BLUETOOTH -> {
                path.reset()
                path.moveTo(cx - h * 0.5f, cy - h * 0.5f)
                path.lineTo(cx + h * 0.5f, cy + h * 0.5f)
                path.lineTo(cx, cy + h * 0.95f)
                path.lineTo(cx, cy - h * 0.95f)
                path.lineTo(cx + h * 0.5f, cy - h * 0.5f)
                path.lineTo(cx - h * 0.5f, cy + h * 0.5f)
                c.drawPath(path, paint)
            }
            Glyph.CLOCK -> {
                c.drawCircle(cx, cy, h * 0.95f, paint)
                c.drawLine(cx, cy, cx, cy - h * 0.55f, paint)
                c.drawLine(cx, cy, cx + h * 0.4f, cy + h * 0.2f, paint)
            }
            Glyph.SLIDERS -> {
                val knobs = floatArrayOf(-0.35f, 0.4f, -0.05f)
                for (k in -1..1) {
                    val y = cy + k * h * 0.62f
                    paint.style = Paint.Style.STROKE
                    c.drawLine(cx - h * 0.95f, y, cx + h * 0.95f, y, paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx + knobs[k + 1] * h, y, size * 0.075f, paint)
                }
            }
            Glyph.REFRESH -> {
                val r = h * 0.78f
                box.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(box, 40f, 280f, false, paint)
                val ang = Math.toRadians(320.0)
                val px = cx + (r * cos(ang)).toFloat()
                val py = cy + (r * sin(ang)).toFloat()
                val tx = (-sin(ang)).toFloat()
                val ty = cos(ang).toFloat()
                val nx = cos(ang).toFloat()
                val ny = sin(ang).toFloat()
                val a = h * 0.42f
                val b = h * 0.34f
                paint.style = Paint.Style.FILL
                path.reset()
                path.moveTo(px + tx * a, py + ty * a)
                path.lineTo(px + nx * b, py + ny * b)
                path.lineTo(px - nx * b, py - ny * b)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.CLEAR -> {
                c.drawCircle(cx, cy, h * 0.95f, paint)
                c.drawLine(cx - h * 0.38f, cy - h * 0.38f, cx + h * 0.38f, cy + h * 0.38f, paint)
                c.drawLine(cx - h * 0.38f, cy + h * 0.38f, cx + h * 0.38f, cy - h * 0.38f, paint)
            }
            Glyph.GEAR -> {
                c.drawCircle(cx, cy, h * 0.34f, paint)
                c.drawCircle(cx, cy, h * 0.66f, paint)
                paint.strokeWidth = max(2f, size * 0.12f)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    c.drawLine(
                        cx + (h * 0.72 * cos(a)).toFloat(), cy + (h * 0.72 * sin(a)).toFloat(),
                        cx + (h * 0.98 * cos(a)).toFloat(), cy + (h * 0.98 * sin(a)).toFloat(), paint
                    )
                }
            }
            Glyph.INPUT -> {
                box.set(cx - h * 0.95f, cy - h * 0.72f, cx + h * 0.95f, cy + h * 0.5f)
                c.drawRoundRect(box, h * 0.16f, h * 0.16f, paint)
                c.drawLine(cx - h * 0.4f, cy + h * 0.85f, cx + h * 0.4f, cy + h * 0.85f, paint)
                paint.style = Paint.Style.FILL
                path.reset()
                path.moveTo(cx - h * 0.2f, cy - h * 0.4f)
                path.lineTo(cx - h * 0.2f, cy + h * 0.18f)
                path.lineTo(cx + h * 0.32f, cy - h * 0.11f)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.PREV -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = size * 0.05f
                c.drawLine(cx - h * 0.62f, cy - h * 0.62f, cx - h * 0.62f, cy + h * 0.62f, paint)
                path.reset()
                path.moveTo(cx + h * 0.66f, cy - h * 0.66f)
                path.lineTo(cx + h * 0.66f, cy + h * 0.66f)
                path.lineTo(cx - h * 0.34f, cy)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.NEXT -> {
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = size * 0.05f
                c.drawLine(cx + h * 0.62f, cy - h * 0.62f, cx + h * 0.62f, cy + h * 0.62f, paint)
                path.reset()
                path.moveTo(cx - h * 0.66f, cy - h * 0.66f)
                path.lineTo(cx - h * 0.66f, cy + h * 0.66f)
                path.lineTo(cx + h * 0.34f, cy)
                path.close()
                c.drawPath(path, paint)
            }
            Glyph.PAUSE -> {
                paint.style = Paint.Style.FILL
                for (side in intArrayOf(-1, 1)) {
                    val l = if (side < 0) cx - h * 0.62f else cx + h * 0.2f
                    box.set(l, cy - h * 0.7f, l + h * 0.42f, cy + h * 0.7f)
                    c.drawRoundRect(box, h * 0.1f, h * 0.1f, paint)
                }
            }
        }
    }
}

object BrandMark {
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()

    init {
        ringPaint.style = Paint.Style.STROKE
    }

    fun draw(c: Canvas, cx: Float, cy: Float, radius: Float) {
        ringPaint.strokeWidth = max(1.2f, radius * 0.06f)
        ringPaint.color = Color.argb(110, 255, 255, 255)
        c.drawCircle(cx, cy, radius, ringPaint)
        orbPaint.shader = RadialGradient(
            cx - radius * 0.25f, cy - radius * 0.35f, radius * 0.95f,
            intArrayOf(Color.WHITE, Color.rgb(201, 210, 227), Color.rgb(84, 96, 122)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, radius * 0.7f, orbPaint)
        ringPaint.strokeWidth = max(1f, radius * 0.05f)
        ringPaint.color = Color.argb(190, 255, 255, 255)
        val r = radius * 0.86f
        box.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(box, 205f, 100f, false, ringPaint)
    }
}

object IconPlate {
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letterPaint = Ui.textPaint(10f, Color.WHITE, Fonts.medium)
    private val glossShader = LinearGradient(
        0f, 0f, 0f, 1f, Color.argb(74, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
    )
    private val matrix = Matrix()
    private val rect = RectF()
    private val tmp = RectF()
    private val clip = Path()

    init {
        shadowPaint.color = Color.BLACK
        edgePaint.style = Paint.Style.STROKE
        edgePaint.color = Color.argb(86, 255, 255, 255)
        letterPaint.textAlign = Paint.Align.CENTER
    }

    /** Draws a custom rounded icon plate with gloss and rim light. UI thread only. */
    fun draw(c: Canvas, icon: Bitmap?, initial: String, accent: Int, cx: Float, cy: Float, size: Float) {
        val half = size / 2f
        rect.set(cx - half, cy - half, cx + half, cy + half)
        val radius = size * 0.225f
        shadowPaint.alpha = 34
        for (i in 3 downTo 1) {
            val e = size * 0.025f * i
            tmp.set(rect.left - e, rect.top - e + size * 0.05f, rect.right + e, rect.bottom + e + size * 0.05f)
            c.drawRoundRect(tmp, radius + e, radius + e, shadowPaint)
        }
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        c.save()
        c.clipPath(clip)
        if (icon != null) {
            basePaint.color = Color.rgb(24, 26, 32)
            c.drawRoundRect(rect, radius, radius, basePaint)
            c.drawBitmap(icon, null, rect, bmpPaint)
        } else {
            basePaint.color = Palette.darken(accent, 0.25f)
            c.drawRoundRect(rect, radius, radius, basePaint)
            letterPaint.textSize = size * 0.5f
            c.drawText(initial, cx, Ui.baseline(letterPaint, cy), letterPaint)
        }
        matrix.setScale(1f, size * 0.55f)
        matrix.postTranslate(0f, rect.top)
        glossShader.setLocalMatrix(matrix)
        glossPaint.shader = glossShader
        c.drawRect(rect.left, rect.top, rect.right, rect.top + size * 0.55f, glossPaint)
        c.restore()
        edgePaint.strokeWidth = max(1f, size * 0.012f)
        c.drawRoundRect(rect, radius, radius, edgePaint)
    }
}


enum class AppCategory(val displayName: String, val eyebrow: String) {
    VIDEO("Streaming and Video", "STREAMING"),
    MUSIC("Music and Audio", "MUSIC AND AUDIO"),
    GAME("Games", "GAMES"),
    UTILITY("Utilities and More", "APPLICATION")
}

class AppEntry(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Bitmap?,
    val banner: Bitmap?,
    val accent: Int,
    val category: AppCategory
) {
    val initial: String = label.trim().take(1).uppercase(Locale.getDefault())
}


class AppRepository(private val context: Context) {
    private val iconPx = 192

    /** Blocking. Call from a background thread. */
    fun loadInstalled(): List<AppEntry> {
        val pm = context.packageManager
        val own = context.packageName
        val found = LinkedHashMap<String, ResolveInfo>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val results: List<ResolveInfo> = try {
                pm.queryIntentActivities(query, 0)
            } catch (e: Exception) {
                emptyList()
            }
            for (ri in results) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == own || found.containsKey(pkg)) continue
                found[pkg] = ri
            }
        }
        val entries = ArrayList<AppEntry>()
        for ((pkg, ri) in found) {
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: pkg
            val icon = runCatching { BitmapUtil.fromDrawable(ri.loadIcon(pm), iconPx, iconPx) }.getOrNull()
            val banner = runCatching {
                val d = ri.activityInfo.loadBanner(pm) ?: ri.activityInfo.applicationInfo.loadBanner(pm)
                if (d != null) BitmapUtil.fromDrawable(d, 320, 180) else null
            }.getOrNull()
            val accent = if (icon != null) BitmapUtil.dominantColor(icon) else Palette.silver
            val category = categorize(ri.activityInfo.applicationInfo, pkg, label)
            entries.add(AppEntry(pkg, ri.activityInfo.name, label, icon, banner, accent, category))
        }
        entries.sortBy { it.label.lowercase(Locale.getDefault()) }
        return entries
    }

    private fun categorize(info: ApplicationInfo, pkg: String, label: String): AppCategory {
        when (info.category) {
            ApplicationInfo.CATEGORY_VIDEO -> return AppCategory.VIDEO
            ApplicationInfo.CATEGORY_AUDIO -> return AppCategory.MUSIC
            ApplicationInfo.CATEGORY_GAME -> return AppCategory.GAME
        }
        val key = (pkg + " " + label).lowercase(Locale.getDefault())
        if (VIDEO_HINTS.any { key.contains(it) }) return AppCategory.VIDEO
        if (MUSIC_HINTS.any { key.contains(it) }) return AppCategory.MUSIC
        if (GAME_HINTS.any { key.contains(it) }) return AppCategory.GAME
        return AppCategory.UTILITY
    }

    companion object {
        private val VIDEO_HINTS = listOf(
            "netflix", "hotstar", "youtube", "video", "movie", "plex", "sonyliv", "zee5",
            "jiocinema", "vlc", "cinema", "stream", "primevideo", "livingroom", "disney"
        )
        private val MUSIC_HINTS = listOf("spotify", "music", "audio", "podcast", "radio", "gaana", "saavn", "wynk")
        private val GAME_HINTS = listOf("game", "arcade", "play.games")
    }
}

class RecentsStore(context: Context) {
    private val prefs = context.getSharedPreferences("sambhav_launcher", Context.MODE_PRIVATE)

    fun get(): List<String> =
        (prefs.getString(KEY, "") ?: "").split("|").filter { it.isNotBlank() }

    fun push(pkg: String) {
        val list = ArrayList(get())
        list.remove(pkg)
        list.add(0, pkg)
        while (list.size > MAX) list.removeAt(list.size - 1)
        prefs.edit().putString(KEY, list.joinToString("|")).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        private const val KEY = "recents"
        private const val MAX = 12
    }
}

object AppLauncher {
    /** Uses the leanback launch intent first, then the standard one, then the resolved component. */
    fun launch(activity: Activity, entry: AppEntry): Boolean {
        val pm = activity.packageManager
        var intent: Intent? = try {
            pm.getLeanbackLaunchIntentForPackage(entry.packageName) ?: pm.getLaunchIntentForPackage(entry.packageName)
        } catch (e: Exception) {
            null
        }
        if (intent == null) {
            intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName(entry.packageName, entry.activityName))
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            val options = ActivityOptions.makeCustomAnimation(activity, android.R.anim.fade_in, android.R.anim.fade_out)
            activity.startActivity(intent, options.toBundle())
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    fun openSetting(activity: Activity, actions: List<String>): Boolean {
        val candidates = actions + Settings.ACTION_SETTINGS
        for (name in candidates) {
            try {
                activity.startActivity(Intent(name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next candidate
            } catch (e: SecurityException) {
                // try the next candidate
            }
        }
        return try {
            val fallback = activity.packageManager.getLeanbackLaunchIntentForPackage("com.android.tv.settings")
            if (fallback != null) {
                activity.startActivity(fallback)
                true
            } else {
                false
            }
        } catch (e: RuntimeException) {
            false
        }
    }
}


class FocusDriver(private val view: View, private val maxScale: Float, private val lift: Float) {
    var fraction = 0f
        private set
    private var pressed = false
    private var animator: ValueAnimator? = null

    fun animateTo(target: Float) {
        animator?.cancel()
        val a = ValueAnimator.ofFloat(fraction, target)
        a.duration = if (target > fraction) 240L else 200L
        a.interpolator = DecelerateInterpolator(1.8f)
        a.addUpdateListener {
            fraction = it.animatedValue as Float
            render()
        }
        animator = a
        a.start()
    }

    fun setPressed(value: Boolean) {
        pressed = value
        render()
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    private fun render() {
        var scale = 1f + (maxScale - 1f) * fraction
        if (pressed) scale *= 0.97f
        view.scaleX = scale
        view.scaleY = scale
        view.translationY = -lift * fraction
        view.translationZ = Ui.s(12f) * fraction
        view.invalidate()
    }
}

/** Base for every D-pad focusable glass control. Adds animated focus, edge handling and click support. */
abstract class FocusGlassView(context: Context, maxScale: Float, lift: Float) : View(context) {
    protected val driver = FocusDriver(this, maxScale, lift)
    val focusFraction: Float get() = driver.fraction
    var focusKey: String = ""
    var onFocusGained: (() -> Unit)? = null
    var onEdgeKey: ((Int) -> Boolean)? = null

    init {
        isFocusable = true
        isClickable = true
        isFocusableInTouchMode = false
        setDefaultFocusHighlightEnabled(false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        driver.animateTo(if (gainFocus) 1f else 0f)
        if (gainFocus) onFocusGained?.invoke()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        driver.setPressed(pressed)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (onEdgeKey?.invoke(keyCode) == true) return true
            val row = parent as? LinearLayout
            if (row != null && row.orientation == LinearLayout.HORIZONTAL) {
                val index = row.indexOfChild(this)
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !hasFocusableSibling(row, index, -1)) return true
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !hasFocusableSibling(row, index, 1)) return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun hasFocusableSibling(row: LinearLayout, from: Int, step: Int): Boolean {
        var i = from + step
        while (i >= 0 && i < row.childCount) {
            val v = row.getChildAt(i)
            if (v.isFocusable && v.visibility == View.VISIBLE) return true
            i += step
        }
        return false
    }

    override fun onDetachedFromWindow() {
        driver.cancel()
        super.onDetachedFromWindow()
    }
}


/** Vertical page container. Scrolls whole sections into view and reports offset for parallax. */
class VerticalStage(context: Context) : FrameLayout(context) {
    val column = LinearLayout(context)
    var anchorTop = Ui.s(250f)
    var onScroll: ((Float) -> Unit)? = null
    private var offset = 0f
    private var animator: ValueAnimator? = null
    private var skipAnimation = false

    init {
        clipChildren = false
        clipToPadding = false
        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        addView(column, FrameLayout.LayoutParams(MATCH, WRAP))
    }

    val scrollOffset: Float get() = offset

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        column.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        setMeasuredDimension(w, h)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused != null) reveal(focused)
    }

    private fun reveal(focused: View) {
        var node: View? = focused
        while (node != null && node.parent !== column) node = node.parent as? View
        val row = node ?: return
        val index = column.indexOfChild(row)
        val maxScroll = max(0f, column.measuredHeight - height.toFloat())
        val visibleBottom = height - Ui.s(16f)
        var target = if (index == 0 || row.bottom <= visibleBottom) 0f else row.top - anchorTop
        target = target.coerceIn(0f, maxScroll)
        animateOffset(target)
    }

    fun suppressNextAnimation() {
        skipAnimation = true
    }

    fun scrollToTop() {
        animateOffset(0f)
    }

    fun resetScroll() {
        animator?.cancel()
        applyOffset(0f)
    }

    private fun animateOffset(target: Float) {
        animator?.cancel()
        if (skipAnimation || abs(target - offset) < 1f) {
            skipAnimation = false
            applyOffset(target)
            return
        }
        val a = ValueAnimator.ofFloat(offset, target)
        a.duration = 460L
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener { applyOffset(it.animatedValue as Float) }
        animator = a
        a.start()
    }

    private fun applyOffset(value: Float) {
        offset = value
        column.translationY = -value
        onScroll?.invoke(value)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}


class GlassPanelLayout(
    context: Context,
    tone: GlassSurface.Tone,
    private val cornerRadius: Float
) : LinearLayout(context) {
    private val surface = GlassSurface(tone)

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        surface.shadowEnabled = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(0f, 0f, w.toFloat(), h.toFloat(), min(cornerRadius, h / 2f))
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        surface.drawGloss(canvas, 0f)
        super.onDraw(canvas)
    }
}


enum class TileStyle(val cardW: Int, val cardH: Int, val labelH: Int, val focusScale: Float) {
    LANDSCAPE(300, 169, 46, 1.09f),
    SQUARE(196, 196, 50, 1.10f),
    POSTER(262, 392, 0, 1.07f)
}

class AppTile(
    context: Context,
    private val entry: AppEntry,
    private val tileStyle: TileStyle
) : FocusGlassView(context, tileStyle.focusScale, Ui.s(4f)) {
    private val bleed = Ui.s(TILE_BLEED)
    private val cardW = Ui.s(tileStyle.cardW)
    private val cardH = Ui.s(tileStyle.cardH)
    private val labelH = Ui.s(tileStyle.labelH)
    val tileWidth: Int = (cardW + bleed * 2).roundToInt()
    val tileHeight: Int = heightFor(tileStyle)
    private val surface = GlassSurface(GlassSurface.Tone.SMOKED)
    private val cardRect = RectF()
    private val labelPaint = Ui.textPaint(Ui.s(23f), Color.WHITE, Fonts.medium, 0.005f)
    private val eyebrowPaint = Ui.textPaint(Ui.s(15f), Ui.alpha(Color.WHITE, 0.7f), Fonts.medium, 0.16f)
    private val mediaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var labelText = entry.label

    init {
        contentDescription = entry.label
        dimPaint.color = Color.BLACK
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(tileWidth, tileHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cardRect.set(bleed, bleed, w - bleed, bleed + cardH)
        val radius = Ui.s(if (tileStyle == TileStyle.POSTER) 26f else 22f)
        val glowAccent = if (tileStyle == TileStyle.SQUARE || (tileStyle == TileStyle.LANDSCAPE && entry.banner == null)) {
            entry.accent
        } else {
            0
        }
        surface.layout(cardRect.left, cardRect.top, cardRect.right, cardRect.bottom, radius, glowAccent)
        pivotX = w / 2f
        pivotY = cardRect.centerY()
        val maxText = if (tileStyle == TileStyle.POSTER) cardW - Ui.s(44f) else cardW
        labelText = TextUtils.ellipsize(entry.label, labelPaint, maxText, TextUtils.TruncateAt.END).toString()
        if (tileStyle == TileStyle.POSTER) {
            artPaint.shader = LinearGradient(
                cardRect.left, cardRect.top, cardRect.right, cardRect.bottom,
                intArrayOf(Palette.lighten(entry.accent, 0.08f), entry.accent, Palette.darken(entry.accent, 0.62f)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            scrimPaint.shader = LinearGradient(
                0f, cardRect.top + cardH * 0.52f, 0f, cardRect.bottom,
                Color.argb(0, 0, 0, 0), Color.argb(225, 0, 0, 0), Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        surface.drawBase(canvas, f)
        canvas.save()
        surface.clipTo(canvas)
        when (tileStyle) {
            TileStyle.LANDSCAPE -> drawLandscape(canvas)
            TileStyle.SQUARE -> IconPlate.draw(
                canvas, entry.icon, entry.initial, entry.accent,
                cardRect.centerX(), cardRect.centerY(), cardRect.width() * 0.60f
            )
            TileStyle.POSTER -> drawPoster(canvas)
        }
        dimPaint.alpha = ((1f - f) * 64f).toInt()
        canvas.drawRect(cardRect, dimPaint)
        canvas.restore()
        surface.drawGloss(canvas, f)
        drawLabel(canvas, f)
    }

    private fun drawLandscape(canvas: Canvas) {
        val banner = entry.banner
        if (banner != null) {
            BitmapUtil.drawCropped(canvas, banner, cardRect, mediaPaint)
        } else {
            IconPlate.draw(
                canvas, entry.icon, entry.initial, entry.accent,
                cardRect.centerX(), cardRect.centerY(), cardRect.height() * 0.56f
            )
        }
    }

    private fun drawPoster(canvas: Canvas) {
        canvas.drawRect(cardRect, artPaint)
        IconPlate.draw(
            canvas, entry.icon, entry.initial, entry.accent,
            cardRect.centerX(), cardRect.top + cardH * 0.37f, cardRect.width() * 0.56f
        )
        canvas.drawRect(cardRect, scrimPaint)
    }

    private fun drawLabel(canvas: Canvas, f: Float) {
        if (tileStyle == TileStyle.POSTER) {
            val x = cardRect.left + Ui.s(22f)
            labelPaint.textAlign = Paint.Align.LEFT
            labelPaint.alpha = (255f * (0.82f + 0.18f * f)).toInt()
            canvas.drawText(labelText, x, cardRect.bottom - Ui.s(24f), labelPaint)
            canvas.drawText(entry.category.eyebrow, x, cardRect.bottom - Ui.s(62f), eyebrowPaint)
        } else {
            labelPaint.textAlign = Paint.Align.CENTER
            labelPaint.alpha = (255f * (0.52f + 0.48f * f)).toInt()
            canvas.drawText(labelText, width / 2f, cardRect.bottom + Ui.s(14f) - labelPaint.ascent(), labelPaint)
        }
    }

    companion object {
        fun heightFor(style: TileStyle): Int =
            (Ui.s(style.cardH) + Ui.s(TILE_BLEED) * 2 + Ui.s(style.labelH)).roundToInt()
    }
}


class NoticeView(context: Context) : View(context) {
    private val surface = GlassSurface(GlassSurface.Tone.SMOKED)
    private val paint = Ui.textPaint(Ui.s(26f), Color.WHITE, Fonts.medium)
    private val bleed = Ui.s(30f)
    private val padX = Ui.s(40f)
    private val barHeight = Ui.s(64f)
    private var message = ""
    private val hide = Runnable {
        animate().alpha(0f).translationY(Ui.s(16f)).setDuration(260L).start()
    }

    init {
        alpha = 0f
        isFocusable = false
    }

    fun show(text: String) {
        message = text
        requestLayout()
        invalidate()
        removeCallbacks(hide)
        animate().cancel()
        translationY = Ui.s(16f)
        animate().alpha(1f).translationY(0f).setDuration(240L).start()
        postDelayed(hide, 2800L)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = paint.measureText(message) + padX * 2 + bleed * 2
        setMeasuredDimension(w.roundToInt(), (barHeight + bleed * 2).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, barHeight / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        surface.drawGloss(canvas, 0f)
        canvas.drawText(message, bleed + padX, Ui.baseline(paint, height / 2f), paint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hide)
        super.onDetachedFromWindow()
    }
}


// =====================================================================================
//  SECTION 2 - Wallpaper
// =====================================================================================

object WallpaperArt {
    /** Built in sky scene: gradient sky, layered clouds, crescent moon and bougainvillea. */
    fun render(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val rnd = java.util.Random(42L)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(
            0f, 0f, 0f, hf,
            intArrayOf(0xFF2A74AC.toInt(), 0xFF3B8BC2.toInt(), 0xFF6FAAD3.toInt(), 0xFFB3CFE4.toInt()),
            floatArrayOf(0f, 0.45f, 0.8f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, p)

        // moon
        val mx = wf * 0.70f
        val my = hf * 0.31f
        val mr = hf * 0.055f
        p.shader = RadialGradient(
            mx, my, mr * 3.2f,
            intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        c.drawCircle(mx, my, mr * 3.2f, p)
        p.shader = null
        val moon = Path()
        val disc = Path()
        val bite = Path()
        disc.addCircle(mx, my, mr, Path.Direction.CW)
        bite.addCircle(mx - mr * 0.38f, my - mr * 0.18f, mr * 0.92f, Path.Direction.CW)
        moon.op(disc, bite, Path.Op.DIFFERENCE)
        p.color = Color.argb(235, 246, 240, 235)
        c.drawPath(moon, p)

        // clouds
        cloudBank(c, wf * 0.80f, hf * 0.74f, wf * 0.56f, rnd, 46)
        cloudBank(c, wf * 0.44f, hf * 0.90f, wf * 0.40f, rnd, 30)
        cloudBank(c, wf * 0.26f, hf * 0.74f, wf * 0.16f, rnd, 12)
        cloudBank(c, wf * 0.95f, hf * 0.40f, wf * 0.22f, rnd, 16)

        // flowers and leaves
        p.shader = null
        for (i in 0 until 90) {
            val side = rnd.nextFloat()
            val x = if (i % 4 == 0) wf * rnd.nextFloat() else wf * 0.2f * side * side
            val y = if (i % 4 == 0) hf * (0.86f + 0.14f * rnd.nextFloat()) else hf * (0.04f + 0.96f * rnd.nextFloat())
            c.save()
            c.translate(x, y)
            c.rotate(rnd.nextFloat() * 360f)
            p.color = if (rnd.nextBoolean()) Color.rgb(46, 96, 58) else Color.rgb(70, 124, 72)
            c.drawOval(-hf * 0.03f, -hf * 0.011f, hf * 0.03f, hf * 0.011f, p)
            c.restore()
        }
        val petals = intArrayOf(0xFFC8143C.toInt(), 0xFFE0284F.toInt(), 0xFF9E0F32.toInt(), 0xFFF05A7A.toInt())
        for (i in 0 until 240) {
            val side = rnd.nextFloat()
            val bottom = i % 3 == 0
            val x = if (bottom) wf * rnd.nextFloat() else wf * 0.19f * side * side
            val y = if (bottom) hf * (0.88f + 0.12f * rnd.nextFloat()) else hf * (0.02f + 0.98f * rnd.nextFloat())
            val r = hf * (0.006f + 0.008f * rnd.nextFloat())
            p.color = petals[rnd.nextInt(petals.size)]
            for (k in 0 until 6) {
                val a = k * 1.0472f + rnd.nextFloat() * 0.4f
                c.drawCircle(x + cos(a) * r, y + sin(a) * r, r * 0.9f, p)
            }
            p.color = Color.rgb(250, 214, 110)
            c.drawCircle(x, y, r * 0.35f, p)
        }
        return bmp
    }

    private fun cloudBank(c: Canvas, cx: Float, cy: Float, size: Float, rnd: java.util.Random, puffs: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until puffs) {
            val x = cx + (rnd.nextFloat() - 0.5f) * size * 1.5f
            val lift = (1f - abs(x - cx) / (size * 0.75f)).coerceIn(0f, 1f)
            val y = cy + (rnd.nextFloat() - 0.5f) * size * 0.34f - lift * size * 0.16f
            val r = size * (0.09f + rnd.nextFloat() * 0.13f)
            val warm = y > cy
            val core = if (warm) Color.argb(235, 250, 226, 220) else Color.argb(240, 255, 255, 255)
            p.shader = RadialGradient(
                x, y, r,
                intArrayOf(core, Color.argb(205, 250, 240, 238), Color.argb(0, 255, 255, 255)),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(x, y, r, p)
        }
    }
}

class WallpaperView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private var reveal = 0f
    private var pan = 0f
    private var panAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val topShade = Paint()
    private val bottomShade = Paint()
    private val dst = RectF()

    fun setBitmap(b: Bitmap) {
        bitmap = b
        revealAnimator?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 800L
        a.addUpdateListener {
            reveal = it.animatedValue as Float
            invalidate()
        }
        revealAnimator = a
        a.start()
    }

    /** Subtle parallax. -1 shifts the picture right, +1 shifts it left. */
    fun panTo(target: Float) {
        panAnimator?.cancel()
        val a = ValueAnimator.ofFloat(pan, target.coerceIn(-1f, 1f))
        a.duration = 650L
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener {
            pan = it.animatedValue as Float
            invalidate()
        }
        panAnimator = a
        a.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        topShade.shader = LinearGradient(0f, 0f, 0f, h * 0.30f, Color.argb(96, 0, 0, 0), Color.argb(0, 0, 0, 0), Shader.TileMode.CLAMP)
        bottomShade.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(), Color.argb(0, 0, 0, 0), Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(0xFF2A74AC.toInt())
        val bmp = bitmap
        if (bmp != null) {
            val extra = w * 0.03f
            val shift = pan * extra
            dst.set(-extra - shift, -extra * 0.5f, w + extra - shift, h + extra * 0.5f)
            paint.alpha = (reveal * 255f).toInt()
            BitmapUtil.drawCropped(canvas, bmp, dst, paint)
        }
        canvas.drawRect(0f, 0f, w, h * 0.30f, topShade)
        canvas.drawRect(0f, h * 0.62f, w, h, bottomShade)
    }

    override fun onDetachedFromWindow() {
        panAnimator?.cancel()
        revealAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
//  SECTION 3 - Clock
// =====================================================================================

class HomeClockView(context: Context) : View(context) {
    private val timePaint = Ui.textPaint(Ui.s(60f), Color.WHITE, Fonts.light, 0.01f)
    private val datePaint = Ui.textPaint(Ui.s(30f), Ui.alpha(Color.WHITE, 0.85f), Fonts.regular, 0.01f)
    private var time = ""
    private var date = ""
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, 15_000L)
        }
    }

    fun refresh() {
        val now = Date()
        val locale = Locale.getDefault()
        val pattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a"
        val newTime = SimpleDateFormat(pattern, locale).format(now).uppercase(locale)
        val newDate = SimpleDateFormat("EEE, MMM d", locale).format(now)
        if (newTime != time || newDate != date) {
            time = newTime
            date = newDate
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.px(720), Ui.px(96))
    }

    override fun onDraw(canvas: Canvas) {
        val base = Ui.s(68f)
        canvas.drawText(time, 0f, base, timePaint)
        canvas.drawText(date, timePaint.measureText(time) + Ui.s(26f), base, datePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
//  SECTION 4 - Now playing (MediaSession) integration
// =====================================================================================

/** Declared in the manifest so the system can grant notification access, which unlocks media sessions. */
class MediaListenerService : NotificationListenerService()

class NowPlaying(
    val controller: MediaController,
    val packageName: String,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val position: Long,
    val duration: Long,
    val timestamp: Long,
    val speed: Float
)

class NowPlayingTracker(
    context: Context,
    private val handler: Handler,
    private val onChange: (NowPlaying?) -> Unit,
    private val onDenied: () -> Unit
) {
    private val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val component = ComponentName(context, MediaListenerService::class.java)
    private var controller: MediaController? = null
    private var started = false
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            refresh()
        }
    }
    private val sessionListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> select(list) }

    fun start() {
        if (started) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionListener, component, handler)
            started = true
            refresh()
        } catch (e: SecurityException) {
            onDenied()
            onChange(null)
        }
    }

    fun stop() {
        if (started) {
            try {
                manager.removeOnActiveSessionsChangedListener(sessionListener)
            } catch (e: RuntimeException) {
                // listener already gone
            }
            started = false
        }
        controller?.unregisterCallback(callback)
        controller = null
    }

    private fun refresh() {
        try {
            select(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            onChange(null)
        }
    }

    private fun select(list: List<MediaController>?) {
        val candidates = list.orEmpty()
        val chosen = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: candidates.firstOrNull { it.metadata != null }
        if (chosen?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = chosen
            chosen?.registerCallback(callback, handler)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) {
            onChange(null)
            return
        }
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
        if (md == null || title.isNullOrBlank()) {
            onChange(null)
            return
        }
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = c.playbackState
        onChange(
            NowPlaying(
                controller = c,
                packageName = c.packageName ?: "",
                title = title,
                artist = artist,
                art = art,
                playing = state?.state == PlaybackState.STATE_PLAYING,
                position = state?.position ?: 0L,
                duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION),
                timestamp = state?.lastPositionUpdateTime ?: SystemClock.elapsedRealtime(),
                speed = state?.playbackSpeed ?: 1f
            )
        )
    }
}

object Equalizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        c: Canvas, left: Float, bottom: Float, barW: Float, gap: Float,
        maxH: Float, bars: Int, color: Int, animated: Boolean
    ) {
        paint.color = color
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until bars) {
            val f = if (animated) 0.35f + 0.65f * (0.5f + 0.5f * sin(t * (5f + i * 1.7f) + i * 1.3f)) else 0.3f
            val bh = maxH * f
            val x = left + i * (barW + gap)
            c.drawRoundRect(x, bottom - bh, x + barW, bottom, barW / 2f, barW / 2f, paint)
        }
    }
}

// =====================================================================================
//  SECTION 5 - Round glass buttons and the now playing pill
// =====================================================================================

class RoundButton(
    context: Context,
    private var glyph: Glyph,
    private val diameter: Float,
    private val tone: GlassSurface.Tone,
    private val glyphColor: Int
) : FocusGlassView(context, 1.16f, 0f) {
    private val bleed = Ui.s(12f)
    private val surface = GlassSurface(tone)

    fun setGlyph(g: Glyph) {
        glyph = g
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (diameter + bleed * 2).roundToInt()
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, diameter / 2f)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        surface.drawBase(canvas, f)
        surface.drawGloss(canvas, f)
        val color = if (tone == GlassSurface.Tone.BRIGHT) glyphColor else Ui.lerpColor(glyphColor, Color.WHITE, f)
        GlyphPainter.draw(canvas, glyph, width / 2f, height / 2f, diameter * 0.42f, color)
    }
}

class NowPlayingPill(context: Context) : FrameLayout(context) {
    var onHidden: (() -> Unit)? = null
    private val bleed = Ui.s(24f)
    private val pillW = Ui.s(760f)
    private val pillH = Ui.s(160f)
    val viewWidth: Int = (pillW + bleed * 2).roundToInt()
    val viewHeight: Int = (pillH + bleed * 2).roundToInt()
    private val surface = GlassSurface(GlassSurface.Tone.SMOKED)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val titlePaint = Ui.textPaint(Ui.s(26f), Color.WHITE, Fonts.medium)
    private val artistPaint = Ui.textPaint(Ui.s(21f), Ui.alpha(Color.WHITE, 0.78f), Fonts.regular)
    private val timePaint = Ui.textPaint(Ui.s(20f), Ui.alpha(Color.WHITE, 0.78f), Fonts.regular)
    private val artRect = RectF()
    private val artClip = Path()
    private val artPlaceholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state: NowPlaying? = null
    private var titleText = ""
    private var artistText = ""
    private val previous = RoundButton(context, Glyph.PREV, Ui.s(50f), GlassSurface.Tone.SMOKED, Color.WHITE)
    private val toggle = RoundButton(context, Glyph.PAUSE, Ui.s(56f), GlassSurface.Tone.BRIGHT, Color.rgb(8, 8, 10))
    private val next = RoundButton(context, Glyph.NEXT, Ui.s(50f), GlassSurface.Tone.SMOKED, Color.WHITE)
    private val tick = object : Runnable {
        override fun run() {
            invalidate()
            if (state?.playing == true) postDelayed(this, 160L)
        }
    }

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        fillPaint.color = Color.argb(214, 3, 3, 5)
        trackPaint.color = Color.argb(60, 255, 255, 255)
        progressPaint.color = SPOTIFY_GREEN
        artPlaceholder.color = Color.rgb(40, 44, 52)
        timePaint.textAlign = Paint.Align.LEFT
        visibility = View.GONE
        place(previous, -Ui.s(84f))
        place(toggle, 0f)
        place(next, Ui.s(84f))
        previous.focusKey = "pill:prev"
        toggle.focusKey = "pill:toggle"
        next.focusKey = "pill:next"
        previous.setOnClickListener { state?.controller?.transportControls?.skipToPrevious() }
        next.setOnClickListener { state?.controller?.transportControls?.skipToNext() }
        toggle.setOnClickListener {
            val s = state
            if (s != null) {
                if (s.playing) s.controller.transportControls.pause() else s.controller.transportControls.play()
            }
        }
    }

    private fun place(button: RoundButton, dx: Float) {
        button.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val size = button.measuredWidth
        val lp = FrameLayout.LayoutParams(size, size)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = (bleed + pillW / 2f + dx - size / 2f).roundToInt()
        lp.topMargin = (bleed + Ui.s(126f) - size / 2f).roundToInt()
        addView(button, lp)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(viewWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(viewHeight, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, bleed + pillW, bleed + pillH, Ui.s(44f))
        artRect.set(bleed + Ui.s(22f), bleed + Ui.s(20f), bleed + Ui.s(80f), bleed + Ui.s(78f))
        artClip.reset()
        artClip.addRoundRect(artRect, Ui.s(10f), Ui.s(10f), Path.Direction.CW)
    }

    fun update(np: NowPlaying?) {
        if (np == null) {
            hide()
            return
        }
        state = np
        val textW = pillW - Ui.s(98f) - Ui.s(90f)
        titleText = TextUtils.ellipsize(np.title, titlePaint, textW, TextUtils.TruncateAt.END).toString()
        artistText = TextUtils.ellipsize(np.artist, artistPaint, textW, TextUtils.TruncateAt.END).toString()
        toggle.setGlyph(if (np.playing) Glyph.PAUSE else Glyph.PLAY)
        removeCallbacks(tick)
        post(tick)
        show()
    }

    private fun show() {
        if (visibility == View.VISIBLE && alpha >= 0.99f) return
        animate().cancel()
        if (visibility != View.VISIBLE) {
            visibility = View.VISIBLE
            alpha = 0f
            translationY = -Ui.s(24f)
        }
        animate().alpha(1f).translationY(0f).setDuration(320L).setInterpolator(DecelerateInterpolator(1.6f)).start()
    }

    private fun hide() {
        state = null
        removeCallbacks(tick)
        if (visibility != View.VISIBLE) return
        val hadFocus = hasFocus()
        animate().cancel()
        animate().alpha(0f).translationY(-Ui.s(24f)).setDuration(240L).withEndAction {
            if (state == null) visibility = View.GONE
        }.start()
        if (hadFocus) onHidden?.invoke()
    }

    private fun position(np: NowPlaying): Long {
        var pos = np.position
        if (np.playing) pos += ((SystemClock.elapsedRealtime() - np.timestamp) * np.speed).toLong()
        if (np.duration > 0) pos = pos.coerceIn(0L, np.duration)
        return max(0L, pos)
    }

    private fun format(ms: Long): String {
        val total = (ms / 1000L).toInt()
        val sec = total % 60
        return (total / 60).toString() + ":" + (if (sec < 10) "0" else "") + sec
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        canvas.drawRoundRect(surface.rect, Ui.s(44f), Ui.s(44f), fillPaint)
        surface.drawGloss(canvas, 0f)
        val np = state ?: return
        val l = bleed
        val t = bleed

        canvas.save()
        canvas.clipPath(artClip)
        val art = np.art
        if (art != null) BitmapUtil.drawCropped(canvas, art, artRect, artPaint) else canvas.drawRect(artRect, artPlaceholder)
        canvas.restore()

        val textX = l + Ui.s(98f)
        canvas.drawText(titleText, textX, Ui.baseline(titlePaint, t + Ui.s(36f)), titlePaint)
        canvas.drawText(artistText, textX, Ui.baseline(artistPaint, t + Ui.s(66f)), artistPaint)
        Equalizer.draw(
            canvas, l + pillW - Ui.s(22f) - Ui.s(30f), t + Ui.s(48f),
            Ui.s(4f), Ui.s(3.5f), Ui.s(26f), 4, SPOTIFY_GREEN, np.playing
        )

        val pos = position(np)
        val barL = l + Ui.s(22f)
        val barR = l + pillW - Ui.s(22f)
        val barY = t + Ui.s(94f)
        canvas.drawRoundRect(barL, barY - Ui.s(2f), barR, barY + Ui.s(2f), Ui.s(2f), Ui.s(2f), trackPaint)
        if (np.duration > 0) {
            val frac = (pos.toFloat() / np.duration.toFloat()).coerceIn(0f, 1f)
            canvas.drawRoundRect(barL, barY - Ui.s(2f), barL + (barR - barL) * frac, barY + Ui.s(2f), Ui.s(2f), Ui.s(2f), progressPaint)
        }
        val timeY = Ui.baseline(timePaint, t + Ui.s(126f))
        timePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(format(pos), barL, timeY, timePaint)
        timePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(if (np.duration > 0) format(np.duration) else "--:--", barR, timeY, timePaint)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        animate().cancel()
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
//  SECTION 6 - Dock
// =====================================================================================

class DockStore(context: Context) {
    private val prefs = context.getSharedPreferences("sambhav_launcher", Context.MODE_PRIVATE)

    private fun read(): MutableList<String> =
        (prefs.getString(KEY, "") ?: "").split("|").filter { it.isNotBlank() }.toMutableList()

    private fun write(list: List<String>) {
        prefs.edit().putString(KEY, list.joinToString("|")).apply()
    }

    fun resolve(apps: List<AppEntry>): List<AppEntry> {
        val byPackage = apps.associateBy { it.packageName }
        var pins = read().filter { byPackage.containsKey(it) }.toMutableList()
        if (!prefs.contains(KEY) && apps.isNotEmpty()) {
            pins = defaults(apps).toMutableList()
            write(pins)
        }
        return pins.mapNotNull { byPackage[it] }.take(MAX)
    }

    fun isPinned(pkg: String): Boolean = read().contains(pkg)

    /** Returns 1 when added, 0 when removed and -1 when the dock is full. */
    fun toggle(pkg: String): Int {
        val pins = read()
        if (pins.remove(pkg)) {
            write(pins)
            return 0
        }
        if (pins.size >= MAX) return -1
        pins.add(pkg)
        write(pins)
        return 1
    }

    private fun defaults(apps: List<AppEntry>): List<String> {
        val out = ArrayList<String>()
        for (name in PRIORITY) {
            val match = apps.firstOrNull { it.packageName.startsWith(name) }
            if (match != null && !out.contains(match.packageName)) out.add(match.packageName)
        }
        for (app in apps.sortedBy { it.category.ordinal }) {
            if (out.size >= MAX) break
            if (!out.contains(app.packageName)) out.add(app.packageName)
        }
        return out.take(MAX)
    }

    companion object {
        const val MAX = 7
        private const val KEY = "dock"
        private val PRIORITY = listOf(
            "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom", "in.startv.hotstar",
            "com.netflix.ninja", "com.spotify.tv.android", "com.google.android.videos", "com.plexapp.android"
        )
    }
}

class DockTile(context: Context, private val entry: AppEntry?) : FocusGlassView(context, 1.34f, Ui.s(16f)) {
    private val bleed = Ui.s(24f)
    private val roomY = Ui.s(60f)
    private val cardW = Ui.s(192f)
    private val cardH = Ui.s(120f)
    val tileWidth: Int = (cardW + bleed * 2).roundToInt()
    val tileHeight: Int = (cardH + roomY * 2).roundToInt()
    val packageName: String? get() = entry?.packageName
    private val frost = GlassSurface(GlassSurface.Tone.FROST)
    private val dark = GlassSurface(GlassSurface.Tone.SMOKED)
    private val cardRect = RectF()
    private val tipRect = RectF()
    private val mediaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tipText = Ui.textPaint(Ui.s(22f), Color.WHITE, Fonts.medium)
    private var label = entry?.label ?: "All Apps"
    private val eqTick = object : Runnable {
        override fun run() {
            invalidate()
            if (playing) postDelayed(this, 120L)
        }
    }
    var playing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            removeCallbacks(eqTick)
            if (value) post(eqTick)
            invalidate()
        }

    init {
        contentDescription = label
        veilPaint.color = Color.WHITE
        dotPaint.color = Color.WHITE
        tipFill.color = Color.rgb(12, 16, 24)
        dark.shadowEnabled = false
        tipText.textAlign = Paint.Align.CENTER
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(tileWidth, tileHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cardRect.set(bleed, roomY, w - bleed, roomY + cardH)
        val radius = Ui.s(26f)
        frost.layout(cardRect.left, cardRect.top, cardRect.right, cardRect.bottom, radius)
        dark.layout(cardRect.left, cardRect.top, cardRect.right, cardRect.bottom, radius)
        pivotX = w / 2f
        pivotY = cardRect.centerY()
        label = TextUtils.ellipsize(entry?.label ?: "All Apps", tipText, Ui.s(190f), TextUtils.TruncateAt.END).toString()
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        frost.drawBase(canvas, f, ((1f - f) * 255f).toInt())
        if (f > 0.01f) dark.drawBase(canvas, 0f, (f * 255f).toInt())
        canvas.save()
        frost.clipTo(canvas)
        if (entry == null) drawGridDots(canvas) else drawContent(canvas, f, entry)
        canvas.restore()
        frost.drawGloss(canvas, f)
        if (playing) {
            val total = 3 * Ui.s(4f) + 2 * Ui.s(3.5f)
            Equalizer.draw(
                canvas, cardRect.centerX() - total / 2f, cardRect.bottom - Ui.s(10f),
                Ui.s(4f), Ui.s(3.5f), Ui.s(22f), 3, entry?.accent ?: SPOTIFY_GREEN, true
            )
        }
        if (f > 0.02f) drawTooltip(canvas, f)
    }

    private fun drawContent(canvas: Canvas, f: Float, app: AppEntry) {
        val banner = app.banner
        if (banner != null) {
            mediaPaint.alpha = (190f + 65f * f).toInt()
            BitmapUtil.drawCropped(canvas, banner, cardRect, mediaPaint)
        } else {
            IconPlate.draw(
                canvas, app.icon, app.initial, app.accent,
                cardRect.centerX(), cardRect.centerY(), cardRect.height() * 0.64f
            )
        }
        veilPaint.alpha = ((1f - f) * 36f).toInt()
        canvas.drawRect(cardRect, veilPaint)
    }

    private fun drawGridDots(canvas: Canvas) {
        val gap = Ui.s(26f)
        dotPaint.alpha = 235
        for (i in -1..1) for (j in -1..1) {
            canvas.drawCircle(cardRect.centerX() + i * gap, cardRect.centerY() + j * gap, Ui.s(6f), dotPaint)
        }
    }

    private fun drawTooltip(canvas: Canvas, f: Float) {
        val tw = tipText.measureText(label) + Ui.s(36f)
        val th = Ui.s(38f)
        val cx = cardRect.centerX()
        val bottom = cardRect.top - Ui.s(14f)
        tipRect.set(cx - tw / 2f, bottom - th, cx + tw / 2f, bottom)
        tipFill.alpha = (f * 238f).toInt()
        canvas.drawRoundRect(tipRect, th / 2.6f, th / 2.6f, tipFill)
        tipText.alpha = (f * 255f).toInt()
        canvas.drawText(label, cx, Ui.baseline(tipText, tipRect.centerY()), tipText)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(eqTick)
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
//  SECTION 7 - All apps overlay
// =====================================================================================

class OverlayTitleView(context: Context) : View(context) {
    private val titlePaint = Ui.textPaint(Ui.s(58f), Color.WHITE, Fonts.light, -0.01f)
    private val hintPaint = Ui.textPaint(Ui.s(22f), Ui.alpha(Color.WHITE, 0.55f), Fonts.regular, 0.01f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.px(230))
    }

    override fun onDraw(canvas: Canvas) {
        val x = Ui.s(96f)
        canvas.drawText("All Apps", x, Ui.s(140f), titlePaint)
        canvas.drawText("Hold OK on an app to add it to the dock or remove it", x, Ui.s(188f), hintPaint)
    }
}

class AllAppsOverlay(
    context: Context,
    private val onLaunch: (AppEntry) -> Unit,
    private val onPinToggle: (AppEntry) -> Unit
) : FrameLayout(context) {
    private val stage = VerticalStage(context)
    private var firstTile: View? = null
    var isOpen = false
        private set

    init {
        visibility = View.GONE
        setBackgroundColor(Ui.alpha(Color.BLACK, 0.80f))
        stage.anchorTop = Ui.s(200f)
        stage.column.setPadding(0, 0, 0, Ui.px(120))
        addView(stage, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    fun firstFocusable(): View? = firstTile

    fun populate(apps: List<AppEntry>) {
        stage.column.removeAllViews()
        stage.resetScroll()
        firstTile = null
        stage.column.addView(OverlayTitleView(context), LinearLayout.LayoutParams(MATCH, Ui.px(230)))
        val perRow = 7
        var index = 0
        while (index < apps.size) {
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.clipChildren = false
            row.setPadding(Ui.px(96) - Ui.px(TILE_BLEED.toInt()), 0, 0, 0)
            for (entry in apps.subList(index, min(index + perRow, apps.size))) {
                val tile = AppTile(context, entry, TileStyle.SQUARE)
                tile.focusKey = "all:" + entry.packageName
                tile.setOnClickListener { onLaunch(entry) }
                tile.isLongClickable = true
                tile.setOnLongClickListener {
                    onPinToggle(entry)
                    true
                }
                row.addView(tile, LinearLayout.LayoutParams(tile.tileWidth, tile.tileHeight))
                if (firstTile == null) firstTile = tile
            }
            stage.column.addView(row, LinearLayout.LayoutParams(MATCH, AppTile.heightFor(TileStyle.SQUARE)))
            index += perRow
        }
    }

    fun open() {
        isOpen = true
        animate().cancel()
        visibility = View.VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(240L).start()
    }

    fun close() {
        isOpen = false
        animate().cancel()
        animate().alpha(0f).setDuration(200L).withEndAction {
            if (!isOpen) visibility = View.GONE
        }.start()
    }
}

// =====================================================================================
//  SECTION 8 - Activity
// =====================================================================================

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var wallpaper: WallpaperView
    private lateinit var content: FrameLayout
    private lateinit var clock: HomeClockView
    private lateinit var pill: NowPlayingPill
    private lateinit var dock: GlassPanelLayout
    private lateinit var overlay: AllAppsOverlay
    private lateinit var notice: NoticeView
    private lateinit var repository: AppRepository
    private lateinit var recents: RecentsStore
    private lateinit var dockStore: DockStore
    private lateinit var tracker: NowPlayingTracker
    private val dockTiles = ArrayList<DockTile>()
    private var apps: List<AppEntry> = emptyList()
    private var nowPlaying: NowPlaying? = null
    private var lastDockKey: String? = null
    private var loadToken = 0
    private var receiversRegistered = false
    private val handler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val reloadRunnable = Runnable { reloadApps() }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handler.removeCallbacks(reloadRunnable)
            handler.postDelayed(reloadRunnable, 800L)
        }
    }

    // ---- lifecycle -------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.init(this)
        window.setBackgroundDrawable(ColorDrawable(0xFF2A74AC.toInt()))
        repository = AppRepository(applicationContext)
        recents = RecentsStore(applicationContext)
        dockStore = DockStore(applicationContext)
        tracker = NowPlayingTracker(this, handler, { onNowPlaying(it) }, { onMediaAccessDenied() })
        lastDockKey = savedInstanceState?.getString(STATE_FOCUS)
        buildShell()
        setContentView(root)
        loadWallpaper()
        reloadApps()
    }

    override fun onStart() {
        super.onStart()
        if (!receiversRegistered) {
            val filter = IntentFilter()
            filter.addAction(Intent.ACTION_PACKAGE_ADDED)
            filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            filter.addAction(Intent.ACTION_PACKAGE_REPLACED)
            filter.addDataScheme("package")
            registerReceiver(packageReceiver, filter)
            receiversRegistered = true
        }
        tracker.start()
    }

    override fun onResume() {
        super.onResume()
        clock.refresh()
    }

    override fun onStop() {
        tracker.stop()
        if (receiversRegistered) {
            try {
                unregisterReceiver(packageReceiver)
            } catch (e: IllegalArgumentException) {
                // already unregistered
            }
            receiversRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_FOCUS, lastDockKey)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) {
            if (overlay.isOpen) closeAllApps(false)
            lastDockKey = null
            focusDock(null)
        }
    }

    // ---- shell -----------------------------------------------------------------------

    private fun buildShell() {
        root = FrameLayout(this)
        root.setBackgroundColor(0xFF2A74AC.toInt())
        root.clipChildren = false

        wallpaper = WallpaperView(this)
        root.addView(wallpaper, FrameLayout.LayoutParams(MATCH, MATCH))

        content = FrameLayout(this)
        content.clipChildren = false
        content.clipToPadding = false
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))

        clock = HomeClockView(this)
        val clockParams = FrameLayout.LayoutParams(WRAP, WRAP)
        clockParams.gravity = Gravity.TOP or Gravity.START
        clockParams.leftMargin = Ui.px(81)
        clockParams.topMargin = Ui.px(54)
        content.addView(clock, clockParams)

        pill = NowPlayingPill(this)
        pill.onHidden = { focusDock(lastDockKey) }
        val pillParams = FrameLayout.LayoutParams(pill.viewWidth, pill.viewHeight)
        pillParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        pillParams.topMargin = Ui.px(62) - Ui.px(24)
        content.addView(pill, pillParams)

        val buttons = LinearLayout(this)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.clipChildren = false
        buttons.clipToPadding = false
        val diameter = Ui.s(69f)
        val inputs = RoundButton(this, Glyph.INPUT, diameter, GlassSurface.Tone.FROST, Color.WHITE)
        val network = RoundButton(this, Glyph.WIFI, diameter, GlassSurface.Tone.FROST, Color.WHITE)
        val gear = RoundButton(this, Glyph.GEAR, diameter, GlassSurface.Tone.FROST, Color.WHITE)
        inputs.focusKey = "top:inputs"
        network.focusKey = "top:network"
        gear.focusKey = "top:settings"
        inputs.setOnClickListener {
            openSystem(
                listOf("android.media.tv.action.SETUP_INPUTS", "android.settings.INPUT_METHOD_SETTINGS"),
                "Input selection is unavailable on this device"
            )
        }
        network.setOnClickListener {
            openSystem(listOf(Settings.ACTION_WIFI_SETTINGS), "Network settings are unavailable on this device")
        }
        gear.setOnClickListener {
            openSystem(listOf(Settings.ACTION_SETTINGS), "Settings are unavailable on this device")
        }
        for (b in listOf(inputs, network, gear)) {
            val lp = LinearLayout.LayoutParams(WRAP, WRAP)
            lp.leftMargin = -Ui.px(4)
            lp.rightMargin = -Ui.px(4)
            buttons.addView(b, lp)
        }
        val buttonParams = FrameLayout.LayoutParams(WRAP, WRAP)
        buttonParams.gravity = Gravity.TOP or Gravity.END
        buttonParams.topMargin = Ui.px(62) - Ui.px(12)
        buttonParams.rightMargin = Ui.px(81) - Ui.px(12)
        content.addView(buttons, buttonParams)

        dock = GlassPanelLayout(this, GlassSurface.Tone.FROST, Ui.s(46f))
        dock.orientation = LinearLayout.HORIZONTAL
        dock.gravity = Gravity.CENTER
        val dockParams = FrameLayout.LayoutParams(Ui.px(1757), Ui.px(124))
        dockParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        dockParams.bottomMargin = Ui.px(60)
        content.addView(dock, dockParams)

        overlay = AllAppsOverlay(this, { launchFromOverlay(it) }, { togglePin(it) })
        root.addView(overlay, FrameLayout.LayoutParams(MATCH, MATCH))

        notice = NoticeView(this)
        val noticeParams = FrameLayout.LayoutParams(WRAP, WRAP)
        noticeParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        noticeParams.bottomMargin = Ui.px(230)
        root.addView(notice, noticeParams)
    }

    private fun loadWallpaper() {
        val metrics = resources.displayMetrics
        val w = min(metrics.widthPixels, 1600)
        val h = min(metrics.heightPixels, 900)
        executor.execute {
            val bmp: Bitmap = try {
                val id = resources.getIdentifier("wallpaper", "drawable", packageName)
                if (id != 0) decodeSampled(id, metrics.widthPixels, metrics.heightPixels) else WallpaperArt.render(w, h)
            } catch (t: Throwable) {
                WallpaperArt.render(w, h)
            }
            handler.post { if (!isDestroyed) wallpaper.setBitmap(bmp) }
        }
    }

    private fun decodeSampled(resId: Int, reqW: Int, reqH: Int): Bitmap {
        val bounds = BitmapFactory.Options()
        bounds.inJustDecodeBounds = true
        BitmapFactory.decodeResource(resources, resId, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        return BitmapFactory.decodeResource(resources, resId, opts) ?: throw IllegalStateException("wallpaper decode failed")
    }

    // ---- data ------------------------------------------------------------------------

    private fun reloadApps() {
        val token = ++loadToken
        executor.execute {
            val list = try {
                repository.loadInstalled()
            } catch (t: Throwable) {
                emptyList()
            }
            handler.post {
                if (token == loadToken && !isDestroyed) {
                    apps = list
                    val key = (currentFocus as? FocusGlassView)?.focusKey ?: lastDockKey
                    rebuildDock(key, !overlay.isOpen)
                }
            }
        }
    }

    private fun rebuildDock(focusKey: String?, restoreFocus: Boolean) {
        dockTiles.clear()
        dock.removeAllViews()
        val pinned = dockStore.resolve(apps)
        val total = pinned.size + 1
        pinned.forEachIndexed { index, entry ->
            val tile = DockTile(this, entry)
            val key = "dock:" + entry.packageName
            tile.focusKey = key
            tile.setOnClickListener { launchApp(entry) }
            tile.isLongClickable = true
            tile.setOnLongClickListener {
                togglePin(entry)
                true
            }
            tile.onFocusGained = { onDockFocused(key, index, total) }
            tile.playing = isPlayingPackage(entry.packageName)
            addDockTile(tile)
        }
        val grid = DockTile(this, null)
        grid.focusKey = "dock:grid"
        grid.setOnClickListener { openAllApps() }
        grid.onFocusGained = { onDockFocused("dock:grid", total - 1, total) }
        addDockTile(grid)
        if (restoreFocus) runAfterLayout { focusDock(focusKey) }
    }

    private fun addDockTile(tile: DockTile) {
        val lp = LinearLayout.LayoutParams(tile.tileWidth, tile.tileHeight)
        lp.leftMargin = -Ui.px(12)
        lp.rightMargin = -Ui.px(12)
        dock.addView(tile, lp)
        dockTiles.add(tile)
    }

    private fun focusDock(key: String?) {
        val target = (if (key != null) dock.findFocusGlass { it.focusKey == key } else null)
            ?: dock.findFocusGlass { true }
        target?.requestFocus()
    }

    private fun onDockFocused(key: String, index: Int, total: Int) {
        lastDockKey = key
        val fraction = if (total > 1) index.toFloat() / (total - 1) else 0.5f
        wallpaper.panTo((fraction - 0.5f) * 2f)
    }

    private fun runAfterLayout(block: () -> Unit) {
        root.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val vto = root.viewTreeObserver
                if (vto.isAlive) vto.removeOnGlobalLayoutListener(this)
                block()
            }
        })
        root.requestLayout()
    }

    // ---- actions ---------------------------------------------------------------------

    private fun launchApp(entry: AppEntry) {
        if (AppLauncher.launch(this, entry)) {
            recents.push(entry.packageName)
        } else {
            notice.show("Unable to open " + entry.label)
        }
    }

    private fun launchFromOverlay(entry: AppEntry) {
        closeAllApps(false)
        launchApp(entry)
    }

    private fun togglePin(entry: AppEntry) {
        when (dockStore.toggle(entry.packageName)) {
            1 -> notice.show(entry.label + " added to the dock")
            0 -> notice.show(entry.label + " removed from the dock")
            else -> notice.show("The dock is full. Hold OK on a dock app to remove it first")
        }
        val key = if (overlay.isOpen) null else "dock:grid"
        rebuildDock(key, !overlay.isOpen)
    }

    private fun openSystem(actions: List<String>, failure: String) {
        if (!AppLauncher.openSetting(this, actions)) notice.show(failure)
    }

    private fun openAllApps() {
        overlay.populate(apps)
        content.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        overlay.open()
        runAfterLayout { overlay.firstFocusable()?.requestFocus() }
    }

    private fun closeAllApps(restoreFocus: Boolean) {
        overlay.close()
        content.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (restoreFocus) runAfterLayout { focusDock("dock:grid") }
    }

    private fun onNowPlaying(np: NowPlaying?) {
        nowPlaying = np
        pill.update(np)
        for (tile in dockTiles) {
            val pkg = tile.packageName
            tile.playing = np != null && np.playing && pkg != null && pkg == np.packageName
        }
    }

    private fun isPlayingPackage(pkg: String): Boolean {
        val np = nowPlaying
        return np != null && np.playing && np.packageName == pkg
    }

    private fun onMediaAccessDenied() {
        val prefs = getSharedPreferences("sambhav_launcher", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("np_hint_shown", false)) {
            prefs.edit().putBoolean("np_hint_shown", true).apply()
            handler.postDelayed(
                { notice.show("Enable notification access for Sambhav TV OS to show now playing") },
                2500L
            )
        }
    }

    // ---- keys ------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event == null || event.repeatCount == 0) {
                if (overlay.isOpen) {
                    closeAllApps(true)
                } else if (currentFocus !is DockTile) {
                    focusDock(lastDockKey)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val STATE_FOCUS = "state_focus"
    }
}
