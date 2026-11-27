package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shelf (approved «Полки: карточки с веером»): a card with the shelf's name in the middle and its first books
 * fanned under it — the middle ones higher, the outer ones turned away. An empty shelf shows a place for a book.
 * A tap opens the shelf (the fanned covers fly into its first places); in edit mode the card folds into its title
 * card with the grip, as before.
 */
internal class ShelfRow(private val a:MainActivity,var key:String,private var title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?,private val rename:((String)->Unit)?,private val remove:(()->Unit)?=null,private val compact:Boolean=false):LinearLayout(a){
    /** The fan: the shelf's first books in its own order (so they fly into its first places when it opens). */
    internal val fan=ShelfFan(a,list.take(if(compact)3 else 4),compact)
    /** The card around the name and the fan; it fades as the shelf folds into its edit card. */
    private val face=android.graphics.drawable.GradientDrawable().apply{setColor(Ui.card(a.light));cornerRadius=a.d(26).toFloat()}
    // ── edit mode: the header becomes a card with a drag handle, the covers fold away ──
    private val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40)}
    private val card=android.graphics.drawable.GradientDrawable().apply{setColor(Ui.card(a.light));cornerRadius=a.d(16).toFloat();alpha=0}
    private val name=a.text(title,Type.TITLE,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val pen=IconView(a,"pen",a.muted).apply{alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    /** Edit mode: removes a shelf of the user's own (the books stay in the library). */
    private val trash=IconView(a,"trash",Ui.danger(a.light)).apply{alpha=0f;visibility=View.INVISIBLE;isClickable=true;contentDescription="Удалить полку";setPadding(a.d(10),a.d(10),a.d(10),a.d(10))}
    internal val handle=Grip()
    /** Delete asks right in the card: the bin grows into a red «Удалить»; a tap anywhere else takes it back. */
    private val pill=a.text("Удалить",Type.SECONDARY,if(a.light)Color.WHITE else Ui.bg(false),true).apply{gravity=Gravity.CENTER;setSingleLine(true);background=a.shape(Ui.danger(a.light),16);visibility=View.GONE;isClickable=true;contentDescription="Удалить полку"}
    private var ask=0f;private var askAnim:android.animation.ValueAnimator?=null
    private val cardColor=Ui.card(a.light)
    private val outside:(MotionEvent)->Unit={e->val r=Rect();if(!pill.getGlobalVisibleRect(r)||!r.contains(e.rawX.toInt(),e.rawY.toInt()))cancelAsk()}
    private val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    private val stageFull=fan.full
    /** The grip (and on a shelf of your own the bin), at the end of the title row. */
    private val end=FrameLayout(a)
    private var progress=0f
    private var anim:android.animation.ValueAnimator?=null
    private var field:EditText?=null
    internal var editing=false;private set
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        head.background=card;head.isClickable=true;head.contentDescription="$title, ${MainActivity.countBooks(list.size)}"
        head.setOnClickListener{if(editing){if(rename!=null)startRename()}else open()}
        // (the quick menu's long press is caught for the whole shelf in dispatchTouchEvent below)
        head.addView(name,LayoutParams(-2,-2))
        head.addView(pen,LayoutParams(a.d(16),a.d(16)).apply{leftMargin=a.d(6)})
        head.addView(View(a),LayoutParams(0,1,1f))
        name.textSize=if(compact)17f else 20f;name.typeface=PolkaFonts.serif(a,true)
        if(compact)head.minimumHeight=0
        end.addView(handle,FrameLayout.LayoutParams(a.d(if(compact)30 else 44),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END))
        // the bin sits right before the grip, at the same place on every card
        remove?.let{f->trash.setOnClickListener{if(editing){commitRename();askDelete()}};a.press(trash);end.addView(trash,FrameLayout.LayoutParams(a.d(40),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(38)})
            pill.setOnClickListener{if(ask<.5f)return@setOnClickListener;a.touchWatchers.remove(outside);pill.isClickable=false
                pill.animate().scaleX(.94f).scaleY(.94f).setDuration(Motion.FAST).withEndAction{pill.animate().scaleX(1f).scaleY(1f).setDuration(Motion.FAST).start()}.start();f()}
            end.addView(pill,FrameLayout.LayoutParams(a.d(40),a.d(32),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(40)})}
        // a status (a small shelf of the block above) is only moved: its grip shows in edit mode, no bin, no name field
        head.addView(end,LayoutParams(-2,a.d(44)))
        addView(head,LayoutParams(-1,-2))
        stage.addView(fan,FrameLayout.LayoutParams(-1,fan.full))
        // a tap anywhere on the card opens the shelf itself (not the book under the finger)
        stage.isClickable=true;stage.contentDescription="Открыть полку $title";stage.setOnClickListener{if(!editing)open()}
        addView(stage,LayoutParams(-1,stageFull).apply{topMargin=a.d(if(compact)6 else 12)})
        background=face
        // the whole card opens the shelf, its margins around the name and the fan too
        isClickable=true;setOnClickListener{if(!editing)open()}
        apply(0f)
    }
    /** The fanned covers' shadows reach past the card: a fading card drawn through a layer would cut them off. */
    override fun hasOverlappingRendering()=false
    /** The name stands in the middle of the card; in edit mode it slides to the left of its title card. */
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);centerName()}
    private fun centerName(){if(head.width==0)return;name.translationX=((head.width-name.width)/2f-name.left)*(1-progress)}
    /** A long press anywhere on the shelf — its title row, the covers, the shelf itself — opens its quick menu. The
     *  covers and the shelf's own scroller take every touch first, so a long-click listener on the shelf never fired;
     *  here the press is watched before they get it. Moving the finger (scrolling) or lifting it early lets it go;
     *  when the menu opens, whatever was under the finger gets a cancel, so no cover opens and only one vibration. */
    private var pressX=0f;private var pressY=0f;private var pressFired=false
    private val slop=android.view.ViewConfiguration.get(a).scaledTouchSlop
    private val pressed=Runnable{val f=actions?:return@Runnable;if(editing)return@Runnable;pressFired=true
        haptic(a,Haptic.LONG)
        cancelUnder();f()}
    private fun cancelUnder(){val now=android.os.SystemClock.uptimeMillis();val c=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,pressX,pressY,0);super.dispatchTouchEvent(c);c.recycle()}
    override fun dispatchTouchEvent(e:MotionEvent):Boolean{
        if(actions==null||editing)return super.dispatchTouchEvent(e)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{pressFired=false;pressX=e.x;pressY=e.y;removeCallbacks(pressed);postDelayed(pressed,android.view.ViewConfiguration.getLongPressTimeout().toLong())}
            MotionEvent.ACTION_MOVE->if(!pressFired&&(kotlin.math.abs(e.x-pressX)>slop||kotlin.math.abs(e.y-pressY)>slop))removeCallbacks(pressed)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{removeCallbacks(pressed);if(pressFired){pressFired=false;return true}}
            MotionEvent.ACTION_POINTER_DOWN->removeCallbacks(pressed)}
        if(pressFired)return true
        val taken=super.dispatchTouchEvent(e)
        // nothing here took the touch: the rest of it goes to the page (a scroll) and never comes back here, so the
        // press is let go now instead of firing later under a scrolling finger
        if(!taken&&e.actionMasked==MotionEvent.ACTION_DOWN)removeCallbacks(pressed)
        return taken}
    /** Morph between the shelf and its edit card; [delay] staggers the rows. */
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){editing=on;if(!on){commitRename();cancelAsk()}
        anim?.cancel();val to=if(on)1f else 0f
        if(!animate||a.prefs.str("motion")=="reduced"){apply(to);return}
        val from=progress
        val sp=Spring.of(.1,460.0);anim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=sp.duration;startDelay=delay;interpolator=sp.interpolator;addUpdateListener{apply(it.animatedValue as Float)};start()}}
    private fun apply(e:Float){progress=e
        val lp=stage.layoutParams as MarginLayoutParams;lp.height=(stageFull*(1-e)).toInt();lp.topMargin=(a.d(if(compact)6 else 12)*(1-e)).toInt();stage.layoutParams=lp
        // while folding, the covers and the shelf are cut at the shrinking bottom instead of spilling over the next shelf
        stage.clipBounds=if(e>0f)android.graphics.Rect(-a.d(40),-a.d(80),a.d(2000),lp.height) else null
        stage.alpha=(1-e*1.6f).coerceIn(0f,1f);stage.scaleY=1f-.08f*e;stage.pivotY=0f
        card.alpha=(e*255).toInt();val pad=(a.d(if(compact)12 else 14)*e).toInt();head.setPadding(pad,(a.d(6)*e).toInt(),(a.d(4)*e).toInt(),(a.d(6)*e).toInt())
        face.alpha=((1-e)*255).toInt();setPadding(0,(a.d(if(compact)17 else 18)*(1-e)).toInt(),0,(a.d(if(compact)10 else 12)*(1-e)).toInt());centerName()
        handle.alpha=((e-.35f)/.65f).coerceIn(0f,1f);handle.translationX=a.d(14)*(1-e);handle.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE
        // at rest a status's title row is only its name (the grip's row height would push the covers down)
        if(compact){end.visibility=if(e>0f)View.VISIBLE else View.GONE;val el=end.layoutParams;if(el!=null){val eh=(a.d(44)*e).toInt();if(el.height!=eh){el.height=eh;end.layoutParams=el}}}
        pen.alpha=if(rename==null||field!=null)0f else ((e-.4f)/.6f).coerceIn(0f,1f)*.8f
        if(remove!=null){trash.alpha=((e-.4f)/.6f).coerceIn(0f,1f)*(1-ask);trash.translationX=a.d(14)*(1-e);trash.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE;trash.scaleX=1-.3f*ask;trash.scaleY=1-.3f*ask}
        head.elevation=a.d(if(a.light)3 else 4)*e
        if(!compact)(layoutParams as? MarginLayoutParams)?.let{m->val idx=(parent as? ViewGroup)?.indexOfChild(this)?:0;val want=if(idx<=0)0 else (a.d(26)+(a.d(10)-a.d(26))*e).toInt();if(m.topMargin!=want){m.topMargin=want;layoutParams=m}}}
    private fun askDelete(){if(ask>0f||!editing)return;pill.visibility=View.VISIBLE;pill.isClickable=true;a.touchWatchers.add(outside);a.root.post{if(pill.visibility==View.VISIBLE&&!a.touchWatchers.contains(outside))a.touchWatchers.add(outside)};runAsk(1f)}
    fun cancelAsk(){a.touchWatchers.remove(outside);if(ask<=0f&&askAnim==null)return;runAsk(0f)}
    private fun runAsk(to:Float){askAnim?.cancel();val from=ask;val reduced=a.prefs.str("motion")=="reduced"
        fun set(k:Float){ask=k;val lp=pill.layoutParams;lp.width=(a.d(40)+(a.d(96)-a.d(40))*k).toInt();pill.layoutParams=lp;pill.alpha=(k*1.5f).coerceIn(0f,1f)
            card.setColor(androidx.core.graphics.ColorUtils.blendARGB(cardColor,Ui.danger(a.light),.14f*k));card.alpha=(progress*255).toInt();apply(progress)
            // the first frame of the opening is k=0: only a finished closing hides the pill
            pill.visibility=if(k<=0f&&to<=0f)View.GONE else View.VISIBLE}
        if(reduced){set(to);askAnim=null;return}
        askAnim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=if(to>from)280 else 220;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{set(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(askAnim===x)askAnim=null}});start()}}
    /** Margins follow the new order after a drag. */
    fun settleMargin(){apply(progress)}
    // ── inline rename ──
    private fun startRename(){if(field!=null||!isAttachedToWindow||!editing)return
        val f=a.edit(title,"Название полки").apply{setSingleLine(true);setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,name.textSize);typeface=name.typeface;setTextColor(a.fg);background=null;setPadding(0,0,0,0)
            includeFontPadding=name.includeFontPadding;minHeight=0;minimumHeight=0;gravity=name.gravity;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;setSelection(text.length)
            setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commitRename();true}else false}
            // never change the views from inside a focus change (the focus is moving through this very field):
            // the commit runs right after, on the next loop
            setOnFocusChangeListener{v,has->if(!has)v.post{if(field===v)commitRename()}}}
        // the field takes the name's exact place and size: the title just gets a cursor, nothing moves or blinks
        // the pencil fades out first, still in its place; only then the title turns into the field (which is a little
        // wider and would push it along), so the pencil never moves
        field=f
        val swap={if(field===f&&isAttachedToWindow&&editing){pen.alpha=0f
            val i=head.indexOfChild(name);head.addView(f,i,LayoutParams(-2,-2));name.visibility=View.GONE
            followKeyboard()
            f.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f,0)}}
        if(a.prefs.str("motion")=="reduced"||pen.alpha<=0f)swap() else pen.animate().alpha(0f).setDuration(Motion.FAST).withEndAction{swap()}.start()}
    /** The page rides on the keyboard: frame by frame, as far as needed for this card to stay above it, by moving the
     *  page as a picture (no layout); it comes back down the same way as the keyboard closes. */
    private fun followKeyboard()=a.rideKeyboard(head)
    private fun commitRename(){val f=field?:return;field=null;val next=f.text.toString().trim()
        // normally the keyboard's closing slide brings the page down; if it never came up (or no slide is reported), settle here
        a.imeFollower?.let{fl->a.root.postDelayed({if(a.imeFollower===fl){val ime=androidx.core.view.ViewCompat.getRootWindowInsets(a.root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0;if(ime==0){fl(0);a.imeFollower=null}}},900)}
        f.onFocusChangeListener=null;f.setOnEditorActionListener(null)
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)
        androidx.core.view.WindowCompat.getInsetsController(a.window,f).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        if(f.hasFocus()){a.root.isFocusableInTouchMode=true;a.root.requestFocus()}
        (f.parent as? ViewGroup)?.removeView(f);name.visibility=View.VISIBLE
        pen.animate().cancel();if(editing&&rename!=null)pen.animate().alpha(.8f).setDuration(Motion.FAST).start()
        // the shelves reload after a rename: let this tap finish first
        if(next.isNotBlank()&&next!=title){name.text=next;title=next;if(key.startsWith("s:"))key="s:$next";head.contentDescription="$next, ${MainActivity.countBooks(list.size)}";centerName();settleBreath();val r=rename;a.root.post{r?.invoke(next)}}}
    /** Saved: the card breathes once. */
    private fun settleBreath(){if(a.prefs.str("motion")=="reduced")return;head.animate().scaleX(1.012f).scaleY(1.012f).setDuration(Motion.FAST).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).withEndAction{head.animate().scaleX(1f).scaleY(1f).setDuration(Motion.STANDARD).start()}.start()}

    /** Six-dot grip; dragging it moves the shelf. */
    internal inner class Grip:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init{contentDescription="Переместить полку";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
        override fun onDraw(c:Canvas){p.color=a.muted;val r=a.d(1).toFloat()*1.6f;val gx=a.d(6).toFloat();val gy=a.d(6).toFloat()
            for(i in 0..1)for(j in -1..1)c.drawCircle(width/2f+(i-.5f)*gx,height/2f+j*gy,r,p)}
    }

    /** Where this card is (in the window's main view) and a picture of it without its covers, for the shelf to
     *  open out of it. */
    internal fun trip():ShelfMorph.Trip{val o=IntArray(2);a.root.getLocationOnScreen(o)
        fun local(r:RectF)=RectF(r).apply{offset(-o[0].toFloat(),-o[1].toFloat())}
        val snap=if(width<=0||height<=0)null else try{fan.alpha=0f;Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{draw(Canvas(it))}}catch(e:Throwable){null}finally{fan.alpha=1f}
        return ShelfMorph.Trip(key,local(ShelfMorph.box(this)),snap,fan.books.indices.mapNotNull{i->fan.spot(i)?.let{(r,t)->ShelfMorph.Spot(fan.books[i],local(r),t)}})}
    override fun onDetachedFromWindow(){a.touchWatchers.remove(outside);removeCallbacks(pressed);super.onDetachedFromWindow()}
}

