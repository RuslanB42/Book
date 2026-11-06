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
 /** Approved «воздушная» shadow: the surface floats ABOVE the page — a wide soft shade all round (barely offset down)
  *  and a short contact shade, instead of a shadow cast downward. Drawn outside the bounds: the parent must not clip. */
 internal var float=false
 internal fun refresh(){onBoundsChange(bounds);invalidateSelf()}
 /** The colour at rest; [fillColor] blends it toward the pressed shade as [press] eases in and out. */
 private val restColor:Int get(){val light=themeLight?:a.light;return when{selected&&light->0xffdedee2.toInt();selected->0xff48484c.toInt();sheet&&light->0xfff7f7f5.toInt();sheet->0xff1c1c1e.toInt();light->Color.WHITE;else->0xff242426.toInt()}}
 private val pressColor:Int get()=if(themeLight?:a.light)0xffe6e6e9.toInt() else 0xff38383c.toInt()
 private var press=0f;private var pressAnim:android.animation.ValueAnimator?=null
 internal val fillColor:Int get()=if(press<=0f)restColor else androidx.core.graphics.ColorUtils.blendARGB(restColor,pressColor,press)
 override fun onBoundsChange(bounds:Rect){val r=RectF(bounds);val radius=radiusDp*a.resources.displayMetrics.density;path.rewind();if(flushBottom||flushTop)path.addRoundRect(r,floatArrayOf(if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius),Path.Direction.CW)else path.addRoundRect(r,radius,radius,Path.Direction.CW)}
 override fun draw(c:Canvas){if(float&&!flushTop&&!flushBottom)FloatShade.draw(c,a,RectF(bounds),radiusDp*a.resources.displayMetrics.density,themeLight?:a.light,opacity)
  else if(float)FloatShade.drawPath(c,a,path,themeLight?:a.light,opacity)
  paint.color=fillColor;paint.alpha=opacity;c.drawPath(path,paint)}
 override fun isStateful()=true
 // a soft press: the shade eases in under the finger and fades out slowly after it (no white flash)
 override fun onStateChange(state:IntArray):Boolean{val next=state.contains(android.R.attr.state_pressed);if(next==pressed)return false;pressed=next
  pressAnim?.cancel();pressAnim=android.animation.ValueAnimator.ofFloat(press,if(next)1f else 0f).apply{duration=if(next)140 else 420;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{press=it.animatedValue as Float;invalidateSelf()};start()}
  return false}
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

/** The floating shade (see [OpaqueSurface.float]): a soft shade all round, baked ONCE per size into a small blurred
 *  picture (live blur was slow: the shade showed up late and stepped in while the bars slid in). */
internal object FloatShade{
 private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
 private val cache=object:android.util.LruCache<String,Bitmap>(24){override fun entryRemoved(e:Boolean,k:String,o:Bitmap,n:Bitmap?){}}
 private val dst=RectF()
 private fun bake(a:MainActivity,w:Int,h:Int,light:Boolean,shape:(Canvas,Paint,Float)->Unit):Bitmap?{
  val key="$w:$h:$light:${shape.hashCode()}";cache.get(key)?.let{return it}
  val pad=a.d(40).toFloat();val W=(w+2*pad).toInt();val H=(h+2*pad).toInt()
  val wide=Glow.blurred(W,H,4,a.d(16).toFloat()){c,pp->pp.color=if(light)0x1c000000 else 0x7a000000;shape(c,pp,pad)}?:return null
  val near=Glow.blurred(W,H,4,a.d(3).toFloat()){c,pp->pp.color=if(light)0x12000000 else 0x46000000;shape(c,pp,pad)}
  val out=Bitmap.createBitmap(wide.width,wide.height,Bitmap.Config.ARGB_8888);val c=Canvas(out);val fp=Paint(Paint.FILTER_BITMAP_FLAG)
  // the wide shade sits a hair lower than the edge (light from above), the near one right under the edge
  c.drawBitmap(wide,0f,a.d(2)/4f,fp);near?.let{c.drawBitmap(it,0f,a.d(1)/4f,fp);it.recycle()};wide.recycle()
  cache.put(key,out);return out}
 private fun put(c:Canvas,a:MainActivity,b:Bitmap,r:RectF,opacity:Int){val pad=a.d(40).toFloat();dst.set(r.left-pad,r.top-pad,r.right+pad,r.bottom+pad);p.alpha=opacity;c.drawBitmap(b,null,dst,p)}
 fun draw(c:Canvas,a:MainActivity,r:RectF,radius:Float,light:Boolean,opacity:Int=255){val w=r.width().toInt();val h=r.height().toInt();if(w<2||h<2)return
  val rad=radius;bake(a,w,h,light,Shape.round(rad))?.let{put(c,a,it,r,opacity)}}
 fun drawPath(c:Canvas,a:MainActivity,path:Path,light:Boolean,opacity:Int=255){val r=RectF();path.computeBounds(r,true);val w=r.width().toInt();val h=r.height().toInt();if(w<2||h<2)return
  bake(a,w,h,light,Shape.path(path,r))?.let{put(c,a,it,r,opacity)}}
 /** Shapes with a stable identity per form, so each size and form is baked once. */
 private object Shape{
  private val rounds=HashMap<Int,(Canvas,Paint,Float)->Unit>();private val paths=HashMap<String,(Canvas,Paint,Float)->Unit>()
  fun round(r:Float)=rounds.getOrPut((r*10).toInt()){{c,pp,pad->c.drawRoundRect(pad,pad,c.width*4f-pad,c.height*4f-pad,r,r,pp)}}
  fun path(path:Path,b:RectF):(Canvas,Paint,Float)->Unit{val shifted=Path(path).apply{offset(-b.left,-b.top)};val key="${b.width().toInt()}x${b.height().toInt()}:${shifted.hashCode()/1}"
   return paths.getOrPut("${b.width().toInt()}x${b.height().toInt()}"){{c,pp,pad->c.save();c.translate(pad,pad);c.drawPath(shifted,pp);c.restore()}}}
 }
}
/** Any drawable with the floating shade under it (for plain shapes: cards, the add button). */
internal class Floating(private val a:MainActivity,private val inner:Drawable,private val radiusPx:Float):Drawable(),Drawable.Callback{
 init{inner.callback=this}
 override fun draw(c:Canvas){FloatShade.draw(c,a,RectF(bounds),radiusPx.coerceAtMost(bounds.height()/2f),a.light);inner.draw(c)}
 override fun onBoundsChange(b:Rect){inner.bounds=b}
 override fun isStateful()=inner.isStateful
 override fun onStateChange(state:IntArray):Boolean{val ch=inner.setState(state);if(ch)invalidateSelf();return ch}
 override fun getOutline(o:Outline){inner.getOutline(o)}
 override fun setAlpha(alpha:Int){inner.alpha=alpha}
 override fun setColorFilter(f:ColorFilter?){inner.colorFilter=f}
 @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
 override fun invalidateDrawable(who:Drawable){invalidateSelf()}
 override fun scheduleDrawable(who:Drawable,what:Runnable,`when`:Long){scheduleSelf(what,`when`)}
 override fun unscheduleDrawable(who:Drawable,what:Runnable){unscheduleSelf(what)}
}
