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
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
//  Sambhav TV OS  -  cinematic glass launcher for Android TV (API 28+)
//  Every surface is drawn natively with Canvas; there are no layout XML files, no
//  WebView and no third party libraries.
// =====================================================================================

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val TILE_BLEED = 26f
private const val HERO_HEIGHT = 700
private const val SECTION_HEADER_HEIGHT = 84
private const val PAGE_TITLE_HEIGHT = 262
private val TAU = (2.0 * PI).toFloat()

// =====================================================================================
//  SECTION 1 - Design tokens and helpers
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

enum class Glyph { PLAY, GRID, INFO, WIFI, DISPLAY, SOUND, BLUETOOTH, CLOCK, SLIDERS, REFRESH, CLEAR }

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

// =====================================================================================
//  SECTION 4 - Ambient background and procedural artwork
// =====================================================================================

class AmbientBackgroundView(context: Context) : View(context) {
    private var colorA = Palette.silver
    private var colorB = Palette.secondary(Palette.silver)
    private var targetA = colorA
    private var targetB = colorB
    private var phase = 0f
    private var running = false
    private var glowRadius = 0f
    private var transition: ValueAnimator? = null
    private val basePaint = Paint()
    private val glowPaintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaintB = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vignettePaint = Paint()
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            phase = (phase + 0.0016f) % 1f
            invalidate()
            postDelayed(this, 66L)
        }
    }

    init {
        isFocusable = false
    }

    fun setRunning(value: Boolean) {
        if (value == running) return
        running = value
        removeCallbacks(tick)
        if (value) postDelayed(tick, 66L)
    }

    fun setColors(a: Int, b: Int) {
        if (a == targetA && b == targetB) return
        targetA = a
        targetB = b
        val fromA = colorA
        val fromB = colorB
        transition?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = 900L
        anim.addUpdateListener {
            val t = it.animatedFraction
            colorA = Ui.lerpColor(fromA, a, t)
            colorB = Ui.lerpColor(fromB, b, t)
            rebuildGlows()
            invalidate()
        }
        transition = anim
        anim.start()
    }

    private fun rebuildGlows() {
        if (width == 0 || height == 0) return
        glowPaintA.shader = RadialGradient(
            0f, 0f, glowRadius,
            intArrayOf(Ui.alpha(colorA, 0.34f), Ui.alpha(colorA, 0.11f), Ui.alpha(colorA, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        glowPaintB.shader = RadialGradient(
            0f, 0f, glowRadius * 0.85f,
            intArrayOf(Ui.alpha(colorB, 0.26f), Ui.alpha(colorB, 0.08f), Ui.alpha(colorB, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        glowRadius = max(w, h) * 0.62f
        basePaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0xFF111217.toInt(), 0xFF08090C.toInt(), 0xFF030304.toInt()),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        vignettePaint.shader = RadialGradient(
            w / 2f, h / 2f, max(w, h) * 0.78f,
            intArrayOf(0x00000000, 0x00000000, 0xB0000000.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        rebuildGlows()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, basePaint)
        val a = phase * TAU
        canvas.save()
        canvas.translate(w * (0.78f + 0.06f * sin(a)), h * (0.16f + 0.05f * cos(a)))
        canvas.drawCircle(0f, 0f, glowRadius, glowPaintA)
        canvas.restore()
        canvas.save()
        canvas.translate(w * (0.14f + 0.05f * cos(a)), h * (0.96f + 0.03f * sin(a)))
        canvas.drawCircle(0f, 0f, glowRadius * 0.85f, glowPaintB)
        canvas.restore()
        canvas.drawRect(0f, 0f, w, h, vignettePaint)
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        transition?.cancel()
        super.onDetachedFromWindow()
    }
}

object ArtFactory {
    /** Renders cinematic hero artwork once, off the UI thread. Bottom edge fades to transparent. */
    fun renderHero(w: Int, h: Int, accentA: Int, accentB: Int, seed: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val rnd = java.util.Random(seed.toLong())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)

        p.shader = LinearGradient(0f, 0f, 0f, hf, Palette.darken(accentA, 0.78f), Palette.black, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, wf, hf, p)

        p.shader = RadialGradient(
            wf * 0.76f, hf * 0.30f, wf * 0.55f,
            intArrayOf(Ui.alpha(accentA, 0.85f), Ui.alpha(accentA, 0.25f), Ui.alpha(accentA, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, p)

        p.shader = RadialGradient(
            wf * 0.30f, hf * 0.98f, wf * 0.5f,
            intArrayOf(Ui.alpha(accentB, 0.55f), Ui.alpha(accentB, 0.14f), Ui.alpha(accentB, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, p)

        val shard = Path()
        for (i in 0 until 6) {
            val x0 = wf * (0.35f + 0.65f * rnd.nextFloat())
            val sw = wf * (0.05f + 0.14f * rnd.nextFloat())
            val skew = wf * (0.10f + 0.18f * rnd.nextFloat())
            shard.reset()
            shard.moveTo(x0, 0f)
            shard.lineTo(x0 + sw, 0f)
            shard.lineTo(x0 + sw - skew, hf)
            shard.lineTo(x0 - skew, hf)
            shard.close()
            p.shader = LinearGradient(
                x0, 0f, x0 - skew, hf,
                Color.argb(34 + rnd.nextInt(24), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP
            )
            c.drawPath(shard, p)
        }

        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = hf * 0.004f + 1f
        for (k in 1..4) {
            p.color = Color.argb(30 - k * 5, 255, 255, 255)
            c.drawCircle(wf * 0.76f, hf * 0.30f, hf * 0.22f * k, p)
        }
        p.style = Paint.Style.FILL

        p.shader = LinearGradient(
            0f, 0f, wf * 0.72f, 0f, Ui.alpha(Palette.black, 0.88f), Ui.alpha(Palette.black, 0f), Shader.TileMode.CLAMP
        )
        c.drawRect(0f, 0f, wf, hf, p)

        p.shader = LinearGradient(
            0f, hf * 0.5f, 0f, hf, Color.argb(0, 255, 255, 255), Color.argb(255, 255, 255, 255), Shader.TileMode.CLAMP
        )
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        c.drawRect(0f, hf * 0.5f, wf, hf, p)
        p.xfermode = null
        return bmp
    }
}

// =====================================================================================
//  SECTION 5 - Data model, discovery, persistence and launching
// =====================================================================================

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

class HeroItem(
    val id: String,
    val eyebrow: String,
    val title: String,
    val description: String,
    val primaryLabel: String,
    val primaryGlyph: Glyph,
    val secondaryLabel: String,
    val secondaryGlyph: Glyph,
    val entry: AppEntry?,
    val accentA: Int,
    val accentB: Int,
    val art: Bitmap
)

class SettingsAction(
    val id: String,
    val title: String,
    val subtitle: String,
    val glyph: Glyph,
    val intentAction: String?
)

object SettingsCatalog {
    val system: List<SettingsAction> = listOf(
        SettingsAction("network", "Network", "Wi-Fi and Ethernet", Glyph.WIFI, Settings.ACTION_WIFI_SETTINGS),
        SettingsAction("display", "Display", "Picture and resolution", Glyph.DISPLAY, Settings.ACTION_DISPLAY_SETTINGS),
        SettingsAction("sound", "Sound", "Audio output", Glyph.SOUND, Settings.ACTION_SOUND_SETTINGS),
        SettingsAction("bluetooth", "Bluetooth", "Remotes and speakers", Glyph.BLUETOOTH, Settings.ACTION_BLUETOOTH_SETTINGS),
        SettingsAction("date", "Date and Time", "Clock and region", Glyph.CLOCK, Settings.ACTION_DATE_SETTINGS),
        SettingsAction("apps", "Applications", "Manage installed apps", Glyph.GRID, Settings.ACTION_APPLICATION_SETTINGS),
        SettingsAction("all", "All Settings", "System preferences", Glyph.SLIDERS, Settings.ACTION_SETTINGS)
    )
    val launcher: List<SettingsAction> = listOf(
        SettingsAction("refresh", "Refresh Apps", "Scan installed applications", Glyph.REFRESH, null),
        SettingsAction("clear", "Clear Recents", "Reset recently opened", Glyph.CLEAR, null)
    )
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

    fun openSetting(activity: Activity, action: SettingsAction): Boolean {
        val candidates = listOfNotNull(action.intentAction, Settings.ACTION_SETTINGS)
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

object HeroFactory {
    private val PRIORITY = listOf(
        "com.google.android.youtube.tv", "com.netflix.ninja", "com.amazon.amazonvideo.livingroom",
        "in.startv.hotstar", "com.disney.disneyplus", "com.jio.jioplay.tv", "com.sonyliv",
        "com.graymatrix.did", "com.google.android.videos", "com.plexapp.android",
        "com.spotify.tv.android", "org.videolan.vlc"
    )

    fun build(apps: List<AppEntry>): List<HeroItem> {
        val out = ArrayList<HeroItem>()
        val brandA = Color.rgb(84, 112, 168)
        val brandB = Palette.secondary(brandA)
        out.add(
            HeroItem(
                id = "brand",
                eyebrow = "WELCOME",
                title = "Sambhav TV OS",
                description = "A calmer, more cinematic way to reach everything you watch, play and listen to.",
                primaryLabel = "Explore Apps",
                primaryGlyph = Glyph.GRID,
                secondaryLabel = "Settings",
                secondaryGlyph = Glyph.SLIDERS,
                entry = null,
                accentA = brandA,
                accentB = brandB,
                art = ArtFactory.renderHero(960, 380, brandA, brandB, 11)
            )
        )
        val picks = ArrayList<AppEntry>()
        for (name in PRIORITY) {
            val match = apps.firstOrNull { it.packageName.startsWith(name) }
            if (match != null && !picks.contains(match)) picks.add(match)
        }
        for (category in listOf(AppCategory.VIDEO, AppCategory.MUSIC, AppCategory.GAME)) {
            for (app in apps) {
                if (picks.size >= 4) break
                if (app.category == category && !picks.contains(app)) picks.add(app)
            }
        }
        if (picks.isEmpty()) picks.addAll(apps.take(2))
        for (e in picks.take(4)) {
            val a = e.accent
            val b = Palette.secondary(a)
            out.add(
                HeroItem(
                    id = e.packageName,
                    eyebrow = e.category.eyebrow,
                    title = e.label,
                    description = describe(e),
                    primaryLabel = "Open",
                    primaryGlyph = Glyph.PLAY,
                    secondaryLabel = "All Apps",
                    secondaryGlyph = Glyph.GRID,
                    entry = e,
                    accentA = a,
                    accentB = b,
                    art = ArtFactory.renderHero(960, 380, a, b, e.packageName.hashCode())
                )
            )
        }
        return out
    }

    private fun describe(e: AppEntry): String = when (e.category) {
        AppCategory.VIDEO -> "Movies, series and live channels in full resolution, ready the moment you are."
        AppCategory.MUSIC -> "Your music and podcasts, brought to the big screen with room filling sound."
        AppCategory.GAME -> "Pick up where you left off and play on the biggest screen in the house."
        AppCategory.UTILITY -> "Open " + e.label + " directly from your Sambhav home screen."
    }
}

// =====================================================================================
//  SECTION 6 - Focus engine and scrolling containers
// =====================================================================================

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

/** Horizontal carousel with eased scrolling driven by focus rather than touch. */
class CarouselRow(context: Context, val rowHeight: Int, private val edgeInset: Float) : FrameLayout(context) {
    val track = LinearLayout(context)
    private var offset = 0f
    private var animator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false
        track.orientation = LinearLayout.HORIZONTAL
        track.clipChildren = false
        track.clipToPadding = false
        track.setPadding(edgeInset.roundToInt(), 0, edgeInset.roundToInt(), 0)
        addView(track, FrameLayout.LayoutParams(WRAP, rowHeight))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        track.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY)
        )
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowHeight)
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (focused == null) return
        var node: View? = focused
        while (node != null && node.parent !== track) node = node.parent as? View
        val item = node ?: return
        reveal(item)
    }

    private fun reveal(item: View) {
        val viewport = width.toFloat()
        val maxScroll = max(0f, track.measuredWidth - viewport)
        var target = offset
        val left = item.left.toFloat()
        val right = item.right.toFloat()
        if (left - target < edgeInset) {
            target = left - edgeInset
        } else if (right - target > viewport - edgeInset) {
            target = right - viewport + edgeInset
        }
        target = target.coerceIn(0f, maxScroll)
        animateTo(target)
    }

    private fun animateTo(target: Float) {
        if (abs(target - offset) < 1f) return
        animator?.cancel()
        val a = ValueAnimator.ofFloat(offset, target)
        a.duration = 340L
        a.interpolator = DecelerateInterpolator(2f)
        a.addUpdateListener {
            offset = it.animatedValue as Float
            track.translationX = -offset
        }
        animator = a
        a.start()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
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

// =====================================================================================
//  SECTION 7 - Chrome: brand, navigation, clock, buttons, headers
// =====================================================================================

class BrandView(context: Context) : View(context) {
    private val wordPaint = Ui.textPaint(Ui.s(30f), Color.WHITE, Fonts.medium, 0.30f)
    private val subPaint = Ui.textPaint(Ui.s(30f), Ui.alpha(Color.WHITE, 0.5f), Fonts.light, 0.30f)
    private val markRadius = Ui.s(24f)
    private val pad = Ui.s(6f)
    private val gap = Ui.s(18f)
    private val wordWidth = wordPaint.measureText("SAMBHAV")
    private val subWidth = subPaint.measureText("TV OS")
    private val spaceWidth = Ui.s(14f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = pad * 2 + markRadius * 2 + gap + wordWidth + spaceWidth + subWidth
        setMeasuredDimension(w.roundToInt(), Ui.px(64))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        BrandMark.draw(canvas, pad + markRadius, cy, markRadius)
        val x = pad + markRadius * 2 + gap
        canvas.drawText("SAMBHAV", x, Ui.baseline(wordPaint, cy), wordPaint)
        canvas.drawText("TV OS", x + wordWidth + spaceWidth, Ui.baseline(subPaint, cy), subPaint)
    }
}

class ClockView(context: Context) : View(context) {
    private val timePaint = Ui.textPaint(Ui.s(52f), Color.WHITE, Fonts.light, -0.01f)
    private val periodPaint = Ui.textPaint(Ui.s(22f), Ui.alpha(Color.WHITE, 0.6f), Fonts.medium, 0.08f)
    private val datePaint = Ui.textPaint(Ui.s(21f), Ui.alpha(Color.WHITE, 0.6f), Fonts.regular, 0.04f)
    private var time = ""
    private var period = ""
    private var date = ""
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            postDelayed(this, 15_000L)
        }
    }

    init {
        timePaint.textAlign = Paint.Align.RIGHT
        periodPaint.textAlign = Paint.Align.RIGHT
        datePaint.textAlign = Paint.Align.RIGHT
    }

    fun refresh() {
        val now = Date()
        val locale = Locale.getDefault()
        val newTime: String
        val newPeriod: String
        if (DateFormat.is24HourFormat(context)) {
            newTime = SimpleDateFormat("HH:mm", locale).format(now)
            newPeriod = ""
        } else {
            newTime = SimpleDateFormat("h:mm", locale).format(now)
            newPeriod = SimpleDateFormat("a", locale).format(now).uppercase(locale)
        }
        val newDate = SimpleDateFormat("EEEE, d MMMM", locale).format(now)
        if (newTime != time || newPeriod != period || newDate != date) {
            time = newTime
            period = newPeriod
            date = newDate
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(Ui.px(320), Ui.px(92))
    }

    override fun onDraw(canvas: Canvas) {
        val right = width.toFloat() - Ui.s(4f)
        var timeRight = right
        if (period.isNotEmpty()) {
            canvas.drawText(period, right, Ui.baseline(periodPaint, Ui.s(36f)) + Ui.s(8f), periodPaint)
            timeRight = right - periodPaint.measureText(period) - Ui.s(10f)
        }
        canvas.drawText(time, timeRight, Ui.baseline(timePaint, Ui.s(36f)), timePaint)
        canvas.drawText(date, right, Ui.baseline(datePaint, Ui.s(76f)), datePaint)
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

class NavTabView(context: Context, private val label: String) : FocusGlassView(context, 1.05f, 0f) {
    var selectedTab = false
        set(value) {
            field = value
            invalidate()
        }
    var onEnterContent: (() -> Unit)? = null
    private val bleed = Ui.s(10f)
    private val pillHeight = Ui.s(56f)
    private val padX = Ui.s(30f)
    private val surface = GlassSurface(GlassSurface.Tone.BRIGHT)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Ui.textPaint(Ui.s(26f), Color.WHITE, Fonts.medium, 0.02f)
    private val textWidth = textPaint.measureText(label)

    init {
        selectionPaint.color = Color.WHITE
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((textWidth + padX * 2 + bleed * 2).roundToInt(), (pillHeight + bleed * 2).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, pillHeight / 2f)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        if (selectedTab && f < 0.99f) {
            selectionPaint.alpha = ((1f - f) * 30f).toInt()
            canvas.drawRoundRect(bleed, bleed, width - bleed, height - bleed, pillHeight / 2f, pillHeight / 2f, selectionPaint)
        }
        if (f > 0.01f) {
            surface.drawBase(canvas, f, (f * 255f).toInt())
            surface.drawGloss(canvas, f)
        }
        val base = if (selectedTab) 1f else 0.6f
        val idle = Ui.alpha(Color.WHITE, base)
        textPaint.color = Ui.lerpColor(idle, Color.rgb(12, 13, 18), f)
        canvas.drawText(label, bleed + padX, Ui.baseline(textPaint, height / 2f), textPaint)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            keyCode == KeyEvent.KEYCODE_ENTER
        ) {
            onEnterContent?.invoke()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

class GlassButton(
    context: Context,
    private var label: String,
    private var glyph: Glyph,
    private val primary: Boolean
) : FocusGlassView(context, 1.07f, 0f) {
    private val bleed = Ui.s(22f)
    private val cardHeight = Ui.s(68f)
    private val padX = Ui.s(32f)
    private val glyphSize = Ui.s(24f)
    private val gap = Ui.s(14f)
    private val surface = GlassSurface(if (primary) GlassSurface.Tone.BRIGHT else GlassSurface.Tone.FROST)
    private val textPaint = Ui.textPaint(Ui.s(26f), Color.WHITE, Fonts.medium, 0.02f)
    private var textWidth = textPaint.measureText(label)

    init {
        contentDescription = label
    }

    fun setContent(newLabel: String, newGlyph: Glyph) {
        label = newLabel
        glyph = newGlyph
        textWidth = textPaint.measureText(label)
        contentDescription = label
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cardWidth = padX * 2 + glyphSize + gap + textWidth
        setMeasuredDimension((cardWidth + bleed * 2).roundToInt(), (cardHeight + bleed * 2).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, cardHeight / 2f)
        pivotX = w / 2f
        pivotY = h / 2f
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        surface.drawBase(canvas, f, if (primary) (205f + 50f * f).toInt() else 255)
        surface.drawGloss(canvas, f)
        val color = if (primary) Color.rgb(12, 13, 18) else Color.WHITE
        val cy = height / 2f
        val left = bleed + padX
        GlyphPainter.draw(canvas, glyph, left + glyphSize / 2f, cy, glyphSize, color)
        textPaint.color = color
        canvas.drawText(label, left + glyphSize + gap, Ui.baseline(textPaint, cy), textPaint)
    }
}

class PagerDotsView(context: Context, private val count: Int) : View(context) {
    private var position = 0f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Ui.s(9f)
    private val active = Ui.s(34f)
    private val gap = Ui.s(10f)

    init {
        paint.color = Color.WHITE
    }

    fun setPosition(target: Int, animate: Boolean) {
        animator?.cancel()
        if (!animate) {
            position = target.toFloat()
            invalidate()
            return
        }
        val a = ValueAnimator.ofFloat(position, target.toFloat())
        a.duration = 420L
        a.interpolator = DecelerateInterpolator(1.6f)
        a.addUpdateListener {
            position = it.animatedValue as Float
            invalidate()
        }
        animator = a
        a.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (count - 1) * (dot + gap) + active
        setMeasuredDimension(w.roundToInt() + 2, Ui.px(12))
    }

    override fun onDraw(canvas: Canvas) {
        var x = 0f
        val cy = height / 2f
        for (i in 0 until count) {
            val weight = (1f - abs(i - position)).coerceIn(0f, 1f)
            val w = Ui.lerp(dot, active, weight)
            paint.alpha = (255f * (0.32f + 0.58f * weight)).toInt()
            canvas.drawRoundRect(x, cy - dot / 2f, x + w, cy + dot / 2f, dot / 2f, dot / 2f, paint)
            x += w + gap
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

class SectionHeaderView(context: Context, private val title: String, private val subtitle: String?) : View(context) {
    private val titlePaint = Ui.textPaint(Ui.s(34f), Ui.alpha(Color.WHITE, 0.96f), Fonts.medium, 0.005f)
    private val subPaint = Ui.textPaint(Ui.s(22f), Ui.alpha(Color.WHITE, 0.45f), Fonts.regular, 0.02f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.px(SECTION_HEADER_HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val x = Ui.s(96f)
        val y = Ui.s(52f)
        canvas.drawText(title, x, y, titlePaint)
        if (!subtitle.isNullOrEmpty()) {
            canvas.drawText(subtitle, x + titlePaint.measureText(title) + Ui.s(20f), y, subPaint)
        }
    }
}

class PageTitleView(context: Context, private val title: String, private val subtitle: String) : View(context) {
    private val titlePaint = Ui.textPaint(Ui.s(60f), Color.WHITE, Fonts.light, -0.01f)
    private val subPaint = Ui.textPaint(Ui.s(25f), Ui.alpha(Color.WHITE, 0.55f), Fonts.regular, 0.01f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), Ui.px(PAGE_TITLE_HEIGHT))
    }

    override fun onDraw(canvas: Canvas) {
        val x = Ui.s(96f)
        canvas.drawText(title, x, Ui.s(206f), titlePaint)
        canvas.drawText(subtitle, x, Ui.s(246f), subPaint)
    }
}

// =====================================================================================
//  SECTION 8 - Application tiles, settings tiles and informational panels
// =====================================================================================

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

class SettingsTile(context: Context, private val action: SettingsAction) : FocusGlassView(context, 1.08f, Ui.s(4f)) {
    private val bleed = Ui.s(TILE_BLEED)
    private val cardW = Ui.s(300)
    private val cardH = Ui.s(190)
    val tileWidth: Int = (cardW + bleed * 2).roundToInt()
    val tileHeight: Int = heightFor()
    private val surface = GlassSurface(GlassSurface.Tone.SMOKED)
    private val cardRect = RectF()
    private val titlePaint = Ui.textPaint(Ui.s(29f), Color.WHITE, Fonts.medium)
    private val subPaint = Ui.textPaint(Ui.s(20f), Ui.alpha(Color.WHITE, 0.55f), Fonts.regular, 0.01f)
    private var titleText = action.title
    private var subText = action.subtitle

    init {
        contentDescription = action.title
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(tileWidth, tileHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cardRect.set(bleed, bleed, w - bleed, h - bleed)
        surface.layout(cardRect.left, cardRect.top, cardRect.right, cardRect.bottom, Ui.s(24f), Palette.silver)
        pivotX = w / 2f
        pivotY = h / 2f
        val maxText = cardW - Ui.s(56f)
        titleText = TextUtils.ellipsize(action.title, titlePaint, maxText, TextUtils.TruncateAt.END).toString()
        subText = TextUtils.ellipsize(action.subtitle, subPaint, maxText, TextUtils.TruncateAt.END).toString()
    }

    override fun onDraw(canvas: Canvas) {
        val f = focusFraction
        surface.drawBase(canvas, f)
        surface.drawGloss(canvas, f)
        val x = cardRect.left + Ui.s(28f)
        GlyphPainter.draw(
            canvas, action.glyph, x + Ui.s(24f), cardRect.top + Ui.s(52f), Ui.s(48f),
            Ui.alpha(Color.WHITE, 0.78f + 0.22f * f)
        )
        canvas.drawText(titleText, x, cardRect.bottom - Ui.s(62f), titlePaint)
        canvas.drawText(subText, x, cardRect.bottom - Ui.s(28f), subPaint)
    }

    companion object {
        fun heightFor(): Int = (Ui.s(190) + Ui.s(TILE_BLEED) * 2).roundToInt()
    }
}

class InfoPanelView(context: Context, private val cells: List<Pair<String, String>>) : View(context) {
    private val bleed = Ui.s(TILE_BLEED)
    private val panelW = Ui.s(980)
    private val panelH = Ui.s(190)
    private val surface = GlassSurface(GlassSurface.Tone.FROST)
    private val labelPaint = Ui.textPaint(Ui.s(16f), Ui.alpha(Color.WHITE, 0.5f), Fonts.medium, 0.18f)
    private val valuePaint = Ui.textPaint(Ui.s(26f), Ui.alpha(Color.WHITE, 0.94f), Fonts.regular)

    init {
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((panelW + bleed * 2).roundToInt(), SettingsTile.heightFor())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, Ui.s(24f))
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        surface.drawGloss(canvas, 0f)
        val columns = 3
        val colW = (panelW - Ui.s(64f)) / columns
        for ((i, cell) in cells.withIndex()) {
            val col = i % columns
            val row = i / columns
            val x = bleed + Ui.s(36f) + col * colW
            val y = bleed + Ui.s(52f) + row * Ui.s(80f)
            canvas.drawText(cell.first, x, y, labelPaint)
            val value = TextUtils.ellipsize(cell.second, valuePaint, colW - Ui.s(24f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(value, x, y + Ui.s(34f), valuePaint)
        }
    }
}

class EmptyPanelView(context: Context, private val title: String, private val message: String) : View(context) {
    private val bleed = Ui.s(TILE_BLEED)
    private val surface = GlassSurface(GlassSurface.Tone.FROST)
    private val titlePaint = Ui.textPaint(Ui.s(32f), Color.WHITE, Fonts.medium)
    private val msgPaint = Ui.textPaint(Ui.s(23f), Ui.alpha(Color.WHITE, 0.6f), Fonts.regular, 0.01f)

    init {
        isFocusable = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((Ui.s(900) + bleed * 2).roundToInt(), (Ui.s(150) + bleed * 2).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, Ui.s(24f))
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        surface.drawGloss(canvas, 0f)
        GlyphPainter.draw(canvas, Glyph.INFO, bleed + Ui.s(70f), height / 2f, Ui.s(54f), Ui.alpha(Color.WHITE, 0.7f))
        val x = bleed + Ui.s(130f)
        canvas.drawText(title, x, height / 2f - Ui.s(6f), titlePaint)
        canvas.drawText(message, x, height / 2f + Ui.s(34f), msgPaint)
    }
}

// =====================================================================================
//  SECTION 9 - Hero banner
// =====================================================================================

class HeroArtLayer(context: Context) : View(context) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    fun setArt(bmp: Bitmap, animate: Boolean) {
        if (bmp === current) return
        animator?.cancel()
        if (!animate || current == null) {
            previous = null
            current = bmp
            fade = 1f
            invalidate()
            return
        }
        previous = current
        current = bmp
        fade = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 700L
        a.addUpdateListener {
            fade = it.animatedValue as Float
            invalidate()
        }
        animator = a
        a.start()
    }

    override fun onDraw(canvas: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        val prev = previous
        if (prev != null && fade < 1f) {
            paint.alpha = 255
            canvas.drawBitmap(prev, null, dst, paint)
        }
        val cur = current ?: return
        paint.alpha = (fade * 255f).toInt()
        canvas.drawBitmap(cur, null, dst, paint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

class FeaturedCardView(context: Context) : View(context) {
    private val bleed = Ui.s(44f)
    private val cardW = Ui.s(600)
    private val cardH = Ui.s(338)
    val viewWidth: Int = (cardW + bleed * 2).roundToInt()
    val viewHeight: Int = (cardH + bleed * 2).roundToInt()
    private val surface = GlassSurface(GlassSurface.Tone.SMOKED)
    private val mediaPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val namePaint = Ui.textPaint(Ui.s(30f), Color.WHITE, Fonts.medium)
    private val subPaint = Ui.textPaint(Ui.s(19f), Ui.alpha(Color.WHITE, 0.62f), Fonts.medium, 0.14f)
    private var current: HeroItem? = null
    private var previous: HeroItem? = null
    private var progress = 1f
    private var animator: ValueAnimator? = null

    init {
        isFocusable = false
    }

    fun setItem(item: HeroItem, animate: Boolean) {
        if (item === current) return
        animator?.cancel()
        if (!animate || current == null) {
            previous = null
            current = item
            progress = 1f
            invalidate()
            return
        }
        previous = current
        current = item
        progress = 0f
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 560L
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        animator = a
        a.start()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(viewWidth, viewHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        surface.layout(bleed, bleed, w - bleed, h - bleed, Ui.s(32f))
        scrimPaint.shader = LinearGradient(
            0f, bleed + cardH * 0.55f, 0f, bleed + cardH,
            Color.argb(0, 0, 0, 0), Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        surface.drawBase(canvas, 0f)
        canvas.save()
        surface.clipTo(canvas)
        val r = surface.rect
        val prev = previous
        if (prev != null && progress < 1f) {
            val layer = canvas.saveLayerAlpha(r.left, r.top, r.right, r.bottom, ((1f - progress) * 255f).toInt())
            drawItem(canvas, prev)
            canvas.restoreToCount(layer)
        }
        val cur = current
        if (cur != null) {
            val layer = canvas.saveLayerAlpha(r.left, r.top, r.right, r.bottom, (progress * 255f).toInt())
            drawItem(canvas, cur)
            canvas.restoreToCount(layer)
        }
        canvas.restore()
        surface.drawGloss(canvas, 0f)
    }

    private fun drawItem(canvas: Canvas, item: HeroItem) {
        val r = surface.rect
        val entry = item.entry
        val banner = entry?.banner
        if (banner != null) {
            BitmapUtil.drawCropped(canvas, banner, r, mediaPaint)
        } else {
            glowPaint.shader = RadialGradient(
                r.centerX(), r.centerY() - r.height() * 0.1f, r.width() * 0.62f,
                intArrayOf(Ui.alpha(item.accentA, 0.7f), Ui.alpha(item.accentB, 0.22f), Ui.alpha(item.accentB, 0f)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawRect(r, glowPaint)
            if (entry == null) {
                BrandMark.draw(canvas, r.centerX(), r.centerY() - r.height() * 0.08f, r.height() * 0.24f)
            } else {
                IconPlate.draw(
                    canvas, entry.icon, entry.initial, entry.accent,
                    r.centerX(), r.centerY() - r.height() * 0.08f, r.height() * 0.46f
                )
            }
        }
        canvas.drawRect(r, scrimPaint)
        val x = r.left + Ui.s(32f)
        canvas.drawText(item.eyebrow, x, r.bottom - Ui.s(62f), subPaint)
        canvas.drawText(item.title, x, r.bottom - Ui.s(26f), namePaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

interface LauncherHost {
    fun launchApp(entry: AppEntry)
    fun openSetting(action: SettingsAction)
    fun selectTab(index: Int, focusContent: Boolean)
    fun onContentFocused(key: String, accentA: Int, accentB: Int)
    fun onHeroItemChanged(item: HeroItem)
}

class HeroView(
    context: Context,
    private val items: List<HeroItem>,
    private val host: LauncherHost
) : FrameLayout(context) {
    private val artLayer = HeroArtLayer(context)
    private val card = FeaturedCardView(context)
    private val column = LinearLayout(context)
    private val eyebrow = Ui.makeText(context, "", Ui.s(21f), Ui.alpha(Color.WHITE, 0.62f), Fonts.medium, 0.24f)
    private val title = Ui.makeText(context, "", Ui.s(80f), Color.WHITE, Fonts.light, -0.01f)
    private val description = Ui.makeText(context, "", Ui.s(27f), Ui.alpha(Color.WHITE, 0.72f), Fonts.regular, 0.005f)
    val primaryButton = GlassButton(context, "Open", Glyph.PLAY, true)
    private val secondaryButton = GlassButton(context, "All Apps", Glyph.GRID, false)
    private val dots = PagerDotsView(context, items.size)
    private var index = 0
    private var parallax = 0f
    private val rotate = Runnable { autoAdvance() }

    init {
        clipChildren = false
        clipToPadding = false
        addView(artLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        val cardParams = FrameLayout.LayoutParams(card.viewWidth, card.viewHeight)
        cardParams.gravity = Gravity.TOP or Gravity.END
        cardParams.topMargin = Ui.px(190) - Ui.px(44)
        cardParams.rightMargin = Ui.px(96) - Ui.px(44)
        addView(card, cardParams)

        column.orientation = LinearLayout.VERTICAL
        column.clipChildren = false
        column.clipToPadding = false
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        description.maxLines = 3
        description.ellipsize = TextUtils.TruncateAt.END
        description.setLineSpacing(0f, 1.15f)
        column.addView(eyebrow, LinearLayout.LayoutParams(WRAP, WRAP))
        val titleParams = LinearLayout.LayoutParams(Ui.px(960), WRAP)
        titleParams.topMargin = Ui.px(12)
        column.addView(title, titleParams)
        val descParams = LinearLayout.LayoutParams(Ui.px(780), WRAP)
        descParams.topMargin = Ui.px(14)
        column.addView(description, descParams)

        val buttons = LinearLayout(context)
        buttons.orientation = LinearLayout.HORIZONTAL
        buttons.clipChildren = false
        val buttonParams = LinearLayout.LayoutParams(WRAP, WRAP)
        buttonParams.topMargin = Ui.px(20)
        buttonParams.leftMargin = -Ui.px(22)
        column.addView(buttons, buttonParams)
        buttons.addView(primaryButton, LinearLayout.LayoutParams(WRAP, WRAP))
        buttons.addView(secondaryButton, LinearLayout.LayoutParams(WRAP, WRAP))

        val dotParams = LinearLayout.LayoutParams(WRAP, WRAP)
        dotParams.topMargin = Ui.px(2)
        column.addView(dots, dotParams)

        val columnParams = FrameLayout.LayoutParams(Ui.px(1000), WRAP)
        columnParams.leftMargin = Ui.px(96)
        columnParams.topMargin = Ui.px(220)
        addView(column, columnParams)

        primaryButton.focusKey = "hero:primary"
        secondaryButton.focusKey = "hero:secondary"
        primaryButton.onFocusGained = { announceFocus("hero:primary") }
        secondaryButton.onFocusGained = { announceFocus("hero:secondary") }
        primaryButton.setOnClickListener { onPrimary() }
        secondaryButton.setOnClickListener { onSecondary() }
        primaryButton.onEdgeKey = { key -> if (key == KeyEvent.KEYCODE_DPAD_LEFT) { step(-1); true } else false }
        secondaryButton.onEdgeKey = { key -> if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { step(1); true } else false }

        if (items.isNotEmpty()) apply(0, false, 1)
    }

    fun currentItem(): HeroItem? = items.getOrNull(index)

    private fun announceFocus(key: String) {
        val item = currentItem() ?: return
        host.onContentFocused(key, item.accentA, item.accentB)
    }

    private fun onPrimary() {
        val item = currentItem() ?: return
        val entry = item.entry
        if (entry != null) host.launchApp(entry) else host.selectTab(3, true)
    }

    private fun onSecondary() {
        val item = currentItem() ?: return
        if (item.entry != null) host.selectTab(3, true) else host.selectTab(4, true)
    }

    private fun step(direction: Int) {
        if (items.size < 2) return
        apply((index + direction + items.size) % items.size, true, direction)
        restartRotation()
    }

    private fun autoAdvance() {
        if (items.size > 1 && isShown && parallax < Ui.s(120f)) {
            apply((index + 1) % items.size, true, 1)
        }
        startRotation()
    }

    private fun apply(newIndex: Int, animate: Boolean, direction: Int) {
        val item = items[newIndex]
        index = newIndex
        artLayer.setArt(item.art, animate)
        card.setItem(item, animate)
        dots.setPosition(newIndex, animate)
        if (animate) {
            column.animate().cancel()
            val shift = direction * Ui.s(24f)
            column.animate().alpha(0f).translationX(-shift).setDuration(150L).withEndAction {
                bindText(items[index])
                column.translationX = shift
                column.animate().alpha(1f).translationX(0f).setDuration(280L)
                    .setInterpolator(DecelerateInterpolator(1.6f)).start()
            }.start()
        } else {
            bindText(item)
        }
        host.onHeroItemChanged(item)
    }

    private fun bindText(item: HeroItem) {
        eyebrow.text = item.eyebrow
        title.text = item.title
        description.text = item.description
        primaryButton.setContent(item.primaryLabel, item.primaryGlyph)
        secondaryButton.setContent(item.secondaryLabel, item.secondaryGlyph)
    }

    fun applyParallax(scroll: Float) {
        parallax = scroll
        artLayer.translationY = scroll * 0.45f
        card.translationY = scroll * 0.12f
    }

    fun startRotation() {
        removeCallbacks(rotate)
        if (items.size > 1) postDelayed(rotate, 9000L)
    }

    fun stopRotation() {
        removeCallbacks(rotate)
    }

    private fun restartRotation() {
        startRotation()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(rotate)
        column.animate().cancel()
        super.onDetachedFromWindow()
    }
}

// =====================================================================================
//  SECTION 10 - Transient overlays
// =====================================================================================

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

class LoadingView(context: Context) : View(context) {
    private var phase = 0f
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tick = object : Runnable {
        override fun run() {
            phase = (phase + 0.035f) % 1f
            invalidate()
            postDelayed(this, 50L)
        }
    }

    init {
        dotPaint.color = Color.WHITE
        isFocusable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        BrandMark.draw(canvas, cx, cy - Ui.s(40f), Ui.s(54f))
        for (i in 0..2) {
            val t = (phase - i * 0.16f + 1f) % 1f
            val a = 0.25f + 0.75f * (0.5f + 0.5f * sin(t * TAU))
            dotPaint.alpha = (a * 255f).toInt()
            canvas.drawCircle(cx + (i - 1) * Ui.s(34f), cy + Ui.s(70f), Ui.s(6f), dotPaint)
        }
    }
}