package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.FrameLayout

/**
 * One rule for every page change (approved «Движение Glyph»): how a page moves depends only on where you go.
 * Tabs are neighbours (a short shift, variant «Б»), a page deeper in comes from the right over the one it came from,
 * sheets rise from below, a book opens through the dark. Three curves serve the whole app.
 */
object MotionSpec {
    /** Everything that comes in. */
    val enter=PathInterpolator(.2f,0f,0f,1f)
    /** Everything that goes away. */
    val exit=PathInterpolator(.4f,0f,1f,1f)
    /** Back, and changes in place. */
    val move=PathInterpolator(.4f,0f,.2f,1f)
    const val TAB_OUT=Motion.FAST;const val TAB_IN=Motion.STANDARD;const val TAB_IN_DELAY=60L;const val TAB_SHIFT=30
    const val DEEP_IN=Motion.EMPHASIS;const val DEEP_BACK=Motion.STANDARD;
    /** Let go too early (a swipe back, a sheet pulled down): back in place. */
    const val SETTLE=Motion.STANDARD
   const val DEEP_SHIFT=.3f;const val DEEP_DIM=.35f
    const val SHEET_IN=Motion.EMPHASIS;const val SHEET_OUT=Motion.STANDARD
    /** A question or a picture shown over the page, in place. */
    const val ASK_IN=Motion.STANDARD;const val ASK_OUT=Motion.FAST
    const val DARK_IN=Motion.STANDARD;const val DARK_OUT=Motion.EMPHASIS
    /** A switch in place (week, month, year): the pill, the figures and the chart arrive together. */
    const val SWITCH=Motion.EMPHASIS
    /** A page built anew in place. */
    const val PAGE_IN=Motion.EMPHASIS
}
data class MotionPose(val alpha:Float=1f,val x:Float=0f,val y:Float=0f,val scale:Float=1f)
class NativeMotion(val view:View,val from:MotionPose,val to:MotionPose=MotionPose(),val duration:Long,val delay:Long=0,val curve:TimeInterpolator=MotionSpec.enter,val complete:()->Unit={}) {
    private var animator:ValueAnimator?=null
    private var ended=false
    private val detach=object:View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v:View) {} ; override fun onViewDetachedFromWindow(v:View) { cancel() } }
    fun sample(elapsed:Long) { val t=curve.getInterpolation(((elapsed-delay).toFloat()/duration.coerceAtLeast(1)).coerceIn(0f,1f)); fun mix(x:Float,y:Float)=x+(y-x)*t
        view.alpha=mix(from.alpha,to.alpha); view.translationX=mix(from.x,to.x); view.translationY=mix(from.y,to.y); view.scaleX=mix(from.scale,to.scale); view.scaleY=view.scaleX }
    fun start():NativeMotion { view.addOnAttachStateChangeListener(detach); sample(0); animator=ValueAnimator.ofFloat(0f,1f).apply { duration=this@NativeMotion.duration+delay; interpolator=LinearInterpolator(); addUpdateListener { sample(currentPlayTime) }; addListener(object:AnimatorListenerAdapter() { override fun onAnimationEnd(animation:Animator) { finish() } }); start() }; return this }
    private fun finish(commit:Boolean=true) { if(ended)return; ended=true; animator?.removeAllListeners(); animator?.cancel(); animator=null; sample(duration+delay); view.removeOnAttachStateChangeListener(detach); if(commit)complete() }
    fun cancel()=finish(false)
}
class ScreenMotion(private val a:MainActivity) {
    private val motions=mutableListOf<NativeMotion>()
    private var ghost:View?=null
    private var tabView:View?=null
    private val tabAnims=mutableListOf<android.view.ViewPropertyAnimator>()
    private fun finishTab(){tabAnims.forEach{it.cancel()};tabAnims.clear();tabView?.let{it.translationX=0f;it.alpha=1f};tabView=null;ghost?.let{(it.parent as? ViewGroup)?.removeView(it)};ghost=null}

