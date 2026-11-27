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
 /** How high it floats (see [Lift]): a bar or a button by default, a menu above everything. */
 internal var lift=Lift.FLOAT
 internal fun refresh(){onBoundsChange(bounds);invalidateSelf()}
 /** The colour at rest; [fillColor] blends it toward the pressed shade as [press] eases in and out. */
 // on a dark page the higher a surface floats, the lighter it is: the page, a card (#242426), a floating bar, a menu
 // over everything (a bar along the screen's edge keeps its own colour)
 private val restColor:Int get(){val light=themeLight?:a.light;val floats=float&&!flushTop&&!flushBottom;return when{selected&&light->0xffdedee2.toInt();selected->0xff48484c.toInt();
  floats&&!light&&lift==Lift.OVER->0xff303033.toInt();floats&&!light&&lift==Lift.FLOAT->0xff2a2a2c.toInt();sheet&&light->0xfff7f7f5.toInt();sheet->0xff1c1c1e.toInt();light->Color.WHITE;else->0xff242426.toInt()}}
 private val pressColor:Int get()=if(themeLight?:a.light)0xffe6e6e9.toInt() else 0xff38383c.toInt()
 private var press=0f;private var pressAnim:android.animation.ValueAnimator?=null
 internal val fillColor:Int get()=if(press<=0f)restColor else androidx.core.graphics.ColorUtils.blendARGB(restColor,pressColor,press)
 override fun onBoundsChange(bounds:Rect){val r=RectF(bounds);val radius=radiusDp*a.resources.displayMetrics.density;path.rewind();if(flushBottom||flushTop)path.addRoundRect(r,floatArrayOf(if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushTop)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius,if(flushBottom)0f else radius),Path.Direction.CW)else path.addRoundRect(r,radius,radius,Path.Direction.CW)}
 override fun draw(c:Canvas){if(float&&!flushTop&&!flushBottom)FloatShade.draw(c,a,RectF(bounds),radiusDp*a.resources.displayMetrics.density,themeLight?:a.light,opacity,lift=lift)
  else if(float)FloatShade.drawPath(c,a,path,themeLight?:a.light,opacity,flushBottom,lift)
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
/** The drawn shade replaces Android's own under [v] (which is black and falls longer the lower on the screen the
 *  view is); its elevation still puts it above the others. */
internal fun noNativeShadow(v:View){if(android.os.Build.VERSION.SDK_INT>=28){v.outlineSpotShadowColor=Color.TRANSPARENT;v.outlineAmbientShadowColor=Color.TRANSPARENT}
 // faded in and out without an offscreen layer: a layer is cut to the view, and so would be its drawn shade
 v.forceHasOverlappingRendering(false)}
/** [v]'s surface pops up over everything (a menu, a note, a chip): the app's shade at that height (see [Lift.OVER]). */
internal fun MainActivity.overSurface(v:View,color:Int,radiusDp:Int){v.background=Floating(this,android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=d(radiusDp).toFloat()},d(radiusDp).toFloat(),onPage=false,lift=Lift.OVER);noNativeShadow(v)}
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

/**
 * How high a thing stands above the page, and so the shade it casts (one system for the whole app, after the best
 * references): [e] is the drop of its longest layer (dp, for a thing 170dp across: bigger things a little more, small
 * ones less), [light] and [dark] the strength of each layer on a light and on a dark page.
 */
internal enum class Lift(val e:Float,val light:Float,val dark:Float){
 /** A knob, a thumb or a tab's pill on its own track. */
 TOUCH(9f,.10f,.20f),
 /** A book cover lying on the page (thin: it stands lower than a card). */
 COVER(26f,.062f,.13f),
 /** A card lying on the page. */
 REST(40f,.065f,.16f),
 /** The bars and the round button floating over the content. */
 FLOAT(46f,.074f,.17f),
 /** Menus, notes and chips popping up over everything. */
 OVER(52f,.085f,.19f),
 /** Something picked up and carried under the finger. */
 CARRY(44f,.09f,.20f)
}

