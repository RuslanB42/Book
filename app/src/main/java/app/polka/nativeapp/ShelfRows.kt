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
    private val glass=Glass()
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
    private val stageFull=(if(list.isEmpty())coverH*3/4 else coverH)+a.d(16)
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
        val glassH=(coverH*.42f).toInt()+a.d(8)
        stage.addView(glass,FrameLayout.LayoutParams(-1,glassH+glass.pad*2,Gravity.BOTTOM).apply{leftMargin=a.d(12)-glass.pad;rightMargin=a.d(12)-glass.pad;bottomMargin=-glass.pad})
        addView(stage,LayoutParams(-1,stageFull).apply{leftMargin=-a.d(20);rightMargin=-a.d(20)})
        apply(0f)
    }
    /** Morph between the shelf and its edit card; [delay] staggers the rows. */
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){editing=on;if(!on)commitRename()
        anim?.cancel();val to=if(on)1f else 0f
        if(!animate||a.prefs.str("motion")=="reduced"){apply(to);return}
        val from=progress
        anim=android.animation.ValueAnimator.ofFloat(from,to).apply{duration=460;startDelay=delay;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{apply(it.animatedValue as Float)};start()}}
    private fun apply(e:Float){progress=e
        val lp=stage.layoutParams;lp.height=(stageFull*(1-e)).toInt();stage.layoutParams=lp
        stage.alpha=(1-e*1.6f).coerceIn(0f,1f);stage.scaleY=1f-.08f*e;stage.pivotY=0f
        card.alpha=(e*255).toInt();val pad=(a.d(14)*e).toInt();head.setPadding(pad,(a.d(6)*e).toInt(),(a.d(4)*e).toInt(),(a.d(6)*e).toInt())
        tail.alpha=(1-e*2).coerceIn(0f,1f);tail.translationX=-a.d(10)*e
        handle.alpha=((e-.35f)/.65f).coerceIn(0f,1f);handle.translationX=a.d(14)*(1-e);handle.visibility=if(e>0f)View.VISIBLE else View.INVISIBLE
        pen.alpha=if(rename==null)0f else ((e-.4f)/.6f).coerceIn(0f,1f)*.8f
        head.elevation=a.d(if(a.light)3 else 4)*e
        (layoutParams as? MarginLayoutParams)?.let{m->val idx=(parent as? ViewGroup)?.indexOfChild(this)?:0;val want=if(idx<=0)0 else (a.d(26)+(a.d(10)-a.d(26))*e).toInt();if(m.topMargin!=want){m.topMargin=want;layoutParams=m}}}
    /** Margins follow the new order after a drag. */
    fun settleMargin(){apply(progress)}
    // ── inline rename ──
    private fun startRename(){if(field!=null)return
        val f=a.edit(title,"Название полки").apply{setSingleLine(true);textSize=17f;typeface=name.typeface;setTextColor(a.fg);background=null;setPadding(0,0,0,0)
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;setSelection(text.length)
            setOnEditorActionListener{_,act,_->if(act==android.view.inputmethod.EditorInfo.IME_ACTION_DONE){commitRename();true}else false}
            setOnFocusChangeListener{_,has->if(!has)commitRename()}}
        field=f;val i=head.indexOfChild(name);name.visibility=View.GONE;head.addView(f,i,LayoutParams(0,-2,1f))
        f.alpha=0f;f.animate().alpha(1f).setDuration(160).start()
        f.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(f,0)}
    private fun commitRename(){val f=field?:return;field=null;val next=f.text.toString().trim()
        (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)
        head.removeView(f);name.visibility=View.VISIBLE
        if(next.isNotBlank()&&next!=title){name.text=next;rename?.invoke(next)}}

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

    /** Clear glass shelf with screws. The books are seen through it directly — sharp, not redrawn, nothing shifted.
     *  Only along the top and bottom bevels the glass bends them, like the rounded edge of thick acrylic: thin strips
     *  of the row are snapshotted once (when covers change) and drawn through a smooth mesh, so the distortion has no
     *  steps; scrolling just slides those strips. */
    private inner class Glass:View(a),ShelfGlassLink.Pane{
        val pad=a.d(22)
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // its own opaque paint: [p] keeps the translucent rim colour from statics(), which faded the snapshot into a grey band
        private val meshPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val bevel=a.d(10).toFloat();private val reach=a.d(6).toFloat();private val seam=a.d(3).toFloat()
        private var topStrip:Bitmap?=null;private var bottomStrip:Bitmap?=null;private var stripScale=1f;private var queued=false;private var x0=0;private var x1=0
        // Android 13+: the covers' layer is bent live by the lens (LiquidGlass); this view only adds shadow, sheen, rim and screws.
        private val liquid get()=LiquidGlass.available
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun refresh(){if(liquid){return};if(queued)return;queued=true;postDelayed(settle,140)}
        // ── a cover flying to / from the book card passes behind this glass (see ShelfGlassLink) ──
        override val home:View get()=this@ShelfRow
        override val layer:View get()=lensHost
        override fun paneRectOnScreen():RectF?{if(!isAttachedToWindow||!liquid)return null;val at=IntArray(2);getLocationOnScreen(at);return RectF(at[0]+pad.toFloat(),at[1]+pad.toFloat(),at[0]+width-pad.toFloat(),at[1]+height-pad.toFloat())}
        private fun applyLens(){if(!liquid){LiquidGlass.apply(lensHost,null,0f,a.light,1f);return}
            // the pane in lensHost coordinates (both are children of the stage)
            val r=RectF((left+pad-lensHost.left).toFloat(),(top+pad-lensHost.top).toFloat(),(right-pad-lensHost.left).toFloat(),(bottom-pad-lensHost.top).toFloat())
            LiquidGlass.apply(lensHost,r,a.d(9).toFloat(),a.light,resources.displayMetrics.density)}
        /** Strips cover about three screens around the visible part; leaving that window re-snaps at once (a few ms). */
        fun scrolled(){if(liquid){return};val sx=scroller.scrollX;val w=scroller.width;if(topStrip!=null&&(sx<x0&&x0>0||sx+w>x1&&x1<row.width)&&!row.isLayoutRequested)capture();invalidate()}
        override fun onTouchEvent(e:MotionEvent)=false
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);if(changed){applyLens();refresh()}}
        private val settle=object:Runnable{override fun run(){if(!isAttachedToWindow){queued=false;return}
            if(row.isLayoutRequested||row.width<1||width<1){postDelayed(this,48);return}
            queued=false;capture();invalidate()}}
        /** Snapshot the two source bands of the row (a little beyond each bevel) at screen resolution. */
        private fun capture(){val gTop=pad.toFloat();val gBottom=(height-pad).toFloat()
            val sw=scroller.width.coerceAtLeast(1);x0=(scroller.scrollX-sw).coerceAtLeast(0);x1=(scroller.scrollX+2*sw).coerceAtMost(row.width).coerceAtLeast(x0+1)
            stripScale=1f
            val offY=(top-(scroller.top+row.top)).toFloat()
            fun shot(y0:Float,y1:Float):Bitmap{val w=x1-x0;val h=(y1-y0).toInt().coerceAtLeast(1)
                val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(bmp);c.drawFilter=PaintFlagsDrawFilter(0,Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                c.translate(-x0.toFloat(),-(y0+offY));row.draw(c);return bmp}
            val oldT=topStrip;val oldB=bottomStrip
            // each strip reaches [seam] px past its inner edge, so the seam is sampled from real pixels, not from the
            // bitmap border (which blended with transparency and drew a grey line across light covers)
            topStrip=shot(gTop-reach,gTop+bevel+seam);bottomStrip=shot(gBottom-bevel-seam,gBottom+reach)
            oldT?.recycle();oldB?.recycle()}
        // Mesh rows: bitmap rows are spread uniformly over the source band; their on-screen y solves y_src = f(y).
        private val rows=18
        private fun meshY(top:Boolean,src:Float,g0:Float):Float{
            // top bevel: f(y)=y−reach·(1−(y−g0)/bevel)², y∈[g0,g0+bevel]; bottom mirrors it. Bisection — f is monotonic.
            var lo=0f;var hi=bevel
            repeat(22){val m=(lo+hi)/2;val t=if(top)1-m/bevel else m/bevel;val f=if(top)m-reach*t*t else m+reach*t*t;if(f<src)lo=m else hi=m}
            return g0+(lo+hi)/2}
        private fun strip(c:Canvas,bmp:Bitmap,top:Boolean,dx:Float){
            val gTop=pad.toFloat();val gBottom=(height-pad).toFloat()
            val g0=if(top)gTop else gBottom-bevel;val src0=if(top)-reach else -seam;val srcH=bevel+reach+seam
            val l=dx+x0;val w=bmp.width.toFloat();val v=FloatArray((rows+1)*2*2)
            for(j in 0..rows){val src=src0+srcH*j/rows;val y=if(top&&src>=bevel||!top&&src<=0f)g0+src else meshY(top,src,g0).coerceIn(g0,g0+bevel)
                v[j*4]=l;v[j*4+1]=y;v[j*4+2]=l+w;v[j*4+3]=y}
            // only where the snapshot reaches; beyond it (mid-fling, for a frame) the real covers show plainly
            c.save();c.clipRect(maxOf(pad.toFloat(),l),g0,minOf((width-pad).toFloat(),l+w),g0+bevel)
            c.drawColor(a.bg)
            c.drawBitmapMesh(bmp,1,rows,v,0,null,0,meshPaint);c.restore()}
        // Static parts are rendered once per size/theme into bitmaps (soft shadow outside; tint, sheen, rim, screws over),
        // shared by every shelf.
        private var under:Bitmap?=null;private var over:Bitmap?=null;private var staticKey=""
        private val clip=Path()
        private fun statics(){val light=a.light;val key="$width:$height:$light";val radius=a.d(9).toFloat()
            val g=RectF(pad.toFloat(),pad.toFloat(),(width-pad).toFloat(),(height-pad).toFloat())
            if(key!=staticKey||under==null){clip.reset();clip.addRoundRect(g,radius,radius,Path.Direction.CW)}
            if(key==staticKey&&under!=null)return;staticKey=key
            shared[key]?.let{(u,o,_)->under=u;over=o;return}
            val density=resources.displayMetrics.density
            under=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                p.color=Color.BLACK;p.setShadowLayer(if(light)18*density else 22*density,0f,if(light)5*density else 7*density,if(light)0x14000000 else 0x40000000);c.drawRoundRect(g,radius,radius,p);p.clearShadowLayer()
                // clear glass: the shadow stays outside only
                p.xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR);c.drawRoundRect(g,radius,radius,p);p.xfermode=null}
            over=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                c.save();c.clipPath(clip)
                // body: a very light tint, a little brighter under the top edge
                p.shader=LinearGradient(0f,g.top,0f,g.bottom,intArrayOf(if(light)0x40ffffff else 0x1fffffff,if(light)0x14ffffff else 0x0affffff,if(light)0x0fd8eef0 else 0x0ac8e6ee,if(light)0x1fffffff else 0x10ffffff),floatArrayOf(0f,.25f,.7f,1f),Shader.TileMode.CLAMP);c.drawRect(g,p)
                // a soft diagonal reflection
                p.shader=LinearGradient(g.left+g.width()*.16f,g.top,g.left+g.width()*.30f,g.bottom,intArrayOf(0x00ffffff,if(light)0x2affffff else 0x14ffffff,0x00ffffff),null,Shader.TileMode.CLAMP);c.drawRect(g,p)
                // bevels catch the light: brighter along the top band, a darker lip at the bottom
                p.shader=LinearGradient(0f,g.top,0f,g.top+bevel,if(light)0x59ffffff else 0x2effffff,0x00ffffff,Shader.TileMode.CLAMP);c.drawRect(g.left,g.top,g.right,g.top+bevel,p)
                p.shader=LinearGradient(0f,g.bottom-bevel,0f,g.bottom,0x00000000,if(light)0x1a000000 else 0x33000000,Shader.TileMode.CLAMP);c.drawRect(g.left,g.bottom-bevel,g.right,g.bottom,p);p.shader=null
                p.style=Paint.Style.STROKE;p.strokeWidth=density;p.color=if(light)0xd9ffffff.toInt() else 0x2effffff
                val e=(radius-density*.5f).coerceAtLeast(0f);c.drawRoundRect(RectF(g.left+density*.5f,g.top+density*.5f,g.right-density*.5f,g.bottom-density*.5f),e,e,p);p.style=Paint.Style.FILL;c.restore()
                val size=9*density;val inset=density+radius*.55f+size/2
                listOf(g.left+inset to g.top+inset,g.right-inset to g.top+inset,g.left+inset to g.bottom-inset,g.right-inset to g.bottom-inset).forEachIndexed{i,(x,y)->ShelfScrew.draw(c,x,y,size,density,ShelfScrew.turns[i])}}
            shared[key]=Triple(under!!,over!!,null)
        }
        override fun onDraw(c:Canvas){if(width<1||height<1)return;statics()
            under?.let{c.drawBitmap(it,0f,0f,null)}
            val dx=(scroller.left+row.left-scroller.scrollX-left).toFloat()
            if(liquid){over?.let{c.drawBitmap(it,0f,0f,null)};return}
            c.save();c.clipPath(clip)
            topStrip?.takeIf{!it.isRecycled}?.let{strip(c,it,true,dx)}
            bottomStrip?.takeIf{!it.isRecycled}?.let{strip(c,it,false,dx)}
            c.restore()
            over?.let{c.drawBitmap(it,0f,0f,null)}
        }
        override fun onAttachedToWindow(){super.onAttachedToWindow();if(liquid)ShelfGlassLink.register(this);applyLens();refresh()}
        override fun onDetachedFromWindow(){ShelfGlassLink.unregister(this);removeCallbacks(settle);queued=false;topStrip?.recycle();bottomStrip?.recycle();topStrip=null;bottomStrip=null;under=null;over=null;staticKey="";super.onDetachedFromWindow()}
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
    fun setEditing(on:Boolean){a.shelvesEditing=on;rows.forEachIndexed{i,r->r.setEditing(on,true,i*28L)}}
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
