package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Where the pages of a page book come from: unpacked pictures or a PDF rendered on demand. */
internal abstract class PageSource{
    abstract val count:Int
    abstract fun size(i:Int):Pair<Int,Int>
    abstract fun render(i:Int,width:Int):Bitmap?
    open fun close(){}
}
internal class ImageSource(dir:File):PageSource(){
    private val meta=JSONObject(File(dir,"pages.json").readText())
    private val files=meta.getJSONArray("files").let{a->(0 until a.length()).map{File(dir,"pages/"+a.getString(it))}}
    private val sizes=meta.getJSONArray("sizes")
    override val count=files.size
    override fun size(i:Int)=sizes.getJSONArray(i).let{it.getInt(0) to it.getInt(1)}
    override fun render(i:Int,width:Int):Bitmap?{val (w,_)=size(i);var s=1;while(w/(s*2)>=width)s*=2
        return BitmapFactory.decodeFile(files[i].path,BitmapFactory.Options().apply{inSampleSize=s;inPreferredConfig=Bitmap.Config.ARGB_8888})}
}
internal class PdfSource(file:File):PageSource(){
    private val fd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);private val r=PdfRenderer(fd)
    private val sizes=HashMap<Int,Pair<Int,Int>>()
    override val count=r.pageCount
    @Synchronized override fun size(i:Int)=sizes.getOrPut(i){r.openPage(i).use{it.width to it.height}}
    @Synchronized override fun render(i:Int,width:Int):Bitmap?{val pg=r.openPage(i);try{val w=width.coerceIn(64,2400);val h=(w*pg.height/pg.width.toFloat()).toInt().coerceIn(64,4000)
        val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);pg.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);sizes[i]=pg.width to pg.height;return b}finally{pg.close()}}
    @Synchronized override fun close(){try{r.close();fd.close()}catch(_:Exception){}}
}

/**
 * The reader for comics and PDF (approved): a dark full screen with the page whole. Swipe or tap an edge to turn,
 * pinch or double-tap to zoom, tap the middle for the panels. Top: the title and «стр. N из M»; bottom: a strip of
 * page thumbnails and the mode — pages, a continuous strip (webtoons) or right to left (manga).
 */
internal class PageReader(private val a:MainActivity,private val book:JSONObject){
    private val doc=book.optJSONObject("doc")!!
    private val source:PageSource=if(doc.str("type")=="pdf")PdfSource(File(PageDocs.dir(a,book),"doc.pdf")) else ImageSource(PageDocs.dir(a,book))
    private val n=source.count
    private val io=Executors.newFixedThreadPool(2)
    private val cache=object:LruCache<String,Bitmap>((Runtime.getRuntime().maxMemory()/5).toInt()){override fun sizeOf(k:String,v:Bitmap)=v.byteCount}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var mode=book.str("pageMode","paged")
    private var page=(book.optJSONObject("progress")?.optInt("page",0)?:0).coerceIn(0,maxOf(0,n-1))
    private val dialog=android.app.Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val root=FrameLayout(a).apply{setBackgroundColor(0xff0e0e0f.toInt())}
    private val stage=FrameLayout(a)
    private lateinit var top:LinearLayout;private lateinit var bottom:LinearLayout
    private lateinit var title2:TextView;private lateinit var strip:LinearLayout;private lateinit var stripScroll:HorizontalScrollView
    private var chrome=true
    private var paged:PagedView?=null;private var ribbon:RibbonView?=null
    private val loading=HashSet<String>()

