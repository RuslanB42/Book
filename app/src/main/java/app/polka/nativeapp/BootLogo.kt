package app.polka.nativeapp

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Same symbol as the launcher, no typographic placeholder during library loading. */
internal fun MainActivity.bootLogo():View=FrameLayout(this).apply {
    tag="polka-boot-logo";setBackgroundColor(getColor(R.color.launcher_background))
    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    addView(ImageView(this@bootLogo).apply{setImageResource(R.drawable.ic_launcher_foreground);scaleType=ImageView.ScaleType.FIT_CENTER},FrameLayout.LayoutParams(d(192),d(192),Gravity.CENTER))
}
