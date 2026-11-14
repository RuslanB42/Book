package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * One shelf: its name and how many books are on it, then the covers standing in a row behind our clear glass
 * shelf with screws (the same glass as the shelf tiles) — the glass covers the lower part of the books and stays
 * put while the covers scroll sideways behind it. Tapping the name opens the whole shelf.
 */
internal class ShelfRow(private val a:MainActivity,var key:String,private var title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?,private val rename:((String)->Unit)?,private val remove:(()->Unit)?=null):LinearLayout(a){
    // The shelf shows only what fits, laid out evenly across the page (no sideways scrolling); the whole shelf opens
    // with a tap. Four covers on a phone, more on wider screens.
    private val gap=a.d(10)
    // at least four covers on a phone (approved «4 в ряд»), more on wider screens
    private val fit=run{val avail=a.resources.displayMetrics.widthPixels-a.d(40);((avail+gap)/(a.d(64)+gap)).coerceIn(4,8)}
    private val coverW=(a.resources.displayMetrics.widthPixels-a.d(40)-gap*(fit-1))/fit;private val coverH=(coverW*1.5f).toInt()
    private val scroller=object:HorizontalScrollView(a){override fun onInterceptTouchEvent(e:MotionEvent)=false;override fun onTouchEvent(e:MotionEvent)=false}.apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false}
    private val row=a.row().apply{gravity=Gravity.BOTTOM;clipChildren=false;clipToPadding=false;setPadding(a.d(20),a.d(6),a.d(20),a.d(10))}
    private val glass=MatteShelf()
    private val lip=ShelfLip()
    private var shown=0
    // ── edit mode: the header becomes a card with a drag handle, the covers fold away ──
    private val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40)}
    private val card=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(16).toFloat();alpha=0}
    private val name=a.text(title,17f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val pen=IconView(a,"pen",a.muted).apply{alpha=0f;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val tail=a.row().apply{gravity=Gravity.CENTER_VERTICAL}
    /** Edit mode: removes a shelf of the user's own (the books stay in the library). */
    private val trash=IconView(a,"trash",0xffff8a7a.toInt()).apply{alpha=0f;visibility=View.INVISIBLE;isClickable=true;contentDescription="Удалить полку";setPadding(a.d(10),a.d(10),a.d(10),a.d(10))}
    internal val handle=Grip()
    /** Delete asks right in the card: the bin grows into a red «Удалить»; a tap anywhere else takes it back. */
    private val pill=a.text("Удалить",13.5f,Color.WHITE,true).apply{gravity=Gravity.CENTER;setSingleLine(true);background=a.shape(0xffff5a4c.toInt(),16);visibility=View.GONE;isClickable=true;contentDescription="Удалить полку"}
    private var ask=0f;private var askAnim:android.animation.ValueAnimator?=null
    private val cardColor=if(a.light)Color.WHITE else 0xff242426.toInt()
    private val outside:(MotionEvent)->Unit={e->val r=Rect();if(!pill.getGlobalVisibleRect(r)||!r.contains(e.rawX.toInt(),e.rawY.toInt()))cancelAsk()}
    private val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    /** The covers live in this layer; on Android 13+ the glass bends it live on the GPU (a RenderEffect). */
    private val lensHost=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    // the shelf (its front, edge and shadow) lies inside the row's own bounds: drawn outside them it was cut off by
    // the page, so only a thin line of it showed
    private val stageFull=(if(list.isEmpty())coverH*3/4 else coverH)+a.d(6)+glass.full-glass.sink
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
        // the bin sits right before the grip, at the same place on every card
        remove?.let{f->trash.setOnClickListener{if(editing){commitRename();askDelete()}};a.press(trash);end.addView(trash,FrameLayout.LayoutParams(a.d(40),a.d(44),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(38)})
            pill.setOnClickListener{if(ask<.5f)return@setOnClickListener;a.touchWatchers.remove(outside);pill.isClickable=false
                pill.animate().scaleX(.94f).scaleY(.94f).setDuration(90).withEndAction{pill.animate().scaleX(1f).scaleY(1f).setDuration(140).start()}.start();f()}
            end.addView(pill,FrameLayout.LayoutParams(a.d(40),a.d(32),Gravity.CENTER_VERTICAL or Gravity.END).apply{rightMargin=a.d(40)})}
        head.addView(end,LayoutParams(-2,a.d(44)))
        addView(head,LayoutParams(-1,-2));a.space(this,6)
        if(list.isEmpty())row.addView(View(a),LayoutParams(1,coverH*3/4))
        more(fit)
        scroller.addView(row);lensHost.addView(scroller,FrameLayout.LayoutParams(-1,-2));stage.addView(lensHost,FrameLayout.LayoutParams(-1,-1))
        // a tap anywhere on the row opens the shelf itself (not the book under the finger)
        stage.isClickable=true;stage.contentDescription="Открыть полку $title";stage.setOnClickListener{if(!editing)open()}
        scroller.setOnScrollChangeListener{_,_,_,_,_->glass.scrolled()}
        // The glass view is larger than the glass by its shadow; it never takes touches, so the row still scrolls.
        // the matte shelf lies behind the covers: its top surface starts under their lower part, so the books stand on it
        stage.addView(glass,0,FrameLayout.LayoutParams(-1,-1));stage.addView(lip,FrameLayout.LayoutParams(-1,-1))
        addView(stage,LayoutParams(-1,stageFull).apply{leftMargin=-a.d(20);rightMargin=-a.d(20)})
        apply(0f)
    }
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
        tail.alpha=(1-e*2).coerceIn(0f,1f);tail.translationX=-a.d(10)*e
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
        if(f.hasFocus()){a.root.isFocusableInTouchMode=true;a.root.requestFocus()}
        (f.parent as? ViewGroup)?.removeView(f);name.visibility=View.VISIBLE
        pen.animate().cancel();if(editing&&rename!=null)pen.animate().alpha(.8f).setDuration(180).start()
        // the shelves reload after a rename: let this tap finish first
        if(next.isNotBlank()&&next!=title){name.text=next;title=next;if(key.startsWith("s:"))key="s:$next";head.contentDescription="$next, ${MainActivity.countBooks(list.size)}";settleBreath();val r=rename;a.root.post{r?.invoke(next)}}}
    /** Saved: the card breathes once. */
    private fun settleBreath(){if(a.prefs.str("motion")=="reduced")return;head.animate().scaleX(1.012f).scaleY(1.012f).setDuration(160).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).withEndAction{head.animate().scaleX(1f).scaleY(1f).setDuration(220).start()}.start()}

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
            val box=FrameLayout(a).apply{clipChildren=false;isClickable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
            // One CoverView per book; when its picture arrives it only redraws (no view swap, no relayout of the row).
            val cover=CoverView(a,b,a.cover(b)).apply{softShadow=true;shadowFade=0f};box.addView(cover,FrameLayout.LayoutParams(-1,-1))
            if(a.cover(b)==null&&b.optJSONObject("cover")!=null)a.requestCover(b){bmp->if(bmp!=null){cover.invalidate();glass.refresh()}}
            row.addView(box,LayoutParams(coverW,coverH).apply{if(i>0)leftMargin=gap})}
        shown=until;glass.refresh()}
    private val feed=object:Runnable{override fun run(){if(!isAttachedToWindow||shown>=list.size)return
        if(scroller.scrollX!=lastFeedX||busy()){lastFeedX=scroller.scrollX;postDelayed(this,120);return}
        more(8);if(shown<list.size)postDelayed(this,60)}}
    private var lastFeedX=-1
    private fun busy()=false
    override fun onAttachedToWindow(){super.onAttachedToWindow()}
    override fun onDetachedFromWindow(){removeCallbacks(feed);a.touchWatchers.remove(outside);super.onDetachedFromWindow()}

    private companion object{val shared=HashMap<String,Triple<Bitmap,Bitmap,ColorFilter?>>()}

    /** Approved «Графит, на уровне глаз»: we look straight at a matte graphite shelf; its front lip hides the books'
     *  feet, so they stand in its depth. One light from above-left: each book casts a soft shadow onto the wall
     *  (right and down), the wall darkens toward the shelf, the books' feet sit in its shade, and under the shelf a
     *  dark line falls into a long soft shadow. This back layer draws the wall; [ShelfLip] draws over the covers. */
    private inner class MatteShelf:View(a){
        private val d=a.d(1).toFloat()
        /** Below the covers' feet: the rest of the lip (9) and room for the shadow under it to fade out completely (40). */
        val full=a.d(49);val sink=0
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{isDither=true}
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun refresh(){invalidate();lip.invalidate()};fun scrolled()=refresh()
        override fun onTouchEvent(e:MotionEvent)=false
        override fun onDraw(c:Canvas){if(width<1)return;val light=a.light;val feet=height-full.toFloat()
            // the wall darkens toward the shelf, fading out toward both ends
            c.drawBitmap(ShelfShade.wall(a,width,light),0f,feet-a.d(70),p)
            // every book's soft shadow on the wall, baked once
            val sh=ShelfShade.book(a,coverW,coverH,light);val pad=ShelfShade.pad(a)
            forCovers{x,y->c.drawBitmap(sh,x-pad,y-pad,p)}}
    }
    /** Calls [f] with the top-left corner of every visible cover, in this stage's coordinates. */
    private inline fun forCovers(f:(Float,Float)->Unit){val ox=(scroller.left+row.left-scroller.scrollX).toFloat();val oy=(scroller.top+row.top).toFloat()
        for(i in 0 until row.childCount){val box=row.getChildAt(i) as? ViewGroup?:continue;if(box.getChildAt(0) !is CoverView||box.visibility!=View.VISIBLE)continue;f(ox+box.left,oy+box.top)}}
    /** Over the covers: their feet in the shelf's shade, then the thin lip that hides them, and the shelf's shadow below. */
    private inner class ShelfLip:View(a){
        private val d=a.d(1).toFloat()
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{isDither=true}
        private val path=Path()
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onTouchEvent(e:MotionEvent)=false
        override fun onDraw(c:Canvas){if(width<1)return;val light=a.light;val l=d*10;val rgt=width-d*10;val feet=height-glass.full.toFloat()
            // the books' lower part in the shelf's shade, the top a touch brighter
            p.shader=LinearGradient(0f,0f,0f,coverH.toFloat(),intArrayOf(0x0fffffff,0,if(light)0x24000000 else 0x4d000000),floatArrayOf(0f,.75f,1f),Shader.TileMode.CLAMP)
            forCovers{x,y->c.save();c.translate(x,y);c.drawRect(0f,0f,coverW.toFloat(),coverH.toFloat(),p);c.restore()};p.shader=null
            // the shadow under the shelf: it fades to nothing before the view ends, so nothing is ever cut
            val fy=feet-d*4;val h=d*13
            c.drawBitmap(ShelfShade.under(a,width,light),0f,fy+h-d,p)
            // the lip: matte stone, lit along the top edge, falling into shade at the foot, its ends a little darker
            path.reset();val t=d*1.5f;val b=d*2.5f;path.addRoundRect(RectF(l,fy,rgt,fy+h),floatArrayOf(t,t,t,t,b,b,b,b),Path.Direction.CW)
            c.save();c.clipPath(path)
            p.color=if(light)0xffd9d8d4.toInt() else 0xff38383c.toInt();c.drawRect(l,fy,rgt,fy+h,p)
            p.shader=ShelfShade.stone(light);c.save();c.translate(l,fy);c.drawRect(0f,0f,rgt-l,h,p);c.restore()
            p.shader=LinearGradient(0f,fy,0f,fy+h,intArrayOf(0,0,if(light)0x2e000000 else 0x73000000),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP);c.drawRect(l,fy,rgt,fy+h,p)
            // a soft highlight along the rounded top edge: brightest a little left of the middle (the light is
            // above-left), dissolving toward both ends and down the face
            p.shader=null;c.drawBitmap(ShelfShade.gleam(a,(rgt-l).toInt(),h.toInt(),light),l,fy,p)
            p.shader=LinearGradient(l,0f,rgt,0f,intArrayOf(0x33000000,0,0,0x3d000000),floatArrayOf(0f,.1f,.9f,1f),Shader.TileMode.CLAMP);c.drawRect(l,fy,rgt,fy+h,p)
            p.shader=null;c.restore()
}
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
        light(0f);isClickable=true}
    /** Same spacing as the shelves: 26dp apart, 10dp between the edit cards. */
    private var marginAnim:android.animation.ValueAnimator?=null
    fun setEditing(on:Boolean,animate:Boolean,delay:Long=0){if(!on)commit();val lp=layoutParams as? LinearLayout.LayoutParams?:return;val to=a.d(if(on)10 else 0);marginAnim?.cancel()
        if(!animate||a.prefs.str("motion")=="reduced"){lp.topMargin=to;layoutParams=lp;reveal(if(on)1f else 0f);return}
        val m0=lp.topMargin;val s0=shown;val s1=if(on)1f else 0f
        val sp=Spring.of(.1,460.0);marginAnim=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=sp.duration;startDelay=delay;interpolator=sp.interpolator;addUpdateListener{val k=it.animatedValue as Float;lp.topMargin=(m0+(to-m0)*k).toInt();layoutParams=lp;reveal((s0+(s1-s0)*k).coerceIn(0f,1f))};start()}}
}

