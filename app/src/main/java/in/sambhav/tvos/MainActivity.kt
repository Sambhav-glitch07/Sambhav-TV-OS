package `in`.sambhav.tvos

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val bg = Color.rgb(9, 11, 16)
    private val panel = Color.rgb(22, 25, 33)
    private val panelFocus = Color.rgb(47, 52, 65)
    private val white = Color.rgb(246, 247, 250)
    private val muted = Color.rgb(160, 166, 180)
    private val accent = Color.rgb(204, 215, 237)
    private lateinit var root: LinearLayout
    private lateinit var appsRow: LinearLayout
    private lateinit var featuredButton: TextView
    private var appViews: List<View> = emptyList()
    private var installedApps: List<LaunchableApp> = emptyList()

    data class LaunchableApp(val label: String, val packageName: String, val icon: android.graphics.drawable.Drawable?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        buildHome()
    }

    override fun onResume() {
        super.onResume()
        if (::appsRow.isInitialized) loadApps()
    }

    private fun buildHome() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(46), dp(26), dp(46), dp(22))
            setBackgroundColor(bg)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = text("SAMBHAV", 19f, white, true).apply { letterSpacing = 0.16f }
        top.addView(brand, LinearLayout.LayoutParams(0, dp(42), 1f))
        val subtitle = text("YOUR ENTERTAINMENT SPACE", 10f, muted, true).apply { letterSpacing = 0.16f }
        top.addView(subtitle)
        top.addView(space(24, 1))
        top.addView(text(clockNow(), 13f, white, true))
        root.addView(top)

        root.addView(space(1, 18))
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(24), dp(28), dp(22))
            background = rounded(Color.rgb(27, 31, 43), 22)
        }
        hero.addView(text("A BETTER WAY TO WATCH", 11f, accent, true).apply { letterSpacing = 0.18f })
        hero.addView(space(1, 10))
        hero.addView(text("Your screen.\nYour world.", 34f, white, true).apply {
            setLineSpacing(dp(1).toFloat(), 1f)
        })
        hero.addView(space(1, 8))
        hero.addView(text("Open your favourite apps from one calm, cinematic home.", 14f, muted, false))
        hero.addView(space(1, 18))
        featuredButton = text("  EXPLORE YOUR APPS   ›  ", 13f, bg, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(12), dp(18), dp(12))
            background = rounded(accent, 30)
            isFocusable = true
            isClickable = true
            setOnClickListener { focusFirstApp() }
            setOnFocusChangeListener { v, hasFocus ->
                v.alpha = if (hasFocus) 0.82f else 1f
            }
        }
        hero.addView(featuredButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        root.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.05f))

        root.addView(space(1, 18))
        val rowTitle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        rowTitle.addView(text("YOUR APPS", 16f, white, true), LinearLayout.LayoutParams(0, dp(30), 1f))
        rowTitle.addView(text("Use  ←  →  and  OK  on your remote", 11f, muted, false))
        root.addView(rowTitle)
        root.addView(space(1, 10))

        val scroller = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            isHorizontalScrollBarEnabled = false
            isFocusable = false
        }
        appsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        scroller.addView(appsRow, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(122)))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(text("SAMBHAV TV OS", 10f, muted, true), LinearLayout.LayoutParams(0, dp(22), 1f))
        footer.addView(text(dateNow(), 10f, muted, false))
        root.addView(space(1, 6))
        root.addView(footer)
        loadApps()
        root.post { featuredButton.requestFocus() }
    }

    private fun loadApps() {
        val pm = packageManager
        val found = LinkedHashMap<String, LaunchableApp>()
        val categories = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        )
        for (intent in categories) {
            val results = try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            } catch (_: Exception) { emptyList() }
            for (ri in results) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg == packageName) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifBlank { pkg }
                if (!found.containsKey(pkg)) {
                    val icon = try { ri.loadIcon(pm) } catch (_: Exception) { null }
                    found[pkg] = LaunchableApp(label, pkg, icon)
                }
            }
        }
        installedApps = found.values.sortedBy { it.label.lowercase(Locale.getDefault()) }
        appsRow.removeAllViews()
        if (installedApps.isEmpty()) {
            val empty = text("No launchable apps found", 13f, muted, false)
            appsRow.addView(empty)
            return
        }
        val views = mutableListOf<View>()
        for (app in installedApps) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(10), dp(12), dp(8))
                background = rounded(panel, 16)
                isFocusable = true
                isClickable = true
                contentDescription = app.label
                setOnFocusChangeListener { v, focused ->
                    v.background = rounded(if (focused) panelFocus else panel, 16)
                    v.scaleX = if (focused) 1.04f else 1f
                    v.scaleY = if (focused) 1.04f else 1f
                }
                setOnClickListener { launchApp(app) }
                setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_UP &&
                        (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                        launchApp(app); true
                    } else false
                }
            }
            val icon = android.widget.ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            }
            card.addView(icon, LinearLayout.LayoutParams(dp(42), dp(42)))
            card.addView(space(1, 7))
            val label = text(app.label, 11f, white, true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(label, LinearLayout.LayoutParams(dp(112), dp(20)))
            val lp = LinearLayout.LayoutParams(dp(142), dp(104))
            lp.marginEnd = dp(12)
            appsRow.addView(card, lp)
            views.add(card)
        }
        appViews = views
    }

    private fun launchApp(app: LaunchableApp) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            AlertDialog.Builder(this)
                .setTitle(app.label)
                .setMessage("This app is installed but does not provide a launchable activity.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // The app may be disabled or restricted by the TV manufacturer.
        } catch (_: SecurityException) {
            // Do not crash if the manufacturer blocks the launch.
        }
    }

    private fun focusFirstApp() {
        if (appViews.isNotEmpty()) appViews.first().requestFocus()
        else {
            AlertDialog.Builder(this)
                .setTitle("No apps found")
                .setMessage("Install apps from your TV's app store, then reopen Sambhav TV OS.")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun space(width: Int, height: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(width), dp(height))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun clockNow(): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
    private fun dateNow(): String = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault()).format(Date())
}
