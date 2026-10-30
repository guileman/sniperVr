package com.guileman.snipervr.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Utilidades para armar la interfaz en código, sin XML ni plantillas. */
object Ui {
    val ACCENT = Color.rgb(214, 170, 72)
    val PANEL = Color.argb(200, 18, 22, 18)

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density)

    fun bg(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = Color.TRANSPARENT) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    fun button(ctx: Context, text: String, sizeSp: Float = 14f, onClick: (Button) -> Unit): Button =
        Button(ctx).apply {
            this.text = text
            isAllCaps = false
            textSize = sizeSp
            setTextColor(Color.WHITE)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            background = bg(Color.argb(150, 20, 20, 20), dp(ctx, 10f), dp(ctx, 1f).toInt(), Color.argb(180, 214, 170, 72))
            val p = dp(ctx, 6f).toInt()
            setPadding(p * 2, p, p * 2, p)
            minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
            stateListAnimator = null
            setOnClickListener { onClick(this) }
        }

    fun label(ctx: Context, text: String, sizeSp: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
        }

    /**
     * Fila de opciones excluyentes. [onSelect] recibe el índice elegido.
     */
    fun segmented(ctx: Context, title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(ctx, title, 13f, ACCENT, true).apply { setPadding(0, dp(ctx, 10f).toInt(), 0, dp(ctx, 4f).toInt()) })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val buttons = ArrayList<TextView>()
        fun paint(sel: Int) {
            buttons.forEachIndexed { i, b ->
                val on = i == sel
                b.background = bg(if (on) ACCENT else Color.argb(140, 30, 30, 30), dp(ctx, 8f), dp(ctx, 1f).toInt(), if (on) ACCENT else Color.argb(120, 255, 255, 255))
                b.setTextColor(if (on) Color.BLACK else Color.WHITE)
            }
        }
        options.forEachIndexed { i, o ->
            val b = TextView(ctx).apply {
                text = o
                textSize = 13f
                gravity = Gravity.CENTER
                val p = dp(ctx, 8f).toInt()
                setPadding(p, p, p, p)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                setOnClickListener { paint(i); onSelect(i) }
            }
            buttons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6f).toInt()
            })
        }
        paint(selected)
        col.addView(row)
        return col
    }
}