/** A shelf's first books fanned (approved «веер»): up to four covers overlapping, the middle ones higher and on top,
 *  the outer ones turned out; an empty shelf shows a dashed place with «+». */
internal class ShelfFan(private val a:MainActivity,val books:List<JSONObject>,private val small:Boolean=false):FrameLayout(a){
    private val cw=a.d(if(small)52 else 70);private val ch=a.d(if(small)78 else 105)
    /** The fan's full height (the covers and the lift of the outer ones). */
    val full=ch+a.d(if(small)24 else 34)
    /** The covers, left to right. */
    val covers=books.map{b->CoverView(a,b,a.cover(b))}
    private val turns=mapOf(1 to floatArrayOf(0f),2 to floatArrayOf(-5f,5f),3 to floatArrayOf(-8f,0f,8f),4 to floatArrayOf(-10f,-3.5f,3.5f,10f))
    init{clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        val n=covers.size
        if(n==0)addView(Place(),LayoutParams(cw,ch,Gravity.CENTER))
        // the outer covers first, so the middle ones lie on top
        covers.indices.sortedByDescending{kotlin.math.abs(it-(n-1)/2f)}.forEach{i->val c=covers[i];c.rotation=turns.getValue(n)[i];addView(c,LayoutParams(cw,ch))}}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val n=covers.size
        if(n==0){super.onLayout(changed,l,t,r,b);return}
        val step=cw*.62f;val total=step*(n-1)+cw;val x0=(width-total)/2f
        covers.forEachIndexed{i,c->val mid=kotlin.math.abs(i-(n-1)/2f);val dy=a.d(if(small)7 else 10)+mid*mid*a.d(if(small)4 else 5)
            val x=(x0+i*step).toInt();val y=dy.toInt();c.layout(x,y,x+cw,y+ch)}}
    /** Where cover [i] is on the screen (its unturned box) and how it is turned. */
    fun spot(i:Int):Pair<RectF,Float>?{val c=covers.getOrNull(i)?:return null;if(!c.isAttachedToWindow||c.width==0)return null
        val at=IntArray(2);getLocationOnScreen(at);return RectF((at[0]+c.left).toFloat(),(at[1]+c.top).toFloat(),(at[0]+c.right).toFloat(),(at[1]+c.bottom).toFloat()) to c.rotation}
    /** An empty shelf's place for a book. */
    private inner class Place:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1)*1.5f;color=if(a.light)0x33000000 else 0x29ffffff
            pathEffect=DashPathEffect(floatArrayOf(a.d(5).toFloat(),a.d(4).toFloat()),0f);strokeCap=Paint.Cap.ROUND}
        private val plus=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1)*1.75f;color=a.muted;strokeCap=Paint.Cap.ROUND}
        override fun onDraw(c:Canvas){val h=p.strokeWidth;val rr=a.d(if(small)5 else 6).toFloat();c.drawRoundRect(h,h,width-h,height-h,rr,rr,p)
            val cx=width/2f;val cy=height/2f;val k=a.d(if(small)7 else 9).toFloat();c.drawLine(cx-k,cy,cx+k,cy,plus);c.drawLine(cx,cy-k,cx,cy+k,plus)}
    }
}