    /** Tabs are neighbours, not deeper: the REAL outgoing page (no snapshot) fades out shifting a little toward the
     *  side it is left for, the new one comes in from the other side the same short way (approved variant «Б»). */
    fun pushTab(old:View?,opening:Boolean){
        if(old==null||a.reducedMotion||a.root.width<=0)return
        (old.parent as? ViewGroup)?.removeView(old)
        old.isEnabled=false
        val blocker=object:FrameLayout(a){override fun onInterceptTouchEvent(e:android.view.MotionEvent)=true;override fun onTouchEvent(e:android.view.MotionEvent)=true}
        blocker.addView(old,FrameLayout.LayoutParams(-1,-1))
        ghost=blocker;val page=a.scroll;tabView=page
        a.root.addView(blocker,1.coerceAtMost(a.root.childCount),FrameLayout.LayoutParams(-1,-1))
        val dir=if(opening)1f else -1f;val shift=a.d(MotionSpec.TAB_SHIFT)*dir
        tabAnims+=blocker.animate().translationX(-shift).alpha(0f).setDuration(MotionSpec.TAB_OUT).setInterpolator(MotionSpec.exit).withEndAction{if(ghost===blocker){(blocker.parent as? ViewGroup)?.removeView(blocker);ghost=null}}.also{it.start()}
        page.translationX=shift;page.alpha=0f
        tabAnims+=page.animate().translationX(0f).alpha(1f).setStartDelay(MotionSpec.TAB_IN_DELAY).setDuration(MotionSpec.TAB_IN).setInterpolator(MotionSpec.enter).withEndAction{if(tabView===page)tabView=null}.also{it.start()}
    }
    fun stop() { finishTab();motions.toList().forEach { it.cancel() }; motions.clear() }
    fun play(view:View,from:MotionPose,duration:Long,delay:Long=0,curve:TimeInterpolator=MotionSpec.enter) {
        if(a.reducedMotion) return
        motions.add(NativeMotion(view,from,duration=duration,delay=delay,curve=curve).start())
    }
}

/**
 * A page that grows out of its card (the tier list, the year in books): a container transform — the rounded bounds
 * morph from the card to the screen while the card's picture fades out and the page, scaled with the bounds, fades in;
 * closing runs it backwards into the card as it is now. A swipe back holds the page under the finger (it shrinks a
 * little and follows it, its corners round); let go far enough and it folds into the card from there, else it settles.
 * [frame] is the full-screen layer, [content] its page; [source] is the card.
 */
