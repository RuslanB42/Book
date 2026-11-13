package app.polka.nativeapp

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Ratings shown as tiers: S is five stars, D is one. Dropping a book into another row changes its rating. */
internal object Tiers {
    val letters=listOf("S","A","B","C","D")
    val names=listOf("Любимые","Понравились","Неплохо","Так себе","Не понравились")
    fun of(book:JSONObject)=book.optInt("rating").let{if(it in 1..5)5-it else -1}
    /** Light and deep tone of each tier's letter. */
    /** Approved «Награды»: gold, silver, bronze, then tarnished metal and dark graphite — the order reads like medals. */
    fun tone(a:MainActivity,k:Int):Pair<Int,Int> =if(a.light)when(k){0->0xffd9a63a.toInt() to 0xffa87a20.toInt();1->0xff9aa0a8.toInt() to 0xff6b7078.toInt();2->0xffc98a55.toInt() to 0xff8e5428.toInt();3->0xff8a8f96.toInt() to 0xff5e636a.toInt();else->0xff6a6a70.toInt() to 0xff45454a.toInt()}
        else when(k){0->0xffffe39a.toInt() to 0xffe2a93a.toInt();1->0xfff4f5f7.toInt() to 0xffa9adb5.toInt();2->0xfff0b98a.toInt() to 0xffb06a3a.toInt();3->0xff8f949b.toInt() to 0xff5e636a.toInt();else->0xff6a6a70.toInt() to 0xff45454a.toInt()}
    /** Finished books of [year] (all of them when null), own order inside a tier first, then the latest finished. */
    fun books(model:ReadingStatistics,year:Int?)=model.finished.filter{year==null||it.date?.year==year}.map{it.book}
    fun sorted(list:List<JSONObject>)=list.sortedWith(compareBy<JSONObject>{it.optDouble("tierPos",Double.MAX_VALUE)}.thenByDescending{it.optLong("completedAt")})
    fun years(model:ReadingStatistics)=model.finished.mapNotNull{it.date?.year}.distinct().sortedDescending()
}

/** A tier letter: big serif, filled top to bottom with the tier's two tones and a soft glow of the same colour. */
internal class TierLetter(private val a:MainActivity,private val k:Int,size:Float,private val glow:Boolean=true):TextView(a){
    init{text=Tiers.letters[k];textSize=size;typeface=PolkaFonts.serif(a,true);includeFontPadding=false;gravity=Gravity.CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){super.onSizeChanged(w,h,ow,oh);val (c1,c2)=Tiers.tone(a,k)
        paint.shader=LinearGradient(0f,0f,0f,h.toFloat(),c1,c2,Shader.TileMode.CLAMP)
        if(glow&&!a.light)setShadowLayer(a.d(14).toFloat(),0f,0f,(c2 and 0xffffff) or (0x66 shl 24)) else setShadowLayer(0f,0f,0f,0)}
}

/** The tier screen's backdrop: the page colour with a warm golden light from the top. Baked once, like the goal road's. */
internal class TierBackdrop(private val a:MainActivity):android.graphics.drawable.Drawable(){
    private val p=Paint(Paint.FILTER_BITMAP_FLAG)
    override fun draw(c:Canvas){val b=bounds;c.drawColor(a.bg);val t=bake(a,b.width())?:return;c.drawBitmap(t,b.left.toFloat(),b.top.toFloat(),p)}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
    companion object{
        private var tex:Bitmap?=null;private var key=""
        fun prepare(a:MainActivity){val w=a.resources.displayMetrics.widthPixels;val k="$w${a.light}";if(k==key)return;a.work({bake(a,w)})}
        @Synchronized fun bake(a:MainActivity,w:Int):Bitmap?{if(w<=0)return null;val k="$w${a.light}";if(k==key&&tex!=null)return tex
            val rx=w*1.05f;val h=(rx*1.1f).toInt()
            val out=Glow.face(w,h,a.bg,if(a.light)0xfff5b52a.toInt() else 0xffe0a12a.toInt(),w*.5f,0f,rx*1.5f,if(a.light).12f else .16f,2.6f*1.7f*1.7f)?:return null;tex=out;key=k;return out}
    }
}