/**
 * A shelf opening out of its card, the same container transform as the tier list and the year in books: the card's
 * rounded bounds grow to the whole screen, the card's picture fades out as the shelf's page (scaled with the bounds)
 * fades in, and the fanned covers fly out of the fan, straighten and land in the shelf's first places — following
 * the page as it grows, so they arrive exactly where it puts them. Back runs it backwards into the card; a swipe back
 * holds the page under the finger (it shrinks a little, follows it, its corners round) and, let go, folds it into the
 * card from there. Copies of the covers fly on a layer over everything, stacked as in the fan (the middle ones on top)
 * from the first frame to the last; the real ones show where they land.
 */
internal class ShelfMorph(private val a:MainActivity,val trip:Trip,private val toList:()->Unit):SwipeClosable{
    class Spot(val book:JSONObject,val r:RectF,val turn:Float)
    /** Where the shelf was opened from, in the window's main view: its card, a picture of it without the covers, its fan. */
    class Trip(val key:String,val card:RectF,val snap:Bitmap?,val spots:List<Spot>)
    private class Leg(val spot:Spot,val to:RectF?,val real:View?,val copy:View)
    companion object{
        /** A shelf is opening or closing: no second tap, back or swipe meanwhile. */
        var busy=false;private set
        fun box(v:View):RectF{val at=IntArray(2);v.getLocationOnScreen(at);return RectF(at[0].toFloat(),at[1].toFloat(),(at[0]+v.width).toFloat(),(at[1]+v.height).toFloat())}
        private const val OPEN=520L;private const val CLOSE=440L
        private val close=android.view.animation.PathInterpolator(.3f,0f,.1f,1f)
    }
    private val W get()=a.root.width.toFloat()
    private val H get()=a.root.height.toFloat()
    private val cardColor=Ui.card(a.light)
    private var page:View?=null
    private val bounds=RectF();private val home=RectF();private var radius=0f
    private var under:ImageView?=null
    private var snapView:ImageView?=null;private var lay:FrameLayout?=null
    private var legs=emptyList<Leg>()
    private var anim:android.animation.ValueAnimator?=null
    private var holding=false
    /** The card's empty place on the list's picture (its name and covers are on the way), and the page's ground
     *  under its content: plain rounded rectangles, so a frame redraws only them, never the page's content. */
    private val hole=Plate(false);private val ground=Plate(true)
    private var fill=cardColor
    private inner class Plate(private val moving:Boolean):View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onDraw(c:Canvas){if(moving){if(bounds.isEmpty)return;p.color=fill;c.drawRoundRect(bounds,radius,radius,p)}
            else{p.color=cardColor;val r=a.d(26).toFloat();c.drawRoundRect(trip.card,r,r,p)}}}
    private val outline=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
        if(bounds.isEmpty){o.setRect(0,0,v.width,v.height);return}
        val s=bounds.width()/W;o.setRoundRect(0,0,v.width,Math.round(bounds.height()/s),radius/s)}}

    /** The list's picture under the page; [empty]: its card without its name and covers (they are on the way). */
    private fun underlay(empty:Boolean){val b=a.backdrop?:return
        if(under==null){under=ImageView(a).apply{setImageBitmap(b);scaleType=ImageView.ScaleType.MATRIX;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            .also{a.root.addView(it,0,FrameLayout.LayoutParams(b.width,b.height))}
            a.root.addView(hole,1,FrameLayout.LayoutParams(-1,-1))}
        hole.visibility=if(empty)View.VISIBLE else View.INVISIBLE}
    private fun attach(empty:Boolean){val p=a.scroll;page=p
        underlay(empty)
        // the page is drawn once into a layer and then only moved, scaled, clipped and faded (no redraw per frame)
        if(ground.parent==null)a.root.addView(ground,a.root.indexOfChild(p),FrameLayout.LayoutParams(-1,-1))
        p.outlineProvider=outline;p.clipToOutline=true;p.background=null;p.pivotX=0f;p.pivotY=0f;p.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        if(snapView==null)trip.snap?.let{s->snapView=ImageView(a).apply{setImageBitmap(s);scaleType=ImageView.ScaleType.FIT_XY;pivotX=0f;pivotY=0f;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
            .also{a.root.addView(it,a.root.indexOfChild(p)+1,FrameLayout.LayoutParams(s.width,s.height))}}
        if(lay==null)lay=FrameLayout(a).apply{clipChildren=false;isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;translationZ=a.d(40).toFloat()}
            .also{a.root.addView(it,FrameLayout.LayoutParams(-1,-1))}}
    /** Everything back as it was: the page whole and still, the helpers gone. */
    private fun detach(){val p=page
        if(p!=null){p.scaleX=1f;p.scaleY=1f;p.translationX=0f;p.translationY=0f;p.alpha=1f;p.clipToOutline=false;p.outlineProvider=ViewOutlineProvider.BACKGROUND;p.setLayerType(View.LAYER_TYPE_NONE,null)}
        legs.forEach{it.real?.alpha=1f};legs=emptyList()
        listOfNotNull(under,hole,ground,snapView,lay).forEach{a.root.removeView(it)};under=null;snapView=null;lay=null
        bounds.setEmpty();page=null}

    /** The covers' flights: from the fan to the page's cells ([cells]: by book, in the page's own, unscaled
     *  coordinates); in the fan's order, the outer ones first so the middle ones lie on top. */
    private fun legs(cells:Map<String,Pair<View,RectF>>):List<Leg>{val l=lay?:return emptyList();val n=trip.spots.size
        return trip.spots.indices.sortedByDescending{kotlin.math.abs(it-(n-1)/2f)}.map{i->val s=trip.spots[i];val c=cells[s.book.str("id")]
            val size=c?.second?:s.r
            // landing in a cell, it brings the cell's check (it shows on the way, as the cover straightens)
            val copy=CoverView(a,s.book,a.cover(s.book)).apply{pivotX=size.width()/2f;pivotY=size.height()/2f;readMark=c!=null&&(c.first as? CoverView)?.readMark==true;markAlpha=0f}
            l.addView(copy,FrameLayout.LayoutParams(size.width().toInt(),size.height().toInt()))
            c?.first?.alpha=0f;Leg(s,c?.second,c?.first,copy)}}
    /** The covers of the page's cells, where they are on the page itself (as if it were not scaled), those on screen
     *  ([whole]: only those wholly on it). */
    private fun cells(whole:Boolean=false):Map<String,Pair<View,RectF>>{val p=page?:return emptyMap();val out=HashMap<String,Pair<View,RectF>>()
        val o=IntArray(2);a.root.getLocationOnScreen(o);val s=if(bounds.isEmpty)1f else bounds.width()/W;val l=if(bounds.isEmpty)0f else bounds.left;val t=if(bounds.isEmpty)0f else bounds.top
        val ids=trip.spots.map{it.book.str("id")}.toSet()
        fun walk(v:View){val tag=v.tag;if(tag is String&&tag.startsWith("grid:")&&v is ViewGroup){val id=tag.removePrefix("grid:");val cover=v.getChildAt(0)
                if(id in ids&&cover!=null){val b=box(cover);b.offset(-o[0].toFloat(),-o[1].toFloat())
                    if(if(whole)b.top>=0&&b.bottom<=H else b.bottom>0&&b.top<H)out[id]=cover to RectF((b.left-l)/s,(b.top-t)/s,(b.right-l)/s,(b.bottom-t)/s)}
                return}
            if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
        walk(p);return out}

    /** One step: [k] 0 is the card, 1 the page at [home]; [round] is how round its corners are there. */
    private fun pose(k:Float,round:Float=0f){val p=page?:return;val c=trip.card
        bounds.set(c.left+(home.left-c.left)*k,c.top+(home.top-c.top)*k,c.right+(home.right-c.right)*k,c.bottom+(home.bottom-c.bottom)*k)
        radius=a.d(26)*(1-k)+round*k
        val s=bounds.width()/W;p.scaleX=s;p.scaleY=s;p.translationX=bounds.left;p.translationY=bounds.top;p.invalidateOutline()
        fill=androidx.core.graphics.ColorUtils.blendARGB(cardColor,a.bg,k);ground.invalidate()
        p.alpha=((k-.12f)/.42f).coerceIn(0f,1f)
        val face=(1f-k/.32f).coerceIn(0f,1f)
        snapView?.let{v->val ss=bounds.width()/v.layoutParams.width;v.scaleX=ss;v.scaleY=ss;v.translationX=bounds.left;v.translationY=bounds.top;v.alpha=face}
        legs.forEach{l->val v=l.copy;val w=v.layoutParams.width.toFloat();val h=v.layoutParams.height.toFloat();val from=l.spot.r;val to=l.to
            // with no cell to land in (the page scrolled away from its first books) the cover stays in the fan and goes
            // with the card's picture: grown and moved with the bounds, fading as it does
            val r=if(to==null){val g=bounds.width()/c.width();RectF(bounds.left+(from.left-c.left)*g,bounds.top+(from.top-c.top)*g,bounds.left+(from.right-c.left)*g,bounds.top+(from.bottom-c.top)*g)}
                else {val tx=to.left*s+bounds.left;val ty=to.top*s+bounds.top
                RectF(from.left+(tx-from.left)*k,from.top+(ty-from.top)*k,from.right+(tx+to.width()*s-from.right)*k,from.bottom+(ty+to.height()*s-from.bottom)*k)}
            v.alpha=if(to==null)face else 1f
            (v as? CoverView)?.markAlpha=if(to==null)0f else ((k-.4f)/.5f).coerceIn(0f,1f)
            v.scaleX=r.width()/w;v.scaleY=r.height()/h;v.translationX=r.centerX()-w/2f;v.translationY=r.centerY()-h/2f
            v.rotation=if(to==null)l.spot.turn else l.spot.turn*(1-k)}}
    private fun run(from:Float,to:Float,ms:Long,curve:android.animation.TimeInterpolator,step:(Float)->Unit,end:()->Unit){anim?.removeAllListeners();anim?.cancel()
        anim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=ms;interpolator=curve;addUpdateListener{step(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){anim=null;end()}});start()}}
    private fun full(){home.set(0f,0f,W,H)}

    /** Out of the card. The page is already built; the first frame drawn already has everything in its place. */
    fun open(){busy=true;attach(true)
        a.root.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{a.root.viewTreeObserver.removeOnPreDrawListener(this)
            full();legs=legs(cells());pose(0f)
            later{run(0f,1f,OPEN,MotionSpec.enter,{pose(it)}){detach();busy=false}};return false}})}
    /** The motion starts once the frames that build the page and its layer are drawn, so none of its steps is lost
     *  to them (they hold the first pose, which looks exactly like what was there). */
    private fun later(f:()->Unit){val ch=android.view.Choreographer.getInstance();ch.postFrameCallback{ch.postFrameCallback{if(page!=null)f()}}}
    /** Back into the card (the back button or the system back). */
    fun close(){busy=true;attach(true);full();legs=legs(landing());pose(1f)
        later{run(1f,0f,CLOSE,close,{pose(it)}){busy=false;toList()}}}
    /** Where the covers fly back from: their cells, when the page shows all of them whole (as it opened); scrolled
     *  away, none (the fan comes back with the card's picture, not some from the cells and some out of nowhere). */
    private fun landing():Map<String,Pair<View,RectF>>{val c=cells(true);return if(trip.spots.all{it.book.str("id") in c})c else emptyMap()}

    // ── the swipe back ──
    override val swipeView:View get()=a.scroll
    override fun swipeReady()=!busy&&anim==null&&a.backdrop!=null&&!a.shelvesEditing
    override fun swipeMove(dx:Float){if(!holding){holding=true;attach(false)}
        val q=(dx/W).coerceIn(0f,1f);val s=1f-.14f*q;val w=W*s;val h=H*s;val left=(W-w)/2f+dx*.5f;val top=(H-h)/2f
        home.set(left,top,left+w,top+h);pose(1f,a.d(26)*(q*5f).coerceAtMost(1f))}
    override fun swipeEnd(go:Boolean){if(!holding)return;holding=false
        if(go){busy=true;underlay(true);val r=radius;legs=legs(landing());pose(1f,r)
            later{run(1f,0f,CLOSE,close,{pose(it,r)}){busy=false;toList()}};return}
        val h0=RectF(home);val r0=radius
        run(0f,1f,MotionSpec.SETTLE,MotionSpec.move,{t->home.set(h0.left*(1-t),h0.top*(1-t),h0.right+(W-h0.right)*t,h0.bottom+(H-h0.bottom)*t);pose(1f,r0*(1-t))}){detach()}}
}

