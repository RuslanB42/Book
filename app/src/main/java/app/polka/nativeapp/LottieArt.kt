package app.polka.nativeapp

import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback

/**
 * Lottie animations of Polka (JSON files in assets/lottie, drawn for the app). Neutral parts are named «ink» in the files and
 * take the theme's muted colour here, so one file works in the light and the dark theme.
 * [loopFrom]: play the whole animation once, then loop from this frame (intro → idle). null — loop all; -1 — play once.
 */
internal fun MainActivity.lottie(name:String,loopFrom:Int?=null):LottieAnimationView=LottieAnimationView(this).apply{
    setAnimation("lottie/$name.json")
    importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    val ink=muted
    addValueCallback(KeyPath("**","ink","**"),LottieProperty.COLOR_FILTER,LottieValueCallback(SimpleColorFilter(ink)))
    if(prefs.str("motion")=="reduced"){ // a still frame: the finished picture
        addLottieOnCompositionLoadedListener{c->frame=(loopFrom?.takeIf{it>0}?:c.endFrame.toInt()).coerceAtMost(c.endFrame.toInt()-1)};return@apply}
    when(loopFrom){
        null->{repeatCount=LottieDrawable.INFINITE;playAnimation()}
        -1->{repeatCount=0}
        else->{repeatCount=0;addAnimatorListener(object:android.animation.AnimatorListenerAdapter(){var looping=false
            override fun onAnimationEnd(x:android.animation.Animator){if(looping)return;looping=true;removeAnimatorListener(this)
                composition?.let{c->setMinAndMaxFrame(loopFrom,c.endFrame.toInt())};repeatCount=LottieDrawable.INFINITE;playAnimation()}});playAnimation()}
    }
}
