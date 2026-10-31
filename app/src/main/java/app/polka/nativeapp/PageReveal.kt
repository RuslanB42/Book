package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt

/** The reader page colour, so the opening page is already the right paper. */
internal fun MainActivity.readerPaper():Int=when(prefs.str("rt")){"black"->0xff000000.toInt();"sepia"->0xfff3ead8.toInt();"paper"->0xfffbfaf7.toInt();"night"->0xff18181a.toInt();else->bg}

/** Where the book's cover is on screen right now (root coordinates), or null if it is not visible. */
internal fun MainActivity.coverRect(id:String,explicit:View?):RectF?{
    fun find(v:View):View?{if(v is CoverView&&v.bookId==id&&v.isShown&&v.getGlobalVisibleRect(Rect()))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    val source=explicit?.takeIf{it.isShown&&it.width>0}?:find(root)?:return null
    val at=IntArray(2);val origin=IntArray(2);source.getLocationOnScreen(at);root.getLocationOnScreen(origin)
    val x=(at[0]-origin[0]).toFloat();val y=(at[1]-origin[1]).toFloat();val r=RectF(x,y,x+source.width*source.scaleX,y+source.height*source.scaleY)
    return if(r.bottom<0||r.top>root.height||r.width()<=1f)null else r
}

/** A sheet of the reader's paper that grows from the cover to the whole screen (and back). Nothing is captured
 *  or rendered in 3D, so it starts on the very frame of the tap; the text is prepared while it moves. */
internal class PageReveal(private val a:MainActivity,var from:RectF?,private val paper:Int):View(a){
    var t=0f;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    private var clock:ValueAnimator?=null
    init{isClickable=true;elevation=a.d(60).toFloat();importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
    override fun onTouchEvent(e:MotionEvent)=true
    override fun onDraw(c:Canvas){
        val W=width.toFloat();val H=height.toFloat();if(W<=0f)return
        val f=from?:RectF(W*.3f,H*.3f,W*.7f,H*.7f)
        rect.set(f.left+(0f-f.left)*t,f.top+(0f-f.top)*t,f.right+(W-f.right)*t,f.bottom+(H-f.bottom)*t)
        val r=a.d(10)*(1f-t)
        p.color=paper
        if(t<1f&&android.os.Build.VERSION.SDK_INT>=28)p.setShadowLayer(a.d(18)*(1f-t)+1f,0f,a.d(6)*(1f-t),((if(a.light)0x30 else 0x70)*(1f-t)).roundToInt() shl 24) else p.clearShadowLayer()
        c.drawRoundRect(rect,r,r,p)
    }
    fun run(to:Float,duration:Long,end:()->Unit){
        clock?.removeAllListeners();clock?.cancel()
        clock=ValueAnimator.ofFloat(t,to).apply{this.duration=duration;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{t=it.animatedValue as Float}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(clock===x){clock=null;end()}}});start()}
    }
    override fun onDetachedFromWindow(){clock?.removeAllListeners();clock?.cancel();clock=null;super.onDetachedFromWindow()}
}

/** Closing: the page fades to plain paper, the library is rebuilt underneath, then the paper folds back into its cover. */
internal fun MainActivity.closeWithReveal(r:NativeReader){
    val live=r.view;val veil=PageReveal(this,null,(live.background as? android.graphics.drawable.ColorDrawable)?.color?:readerPaper()).apply{t=1f;alpha=0f}
    bookClosing=true;root.addView(veil,FrameLayout.LayoutParams(-1,-1))
    veil.animate().alpha(1f).setDuration(150).withEndAction{
        (live.parent as? ViewGroup)?.removeView(live);bookClosing=false
        render(true,false);(veil.parent as? ViewGroup)?.removeView(veil);root.addView(veil,FrameLayout.LayoutParams(-1,-1));finishBookReturn()
        // Wait for the rebuilt library to be laid out, then fold the paper back into the book's cover.
        val observer=veil.viewTreeObserver
        observer.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
            if(observer.isAlive)observer.removeOnPreDrawListener(this)
            val target=coverRect(r.book.str("id"),null);veil.from=target
            if(target==null)veil.animate().alpha(0f).setDuration(220).withEndAction{root.removeView(veil)}.start()
            else{veil.run(0f,380L){root.removeView(veil)};veil.animate().alpha(0f).setStartDelay(260).setDuration(120).start()}
            return true}})
    }.start()
}