/** The page rides on the keyboard: frame by frame, as far as needed for [target] to stay above it, by moving the
 *  page as a picture (no layout); it comes back down the same way as the keyboard closes. */
internal fun MainActivity.rideKeyboard(target:View){
    var sv:android.view.ViewParent?=target.parent;while(sv!=null&&sv !is ScrollView)sv=sv.parent
    val page=sv as? View?:return
    val at=IntArray(2);target.getLocationOnScreen(at);val root=IntArray(2);this.root.getLocationOnScreen(root)
    val cardBottom=at[1]-page.translationY+target.height;val screenBottom=root[1]+this.root.height-d(20)
    imeFollower={ime->page.translationY=-(cardBottom-(screenBottom-ime)).coerceAtLeast(0f)}}
/** If the keyboard never reports its closing slide, bring the page down anyway. */
internal fun MainActivity.settleKeyboardRide(){imeFollower?.let{fl->root.postDelayed({if(imeFollower===fl){val ime=androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0;if(ime==0){fl(0);imeFollower=null}}},900)}}

/** The empty place for a new shelf, always last on «Полки» (in both modes): a dashed card «+ Новая полка». A tap
 *  turns it into a field right where it is; once named, the new shelf grows out of it above, and the place stays. */
internal class ShelfSlot(private val a:MainActivity,private val create:(String)->Boolean):FrameLayout(a){
    private val dash=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.4f*a.resources.displayMetrics.density;pathEffect=DashPathEffect(floatArrayOf(a.d(5).toFloat(),a.d(4).toFloat()),0f)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private var lit=0f
    private val ink=Ui.fill(a.light)
    private val label=a.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(14),0)}
    private var field:EditText?=null
    init{setWillNotDraw(false);clipChildren=false;isClickable=true;contentDescription="Новая полка"
        label.addView(IconView(a,"plus",a.muted),LinearLayout.LayoutParams(a.d(18),a.d(18)).apply{rightMargin=a.d(10)})
        label.addView(a.text("Новая полка",Type.TEXT,a.muted,true))
        addView(label,LayoutParams(-1,-1))
        setOnClickListener{start()};a.press(this)}
    /** The place for a new shelf exists only while editing: it opens and fades in with the edit mode. */
    private var shown=if(a.shelvesEditing)1f else 0f
    private fun reveal(k:Float){shown=k;alpha=k;visibility=if(k<=0.001f)View.GONE else View.VISIBLE;requestLayout()}
    override fun onMeasure(w:Int,h:Int)=super.onMeasure(w,MeasureSpec.makeMeasureSpec((a.d(52)*shown).toInt(),MeasureSpec.EXACTLY))
    override fun onDraw(c:Canvas){val i=dash.strokeWidth/2;val r=a.d(16).toFloat()
        if(lit>0f){fill.color=(((.03f*lit)*255).toInt() shl 24) or ink;c.drawRoundRect(i,i,width-i,height-i,r,r,fill)}
        dash.color=((((.18f+.22f*lit)*(if(a.light).8f else 1f))*255).toInt() shl 24) or ink;c.drawRoundRect(i,i,width-i,height-i,r,r,dash)}
    private fun light(to:Float){android.animation.ValueAnimator.ofFloat(lit,to).apply{duration=Motion.STANDARD;addUpdateListener{lit=it.animatedValue as Float;invalidate()};start()}}
    /** Opened from the «+» menu outside the edit mode: the place first unfolds at the end of the shelves (with the
     *  shelves' own spacing), then turns into the field; once the name is in (or dropped), it folds away again. */
    private var fresh=false
    /** Folds the place away after a «+»-menu shelf (outside the edit mode). */
    private fun fold(after:Long){if(!fresh)return;fresh=false;if(a.shelvesEditing)return
        val lp=layoutParams as? LinearLayout.LayoutParams?:return;val m0=lp.topMargin
        if(a.prefs.str("motion")=="reduced"){lp.topMargin=0;layoutParams=lp;reveal(0f);return}
        val sp=Spring.of(.1,460.0);marginAnim?.cancel();marginAnim=android.animation.ValueAnimator.ofFloat(1f,0f).apply{duration=sp.duration;startDelay=after;interpolator=sp.interpolator
            addUpdateListener{val k=it.animatedValue as Float;lp.topMargin=(m0*k).toInt();layoutParams=lp;reveal(k)};start()}}
    private fun start(){if(field!=null||!isAttachedToWindow)return
        val f=a.edit("","Название полки").apply{setSingleLine(true);textSize=Type.TITLE;typeface=PolkaFonts.sans(600);setTextColor(a.fg);setHintTextColor(a.muted);background=null;setPadding(a.d(14),0,a.d(14),0);minHeight=0;minimumHeight=0
            gravity=Gravity.CENTER_VERTICAL;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;alpha=0f
            setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commit();true}else false}
            setOnFocusChangeListener{v,has->if(!has)v.post{if(field===v)commit()}}}
        field=f;isClickable=false;light(1f)
        label.animate().alpha(0f).setDuration(Motion.FAST).start()
        addView(f,LayoutParams(-1,-1));f.animate().alpha(1f).setStartDelay(60).setDuration(Motion.FAST).start()
        a.rideKeyboard(this)
        f.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f,0)}
    fun commit(){val f=field?:return;field=null;val next=f.text.toString().trim()
        a.settleKeyboardRide()
        f.onFocusChangeListener=null;f.setOnEditorActionListener(null)
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)
        if(f.hasFocus()){a.root.isFocusableInTouchMode=true;a.root.requestFocus()}
        val made=next.isNotBlank()&&create(next)
        // the typed name leaves with the new shelf; the place quietly becomes «+ Новая полка» again
        f.animate().alpha(0f).setStartDelay(0).setDuration(if(made)140 else 120).withEndAction{removeView(f)}.start()
        label.animate().alpha(1f).setStartDelay(if(made)200 else 80).setDuration(Motion.STANDARD).start()
        light(0f);isClickable=true
        // from the «+» menu: the new shelf has grown above (420 ms); then the place folds away
        fold(if(made)380L else 120L)}
    /** Same spacing as the shelves: 26dp apart, 10dp between the edit cards. */
    private var marginAnim:android.animation.ValueAnimator?=null
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){if(!on)commit();val lp=layoutParams as? LinearLayout.LayoutParams?:return;val to=a.d(if(on)10 else 0);marginAnim?.cancel()
        if(!animate||a.prefs.str("motion")=="reduced"){lp.topMargin=to;layoutParams=lp;reveal(if(on)1f else 0f);return}
        val m0=lp.topMargin;val s0=shown;val s1=if(on)1f else 0f
        val sp=Spring.of(.1,460.0);marginAnim=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=sp.duration;startDelay=delay;interpolator=sp.interpolator;addUpdateListener{val k=it.animatedValue as Float;lp.topMargin=(m0+(to-m0)*k).toInt();layoutParams=lp;reveal((s0+(s1-s0)*k).coerceIn(0f,1f))};start()}}
}

