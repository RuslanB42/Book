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
  else if(float)FloatShade.drawPath(c,a,path,themeLight?:a.light,opacity,flushBottom)
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

/** The floating shade (see [OpaqueSurface.float]), after the references: light falls from above, so the shade lies
 *  UNDER the card rather than round it — a broad soft pool, narrower than the card and dropped below it (its sides
 *  tucked in, so no rim shows at the sides or the top), a short contact shade along the bottom edge that sets the card
 *  on the page, and a very faint haze all round. Its sizes follow the card (a button gets a small one). On a light page
 *  it is a cool grey, never flat black; on a dark one black and deeper, so it still reads. Dithered, so its long fall
 *  shows no steps; the picture reaches past the whole fall, so it never ends in an edge. Baked ONCE per size into a
 *  small blurred picture (live blur was slow: the shade showed up late and stepped in while the bars slid in). */
internal object FloatShade{
 private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
 private class Baked(val bmp:Bitmap,val pad:Float)
 private val cache=object:android.util.LruCache<String,Baked>(24){override fun entryRemoved(e:Boolean,k:String,o:Baked,n:Baked?){}}
 private val dst=RectF()
 /** One gaussian: how far it is tucked in at the sides and the top, dropped below, its spread (dp, for a card 170dp
  *  high) and its strength on a light and on a dark page. */
 private class Layer(val inX:Float,val inTop:Float,val dy:Float,val sigma:Float,val light:Float,val dark:Float)
 private val layers=listOf(Layer(22f,26f,16f,16f,.24f,.62f),Layer(4f,8f,3f,4f,.10f,.55f),Layer(0f,0f,4f,26f,.065f,.20f))
 /** Never full black: on a light page a soft blue-grey (it cannot get darker than itself, so the shade stays light);
  *  on a dark page a near-black just short of it. */
 private const val LIGHT_TINT=0x5a6275;private const val DARK_TINT=0x050507
 private fun scale(a:MainActivity,w:Int,h:Int)=(min(w,h)/a.d(170).toFloat()).coerceIn(.35f,1.3f)
 private fun bake(a:MainActivity,w:Int,h:Int,light:Boolean,dither:Boolean,shapeKey:String,up:Boolean=false,shape:(Canvas,Paint,RectF)->Unit):Baked?{
  val key="$w:$h:$light:$dither:$up:$shapeKey";cache.get(key)?.let{return it}
  // (sizes come in steps of 8dp, so a card that grows is not baked again every frame; the picture is stretched the
  // few pixels to the card, which no soft shade shows)
  val s=scale(a,w,h);val dp=a.resources.displayMetrics.density*s
  // room for the haze's whole fall (three sigmas past its drop)
  val pad=(4f+3f*26f)*dp+a.d(4);val W=(w+2*pad).toInt();val H=(h+2*pad).toInt()
  val out=try{Bitmap.createBitmap(max(1,W/4),max(1,H/4),Bitmap.Config.ARGB_8888)}catch(e:Throwable){return null};val c=Canvas(out);val fp=Paint(Paint.FILTER_BITMAP_FLAG)
  val tint=if(light)LIGHT_TINT else DARK_TINT
  // [up]: a bar along the bottom edge casts its shade upward (below it there is only the edge of the screen)
  for(l in layers){val r=if(up)RectF(pad+l.inX*dp,pad-l.dy*dp,pad+w-l.inX*dp,pad+h-(l.inTop+l.dy)*dp) else RectF(pad+l.inX*dp,pad+(l.inTop+l.dy)*dp,pad+w-l.inX*dp,pad+h+l.dy*dp);if(r.width()<=1f||r.height()<=1f)continue
   val b=Glow.blurred(W,H,4,l.sigma*dp){cc,pp->pp.color=tint or (0xff shl 24);shape(cc,pp,r)}?:continue
   fp.alpha=((if(light)l.light else l.dark)*255).roundToInt();c.drawBitmap(b,0f,0f,fp);b.recycle()}
  // dither: a step of the page's colour is about 255/its brightness steps of the shade's alpha (one on a light page,
  // about twelve on a dark one), so the noise is half a step of the page's colour. Only under cards lying on the page:
  // a bar or a button floats over covers too, and over a bright cover the same noise showed as grain.
  if(dither){val amp=if(light)1 else 8;val n=out.width*out.height;val px=IntArray(n);out.getPixels(px,0,out.width,0,0,out.width,out.height);val rnd=java.util.Random(7)
   // (thinning out where the shade fades away, so the clear page round it gets no grain)
   for(i in 0 until n){val al=px[i] ushr 24;if(al==0)continue;val k=min(amp,1+al*amp/16);px[i]=((al+rnd.nextInt(2*k+1)-k).coerceIn(0,255) shl 24) or tint}
   out.setPixels(px,0,out.width,0,0,out.width,out.height)}
  return Baked(out,pad).also{cache.put(key,it)}}
 private fun put(c:Canvas,b:Baked,r:RectF,opacity:Int){dst.set(r.left-b.pad,r.top-b.pad,r.right+b.pad,r.bottom+b.pad);p.alpha=opacity;c.drawBitmap(b.bmp,null,dst,p)}
 private fun step(a:MainActivity,v:Float):Int{val q=a.d(8);return max(q,((v/q).roundToInt())*q)}
 fun draw(c:Canvas,a:MainActivity,r:RectF,radius:Float,light:Boolean,opacity:Int=255,dither:Boolean=false){if(r.width()<2||r.height()<2)return;val w=step(a,r.width());val h=step(a,r.height())
  bake(a,w,h,light,dither,"r${(radius*10).toInt()}"){cc,pp,to->cc.drawRoundRect(to,radius,radius,pp)}?.let{put(c,it,r,opacity)}}
 fun drawPath(c:Canvas,a:MainActivity,path:Path,light:Boolean,opacity:Int=255,up:Boolean=false){val r=RectF();path.computeBounds(r,true);if(r.width()<2||r.height()<2)return;val w=step(a,r.width());val h=step(a,r.height())
  val from=RectF(r);bake(a,w,h,light,false,"p${path.hashCode()}",up){cc,pp,to->val m=Matrix();m.setRectToRect(from,to,Matrix.ScaleToFit.FILL);cc.drawPath(Path(path).apply{transform(m)},pp)}?.let{put(c,it,r,opacity)}}
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
internal class Floating(private val a:MainActivity,private val inner:Drawable,private val radiusPx:Float,private val strength:Float=1f,private val onPage:Boolean=true):Drawable(),Drawable.Callback{
 init{inner.callback=this}
 override fun draw(c:Canvas){FloatShade.draw(c,a,RectF(bounds),radiusPx.coerceAtMost(bounds.height()/2f),a.light,(255*strength.coerceIn(0f,1f)).roundToInt(),onPage);inner.draw(c)}
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