internal class CardMorph(private val a:MainActivity,private val frame:FrameLayout,private val content:View,private val source:()->View?,private val corner:Int){
    private val snapView=android.widget.ImageView(a).apply{scaleType=android.widget.ImageView.ScaleType.FIT_XY;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var snap:Bitmap?=null;private var from:RectF?=null
    /** Where the page is when the morph is at 1: the whole screen, or where a swipe left it. */
    private val home=RectF()
    private val bounds=RectF();private var radius=0f
    private var anim:ValueAnimator?=null
    private var k=1f
    private val W get()=frame.width.takeIf{it>0}?.toFloat()?:a.root.width.toFloat()
    private val H get()=frame.height.takeIf{it>0}?.toFloat()?:a.root.height.toFloat()
    init{frame.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
            if(bounds.isEmpty)o.setRect(0,0,v.width,v.height) else o.setRoundRect(Math.round(bounds.left),Math.round(bounds.top),Math.round(bounds.right),Math.round(bounds.bottom),radius)}}
        frame.clipToOutline=true}
    /** One step: [p] 0 is the card, 1 the page at [home]; [round] is how round its corners are there. */
    private fun pose(p:Float,round:Float=0f){k=p;val f=from?:RectF(home)
        bounds.set(f.left+(home.left-f.left)*p,f.top+(home.top-f.top)*p,f.right+(home.right-f.right)*p,f.bottom+(home.bottom-f.bottom)*p)
        radius=a.d(corner)*(1-p)+round*p;frame.invalidateOutline()
        val sc=bounds.width()/W;content.pivotX=0f;content.pivotY=0f;content.scaleX=sc;content.scaleY=sc;content.translationX=bounds.left;content.translationY=bounds.top
        content.alpha=((p-.12f)/.42f).coerceIn(0f,1f)
        val b=snap;if(b!=null){val ss=bounds.width()/b.width;snapView.pivotX=0f;snapView.pivotY=0f;snapView.scaleX=ss;snapView.scaleY=ss;snapView.translationX=bounds.left;snapView.translationY=bounds.top
            snapView.alpha=(1f-p/.32f).coerceIn(0f,1f)}}
    /** Where the card is now and a picture of it. */
    private fun capture(){val v=source();frame.removeView(snapView);snapView.setImageDrawable(null);snap?.recycle();snap=null
        if(v==null||!v.isAttachedToWindow||v.width==0){from=null;return}
        val l=IntArray(2);v.getLocationOnScreen(l);val o=IntArray(2);frame.getLocationOnScreen(o)
        from=RectF((l[0]-o[0]).toFloat(),(l[1]-o[1]).toFloat(),(l[0]-o[0]+v.width).toFloat(),(l[1]-o[1]+v.height).toFloat())
        snap=try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}catch(e:Throwable){null}
        snap?.let{snapView.setImageBitmap(it);frame.addView(snapView,FrameLayout.LayoutParams(it.width,it.height,Gravity.TOP or Gravity.START))}}
    private fun run(f:Float,t:Float,ms:Long,i:TimeInterpolator,step:(Float)->Unit,end:()->Unit){stop()
        anim=ValueAnimator.ofFloat(f,t).apply{duration=ms;interpolator=i;addUpdateListener{step(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:Animator){cancelled=true}
                override fun onAnimationEnd(x:Animator){if(!cancelled)end()}});start()}}
    private fun stop(){anim?.removeAllListeners();anim?.cancel();anim=null}
    private fun still(){content.setLayerType(View.LAYER_TYPE_NONE,null);frame.removeView(snapView);snapView.setImageDrawable(null);snap?.recycle();snap=null
        bounds.setEmpty();frame.invalidateOutline();content.scaleX=1f;content.scaleY=1f;content.translationX=0f;content.translationY=0f;content.alpha=1f}
    private fun full(){home.set(0f,0f,W,H)}

    /** Out of the card, started on the first drawn frame so no step is skipped. */
    fun open(){full();capture();pose(0f);content.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        // it starts once the frames that build the page and its layer are drawn (they hold the card's own look), so
        // none of its steps is lost to them
        frame.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{frame.viewTreeObserver.removeOnPreDrawListener(this)
            full();pose(0f);val ch=Choreographer.getInstance();val g=++gen;ch.postFrameCallback{ch.postFrameCallback{if(g==gen&&frame.isAttachedToWindow)run(0f,1f,520,MotionSpec.enter,{pose(it)}){still()}}};return true}})}
    /** Back into the card (as it is now, so it is brought up to date first), from wherever the page is. */
    /** Which motion is current: an opening still waiting to start is dropped when the page closes first. */
    private var gen=0
    fun close(done:()->Unit){gen++;val held=!bounds.isEmpty&&swiping;swiping=false
        frame.post{val r=radius;if(held)home.set(bounds) else full();capture();content.setLayerType(View.LAYER_TYPE_HARDWARE,null);pose(if(held)1f else k,if(held)r else 0f)
            run(k,0f,if(held)380 else 420,MotionSpec.enter,{pose(it,if(held)r else 0f)}){done()}}}

    // ── the swipe back ──
    private var swiping=false
    /** The page under the finger, [dx] to the right of where the swipe started. */
    fun hold(dx:Float){if(!swiping){gen++;stop();swiping=true;from=null;content.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
        val q=(dx/W).coerceIn(0f,1f);val s=1f-.14f*q
        val w=W*s;val h=H*s;val left=(W-w)/2f+dx*.5f;val top=(H-h)/2f
        home.set(left,top,left+w,top+h);pose(1f,a.d(corner)*(q*5f).coerceAtMost(1f))}
    /** Let go too early: the page settles back to the whole screen. */
    fun settle(){if(!swiping)return;swiping=false;val h0=RectF(home);val r0=radius
        run(0f,1f,MotionSpec.SETTLE,MotionSpec.move,{t->home.set(h0.left*(1-t),h0.top*(1-t),h0.right+(W-h0.right)*t,h0.bottom+(H-h0.bottom)*t);pose(1f,r0*(1-t))}){still()}}
}

