package app.polka.nativeapp

import android.graphics.Outline
import android.graphics.RectF
import android.graphics.Matrix
import android.graphics.Path

import android.view.ViewOutlineProvider

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
    /** Which quote each card shows. */
    private val quoteOf=HashMap<View,Int>()
    private var index=(a.quoteDeckIndex[book.str("id")]?:0).coerceIn(0,(list.size-1).coerceAtLeast(0))
    private val counter=a.text("",Type.SECONDARY,a.muted)
    private val deck=Deck()
    private val dots=Dots()
    private fun reduced()=a.prefs.str("motion")=="reduced"
    private fun word(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        // Approved «А1»: a bigger cover and the title in the serif of the quotes, pressed close to the deck; where you are
        // in the deck is told by quiet dots under it (the current one stretched, in its quote's colour), not «1 из 6».
        val head=a.line().apply{gravity=Gravity.BOTTOM;setPadding(a.d(4),0,a.d(4),a.d(12));isClickable=true;setOnClickListener{a.detail(book)};contentDescription="Цитаты из книги ${book.str("title")}"}
        head.addView(CoverView(a,book),LayoutParams(a.d(46),a.d(69)))
        val meta=a.column().apply{setPadding(a.d(14),0,a.d(4),a.d(4))}
        meta.addView(a.text(book.str("title"),Type.H2,a.fg,true,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        meta.addView(a.text(MainActivity.authors(book).ifBlank{"Автор не указан"},Type.SECONDARY,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(4),0,0)})
        head.addView(meta,LayoutParams(0,-2,1f))
        if(showHead)addView(head,LayoutParams(-1,-2))
        addView(deck,LayoutParams(-1,-2))
        if(showHead){if(list.size>1)addView(if(list.size<=14)dots else counter.apply{gravity=Gravity.CENTER},LayoutParams(-1,if(list.size<=14)a.d(8) else -2).apply{topMargin=a.d(2)})}
        // Without the book line (on the book's own card) the position is told by the same dots under the deck.
        else if(list.size>1)addView(if(list.size<=14)dots else counter.apply{gravity=Gravity.CENTER},LayoutParams(-1,if(list.size<=14)a.d(8) else -2).apply{topMargin=a.d(10)})
        updateCounter()
    }
    private fun updateCounter(){counter.text=if(list.size>1)"${index+1} из ${list.size}" else "1 ${word(1)}";dots.go(index)}
    /** Position dots: the current one is a short capsule in its quote's colour, it glides to the next one. */
    private inner class Dots:View(a){
        private val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var at=index.toFloat();private var anim:ValueAnimator?=null
        private val rest=if(a.light)0xffc9c9cd.toInt() else 0xff5a5a5f.toInt()
        fun go(i:Int){if(width==0||reduced()){at=i.toFloat();invalidate();return};anim?.cancel();anim=ValueAnimator.ofFloat(at,i.toFloat()).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{at=it.animatedValue as Float;invalidate()};start()}}
        override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
        override fun onDraw(c:android.graphics.Canvas){val n=list.size;if(n<2)return
            val dot=a.d(6).toFloat();val gap=a.d(6).toFloat();val wide=a.d(18).toFloat();val h=dot;val y=(height-h)/2f
            var x=(width-(n*dot+(n-1)*gap+(wide-dot)))/2f
            val lo=floor(at).toInt();val f=at-lo
            for(i in 0 until n){val k=when(i){lo->1f-f;lo+1->f;else->0f};val w=dot+(wide-dot)*k
                p.color=mix(rest,quoteInk(list[i]),k);c.drawRoundRect(x,y,x+w,y+h,h/2f,h/2f,p);x+=w+gap}}
        private fun mix(c1:Int,c2:Int,t:Float):Int{
            val u=t.coerceIn(0f,1f)
            val r=(((c1 shr 16) and 255)*(1-u)+((c2 shr 16) and 255)*u).toInt()
            val g=(((c1 shr 8) and 255)*(1-u)+((c2 shr 8) and 255)*u).toInt()
            val b=((c1 and 255)*(1-u)+(c2 and 255)*u).toInt()
            return android.graphics.Color.rgb(r,g,b)
        }
    }

    /** A quote card that keeps its own layout whatever height it is shown at: the visible height (its tag, in px) is
     *  a clip, so a shorter view hides its bottom (the buttons slide out of sight under the edge) instead of squeezing
     *  them, and a taller one shows more of the card's own background. */
    private fun card(i:Int):View=a.quoteCardNative(book,list[i],onOpen?.let{f->{f(list[i])}}).apply{
        val r=a.d(24).toFloat()
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){val h=((view.tag as? Int)?:view.height).coerceAtMost(view.height);o.setRoundRect(0,0,view.width,maxOf(h,1),r)}}
        // the face and its shade end at the shown height (the words are kept above it by [fit]); a clip to the outline
        // would cut the shade off
        val v=this;(background as? QuoteSurface)?.visible={v.tag as? Int}}.also{quoteOf[it]=i}

    /**
     * The fan (approved «карточка уходит под веер»). The sheets under the card are real quote cards — the next quotes —
     * fanned out: the first turned a little one way, the second the other, both peeking out. Dragging the card moves
     * every sheet one place forward toward its new place. Thrown away, the card swings aside, slips under the deck and
     * becomes its last sheet, while the sheet in front straightens up into the card: nothing appears or disappears.
     */
    private inner class Deck:FrameLayout(a){
        // the sheets lie straight under the card (after the reference): each a little narrower and peeking out a little
        // lower than the one above it, as the pile in the book card
        private val fanY=floatArrayOf(a.d(9).toFloat(),a.d(18).toFloat());private val fanIn=floatArrayOf(a.d(12).toFloat(),a.d(24).toFloat())
        private val depth=minOf(list.size,3)
        /** cards[0] is the card on top; cards[1..] are the sheets of the fan. */
        private val cards=MutableList(depth){k->card(Math.floorMod(index+k,list.size))}
        private val front get()=cards[0]
        private var heldH=-1
        /** While the deck changes height after a throw: the cards keep the layout they were given for it and only how
         *  much of them shows moves, so a frame costs no text measuring. */
        private var resizing=false
        /** How much of its words the sheet right under the card shows (only while it rises into the card). */
        private var reveal=0f
        private var downX=0f;private var downY=0f;private var dragging=false;private var velocity:VelocityTracker?=null
        private var busy=false
        private var lift=0f
        init{clipChildren=false;clipToPadding=false;cards.asReversed().forEach{addView(it)};pose(0f)}
        override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws)
            // every card shows the deck's height
            if(resizing){for(i in 0 until childCount)getChildAt(i).tag=heldH;setMeasuredDimension(w,heldH+if(cards.size>1)a.d(18) else 0);return}
            val exactW=MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY);val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
            // every card is laid out at its own natural height (never squeezed); only how much of it shows changes
            front.measure(exactW,free);val h=if(heldH>=0)heldH else front.measuredHeight
            for(i in 0 until childCount){val c=getChildAt(i);if(c!==front)c.measure(exactW,free)
                // sheets show the card's height, so their edges peek out evenly whatever their own text
                c.tag=h;if(c.measuredHeight<h)c.measure(exactW,MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
            front.tag=h
            if(front.measuredHeight<h)front.measure(exactW,MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
            setMeasuredDimension(w,h+if(cards.size>1)a.d(18) else 0)}
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){for(i in 0 until childCount){val c=getChildAt(i);c.layout(0,0,r-l,c.measuredHeight);fit(c);c.invalidateOutline()};pose(lift)}
        /** The footer (the rule and the row with the buttons) always sits at the bottom edge of the shown height, whole;
         *  when the card shows less than its full height, the quote's text slides under the rule instead. */
        private fun fit(v:View){v.invalidate();val g=v as? ViewGroup?:return;if(g.childCount<3)return
            val shown=(g.tag as? Int)?:g.height;val foot=g.getChildAt(g.childCount-1);val rule=g.getChildAt(g.childCount-2)
            val shift=(shown-(foot.bottom+g.paddingBottom)).toFloat();foot.translationY=shift;rule.translationY=shift
            val limit=rule.top+shift.toInt()-a.d(6)
            for(i in 0 until g.childCount-2){val c=g.getChildAt(i);val room=limit-c.top
                c.clipBounds=if(room>=c.height)null else android.graphics.Rect(0,0,c.width,maxOf(0,room))}}
        /** Place [v] at slot [slot] (−1 = the card's own place, 0/1 = the fan), moved [p] of the way to the slot before it. */
        private fun place(v:View,slot:Int,p:Float){
            fun y(s:Int):Float{return if(s<0)0f else fanY[s]}
            fun n(s:Int):Float{return if(s<0)0f else fanIn[s]}
            fun e(s:Int):Float{return a.d(if(s<0)(if(a.light)8 else 10) else if(s==0)(if(a.light)4 else 6) else 3).toFloat()}
            val to=slot-1;v.pivotX=v.width/2f;v.pivotY=((v.tag as? Int)?:v.height)/2f
            v.rotation=0f;v.translationX=0f;v.translationY=y(slot)+(y(to)-y(slot))*p
            v.elevation=e(slot)+(e(to)-e(slot))*p.coerceIn(0f,1f);v.translationZ=-a.d(1)*(slot+1-p)
            // lighter and wider as it rises toward the top
            (v.background as? QuoteSurface)?.let{it.depth=slot+1-p;it.narrow=n(slot)+(n(to)-n(slot))*p}}
        /** [p] (0..1, may overshoot a little) moves every sheet one place forward. The sheets keep the card's height and
         *  stay blank, so nothing shows from under the card or below the deck while it is dragged. */
        /** The pile casts one shade (see [QuoteSurface]): the lowest card for the whole pile, each card above only onto
         *  the card right under it. The order is the cards' height above the page (a thrown card is under the pile
         *  before it is moved to the end of the list). */
        private fun shades(){val order=cards.sortedByDescending{it.z};val top=order.first()
            fun s(v:View)=v.background as? QuoteSurface
            if(order.size<2){s(top)?.let{it.pile=null;it.under=null;it.own=1f};top.invalidate();return}
            // the card on top, moved or turned by the finger, leaves the pile: its own shade comes in as it goes
            val lifted=(abs(top.translationX)/a.d(48)+abs(top.rotation)/6f).coerceIn(0f,1f)
            val rad=a.d(24).toFloat();val m=Matrix();val inv=Matrix()
            order.forEachIndexed{k,v->val sv=s(v)?:return@forEachIndexed
                sv.own=if(k==0)lifted else 0f
                if(k==order.lastIndex){
                    // every card's face in the pile, in this card's coordinates, each at its place in the pile (the top
                    // card at the top's place, giving it up to the card under it as the finger lifts it off)
                    fun face(u:View,y:Float,n:Float)=RectF(n,y-v.translationY,u.width-n,y-v.translationY+((u.tag as? Int)?:u.height))
                    val faces=order.drop(1).map{u->face(u,u.translationY,s(u)?.narrow?:0f)}.toMutableList()
                    val tf=face(top,0f,0f);val uf=faces[0]
                    faces.add(0,RectF(tf.left+(uf.left-tf.left)*lifted,tf.top+(uf.top-tf.top)*lifted,tf.right+(uf.right-tf.right)*lifted,tf.bottom+(uf.bottom-tf.bottom)*lifted))
                    sv.pile=faces;sv.under=null}
                else{val nx=order[k+1];val nn=s(nx)?.narrow?:0f;val h=((nx.tag as? Int)?:nx.height).toFloat()
                    // the outline of the card under it, brought into this card's own coordinates (as moved and turned)
                    m.set(nx.matrix);v.matrix.invert(inv);m.postConcat(inv)
                    sv.pile=null;sv.under=Path().apply{addRoundRect(nn,0f,nx.width-nn,h,rad,rad,Path.Direction.CW);transform(m)}}
                v.invalidate()}}
        private fun pose(p:Float){lift=p;val h=(front.tag as? Int)?:front.height;for(k in 1 until cards.size){val v=cards[k]
            if(v.tag!=h){v.tag=h;fit(v);v.invalidateOutline()}
            content(v,if(k==1)reveal else 0f)
            place(v,k-1,p)}
            shades()}
        /** The sheets under the card are blank: only the card on top shows its words and buttons. */
        private fun content(v:View,al:Float){(v as? ViewGroup)?.let{g->for(i in 0 until g.childCount)g.getChildAt(i).alpha=al}}
        /** Puts quote [want] onto the card at place [k] if it holds another one (only ever done to a blank sheet). */
        private fun hold(k:Int,want:Int){val v=cards[k];if(quoteOf[v]==want)return
            val fresh=card(want);fresh.tag=v.tag;val at=indexOfChild(v);removeView(v);quoteOf.remove(v);addView(fresh,at);cards[k]=fresh;content(fresh,if(k==0)1f else 0f)}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
            if(list.size<2||busy)return false
            when(e.actionMasked){MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;dragging=false;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)}}
                MotionEvent.ACTION_MOVE->{velocity?.addMovement(e);val dx=e.x-downX;val dy=e.y-downY;if(abs(dx)>a.d(10)&&abs(dx)>abs(dy)*1.2f){dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true}}}
            return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            if(!dragging){if(e.actionMasked==MotionEvent.ACTION_DOWN&&list.size>1&&!busy){downX=e.x;downY=e.y;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)};return true};return super.onTouchEvent(e)}
            velocity?.addMovement(e);val dx=e.x-downX
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->{
                    // the sheet under the card is the quote the throw will bring: the next one to the left, the one
                    // before to the right; it is swapped while still blank, as the direction shows
                    val want=Math.floorMod(index+(if(dx<0)1 else -1),list.size)
                    if(cards.size>1&&quoteOf[cards[1]]!=want&&abs(dx)<width*.12f){hold(1,want);measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));layout(left,top,right,bottom)}
                    front.translationX=dx;front.translationY=abs(dx)*.05f;front.rotation=dx/width*10f;pose((abs(dx)/(width*.6f)).coerceIn(0f,1f)*.55f)}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f;dragging=false;parent?.requestDisallowInterceptTouchEvent(false)
                    val go=e.actionMasked==MotionEvent.ACTION_UP&&(abs(dx)>width*.3f||abs(vx)>a.d(700)&&sign(vx)==sign(dx))
                    if(go)fling(if(dx<0)-1 else 1) else settleBack()}
            };return true}
        private fun settleBack(){front.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(380).setInterpolator(PathInterpolator(.2f,1.25f,.4f,1f)).start()
            val from=lift;ValueAnimator.ofFloat(from,0f).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pose(it.animatedValue as Float)};start()}}
        private fun fling(dir:Int){
            busy=true;val old=front;val oldH=(old.tag as? Int)?:old.height
            haptic(a,Haptic.SELECT)
            index=Math.floorMod(index+(if(dir<0)1 else -1),list.size);a.quoteDeckIndex[book.str("id")]=index;updateCounter()
            val glide=PathInterpolator(.2f,0f,0f,1f);val swing=PathInterpolator(.45f,0f,.25f,1f);val settle=android.view.animation.OvershootInterpolator(1.1f)
            val sx=old.translationX;val sy=old.translationY;val sr=old.rotation;val from=lift
            val outX=width*.82f*dir;val outY=a.d(28).toFloat();val outR=dir*16f
            val back=cards.size-2 // the last place in the fan
            // the sheet that rises is the quote that comes now (the index is already the new one): the next one, or the
            // one before when thrown to the right
            if(cards.size>1&&quoteOf[cards[1]]!=index){hold(1,index);measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));layout(left,top,right,bottom)}
            val next=if(cards.size>1)cards[1] else null
            fun finish(){
                // the deck turns round: the risen sheet is the card now, the thrown card the last sheet
                cards.removeAt(0);cards.add(old);heldH=-1;resizing=false;reveal=0f
                // the card holds the current quote and every sheet the one that really comes at its place
                for(k in 0 until cards.size)hold(k,Math.floorMod(index+k,list.size))
                measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));layout(left,top,right,bottom)
                pose(0f);content(front,1f);requestLayout();busy=false}
            if(reduced()||next==null){old.translationX=0f;old.translationY=0f;old.rotation=0f;finish();return}
            // as the card flies off it goes blank and the deck, still blank under it, takes the coming quote's height;
            // the risen sheet shows its words only once the height has settled. Every card is laid out once, tall
            // enough for both heights; from here on a frame only moves the edge.
            val exactW=MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY);val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
            val newH=run{next.measure(exactW,free);next.measuredHeight};val tall=maxOf(oldH,newH)
            for(c in cards){c.measure(exactW,free);if(c.measuredHeight<tall)c.measure(exactW,MeasureSpec.makeMeasureSpec(tall,MeasureSpec.EXACTLY));c.layout(0,0,width,c.measuredHeight);c.tag=oldH;fit(c);c.invalidateOutline()}
            heldH=oldH;resizing=true;pose(lift)
            ValueAnimator.ofFloat(0f,1f).apply{duration=230;interpolator=glide
                addUpdateListener{val t=it.animatedValue as Float;old.translationX=sx+(outX-sx)*t;old.translationY=sy+(outY-sy)*t;old.rotation=sr+(outR-sr)*t
                    content(old,1f-(t/.35f).coerceIn(0f,1f))
                    heldH=(oldH+(newH-oldH)*glide.getInterpolation(((t-.2f)/.8f).coerceIn(0f,1f))).toInt();requestLayout()
                    pose(from+(.75f-from)*t)}
                addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){
                    // under the deck now: it takes the last place of the fan, the sheets in front step forward and the
                    // risen one shows its words
                    heldH=newH;requestLayout();old.elevation=a.d(3).toFloat();old.translationZ=-a.d(4).toFloat()
                    val lp=lift
                    ValueAnimator.ofFloat(0f,1f).apply{duration=520;interpolator=swing
                        addUpdateListener{val t=it.animatedValue as Float
                            old.pivotX=old.width/2f;old.pivotY=((old.tag as? Int)?:old.height)/2f
                            old.translationX=outX*(1f-t);old.translationY=outY+(fanY[back]-outY)*t;old.rotation=outR*(1f-t)
                            reveal=(t/.3f).coerceIn(0f,1f);(old.background as? QuoteSurface)?.let{it.depth=(back+1)*t;it.narrow=fanIn[back]*t}
                            pose(lp+(1f-lp)*settle.getInterpolation(t))}
                        addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){finish()}});start()}}});start()}
        }
    }
}
