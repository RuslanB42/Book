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
internal class PageReader(private val a:MainActivity,override val book:JSONObject):AutoHost{
    private val doc=book.optJSONObject("doc")!!
    /** A comic (not a PDF): no bookmarks — its pages are found by their pictures. */
    private val comic=doc.str("type")!="pdf"
    private val source:PageSource=if(doc.str("type")=="pdf")PdfSource(File(PageDocs.dir(a,book),"doc.pdf")) else ImageSource(PageDocs.dir(a,book))
    private val n=source.count
    private val io=Executors.newFixedThreadPool(2)
    private val cache=object:LruCache<String,Bitmap>((Runtime.getRuntime().maxMemory()/5).toInt()){override fun sizeOf(k:String,v:Bitmap)=v.byteCount}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var mode=book.str("pageMode","paged").let{if(it=="rtl"){book.put("pageRtl",true);"paged"}else it}
    /** Pages turn right to left (manga). */
    private var rtl=book.optBoolean("pageRtl")
    /** How a page turns, as in the book reader: «curl», «slide», «cover» or «none». */
    private val turnStyle get()=a.prefs.str("comicTurn","slide")
    private var page=(book.optJSONObject("progress")?.optInt("page",0)?:0).coerceIn(0,maxOf(0,n-1))
    private val dialog=android.app.Dialog(a,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    /** Every touch is the reader being with the page. */
    private val root=object:FrameLayout(a){override fun dispatchTouchEvent(e:MotionEvent):Boolean{tracker.touch()
        // the automatic reading sees every touch first: a tap is its pause (see [AutoRead])
        val au=auto;if(au!=null&&au.intercept(e){val c=MotionEvent.obtain(e).apply{action=MotionEvent.ACTION_CANCEL};super.dispatchTouchEvent(c);c.recycle()})return true
        return super.dispatchTouchEvent(e)}}
    /** Time, pages and sittings, counted as in the text reader (see [ReadingTracker]). */
    private val tracker=ReadingTracker(false,doc.str("type")=="pdf").also{a.seedTracker(it,book.str("id"))}
    private var paused=false
    private val heartbeat=object:Runnable{override fun run(){credit();root.postDelayed(this,5000)}}
    private val stage=FrameLayout(a)
    private lateinit var top:LinearLayout;private lateinit var bottom:LinearLayout
    private lateinit var strip:LinearLayout;private lateinit var stripScroll:HorizontalScrollView
    private lateinit var titleView:TextView;private lateinit var authorView:TextView;private lateinit var pageLabel:TextView;private lateinit var leftLabel:TextView
    private var markIcon:IconView?=null;private lateinit var soundButton:SoundButton;private lateinit var soundChip:SoundChip
    private val icons=ArrayList<IconView>()
    private var chrome=true
    private var paged:PagedView?=null;private var ribbon:RibbonView?=null
    private val loading=HashSet<String>()
    // the look: the backdrop round the page and how the page is fitted
    private val bgKey get()=a.prefs.str("comicBg","black")
    private fun bgColor()=when(bgKey){"graphite"->0xff1c1c1e.toInt();"white"->0xfff3f2ef.toInt();else->0xff000000.toInt()}
    private val lightBg get()=bgKey=="white"
    private fun ink()=if(lightBg)0xff1e1d1b.toInt() else 0xfff3f2ef.toInt()
    private fun mutedInk()=if(lightBg)0xff76767b.toInt() else 0xff8b8b91.toInt()
    private val widthFit get()=a.prefs.str("comicFit")=="width"
    private fun marks():MutableList<Int>{if(comic)return mutableListOf();val arr=book.optJSONArray("pageMarks")?:return mutableListOf();return (0 until arr.length()).map{arr.optInt(it)}.filter{it in 0 until n}.distinct().toMutableList()}

    fun show(){
        book.put("openedAt",System.currentTimeMillis());a.immersiveSheets=true;a.pageReading=this
        tracker.skipTime();tracker.touch();root.postDelayed(heartbeat,5000)
        root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        // the bars' soft shade lies outside them
        root.clipChildren=false
        buildChrome()
        dialog.setContentView(root)
        dialog.window?.let{w->w.setLayout(-1,-1)
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        applyLook()
        applyBrightness(dialog.window)
        // the bars run to the screen's edges like the reader's: the safe area is room inside them
        SafeArea.bind(root){i->val s=SafeArea.content(i,false)
            top.layoutParams=(top.layoutParams as FrameLayout.LayoutParams).apply{height=a.d(52)+s.top};top.setPadding(a.d(8)+s.left,a.d(4)+s.top,a.d(8)+s.right,a.d(4))
            bottom.setPadding(a.d(18)+s.left,a.d(8),a.d(18)+s.right,a.d(4)+s.bottom)}
        dialog.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dialog.setOnDismissListener{finish()}
        setMode(mode,false)
        dialog.show()
        root.alpha=0f;root.animate().alpha(1f).setDuration(if(reduced)0 else MotionSpec.DARK_OUT).setInterpolator(MotionSpec.enter).start()
        root.postDelayed({setChrome(false)},1400)
    }
    private fun close(){root.animate().alpha(0f).setDuration(if(reduced)0 else MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).withEndAction{dialog.dismiss()}.start()}
    private fun finish(){auto?.stop();root.removeCallbacks(heartbeat);val finished=finishOnClose();credit(finished);paused=true;if(a.pageReading===this)a.pageReading=null;a.immersiveSheets=false;Ambience.stop(a);save(true);io.shutdownNow();source.close()
        if(finished)a.root.postDelayed({if(!a.isFinishing&&!a.isDestroyed)BookFinish(a,book){}.show()},300)}
    /** The end of the book is being shown (the window «Книга прочитана» or the question before it). */
    private var finishing=false
    /** Swiped on past the last page: the book is read (never on coming to the last page, it is still being read then). */
    private fun pastEnd(){
        if(finishing||Library.status(book)=="done"||n<=0||page<n-1)return
        credit();finishing=true
        // the page itself turns into the cover (not the whole screen with the backdrop round it)
        val go:()->Unit={markDone();BookFinish(a,book){leave->finishing=false;tracker.skipTime();if(leave)close()}.show(fromPage=true,window=dialog.window,page=paged?.shown(),pageBg=bgColor())}
        if(!tracker.seeded||tracker.share()>=.5)go()
        else{var chosen=false
            // the end comes once the question has gone away (it no longer vanishes at once from under the page)
            a.dialog().setTitle("Книга прочитана?").setMessage("Отметить книгу прочитанной?").setNegativeButton("Нет",null).setPositiveButton("Прочитана"){_,_->chosen=true}.show()
                .setOnDismissListener{if(chosen)go() else{finishing=false;tracker.skipTime()}}}
    }
    // newer than the library's copy, so saving does not take the old «Читаю» back from it
    private fun markDone(){credit(true);val now=System.currentTimeMillis();book.put("state","done").put("completedAt",now).put("modifiedAt",now);save(false)}
    /** Closed on the last page after a look at it, the book really read here: it is read, shown once closed. */
    private fun finishOnClose():Boolean{
        if(finishing||Library.status(book)=="done"||n<=0||page<n-1||!tracker.seeded||tracker.share()<.5||tracker.dwellNow()<4000)return false
        book.put("state","done").put("completedAt",System.currentTimeMillis());return true}
    private fun save(done:Boolean){
        // the status sheet and the data editor save the book on their own: keep what they changed
        a.books.firstOrNull{it.str("id")==book.str("id")}?.takeIf{it!==book}?.let{latest->latest.keys().forEach{k->if(k !in setOf("progress","pageMode","pageRtl","pageMarks","openedAt","state","completedAt","startedAt"))book.put(k,latest.get(k))}
            if(latest.optLong("modifiedAt")>=book.optLong("modifiedAt"))listOf("state","completedAt","startedAt").forEach{k->if(latest.has(k))book.put(k,latest.get(k))}}
        book.put("progress",(book.optJSONObject("progress")?:JSONObject()).put("page",page).put("pct",if(Library.status(book)=="done"||n<=1)1.0 else minOf(page/(n-1).toDouble(),.999)))
        val st=Library.status(book)
        credit()
        if(page>0&&(st=="want"||st=="paused")){book.put("state","reading");if(book.optLong("startedAt")==0L)book.put("startedAt",System.currentTimeMillis())}
        val copy=book.copyJson();a.work({a.library.save(copy)}){if(done)a.reload()}}

    fun pause(){auto?.appPaused();credit();paused=true}
    fun resume(){paused=false;tracker.skipTime();tracker.touch()}
    /** The time and pages since the last tick, into the statistics. */
    /** [end] — the book was just finished: its last page counts as read. */
    private fun credit(end:Boolean=false){
        val now=System.currentTimeMillis();val c=tracker.tick(now,if(end)n else page,0,paused||finishing);tracker.noteCredit(c,n)
        if(c.elapsed<=0&&c.pages<=0&&c.sittingAdd<=0)return
        val id=book.str("id");val title=book.str("title")
        a.work({a.library.db.runInTransaction{val raw=a.library.dao.meta("stats")?:return@runInTransaction
            val meta=JSONObject(raw.json);val data=meta.optJSONObject("data")?:return@runInTransaction
            recordReading(data,id,title,n,c,now,0);a.library.dao.putMeta(MetaRow("stats",meta.toString()))}})
    }

    // ── pages ──
    private fun key(i:Int,w:Int)="$i:$w"
    /** The page at [width] px, from the cache or loaded in the background ([ready] runs on the main thread). */
    fun bitmap(i:Int,width:Int,ready:()->Unit):Bitmap?{val k=key(i,width);cache.get(k)?.let{return it}
        if(loading.add(k))io.execute{val b=try{source.render(i,width)}catch(_:Throwable){null};root.post{loading.remove(k);if(b!=null){cache.put(k,b);ready()}}};return null}
    private fun go(i:Int,animate:Boolean=true){val t=i.coerceIn(0,n-1);if(t==page&&paged?.settled!=false)return;page=t;paged?.show(t,animate);ribbon?.scrollTo(t);paint();save(false);auto?.pageShown()}
    private fun paint(){pageLabel.text="Страница ${page+1}";val left=n-1-page;leftLabel.text=if(left>0)"ещё $left стр" else "последняя"
        markIcon?.let{it.isSelected=page in marks();it.invalidate()}
        for(i in 0 until strip.childCount)(strip.getChildAt(i) as? Thumb)?.place(page)
        strip.getChildAt(page)?.let{t->val target=t.left+t.width/2-stripScroll.width/2;if(reduced)stripScroll.scrollTo(target,0) else stripScroll.smoothScrollTo(target,0)}}
    private fun setMode(m:String,animate:Boolean){mode=m;book.put("pageMode",m);stage.removeAllViews();paged=null;ribbon=null
        if(m=="ribbon")ribbon=RibbonView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.scrollTo(page)}}
        else paged=PagedView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.show(page,false)}}
        if(animate&&!reduced){stage.alpha=0f;stage.animate().alpha(1f).setDuration(220).start()}
        // the strip runs the way the pages turn
        strip.layoutDirection=if(rtl&&m!="ribbon")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.post{paint()}}
    fun toggleChrome()=setChrome(!chrome)

    // ── Автолистание (see [AutoRead]): a page's time is seconds; in the strip, a screen's ──
    private var auto:AutoRead?=null
    private fun autoRead(){if(auto!=null||n==0)return;auto=AutoRead(a,this).also{it.start()}}
    override val autoView:FrameLayout get()=root
    override val autoScroll:View? get()=ribbon
    override val pictures=true
    override fun paged()=ribbon==null
    override fun autoBg()=bgColor()
    override fun autoInk()=ink()
    override fun autoWindow()=dialog.window
    override fun autoInset()=androidx.core.view.ViewCompat.getRootWindowInsets(root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())?.bottom?:0
    override fun readerWpm()=0
    override fun calmForAuto(){if(chrome)setChrome(false)}
    override fun pageKey()="$page"
    override fun position()=page
    override fun pageCharsNow()=0
    override fun pageImagesNow()=1
    override fun autoBusy()=finishing||paged?.busy==true
    override fun atEnd()=if(ribbon!=null)ribbon?.canScrollVertically(1)==false else page>=n-1
    override fun turn(direction:Int){tracker.touch();val pv=paged;if(pv!=null)pv.turn(direction) else if(direction>0&&atEnd())pastEnd()}
    override fun scrollCharsPerPx()=0f
    override fun nextChapterAuto(){}
    override fun herePointNow()=JSONObject().put("page",page)
    override fun pageLabel(p:JSONObject)="стр. ${p.optInt("page")+1}"
    override fun autoReturn(p:JSONObject){jump(p.optInt("page"))}
    override fun quoteActive()=false
    override fun autoEnded(){auto=null}
    private fun setChrome(on:Boolean){chrome=on;listOf(top to -1,bottom to 1).forEach{(v,s)->v.animate().cancel()
        chromeSprings[v]?.stop()
        if(reduced){v.alpha=if(on)1f else 0f;v.translationY=if(on)0f else s*v.height.toFloat();v.visibility=if(on)View.VISIBLE else View.INVISIBLE;return@forEach}
        // the same spring as the book reader's bars: they glide in and out with the sheets that rise from below
        v.visibility=View.VISIBLE;val off=s*v.height.toFloat().coerceAtLeast(1f);val start=(v.translationY/off).coerceIn(0f,1f)
        val sp=chromeSprings.getOrPut(v){LiquidSpring{k,_->v.translationY=off*k;v.alpha=(1f-k*1.25f).coerceIn(0f,1f);if(k>=.999f&&!chrome)v.visibility=View.INVISIBLE}}
        sp.snap(start);sp.to(if(on)0f else 1f)}}
    private val chromeSprings=HashMap<View,LiquidSpring>()

    // ── panels: the same bars, icons and sheets as the book reader ──
    private fun icon(kind:String,label:String,fn:()->Unit)=IconView(a,kind,ink()).apply{icons.add(this);contentDescription=label;isClickable=true;setOnClickListener{fn()};a.press(this)}
    private fun buildChrome(){
        top=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(8),a.d(4),a.d(8),a.d(4))}
        top.addView(icon("chevdown","Закрыть книгу"){close()},LinearLayout.LayoutParams(a.d(48),a.d(48)))
        val texts=a.column()
        titleView=a.text(book.str("title"),15f,ink(),true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};texts.addView(titleView)
        authorView=a.text(MainActivity.authors(book),13f,mutedInk()).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;visibility=if(text.isBlank())View.GONE else View.VISIBLE};texts.addView(authorView)
        top.addView(texts,LinearLayout.LayoutParams(0,a.d(48),1f));texts.gravity=Gravity.CENTER_VERTICAL
        soundChip=SoundChip(a){AmbienceSheet(a,bgColor()).show()};top.addView(soundChip,LinearLayout.LayoutParams(-2,a.d(28)).apply{marginEnd=a.d(2);marginStart=a.d(6)})
        top.addView(icon("more","Меню книги"){menu()},LinearLayout.LayoutParams(a.d(48),a.d(48)))
        top.forceHasOverlappingRendering(false)
        root.addView(top,FrameLayout.LayoutParams(-1,a.d(52),Gravity.TOP))
        bottom=a.column().apply{setPadding(a.d(18),a.d(8),a.d(18),a.d(4));clipChildren=false;clipToPadding=false;forceHasOverlappingRendering(false)}
        val row=a.line()
        pageLabel=a.text("",15f,ink()).apply{gravity=Gravity.CENTER_VERTICAL};leftLabel=a.text("",14f,mutedInk()).apply{gravity=Gravity.CENTER_VERTICAL}
        row.addView(pageLabel,LinearLayout.LayoutParams(0,a.d(40),1f));row.addView(leftLabel,LinearLayout.LayoutParams(-2,a.d(40)))
        bottom.addView(row)
        // the pages as small cards: the current one is larger and ringed; a tap opens a page
        strip=a.line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false;setPadding(a.d(18),0,a.d(18),0)}
        for(i in 0 until n)strip.addView(Thumb(i).apply{setOnClickListener{jump(i)}},LinearLayout.LayoutParams(a.d(44),a.d(66)).apply{if(i>0)marginStart=a.d(9)})
        stripScroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipChildren=false;clipToPadding=false;addView(strip,FrameLayout.LayoutParams(-2,-1))}
        stripScroll.setOnScrollChangeListener{_,_,_,_,_->for(i in 0 until strip.childCount)strip.getChildAt(i).invalidate()}
        bottom.addView(stripScroll,LinearLayout.LayoutParams(-1,a.d(88)).apply{leftMargin=-a.d(18);rightMargin=-a.d(18)})
        val tools=a.line().apply{gravity=Gravity.CENTER;setPadding(0,a.d(4),0,0)}
        tools.addView(icon("contents","Страницы"){pagesSheet()},LinearLayout.LayoutParams(0,a.d(48),1f))
        if(!comic)markIcon=icon("bm","Закладка на этой странице"){toggleMark()}.also{tools.addView(it,LinearLayout.LayoutParams(0,a.d(48),1f))}
        tools.addView(icon("play","Автолистание"){autoRead()},LinearLayout.LayoutParams(0,a.d(48),1f))
        tools.addView(icon("aa","Оформление"){settingsPanel()},LinearLayout.LayoutParams(0,a.d(48),1f))
        soundButton=SoundButton(a,ink()).apply{isClickable=true;setOnClickListener{AmbienceSheet(a,bgColor()).show()};a.press(this)}
        tools.addView(soundButton,LinearLayout.LayoutParams(0,a.d(48),1f))
        bottom.addView(tools)
        root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }
    /** Backdrop, bars and ink for the chosen background. */
    private fun applyLook(){val c=bgColor();root.setBackgroundColor(c)
        dialog.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c));ReaderWindow.configure(w,true,c)}
        listOf(top to 22f,bottom to 26f).forEach{(bar,radius)->a.controlSurface(bar,radius,sheet=true).apply{float=true;themeLight=lightBg;flushTop=bar===top;flushBottom=bar===bottom;refresh()}}
        val surface=(bottom.background as OpaqueSurface).fillColor;icons.forEach{it.ink=ink();it.cut=surface;it.invalidate()};soundButton.ink=ink();soundChip.repaint()
        titleView.setTextColor(ink());authorView.setTextColor(mutedInk());pageLabel.setTextColor(ink());leftLabel.setTextColor(mutedInk())
        paged?.invalidate();ribbon?.invalidate()}
    private fun applyBrightness(w:android.view.Window?){w?:return;val v=a.prefs.optDouble("bright",-1.0).toFloat()
        val target=if(v<0f)WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else v.coerceIn(.01f,1f);if(w.attributes.screenBrightness!=target)w.attributes=w.attributes.apply{screenBrightness=target}}
    /** Opens a page picked in the strip or the grid: the next or previous one slides in, a far one fades in. */
    private fun jump(i:Int){if(i==page)return;val pv=paged
        if(pv!=null&&kotlin.math.abs(i-page)==1){pv.turn(i-page);return}
        go(i,false);if(!reduced){stage.animate().cancel();stage.alpha=.25f;stage.animate().alpha(1f).setDuration(220).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
    private fun toggleMark(){val m=marks();if(page in m)m.remove(page) else m.add(page);book.put("pageMarks",org.json.JSONArray(m.sorted()));paint();save(false)}
    private fun menu(){
        val items=arrayOf("Статус","Изменить данные","Скачать файл")
        a.dialog().setTitle(book.str("title")).setItemIcons("check","pen","download").setItems(items){_,i->when(i){0->a.statusSheet(book);1->a.metadataEditor(book);else->a.exportBook(book,"doc")}}.show()
    }
    /** «Страницы»: every page as a card in a grid (current ringed, bookmarked ones marked); «Закладки» shows only those. */
    private fun pagesSheet(){
        val box=a.column();val grid=a.column();var onlyMarks=false;var sheet:android.app.AlertDialog?=null
        fun fill(){grid.removeAllViews();val marked=marks();val list=if(onlyMarks)marked.sorted() else (0 until n).toList()
            list.chunked(3).forEach{chunk->val r=a.line().apply{gravity=Gravity.TOP;clipChildren=false;clipToPadding=false}
                for(j in 0 until 3){val i=chunk.getOrNull(j);val lp=LinearLayout.LayoutParams(0,-2,1f).apply{if(j>0)marginStart=a.d(12)}
                    if(i==null){r.addView(View(a),lp);continue}
                    val cell=a.column().apply{clipChildren=false;clipToPadding=false;isClickable=true;contentDescription="Страница ${i+1}";setOnClickListener{sheet?.dismiss();jump(i)};a.press(this)}
                    cell.addView(GridThumb(i,i in marked))
                    cell.addView(a.text("${i+1}",13f,if(i==page)a.fg else a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(6),0,0)},LinearLayout.LayoutParams(-1,-2))
                    r.addView(cell,lp)}
                grid.addView(r,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(14)})}}
        if(marks().isNotEmpty()){
            val seg=SlidingSegment(a,0,2,if(a.light)0x0f000000 else 0x11ffffff,if(a.light)Color.WHITE else 0xfff2f2f2.toInt(),20)
            fun tint(k:Int){for(i in 0 until seg.childCount)(seg.getChildAt(i) as TextView).setTextColor(if(i==k)(if(a.light)a.fg else 0xff111111.toInt()) else a.muted)}
            listOf("Все","Закладки").forEachIndexed{k,l->seg.addView(a.text(l,13.5f,bold=true).apply{gravity=Gravity.CENTER;a.press(this);setOnClickListener{seg.choose(k);tint(k);onlyMarks=k==1;fill()}},LinearLayout.LayoutParams(0,-1,1f))}
            tint(0);box.addView(seg,LinearLayout.LayoutParams(-1,a.d(40)).apply{bottomMargin=a.d(16)})}
        box.addView(grid);fill()
        sheet=a.dialog().setTitle("Страницы").setView(box).setTall(true).show()
    }
    /** «Оформление» for pictures: the reader's panel (brightness), the backdrop, how pages turn and how they fit. */
    private fun settingsPanel(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme)
        val sheetColor=if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt();val pill=if(a.light)0x0f000000 else 0x11ffffff;val H=a.d(50)
        val root2=FrameLayout(a).apply{isClickable=true}
        val sheet=a.column().apply{isClickable=true;clipChildren=false;clipToPadding=false;setPadding(a.d(18),a.d(12),a.d(18),a.d(20));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(18)})
        // brightness, exactly as in the reader
        val bright=a.line()
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0)
        val bar=BrightBar(a,start){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);applyBrightness(dialog.window);applyBrightness(dlg.window);if(done)a.savePreferences()}
        bar.dim=auto.on
        auto.setOnClickListener{val on=!auto.on;auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value.toDouble());a.savePreferences();applyBrightness(dialog.window);applyBrightness(dlg.window)}
        bright.addView(bar,LinearLayout.LayoutParams(0,H,1f));bright.addView(auto,LinearLayout.LayoutParams(a.d(84),H).apply{leftMargin=a.d(10)});sheet.addView(bright)
        fun caption(t:String)=sheet.addView(a.text(t,13f,a.muted).apply{typeface=PolkaFonts.sans(500)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(16);bottomMargin=a.d(8);leftMargin=a.d(4)})
        caption("Фон")
        val tiles=a.line();val all=mutableListOf<Pair<String,BackdropTile>>()
        listOf(Triple("black","Чёрный",0xff000000.toInt()),Triple("graphite","Графит",0xff1c1c1e.toInt()),Triple("white","Белый",0xfff3f2ef.toInt())).forEachIndexed{i,(k,label,c)->
            val t=BackdropTile(a,c,label).apply{isClickable=true;contentDescription="Фон: $label";on=bgKey==k;setOnClickListener{if(bgKey!=k){a.prefs.put("comicBg",k);a.savePreferences();applyLook();all.forEach{(kk,v)->v.on=kk==k}}};a.press(this)}
            all.add(k to t);tiles.addView(t,LinearLayout.LayoutParams(0,a.d(58),1f).apply{if(i>0)leftMargin=a.d(2)})}
        sheet.addView(tiles,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-a.d(4);rightMargin=-a.d(4)})
        fun segment(labels:List<String>,selected:Int,pick:(Int)->Unit):View{
            val seg=SlidingSegment(a,selected,labels.size,pill,if(a.light)Color.WHITE else 0xfff2f2f2.toInt(),25)
            fun tint(k:Int){for(i in 0 until seg.childCount)(seg.getChildAt(i) as TextView).setTextColor(if(i==k)(if(a.light)a.fg else 0xff111111.toInt()) else a.muted)}
            labels.forEachIndexed{k,l->seg.addView(a.text(l,13.5f,bold=true).apply{gravity=Gravity.CENTER;maxLines=1;a.press(this);setOnClickListener{seg.choose(k);tint(k);pick(k)}},LinearLayout.LayoutParams(0,-1,1f))}
            tint(selected);return seg}
        // how pages turn, as in the book reader: the same live previews, the strip («Лента») being one of them
        val turns=a.line().apply{gravity=Gravity.TOP}
        val shows=ArrayList<()->Unit>()
        fun current()=if(mode=="ribbon")"ribbon" else turnStyle
        listOf("curl" to "Листание","slide" to "Сдвиг","cover" to "Наложение","none" to "Без анимации","ribbon" to "Лента").forEach{(key,label)->
            val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;contentDescription=label}
            val tile=TurnTile(a,if(key=="ribbon")"scroll" else key,bgColor(),ink());col.addView(tile,LinearLayout.LayoutParams(-1,-2))
            val name=a.text(label,11.5f,a.muted).apply{gravity=Gravity.CENTER;maxLines=2;setLineSpacing(0f,1.1f)};col.addView(name,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})
            val show={val on=current()==key;tile.on=on;name.setTextColor(if(on)a.fg else a.muted);name.typeface=PolkaFonts.sans(if(on)600 else 400)}
            show();shows.add(show)
            col.setOnClickListener{if(current()==key)return@setOnClickListener
                if(key!="ribbon"){a.prefs.put("comicTurn",key);a.savePreferences()}
                val want=if(key=="ribbon")"ribbon" else "paged"
                if(mode!=want)root.postDelayed({setMode(want,true)},if(reduced)0L else 300L)
                mode=want;shows.forEach{it()}}
            a.press(col);turns.addView(col,LinearLayout.LayoutParams(0,-2,1f))}
        sheet.addView(turns,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16)})
        sheet.addView(LayoutToggle(a,"Справа налево",rtl){on->rtl=on;book.put("pageRtl",on);save(false);if(mode!="ribbon")setMode(mode,true)},LinearLayout.LayoutParams(-1,H).apply{topMargin=a.d(12)})
        caption("Масштаб")
        sheet.addView(segment(listOf("Страница целиком","По ширине"),if(widthFit)1 else 0){k->a.prefs.put("comicFit",if(k==1)"width" else "page");a.savePreferences();paged?.refit()},LinearLayout.LayoutParams(-1,H))
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root2.addView(sheet,lp)
        var entrance:SheetMotion?=null;var closing=false
        fun dismiss(){if(closing)return;closing=true;entrance?.stop();if(reduced){dlg.dismiss();return}
            sheet.animate().translationY(sheet.height+a.d(24).toFloat()).setDuration(MotionSpec.SHEET_OUT).setInterpolator(MotionSpec.exit).withEndAction{dlg.dismiss()}.start()}
        root2.setOnClickListener{dismiss()}
        dlg.setContentView(root2)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,bgColor())
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        applyBrightness(dlg.window)
        SafeArea.bind(root2){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;sheet.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){dismiss();true}else false}
        dlg.show()
        if(!reduced){sheet.translationY=a.d(1200).toFloat();sheet.post{val h=sheet.height+a.d(24).toFloat();val spring=SheetMotion{v->sheet.translationY=h*v};entrance=spring;spring.snap(1f);spring.to(0f)}}
    }
    /** A page in the «Страницы» grid: the picture as a card, ringed when it is the open page, a ribbon when bookmarked. */
    private inner class GridThumb(val i:Int,private val marked:Boolean):View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onMeasure(w:Int,h:Int){val width=MeasureSpec.getSize(w);setMeasuredDimension(width,width*3/2)}
        override fun onDraw(c:Canvas){val r=a.d(6).toFloat();val w=width.toFloat();val h=height.toFloat()
            if(width<=0)return
            val b=bitmap(i,(width*2/3).coerceAtLeast(a.d(60))){invalidate()}
            c.save();c.clipPath(Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)})
            if(b!=null){val s=maxOf(w/b.width,h/b.height);val bw=b.width*s;c.drawBitmap(b,null,RectF((w-bw)/2,0f,(w+bw)/2,b.height*s),p)}else{p.color=if(a.light)0xffe6e6e3.toInt() else 0xff2a2a2d.toInt();c.drawRect(0f,0f,w,h,p)}
            c.restore()
            if(i==page){p.style=Paint.Style.STROKE;val sw=a.d(2).toFloat();p.strokeWidth=sw;p.color=a.fg;c.drawRoundRect(sw/2,sw/2,w-sw/2,h-sw/2,r,r,p);p.style=Paint.Style.FILL}
            if(marked){val x=w-a.d(20);val bw=a.d(13).toFloat();val bh=a.d(19).toFloat();p.color=a.fg
                c.drawPath(Path().apply{moveTo(x,0f);lineTo(x+bw,0f);lineTo(x+bw,bh);lineTo(x+bw/2,bh-bw*.4f);lineTo(x,bh);close()},p)}}
    }
    /** A page thumbnail in the strip; the current one is outlined. Loaded only while on screen. */
    private inner class Thumb(val i:Int):View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private var on=false
        init{isClickable=true;contentDescription="Страница ${i+1}"}
        /** The open page's card is larger; the cards beside it step aside by as much as it grew (and its ring), so
         *  the gaps round it are the same as between all the others. Nothing else changes the cards' size. */
        fun place(cur:Int){val was=on;on=i==cur;val s=if(on)1.18f else 1f
            val side=if(i==cur)0 else if(i<cur)-1 else 1;val tx=side*(if(rtl&&mode!="ribbon")-1 else 1)*(a.d(44)*.09f+a.d(3))
            if(scaleX==s&&translationX==tx&&was==on)return
            animate().cancel()
            if(reduced||!isAttachedToWindow){scaleX=s;scaleY=s;translationX=tx} else animate().scaleX(s).scaleY(s).translationX(tx).setDuration(260).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start();invalidate()}
        override fun onDraw(c:Canvas){val r=a.d(5).toFloat();val w=width.toFloat();val h=height.toFloat()
            // in view along the strip (the bar itself may still be sliding in from off the screen)
            val x=left-stripScroll.scrollX;val shown=x+width>0&&x<stripScroll.width
            val b=if(shown)bitmap(i,a.d(76)){invalidate()} else cache.get(key(i,a.d(76)))
            p.alpha=if(on)255 else 190
            c.save();c.clipPath(Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)})
            if(b!=null){val s=maxOf(w/b.width,h/b.height);val bw=b.width*s;c.drawBitmap(b,null,RectF((w-bw)/2,0f,(w+bw)/2,b.height*s),p)}else{p.color=if(lightBg)0xffdcdcd8.toInt() else 0xff2a2a2d.toInt();c.drawRect(0f,0f,w,h,p)}
            c.restore()
            if(on){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat()/scaleX;p.color=ink();p.alpha=255;val k=-a.d(3).toFloat()/scaleX;c.drawRoundRect(k,k,w-k,h-k,r-k,r-k,p);p.style=Paint.Style.FILL}}
    }

    /** One page at a time: neighbours slide in with the finger; pinch and double-tap zoom; taps at the edges turn. */
    private inner class PagedView:View(a){
        private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        private var shift=0f;private var scale=1f;private var px=0f;private var py=0f
        /** «По ширине»: how far down the open page has been moved (a page taller than the screen is read top to bottom). */
        private var pan=0f;private var axis=0;private var panAnim:ValueAnimator?=null
        private fun maxPan(i:Int)=if(widthFit)(fit(i).height()-height).coerceAtLeast(0f) else 0f
        fun refit(){pan=0f;scale=1f;px=0f;py=0f;invalidate()}
        private var anim:ValueAnimator?=null;var settled=true
        /** A page is still moving (a turn or a slide settling). */
        val busy get()=!settled||turnClock!=null||turning!=null
        private val scaler=ScaleGestureDetector(a,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(d:ScaleGestureDetector):Boolean{val ns=(scale*d.scaleFactor).coerceIn(1f,5f);val f=ns/scale;px=d.focusX-(d.focusX-px)*f;py=d.focusY-(d.focusY-py)*f;scale=ns;clamp();invalidate();return true}})
        private val gestures=GestureDetector(a,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean{val x=e.x/width
                when{x<.28f->step(if(rtl)1 else -1);x>.72f->step(if(rtl)-1 else 1);else->toggleChrome()};return true}
            override fun onDoubleTap(e:MotionEvent):Boolean{zoomTo(if(scale>1.05f)1f else 2.5f,e.x,e.y);return true}
            override fun onScroll(e1:MotionEvent?,e2:MotionEvent,dx:Float,dy:Float):Boolean{
                if(scale>1.01f){px-=dx;py-=dy;clamp();invalidate()}else if(!scaler.isInProgress&&axis==0)axis=if(kotlin.math.abs(dy)>kotlin.math.abs(dx)&&maxPan(page)>0f)2 else 1
                if(scale<=1.01f&&axis==2){panAnim?.cancel();pan=(pan+dy).coerceIn(0f,maxPan(page));invalidate()}
                else if(scale<=1.01f&&!scaler.isInProgress&&sheetTurn){
                    // the page turns as a sheet over the screen, following the finger (as in the book reader)
                    if(turning==null&&!refused){val side=if(dx>0)1 else -1;if(!beginTurn(side,e2.y,downY))refused=true}
                    // past the first or the last page the page only gives a little
                    val v=turning;if(v!=null)v.pose((-(e2.x-downX)*turnSide/width).coerceIn(0f,1f),e2.y,downY) else{anim?.cancel();settled=false;shift-=dx*.3f;invalidate()}}
                else if(scale<=1.01f&&!scaler.isInProgress){anim?.cancel();settled=false
                    // past the first or the last page the page only gives a little, there is nothing to pull in
                    val edge=(shift-dx>0&&!has(-1))||(shift-dx<0&&!has(1));shift-=if(edge)dx*.3f else dx;invalidate()};return true}
            override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean{
                if(axis==2){val to=(pan-vy*.28f).coerceIn(0f,maxPan(page));panAnim?.cancel();if(reduced){pan=to;invalidate()}else panAnim=ValueAnimator.ofFloat(pan,to).apply{duration=420;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pan=it.animatedValue as Float;invalidate()};start()};return true}
                if(turning!=null){flingX=vx;return true}
                if(scale<=1.01f&&kotlin.math.abs(vx)>a.d(500)&&kotlin.math.abs(vx)>kotlin.math.abs(vy)){flung=true;step(if((vx<0)!=rtl)1 else -1,kotlin.math.abs(vx))};return true}})
        private var flung=false
        override fun onTouchEvent(e:MotionEvent):Boolean{if(turnClock!=null)return true
            scaler.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_DOWN){flung=false;axis=0;panAnim?.cancel();downX=e.x;downY=e.y;flingX=0f;refused=false};gestures.onTouchEvent(e)
            val sheet=turning
            if(sheet!=null&&(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)){
                val commit=e.actionMasked==MotionEvent.ACTION_UP&&(if(kotlin.math.abs(flingX)>a.d(650))-flingX*turnSide>0 else sheet.progress>.32f);endTurn(commit,flingX);return true}
            if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&scale<=1.01f&&!flung&&shift!=0f){
                // past the last page the page only gives a little: a short pull is enough there to finish the book
                val edge=(shift<0f&&!has(1))||(shift>0f&&!has(-1));val lim=width*(if(edge).07f else .22f)
                val dir=if(shift< -lim)1 else if(shift>lim)-1 else 0;if(dir!=0)step(if(rtl)-dir else dir) else settle(0f)};return true}
        /** Where the open page is in the window, as much of it as is on the screen. */
        fun shown():RectF?{if(width==0||scale>1.01f)return null;val r=fit(page);r.offset(shift,-pan);if(!r.intersect(0f,0f,width.toFloat(),height.toFloat()))return null
            val at=IntArray(2);getLocationInWindow(at);r.offset(at[0].toFloat(),at[1].toFloat());return r}
        /** Is there a page on that side of the screen (-1 left, +1 right)? */
        private fun has(side:Int)=(page+(if(rtl)-side else side)) in 0 until n
        /** +1: the next page. The page carries on from exactly where the finger left it (no jump back), and a quick
         *  throw settles faster, like the year pages in the tier list. */
        fun turn(d:Int)=step(d)
        private fun step(d:Int,velocity:Float=0f){val t=page+d;if(t<0||t>=n){settle(0f,velocity);if(t>=n)pastEnd();return};val visual=if(rtl)-d else d
            if(sheetTurn){if(turning==null&&beginTurn(visual,height*.9f,height*.9f))endTurn(true,0f,false);return}
            val from=shift;go(t,false);pan=0f;shift=from+visual*width.toFloat();settle(0f,velocity)}
        fun show(i:Int,animate:Boolean){scale=1f;px=0f;py=0f;pan=0f;if(!animate){shift=0f;invalidate()}}
        private fun settle(to:Float,velocity:Float=0f){anim?.cancel();settled=false;if(reduced){shift=to;settled=true;invalidate();return}
            val span=kotlin.math.abs(to-shift)
            anim=ValueAnimator.ofFloat(shift,to).apply{duration=if(velocity>0f)(span/velocity*1000f).toLong().coerceIn(160L,340L) else (180+160*span/width.coerceAtLeast(1)).toLong().coerceIn(180L,340L);interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shift=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){settled=true}});start()}}
        // ── a turn as in the book reader: the page and the one it turns to as two pictures of the screen, turned over it ──
        private val sheetTurn get()=turnStyle!="slide"
        private var turning:PageCurlView?=null;private var turnSide=0;private var turnClock:ValueAnimator?=null
        private var downX=0f;private var downY=0f;private var flingX=0f;private var refused=false
        /** The screen with page [i] on it, as it is drawn. */
        private fun shot(i:Int,panned:Boolean):Bitmap{val b=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(bgColor())
            val r=fit(i);if(panned)c.translate(0f,-pan)
            val pic=bitmap(i,width){};if(pic!=null)c.drawBitmap(pic,null,r,p) else{p.color=if(lightBg)0xffdcdcd8.toInt() else 0xff1c1c1e.toInt();c.drawRect(r,p)};return b}
        /** Starts turning toward the page on [side] of the screen (-1 left, +1 right); false when there is none. */
        private fun beginTurn(side:Int,y:Float,anchor:Float):Boolean{if(turning!=null||!has(side)||width<=0||height<=0)return false
            val t=page+(if(rtl)-side else side)
            val v=PageCurlView(a,shot(page,true),shot(t,false),side,bgColor(),reduced).apply{style=turnStyle}
            turning=v;turnSide=side;anim?.cancel();shift=0f
            stage.addView(v,FrameLayout.LayoutParams(-1,-1));v.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));v.layout(0,0,width,height)
            v.pose(0f,y,anchor);return true}
        /** Finishes the turn: the sheet goes on to the new page or falls back; [gesture] — let go by the finger, at [vx]. */
        private fun endTurn(commit:Boolean,vx:Float,gesture:Boolean=true){val v=turning?:return;turnClock?.cancel()
            fun done(){turnClock=null;if(commit)go(page+(if(rtl)-turnSide else turnSide),false);stage.removeView(v);turning=null;invalidate()}
            val start=v.progress;val end=if(commit)1f else 0f
            if(reduced||v.style=="none"){v.pose(end,v.touchY);done();return}
            // as in the book reader: let go, the sheet leaves at the finger's own speed, then settles
            val remaining=kotlin.math.abs(end-start).coerceAtLeast(.001f)
            val toward=((-vx*turnSide/width.coerceAtLeast(1))*(if(commit)1f else -1f)).coerceAtLeast(0f)
            val flight=if(toward>0f)(1.6f*remaining/toward*1000f).toLong().coerceIn(200L,520L) else (remaining*560f).toLong().coerceIn(220L,480L)
            val slope=(toward*flight/1000f/remaining).coerceIn(0f,2.6f)
            turnClock=ValueAnimator.ofFloat(start,end).apply{duration=if(gesture)flight else 520L;interpolator=if(gesture)android.animation.TimeInterpolator{u->slope*u+(3f-2f*slope)*u*u+(slope-2f)*u*u*u} else PathInterpolator(.42f,0f,.16f,1f)
                addUpdateListener{v.pose(it.animatedValue as Float,v.touchY)}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){done()}});start()}}
        override fun onDetachedFromWindow(){turnClock?.removeAllListeners();turnClock?.cancel();turnClock=null;super.onDetachedFromWindow()}
        private fun zoomTo(s:Float,fx:Float,fy:Float){val from=scale;val fpx=px;val fpy=py
            val tpx=if(s<=1f)0f else fx-(fx-px)*(s/scale);val tpy=if(s<=1f)0f else fy-(fy-py)*(s/scale)
            if(reduced){scale=s;px=tpx;py=tpy;clamp();invalidate();return}
            ValueAnimator.ofFloat(0f,1f).apply{duration=260;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{val t=it.animatedValue as Float;scale=from+(s-from)*t;px=fpx+(tpx-fpx)*t;py=fpy+(tpy-fpy)*t;clamp();invalidate()};start()}}
        private fun fit(i:Int):RectF{val (w,h)=source.size(i)
            if(widthFit){val dh=h*width/w.toFloat();val t=if(dh<=height)(height-dh)/2 else 0f;return RectF(0f,t,width.toFloat(),t+dh)}
            val s=minOf(width/w.toFloat(),height/h.toFloat());val dw=w*s;val dh=h*s;return RectF((width-dw)/2,(height-dh)/2,(width+dw)/2,(height+dh)/2)}
        private fun clamp(){if(scale<=1f){px=0f;py=0f;return};val r=fit(page);val w=r.width()*scale;val h=r.height()*scale
            val minX=minOf(0f,width-(r.left*scale+w));val maxX=maxOf(0f,-r.left*scale);px=px.coerceIn(minOf(minX,maxX),maxOf(minX,maxX))
            val minY=minOf(0f,height-(r.top*scale+h));val maxY=maxOf(0f,-r.top*scale);py=py.coerceIn(minOf(minY,maxY),maxOf(minY,maxY))}
        override fun onDraw(c:Canvas){if(n==0)return;val target=(width*(if(scale>1f)scale.coerceAtMost(2.5f) else 1f)).toInt().coerceAtMost(2400)
            for(d in -1..1){val i=page+(if(rtl)-d else d);if(i<0||i>=n)continue;val x=shift+d*width
                if(x<=-width||x>=width)continue
                val r=fit(i);c.save();c.translate(x,0f);if(d==0&&scale>1f){c.translate(px,py);c.scale(scale,scale)}else if(d==0)c.translate(0f,-pan)
                val b=bitmap(i,target){invalidate()}?:bitmap(i,width){invalidate()}
                if(b!=null)c.drawBitmap(b,null,r,p) else{p.color=if(lightBg)0xffdcdcd8.toInt() else 0xff1c1c1e.toInt();c.drawRect(r,p)}
                c.restore()}
            // keep the neighbours warm, two ahead and one behind, so a quick run of swipes never shows an empty page
            for(k in intArrayOf(1,-1,2))(page+k).takeIf{it in 0 until n}?.let{bitmap(it,width){if(kotlin.math.abs(shift)>0f)invalidate()}}}
    }

    /** All pages one under another (webtoons): each fits the width; pictures load as they come near the screen. */
    private inner class RibbonView:ScrollView(a){
        private val column=a.column()
        init{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(column)
            for(i in 0 until n)column.addView(Slot(i),LinearLayout.LayoutParams(-1,-2))
            setOnScrollChangeListener{_,_,y,_,_->tracker.touch();val mid=y+height/2;for(i in 0 until column.childCount){val v=column.getChildAt(i);if(mid>=v.top&&mid<v.bottom){if(i!=page){page=i;paint();save(false)};break}}}}
        fun scrollTo(i:Int){column.getChildAt(i)?.let{v->if(v.height==0)post{scrollTo(0,v.top)} else scrollTo(0,v.top)}}
        private var taps=GestureDetector(a,object:GestureDetector.SimpleOnGestureListener(){override fun onSingleTapConfirmed(e:MotionEvent):Boolean{toggleChrome();return true}})
        override fun dispatchTouchEvent(e:MotionEvent):Boolean{taps.onTouchEvent(e);return super.dispatchTouchEvent(e)}
        private inner class Slot(val i:Int):View(a){
            private val p=Paint(Paint.FILTER_BITMAP_FLAG)
            override fun onMeasure(w:Int,h:Int){val width=MeasureSpec.getSize(w);val (pw,ph)=source.size(i);setMeasuredDimension(width,(width*ph/pw.toFloat()).toInt())}
            override fun onDraw(c:Canvas){val sy=this@RibbonView.scrollY;val near=bottom>sy-height*2&&top<sy+this@RibbonView.height+height*2
                val b=if(near)bitmap(i,width){invalidate()} else null
                if(b!=null)c.drawBitmap(b,null,Rect(0,0,width,height),p) else{p.color=if(lightBg)0xffdcdcd8.toInt() else 0xff1c1c1e.toInt();c.drawRect(0f,0f,width.toFloat(),height.toFloat(),p)}}
        }
        override fun onScrollChanged(l:Int,t:Int,ol:Int,ot:Int){super.onScrollChanged(l,t,ol,ot);for(i in 0 until column.childCount)column.getChildAt(i).invalidate()}
    }
}

/** A backdrop choice in the comics «Оформление»: the colour as a tile with its name, ringed when chosen (like the reader's themes). */
internal class BackdropTile(private val a:MainActivity,private val bg:Int,private val label:String):View(a){
    private var k=0f;private var anim:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||!isAttachedToWindow||a.prefs.str("motion")=="reduced"){first=false;k=target;invalidate();return}
        anim?.cancel();anim=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val inset=4*d;val r=RectF(inset,inset,width-inset,height-inset);val rad=16*d
        if(k>0f){p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;p.alpha=(255*k).toInt();val o=d+(1f-k)*2f*d;c.drawRoundRect(RectF(o,o,width-o,height-o),rad+inset-o,rad+inset-o,p);p.alpha=255}
        p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(Color.red(bg)>128)0x1a000000 else 0x24ffffff;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.FILL;p.color=if(Color.red(bg)>128)0xff1e1d1b.toInt() else 0xfff3f2ef.toInt();p.textSize=13.5f*a.resources.displayMetrics.scaledDensity;p.typeface=PolkaFonts.sans(500);p.textAlign=Paint.Align.CENTER
        c.drawText(label,width/2f,height/2f-(p.ascent()+p.descent())/2f,p)}
}