/** One tier on the full screen: the letter with its name on the left, the covers on the right, a hairline above. */
internal class TierRow(private val a:MainActivity,val k:Int,val covers:FlowRow):LinearLayout(a){
    private val hair=Paint().apply{color=if(a.light)0x14000000 else 0x10ffffff}
    private val band:InsetDrawable
    var lit=false;private set
    init{orientation=HORIZONTAL;clipChildren=false;clipToPadding=false;setWillNotDraw(false)
        setPadding(a.d(8),a.d(11),a.d(18),a.d(11));minimumHeight=a.d(100)
        val label=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,a.d(4),0,0)}
        if(k<5){label.addView(TierLetter(a,k,40f,false),LinearLayout.LayoutParams(-2,a.d(50)))
            label.addView(a.text(Tiers.names[k],10.5f,a.muted).apply{maxLines=1;gravity=Gravity.CENTER;setPadding(0,a.d(4),0,0)})}
        addView(label,LayoutParams(a.d(84),-2))
        addView(covers,LayoutParams(0,-2,1f).apply{marginStart=a.d(4);topMargin=a.d(2)})
        val (_,c2)=if(k<5)Tiers.tone(a,k) else (0xff8a8a90.toInt() to 0xff8a8a90.toInt())
        val g=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf((c2 and 0xffffff) or (0x26 shl 24),(c2 and 0xffffff) or (0x0a shl 24),(c2 and 0xffffff) or (0x05 shl 24))).apply{cornerRadius=a.d(18).toFloat();setStroke(a.d(1),(c2 and 0xffffff) or (0x40 shl 24))}
        band=InsetDrawable(g,a.d(8),a.d(2),a.d(8),a.d(2)).apply{alpha=0};background=band}
    fun light(on:Boolean,motion:Boolean){if(on==lit)return;lit=on;invalidate()
        if(!motion){band.alpha=if(on)255 else 0;return}
        android.animation.ValueAnimator.ofInt(band.alpha,if(on)255 else 0).apply{duration=if(on)200 else 260;addUpdateListener{band.alpha=it.animatedValue as Int}}.start()}
    override fun onDraw(c:Canvas){super.onDraw(c);if(k in 1..4&&!lit)c.drawRect(a.d(100).toFloat(),0f,(width-a.d(18)).toFloat(),a.d(1).toFloat(),hair)}
}

/**
 * Тир-лист — opened from its card in the statistics. Rows S…D are the ratings 5…1; a long press lifts a cover,
 * the row under the finger lights up in its colour and opens a place, letting go puts the book there and saves the
 * new rating. A short tap opens the book card. The share button makes a picture of the list.
 */