    fun show(){
        book.put("openedAt",System.currentTimeMillis())
        root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        buildChrome()
        dialog.setContentView(root)
        dialog.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xff0e0e0f.toInt()));w.setLayout(-1,-1);ReaderWindow.configure(w,true,0xff0e0e0f.toInt())
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);(top.layoutParams as FrameLayout.LayoutParams).topMargin=s.top+a.d(10);(bottom.layoutParams as FrameLayout.LayoutParams).bottomMargin=s.bottom+a.d(10);top.requestLayout();bottom.requestLayout()}
        dialog.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dialog.setOnDismissListener{finish()}
        setMode(mode,false)
        dialog.show()
        root.alpha=0f;root.animate().alpha(1f).setDuration(if(reduced)0 else 220).start()
        root.postDelayed({setChrome(false)},1400)
    }
    private fun close(){root.animate().alpha(0f).setDuration(if(reduced)0 else 180).withEndAction{dialog.dismiss()}.start()}
    private fun finish(){save(true);io.shutdownNow();source.close()}
    private fun save(done:Boolean){book.put("progress",(book.optJSONObject("progress")?:JSONObject()).put("page",page).put("pct",if(n<=1)1.0 else page/(n-1).toDouble()))
        if(n>0&&page>=n-1&&book.optLong("completedAt")==0L)book.put("completedAt",System.currentTimeMillis())
        val copy=book.copyJson();a.work({a.library.save(copy)}){if(done)a.reload()}}

    // ── pages ──
    private fun key(i:Int,w:Int)="$i:$w"
    /** The page at [width] px, from the cache or loaded in the background ([ready] runs on the main thread). */
    fun bitmap(i:Int,width:Int,ready:()->Unit):Bitmap?{val k=key(i,width);cache.get(k)?.let{return it}
        if(loading.add(k))io.execute{val b=try{source.render(i,width)}catch(_:Throwable){null};root.post{loading.remove(k);if(b!=null){cache.put(k,b);ready()}}};return null}
    private fun go(i:Int,animate:Boolean=true){val t=i.coerceIn(0,n-1);if(t==page&&paged?.settled!=false)return;page=t;paged?.show(t,animate);ribbon?.scrollTo(t);paint();save(false)}
    private fun paint(){title2.text="стр. ${page+1} из $n"
        for(i in 0 until strip.childCount)(strip.getChildAt(i) as? Thumb)?.setOn(i==page)
        strip.getChildAt(page)?.let{t->val target=t.left+t.width/2-stripScroll.width/2;if(reduced)stripScroll.scrollTo(target,0) else stripScroll.smoothScrollTo(target,0)}}
    private fun setMode(m:String,animate:Boolean){mode=m;book.put("pageMode",m);stage.removeAllViews();paged=null;ribbon=null
        if(m=="ribbon")ribbon=RibbonView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.scrollTo(page)}}
        else paged=PagedView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.show(page,false)}}
        if(animate&&!reduced){stage.alpha=0f;stage.animate().alpha(1f).setDuration(220).start()}
        modeButtons.forEachIndexed{i,v->v.isSelected=listOf("paged","ribbon","rtl")[i]==m}
        // the strip runs the way the pages turn
        strip.layoutDirection=if(m=="rtl")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.post{paint()}}
    fun toggleChrome()=setChrome(!chrome)
    private fun setChrome(on:Boolean){chrome=on;listOf(top to -1,bottom to 1).forEach{(v,s)->v.animate().cancel()
        if(reduced){v.alpha=if(on)1f else 0f;v.visibility=if(on)View.VISIBLE else View.INVISIBLE;return@forEach}
        if(on){v.visibility=View.VISIBLE;v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
        else v.animate().alpha(0f).translationY(s*a.d(16).toFloat()).setDuration(180).withEndAction{if(!chrome)v.visibility=View.INVISIBLE}.start()}}

    // ── panels ──
    private val modeButtons=ArrayList<TextView>()
    private fun card()=GradientDrawable().apply{setColor(0xf01c1c1e.toInt());cornerRadius=a.d(24).toFloat()}
    private fun buildChrome(){
        top=a.line().apply{background=card();setPadding(a.d(6),0,a.d(14),0);elevation=a.d(8).toFloat();gravity=Gravity.CENTER_VERTICAL}
        top.addView(IconView(a,"chevdown",0xfff3f2ef.toInt()).apply{isClickable=true;contentDescription="Закрыть";setOnClickListener{close()};a.press(this)},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        val texts=a.column();texts.addView(a.text(book.str("title"),14.5f,0xfff3f2ef.toInt(),true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        title2=a.text("",12f,0xff8b8b91.toInt());texts.addView(title2,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(1)})
        top.addView(texts,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(4)})
        root.addView(top,FrameLayout.LayoutParams(-1,a.d(56),Gravity.TOP).apply{leftMargin=a.d(10);rightMargin=a.d(10)})
        bottom=a.column().apply{background=card();setPadding(a.d(12),a.d(12),a.d(12),a.d(12));elevation=a.d(8).toFloat()}
        strip=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(4),a.d(4),a.d(4),a.d(4))}
        for(i in 0 until n)strip.addView(Thumb(i).apply{setOnClickListener{go(i,false)}},LinearLayout.LayoutParams(a.d(38),a.d(56)).apply{marginEnd=a.d(8)})
        stripScroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(strip)}
        stripScroll.setOnScrollChangeListener{_,_,_,_,_->for(i in 0 until strip.childCount)strip.getChildAt(i).invalidate()}
        bottom.addView(stripScroll,LinearLayout.LayoutParams(-1,a.d(64)))
        val seg=a.line().apply{background=GradientDrawable().apply{setColor(0x12ffffff);cornerRadius=a.d(18).toFloat()};setPadding(a.d(3),a.d(3),a.d(3),a.d(3))}
        listOf("paged" to "Страницы","ribbon" to "Лента","rtl" to "Справа налево").forEach{(k,label)->
            val b=a.text(label,12.5f,0xff9a9aa0.toInt(),true).apply{gravity=Gravity.CENTER;isClickable=true;a.press(this)
                background=android.graphics.drawable.StateListDrawable().apply{addState(intArrayOf(android.R.attr.state_selected),GradientDrawable().apply{setColor(0xfff3f2ef.toInt());cornerRadius=a.d(15).toFloat()});addState(intArrayOf(),android.graphics.drawable.ColorDrawable(0))}
                setTextColor(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(0xff141416.toInt(),0xff9a9aa0.toInt())))
                setOnClickListener{if(mode!=k)setMode(k,true)}}
            modeButtons.add(b);seg.addView(b,LinearLayout.LayoutParams(0,a.d(30),1f))}
        bottom.addView(seg,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
        root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=a.d(10);rightMargin=a.d(10)})
    }
    /** A page thumbnail in the strip; the current one is outlined. Loaded only while on screen. */
    private inner class Thumb(val i:Int):View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private var on=false
        init{isClickable=true;contentDescription="Страница ${i+1}"}
        fun setOn(v:Boolean){if(on!=v){on=v;invalidate()}}
        override fun onDraw(c:Canvas){val r=a.d(4).toFloat();val w=width.toFloat();val h=height.toFloat()
            val vis=Rect();val shown=getGlobalVisibleRect(vis)
            val b=if(shown)bitmap(i,a.d(76)){invalidate()} else cache.get(key(i,a.d(76)))
            p.alpha=if(on)255 else 140
            c.save();c.clipPath(Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)})
            if(b!=null){val s=maxOf(w/b.width,h/b.height);val bw=b.width*s;val bh=b.height*s;c.drawBitmap(b,null,RectF((w-bw)/2,(h-bh)/2,(w+bw)/2,(h+bh)/2),p)}else{p.color=0xff2a2a2d.toInt();c.drawRect(0f,0f,w,h,p)}
            c.restore()
            if(on){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=0xfff3f2ef.toInt();p.alpha=255;val k=-a.d(3).toFloat();c.drawRoundRect(k,k,w-k,h-k,r+a.d(3),r+a.d(3),p);p.style=Paint.Style.FILL}}
        override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(38),a.d(56))
    }

    /** One page at a time: neighbours slide in with the finger; pinch and double-tap zoom; taps at the edges turn. */
    private inner class PagedView:View(a){
        private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var shift=0f;private var scale=1f;private var px=0f;private var py=0f
        private var anim:ValueAnimator?=null;var settled=true
        private val rtl get()=mode=="rtl"
        private val scaler=ScaleGestureDetector(a,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean{val ns=(scale*d.scaleFactor).coerceIn(1f,5f);val f=ns/scale;px=d.focusX-(d.focusX-px)*f;py=d.focusY-(d.focusY-py)*f;scale=ns;clamp();invalidate();return true}})
        private val gestures=GestureDetector(a,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{val x=e.x/width
                when{x<.28f->step(if(rtl)1 else -1);x>.72f->step(if(rtl)-1 else 1);else->toggleChrome()};return true}
            override fun onDoubleTap(e:MotionEvent):Boolean{zoomTo(if(scale>1.05f)1f else 2.5f,e.x,e.y);return true}
            override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean{
                if(scale>1.01f){px-=dx;py-=dy;clamp();invalidate()}else if(!scaler.isInProgress){anim?.cancel();shift-=dx;invalidate()};return true}
            override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean{if(scale<=1.01f&&kotlin.math.abs(vx)>a.d(500)&&kotlin.math.abs(vx)>kotlin.math.abs(vy)){flung=true;step(if((vx<0)!=rtl)1 else -1)};return true}})
        private var flung=false
        override fun onTouchEvent(e:MotionEvent):Boolean{scaler.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_DOWN)flung=false;gestures.onTouchEvent(e)
            if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&scale<=1.01f&&!flung&&shift!=0f){
                val dir=if(shift< -width*.22f)1 else if(shift>width*.22f)-1 else 0;if(dir!=0)step(if(rtl)-dir else dir) else settle(0f)};return true}
        /** +1: the next page. */
        private fun step(d:Int){val t=page+d;if(t<0||t>=n){settle(0f);return};val visual=if(rtl)-d else d
            go(t,false);shift+=visual*width.toFloat();settle(0f)}
        fun show(i:Int,animate:Boolean){scale=1f;px=0f;py=0f;if(!animate){shift=0f;invalidate()}}
        private fun settle(to:Float){anim?.cancel();settled=false;if(reduced){shift=to;settled=true;invalidate();return}
            anim=ValueAnimator.ofFloat(shift,to).apply{duration=280;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shift=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){settled=true}});start()}}
        private fun zoomTo(s:Float,fx:Float,fy:Float){val from=scale;val fpx=px;val fpy=py
            val tpx=if(s<=1f)0f else fx-(fx-px)*(s/scale);val tpy=if(s<=1f)0f else fy-(fy-py)*(s/scale)
            if(reduced){scale=s;px=tpx;py=tpy;clamp();invalidate();return}
            ValueAnimator.ofFloat(0f,1f).apply{duration=260;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{val t=it.animatedValue as Float;scale=from+(s-from)*t;px=fpx+(tpx-fpx)*t;py=fpy+(tpy-fpy)*t;clamp();invalidate()};start()}}
        private fun fit(i:Int):RectF{val (w,h)=source.size(i);val s=minOf(width/w.toFloat(),height/h.toFloat());val dw=w*s;val dh=h*s;return RectF((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2)}
        private fun clamp(){if(scale<=1f){px=0f;py=0f;return};val r=fit(page);val w=r.width()*scale;val h=r.height()*scale
            val minX=minOf(0f,width-(r.left*scale+w));val maxX=maxOf(0f,-r.left*scale);px=px.coerceIn(minOf(minX,maxX),maxOf(minX,maxX))
            val minY=minOf(0f,height-(r.top*scale+h));val maxY=maxOf(0f,-r.top*scale);py=py.coerceIn(minOf(minY,maxY),maxOf(minY,maxY))}
        override fun onDraw(c:Canvas){if(n==0)return;val target=(width*(if(scale>1f)scale.coerceAtMost(2.5f) else 1f)).toInt().coerceAtMost(2400)
            for(d in -1..1){val i=page+(if(rtl)-d else d);if(i<0||i>=n)continue;val x=shift+d*width
                if(x<=-width||x>=width)continue
                val r=fit(i);c.save();c.translate(x,0f);if(d==0&&scale>1f){c.translate(px,py);c.scale(scale,scale)}
                val b=bitmap(i,target){invalidate()}?:bitmap(i,width){invalidate()}
                if(b!=null)c.drawBitmap(b,null,r,p) else{p.color=0xff1c1c1e.toInt();c.drawRect(r,p)}
                c.restore()}
            // keep the neighbours warm
            if(settled){bitmap((page+1).coerceAtMost(n-1),width){};bitmap((page-1).coerceAtLeast(0),width){}}}
    }

    /** All pages one under another (webtoons): each fits the width; pictures load as they come near the screen. */
    private inner class RibbonView:ScrollView(a){
        private val column=a.column()
        init{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(column)
            for(i in 0 until n)column.addView(Slot(i),LinearLayout.LayoutParams(-1,-2))
            setOnScrollChangeListener{_,_,y,_,_->val mid=y+height/2;for(i in 0 until column.childCount){val v=column.getChildAt(i);if(mid>=v.top&&mid<v.bottom){if(i!=page){page=i;paint();save(false)};break}}}}
        fun scrollTo(i:Int){column.getChildAt(i)?.let{v->if(v.height==0)post{scrollTo(0,v.top)} else scrollTo(0,v.top)}}
        private var taps=GestureDetector(a,object:GestureDetector.SimpleOnGestureListener(){override fun onSingleTapConfirmed(e:MotionEvent):Boolean{toggleChrome();return true}})
        override fun dispatchTouchEvent(e:MotionEvent):Boolean{taps.onTouchEvent(e);return super.dispatchTouchEvent(e)}
        private inner class Slot(val i:Int):View(a){
            private val p=Paint(Paint.FILTER_BITMAP_FLAG)
            override fun onMeasure(w:Int,h:Int){val width=MeasureSpec.getSize(w);val (pw,ph)=source.size(i);setMeasuredDimension(width,(width*ph/pw.toFloat()).toInt())}
            override fun onDraw(c:Canvas){val sy=this@RibbonView.scrollY;val near=bottom>sy-height*2&&top<sy+this@RibbonView.height+height*2
                val b=if(near)bitmap(i,width){invalidate()} else null
                if(b!=null)c.drawBitmap(b,null,Rect(0,0,width,height),p) else{p.color=0xff1c1c1e.toInt();c.drawRect(0f,0f,width.toFloat(),height.toFloat(),p)}}
        }
        override fun onScrollChanged(l:Int,t:Int,ol:Int,ot:Int){super.onScrollChanged(l,t,ol,ot);for(i in 0 until column.childCount)column.getChildAt(i).invalidate()}
    }
}
