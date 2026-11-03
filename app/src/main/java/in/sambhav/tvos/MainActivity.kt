package `in`.sambhav.tvos

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.animation.DecelerateInterpolator
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val backgroundColor = Color.rgb(8, 10, 16)
    private val glassColor = Color.argb(145, 42, 47, 61)
    private val glassFocus = Color.argb(225, 91, 103, 130)
    private val white = Color.rgb(248, 249, 252)
    private val secondary = Color.rgb(165, 173, 190)
    private val accent = Color.rgb(205, 218, 245)

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var appRow: LinearLayout
    private lateinit var hero: LinearLayout
    private lateinit var clock: TextView

    private val appCards = mutableListOf<View>()

    data class TVApp(
        val name: String,
        val packageName: String,
        val icon: Drawable?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        buildInterface()
    }

    private fun buildInterface() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(55), dp(30), dp(55), dp(25))
            setBackgroundColor(backgroundColor)
            isFocusable = true
            isFocusableInTouchMode = true
        }

        setContentView(root)

        buildNavigation()
        buildHero()
        buildSectionHeader()
        buildApplications()
        buildFooter()

        root.post {
            root.requestFocus()
        }
    }

    private fun buildNavigation() {
        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val logo = createText(
            "SAMBHAV",
            23f,
            white,
            true
        )

        logo.letterSpacing = 0.20f

        navigation.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )

        val sections = listOf(
            "Home",
            "Movies",
            "Shows",
            "Apps"
        )

        sections.forEachIndexed { index, section ->

            val item = createText(
                section,
                14f,
                if (index == 0) white else secondary,
                index == 0
            )

            item.gravity = Gravity.CENTER
            item.setPadding(dp(15), 0, dp(15), 0)
            item.isFocusable = true

            item.setOnFocusChangeListener { view, focused ->
                view.alpha = if (focused) 1f else 0.65f
                view.scaleX = if (focused) 1.08f else 1f
                view.scaleY = if (focused) 1.08f else 1f
            }

            navigation.addView(
                item,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(46)
                )
            )
        }

        navigation.addView(space(22, 1))

        clock = createText(
            currentTime(),
            15f,
            white,
            true
        )

        navigation.addView(clock)

        root.addView(navigation)
        root.addView(space(1, 24))
    }

    private fun buildHero() {
        hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(45), dp(30), dp(45), dp(30))
            background = glassBackground(
                Color.rgb(27, 33, 48),
                30
            )
            elevation = dp(8).toFloat()
        }

        val eyebrow = createText(
            "THE NEXT GENERATION OF ENTERTAINMENT",
            11f,
            accent,
            true
        )

        eyebrow.letterSpacing = 0.18f

        hero.addView(eyebrow)
        hero.addView(space(1, 15))

        val heading = createText(
            "Entertainment.\nReimagined.",
            43f,
            white,
            true
        )

        heading.setLineSpacing(dp(3).toFloat(), 1f)

        hero.addView(heading)
        hero.addView(space(1, 12))

        hero.addView(
            createText(
                "A cinematic experience, designed around you.",
                15f,
                secondary,
                false
            )
        )

        hero.addView(space(1, 24))

        val button = createGlassButton(
            "EXPLORE YOUR APPS"
        ) {
            focusFirstApp()
        }

        hero.addView(
            button,
            LinearLayout.LayoutParams(
                dp(230),
                dp(53)
            )
        )

        root.addView(
            hero,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.15f
            )
        )

        root.addView(space(1, 25))
    }

    private fun buildSectionHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            createText(
                "YOUR APPLICATIONS",
                17f,
                white,
                true
            ),
            LinearLayout.LayoutParams(0, dp(35), 1f)
        )

        header.addView(
            createText(
                "SELECT AN APP TO CONTINUE",
                10f,
                secondary,
                true
            )
        )

        root.addView(header)
        root.addView(space(1, 12))
    }

    private fun buildApplications() {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFocusable = false
        }

        appRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        scroll.addView(
            appRow,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(135)
            )
        )

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(145)
            )
        )

        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        appRow.removeAllViews()
        appCards.clear()

        val applications = discoverApps()

        if (applications.isEmpty()) {
            appRow.addView(
                createText(
                    "No launchable applications found.",
                    14f,
                    secondary,
                    false
                )
            )
            return
        }

        applications.forEach { app ->
            addApplicationCard(app)
        }
    }

    private fun discoverApps(): List<TVApp> {
        val discovered = LinkedHashMap<String, TVApp>()

        val categories = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(
                Intent.CATEGORY_LEANBACK_LAUNCHER
            ),
            Intent(Intent.ACTION_MAIN).addCategory(
                Intent.CATEGORY_LAUNCHER
            )
        )

        categories.forEach { intent ->

            val results = try {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
                )
            } catch (_: Exception) {
                emptyList()
            }

            results.forEach { result ->

                val packageName =
                    result.activityInfo?.packageName ?: return@forEach

                if (packageName == packageName()) {
                    return@forEach
                }

                if (!discovered.containsKey(packageName)) {

                    val name = try {
                        result.loadLabel(packageManager).toString()
                    } catch (_: Exception) {
                        packageName
                    }

                    val icon = try {
                        result.loadIcon(packageManager)
                    } catch (_: Exception) {
                        null
                    }

                    discovered[packageName] = TVApp(
                        name,
                        packageName,
                        icon
                    )
                }
            }
        }

        return discovered.values.sortedBy {
            it.name.toLowerCase(Locale.getDefault())
        }
    }

    private fun packageName(): String {
        return applicationContext.packageName
    }

    private fun addApplicationCard(app: TVApp) {

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(10))

            background = glassBackground(
                glassColor,
                20
            )

            isFocusable = true
            isClickable = true

            setOnFocusChangeListener { view, focused ->

                view.background = glassBackground(
                    if (focused) glassFocus else glassColor,
                    20
                )

                view.animate()
                    .scaleX(if (focused) 1.09f else 1f)
                    .scaleY(if (focused) 1.09f else 1f)
                    .translationZ(if (focused) dp(10).toFloat() else 0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }

            setOnClickListener {
                launchApplication(app)
            }

            setOnKeyListener { _, keyCode, event ->

                if (
                    event.action == KeyEvent.ACTION_UP &&
                    (
                        keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER
                    )
                ) {
                    launchApplication(app)
                    true
                } else {
                    false
                }
            }
        }

        val icon = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        card.addView(
            icon,
            LinearLayout.LayoutParams(
                dp(48),
                dp(48)
            )
        )

        card.addView(space(1, 9))

        val label = createText(
            app.name,
            11f,
            white,
            true
        ).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        card.addView(
            label,
            LinearLayout.LayoutParams(
                dp(115),
                dp(22)
            )
        )

        val params = LinearLayout.LayoutParams(
            dp(145),
            dp(122)
        )

        params.marginEnd = dp(14)

        appRow.addView(card, params)
        appCards.add(card)
    }

    private fun launchApplication(app: TVApp) {

        val launchIntent =
            packageManager.getLeanbackLaunchIntentForPackage(
                app.packageName
            ) ?: packageManager.getLaunchIntentForPackage(
                app.packageName
            )

        if (launchIntent == null) {
            Toast.makeText(
                this,
                "Application cannot be opened",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        try {
            startActivity(launchIntent)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Unable to launch application",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun focusFirstApp() {
        if (appCards.isNotEmpty()) {
            appCards.first().requestFocus()
        } else {
            Toast.makeText(
                this,
                "No applications available",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun buildFooter() {

        root.addView(space(1, 15))

        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        footer.addView(
            createText(
                "SAMBHAV TV OS",
                10f,
                secondary,
                true
            ),
            LinearLayout.LayoutParams(0, dp(25), 1f)
        )

        footer.addView(
            createText(
                "DESIGNED FOR YOUR LIVING ROOM",
                10f,
                secondary,
                false
            )
        )

        root.addView(footer)
    }

    private fun createGlassButton(
        title: String,
        action: () -> Unit
    ): TextView {

        return createText(
            title,
            12f,
            white,
            true
        ).apply {

            gravity = Gravity.CENTER

            background = glassBackground(
                Color.argb(190, 105, 119, 148),
                30
            )

            isFocusable = true
            isClickable = true

            setOnClickListener {
                action()
            }

            setOnFocusChangeListener { view, focused ->

                view.alpha = if (focused) 1f else 0.85f

                view.animate()
                    .scaleX(if (focused) 1.04f else 1f)
                    .scaleY(if (focused) 1.04f else 1f)
                    .setDuration(160)
                    .start()
            }
        }
    }

    private fun glassBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
            setStroke(
                dp(1),
                Color.argb(65, 230, 237, 255)
            )
        }
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean
    ): TextView {

        return TextView(this).apply {

            text = value
            textSize = size
            setTextColor(color)

            typeface = Typeface.create(
                "sans-serif",
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )

            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = true
        }
    }

    private fun space(
        width: Int,
        height: Int
    ): View {

        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                dp(width),
                dp(height)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * resources.displayMetrics.density
        ).toInt()
    }

    private fun currentTime(): String {
        return SimpleDateFormat(
            "h:mm a",
            Locale.getDefault()
        ).format(Date())
    }

    override fun onResume() {
        super.onResume()

        if (::appRow.isInitialized) {
            loadInstalledApps()
        }
    }
}
