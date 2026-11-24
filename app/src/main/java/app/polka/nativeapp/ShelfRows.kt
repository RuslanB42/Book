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
internal class ShelfRow(private val a:MainActivity,var key:String,private var title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?,private val rename:((String)->Unit)?,private val remove:(()->Unit)?=null):LinearLayout(a){
    /** The fan: the shelf's first books in its own order (so they fly into its first places when it opens). */
    internal val fan=ShelfFan(a,list.take(4))
    /** The card around the name and the fan; it fades as the shelf folds into its edit card. */
    private val face=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)Color.WHITE else 0xff202022.toInt());cornerRadius=a.d(26).toFloat()}
    // ── edit mode: the header becomes a card with a drag handle, the covers fold away ──
    private val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40)}
    private val card=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(16).toFloat();alpha=0}
    private val name=a.text(title,17f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val pen=IconView(a,"pen",a.muted).apply{alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    /** Edit mode: removes a shelf of the user's own (the books stay in the library). */
    private val trash=IconView(a,"trash",0xffff8a7a.toInt()).apply{alpha=0f;visibility=View.INVISIBLE;isClickable=true;contentDescription="Удалить полку";setPadding(a.d(10),a.d(10),a.d(10),a.d(10))}
    internal val handle=Grip()
    /** Delete asks right in the card: the bin grows into a red «Удалить»; a tap anywhere else takes it back. */
    private val pill=a.text("Удалить",13.5f,Color.WHITE,true).apply{gravity=Gravity.CENTER;setSingleLine(true);background=a.shape(0xffff5a4c.toInt(),16);visibility=View.GONE;isClickable=true;contentDescription="Удалить полку"}
    private var ask=0f;private var askAnim:android.animation.ValueAnimator?=null
    private val cardColor=if(a.light)Color.WHITE else 0xff242426.toInt()
    private val outside:(MotionEvent)->Unit={e->val r=Rect();if(!pill.getGlobalVisibleRect(r)||!r.contains(e.rawX.toInt(),e.rawY.toInt()))cancelAsk()}
    private val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    private val stageFull=fan.full
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
        name.textSize=20f;name.typeface=PolkaFonts.serif(a,true)
        val end=FrameLayout(a);end.addView(handle,FrameLayout.LayoutParams(a.d(44),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END))
        // the bin sits right before the grip, at the same place on every card
        remove?.let{f->trash.setOnClickListener{if(editing){commitRename();askDelete()}};a.press(trash);end.addView(trash,FrameLayout.LayoutParams(a.d(40),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(38)})
            pill.setOnClickListener{if(ask<.5f)return@setOnClickListener;a.touchWatchers.remove(outside);pill.isClickable=false
                pill.animate().scaleX(.94f).scaleY(.94f).setDuration(90).withEndAction{pill.animate().scaleX(1f).scaleY(1f).setDuration(140).start()}.start();f()}
            end.addView(pill,FrameLayout.LayoutParams(a.d(40),a.d(32),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(40)})}
        head.addView(end,LayoutParams(-2,a.d(44)))
        addView(head,LayoutParams(-1,-2))
        stage.addView(fan,FrameLayout.LayoutParams(-1,fan.full))
        // a tap anywhere on the card opens the shelf itself (not the book under the finger)
        stage.isClickable=true;stage.contentDescription="Открыть полку $title";stage.setOnClickListener{if(!editing)open()}
        addView(stage,LayoutParams(-1,stageFull).apply{topMargin=a.d(12)})
        background=face
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
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
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
        return super.dispatchTouchEvent(e)}
    /** Morph between the shelf and its edit card; [delay] staggers the rows. */
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){editing=on;if(!on){commitRename();cancelAsk()}
        anim?.cancel();val to=if(on)1f else 0f
        if(!animate||a.prefs.str("motion")=="reduced"){apply(to);return}
        val from=progress
        val sp=Spring.of(.1,460.0);anim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=sp.duration;startDelay=delay;interpolator=sp.interpolator;addUpdateListener{apply(it.animatedValue as Float)};start()}}
    private fun apply(e:Float){progress=e
        val lp=stage.layoutParams;lp.height=(stageFull*(1-e)).toInt();stage.layoutParams=lp
        // while folding, the covers and the shelf are cut at the shrinking bottom instead of spilling over the next shelf
        stage.clipBounds=if(e>0f)android.graphics.Rect(-a.d(40),-a.d(80),a.d(2000),lp.height) else null
        stage.alpha=(1-e*1.6f).coerceIn(0f,1f);stage.scaleY=1f-.08f*e;stage.pivotY=0f
        card.alpha=(e*255).toInt();val pad=(a.d(14)*e).toInt();head.setPadding(pad,(a.d(6)*e).toInt(),(a.d(4)*e).toInt(),(a.d(6)*e).toInt())
        face.alpha=((1-e)*255).toInt();setPadding(0,(a.d(18)*(1-e)).toInt(),0,(a.d(12)*(1-e)).toInt());centerName()
        handle.alpha=((e-.35f)/.65f).coerceIn(0f,1f);handle.translationX=a.d(14)*(1-e);handle.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE
        pen.alpha=if(rename==null||field!=null)0f else ((e-.4f)/.6f).coerceIn(0f,1f)*.8f
        if(remove!=null){trash.alpha=((e-.4f)/.6f).coerceIn(0f,1f)*(1-ask);trash.translationX=a.d(14)*(1-e);trash.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE;trash.scaleX=1-.3f*ask;trash.scaleY=1-.3f*ask}
        head.elevation=a.d(if(a.light)3 else 4)*e
        (layoutParams as? MarginLayoutParams)?.let{m->val idx=(parent as? ViewGroup)?.indexOfChild(this)?:0;val want=if(idx<=0)0 else (a.d(12)+(a.d(10)-a.d(12))*e).toInt();if(m.topMargin!=want){m.topMargin=want;layoutParams=m}}}
    private fun askDelete(){if(ask>0f||!editing)return;pill.visibility=View.VISIBLE;pill.isClickable=true;a.touchWatchers.add(outside);a.root.post{if(pill.visibility==View.VISIBLE&&!a.touchWatchers.contains(outside))a.touchWatchers.add(outside)};runAsk(1f)}
    fun cancelAsk(){a.touchWatchers.remove(outside);if(ask<=0f&&askAnim==null)return;runAsk(0f)}
    private fun runAsk(to:Float){askAnim?.cancel();val from=ask;val reduced=a.prefs.str("motion")=="reduced"
        fun set(k:Float){ask=k;val lp=pill.layoutParams;lp.width=(a.d(40)+(a.d(96)-a.d(40))*k).toInt();pill.layoutParams=lp;pill.alpha=(k*1.5f).coerceIn(0f,1f)
            card.setColor(androidx.core.graphics.ColorUtils.blendARGB(cardColor,0xffff5a4c.toInt(),.14f*k));card.alpha=(progress*255).toInt();apply(progress)
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
        if(a.prefs.str("motion")=="reduced"||pen.alpha<=0f)swap() else pen.animate().alpha(0f).setDuration(110).withEndAction{swap()}.start()}
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
        pen.animate().cancel();if(editing&&rename!=null)pen.animate().alpha(.8f).setDuration(180).start()
        // the shelves reload after a rename: let this tap finish first
        if(next.isNotBlank()&&next!=title){name.text=next;title=next;if(key.startsWith("s:"))key="s:$next";head.contentDescription="$next, ${MainActivity.countBooks(list.size)}";centerName();settleBreath();val r=rename;a.root.post{r?.invoke(next)}}}
    /** Saved: the card breathes once. */
    private fun settleBreath(){if(a.prefs.str("motion")=="reduced")return;head.animate().scaleX(1.012f).scaleY(1.012f).setDuration(160).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).withEndAction{head.animate().scaleX(1f).scaleY(1f).setDuration(220).start()}.start()}

    /** Six-dot grip; dragging it moves the shelf. */
    internal inner class Grip:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init{contentDescription="Переместить полку";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
        override fun onDraw(c:Canvas){p.color=a.muted;val r=a.d(1).toFloat()*1.6f;val gx=a.d(6).toFloat();val gy=a.d(6).toFloat()
            for(i in 0..1)for(j in -1..1)c.drawCircle(width/2f+(i-.5f)*gx,height/2f+j*gy,r,p)}
    }

    override fun onDetachedFromWindow(){a.touchWatchers.remove(outside);super.onDetachedFromWindow()}
}

