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

    /**
     * The fan (approved «карточка уходит под веер»). The sheets under the card are real quote cards — the next quotes —
     * fanned out: the first turned a little one way, the second the other, both peeking out. Dragging the card moves
     * every sheet one place forward toward its new place. Thrown away, the card swings aside, slips under the deck and
     * becomes its last sheet, while the sheet in front straightens up into the card: nothing appears or disappears.
     */
    private inner class Deck:FrameLayout(a){
        private val fanRot=floatArrayOf(-2.4f,3.2f);private val fanX=floatArrayOf(-a.d(4).toFloat(),a.d(6).toFloat());private val fanY=floatArrayOf(a.d(6).toFloat(),a.d(9).toFloat())
        private val depth=minOf(list.size,3)
        /** cards[0] is the card on top; cards[1..] are the sheets of the fan. */
        private val cards=MutableList(depth){k->card(Math.floorMod(index+k,list.size))}
        private val front get()=cards[0]
        private var heldH=-1
        private var downX=0f;private var downY=0f;private var dragging=false;private var velocity:VelocityTracker?=null
        private var busy=false
        private var lift=0f
        init{clipChildren=false;clipToPadding=false;cards.asReversed().forEach{addView(it)};pose(0f)}
        override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws)
            front.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),if(heldH>=0)MeasureSpec.makeMeasureSpec(heldH,MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
            val h=front.measuredHeight
            // sheets take the card's height, so their edges peek out evenly whatever their own text
            for(k in 1 until cards.size)cards[k].measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
            for(i in 0 until childCount){val c=getChildAt(i);if(c !in cards)c.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
            setMeasuredDimension(w,h+if(cards.size>1)a.d(18) else 0)}
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){for(i in 0 until childCount){val c=getChildAt(i);c.layout(0,0,r-l,c.measuredHeight)};pose(lift)}
        /** Place [v] at slot [slot] (−1 = the card's own place, 0/1 = the fan), moved [p] of the way to the slot before it. */
        private fun place(v:View,slot:Int,p:Float){
            fun r(s:Int)=if(s<0)0f else fanRot[s];fun x(s:Int)=if(s<0)0f else fanX[s];fun y(s:Int)=if(s<0)0f else fanY[s]
            fun e(s:Int)=a.d(if(s<0)(if(a.light)8 else 10) else if(s==0)(if(a.light)4 else 6) else 3).toFloat()
            val to=slot-1;v.pivotX=v.width/2f;v.pivotY=v.height/2f
            v.rotation=r(slot)+(r(to)-r(slot))*p;v.translationX=x(slot)+(x(to)-x(slot))*p;v.translationY=y(slot)+(y(to)-y(slot))*p
            v.elevation=e(slot)+(e(to)-e(slot))*p.coerceIn(0f,1f);v.translationZ=-a.d(1)*(slot+1-p)}
        /** [p] (0..1, may overshoot a little) moves every sheet one place forward. */
        private fun pose(p:Float){lift=p;for(k in 1 until cards.size)place(cards[k],k-1,p)}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
            if(list.size<2||busy)return false
            when(e.actionMasked){MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;dragging=false;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)}}
                MotionEvent.ACTION_MOVE->{velocity?.addMovement(e);val dx=e.x-downX;val dy=e.y-downY;if(abs(dx)>a.d(10)&&abs(dx)>abs(dy)*1.2f){dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true}}}
            return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(!dragging){if(e.actionMasked==MotionEvent.ACTION_DOWN&&list.size>1&&!busy){downX=e.x;downY=e.y;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)};return true};return super.onTouchEvent(e)}
            velocity?.addMovement(e);val dx=e.x-downX
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->{front.translationX=dx;front.translationY=abs(dx)*.05f;front.rotation=dx/width*10f;pose((abs(dx)/(width*.6f)).coerceIn(0f,1f)*.55f)}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f;dragging=false;parent?.requestDisallowInterceptTouchEvent(false)
                    val go=e.actionMasked==MotionEvent.ACTION_UP&&(abs(dx)>width*.3f||abs(vx)>a.d(700)&&sign(vx)==sign(dx))
                    if(go)fling(if(dx<0)-1 else 1) else settleBack()}
            };return true}
        private fun settleBack(){front.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(380).setInterpolator(PathInterpolator(.2f,1.25f,.4f,1f)).start()
            val from=lift;ValueAnimator.ofFloat(from,0f).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pose(it.animatedValue as Float)};start()}}
        private fun fling(dir:Int){
            busy=true;val old=front;val oldH=old.height
            if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            index=Math.floorMod(index+(if(dir<0)1 else -1),list.size);a.quoteDeckIndex[book.str("id")]=index;updateCounter()
            val glide=PathInterpolator(.2f,0f,0f,1f);val swing=PathInterpolator(.45f,0f,.25f,1f);val settle=android.view.animation.OvershootInterpolator(1.1f)
            val sx=old.translationX;val sy=old.translationY;val sr=old.rotation;val from=lift
            val outX=width*.82f*dir;val outY=a.d(28).toFloat();val outR=dir*16f
            val back=cards.size-2 // the last place in the fan
            val next=if(cards.size>1)cards[1] else null
            fun finish(){
                // the deck turns round: the risen sheet is the card now, the thrown card the last sheet
                cards.removeAt(0);cards.add(old);heldH=-1
                // with more quotes than places, the last sheet shows the quote that really comes there; it fades in
                // over the thrown card, which only peeks out at the edges
                if(list.size>cards.size){val k=cards.size-1;val fresh=card(Math.floorMod(index+k,list.size));fresh.alpha=0f;addView(fresh,indexOfChild(old)+1)
                    cards[k]=fresh;measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));layout(left,top,right,bottom)
                    pose(0f);fresh.animate().alpha(1f).setDuration(220).withEndAction{removeView(old)}.start()}
                pose(0f);requestLayout();busy=false}
            if(reduced()||next==null){old.translationX=0f;old.translationY=0f;old.rotation=0f;finish();return}
            // the card keeps the height it had; the risen sheet grows to its own text once it is on top
            val newH=run{next.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));next.measuredHeight}
            ValueAnimator.ofFloat(0f,1f).apply{duration=230;interpolator=glide
                addUpdateListener{val t=it.animatedValue as Float;old.translationX=sx+(outX-sx)*t;old.translationY=sy+(outY-sy)*t;old.rotation=sr+(outR-sr)*t;pose(from+(.75f-from)*t)}
                addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){
                    // under the deck now: it takes the last place of the fan, the sheets in front step forward
                    old.elevation=a.d(3).toFloat();old.translationZ=-a.d(4).toFloat()
                    val lp=lift
                    ValueAnimator.ofFloat(0f,1f).apply{duration=520;interpolator=swing
                        addUpdateListener{val t=it.animatedValue as Float
                            old.pivotX=old.width/2f;old.pivotY=old.height/2f
                            old.translationX=outX+(fanX[back]-outX)*t;old.translationY=outY+(fanY[back]-outY)*t;old.rotation=outR+(fanRot[back]-outR)*t
                            pose(lp+(1f-lp)*settle.getInterpolation(t))
                            heldH=(oldH+(newH-oldH)*glide.getInterpolation(t)).toInt()
                            // the sheet rising into the card grows to its own height; the deck follows
                            requestLayout()}
                        addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){finish()}});start()}}});start()}
        }
    }
}