/** «Полки»: every shelf as a row of covers on our glass shelf, in the saved order, then the place for a new shelf.
 *  «Изменить» turns the rows into cards that can be dragged into a new order, renamed in place, and removed —
 *  all in place: nothing on the page is rebuilt. */
internal fun MainActivity.shelfRows(){
    val byShelf=books.groupBy{it.str("shelf")};val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys.filter{it.isNotBlank()}).distinct()
    val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
    lateinit var editor:ShelfEditor
    val motion={MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF}
    /** One of your shelves. Its name can change while the page stays up, so everything asks for the current one. */
    fun own(name0:String,list:List<JSONObject>):ShelfRow{var cur=name0;lateinit var row:ShelfRow
        row=ShelfRow(this,"s:$name0",name0,sortBooks(list),{pendingMotion=motion();shelf=cur;render(false,false)},{shelfActions(cur)},
            {next:String->val old=cur;cur=next;renameShelf(old,next,quiet=true)},{val gone=cur;editor.delete(row){removeShelfQuietly(gone)}})
        return row}
    val autos=(listOf("reading","want","done")+listOf("paused").filter{k->books.any{Library.status(it)==k}}).map{key->ShelfRow(this,"a:$key",MainActivity.autoTitle(key),sortBooks(books.groupBy{Library.status(it)}[key].orEmpty()),{pendingMotion=motion();auto=key;render(false,false)},null,null)}
    val saved=prefs.optJSONArray("shelfOrder")?.strings().orEmpty()
    val rows=(manual.map{own(it,byShelf[it].orEmpty())}+autos).sortedBy{saved.indexOf(it.key).let{i->if(i<0)Int.MAX_VALUE else i}}
    rows.forEachIndexed{i,r->page.addView(r,LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=d(26)})}
    val slot=ShelfSlot(this){name->
        val taken=(prefs.optJSONArray("shelves")?.strings().orEmpty()+books.map{it.str("shelf")}).any{it.equals(name,true)}
        if(taken){Notice.short(this,Notice.Kind.SHELF,"Полка «$name» уже есть");false}
        else{prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+name).distinct()));savePreferences();editor.grow(own(name,emptyList()));true}}
    page.addView(slot,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(if(rows.isEmpty()||!shelvesEditing)0 else 10)});if(!shelvesEditing)slot.visibility=View.GONE
    editor=ShelfEditor(this,page,rows.toMutableList(),slot);shelfEditor=editor
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
    /** A new shelf grows out of the empty place, just above it: its height opens, it rises a little and fades in. */
    fun grow(r:ShelfRow){r.handle.setOnTouchListener{_,e->touch(r,e);true}
        val at=place?.let{page.indexOfChild(it)}?.takeIf{it>=0}?:page.childCount
        val lp=LinearLayout.LayoutParams(-1,-2).apply{if(at>0)topMargin=a.d(if(a.shelvesEditing)10 else 26)}
        page.addView(r,at,lp);rows.add(r);r.setEditing(a.shelvesEditing,false);saveOrder()
        if(reduced)return
        r.measure(View.MeasureSpec.makeMeasureSpec(page.width-page.paddingLeft-page.paddingRight,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val full=r.measuredHeight;val m=lp.topMargin
        lp.height=0;lp.topMargin=0;r.layoutParams=lp;r.alpha=0f;r.translationY=a.d(8).toFloat();r.scaleX=.97f;r.scaleY=.97f
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=glide;addUpdateListener{val k=it.animatedValue as Float;lp.height=(full*k).toInt().coerceAtLeast(1);lp.topMargin=(m*k).toInt();r.layoutParams=lp}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){lp.height=LinearLayout.LayoutParams.WRAP_CONTENT;r.layoutParams=lp;r.settleMargin()}});start()}
        r.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(60).setDuration(420).setInterpolator(glide).start()}
    /** The card slides left and fades, then its place closes and the cards below glide up. */
    fun delete(r:ShelfRow,done:()->Unit){val i=rows.indexOf(r);if(i<0)return
        val finish={page.removeView(r);rows.remove(r);rows.forEach{it.settleMargin()};saveOrder();done()}
        if(reduced||!r.isAttachedToWindow){finish();return}
        r.animate().translationX(-a.d(46).toFloat()).alpha(0f).scaleX(.96f).scaleY(.96f).setStartDelay(40).setDuration(300).setInterpolator(glide).start()
        val lp=r.layoutParams as LinearLayout.LayoutParams;val h=r.height;val m=lp.topMargin
        // the first card leaves: the one after it gives up its gap at the same pace, so nothing jumps at the end
        val next=if(i==0)rows.getOrNull(1) else null;val nlp=next?.layoutParams as? LinearLayout.LayoutParams;val nm=nlp?.topMargin?:0
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=200;duration=420;interpolator=glide
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

/** Baked parts of the eye-level shelf, made once per size and theme: every shade is a smooth function of the
 *  distance, computed pixel by pixel with a grain of noise — no blurred shapes (their edges were cut by the view)
 *  and no visible steps. */
internal object ShelfShade{
    private val cache=HashMap<String,Bitmap>()
    fun pad(a:MainActivity)=a.d(24).toFloat()
    private fun step(e0:Float,e1:Float,x:Float):Float{val t=((x-e0)/(e1-e0)).coerceIn(0f,1f);return t*t*(3-2*t)}
    /** A book's soft shadow on the wall: light from above-left, so it lies to the right and a little down. */
    fun book(a:MainActivity,w:Int,h:Int,light:Boolean):Bitmap=cache.getOrPut("b$w:$h:$light"){val d=a.d(1).toFloat();val pad=pad(a);val s=d*11
        SoftShade.bake((w+pad*2).toInt(),(h+pad*2).toInt(),if(light)0x281e14 else 0,if(light).20f else .62f){px,py->val x=px-pad;val y=py-pad
            step(d*8-s,d*8+s,x)*(1-step(w+d*7-s,w+d*7+s,x))*step(d*10-s,d*10+s*1.4f,y)*(1-step(h-d*2,h+d*7,y))}}
    /** Under the shelf: darkest right below it, then a long soft falloff that reaches nothing well before the edge. */
    fun under(a:MainActivity,w:Int,light:Boolean):Bitmap=cache.getOrPut("u$w:$light"){val d=a.d(1).toFloat();val l=d*10;val r=w-d*10
        SoftShade.bake(w,a.d(41),if(light)0x281e14 else 0,if(light).30f else .78f){x,y->val k=y/d
            val side=step(l-d*2,l+d*26+k*1.2f*d,x)*(1-step(r-d*26-k*1.2f*d,r+d*2,x))
            (.55f*kotlin.math.exp(-(k/3.2f)*(k/3.2f))+.45f*kotlin.math.exp(-(k/13f)*(k/13f)))*side*(1-step(d*30,d*40,y))}}
    /** The lip's highlight: a soft band just under its top edge, strongest left of the middle. */
    fun gleam(a:MainActivity,w:Int,h:Int,light:Boolean):Bitmap=cache.getOrPut("g$w:$h:$light"){val d=a.d(1).toFloat()
        SoftShade.bake(w,h,0xffffff,if(light).55f else .17f){x,y->val k=(y-d*1.4f)/(d*1.7f);val m=(x-w*.38f)/(w*.42f)
            kotlin.math.exp(-k*k)*(.3f+.7f*kotlin.math.exp(-m*m))*step(0f,d*26,x)*(1-step(w-d*26,w.toFloat(),x))}}
    /** The wall above the shelf, darker toward it. */
    fun wall(a:MainActivity,w:Int,light:Boolean):Bitmap=cache.getOrPut("w$w:$light"){val d=a.d(1).toFloat();val hh=d*70;val l=d*10;val r=w-d*10
        SoftShade.bake(w,a.d(70),if(light)0x3c3228 else 0,if(light).10f else .34f){x,y->val t=y/hh;t*t*step(l-d*6,l+d*40,x)*(1-step(r-d*40,r+d*6,x))}}
    /** Fine matte stone grain, tiled. */
    private val stones=HashMap<Boolean,BitmapShader>()
    fun stone(light:Boolean):BitmapShader=stones.getOrPut(light){val n=96;val px=IntArray(n*n);val rnd=java.util.Random(11)
        val low=FloatArray(n){rnd.nextFloat()}
        for(y in 0 until n)for(x in 0 until n){val v=(rnd.nextFloat()-.5f)*.9f+(low[(x*7+y*3)%n]-.5f)*.4f;val alpha=(kotlin.math.abs(v)*(if(light)26 else 34)).toInt().coerceIn(0,255)
            px[y*n+x]=if(v<0)Color.argb(alpha,0,0,0) else Color.argb(alpha,255,255,255)}
        BitmapShader(Bitmap.createBitmap(px,n,n,Bitmap.Config.ARGB_8888),Shader.TileMode.REPEAT,Shader.TileMode.REPEAT)}
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