/**
 * «Вглубь»: the page on [top] comes in from the right edge while everything beneath it (its siblings: the live page or
 * a picture of it) moves a third of the way left and darkens. Back is the same motion reversed, and a swipe back drives
 * it under the finger. [at] 0 is the top page fully in, 1 fully away to the right.
 */
internal class Depth(private val a:MainActivity,private val top:View){
    private val host get()=top.parent as? ViewGroup
    private val dim=View(a).apply{setBackgroundColor(Color.BLACK);alpha=0f;outlineProvider=null;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var under=emptyList<View>()
    private var topZ=0f
    private var clock:ValueAnimator?=null
    var at=0f;private set
    private fun attach(){val h=host?:return;if(dim.parent!=null)return
        under=(0 until h.childCount).map{h.getChildAt(it)}.filter{it!==top&&it!==dim}
        dim.elevation=(under.maxOfOrNull{it.z}?:0f)+a.d(1)
        h.addView(dim,h.indexOfChild(top),ViewGroup.LayoutParams(-1,-1))
        topZ=top.translationZ;if(top.z<=dim.elevation)top.translationZ=dim.elevation+a.d(1)-top.elevation}
    private fun detach(){(dim.parent as? ViewGroup)?.removeView(dim);top.translationZ=topZ;under.forEach{it.translationX=0f};under=emptyList()}
    /** Puts the pages where they are at [p] (0 the top page in, 1 gone to the right). */
    fun pose(p:Float){attach();at=p;val w=(host?.width?:a.root.width).toFloat()
        top.translationX=w*p;under.forEach{it.translationX=-w*MotionSpec.DEEP_SHIFT*(1-p)};dim.alpha=MotionSpec.DEEP_DIM*(1-p)}
    private fun run(to:Float,ms:Long,curve:TimeInterpolator,done:()->Unit){stop();val from=at
        clock=ValueAnimator.ofFloat(from,to).apply{duration=ms;interpolator=curve;addUpdateListener{pose(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){var cancelled=false;override fun onAnimationCancel(x:Animator){cancelled=true}
                override fun onAnimationEnd(x:Animator){if(!cancelled){detach();done()}}});start()}}
    fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
    /** The top page comes in. */
    fun enter(done:()->Unit={}){if(a.reducedMotion){pose(0f);detach();done();return};pose(1f);run(0f,MotionSpec.DEEP_IN,MotionSpec.enter,done)}
    /** The top page goes back to the right, from wherever it is now. */
    fun leave(done:()->Unit={}){if(a.reducedMotion){detach();done();return};if(dim.parent==null)pose(0f)
        run(1f,(MotionSpec.DEEP_BACK*(1-at)).toLong().coerceAtLeast(90L),MotionSpec.move,done)}
    /** Let go too early in a swipe back: the top page settles in again. */
    fun settle(done:()->Unit={}){run(0f,MotionSpec.SETTLE,MotionSpec.move,done)}
}

/**
 * Where a sheet is: 0 up, 1 down out of sight. Rising (toward 0) it comes in, sinking (toward 1) it goes away; a part of
 * the way takes its part of the time. No bounce: sheets move like every other page (see [MotionSpec]).
 */
internal class SheetMotion(private val changed:(Float)->Unit){
    var value=0f;private set
    private var clock:ValueAnimator?=null
    fun snap(to:Float){stop();value=to;changed(value)}
    fun stop(){clock?.removeAllListeners();clock?.cancel();clock=null}
    fun to(target:Float){stop();val from=value;if(from==target){changed(value);return}
        val rising=target<from;val part=kotlin.math.abs(target-from).coerceIn(.4f,1f)
        clock=ValueAnimator.ofFloat(from,target).apply{duration=((if(rising)MotionSpec.SHEET_IN else MotionSpec.SHEET_OUT)*part).toLong()
            interpolator=if(rising)MotionSpec.enter else MotionSpec.exit;addUpdateListener{value=it.animatedValue as Float;changed(value)};start()}}
}
