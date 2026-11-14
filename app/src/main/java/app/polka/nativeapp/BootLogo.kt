package app.polka.nativeapp

import android.view.View
import android.widget.FrameLayout

/** A plain cover in the background colour while the library loads: no icon at launch. */
internal fun MainActivity.bootLogo():View=FrameLayout(this).apply {
    tag="polka-boot-logo";setBackgroundColor(getColor(R.color.launcher_background))
    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
}

/** The freshly rendered interface comes in by itself: it fades in and rises a little, part after part. */
internal fun MainActivity.revealFromBoot(logo:View?){
    (logo?.parent as? android.view.ViewGroup)?.removeView(logo)
    val reduced=prefs.str("motion")=="reduced"
    val ease=android.view.animation.PathInterpolator(.16f,1f,.3f,1f)
    var i=0
    for(k in 0 until root.childCount){val v=root.getChildAt(k);if(v.visibility!=View.VISIBLE||v.alpha<1f)continue
        v.alpha=0f
        if(reduced){v.animate().alpha(1f).setDuration(200).start();continue}
        v.translationY=d(14).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(40L*i).setDuration(520).setInterpolator(ease).start();i++}
}
