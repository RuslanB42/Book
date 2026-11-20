package app.polka.nativeapp

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator

/**
 * «Назад» by a swipe to the right, as in other apps: inside a shelf (yours or a status one) or a book's quotes, the
 * page follows the finger to the right; let go past a third of the screen (or with a flick) and it slides away and the
 * list it came from glides in from the left; let go earlier and it settles back. The swipe starts anywhere on the page
 * except over something that scrolls sideways itself, and never from the very edge, which belongs to Android's own back.
 */
internal class SwipeBack(private val a:MainActivity){
    private var x0=0f;private var y0=0f;private var tracking=false;private var active=false;private var velocity:VelocityTracker?=null
    private val slop=ViewConfiguration.get(a).scaledTouchSlop
    private var settle:ValueAnimator?=null
    /** What the swipe takes away: the settings, the profile (statistics), or the page inside a shelf / a book's quotes.
     *  An overlay slides off and uncovers the page under it, as in other apps. */
    private fun target():View?{if(a.reader!=null||a.addMenu!=null||a.goalRoad!=null||a.tierList!=null)return null
        a.settingsPage?.let{return it};a.profileOverlay?.let{return it}
        return if(!a.shelvesEditing&&(a.shelf!=null||a.auto!=null))a.scroll else null}
    private var view:View?=null
    /** Whether something under the finger scrolls sideways (a row of covers, a carousel): the swipe is its. */
    private fun sideways(v:View,x:Int,y:Int):Boolean{if(!v.isShown)return false;val r=android.graphics.Rect();if(!v.getGlobalVisibleRect(r)||!r.contains(x,y))return false
        if(v.canScrollHorizontally(-1)||v.canScrollHorizontally(1))return true
        if(v is ViewGroup)for(i in v.childCount-1 downTo 0)if(sideways(v.getChildAt(i),x,y))return true;return false}
    /** Returns true when the event is the swipe's (the page under it is not to get it). */
    fun handle(e:MotionEvent,cancelChildren:()->Unit):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{tracking=false;active=false
                val edge=a.d(28)
                val t=target()
                if(t!=null&&settle?.isRunning!=true&&e.rawX>edge&&!sideways(t,e.rawX.toInt(),e.rawY.toInt())){view=t;tracking=true;x0=e.rawX;y0=e.rawY;velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(e)}}
            MotionEvent.ACTION_MOVE->if(tracking){velocity?.addMovement(e);val dx=e.rawX-x0;val dy=e.rawY-y0
                if(!active){if(kotlin.math.abs(dy)>slop&&kotlin.math.abs(dy)>kotlin.math.abs(dx))tracking=false
                    else if(dx>slop*1.5f&&dx>kotlin.math.abs(dy)*1.6f){active=true;x0=e.rawX;cancelChildren()}}
                if(active){move(maxOf(0f,e.rawX-x0));return true}}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val was=active;active=false
                if(was&&tracking){velocity?.addMovement(e);velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f;val dx=view?.translationX?:0f
                    val w=a.root.width.toFloat()
                    if(e.actionMasked==MotionEvent.ACTION_UP&&(dx>w/3||vx>a.d(900)))leave(dx,w) else back(dx)}
                tracking=false;velocity?.recycle();velocity=null;if(was)return true}}
        return active}
    /** Under a shelf's page: the picture of the list it was opened from, shown while the page is dragged away. */
    private var under:android.widget.ImageView?=null
    private fun uncover(){if(view!==a.scroll||under!=null)return;val b=a.backdrop?:return
        val iv=android.widget.ImageView(a).apply{setImageBitmap(b);scaleType=android.widget.ImageView.ScaleType.MATRIX;setBackgroundColor(a.bg)}
        val at=IntArray(2);a.scroll.getLocationInWindow(at);val rootAt=IntArray(2);a.root.getLocationInWindow(rootAt)
        // the page itself is see-through (the window colour shows through it); while it slides over the list it must
        // be solid like the profile and the settings are, or the two show through each other
        sceneBg=a.scroll.background;a.scroll.setBackgroundColor(a.bg)
        a.root.addView(iv,a.root.indexOfChild(a.scroll),android.widget.FrameLayout.LayoutParams(a.scroll.width,a.scroll.height).apply{leftMargin=at[0]-rootAt[0];topMargin=at[1]-rootAt[1]});under=iv}
    private var sceneBg:android.graphics.drawable.Drawable?=null
    private fun cover(){if(under!=null)a.scroll.background=sceneBg;sceneBg=null;under?.let{a.root.removeView(it)};under=null}
    /** The navigation is not on a shelf's page; the list under it has it, so it shows in the part already uncovered. */
    private fun navAt(dx:Float){if(view!==a.scroll)return;listOfNotNull<View>(a.nativeNavigation,a.addButton).forEach{n->
        if(dx<=0f){n.clipBounds=null;n.visibility=View.INVISIBLE;return@forEach}
        n.visibility=View.VISIBLE;n.alpha=1f;n.translationY=0f
        n.clipBounds=android.graphics.Rect(0,0,(dx-n.left).toInt().coerceIn(0,n.width),n.height)}}
    private fun move(dx:Float){val v=view?:return;if(dx>0f)uncover();v.translationX=dx;navAt(dx)}
    private fun back(from:Float){settle?.cancel();settle=ValueAnimator.ofFloat(from,0f).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{move(it.animatedValue as Float)}
        addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){cover()}});start()}}
    private fun leave(from:Float,w:Float){settle?.cancel();settle=ValueAnimator.ofFloat(from,w).apply{duration=(220*(1-from/w)).toLong().coerceAtLeast(90);interpolator=PathInterpolator(.3f,0f,.6f,1f)
        addUpdateListener{move(it.animatedValue as Float)}
        addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){val v=view;view=null
            if(v===a.scroll){listOfNotNull<View>(a.nativeNavigation,a.addButton).forEach{it.clipBounds=null};a.navRevealed=true;v.translationX=0f;a.swipedBack();cover()} else if(v!=null)a.swipedAway(v)}});start()}}
}
