package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View
import kotlin.math.*

/** Plain opaque controls. No scene capture, optical shaders, bitmap blur or glass highlights. */
internal class OpaqueSurface(private val a:MainActivity,private val radiusDp:Float=28f,private val selected:Boolean=false,private val sheet:Boolean=false):Drawable(){
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 private val path=Path()
 private var opacity=255
 private var pressed=false
 internal var themeLight:Boolean?=null
 internal var flushBottom=false
 internal var flushTop=false
 internal fun refresh(){onBoundsChange(bounds);invalidateSelf()}
 internal val fillColor:Int get(){val light=themeLight?:a.light;return when{pressed&&light->0xffdedee2.toInt();pressed->0xff414145.toInt();selected&&light->0xffdedee2.toInt();selected->0xff48484c.toInt();sheet&&light->0xfff7f7f5.toInt();sheet->0xff1c1c1e.toInt();light->Color.WHITE;else->0xff242426.toInt()}}
 override fun onBoundsChange(bounds:Rect){val r=RectF(bounds);val radius=radiusDp*a.resources.displayMetrics.density;path.rewind();if(flushBottom||flushTop)path.addRoundRect(r,floatArrayOf(if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius),Path.Direction.CW)else path.addRoundRect(r,radius,radius,Path.Direction.CW)}
 override fun draw(c:Canvas){paint.color=fillColor;paint.alpha=opacity;c.drawPath(path,paint)}
 override fun isStateful()=true
 override fun onStateChange(state:IntArray):Boolean{val next=state.contains(android.R.attr.state_pressed);if(next==pressed)return false;pressed=next;invalidateSelf();return true}
 override fun getOutline(outline:Outline){if(flushBottom||flushTop)outline.setConvexPath(path)else outline.setRoundRect(bounds,radiusDp*a.resources.displayMetrics.density);outline.alpha=1f}
 override fun setAlpha(alpha:Int){opacity=alpha;invalidateSelf()}
 override fun setColorFilter(filter:ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
 @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT // rounded transparent corners, opaque interior
}
internal fun MainActivity.controlSurface(host:View,radius:Float=28f,selected:Boolean=false,sheet:Boolean=false):OpaqueSurface {
 (host.background as? OpaqueSurface)?.let{it.refresh();return it}
 return OpaqueSurface(this,radius,selected,sheet).also{host.background=it}
}

/** Analytic damped spring, independent of frame rate. Retargeting retains presentation velocity. */
internal class LiquidSpring(private val changed:(Float,Float)->Unit){
 var value=0f;private set
 var velocity=0f;private set
 private var clock:android.animation.ValueAnimator?=null
 internal fun sample(ms:Long){clock?.let{it.pause();if(ms>=it.duration)it.end()else it.currentPlayTime=ms}}
 fun snap(to:Float){stop();value=to;velocity=0f;changed(value,velocity)}
 fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
 fun to(target:Float){stop();val start=value;val speed=velocity;clock=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=620;interpolator=android.view.animation.LinearInterpolator();addUpdateListener{val t=currentPlayTime/1000f;val decay=exp(-15f*t);val wd=13f;val delta=start-target;val b=(speed+15f*delta)/wd;value=target+decay*(delta*cos(wd*t)+b*sin(wd*t));velocity=decay*((-15f*delta+wd*b)*cos(wd*t)+(-15f*b-wd*delta)*sin(wd*t));changed(value,velocity)};addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){value=target;velocity=0f;changed(value,velocity)}});start()}}
}
