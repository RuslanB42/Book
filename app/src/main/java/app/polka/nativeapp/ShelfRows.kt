package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONObject

/**
 * One shelf: its name and how many books are on it, then the covers standing in a row behind our clear glass
 * shelf with screws (the same glass as the shelf tiles) — the glass covers the lower part of the books and stays
 * put while the covers scroll sideways behind it. Tapping the name opens the whole shelf.
 */
internal class ShelfRow(private val a:MainActivity,val key:String,private val title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?,private val rename:((String)->Unit)?):LinearLayout(a){
    private val coverW=a.d(88);private val coverH=a.d(132)
    private val scroller=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false}
    private val row=a.row().apply{gravity=Gravity.BOTTOM;clipChildren=false;clipToPadding=false;setPadding(a.d(20),a.d(6),a.d(20),a.d(10))}
    private val glass=MatteShelf()
    private var shown=0
    // ── edit mode: the header becomes a card with a drag handle, the covers fold away ──
    private val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40)}
    private val card=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(16).toFloat();alpha=0}
    private val name=a.text(title,17f,a.fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val pen=IconView(a,"pen",a.muted).apply{alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val tail=a.row().apply{gravity=Gravity.CENTER_VERTICAL}
    internal val handle=Grip()
    private val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    /** The covers live in this layer; on Android 13+ the glass bends it live on the GPU (a RenderEffect). */
    private val lensHost=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    // the shelf (its front, edge and shadow) lies inside the row's own bounds: drawn outside them it was cut off by
    // the page, so only a thin line of it showed
    private val stageFull=(if(list.isEmpty())coverH*3/4 else coverH)+a.d(16)+a.d(36)
    private var progress=0f
    private var anim:android.animation.ValueAnimator?=null
    private var field:EditText?=null
    internal var editing=false;private set
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        head.background=card;head.isClickable=true;head.contentDescription="$title, ${MainActivity.countBooks(list.size)}"
        head.setOnClickListener{if(editing){if(rename!=null)startRename()}else open()}
        actions?.let{f->head.setOnLongClickListener{if(editing)false else{f();true}}}
        head.addView(name,LayoutParams(-2,-2))
        head.addView(pen,LayoutParams(a.d(16),a.d(16)).apply{leftMargin=a.d(6)})
        head.addView(View(a),LayoutParams(0,1,1f))
        tail.addView(a.text(MainActivity.countBooks(list.size),13f,a.muted));tail.addView(IconView(a,"next",a.muted).apply{alpha=.7f},LayoutParams(a.d(18),a.d(18)).apply{leftMargin=a.d(4)})
        val end=FrameLayout(a);end.addView(tail,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_VERTICAL or Gravity.END));end.addView(handle,FrameLayout.LayoutParams(a.d(44),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END))
        head.addView(end,LayoutParams(-2,a.d(44)))
        addView(head,LayoutParams(-1,-2));a.space(this,6)
        if(list.isEmpty())row.addView(a.text("Пока пусто — добавьте сюда книги",13.5f,a.muted).apply{gravity=Gravity.TOP;setPadding(a.d(4),a.d(10),0,0)},LayoutParams(-2,coverH*3/4))
        more(7)
        scroller.addView(row);lensHost.addView(scroller,FrameLayout.LayoutParams(-1,-2));stage.addView(lensHost,FrameLayout.LayoutParams(-1,-1))
        scroller.setOnScrollChangeListener{_,_,_,_,_->glass.scrolled()}
        // The glass view is larger than the glass by its shadow; it never takes touches, so the row still scrolls.
        // the matte shelf lies behind the covers: its top surface starts under their lower part, so the books stand on it
        stage.addView(glass,0,FrameLayout.LayoutParams(-1,glass.full,Gravity.BOTTOM))
        addView(stage,LayoutParams(-1,stageFull).apply{leftMargin=-a.d(20);rightMargin=-a.d(20)})
        apply(0f)
    }
    /** Morph between the shelf and its edit card; [delay] staggers the rows. */
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){editing=on;if(!on)commitRename()
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
        tail.alpha=(1-e*2).coerceIn(0f,1f);tail.translationX=-a.d(10)*e
        handle.alpha=((e-.35f)/.65f).coerceIn(0f,1f);handle.translationX=a.d(14)*(1-e);handle.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE
        pen.alpha=if(rename==null)0f else ((e-.4f)/.6f).coerceIn(0f,1f)*.8f
        head.elevation=a.d(if(a.light)3 else 4)*e
        (layoutParams as? MarginLayoutParams)?.let{m->val idx=(parent as? ViewGroup)?.indexOfChild(this)?:0;val want=if(idx<=0)0 else (a.d(12)+(a.d(10)-a.d(12))*e).toInt();if(m.topMargin!=want){m.topMargin=want;layoutParams=m}}}
    /** Margins follow the new order after a drag. */
    fun settleMargin(){apply(progress)}
    // ── inline rename ──
    private fun startRename(){if(field!=null||!isAttachedToWindow||!editing)return
        val f=a.edit(title,"Название полки").apply{setSingleLine(true);textSize=17f;typeface=name.typeface;setTextColor(a.fg);background=null;setPadding(0,0,0,0)
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;setSelection(text.length)
            setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commitRename();true}else false}
            // never change the views from inside a focus change (the focus is moving through this very field):
            // the commit runs right after, on the next loop
            setOnFocusChangeListener{v,has->if(!has)v.post{if(field===v)commitRename()}}}
        field=f;val i=head.indexOfChild(name);name.visibility=View.GONE;head.addView(f,i,LayoutParams(0,-2,1f))
        f.alpha=0f;f.animate().alpha(1f).setDuration(160).start()
        f.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f,0)}
    private fun commitRename(){val f=field?:return;field=null;val next=f.text.toString().trim()
        f.onFocusChangeListener=null;f.setOnEditorActionListener(null)
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)
        if(f.hasFocus()){a.root.isFocusableInTouchMode=true;a.root.requestFocus()}
        (f.parent as? ViewGroup)?.removeView(f);name.visibility=View.VISIBLE
        // the shelves reload after a rename: let this tap finish first
        if(next.isNotBlank()&&next!=title){name.text=next;val r=rename;a.root.post{r?.invoke(next)}}}

    /** Six-dot grip; dragging it moves the shelf. */
    internal inner class Grip:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init{contentDescription="Переместить полку";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
        override fun onDraw(c:Canvas){p.color=a.muted;val r=a.d(1).toFloat()*1.6f;val gx=a.d(6).toFloat();val gy=a.d(6).toFloat()
            for(i in 0..1)for(j in -1..1)c.drawCircle(width/2f+(i-.5f)*gx,height/2f+j*gy,r,p)}
    }

    // Only the covers that fit on screen are built with the page (a 72-book shelf no longer builds 72 views
    // during the tab switch); the rest are appended as the row is scrolled toward its end.
    // The covers that fit on screen are built with the page; the rest are appended in small batches right after the
    // page has settled — never while the row is being flung (a relayout mid-fling made the scroll stutter back).
    private fun more(n:Int){val until=minOf(list.size,shown+n)
        for(i in shown until until){val b=list[i]
            val box=FrameLayout(a).apply{clipChildren=false;isClickable=true;contentDescription=b.str("title");setOnClickListener{a.detail(b,this)};a.press(this)}
            // One CoverView per book; when its picture arrives it only redraws (no view swap, no relayout of the row).
            val cover=CoverView(a,b,a.cover(b)).apply{softShadow=true};box.addView(cover,FrameLayout.LayoutParams(-1,-1))
            if(a.cover(b)==null&&b.optJSONObject("cover")!=null)a.requestCover(b){bmp->if(bmp!=null){cover.invalidate();glass.refresh()}}
            row.addView(box,LayoutParams(coverW,coverH).apply{if(i>0)leftMargin=a.d(12)})}
        shown=until;glass.refresh()}
    private val feed=object:Runnable{override fun run(){if(!isAttachedToWindow||shown>=list.size)return
        if(scroller.scrollX!=lastFeedX||busy()){lastFeedX=scroller.scrollX;postDelayed(this,120);return}
        more(8);if(shown<list.size)postDelayed(this,60)}}
    private var lastFeedX=-1
    private fun busy()=false
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(shown<list.size)postDelayed(feed,500)}
    override fun onDetachedFromWindow(){removeCallbacks(feed);super.onDetachedFromWindow()}

    private companion object{val shared=HashMap<String,Triple<Bitmap,Bitmap,ColorFilter?>>()}

    /** A matte wall shelf from edge to edge (approved design В3): the shelf's top surface, seen from a little above,
     *  with the books standing towards its back; then the front edge with a faint light line and a soft shadow below.
     *  No glass, no reflections. Drawn behind the covers. */
    private inner class MatteShelf:View(a){
        private val d=a.d(1).toFloat()
        /** How far the top surface reaches up behind the books, the visible depth in front of them, the edge, the shadow. */
        val behind=a.d(20);private val front=a.d(12);private val edge=a.d(14);private val shade=a.d(20)
        val full=behind+front+edge+shade
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{isDither=true}
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun refresh(){};fun scrolled(){}
        override fun onTouchEvent(e:MotionEvent)=false
        override fun onDraw(c:Canvas){val w=width.toFloat();val light=a.light
            val top=0f;val lip=(behind+front).toFloat();val bottom=lip+edge
            // top surface: darker at the back (in the books' shadow), lighter towards the front edge
            p.shader=LinearGradient(0f,top,0f,lip,if(light)0xffd6d6d2.toInt() else 0xff222226.toInt(),if(light)0xffeaeae6.toInt() else 0xff3c3c42.toInt(),Shader.TileMode.CLAMP)
            c.drawRect(0f,top,w,lip,p)
            // the front edge, a thin light line along its top
            p.shader=LinearGradient(0f,lip,0f,bottom,if(light)0xffcdcdc9.toInt() else 0xff414147.toInt(),if(light)0xffb8b8b4.toInt() else 0xff222226.toInt(),Shader.TileMode.CLAMP)
            c.drawRect(0f,lip,w,bottom,p);p.shader=null
            p.color=if(light)0x99ffffff.toInt() else 0x24ffffff;c.drawRect(0f,lip,w,lip+d,p)
            // the shadow the shelf casts on the wall below
            p.shader=LinearGradient(0f,bottom,0f,bottom+shade,if(light)0x26000000 else 0x99000000.toInt(),0,Shader.TileMode.CLAMP);c.drawRect(0f,bottom,w,bottom+shade,p);p.shader=null}
    }

    /** Matte glass shelf with screws, light to draw: every cover has a tiny pre-blurred copy (made once, cached);
     *  the pane paints those copies where the covers are — a handful of small bitmaps per frame, no snapshots of
     *  the row, no offscreen effects. Then a frosted tint, a soft top sheen, the rim and four screws. */
    private inner class Glass:View(a),ShelfGlassLink.Pane{
        val pad=a.d(22)
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val frostPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun refresh()=invalidate()
        fun scrolled()=invalidate()
        override fun onTouchEvent(e:MotionEvent)=false
        // ── a cover flying to / from the book card passes behind this glass (see ShelfGlassLink) ──
        override val home:View get()=this@ShelfRow
        override fun paneRectOnScreen():RectF?{if(!isAttachedToWindow)return null;val at=IntArray(2);getLocationOnScreen(at);return RectF(at[0]+pad.toFloat(),at[1]+pad.toFloat(),at[0]+width-pad.toFloat(),at[1]+height-pad.toFloat())}
        override fun ghostMoved()=invalidate()
        // Static parts, once per size/theme and shared by every shelf: soft shadow outside; tint, sheen, rim, screws over.
        private var under:Bitmap?=null;private var over:Bitmap?=null;private var staticKey=""
        private val clip=Path()
        private fun statics(){val light=a.light;val key="$width:$height:$light";val radius=a.d(9).toFloat()
            val g=RectF(pad.toFloat(),pad.toFloat(),(width-pad).toFloat(),(height-pad).toFloat())
            if(key!=staticKey||under==null){clip.reset();clip.addRoundRect(g,radius,radius,Path.Direction.CW)
                val m=ColorMatrix().apply{setSaturation(1.12f)};val k=if(light)1.05f else .82f;m.postConcat(ColorMatrix(floatArrayOf(k,0f,0f,0f,0f,0f,k,0f,0f,0f,0f,0f,k,0f,0f,0f,0f,0f,1f,0f)));frostPaint.colorFilter=ColorMatrixColorFilter(m)}
            if(key==staticKey&&under!=null)return;staticKey=key
            shared[key]?.let{(u,o,_)->under=u;over=o;return}
            val density=resources.displayMetrics.density
            under=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                p.color=if(light)0xfff2f2f0.toInt() else 0xff161617.toInt();p.setShadowLayer(if(light)18*density else 22*density,0f,if(light)5*density else 7*density,if(light)0x14000000 else 0x40000000);c.drawRoundRect(g,radius,radius,p);p.clearShadowLayer()}
            over=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                c.save();c.clipPath(clip)
                p.color=if(light)0x66ffffff else 0x0effffff;c.drawRect(g,p)
                p.shader=LinearGradient(0f,g.top,0f,g.top+8*density,if(light)0x4dffffff else 0x1fffffff,0x00ffffff,Shader.TileMode.CLAMP);c.drawRect(g.left,g.top,g.right,g.top+8*density,p);p.shader=null
                p.style=Paint.Style.STROKE;p.strokeWidth=density;p.color=if(light)0xccffffff.toInt() else 0x21ffffff
                val e=(radius-density*.5f).coerceAtLeast(0f);c.drawRoundRect(RectF(g.left+density*.5f,g.top+density*.5f,g.right-density*.5f,g.bottom-density*.5f),e,e,p);p.style=Paint.Style.FILL;c.restore()
                val size=9*density;val inset=density+radius*.55f+size/2
                listOf(g.left+inset to g.top+inset,g.right-inset to g.top+inset,g.left+inset to g.bottom-inset,g.right-inset to g.bottom-inset).forEachIndexed{i,(x,y)->ShelfScrew.draw(c,x,y,size,density,ShelfScrew.turns[i])}}
            shared[key]=Triple(under!!,over!!,null)
        }
        private val dst=RectF()
        /** A cover's frosted copy over [l,t,r,b] (glass coordinates); the copy carries its own soft margin. */
        private fun frost(c:Canvas,cover:CoverView,l:Float,t:Float,r:Float,b:Float){val f=FrostCache.get(cover)?:return
            val mx=(r-l)*FrostCache.MARGIN;val my=(b-t)*FrostCache.MARGIN_Y;dst.set(l-mx,t-my,r+mx,b+my);c.drawBitmap(f,null,dst,frostPaint)}
        override fun onDraw(c:Canvas){if(width<1||height<1)return;statics()
            under?.let{c.drawBitmap(it,0f,0f,null)}
            c.save();c.clipPath(clip)
            val ox=(scroller.left+row.left-scroller.scrollX-left).toFloat();val oy=(scroller.top+row.top-top).toFloat()
            val gl=pad.toFloat();val gr=(width-pad).toFloat()
            for(i in 0 until row.childCount){val box=row.getChildAt(i) as? ViewGroup?:continue;val cover=box.getChildAt(0) as? CoverView?:continue
                if(box.visibility!=View.VISIBLE||cover.visibility!=View.VISIBLE)continue
                // the box's press scale (about its centre) is followed too
                val cx=ox+box.left+box.width/2f;val cy=oy+box.top+box.height/2f;val hw=box.width*box.scaleX/2f;val hh=box.height*box.scaleY/2f
                if(cx+hw*1.6f<gl||cx-hw*1.6f>gr)continue
                frost(c,cover,cx-hw,cy-hh,cx+hw,cy+hh)}
            if(ShelfGlassLink.pane===this&&ShelfGlassLink.front<1f){ShelfGlassLink.ghost?.let{g->val at=IntArray(2);getLocationOnScreen(at);val r=ShelfGlassLink.ghostRect
                frostPaint.alpha=((1f-ShelfGlassLink.front)*255).toInt();frost(c,g,r.left-at[0],r.top-at[1],r.right-at[0],r.bottom-at[1]);frostPaint.alpha=255}}
            c.restore()
            over?.let{c.drawBitmap(it,0f,0f,null)}
        }
        override fun onAttachedToWindow(){super.onAttachedToWindow();ShelfGlassLink.register(this)}
        override fun onDetachedFromWindow(){ShelfGlassLink.unregister(this);under=null;over=null;staticKey="";super.onDetachedFromWindow()}
    }


}