/**
 * The shade (see [Lift]). Built as the best references build it: not one blurred blob but five layers, each twice as
 * far and twice as soft as the one before (from a hairline contact shade right under the edge to a long soft fall),
 * so it is crisp where the thing meets the page and fades out over a long distance with no edge anywhere. Every
 * layer is tucked in a little at the sides (more the farther it falls), so no rim shows round the sides or the top.
 * One light for the whole app, high above and a touch to the left (as on the covers): every shade falls down and a
 * hair to the right. Never black: on a light page a neutral slate grey (it cannot get darker than itself, so the
 * shade stays light and clean), on a dark page a near-black just short of it. Shades on the page itself are dithered
 * (no steps in the long fall on a dark page); a bar, a button or a menu floats over covers too, where the same noise
 * showed as grain. Baked once per size into a small picture (a live blur was slow: the shade came late and stepped in
 * while the bars slid in); the picture reaches past the whole fall, so it never ends in an edge.
 */
internal object FloatShade{
 private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
 private class Baked(val bmp:Bitmap,val pad:Float)
 private val cache=object:android.util.LruCache<String,Baked>(40){override fun entryRemoved(e:Boolean,k:String,o:Baked,n:Baked?){}}
 private val dst=RectF()
 private const val LIGHT_TINT=0x40424a;private const val DARK_TINT=0x050507
 /** The shade's colour (no alpha). */
 fun tint(light:Boolean)=if(light)LIGHT_TINT else DARK_TINT
 /** The first of the five layers a shade has. On a dark page only the three broad ones: a page that dark leaves the
  *  shade some twenty steps of brightness to fall through (two hundred on a light one), so the tight layers by the
  *  edge stood out as a hard dark rim; there the depth is told by the surfaces (higher is lighter, see [OpaqueSurface])
  *  and the shade is only a wide, faint fall. */
 fun firstLayer(light:Boolean)=if(light)0 else 2
 /** The light's slant: a shade moves this much to the right for every step it falls. */
 const val SLANT=.1f
 /** The longest drop of a shade cast by a thing [w]×[h] px at [lift]. */
 fun reach(a:MainActivity,lift:Lift,w:Int,h:Int)=lift.e*a.resources.displayMetrics.density*sqrt(min(w,h)/a.d(170).toFloat()).coerceIn(.3f,1.15f)
 private fun bake(a:MainActivity,w:Int,h:Int,light:Boolean,dither:Boolean,lift:Lift,shapeKey:String,up:Boolean=false,shape:(Canvas,Paint,RectF)->Unit):Baked?{
  val key="$w:$h:$light:$dither:$up:$lift:$shapeKey";cache.get(key)?.let{return it}
  val e=reach(a,lift,w,h);val dp=a.resources.displayMetrics.density
  // room for the longest layer's whole fall (three sigmas past its drop and slant)
  val pad=e*(1f+SLANT)+3f*(.6f*e+.4f*dp)+a.d(4);val W=(w+2*pad).toInt();val H=(h+2*pad).toInt()
  val out=try{Bitmap.createBitmap(max(1,W/4),max(1,H/4),Bitmap.Config.ARGB_8888)}catch(e:Throwable){return null};val c=Canvas(out);c.scale(.25f,.25f)
  val tint=tint(light);val each=((if(light)lift.light else lift.dark)*255).roundToInt()
  val lp=Paint(Paint.ANTI_ALIAS_FLAG);val r=RectF()
  for(i in firstLayer(light) until 5){val dy=e*(1f/(1 shl (4-i)));val sigma=.6f*dy+.4f*dp;val tuck=min(.14f*dy,.1f*min(w,h));val dx=SLANT*dy
   // [up]: a bar along the bottom edge casts its shade upward (below it there is only the edge of the screen)
   val fall=if(up)-dy else dy
   r.set(pad+tuck+dx,pad+tuck+fall,pad+w-tuck+dx,pad+h-tuck+fall);if(r.width()<=1f||r.height()<=1f)continue
   lp.maskFilter=BlurMaskFilter(max(.5f,sigma/.57735f),BlurMaskFilter.Blur.NORMAL);lp.color=(each shl 24) or tint;shape(c,lp,r)}
  // dither: a step of the page's colour is about 255/its brightness steps of the shade's alpha (one on a light page,
  // about twelve on a dark one), so the noise is half a step of the page's colour, thinning out where the shade fades
  // away (the clear page round it gets no grain)
  if(dither){val amp=if(light)1 else 6;val n=out.width*out.height;val px=IntArray(n);out.getPixels(px,0,out.width,0,0,out.width,out.height);val rnd=java.util.Random(7)
   for(k in 0 until n){val al=px[k] ushr 24;if(al==0)continue;val q=min(amp,1+al*amp/16);px[k]=((al+rnd.nextInt(2*q+1)-q).coerceIn(0,255) shl 24) or tint}
   out.setPixels(px,0,out.width,0,0,out.width,out.height)}
  return Baked(out,pad).also{cache.put(key,it)}}
 private fun put(c:Canvas,b:Baked,r:RectF,opacity:Int){dst.set(r.left-b.pad,r.top-b.pad,r.right+b.pad,r.bottom+b.pad);p.alpha=opacity;c.drawBitmap(b.bmp,null,dst,p)}
 /** Sizes in steps (8dp, 2dp for small things), so a thing that grows is not baked again every frame; the picture is
  *  stretched the few pixels to it, which no soft shade shows. */
 private fun step(a:MainActivity,v:Float):Int{val q=a.d(if(v<a.d(64))2 else 8);return max(q,((v/q).roundToInt())*q)}
 fun draw(c:Canvas,a:MainActivity,r:RectF,radius:Float,light:Boolean,opacity:Int=255,dither:Boolean=false,lift:Lift=Lift.REST){if(r.width()<2||r.height()<2||opacity<=0)return;val w=step(a,r.width());val h=step(a,r.height())
  bake(a,w,h,light,dither,lift,"r${(radius*10).toInt()}"){cc,pp,to->val tuck=(w-to.width())/2f;val rr=(radius-tuck).coerceIn(0f,min(to.width(),to.height())/2f);cc.drawRoundRect(to,rr,rr,pp)}?.let{put(c,it,r,opacity)}}
 /** [key]: what the shape is (a path made anew with the same shape keeps its baked shade); [dither] as in [draw]. */
 fun drawPath(c:Canvas,a:MainActivity,path:Path,light:Boolean,opacity:Int=255,up:Boolean=false,lift:Lift=Lift.FLOAT,dither:Boolean=false,key:String?=null){val r=RectF();path.computeBounds(r,true);if(r.width()<2||r.height()<2||opacity<=0)return;val w=step(a,r.width());val h=step(a,r.height())
  val from=RectF(r);bake(a,w,h,light,dither,lift,"p${key?:path.hashCode()}",up){cc,pp,to->val m=Matrix();m.setRectToRect(from,to,Matrix.ScaleToFit.FILL);cc.drawPath(Path(path).apply{transform(m)},pp)}?.let{put(c,it,r,opacity)}}
}
/** The floating shade under [card], drawn by its parent: for a card that clips itself to its outline (which would cut
 *  off a shade of its own). The parent is redrawn whenever the card moves or changes size. */
