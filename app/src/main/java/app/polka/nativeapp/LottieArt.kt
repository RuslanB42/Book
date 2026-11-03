package app.polka.nativeapp

import android.widget.ImageView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable
import com.airbnb.lottie.LottieProperty
import com.airbnb.lottie.SimpleColorFilter
import com.airbnb.lottie.model.KeyPath
import com.airbnb.lottie.value.LottieValueCallback

/** A plain ImageView playing a LottieDrawable (no AppCompat needed). */
internal class LottieView(a:MainActivity):ImageView(a){
    val drawable=LottieDrawable()
    var repeatCount:Int get()=drawable.repeatCount;set(v){drawable.repeatCount=v}
    private var playWhenReady=false
    init{scaleType=ScaleType.FIT_CENTER;setImageDrawable(drawable);importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun playAnimation(){if(drawable.composition==null)playWhenReady=true else drawable.playAnimation()}
    internal fun loaded(){setImageDrawable(null);setImageDrawable(drawable);if(playWhenReady){playWhenReady=false;drawable.playAnimation()}}
    override fun onDetachedFromWindow(){drawable.pauseAnimation();super.onDetachedFromWindow()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(drawable.composition!=null&&drawable.repeatCount!=0&&!drawable.isAnimating)drawable.resumeAnimation()}
}

/**
 * Lottie animations of Polka (JSON files in assets/lottie, drawn for the app). Neutral parts are named «ink» in the
 * files and take the theme's muted colour here, so one file works in the light and the dark theme.
 * [loopFrom]: play the whole animation once, then loop from this frame (intro → idle). null — loop all; -1 — play once on demand.
 */
internal fun MainActivity.lottie(name:String,loopFrom:Int?=null):LottieView=LottieView(this).apply{
    val view=this;val d=drawable
    val reduced=prefs.str("motion")=="reduced"
    when(loopFrom){null->{d.repeatCount=LottieDrawable.INFINITE;if(!reduced)playAnimation()};-1->d.repeatCount=0
        else->{d.repeatCount=0;if(!reduced){d.addAnimatorListener(object:android.animation.AnimatorListenerAdapter(){var looping=false
            override fun onAnimationEnd(x:android.animation.Animator){if(looping)return;looping=true;d.removeAnimatorListener(this)
                d.composition?.let{c->d.setMinAndMaxFrame(loopFrom,c.endFrame.toInt())};d.repeatCount=LottieDrawable.INFINITE;d.playAnimation()}});playAnimation()}}}
    LottieCompositionFactory.fromAsset(this@lottie,"lottie/$name.json").addListener{c->
        d.composition=c
        d.addValueCallback(KeyPath("**","ink","**"),LottieProperty.COLOR_FILTER,LottieValueCallback(SimpleColorFilter(muted)))
        if(reduced)d.frame=(loopFrom?.takeIf{it>0}?:c.endFrame.toInt()).coerceAtMost(c.endFrame.toInt()-1)
        view.loaded()}
}
