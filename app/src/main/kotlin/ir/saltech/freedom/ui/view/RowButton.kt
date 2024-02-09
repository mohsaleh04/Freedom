package ir.saltech.freedom.ui.view

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.Gravity.CENTER
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ir.saltech.freedom.R

class RowButton(context: Context?, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    private val layout: LinearLayout
        get() = findViewById(R.id.row_button_layout)
    private val textView: TextView
        get() = findViewById(R.id.row_button_text)
    private val imageView: ImageView
        get() = findViewById(R.id.row_button_icon)

    private var animatorSet: AnimatorSet? = null
    private val pressScale = 0.98f

    var text: String
        get() = textView.text.toString()
        set(value) {
            textView.text = value
        }
    var icon: Drawable
        get() = imageView.drawable
        set(value) {
            imageView.setImageDrawable(value)
        }

    init {
        LayoutInflater.from(context).inflate(R.layout.view_row_button, this, true)
        orientation = HORIZONTAL
        gravity = CENTER
        isEnabled = true
        attrs?.let {
            val typedArray = context!!.obtainStyledAttributes(it, R.styleable.RowButton)
            val messageText = typedArray.getString(R.styleable.RowButton_android_text)
            val iconSrc = typedArray.getDrawable(R.styleable.RowButton_android_src)
            textView.text = messageText
            imageView.setImageDrawable(iconSrc)
            typedArray.recycle()
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val disabledColor = context.resources.getColor(R.color.disable_button_fade, context.theme)
        val enabledColor = context.resources.getColor(R.color.colorAccent, context.theme)
        layout.isActivated = enabled
        for (i in 0 until layout.childCount) {
            layout.getChildAt(i).isEnabled = enabled
        }
        if (enabled) {
            layout.backgroundTintList = ColorStateList.valueOf(enabledColor)
        } else {
            layout.backgroundTintList = ColorStateList.valueOf(disabledColor)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun setOnClickListener(l: OnClickListener?) {
        layout.setOnTouchListener { v: View?, event: MotionEvent ->
            if (layout.isActivated) {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    val scaleX =
                        ObjectAnimator.ofFloat(v, "scaleX", 1f, pressScale)
                    val scaleY =
                        ObjectAnimator.ofFloat(v, "scaleY", 1f, pressScale)
                    animatorSet = AnimatorSet()
                    animatorSet!!.playTogether(scaleX, scaleY)
                    animatorSet!!.setDuration(125)
                    animatorSet!!.start()
                } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    val scaleX =
                        ObjectAnimator.ofFloat(v, "scaleX", pressScale, 1f)
                    val scaleY =
                        ObjectAnimator.ofFloat(v, "scaleY", pressScale, 1f)
                    animatorSet = AnimatorSet()
                    animatorSet!!.playTogether(scaleX, scaleY)
                    animatorSet!!.setDuration(125)
                    animatorSet!!.start()
                    l?.onClick(v!!)
                }
            }
            true
        }
    }
}