/** A shelf's first books fanned (approved «веер»): up to four covers overlapping, the middle ones higher and on top,
 *  the outer ones turned out; an empty shelf shows a dashed place with «+». */
internal class ShelfFan(private val a:MainActivity,val books:List<JSONObject>):FrameLayout(a){
    private val cw=a.d(70);private val ch=a.d(105)
    /** The fan's full height (the covers and the lift of the outer ones). */
    val full=ch+a.d(34)
    /** The covers, left to right. */
    val covers=books.map{b->CoverView(a,b,a.cover(b)).apply{softShadow=true}}
    private val turns=mapOf(1 to floatArrayOf(0f),2 to floatArrayOf(-5f,5f),3 to floatArrayOf(-8f,0f,8f),4 to floatArrayOf(-10f,-3.5f,3.5f,10f))
    init{clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        val n=covers.size
        if(n==0)addView(Place(),LayoutParams(cw,ch,Gravity.CENTER))
        // the outer covers first, so the middle ones lie on top
        covers.indices.sortedByDescending{kotlin.math.abs(it-(n-1)/2f)}.forEach{i->val c=covers[i];c.rotation=turns.getValue(n)[i];addView(c,LayoutParams(cw,ch))}}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val n=covers.size
        if(n==0){super.onLayout(changed,l,t,r,b);return}
        val step=cw*.62f;val total=step*(n-1)+cw;val x0=(width-total)/2f
        covers.forEachIndexed{i,c->val mid=kotlin.math.abs(i-(n-1)/2f);val dy=a.d(10)+mid*mid*a.d(5)
            val x=(x0+i*step).toInt();val y=dy.toInt();c.layout(x,y,x+cw,y+ch)}}
    /** Where cover [i] is on the screen (its unturned box) and how it is turned. */
    fun spot(i:Int):Pair<RectF,Float>?{val c=covers.getOrNull(i)?:return null;if(!c.isAttachedToWindow||c.width==0)return null
        val at=IntArray(2);getLocationOnScreen(at);return RectF((at[0]+c.left).toFloat(),(at[1]+c.top).toFloat(),(at[0]+c.right).toFloat(),(at[1]+c.bottom).toFloat()) to c.rotation}
    /** An empty shelf's place for a book. */
    private inner class Place:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1)*1.5f;color=if(a.light)0x33000000 else 0x29ffffff
            pathEffect=DashPathEffect(floatArrayOf(a.d(5).toFloat(),a.d(4).toFloat()),0f);strokeCap=Paint.Cap.ROUND}
        private val plus=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1)*1.75f;color=a.muted;strokeCap=Paint.Cap.ROUND}
        override fun onDraw(c:Canvas){val h=p.strokeWidth;c.drawRoundRect(h,h,width-h,height-h,a.d(6).toFloat(),a.d(6).toFloat(),p)
            val cx=width/2f;val cy=height/2f;val k=a.d(9).toFloat();c.drawLine(cx-k,cy,cx+k,cy,plus);c.drawLine(cx,cy-k,cx,cy+k,plus)}
    }
}

