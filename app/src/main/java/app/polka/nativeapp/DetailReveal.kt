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
 private var start=0;private var target=0;private var dissolve=true
 internal var progress=1f;private set
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 init{setWillNotDraw(false);clipChildren=true;addView(body,LayoutParams(-1,-2))}
 private fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
 fun change(animated:Boolean,dissolveInk:Boolean=true,update:()->Unit){
  val move=animated&&a.prefs.str("motion")!="reduced"&&isLaidOut&&width>0&&height>0
  val h=height
  val capture=if(move&&dissolveInk){val scale=minOf(1f,1024f/max(width,height));Bitmap.createBitmap(max(1,(width*scale).roundToInt()),max(1,(height*scale).roundToInt()),Bitmap.Config.ARGB_8888).also{val c=Canvas(it);c.scale(scale,scale);draw(c)}}else null
  stop();old?.recycle();old=capture;dissolve=dissolveInk;update()
  if(!move){shown=null;body.alpha=1f;body.translationY=0f;progress=1f;requestLayout();invalidate();return}
  body.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
  start=h;target=if(body.visibility==GONE)0 else body.measuredHeight;shown=start;body.alpha=if(dissolve)0f else 1f;progress=0f;requestLayout()
  clock=ValueAnimator.ofFloat(0f,1f).apply{duration=360;interpolator=MotionSpec.settle;addUpdateListener{sample(it.animatedValue as Float)};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){complete()}});start()}
 }
 internal fun sample(value:Float){progress=value.coerceIn(0f,1f);shown=(start+(target-start)*progress).roundToInt();body.alpha=if(dissolve)((progress-.22f)/.58f).coerceIn(0f,1f)else 1f;body.translationY=if(dissolve)a.d(6)*(1-progress)else 0f;requestLayout();invalidate()}
 internal fun freeze(){stop()}
 private fun complete(){shown=null;progress=1f;body.alpha=1f;body.translationY=0f;old?.recycle();old=null;clock=null;requestLayout();invalidate()}
 override fun onMeasure(ws:Int,hs:Int){body.measure(ws,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));setMeasuredDimension(MeasureSpec.getSize(ws),shown?:if(body.visibility==GONE)0 else body.measuredHeight)}
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){body.layout(0,0,r-l,body.measuredHeight)}
 override fun onDraw(c:Canvas){super.onDraw(c);old?.takeUnless{it.isRecycled}?.let{paint.alpha=(255*(1-progress/.22f).coerceIn(0f,1f)).toInt();val save=c.save();c.clipRect(0,0,width,height);c.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),start.toFloat()),paint);c.restoreToCount(save)}}
 override fun onDetachedFromWindow(){stop();complete();super.onDetachedFromWindow()}
}
