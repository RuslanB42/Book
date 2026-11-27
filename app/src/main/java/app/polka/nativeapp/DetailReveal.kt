package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.widget.*
import kotlin.math.*

/** Height + ink dissolve, without scaling text or retaining duplicate interactive views. */
internal class DetailReveal(private val a:MainActivity):FrameLayout(a){
 val body=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
 private var clock:ValueAnimator?=null
 private var old:Bitmap?=null
 private var shown:Int?=null
 private var start=0;private var target=0;private var dissolve=true;private var crossfade=false
 /** Called with the eased progress of every frame, so content can move in lockstep with the height. */
 var onProgress:((Float)->Unit)?=null
 /** While the height moves, what reaches past it fades out over [fall] below it (content never spills over what
  *  follows) instead of being cut: the cards' soft shades go on below them and a cut showed as an edge. */
 private var softClip=false
 private val side get()=a.d(56).toFloat()
 private val fall get()=a.d(72).toFloat()
 private val mask=Paint().apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
 /** A pre-rendered picture drawn over the content (used to let the quote pile appear while cards fold into it); made
  *  by [picture], with the room round it where the shades are. */
 var overlay:Bitmap?=null;var overlayAlpha=0f
 fun picture(v:View,w:Int,h:Int):Bitmap=Bitmap.createBitmap((w+2*side).roundToInt(),(h+side+fall).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also{val c=Canvas(it);c.translate(side,side);v.draw(c)}
 internal var progress=1f;private set
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 init{setWillNotDraw(false);clipChildren=false;clipToPadding=false;addView(body,LayoutParams(-1,-2))}
 private fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
 fun change(animated:Boolean,dissolveInk:Boolean=true,crossfadeInk:Boolean=false,duration:Long=360,curve:android.animation.TimeInterpolator=MotionSpec.enter,update:()->Unit){
  val move=animated&&a.prefs.str("motion")!="reduced"&&isLaidOut&&width>0&&height>0
  val h=height
  val capture=if(move&&(dissolveInk||crossfadeInk)){val scale=minOf(1f,1024f/max(width,height));Bitmap.createBitmap(max(1,((width+2*side)*scale).roundToInt()),max(1,((height+side+fall)*scale).roundToInt()),Bitmap.Config.ARGB_8888).also{val c=Canvas(it);c.scale(scale,scale);c.translate(side,side);draw(c)}}else null
  stop();old?.recycle();old=capture;overlay?.recycle();overlay=null;softClip=false;dissolve=dissolveInk&&!crossfadeInk;crossfade=crossfadeInk;update()
  if(!move){shown=null;body.alpha=1f;body.translationY=0f;progress=1f;onProgress?.invoke(1f);onProgress=null;requestLayout();invalidate();return}
  body.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
  start=h;target=if(body.visibility==GONE)0 else body.measuredHeight;shown=start;clipChildren=false;softClip=true;body.alpha=if(dissolve||crossfade)0f else 1f;progress=0f;onProgress?.invoke(0f);requestLayout()
  clock=ValueAnimator.ofFloat(0f,1f).apply{this.duration=duration;interpolator=curve;addUpdateListener{sample(it.animatedValue as Float)};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){complete()}});start()}
 }
 internal fun sample(value:Float){progress=value.coerceIn(0f,1f);shown=(start+(target-start)*progress).roundToInt();body.alpha=if(crossfade)(progress/.2f).coerceIn(0f,1f) else if(dissolve)((progress-.22f)/.58f).coerceIn(0f,1f)else 1f;onProgress?.invoke(progress);body.translationY=if(dissolve)a.d(6)*(1-progress)else 0f;requestLayout();invalidate()}
 /** Moves only the visible height (content untouched) and reports eased progress; the height then stays held for the next change. */
 fun animateHeight(to:Int,duration:Long,curve:android.animation.TimeInterpolator,frame:(Float)->Unit,end:()->Unit){
  stop();old?.recycle();old=null;val from=height;crossfade=false;dissolve=false;body.alpha=1f;body.translationY=0f;clipChildren=false;softClip=true
  clock=ValueAnimator.ofFloat(0f,1f).apply{this.duration=duration;interpolator=curve
   addUpdateListener{val p=it.animatedValue as Float;shown=(from+(to-from)*p).roundToInt();frame(p);requestLayout();invalidate()}
   addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){if(clock===animation){clock=null;shown=to;end()}}});start()}
 }
 private fun complete(){clipChildren=false;softClip=false;onProgress?.invoke(1f);onProgress=null;shown=null;progress=1f;body.alpha=1f;body.translationY=0f;old?.recycle();old=null;clock=null;requestLayout();invalidate()}
 private var lastWs=0
 override fun onMeasure(ws:Int,hs:Int){if(!(shown!=null&&ws==lastWs&&body.measuredWidth>0&&!body.isLayoutRequested))body.measure(ws,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));lastWs=ws;setMeasuredDimension(MeasureSpec.getSize(ws),shown?:if(body.visibility==GONE)0 else body.measuredHeight)}
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){body.layout(0,0,r-l,body.measuredHeight)}
 override fun dispatchDraw(c:Canvas){
  if(!softClip&&old==null){super.dispatchDraw(c);return}
  val h=height.toFloat();val save=c.saveLayer(-side,-side,width+side,h+fall,null)
  // the page as it was (with the room round it, where its shades are), fading out
  old?.takeUnless{it.isRecycled}?.let{paint.alpha=(255*(1-progress/(if(crossfade).2f else .22f)).coerceIn(0f,1f)).toInt();c.drawBitmap(it,null,RectF(-side,-side,width+side,start+fall),paint)}
  super.dispatchDraw(c)
  overlay?.takeUnless{it.isRecycled}?.let{paint.alpha=(255*overlayAlpha.coerceIn(0f,1f)).toInt();c.drawBitmap(it,-side,-side,paint)}
  mask.shader=LinearGradient(0f,h,0f,h+fall,Color.BLACK,Color.TRANSPARENT,Shader.TileMode.CLAMP);c.drawRect(-side,h,width+side,h+fall,mask)
  c.restoreToCount(save)
 }
 override fun onDetachedFromWindow(){stop();complete();overlay?.recycle();overlay=null;super.onDetachedFromWindow()}
}