/**
 * A shelf opening and closing (approved «Полка: как открывается»): its fanned covers fly out of the card, straighten
 * and grow into the shelf's first places while its page fades in over the list; on the way back they fly home into
 * the fan as the page fades away. Copies of the covers fly on a layer over everything; the real ones show on landing.
 */
internal object ShelfFlight{
    class Spot(val book:JSONObject,val r:RectF,val turn:Float)
    private class Leg(val book:JSONObject,val from:RectF,val fromTurn:Float,val to:RectF,val toTurn:Float,val real:View)
    private const val TIME=560L;private const val STAGGER=35L
    private val curve=android.view.animation.PathInterpolator(.3f,0f,0f,1f)

    /** The covers of the grid now on screen, by book. */
    fun cells(a:MainActivity):Map<String,View>{val out=HashMap<String,View>()
        fun walk(v:View){val t=v.tag;if(t is String&&t.startsWith("grid:")&&v.isShown&&v is ViewGroup){(v.getChildAt(0) as? CoverView)?.let{out[t.removePrefix("grid:")]=it};return}
            if(v is ViewGroup)for(i in 0 until v.childCount)walk(v.getChildAt(i))}
        walk(a.root);return out}
    private fun box(v:View):RectF{val at=IntArray(2);v.getLocationOnScreen(at);return RectF(at[0].toFloat(),at[1].toFloat(),(at[0]+v.width).toFloat(),(at[1]+v.height).toFloat())}
    private fun row(v:View,key:String?):ShelfRow?{if(key==null)return null;if(v is ShelfRow&&v.key==key)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)row(v.getChildAt(i),key)?.let{return it};return null}
    private fun laid(a:MainActivity,f:()->Unit){a.root.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{a.root.viewTreeObserver.removeOnPreDrawListener(this);f();return true}})}

    /** Into the shelf: [page] (the new one) fades in over [under] (the list's picture), where the card's fan at [fan]
     *  is emptied at once; the covers fly from [spots] into the grid. */
    fun open(a:MainActivity,page:View,under:View,fan:RectF,spots:List<Spot>,done:()->Unit){page.alpha=0f
        // the list's picture still has the covers in the card: they have just left it
        val o=IntArray(2);a.root.getLocationOnScreen(o)
        val patch=View(a).apply{setBackgroundColor(if(a.light)Color.WHITE else 0xff202022.toInt());importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        a.root.addView(patch,a.root.indexOfChild(under)+1,FrameLayout.LayoutParams((fan.width()-a.d(52)).toInt(),fan.height().toInt()).apply{leftMargin=(fan.left+a.d(26)-o[0]).toInt();topMargin=(fan.top-o[1]).toInt()})
        laid(a){val cells=cells(a)
            val legs=spots.mapNotNull{s->val v=cells[s.book.str("id")]?:return@mapNotNull null;Leg(s.book,s.r,s.turn,box(v),0f,v)}
            fly(a,legs,{k->page.alpha=(k/.55f).coerceIn(0f,1f)}){page.alpha=1f;a.root.removeView(patch);done()}}}

    /** Back to the list: [img] (the shelf's picture, [pic]) fades away and the covers that were at [cells] fly home
     *  into the fan of the shelf [key]. When that shelf is not on the screen, [otherwise] runs instead. */
    fun back(a:MainActivity,key:String?,cells:Map<String,View>,img:ImageView,pic:Bitmap,done:()->Unit,otherwise:()->Unit){
        val from=cells.mapValues{box(it.value)}
        laid(a){val r=row(a.root,key);val screen=RectF(0f,0f,a.root.width.toFloat(),a.root.height.toFloat())
            val legs=r?.fan?.let{f->f.books.indices.mapNotNull{i->val s=f.spot(i)?:return@mapNotNull null;val at=from[f.books[i].str("id")]?:return@mapNotNull null
                if(!RectF.intersects(s.first,screen))return@mapNotNull null;Leg(f.books[i],at,0f,s.first,s.second,f.covers[i])}}.orEmpty()
            if(legs.isEmpty()){otherwise();return@laid}
            // the covers leave the shelf's picture as their copies lift off it
            val o=IntArray(2);a.root.getLocationOnScreen(o);val c=Canvas(pic);val p=Paint().apply{color=a.bg};val m=a.d(10).toFloat()
            legs.forEach{l->c.drawRect(l.from.left-o[0]-m,l.from.top-o[1]-m,l.from.right-o[0]+m,l.from.bottom-o[1]+m,p)};img.invalidate()
            fly(a,legs,{k->img.alpha=1f-(k/.5f).coerceIn(0f,1f)},done)}}

    private fun fly(a:MainActivity,legs:List<Leg>,fade:(Float)->Unit,done:()->Unit){
        val o=IntArray(2);a.root.getLocationOnScreen(o)
        val layer=FrameLayout(a).apply{clipChildren=false;isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;translationZ=a.d(40).toFloat()}
        a.root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        val copies=legs.map{l->l.real.alpha=0f;CoverView(a,l.book,a.cover(l.book)).apply{softShadow=true}.also{layer.addView(it,FrameLayout.LayoutParams(l.to.width().toInt(),l.to.height().toInt()))}}
        fun frame(t:Long){legs.forEachIndexed{i,l->val k=curve.getInterpolation(((t-i*STAGGER).toFloat()/TIME).coerceIn(0f,1f));val v=copies[i]
            fun mix(x:Float,y:Float)=x+(y-x)*k
            val w=l.to.width();val h=l.to.height()
            v.pivotX=w/2f;v.pivotY=h/2f;v.scaleX=mix(l.from.width()/w,1f);v.scaleY=mix(l.from.height()/h,1f)
            v.translationX=mix(l.from.centerX(),l.to.centerX())-o[0]-w/2f;v.translationY=mix(l.from.centerY(),l.to.centerY())-o[1]-h/2f
            v.rotation=mix(l.fromTurn,l.toTurn)}}
        val total=TIME+STAGGER*(legs.size-1).coerceAtLeast(0)
        frame(0)
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=total;interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener{val k=it.animatedValue as Float;frame((k*total).toLong());fade(curve.getInterpolation(k))}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){legs.forEach{it.real.alpha=1f};a.root.removeView(layer);done()}})
            start()}}
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
    private val ink=if(a.light)0x000000 else 0xffffff
    private val label=a.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(14),0)}
    private var field:EditText?=null
    init{setWillNotDraw(false);clipChildren=false;isClickable=true;contentDescription="Новая полка"
        label.addView(IconView(a,"plus",a.muted),LinearLayout.LayoutParams(a.d(18),a.d(18)).apply{rightMargin=a.d(10)})
        label.addView(a.text("Новая полка",15f,a.muted,true))
        addView(label,LayoutParams(-1,-1))
        setOnClickListener{start()};a.press(this)}
    /** The place for a new shelf exists only while editing: it opens and fades in with the edit mode. */
    private var shown=if(a.shelvesEditing)1f else 0f
    private fun reveal(k:Float){shown=k;alpha=k;visibility=if(k<=0.001f)View.GONE else View.VISIBLE;requestLayout()}
    override fun onMeasure(w:Int,h:Int)=super.onMeasure(w,MeasureSpec.makeMeasureSpec((a.d(52)*shown).toInt(),MeasureSpec.EXACTLY))
    override fun onDraw(c:Canvas){val i=dash.strokeWidth/2;val r=a.d(16).toFloat()
        if(lit>0f){fill.color=(((.03f*lit)*255).toInt() shl 24) or ink;c.drawRoundRect(i,i,width-i,height-i,r,r,fill)}
        dash.color=((((.18f+.22f*lit)*(if(a.light).8f else 1f))*255).toInt() shl 24) or ink;c.drawRoundRect(i,i,width-i,height-i,r,r,dash)}
    private fun light(to:Float){android.animation.ValueAnimator.ofFloat(lit,to).apply{duration=220;addUpdateListener{lit=it.animatedValue as Float;invalidate()};start()}}
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
        val f=a.edit("","Название полки").apply{setSingleLine(true);textSize=17f;typeface=PolkaFonts.sans(600);setTextColor(a.fg);setHintTextColor(a.muted);background=null;setPadding(a.d(14),0,a.d(14),0);minHeight=0;minimumHeight=0
            gravity=Gravity.CENTER_VERTICAL;imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;alpha=0f
            setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commit();true}else false}
            setOnFocusChangeListener{v,has->if(!has)v.post{if(field===v)commit()}}}
        field=f;isClickable=false;light(1f)
        label.animate().alpha(0f).setDuration(120).start()
        addView(f,LayoutParams(-1,-1));f.animate().alpha(1f).setStartDelay(60).setDuration(160).start()
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
        label.animate().alpha(1f).setStartDelay(if(made)200 else 80).setDuration(240).start()
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

