package app.polka.nativeapp

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup

/**
 * «Назад» by a swipe to the right, as in other apps: a page one level in (a shelf or a status shelf, the statistics,
 * the settings) follows the finger to the right while the page under it comes back from the left out of the dark
 * (the same motion as the back button, see [Depth]); let go past a third of the screen (or with a flick) and it goes,
 * let go earlier and it settles back. The swipe starts anywhere on the page except over something that scrolls
 * sideways itself, and never from the very edge, which belongs to Android's own back.
 */
internal class SwipeBack(private val a:MainActivity){
    private var x0=0f;private var y0=0f;private var tracking=false;private var active=false;private var velocity:VelocityTracker?=null
    private val slop=ViewConfiguration.get(a).scaledTouchSlop
    /** What the swipe takes away and what is done once it has gone. The tier list and the goal road page through
     *  their years sideways themselves, so they close by their own button. */
    private fun target():Pair<View,()->Unit>?{if(a.reader!=null||a.addMenu!=null||a.goalRoad!=null||a.tierList!=null)return null
        a.settingsPage?.let{v->return v to {a.swipedAway(v)}};a.profileOverlay?.let{v->return v to {a.swipedAway(v)}}
        return if(!a.shelvesEditing&&(a.shelf!=null||a.auto!=null))a.scroll to {a.swipedBack()} else null}
    private var view:View?=null;private var gone:(()->Unit)?=null
    private var depth:Depth?=null
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
                if(t!=null&&depth==null&&e.rawX>edge&&!sideways(t.first,e.rawX.toInt(),e.rawY.toInt())){view=t.first;gone=t.second;tracking=true;x0=e.rawX;y0=e.rawY;velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(e)}}
            MotionEvent.ACTION_MOVE->if(tracking){velocity?.addMovement(e);val dx=e.rawX-x0;val dy=e.rawY-y0
                if(!active){if(kotlin.math.abs(dy)>slop&&kotlin.math.abs(dy)>kotlin.math.abs(dx))tracking=false
                    else if(dx>slop*1.5f&&dx>kotlin.math.abs(dy)*1.6f){active=true;x0=e.rawX;cancelChildren()}}
                if(active){move(maxOf(0f,e.rawX-x0));return true}}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val was=active;active=false
                if(was&&tracking){velocity?.addMovement(e);velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f;val dx=view?.translationX?:0f
                    val w=a.root.width.toFloat()
                    if(e.actionMasked==MotionEvent.ACTION_UP&&(dx>w/3||vx>a.d(900)))leave() else back()}
                tracking=false;velocity?.recycle();velocity=null;if(was)return true}}
        return active}
    /** Under a shelf's page: the picture of the list it was opened from (the navigation in it), shown while the page
     *  is dragged away. The settings and the statistics have the live page under them. */
    private var under:android.widget.ImageView?=null
    private var sceneBg:android.graphics.drawable.Drawable?=null
    private fun uncover(){val v=view?:return;if(depth!=null)return
        if(v===a.scroll){val b=a.backdrop?:return
            val iv=android.widget.ImageView(a).apply{setImageBitmap(b);scaleType=android.widget.ImageView.ScaleType.MATRIX;setBackgroundColor(a.bg)}
            // the page itself is see-through (the window colour shows through it); while it slides over the list it
            // must be solid like the statistics and the settings are, or the two show through each other
            sceneBg=a.scroll.background;a.scroll.setBackgroundColor(a.bg)
            a.root.addView(iv,0,android.widget.FrameLayout.LayoutParams(b.width,b.height));under=iv}
        depth=Depth(a,v)}
    private fun cover(){if(under!=null)a.scroll.background=sceneBg;sceneBg=null;under?.let{a.root.removeView(it)};under=null;depth=null}
    private fun move(dx:Float){val v=view?:return;uncover();depth?.pose((dx/a.root.width.coerceAtLeast(1)).coerceIn(0f,1f))?:run{v.translationX=dx}}
    private fun back(){val d=depth;if(d==null){view?.translationX=0f;return};d.settle{cover()}}
    private fun leave(){val d=depth;val done=gone;view=null;gone=null
        if(d==null){done?.invoke();return}
        d.leave{cover();done?.invoke()}}
}
