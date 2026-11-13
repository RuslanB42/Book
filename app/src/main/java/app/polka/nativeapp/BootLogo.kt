package app.polka.nativeapp

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/** Same symbol as the launcher, no typographic placeholder during library loading. */
internal fun MainActivity.bootLogo():View=FrameLayout(this).apply {
    tag="polka-boot-logo";setBackgroundColor(getColor(R.color.launcher_background))
    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    addView(ImageView(this@bootLogo).apply{setImageResource(R.drawable.glyph_logo);scaleType=ImageView.ScaleType.FIT_CENTER},FrameLayout.LayoutParams(d(192),d(192),Gravity.CENTER))
}

/** Hands the boot screen over to the freshly rendered library: the icon dips, then swells and dissolves while the screen fades away. */
internal fun MainActivity.revealFromBoot(logo:View?){
    if(logo==null)return
    (logo.parent as? android.view.ViewGroup)?.removeView(logo)
    root.addView(logo,FrameLayout.LayoutParams(-1,-1))
    val icon=(logo as? FrameLayout)?.getChildAt(0)
    val reduced=prefs.str("motion")=="reduced"
    val ease=android.view.animation.PathInterpolator(.22f,1f,.36f,1f)
    fun done(){(logo.parent as? android.view.ViewGroup)?.removeView(logo)}
    if(reduced||icon==null){logo.animate().alpha(0f).setDuration(200).withEndAction{done()}.start();return}
    icon.animate().scaleX(.92f).scaleY(.92f).setDuration(150).setInterpolator(android.view.animation.DecelerateInterpolator()).withEndAction{
        icon.animate().scaleX(1.35f).scaleY(1.35f).alpha(0f).setDuration(420).setInterpolator(ease).start()
        logo.animate().alpha(0f).setStartDelay(90).setDuration(380).setInterpolator(ease).withEndAction{done()}.start()
    }.start()
}