/** «Полки»: every shelf as a card with its first books fanned, in the saved order, then the place for a new shelf.
 *  «Изменить» turns the rows into cards that can be dragged into a new order, renamed in place, and removed —
 *  all in place: nothing on the page is rebuilt. */
internal fun MainActivity.shelfRows(){
    val byShelf=books.groupBy{it.str("shelf")};val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys.filter{it.isNotBlank()}).distinct()
    val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
    lateinit var editor:ShelfEditor
    /** One of your shelves. Its name can change while the page stays up, so everything asks for the current one. */
    fun own(name0:String,list:List<JSONObject>):ShelfRow{var cur=name0;lateinit var row:ShelfRow
        row=ShelfRow(this,"s:$name0",name0,sortBooks(list),{enterShelf(row.fan){shelf=cur}},{shelfActions(cur)},
            {next:String->val old=cur;cur=next;renameShelf(old,next,quiet=true)},{val gone=cur;editor.delete(row){removeShelfQuietly(gone)}})
        return row}
    val autos=(listOf("reading","want","done")+listOf("paused").filter{k->books.any{Library.status(it)==k}}).map{key->lateinit var row:ShelfRow
        row=ShelfRow(this,"a:$key",MainActivity.autoTitle(key),sortBooks(books.groupBy{Library.status(it)}[key].orEmpty()),{enterShelf(row.fan){auto=key}},{autoShelfActions(key)},null);row}
    val saved=prefs.optJSONArray("shelfOrder")?.strings().orEmpty()
    val rows=(manual.map{own(it,byShelf[it].orEmpty())}+autos).sortedBy{saved.indexOf(it.key).let{i->if(i<0)Int.MAX_VALUE else i}}
    rows.forEachIndexed{i,r->page.addView(r,LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=d(26)})}
    val slot=ShelfSlot(this){name->
        val taken=(prefs.optJSONArray("shelves")?.strings().orEmpty()+books.map{it.str("shelf")}).any{it.equals(name,true)}
        if(taken){Notice.short(this,Notice.Kind.SHELF,"Полка «$name» уже есть");false}
        else{prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+name).distinct()));savePreferences();editor.grow(own(name,emptyList()));true}}
    page.addView(slot,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(if(rows.isEmpty()||!shelvesEditing)0 else 10)});if(!shelvesEditing)slot.visibility=View.GONE
    editor=ShelfEditor(this,page,rows.toMutableList(),slot);shelfEditor=editor;addShelfRow={name->editor.grow(own(name,emptyList()),true)};removeShelfRow={name->editor.deleteKey("s:$name"){removeShelfQuietly(name)}}
    if(shelvesEditing){rows.forEach{it.setEditing(true,false)};slot.setEditing(true,false)}
}

