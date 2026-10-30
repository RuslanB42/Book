package app.polka.nativeapp

import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

/** Source enableReorder: 360ms hold, 1.08 lift, 18% slot hysteresis, 260/300ms FLIP. */
internal class ShelfReorder(private val a:MainActivity,private val grid:VirtualGrid,private val items:MutableList<JSONObject>,private val onHold:((JSONObject)->Unit)?=null,private val commit:(List<JSONObject>)->Unit) {
    private var pending=-1;private var pointer=-1;private var sx=0f;private var sy=0f;private var px=0f;private var py=0f
    private var index=-1;private var original=emptyList<JSONObject>();private var grabbed:View?=null;private var ghost:ImageView?=null;private var image:Bitmap?=null
    private var gx=0f;private var gy=0f;private var moved=false;private var pad=0;private var lastFrame=0L;private var landing=false
    internal val active get()=index>=0
    private fun reduced()=a.prefs.str("motion")=="reduced"||!android.animation.ValueAnimator.areAnimatorsEnabled()
    private val lift=Runnable {begin()}
    private val frame=object:Runnable{override fun run(){if(!active)return;val now=SystemClock.uptimeMillis();val dt=if(lastFrame==0L)16 else (now-lastFrame).coerceIn(1,32);lastFrame=now
        val scroll=generateSequence(grid.parent){it.parent}.filterIsInstance<ScrollView>().firstOrNull()
        if(scroll!=null){val r=Rect();scroll.getGlobalVisibleRect(r);val rootAt=IntArray(2);a.root.getLocationOnScreen(rootAt);r.top=maxOf(r.top,rootAt[1]+a.appInsets.top);r.bottom=minOf(r.bottom,rootAt[1]+a.root.height-a.navigationBottom()-a.d(72));val edge=minOf(a.d(90),r.height()/3).coerceAtLeast(1);val velocity=when{py<r.top+edge->-((r.top+edge-py)/edge).coerceIn(0f,1f);py>r.bottom-edge->((py-(r.bottom-edge))/edge).coerceIn(0f,1f);else->0f};if(velocity!=0f)scroll.scrollBy(0,(velocity*a.d(720)*dt/1000f).roundToInt())}
        position();hit();grid.postOnAnimation(this)}}
    fun intercept(e:MotionEvent):Boolean {
        if(active)return true
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{clearPending();if(landing)return false;val hit=grid.cellAt(e.x,e.y)?.takeIf{grid.movable(it.first)}?:return false;pending=hit.first;pointer=e.getPointerId(0);sx=e.rawX;sy=e.rawY;px=sx;py=sy;grid.postDelayed(lift,360)}
            MotionEvent.ACTION_MOVE->{if(hypot(e.rawX-sx,e.rawY-sy)>a.d(10)){clearPending()}}
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_UP->clearPending()
        };return false
    }
    fun touch(e:MotionEvent):Boolean {if(!active)return false
        if(e.pointerCount>1||e.actionMasked==MotionEvent.ACTION_CANCEL||e.actionMasked==MotionEvent.ACTION_POINTER_UP){finish(false);return true}
        if(e.getPointerId(e.actionIndex)!=pointer){finish(false);return true}
        px=e.rawX;py=e.rawY
        when(e.actionMasked){MotionEvent.ACTION_MOVE->{position();hit()};MotionEvent.ACTION_UP->finish(true)};return true
    }
    private fun clearPending(){grid.removeCallbacks(lift);pending=-1}
    private fun begin(){val at=pending;val view=grid.viewAt(at)?:return;clearPending();if(!grid.isAttachedToWindow||view.width<=0||view.height<=0)return
        grid.cancelChildTouch()
        index=at;original=items.toList();grabbed=view;grid.pinned=view
        moved=false;pad=a.d(if(hasShelfArt(view))60 else 22) // shelf glass casts a 34dp blur + 18dp offset shadow; a smaller margin clips it into a black box
        val loc=IntArray(2);view.getLocationOnScreen(loc);gx=px-loc[0]+pad;gy=py-loc[1]+pad
        // The whole card (art AND caption) floats as one picture, so text can never lag behind or stay in the slot.
        val cover=(view as? ViewGroup)?.getChildAt(0)
        val w=view.width;val h=view.height
        image=Bitmap.createBitmap(w+pad*2,h+pad*2,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
            if(cover!=null){c.translate(pad.toFloat(),pad.toFloat());val a0=view.alpha;view.alpha=1f;view.draw(c);view.alpha=a0}
        ghost=ImageView(a).apply{setImageBitmap(image);scaleType=ImageView.ScaleType.FIT_XY;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false}
        a.root.overlay.add(ghost!!);ghost!!.measure(View.MeasureSpec.makeMeasureSpec(w+pad*2,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h+pad*2,View.MeasureSpec.EXACTLY));ghost!!.layout(0,0,w+pad*2,h+pad*2);ghost!!.pivotX=(w+pad*2)/2f;ghost!!.pivotY=pad+(cover?.let{(it.top+it.bottom)/2f}?:h/2f)
        view.alpha=0f;grid.parent.requestDisallowInterceptTouchEvent(true);view.isPressed=false;view.cancelLongPress();view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if(!reduced()){ghost!!.alpha=.0f;ghost!!.scaleX=1f;ghost!!.scaleY=1f;ghost!!.animate().alpha(1f).scaleX(1.05f).scaleY(1.05f).setDuration(200).setInterpolator(MotionSpec.settle).start()}else ghost!!.alpha=1f
        a.shelfReorder=this;position();lastFrame=0;grid.postOnAnimation(frame)
    }
    private fun hasShelfArt(v:View):Boolean=v is ShelfArt||(v is ViewGroup&&(0 until v.childCount).any{hasShelfArt(v.getChildAt(it))})
    private fun position(){if(hypot(px-sx,py-sy)>a.d(12))moved=true;val at=IntArray(2);a.root.getLocationOnScreen(at);ghost?.translationX=px-gx-at[0];ghost?.translationY=py-gy-at[1]}
    private fun hit(){if(!active)return;val at=IntArray(2);grid.getLocationOnScreen(at);val r=grid.slot(index);val target=grid.nearest(px-gx+pad-at[0]+r.width()/2f,py-gy+pad-at[1]+r.width()*.75f,index);if(target!=index){val from=index;index=target;grid.move(from,target,{items.add(target,items.removeAt(from))},!reduced())}}
    private fun finish(save:Boolean){if(!active)return;grid.removeCallbacks(frame);clearPending();val from=index
        if(!save){val target=original.indexOfFirst{it.str("id")==items[from].str("id")};if(target>=0&&target!=from)grid.move(from,target,{items.clear();items.addAll(original)},false);index=target}
        val view=grabbed;val floating=ghost;val changed=items.map{it.str("id")}!=original.map{it.str("id")};val tapped=if(save&&!changed&&!moved)items[from] else null;index=-1;grid.parent?.requestDisallowInterceptTouchEvent(false);landing=true
        val loc=IntArray(2);view?.getLocationOnScreen(loc);val rootAt=IntArray(2);a.root.getLocationOnScreen(rootAt)
        if(save&&changed)commit(items.toList())
        val done={cleanup();grid.sync();a.flushDeferredRender();tapped?.let{t->onHold?.invoke(t)};Unit}
        if(floating==null||reduced()||!grid.isAttachedToWindow)done()else floating.animate().translationX((loc[0]-rootAt[0]-pad).toFloat()).translationY((loc[1]-rootAt[1]-pad).toFloat()).scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(MotionSpec.settle).withEndAction{done()}.start()
    }
    private fun cleanup(){ghost?.animate()?.cancel();ghost?.let{a.root.overlay.remove(it);it.setImageDrawable(null)};ghost=null;image?.recycle();image=null;grabbed?.alpha=1f;grabbed=null;pad=0;grid.pinned=null;landing=false;if(a.shelfReorder===this)a.shelfReorder=null}
    fun cancel(){clearPending();if(active)finish(false);if(landing)cleanup()}
}