/** «Полки» (approved «Б: полки, статусы сверху»): the four statuses as small shelves in a block of two by two, always
 *  there (in edit mode they can be dragged into another order, never renamed or removed); then every shelf of your own as a card with its first books fanned, in the saved order,
 *  then the place for a new shelf.
 *  «Изменить» turns the rows into cards that can be dragged into a new order, renamed in place, and removed —
 *  all in place: nothing on the page is rebuilt. */
internal fun MainActivity.shelfRows(){
    val byShelf=HashMap<String,MutableList<JSONObject>>();books.forEach{b->BookShelves.of(b).forEach{n->byShelf.getOrPut(n){mutableListOf()}.add(b)}}
    val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys).filter{it.isNotBlank()}.distinct()
    val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
    lateinit var editor:ShelfEditor
    /** One of your shelves. Its name can change while the page stays up, so everything asks for the current one. */
    fun own(name0:String,list:List<JSONObject>):ShelfRow{var cur=name0;lateinit var row:ShelfRow
        row=ShelfRow(this,"s:$name0",name0,sortBooks(list),{enterShelf(row){shelf=cur}},{shelfActions(cur)},
            {next:String->val old=cur;cur=next;renameShelf(old,next,quiet=true)},{val gone=cur;editor.delete(row){removeShelfQuietly(gone)}})
        return row}
    val byStatus=books.groupBy{Library.status(it)}
    val all=listOf("reading","want","done","paused")
    val order=prefs.optJSONArray("statusOrder")?.strings().orEmpty().filter{it in all}.distinct().let{it+(all-it.toSet())}
    val statuses=StatusGrid(this,order.associateWith{key->lateinit var row:ShelfRow
        row=ShelfRow(this,"a:$key",MainActivity.autoTitle(key),sortBooks(byStatus[key].orEmpty()),{enterShelf(row){auto=key}},{autoShelfActions(key)},null,compact=true);row},order)
    page.addView(statuses,LinearLayout.LayoutParams(-1,-2))
    val saved=prefs.optJSONArray("shelfOrder")?.strings().orEmpty()
    val rows=manual.map{own(it,byShelf[it].orEmpty())}.sortedBy{saved.indexOf(it.key).let{i->if(i<0)Int.MAX_VALUE else i}}
    rows.forEach{r->page.addView(r,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(26)})}
    val slot=ShelfSlot(this){name->
        val taken=(prefs.optJSONArray("shelves")?.strings().orEmpty()+books.flatMap{BookShelves.of(it)}).any{it.equals(name,true)}
        if(taken){Notice.short(this,Notice.Kind.SHELF,"Полка «$name» уже есть");false}
        else{prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+name).distinct()));savePreferences();editor.grow(own(name,emptyList()));true}}
    page.addView(slot,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(if(rows.isEmpty()||!shelvesEditing)0 else 10)});if(!shelvesEditing)slot.visibility=View.GONE
    editor=ShelfEditor(this,page,rows.toMutableList(),slot,statuses);shelfEditor=editor;addShelfRow={name->editor.grow(own(name,emptyList()),true)};removeShelfRow={name->editor.deleteKey("s:$name"){removeShelfQuietly(name)}}
    if(shelvesEditing){rows.forEach{it.setEditing(true,false)};slot.setEditing(true,false);statuses.setEditing(true,false)}
}