internal class TierListOverlay(private val a:MainActivity,private val model:ReadingStatistics,startYear:Int?,private val source:()->View?,private val onClose:()->Unit){
    private val motion get()=a.prefs.str("motion")!="reduced"
    private val years=Tiers.years(model)
    private var year:Int?=startYear?.takeIf{it in years}?:years.firstOrNull()
    private val frame=Frame()
    private val col=a.column()
    private val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;clipToPadding=false;isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(24))}
    private val page=a.column().apply{clipChildren=false;clipToPadding=false}
    // approved: the years side by side over the page, swiped like pages; the last one is «Все годы»
    private val tabs:List<Int?> =years.map<Int,Int?>{it}+listOf<Int?>(null)
    private val tabRow=a.line().apply{gravity=Gravity.BOTTOM}
    private val tabStrip=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(tabRow)}
    private val tabViews=mutableListOf<TextView>()
    private val pager=Pager()
    private val sideL=a.column().apply{clipChildren=false;clipToPadding=false}
    private val sideR=a.column().apply{clipChildren=false;clipToPadding=false}
    private var pageAnim:android.animation.ValueAnimator?=null
    private val countText=a.text("",14f,a.muted)
    private val rowsBox=a.column().apply{clipChildren=false;clipToPadding=false}
    private val rows=mutableListOf<TierRow>()
    private var closing=false
    private var share:FrameLayout?=null
    private var poster:Bitmap?=null
    private val shown=HashMap<String,Int>()
    // container transform from the statistics card, the same as the goal road's
    private val snapView=ImageView(a).apply{scaleType=ImageView.ScaleType.FIT_XY;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private var snap:Bitmap?=null;private var from:RectF?=null
    private val bounds=RectF();private var radius=0f;private var k=1f
    private var anim:android.animation.ValueAnimator?=null
    private fun run(f:Float,t:Float,ms:Long,i:android.animation.TimeInterpolator,end:()->Unit){anim?.cancel()
        anim=android.animation.ValueAnimator.ofFloat(f,t).apply{duration=ms;interpolator=i;addUpdateListener{morph(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(x:android.animation.Animator){if(!cancelled)end()}});start()}}
    /** One step of the morph: [p] 0 is the card, 1 the whole screen. */
    private fun morph(p:Float){k=p;val W=frame.width.takeIf{it>0}?.toFloat()?:a.root.width.toFloat();val H=frame.height.takeIf{it>0}?.toFloat()?:a.root.height.toFloat()
        val f=from?:RectF(0f,0f,W,H)
        bounds.set(f.left*(1-p),f.top*(1-p),f.right+(W-f.right)*p,f.bottom+(H-f.bottom)*p);radius=a.d(24)*(1-p);frame.invalidateOutline()
        val sc=bounds.width()/W;col.pivotX=0f;col.pivotY=0f;col.scaleX=sc;col.scaleY=sc;col.translationX=bounds.left;col.translationY=bounds.top
        col.alpha=((p-.12f)/.42f).coerceIn(0f,1f)
        val b=snap;if(b!=null){val ss=bounds.width()/b.width;snapView.pivotX=0f;snapView.pivotY=0f;snapView.scaleX=ss;snapView.scaleY=ss;snapView.translationX=bounds.left;snapView.translationY=bounds.top
            snapView.alpha=(1f-p/.32f).coerceIn(0f,1f)}}
    /** Where the card is now and a picture of it, for the morph. */
    private fun capture(){val v=source();frame.removeView(snapView);snapView.setImageDrawable(null);snap?.recycle();snap=null
        if(v==null||!v.isAttachedToWindow||v.width==0){from=null;return}
        val l=IntArray(2);v.getLocationInWindow(l);val o=IntArray(2);a.root.getLocationInWindow(o)
        from=RectF((l[0]-o[0]).toFloat(),(l[1]-o[1]).toFloat(),(l[0]-o[0]+v.width).toFloat(),(l[1]-o[1]+v.height).toFloat())
        snap=try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}catch(e:Throwable){null}
        snap?.let{snapView.setImageBitmap(it);frame.addView(snapView,FrameLayout.LayoutParams(it.width,it.height,Gravity.TOP or Gravity.START))}}

    private inner class Frame:FrameLayout(a){
        var lastX=0f;var lastY=0f;private var cancelled=false
        override fun dispatchTouchEvent(e:MotionEvent):Boolean{lastX=e.x;lastY=e.y
            if(drag!=null){if(!cancelled){cancelled=true;val c=MotionEvent.obtain(e).apply{action=MotionEvent.ACTION_CANCEL};super.dispatchTouchEvent(c);c.recycle()}
                dragEvent(e);return true}
            cancelled=false;return super.dispatchTouchEvent(e)}
    }

    fun show(){
        frame.isClickable=true;frame.setBackgroundColor(a.bg);frame.elevation=a.d(30).toFloat()
        frame.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
            if(bounds.isEmpty)o.setRect(0,0,v.width,v.height) else o.setRoundRect(bounds.left.roundToInt(),bounds.top.roundToInt(),bounds.right.roundToInt(),bounds.bottom.roundToInt(),radius)}}
        frame.clipToOutline=true
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),a.d(8),a.d(16),0)}
        fun round(kind:String,desc:String,click:()->Unit)=FrameLayout(a).apply{isClickable=true;contentDescription=desc;setOnClickListener{click()}
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(a.light)0x0d000000 else 0x14ffffff)}
            addView(Glyph(a,kind,a.fg),FrameLayout.LayoutParams(a.d(17),a.d(17),Gravity.CENTER));a.press(this)}
        bar.addView(round("down","Закрыть"){close()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
        bar.addView(a.text("ТИР-ЛИСТ",12.5f,a.muted,true).apply{letterSpacing=.06f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(round("share","Поделиться тир-листом"){openShare()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
        col.addView(bar)
        // the years over the page: the chosen one white, the others quiet; a swipe turns the page
        tabs.forEachIndexed{i,y->val t=if(y==null)a.text("Все годы",17f,a.muted,true) else a.text("$y",40f,a.muted,true,serif=true)
            t.includeFontPadding=false;t.isClickable=true;t.setOnClickListener{goTo(i)};tabViews.add(t)
            tabRow.addView(t,LinearLayout.LayoutParams(-2,-2).apply{marginEnd=a.d(20);if(y==null)bottomMargin=a.d(5)})}
        tabStrip.setPadding(a.d(22),0,a.d(22),0)
        col.addView(tabStrip,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})
        col.addView(countText.apply{setPadding(a.d(22),a.d(8),a.d(22),a.d(10))})
        page.addView(rowsBox)
        scroll.addView(page,ViewGroup.LayoutParams(-1,-2))
        pager.addView(sideL,FrameLayout.LayoutParams(-1,-1));pager.addView(sideR,FrameLayout.LayoutParams(-1,-1))
        pager.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        col.addView(pager,LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(col,FrameLayout.LayoutParams(-1,-1))
        a.followInsets(frame){i->col.setPadding(i.left,i.top,i.right,0);scroll.setPadding(0,0,0,i.bottom+a.d(28))}
        pager.post{offset(0f)}
        a.root.addView(frame,FrameLayout.LayoutParams(-1,-1))
        a.tierList=this
        bind(false)
        if(!motion)return
        capture();morph(0f);col.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        // start on the first drawn frame, so no step of the morph is skipped
        frame.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{frame.viewTreeObserver.removeOnPreDrawListener(this)
            run(0f,1f,520,PathInterpolator(.2f,0f,0f,1f)){col.setLayerType(View.LAYER_TYPE_NONE,null);frame.removeView(snapView);bounds.setEmpty();frame.invalidateOutline()};return true}})
    }

    private fun cell(b:JSONObject,small:Boolean=false):CoverView{
        val cv=CoverView(a,b,a.cover(b)).apply{softShadow=true;tag=b;contentDescription=b.str("title");isClickable=true;isLongClickable=true;if(small)alpha=.6f}
        cv.setOnClickListener{a.detail(b,cv)}
        cv.setOnLongClickListener{startDrag(cv,b);true}
        a.press(cv)
        a.observeBook(cv,b.str("id")){fresh->replaceJson(b,fresh);if(drag==null&&shown[b.str("id")]!=Tiers.of(b))frame.removeCallbacks(rebindTask).also{frame.postDelayed(rebindTask,120)}}
        return cv}
    private val rebindTask=Runnable{if(drag==null&&!closing)bind(true)}
    private fun cellSize(small:Boolean)=if(small)Pair(a.d(40),a.d(60)) else Pair(a.d(48),a.d(72))

    /** Lays the chosen year out: five rows and the tray of books without a rating. */
    private fun bind(animated:Boolean){
        val list=Tiers.books(model,year)
        countText.text=MainActivity.countBooks(list.size)
        paintTabs(index().toFloat())
        shown.clear();list.forEach{shown[it.str("id")]=Tiers.of(it)}
        fun build(){fill(rowsBox,list,true);sides()}
        if(animated&&motion){rowsBox.animate().cancel();rowsBox.animate().alpha(0f).setDuration(140).withEndAction{build();rowsBox.translationY=a.d(8).toFloat()
            rowsBox.animate().alpha(1f).translationY(0f).setDuration(340).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}.start()} else build()
    }
    /** Lays five rows (and the tray) into [box]; [live] ones can be dragged, the neighbours shown during a swipe are pictures. */
    private fun fill(box:LinearLayout,list:List<JSONObject>,live:Boolean){box.removeAllViews();if(live)rows.clear()
            for(k in 0..5){val small=k==5;val items=Tiers.sorted(list.filter{Tiers.of(it)==(if(k==5)-1 else k)})
                if(k==5&&items.isEmpty())continue
                val flow=FlowRow(a,a.d(if(small)8 else 7),a.d(8),true){motion}
                items.forEach{b->val (w,h)=cellSize(small);flow.addView(if(live)cell(b,small) else CoverView(a,b,a.cover(b)).apply{softShadow=true;if(small)alpha=.6f},ViewGroup.LayoutParams(w,h))}
                val row=TierRow(a,k,flow)
                if(k==5){row.minimumHeight=0;row.setPadding(a.d(14),a.d(12),a.d(14),a.d(14));(row.getChildAt(0)).visibility=View.GONE
                    (flow.layoutParams as LinearLayout.LayoutParams).apply{marginStart=0;topMargin=a.d(28)}
                    val tray=FrameLayout(a).apply{clipChildren=false;clipToPadding=false
                        background=GradientDrawable().apply{cornerRadius=a.d(22).toFloat();setStroke(a.d(1)+a.d(1)/2,if(a.light)0x26000000 else 0x22ffffff,a.d(5).toFloat(),a.d(4).toFloat())}}
                    tray.addView(a.text("ЕЩЁ НЕ ОЦЕНЕНЫ",11f,if(a.light)0xff5c5c62.toInt() else 0xffc9c9cf.toInt(),true).apply{letterSpacing=.08f},FrameLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(14);topMargin=a.d(12)})
                    tray.addView(row,FrameLayout.LayoutParams(-1,-2))
                    box.addView(tray,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=a.d(16);rightMargin=a.d(16);topMargin=a.d(14)})}
                else box.addView(row,LinearLayout.LayoutParams(-1,-2))
                if(live)rows.add(row)}
    }

    // ───── years as pages ─────
    private fun index()=tabs.indexOf(year).coerceAtLeast(0)
    /** The neighbouring years, ready to slide in. */
    private fun sides(){val i=index()
        tabs.getOrNull(i-1).let{if(i>0)fill(sideL,Tiers.books(model,it),false) else sideL.removeAllViews()}
        tabs.getOrNull(i+1).let{if(i<tabs.size-1)fill(sideR,Tiers.books(model,it),false) else sideR.removeAllViews()}}
    /** [px] is how far the page is pulled: left is the next year. */
    private fun offset(px:Float){val w=pager.width.toFloat().coerceAtLeast(1f)
        scroll.translationX=px;sideL.translationX=px-w;sideR.translationX=px+w
        paintTabs(index()-px/w)}
    /** Colours the years for a position between pages and keeps the chosen one in view. */
    private fun paintTabs(at:Float){val dim=if(a.light)0xffc7c7cc.toInt() else 0xff4a4a4e.toInt()
        tabViews.forEachIndexed{i,t->val near=(1f-abs(at-i)).coerceIn(0f,1f);t.setTextColor(androidx.core.graphics.ColorUtils.blendARGB(dim,a.fg,near))}
        val lo=at.toInt().coerceIn(0,tabViews.size-1);val hi=(lo+1).coerceAtMost(tabViews.size-1);val f=at-lo
        val x=tabViews[lo].left+(tabViews[hi].left-tabViews[lo].left)*f
        // the chosen year stays at the left edge, the strip follows the swipe
        if(tabViews[lo].width>0)tabStrip.scrollTo(x.toInt().coerceAtLeast(0),0)}
    /** Turns to page [i]: slides there when it is next door, otherwise changes in place. */
    private fun goTo(i:Int,fromPx:Float=0f,velocity:Float=0f){if(closing||drag!=null)return;val cur=index();if(i==cur&&fromPx==0f)return
        if(abs(i-cur)>1||!motion){year=tabs[i];scroll.scrollTo(0,0);offset(0f);bind(motion);return}
        val w=pager.width.toFloat();val to=if(i>cur)-w else if(i<cur)w else 0f
        pageAnim?.cancel();pageAnim=android.animation.ValueAnimator.ofFloat(fromPx,to).apply{duration=if(velocity>0f)(abs(to-fromPx)/velocity*1000f).toLong().coerceIn(160L,340L) else 340L
            interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{offset(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(x:android.animation.Animator){if(cancelled)return
                    if(i!=cur){year=tabs[i];scroll.scrollTo(0,0);bind(false)};offset(0f)}});start()}}
    /** The page area: a sideways swipe pulls the neighbouring year in. */
    private inner class Pager:FrameLayout(a){
        private var x0=0f;private var y0=0f;private var swiping=false;private var vt:VelocityTracker?=null
        private val slop=ViewConfiguration.get(a).scaledTouchSlop
        init{clipChildren=true}
        private fun pull(dx:Float):Float{val i=index();return if((dx>0&&i==0)||(dx<0&&i==tabs.size-1))dx*.25f else dx}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{if(drag!=null||tabs.size<2||closing)return false
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{x0=e.x;y0=e.y;swiping=false;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e)}
                MotionEvent.ACTION_MOVE->{vt?.addMovement(e);val dx=e.x-x0;val dy=e.y-y0
                    if(abs(dx)>slop&&abs(dx)>abs(dy)*1.3f){swiping=true;x0=e.x-(if(dx>0)slop else -slop).toFloat();pageAnim?.cancel();parent?.requestDisallowInterceptTouchEvent(true)}}}
            return swiping}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(drag!=null)return false
            vt?.addMovement(e)
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{x0=e.x;y0=e.y;swiping=false;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e);return true}
                MotionEvent.ACTION_MOVE->{val dx=e.x-x0;if(!swiping&&abs(dx)>slop&&abs(dx)>abs(e.y-y0)*1.3f){swiping=true;x0=e.x}
                    if(swiping)offset(pull(e.x-x0))}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(swiping){vt?.computeCurrentVelocity(1000);val v=vt?.xVelocity?:0f;val px=scroll.translationX;val i=index()
                    val fling=abs(v)>a.d(600)
                    val target=when{(px<-width*.3f||(fling&&v<0&&px<0))&&i<tabs.size-1->i+1;(px>width*.3f||(fling&&v>0&&px>0))&&i>0->i-1;else->i}
                    goTo(target,px,abs(v))};swiping=false;vt?.recycle();vt=null}}
            return true}
    }

    // ───── dragging ─────
    private class Drag(val book:JSONObject,val float:CoverView,val hole:View,val dx:Float,val dy:Float,var row:TierRow,val from:Int)
    private var drag:Drag?=null
    private fun where(v:View):RectF{val l=IntArray(2);v.getLocationInWindow(l);val f=IntArray(2);frame.getLocationInWindow(f)
        val x=(l[0]-f[0]).toFloat();val y=(l[1]-f[1]).toFloat();return RectF(x,y,x+v.width,y+v.height)}
    private fun startDrag(cv:CoverView,b:JSONObject){if(drag!=null||closing||share!=null)return
        val flow=cv.parent as? FlowRow?:return;val row=rows.firstOrNull{it.covers===flow}?:return
        val r=where(cv);val idx=flow.indexOfChild(cv)
        val (w,h)=cellSize(false)
        val hole=View(a)
        flow.removeView(cv);flow.addView(hole,idx,ViewGroup.LayoutParams(if(row.k==5)a.d(40) else w,if(row.k==5)a.d(60) else h))
        val fl=CoverView(a,b,a.cover(b)).apply{softShadow=true;elevation=a.d(16).toFloat();pivotX=r.width()/2;pivotY=r.height()/2}
        frame.addView(fl,FrameLayout.LayoutParams(r.width().toInt(),r.height().toInt()));fl.translationX=r.left;fl.translationY=r.top
        val sc=if(row.k==5)1.45f else 1.2f
        if(motion)fl.animate().scaleX(sc).scaleY(sc).rotation(-4f).setDuration(180).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start() else {fl.scaleX=sc;fl.scaleY=sc}
        frame.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        drag=Drag(b,fl,hole,frame.lastX-r.left,frame.lastY-r.top,row,row.k)
        if(row.k<5)row.light(true,motion)
        frame.postOnAnimation(edgeScroll)
    }
    private fun dragEvent(e:MotionEvent){val d=drag?:return
        when(e.actionMasked){
            MotionEvent.ACTION_MOVE->{d.float.translationX=e.x-d.dx;d.float.translationY=e.y-d.dy;aim(e.x,e.y)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->drop()}}
    /** Finds the row under the finger and the place in it, and moves the empty place there. */
    private fun aim(x:Float,y:Float){val d=drag?:return
        val target=rows.firstOrNull{val r=where(if(it.k==5)it.parent as View else it);y>=r.top&&y<=r.bottom}?:return
        val flow=target.covers;var idx=0
        for(i in 0 until flow.childCount){val ch=flow.getChildAt(i);if(ch===d.hole)continue;val r=where(ch)
            if(y>r.bottom+a.d(4)||(y>=r.top-a.d(4)&&x>r.centerX()))idx++}
        if(target!==d.row){d.row.light(false,motion);d.row=target;if(target.k<5)target.light(true,motion);frame.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            val small=target.k==5;d.float.animate().scaleX(if(small)1.45f else 1.2f).scaleY(if(small)1.45f else 1.2f).setDuration(160).start()}
        val cur=d.hole.parent as? FlowRow
        if(cur!==flow||cur.indexOfChild(d.hole)!=idx){cur?.removeView(d.hole);val (w,h)=cellSize(target.k==5)
            flow.addView(d.hole,idx.coerceAtMost(flow.childCount),ViewGroup.LayoutParams(w,h))}}
    /** Near the top or bottom edge the list scrolls under the lifted cover. */
    private val edgeScroll=object:Runnable{override fun run(){if(drag==null)return
        val r=where(scroll);val y=frame.lastY;val edge=a.d(90).toFloat();val top=r.top+edge;val bottom=r.bottom-edge
        val v=when{y<top->-((top-y)/edge).coerceIn(0f,1f)*a.d(14);y>bottom->((y-bottom)/edge).coerceIn(0f,1f)*a.d(14);else->0f}
        if(v!=0f){scroll.scrollBy(0,v.toInt());aim(frame.lastX,frame.lastY)}
        frame.postOnAnimation(this)}}
    private fun drop(){val d=drag?:return
        val flow=d.hole.parent as? FlowRow;val target=d.row
        val r=where(d.hole)
        fun settle(){val f=d.hole.parent as? FlowRow;val i=f?.indexOfChild(d.hole)?:-1
            f?.removeView(d.hole);frame.removeView(d.float);d.row.light(false,motion);drag=null
            if(f==null||i<0){bind(false);return}
            val small=target.k==5;val (w,h)=cellSize(small);f.addView(cell(d.book,small),i,ViewGroup.LayoutParams(w,h))
            commit(d.book,target,f)}
        if(flow==null){settle();return}
        if(!motion){settle();return}
        val small=target.k==5;val w=d.float.width.coerceAtLeast(1).toFloat();val hgt=d.float.height.coerceAtLeast(1).toFloat();val sw=r.width()/w
        // the scale pivots on the middle, so aim the middle of the cover at the middle of its place
        d.float.animate().translationX(r.left-(w-r.width())/2).translationY(r.top-(hgt-r.height())/2).scaleX(sw).scaleY(sw).rotation(0f).alpha(if(small).6f else 1f)
            .setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).withEndAction{settle()}.start()
    }
    /** Saves the new rating and the order inside the row the book landed in. */
    private fun commit(book:JSONObject,row:TierRow,flow:FlowRow){
        val before=Tiers.of(book);val now=if(row.k==5)-1 else row.k
        if(now!=before){if(now<0)book.remove("rating") else book.put("rating",5-now)}
        shown[book.str("id")]=now
        for(i in 0 until flow.childCount){val b=flow.getChildAt(i).tag as? JSONObject?:continue
            if(b.optDouble("tierPos",-1.0)!=i.toDouble()||b===book){b.put("tierPos",i.toDouble());a.saveBook(b){}}}
        if(now!=before)frame.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** The main screen is rebuilt when a book is saved (a rating set here): the list stays open on top of it. */
    fun reattach(){if(closing||frame.parent===a.root)return;(frame.parent as? ViewGroup)?.removeView(frame);a.root.addView(frame,FrameLayout.LayoutParams(-1,-1))}
    fun back(){if(share!=null)closeShare() else close()}
    fun close(){if(closing)return;closing=true;closeShare()
        val d=drag;if(d!=null){frame.removeView(d.float);drag=null}
        fun done(){(frame.parent as? ViewGroup)?.removeView(frame);if(a.tierList===this)a.tierList=null;poster?.recycle();poster=null;snapView.setImageDrawable(null);snap?.recycle();snap=null}
        // the card under the list is brought up to date first, so the list folds back into the new picture
        onClose()
        if(!motion){done();return}
        frame.post{capture();bounds.set(0f,0f,frame.width.toFloat(),frame.height.toFloat());col.setLayerType(View.LAYER_TYPE_HARDWARE,null);morph(k)
            run(k,0f,420,PathInterpolator(.3f,0f,.1f,1f)){done()}}}

    // ───── share picture ─────
    private fun openShare(){if(share!=null||drag!=null)return
        val list=Tiers.books(model,year).filter{Tiers.of(it)>=0}
        val layer=FrameLayout(a).apply{setBackgroundColor(if(a.light)0xe6f2f2f0.toInt() else 0xe608080a.toInt());isClickable=true;setOnClickListener{closeShare()}}
        val box=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
        val bmp=TierPoster.render(a,year,list);poster?.recycle();poster=bmp
        val img=ImageView(a).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.FIT_CENTER;elevation=a.d(16).toFloat();clipToOutline=true;isClickable=true;contentDescription="Тир-лист"
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,a.d(22).toFloat())}}}
        val maxW=a.resources.displayMetrics.widthPixels-a.d(48);val maxH=(frame.height*.66f).toInt()
        val k=min(maxW.toFloat()/bmp.width,maxH.toFloat()/bmp.height)
        box.addView(img,LinearLayout.LayoutParams((bmp.width*k).toInt(),(bmp.height*k).toInt()))
        val acts=a.line().apply{gravity=Gravity.CENTER}
        fun act(label:String,primary:Boolean,click:()->Unit)=a.text(label,16f,if(primary)a.bg else a.fg,true).apply{gravity=Gravity.CENTER;isClickable=true
            background=GradientDrawable().apply{setColor(if(primary)a.fg else if(a.light)0x14000000 else 0x1affffff);cornerRadius=a.d(24).toFloat()};setOnClickListener{click()};a.press(this)}
        acts.addView(act("Сохранить",false){save()},LinearLayout.LayoutParams(0,a.d(48),1f))
        acts.addView(act("Отправить",true){send()},LinearLayout.LayoutParams(0,a.d(48),1f).apply{marginStart=a.d(10)})
        box.addView(acts,LinearLayout.LayoutParams((bmp.width*k).toInt(),-2).apply{topMargin=a.d(20)})
        layer.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        frame.addView(layer,FrameLayout.LayoutParams(-1,-1));share=layer
        if(motion){layer.alpha=0f;layer.animate().alpha(1f).setDuration(220).start()
            img.translationY=a.d(24).toFloat();img.alpha=0f
            img.animate().translationY(0f).alpha(1f).setStartDelay(60).setDuration(520).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}
    }
    private fun closeShare(){val layer=share?:return;share=null
        if(motion)layer.animate().alpha(0f).setDuration(180).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}
    private fun fileName()="Glyph-tier-${year?:"all"}.png"
    private fun send(){val bmp=poster?:return;val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,fileName())
        a.work({file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}){
            val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Тир-лист",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться"))}}
    private fun save(){val bmp=poster?:return
        if(Build.VERSION.SDK_INT<29){send();return}
        val name=fileName()
        if(Build.VERSION.SDK_INT>=29)a.work({val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Glyph");put(MediaStore.Images.Media.IS_PENDING,1)}
            val r=a.contentResolver;val uri=r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("no uri")
            r.openOutputStream(uri)?.use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);r.update(uri,values,null,null)},
            failed={Notice.short(a,Notice.Kind.ERROR,"Не получилось сохранить")}){Notice.short(a,Notice.Kind.OK,"Сохранено в галерею");closeShare()}}
}