/** Drag-to-reorder for the shelf cards in edit mode: the held card lifts and follows the finger, the others slide
 *  aside with a short glide to show where it will land; on release it settles into the gap and the order is saved. */
internal class ShelfEditor(private val a:MainActivity,private val page:LinearLayout,private val rows:MutableList<ShelfRow>,private val place:ShelfSlot?=null){
    private var held:ShelfRow?=null;private var from=0;private var target=0;private var startY=0f;private var slot=1
    private var startScroll=0;private var lastY=0f
    private val glide=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
    init{rows.forEach{r->r.handle.setOnTouchListener{_,e->touch(r,e);true}}}
    fun setEditing(on:Boolean){a.shelvesEditing=on;val delays=Stagger.delays(rows.size+1,30,ease=Ease.outQuad);rows.forEachIndexed{i,r->r.setEditing(on,true,delays[i])};place?.setEditing(on,true,delays.last())}
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
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=640;interpolator=android.view.animation.PathInterpolator(.42f,0f,.18f,1f);addUpdateListener{val k=it.animatedValue as Float;lp.height=(full*k).toInt().coerceAtLeast(1);lp.topMargin=(m*k).toInt();r.layoutParams=lp
                if(s1>s0)sv.scrollTo(0,(s0+(s1-s0)*k).toInt())}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){lp.height=LinearLayout.LayoutParams.WRAP_CONTENT;r.layoutParams=lp;r.settleMargin()}});start()}
        r.animate().alpha(1f).translationY(0f).setStartDelay(160).setDuration(520).setInterpolator(glide).start()}
    /** The card slides left and fades, then its place closes and the cards below glide up. */
    fun delete(r:ShelfRow,done:()->Unit){val i=rows.indexOf(r);if(i<0)return
        val finish={page.removeView(r);rows.remove(r);rows.forEach{it.settleMargin()};saveOrder();done()}
        if(reduced||!r.isAttachedToWindow){finish();return}
        // it melts away where it stands, then its place closes
        r.animate().alpha(0f).setStartDelay(40).setDuration(360).setInterpolator(android.view.animation.PathInterpolator(.4f,0f,.6f,1f)).start()
        val lp=r.layoutParams as LinearLayout.LayoutParams;val h=r.height;val m=lp.topMargin
        // the first card leaves: the one after it gives up its gap at the same pace, so nothing jumps at the end
        val next=if(i==0)rows.getOrNull(1) else null;val nlp=next?.layoutParams as? LinearLayout.LayoutParams;val nm=nlp?.topMargin?:0
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=300;duration=460;interpolator=android.view.animation.PathInterpolator(.42f,0f,.18f,1f)
            addUpdateListener{val k=it.animatedValue as Float;lp.height=(h*(1-k)).toInt();lp.topMargin=(m*(1-k)).toInt();r.layoutParams=lp;if(next!=null&&nlp!=null){nlp.topMargin=(nm*(1-k)).toInt();next.layoutParams=nlp}}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){finish()}});start()}}
    private fun touch(r:ShelfRow,e:MotionEvent){if(!r.editing)return
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{held=r;from=rows.indexOf(r);target=from;startY=e.rawY;lastY=e.rawY;startScroll=a.scroll.scrollY;slot=r.height+a.d(10)
                r.parent.requestDisallowInterceptTouchEvent(true);r.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                r.animate().scaleX(1.03f).scaleY(1.03f).translationZ(a.d(14).toFloat()).setDuration(180).setInterpolator(glide).start()
                a.scroll.postOnAnimation(edge)}
            MotionEvent.ACTION_MOVE->{lastY=e.rawY;follow()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val h=held?:return;held=null;a.scroll.removeCallbacks(edge)
                h.animate().translationY(((target-from)*slot).toFloat()).scaleX(1f).scaleY(1f).translationZ(0f).setDuration(260).setInterpolator(glide).withEndAction{drop(h)}.start()}
        }}
    /** The held card stays under the finger, including while the page scrolls under it. */
    private fun follow(){val h=held?:return;val dy=lastY-startY+(a.scroll.scrollY-startScroll);h.translationY=dy
        val t=(from+Math.round(dy/slot)).coerceIn(0,rows.lastIndex)
        if(t!=target){target=t;h.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            rows.forEachIndexed{j,o->if(o===h)return@forEachIndexed;val shift=when{j in (from+1)..target-> -slot;j in target until from->slot;else->0}.toFloat()
                if(o.translationY!=shift)o.animate().translationY(shift).setDuration(240).setInterpolator(glide).start()}}}
    /** Auto-scroll: holding the card near the top or bottom edge scrolls the page, faster the closer to the edge. */
    private val edge=object:Runnable{override fun run(){if(held==null)return
        val loc=IntArray(2);a.scroll.getLocationOnScreen(loc);val top=loc[1]+a.appInsets.top+a.d(8);val bottom=loc[1]+a.scroll.height-a.navigationBottom()-a.d(66)
        val zone=a.d(88).toFloat();val max=a.d(16).toFloat()
        val v=when{lastY<top+zone->-max*((top+zone-lastY)/zone).coerceIn(0f,1f);lastY>bottom-zone->max*((lastY-(bottom-zone))/zone).coerceIn(0f,1f);else->0f}
        if(v!=0f){val before=a.scroll.scrollY;a.scroll.scrollBy(0,v.toInt().let{if(it==0)(if(v>0)1 else -1) else it});if(a.scroll.scrollY!=before)follow()}
        a.scroll.postOnAnimation(this)}}
    /** Put the card where it landed, with no visible jump, and save the order. */
    private fun drop(h:ShelfRow){if(target!=from){rows.removeAt(from);rows.add(target,h);page.removeView(h);page.addView(h,target)}
        rows.forEach{it.animate().cancel();it.translationY=0f;it.settleMargin()}
        saveOrder()}
}

/** Top of «Полки»: only «Изменить» on the right. In edit mode it turns into a filled «Готово». */
internal fun MainActivity.shelvesTopBar():View{
    val bar=FrameLayout(this)
    bar.addView(text("Полки",26f,fg,true,serif=true),FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL).apply{leftMargin=d(2)})
    val label=text(if(shelvesEditing)"Готово" else "Редактировать",15f,fg,true).apply{gravity=Gravity.CENTER;setPadding(d(18),0,d(18),0);isClickable=true;isFocusable=true;contentDescription="Редактировать полки"}
    fun paint(){val on=shelvesEditing;label.setTextColor(if(on)bg else fg);label.background=if(on)shape(fg,20) else controlSurface(label,20f)}
    paint();shelvesTopBarLabel=label
    label.setOnClickListener{val on=!shelvesEditing;shelfEditor?.setEditing(on)
        label.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(110).withEndAction{label.text=if(on)"Готово" else "Редактировать";paint();label.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
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