/** The four statuses, two by two, 10dp apart. In edit mode they fold into small edit cards with the grip, as the
 *  shelves below do, and one can be dragged anywhere in the block: it lifts and follows the finger, the others glide
 *  into their new places, and the order is saved when it is let go. Outside edit mode a tap opens a status and a long
 *  press its menu. */
internal class StatusGrid(private val a:MainActivity,private val tiles:Map<String,ShelfRow>,order:List<String>):FrameLayout(a){
    private val order=order.toMutableList()
    private val gap=a.d(10)
    private var editing=false
    private var held:String?=null;private var downX=0f;private var downY=0f;private var cellW=0;private var cellH=0
    private val glide=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
    init{clipChildren=false;clipToPadding=false;order.forEach{k->addView(tiles.getValue(k),LayoutParams(-1,-2))}}
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);cellW=(w-gap)/2
        tiles.values.forEach{it.measure(MeasureSpec.makeMeasureSpec(cellW,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))}
        cellH=tiles.values.maxOf{it.measuredHeight}
        tiles.values.forEach{it.measure(MeasureSpec.makeMeasureSpec(cellW,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(cellH,MeasureSpec.EXACTLY))}
        setMeasuredDimension(w,cellH*2+gap)}
    private fun slotX(i:Int)=(i%2)*(cellW+gap);private fun slotY(i:Int)=(i/2)*(cellH+gap)
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){order.forEachIndexed{i,k->tiles.getValue(k).layout(slotX(i),slotY(i),slotX(i)+cellW,slotY(i)+cellH)}}
    fun setEditing(on:Boolean,animate:Boolean){if(!on)drop();editing=on;tiles.values.forEach{it.setEditing(on,animate)}}
    override fun onInterceptTouchEvent(e:MotionEvent)=editing
    override fun onTouchEvent(e:MotionEvent):Boolean{if(!editing)return super.onTouchEvent(e)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{val k=order.indices.firstOrNull{i->e.x>=slotX(i)&&e.x<slotX(i)+cellW&&e.y>=slotY(i)&&e.y<slotY(i)+cellH}?.let{order[it]}?:return false
                held=k;downX=e.x;downY=e.y;parent?.requestDisallowInterceptTouchEvent(true)
                val v=tiles.getValue(k);v.bringToFront();v.haptic(a,Haptic.LONG)
                v.animate().scaleX(1.04f).scaleY(1.04f).translationZ(a.d(14).toFloat()).setDuration(Motion.FAST).setInterpolator(glide).start()}
            MotionEvent.ACTION_MOVE->{val k=held?:return true;val v=tiles.getValue(k);val i=order.indexOf(k)
                val dx=e.x-downX;val dy=e.y-downY;v.translationX=dx;v.translationY=dy
                val cx=slotX(i)+cellW/2f+dx;val cy=slotY(i)+cellH/2f+dy
                val t=order.indices.minByOrNull{j->val ex=slotX(j)+cellW/2f-cx;val ey=slotY(j)+cellH/2f-cy;ex*ex+ey*ey}?:i
                if(t!=i)move(k,i,t)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{drop();parent?.requestDisallowInterceptTouchEvent(false)}}
        return true}
    /** [k] takes place [to]: the others glide from where they are to their new places, the held one stays under the finger. */
    private fun move(k:String,from:Int,to:Int){val before=order.associateWith{key->val j=order.indexOf(key);slotX(j)+tiles.getValue(key).translationX to slotY(j)+tiles.getValue(key).translationY}
        order.removeAt(from);order.add(to,k);haptic(a,Haptic.SELECT)
        order.forEachIndexed{j,key->val v=tiles.getValue(key);val (bx,by)=before.getValue(key)
            v.layout(slotX(j),slotY(j),slotX(j)+cellW,slotY(j)+cellH);v.translationX=bx-slotX(j);v.translationY=by-slotY(j)
            if(key==k){downX+=slotX(j)-slotX(from);downY+=slotY(j)-slotY(from)}
            else v.animate().translationX(0f).translationY(0f).setDuration(Motion.STANDARD).setInterpolator(glide).start()}}
    /** Let go: the held status settles into its place and the order is saved. */
    private fun drop(){val k=held?:return;held=null
        tiles.getValue(k).animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(Motion.STANDARD).setInterpolator(glide).start()
        a.prefs.put("statusOrder",JSONArray(order));a.savePreferences()}
}