/** «Полки»: every shelf as a row of covers on our glass shelf, in the saved order. «Изменить» turns the rows into
 *  cards that can be dragged into a new order and renamed in place. */
internal fun MainActivity.shelfRows(){
    val byShelf=books.groupBy{it.str("shelf")};val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys.filter{it.isNotBlank()}+REC_SHELF).distinct()
    val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
    data class Item(val key:String,val name:String,val list:List<JSONObject>,val open:()->Unit,val actions:(()->Unit)?,val rename:((String)->Unit)?)
    val all=manual.map{name->Item("s:$name",name,byShelf[name].orEmpty(),{pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;shelf=name;render(false,false)},{shelfActions(name)},{next:String->renameShelf(name,next)})}+
        listOf("reading","want","done").map{key->Item("a:$key",MainActivity.autoTitle(key),books.groupBy{Library.status(it)}[key].orEmpty(),{pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;auto=key;render(false,false)},null,null)}
    val saved=prefs.optJSONArray("shelfOrder")?.strings().orEmpty()
    val ordered=all.sortedBy{saved.indexOf(it.key).let{i->if(i<0)Int.MAX_VALUE else i}}
    val rows=ordered.map{ShelfRow(this,it.key,it.name,it.list.sortedByDescending{b->maxOf(b.optLong("openedAt"),b.optLong("addedAt"))},it.open,it.actions,it.rename)}
    rows.forEachIndexed{i,r->page.addView(r,LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=d(26)})}
    val editor=ShelfEditor(this,page,rows.toMutableList());shelfEditor=editor
    if(shelvesEditing)rows.forEach{it.setEditing(true,false)}
}