internal class ShadeUnder(private val a:MainActivity,private val card:View,private val radiusPx:Float):Drawable(){
 private val r=RectF()
 override fun draw(c:Canvas){if(card.visibility!=View.VISIBLE||card.width<2||card.parent==null)return
  r.set(card.left+card.translationX,card.top+card.translationY,card.right+card.translationX,card.bottom+card.translationY);FloatShade.draw(c,a,r,radiusPx,a.light,(255*card.alpha.coerceIn(0f,1f)).roundToInt(),true)}
 override fun setAlpha(alpha:Int){}
 override fun setColorFilter(f:ColorFilter?){}
 @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
internal fun MainActivity.shadeUnder(card:View,radiusDp:Int=24){val host=card.parent as? View?:return
 host.background=ShadeUnder(this,card,d(radiusDp).toFloat());card.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->host.invalidate()}}
/** Any drawable with the floating shade under it (for plain shapes: cards, the add button). */
/** [onPage]: a card lying on the page (its shade is dithered, see [FloatShade]); a button floating over the content
 *  is not. */
internal class Floating(private val a:MainActivity,private val inner:Drawable,private val radiusPx:Float,private val strength:Float=1f,private val onPage:Boolean=true,private val lift:Lift=Lift.REST):Drawable(),Drawable.Callback{
 init{inner.callback=this}
 override fun draw(c:Canvas){FloatShade.draw(c,a,RectF(bounds),radiusPx.coerceAtMost(bounds.height()/2f),a.light,(255*strength.coerceIn(0f,1f)).roundToInt(),onPage,lift);inner.draw(c)}
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