/** The picture to share: the title, the count and every non-empty tier with its covers, on the same golden backdrop. */
internal object TierPoster{
    fun render(a:MainActivity,year:Int?,books:List<JSONObject>):Bitmap{
        val W=1080;val pad=64f;val labelW=150f;val cw=118f;val ch=177f;val gap=16f
        val perLine=max(1,((W-pad*2-labelW+gap)/(cw+gap)).toInt())
        val groups=(0..4).map{k->Tiers.sorted(books.filter{Tiers.of(it)==k})}.withIndex().filter{it.value.isNotEmpty()}
        val heights=groups.map{g->val lines=(g.value.size+perLine-1)/perLine;max(150f,lines*ch+(lines-1)*gap)+48f}
        val top=330f;val H=(top+heights.sum()+pad+40f).toInt().coerceAtLeast(720)
        val bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
        c.drawColor(a.bg)
        Glow.face(W,H,a.bg,if(a.light)0xfff5b52a.toInt() else 0xffe0a12a.toInt(),W*.5f,0f,W*1.6f,if(a.light).12f else .16f,2.6f*1.7f*1.7f)?.let{c.drawBitmap(it,0f,0f,null);it.recycle()}
        val tp=Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface=PolkaFonts.sans(600);tp.textSize=30f;tp.letterSpacing=.08f;tp.color=a.muted;c.drawText("ТИР-ЛИСТ",pad,130f,tp);tp.letterSpacing=0f
        tp.typeface=PolkaFonts.serif(a,true);tp.textSize=74f;tp.color=a.fg;c.drawText(if(year!=null)"Мой $year в книгах" else "Мои книги",pad,222f,tp)
        tp.typeface=PolkaFonts.sans(400);tp.textSize=34f;tp.color=a.muted;c.drawText(MainActivity.countBooks(books.size),pad,278f,tp)
        tp.typeface=PolkaFonts.display(600);tp.textSize=30f;tp.color=a.muted;tp.textAlign=Paint.Align.RIGHT;c.drawText("Glyph",W-pad,130f,tp);tp.textAlign=Paint.Align.LEFT
        val hair=Paint().apply{color=if(a.light)0x14000000 else 0x12ffffff}
        var y=top
        groups.forEachIndexed{gi,g->val k=g.index;val h=heights[gi]
            if(gi>0)c.drawRect(pad+labelW,y,W-pad,y+2f,hair)
            val (c1,c2)=Tiers.tone(a,k)
            tp.typeface=PolkaFonts.serif(a,true);tp.textSize=104f;tp.textAlign=Paint.Align.CENTER
            tp.shader=LinearGradient(0f,y+40f,0f,y+150f,c1,c2,Shader.TileMode.CLAMP);c.drawText(Tiers.letters[k],pad+labelW/2-14f,y+24f+104f*.78f+20f,tp);tp.shader=null;tp.textAlign=Paint.Align.LEFT
            g.value.forEachIndexed{i,b->val line=i/perLine;val col=i%perLine
                val x=pad+labelW+col*(cw+gap);val cy=y+24f+line*(ch+gap)
                val cv=CoverView(a,b,a.cover(b)).apply{softShadow=true};cv.measure(View.MeasureSpec.makeMeasureSpec(cw.toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ch.toInt(),View.MeasureSpec.EXACTLY))
                cv.layout(0,0,cw.toInt(),ch.toInt());c.save();c.translate(x,cy);cv.draw(c);c.restore()}
            y+=h}
        return bmp
    }
}