/** Drag-to-reorder for the shelf cards in edit mode: the held card lifts and follows the finger, the others slide
 *  aside with a short glide to show where it will land; on release it settles into the gap and the order is saved. */
internal class ShelfEditor(private val a:MainActivity,private val page:LinearLayout,private val rows:MutableList<ShelfRow>){
    private var held:ShelfRow?=null;private var from=0;private var target=0;private var startY=0f;private var slot=1
    private var startScroll=0;private var lastY=0f
    private val glide=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
    init{rows.forEach{r->r.handle.setOnTouchListener{_,e->touch(r,e);true}}}
    fun setEditing(on:Boolean){a.shelvesEditing=on;val delays=Stagger.delays(rows.size,30,ease=Ease.outQuad);rows.forEachIndexed{i,r->r.setEditing(on,true,delays[i])}}
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
        a.prefs.put("shelfOrder",org.json.JSONArray(rows.map{it.key}));a.savePreferences()}
}

/** Top of «Полки»: only «Изменить» on the right. In edit mode it turns into a filled «Готово». */
internal fun MainActivity.shelvesTopBar():View{
    val bar=FrameLayout(this)
    val label=text(if(shelvesEditing)"Готово" else "Изменить",15f,fg,true).apply{gravity=Gravity.CENTER;setPadding(d(18),0,d(18),0);isClickable=true;isFocusable=true;contentDescription="Изменить полки"}
    fun paint(){val on=shelvesEditing;label.setTextColor(if(on)bg else fg);label.background=if(on)shape(fg,20) else controlSurface(label,20f)}
    paint();shelvesTopBarLabel=label
    label.setOnClickListener{val on=!shelvesEditing;shelfEditor?.setEditing(on)
        label.animate().alpha(0f).scaleX(.92f).scaleY(.92f).setDuration(110).withEndAction{label.text=if(on)"Готово" else "Изменить";paint();label.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    press(label)
    bar.addView(label,FrameLayout.LayoutParams(-2,d(40),Gravity.END or Gravity.CENTER_VERTICAL))
    return bar
}