/** Drag-to-reorder for the shelf cards in edit mode: the held card lifts and follows the finger, the others slide
 *  aside with a short glide to show where it will land; on release it settles into the gap and the order is saved. */
internal class ShelfEditor(private val a:MainActivity,private val page:LinearLayout,private val rows:MutableList<ShelfRow>,private val place:ShelfSlot?=null,private val statuses:StatusGrid?=null){
    private var held:ShelfRow?=null;private var from=0;private var target=0;private var startY=0f;private var slot=1
    private var startScroll=0;private var lastY=0f
    private val glide=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
    init{rows.forEach{r->r.handle.setOnTouchListener{_,e->touch(r,e);true}}}
    fun setEditing(on:Boolean){a.shelvesEditing=on;statuses?.setEditing(on,true);val delays=Stagger.delays(rows.size+1,30,ease=Ease.outQuad);rows.forEachIndexed{i,r->r.setEditing(on,true,delays[i])};place?.setEditing(on,true,delays.last())}
    private fun saveOrder(){a.prefs.put("shelfOrder",org.json.JSONArray(rows.map{it.key}));a.savePreferences()}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    /** The shelf [key] leaves the page in place (the quick menu's «Удалить полку»); false if it is not here. */
    fun deleteKey(key:String,done:()->Unit):Boolean{val r=rows.firstOrNull{it.key==key}?:return false;delete(r,done);return true}
    /** A new shelf grows out of the empty place, just above it: its height opens, it rises a little and fades in. */
    fun grow(r:ShelfRow,follow:Boolean=false){r.handle.setOnTouchListener{_,e->touch(r,e);true}
        val at=place?.let{page.indexOfChild(it)}?.takeIf{it>=0}?:page.childCount
        val lp=LinearLayout.LayoutParams(-1,-2).apply{if(at>0)topMargin=a.d(if(a.shelvesEditing)10 else 26)}
        page.addView(r,at,lp);rows.add(r);r.setEditing(a.shelvesEditing,false);saveOrder()
        if(reduced)return
        r.measure(View.MeasureSpec.makeMeasureSpec(page.width-page.paddingLeft-page.paddingRight,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val full=r.measuredHeight;val m=lp.topMargin
        // [follow]: the page rides down in the same motion as the shelf opens, so the two never fight
        val sv=a.scroll;val s0=sv.scrollY;val viewport=sv.height-sv.paddingBottom;val content0=sv.getChildAt(0)?.height?:0
        val s1=if(follow)maxOf(s0,content0+full+m-viewport) else s0
        // no scaling: the shelf glass runs to the screen edges, and a scaled row showed with its sides cut off
        lp.height=0;lp.topMargin=0;r.layoutParams=lp;r.alpha=0f;r.translationY=a.d(14).toFloat()
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.LONG;interpolator=android.view.animation.PathInterpolator(.42f,0f,.18f,1f);addUpdateListener{val k=it.animatedValue as Float;lp.height=(full*k).toInt().coerceAtLeast(1);lp.topMargin=(m*k).toInt();r.layoutParams=lp
                if(s1>s0)sv.scrollTo(0,(s0+(s1-s0)*k).toInt())}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){lp.height=LinearLayout.LayoutParams.WRAP_CONTENT;r.layoutParams=lp;r.settleMargin()}});start()}
        r.animate().alpha(1f).translationY(0f).setStartDelay(160).setDuration(Motion.LONG).setInterpolator(glide).start()}
    /** The card slides left and fades, then its place closes and the cards below glide up. */
    fun delete(r:ShelfRow,done:()->Unit){val i=rows.indexOf(r);if(i<0)return
        val finish={page.removeView(r);rows.remove(r);rows.forEach{it.settleMargin()};saveOrder();done()}
        if(reduced||!r.isAttachedToWindow){finish();return}
        // it melts away where it stands, then its place closes
        r.animate().alpha(0f).setStartDelay(40).setDuration(Motion.EMPHASIS).setInterpolator(android.view.animation.PathInterpolator(.4f,0f,.6f,1f)).start()
        val lp=r.layoutParams as LinearLayout.LayoutParams;val h=r.height;val m=lp.topMargin
        // the first card of the page leaves: the one after it gives up its gap at the same pace, so nothing jumps at
        // the end (below the statuses every card keeps its gap)
        val next=if(i==0&&page.indexOfChild(r)==0)rows.getOrNull(1) else null;val nlp=next?.layoutParams as? LinearLayout.LayoutParams;val nm=nlp?.topMargin?:0
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=300;duration=Motion.EMPHASIS;interpolator=android.view.animation.PathInterpolator(.42f,0f,.18f,1f)
            addUpdateListener{val k=it.animatedValue as Float;lp.height=(h*(1-k)).toInt();lp.topMargin=(m*(1-k)).toInt();r.layoutParams=lp;if(next!=null&&nlp!=null){nlp.topMargin=(nm*(1-k)).toInt();next.layoutParams=nlp}}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){finish()}});start()}}
    private fun touch(r:ShelfRow,e:MotionEvent){if(!r.editing)return
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{held=r;from=rows.indexOf(r);target=from;startY=e.rawY;lastY=e.rawY;startScroll=a.scroll.scrollY;slot=r.height+a.d(10)
                r.parent.requestDisallowInterceptTouchEvent(true);r.haptic(a,Haptic.LONG)
                r.animate().scaleX(1.03f).scaleY(1.03f).translationZ(a.d(14).toFloat()).setDuration(Motion.FAST).setInterpolator(glide).start()
                a.scroll.postOnAnimation(edge)}
            MotionEvent.ACTION_MOVE->{lastY=e.rawY;follow()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val h=held?:return;held=null;a.scroll.removeCallbacks(edge)
                h.animate().translationY(((target-from)*slot).toFloat()).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(Motion.STANDARD).setInterpolator(glide).withEndAction{drop(h)}.start()}
        }}
    /** The held card stays under the finger, including while the page scrolls under it. */
    private fun follow(){val h=held?:return;val dy=lastY-startY+(a.scroll.scrollY-startScroll);h.translationY=dy
        val t=(from+Math.round(dy/slot)).coerceIn(0,rows.lastIndex)
        if(t!=target){target=t;h.haptic(a,Haptic.SELECT)
            rows.forEachIndexed{j,o->if(o===h)return@forEachIndexed;val shift=when{j in (from+1)..target-> -slot;j in target until from->slot;else->0}.toFloat()
                if(o.translationY!=shift)o.animate().translationY(shift).setDuration(Motion.STANDARD).setInterpolator(glide).start()}}}
    /** Auto-scroll: holding the card near the top or bottom edge scrolls the page, faster the closer to the edge. */
    private val edge=object:Runnable{override fun run(){if(held==null)return
        val loc=IntArray(2);a.scroll.getLocationOnScreen(loc);val top=loc[1]+a.appInsets.top+a.d(8);val bottom=loc[1]+a.scroll.height-a.floatingBottom()-a.d(64)
        val zone=a.d(88).toFloat();val max=a.d(16).toFloat()
        val v=when{lastY<top+zone->-max*((top+zone-lastY)/zone).coerceIn(0f,1f);lastY>bottom-zone->max*((lastY-(bottom-zone))/zone).coerceIn(0f,1f);else->0f}
        if(v!=0f){val before=a.scroll.scrollY;a.scroll.scrollBy(0,v.toInt().let{if(it==0)(if(v>0)1 else -1) else it});if(a.scroll.scrollY!=before)follow()}
        a.scroll.postOnAnimation(this)}}
    /** Put the card where it landed, with no visible jump, and save the order. */
    private fun drop(h:ShelfRow){if(target!=from){val base=rows.minOf{page.indexOfChild(it)};rows.removeAt(from);rows.add(target,h);page.removeView(h);page.addView(h,base+target)}
        rows.forEach{it.animate().cancel();it.translationY=0f;it.settleMargin()}
        saveOrder()}
}

