package dev.cloudsim.gateway.adb

import dev.cloudsim.gateway.L

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small box floating above the system's pairing dialog, to type its 6-digit code without leaving the dialog (which
 * would close it). MIUI hides the reply field of notifications, so this is the main way in (user's decision, 2026-11-04);
 * it needs "display over other apps" and is shown only while pairing.
 */
object PairingOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun allowed(context: Context) = Settings.canDrawOverlays(context)

    fun show(context: Context, onCode: (String) -> Unit) = main.post {
        if (view != null || !allowed(context)) return@post
        val wm = context.getSystemService(WindowManager::class.java)
        val dp = context.resources.displayMetrics.density
        val input = EditText(context).apply {
            hint = L.t("6 位配对码")
            inputType = InputType.TYPE_CLASS_NUMBER
            textSize = 24f
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 16 * dp; setStroke((2 * dp).toInt(), Color.rgb(103, 80, 164)) }
            addView(TextView(context).apply { text = L.t("SimNest 网关：把下面窗口里的 6 位配对码填在这里"); textSize = 15f; setTextColor(Color.BLACK) })
            addView(input)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(Button(context).apply {
                    text = L.t("配对")
                    setOnClickListener {
                        val code = input.text.toString().filter(Char::isDigit)
                        if (code.length == 6) { hide(context); onCode(code) } else input.error = L.t("要 6 位数字")
                    }
                })
                addView(Button(context).apply { text = L.t("取消"); setOnClickListener { hide(context) } })
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            y = (48 * dp).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        }
        wm.addView(box, params)
        view = box
        input.requestFocus()
        input.postDelayed({ context.getSystemService(InputMethodManager::class.java).showSoftInput(input, 0) }, 300)
    }

    fun hide(context: Context) = main.post {
        view?.let { runCatching { context.getSystemService(WindowManager::class.java).removeView(it) } }
        view = null
    }
}
