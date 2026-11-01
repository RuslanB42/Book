package app.polka.nativeapp

import android.animation.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

/**
 * A book's quotes as a deck of cards: the front card follows the finger, tilts a little and flies off when swiped
 * (left → next, right → previous, round and round); the sheet behind rises into its place while you drag, and the new
 * quote settles on top. Above it — which book the quotes are from and where you are in the deck.
 */
internal class QuoteDeck(private val a:MainActivity,private val book:JSONObject,quotes:List<JSONObject>,private val showHead:Boolean=true,private val onOpen:((JSONObject)->Unit)?=null):LinearLayout(a){
    private val list=quotes.sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")})
    private var index=(a.quoteDeckIndex[book.str("id")]?:0).coerceIn(0,(list.size-1).coerceAtLeast(0))
    private val counter=a.text("",12.5f,a.muted)
    private val deck=Deck()
    private fun reduced()=a.prefs.str("motion")=="reduced"
    private fun word(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(2),0,a.d(2),a.d(12));isClickable=true;setOnClickListener{a.detail(book)};contentDescription="Цитаты из книги ${book.str("title")}"}
        head.addView(CoverView(a,book),LayoutParams(a.d(30),a.d(45)))
        val meta=a.column().apply{setPadding(a.d(12),0,a.d(8),0)}
        meta.addView(a.text(book.str("title"),15f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        meta.addView(a.text(MainActivity.authors(book).ifBlank{"Автор не указан"},12.5f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        head.addView(meta,LayoutParams(0,-2,1f));head.addView(counter)
        if(showHead)addView(head,LayoutParams(-1,-2))
        addView(deck,LayoutParams(-1,-2))
        // Without the book line (on the book's own card) the position sits under the deck.
        if(!showHead&&list.size>1){(counter.parent as? ViewGroup)?.removeView(counter);counter.gravity=Gravity.CENTER;addView(counter,LayoutParams(-1,-2).apply{topMargin=a.d(10)})}
        updateCounter()
    }
    private fun updateCounter(){counter.text=if(list.size>1)"${index+1} из ${list.size}" else "1 ${word(1)}"}
    private fun card(i:Int):View=a.quoteCardNative(book,list[i],onOpen?.let{f->{f(list[i])}})

    private inner class Deck:FrameLayout(a){
        private val step=a.d(9).toFloat();private val inset=a.d(12).toFloat()
        private val sheets=List((list.size-1).coerceIn(0,2)){i->View(a).apply{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;a.floatCard(this,22,if(a.light)(if(i==0)0xfff7f7f5.toInt() else 0xffefefec.toInt()) else (if(i==0)0xff202022.toInt() else 0xff1c1c1e.toInt()),if(a.light)5-i*2 else 7-i*2)}}
        private var front:View=card(index)
        private var frontH=0;private var heldH=-1
        private var downX=0f;private var downY=0f;private var dragging=false;private var velocity:VelocityTracker?=null
        private var busy=false
        init{clipChildren=false;clipToPadding=false;sheets.asReversed().forEach{addView(it)};addView(front);pose(0f)}
        override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws)
            for(i in 0 until childCount){val c=getChildAt(i);if(c in sheets)continue;c.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))}
            frontH=front.measuredHeight;val h=if(heldH>=0)heldH else frontH
            sheets.forEach{it.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
            setMeasuredDimension(w,h+(sheets.size*step).toInt())}
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){for(i in 0 until childCount){val c=getChildAt(i);c.layout(0,0,r-l,c.measuredHeight)};pose(lift)}
        private var lift=0f
        /** Sheets behind: narrower and lower; [p] (0..1) raises the first one toward the front position. */
        private fun pose(p:Float){lift=p
            sheets.forEachIndexed{i,s->val k=(i+1)-if(i==0)p else p*.5f;val w=s.width.toFloat().takeIf{it>0}?:return@forEachIndexed
                s.pivotX=w/2;s.pivotY=0f;s.scaleX=(w-2*inset*k)/w;s.scaleY=1f;s.translationY=step*k;s.translationZ=-a.d(2)*k}}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
            if(list.size<2||busy)return false
            when(e.actionMasked){MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;dragging=false;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)}}
                MotionEvent.ACTION_MOVE->{velocity?.addMovement(e);val dx=e.x-downX;val dy=e.y-downY;if(abs(dx)>a.d(10)&&abs(dx)>abs(dy)*1.2f){dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true}}}
            return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(!dragging){if(e.actionMasked==MotionEvent.ACTION_DOWN&&list.size>1&&!busy){downX=e.x;downY=e.y;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)};return true};return super.onTouchEvent(e)}
            velocity?.addMovement(e);val dx=e.x-downX
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->{front.translationX=dx;front.translationY=abs(dx)*.04f;front.rotation=dx/width*9f;pose((abs(dx)/(width*.6f)).coerceIn(0f,1f))}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f;dragging=false;parent?.requestDisallowInterceptTouchEvent(false)
                    val go=e.actionMasked==MotionEvent.ACTION_UP&&(abs(dx)>width*.3f||abs(vx)>a.d(700)&&sign(vx)==sign(dx))
                    if(go)fling(if(dx<0)-1 else 1,vx) else settleBack()}
            };return true}
        private fun settleBack(){front.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(380).setInterpolator(PathInterpolator(.2f,1.25f,.4f,1f)).start()
            val from=lift;ValueAnimator.ofFloat(from,0f).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pose(it.animatedValue as Float)};start()}}
        /** Swipe left → next quote, right → previous. The card leaves along its path, the next one lands on the risen sheet. */
        private fun fling(dir:Int,vx:Float){
            busy=true;val old=front;val oldH=frontH
            val dist=width*1.25f*dir;val speed=abs(vx).coerceAtLeast(a.d(1200).toFloat());val ms=((abs(dist-old.translationX)/speed)*1000).toLong().coerceIn(180,320)
            old.animate().translationX(dist).translationY(a.d(24).toFloat()).rotation(dir*14f).alpha(.0f).setDuration(ms).setInterpolator(PathInterpolator(.3f,0f,.8f,.6f)).withEndAction{removeView(old)}.start()
            ValueAnimator.ofFloat(lift,1f).apply{duration=ms;addUpdateListener{pose(it.animatedValue as Float)};start()}
            if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            index=Math.floorMod(index+(if(dir<0)1 else -1),list.size);a.quoteDeckIndex[book.str("id")]=index;updateCounter()
            postDelayed({
                val next=card(index);heldH=oldH;front=next;next.alpha=0f;next.translationY=a.d(6).toFloat();addView(next)
                measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
                val newH=next.measuredHeight
                next.animate().alpha(1f).translationY(0f).setDuration(300).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
                // height eases to the new card; the risen sheet sinks back behind it
                ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=PathInterpolator(.2f,0f,0f,1f)
                    addUpdateListener{val t=it.animatedValue as Float;heldH=(oldH+(newH-oldH)*t).toInt();pose(1f-t);requestLayout()}
                    addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){heldH=-1;pose(0f);requestLayout();busy=false}});start()}
            },(ms*.55f).toLong())
        }
    }
}