/** Top of «Полки»: only «Изменить» on the right. In edit mode it turns into a filled «Готово». */
internal fun MainActivity.shelvesTopBar():View{
    val bar=FrameLayout(this)
    bar.addView(text("Полки",Type.H1,fg,true,serif=true),FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=d(2)})
    val label=text(if(shelvesEditing)"Готово" else "Редактировать",Type.TEXT,fg,true).apply{gravity=Gravity.CENTER;setPadding(d(18),0,d(18),0);isClickable=true;isFocusable=true;contentDescription="Редактировать полки"}
    fun paint(){val on=shelvesEditing;label.setTextColor(if(on)bg else fg);label.background=if(on)shape(fg,20) else controlSurface(label,20f)}
    paint();shelvesTopBarLabel=label
    label.setOnClickListener{val on=!shelvesEditing;shelfEditor?.setEditing(on)
        label.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(Motion.FAST).withEndAction{label.text=if(on)"Готово" else "Редактировать";paint();label.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.STANDARD).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    press(label)
    bar.addView(label,FrameLayout.LayoutParams(-2,d(40),Gravity.END or Gravity.CENTER_VERTICAL))
    return bar
}

/** A shade or glow as a picture: [f] gives its strength (0..1) at every pixel, [max] is the opacity at full
 *  strength. Each pixel gets a grain of noise before rounding, so a gentle falloff never shows steps. */
internal object SoftShade{
    fun bake(w:Int,h:Int,rgb:Int,max:Float,f:(Float,Float)->Float):Bitmap{val px=IntArray(maxOf(w,0)*maxOf(h,0));var seed=0x9e3779b9.toInt();val c=rgb and 0xffffff
        for(y in 0 until h)for(x in 0 until w){seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5)
            val v=f(x+.5f,y+.5f)*max*255f;val al=if(v<=0f)0 else (v+((seed ushr 8) and 0xffff)/65536f-.5f+.5f).toInt().coerceIn(0,255)
            px[y*w+x]=(al shl 24) or c}
        return Bitmap.createBitmap(maxOf(w,1),maxOf(h,1),Bitmap.Config.ARGB_8888).also{if(w>0&&h>0)it.setPixels(px,0,w,0,0,w,h)}}
}
