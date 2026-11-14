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
    private val root=FrameLayout(a)
    private val stage=FrameLayout(a)
    private lateinit var top:LinearLayout;private lateinit var bottom:LinearLayout
    private lateinit var strip:LinearLayout;private lateinit var stripScroll:HorizontalScrollView
    private lateinit var titleView:TextView;private lateinit var authorView:TextView;private lateinit var pageLabel:TextView;private lateinit var leftLabel:TextView
    private lateinit var markIcon:IconView;private lateinit var soundButton:SoundButton;private lateinit var soundChip:SoundChip
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
    private fun marks():MutableList<Int>{val arr=book.optJSONArray("pageMarks")?:return mutableListOf();return (0 until arr.length()).map{arr.optInt(it)}.filter{it in 0 until n}.distinct().toMutableList()}

    fun show(){
        book.put("openedAt",System.currentTimeMillis());a.immersiveSheets=true
        root.addView(stage,FrameLayout.LayoutParams(-1,-1))
        buildChrome()
        dialog.setContentView(root)
        dialog.window?.let{w->w.setLayout(-1,-1)
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        applyLook()
        applyBrightness(dialog.window)
        // the bars run to the screen's edges like the reader's: the safe area is room inside them
        SafeArea.bind(root){i->val s=SafeArea.content(i,false)
            top.layoutParams=(top.layoutParams as FrameLayout.LayoutParams).apply{height=a.d(52+30)+s.top};top.setPadding(a.d(8)+s.left,a.d(4)+s.top,a.d(8)+s.right,a.d(4+30))
            bottom.setPadding(a.d(18)+s.left,a.d(8+36),a.d(18)+s.right,a.d(4)+s.bottom)}
        dialog.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dialog.setOnDismissListener{finish()}
        setMode(mode,false)
        dialog.show()
        root.alpha=0f;root.animate().alpha(1f).setDuration(if(reduced)0 else 220).start()
        root.postDelayed({setChrome(false)},1400)
    }
    private fun close(){root.animate().alpha(0f).setDuration(if(reduced)0 else 180).withEndAction{dialog.dismiss()}.start()}
    private fun finish(){a.immersiveSheets=false;Ambience.stop(a);save(true);io.shutdownNow();source.close()}
    private fun save(done:Boolean){
        // the status sheet and the data editor save the book on their own: keep what they changed
        a.books.firstOrNull{it.str("id")==book.str("id")}?.takeIf{it!==book}?.let{latest->latest.keys().forEach{k->if(k !in setOf("progress","pageMode","pageMarks","openedAt","state","completedAt","startedAt"))book.put(k,latest.get(k))}
            if(latest.optLong("modifiedAt")>=book.optLong("modifiedAt"))listOf("state","completedAt","startedAt").forEach{k->if(latest.has(k))book.put(k,latest.get(k))}}
        book.put("progress",(book.optJSONObject("progress")?:JSONObject()).put("page",page).put("pct",if(n<=1)1.0 else page/(n-1).toDouble()))
        val st=Library.status(book)
        if(n>0&&page>=n-1&&st!="done"){book.put("state","done");book.put("completedAt",System.currentTimeMillis())}
        else if(page>0&&(st=="want"||st=="paused")){book.put("state","reading");if(book.optLong("startedAt")==0L)book.put("startedAt",System.currentTimeMillis())}
        val copy=book.copyJson();a.work({a.library.save(copy)}){if(done)a.reload()}}

    // ── pages ──
    private fun key(i:Int,w:Int)="$i:$w"
    /** The page at [width] px, from the cache or loaded in the background ([ready] runs on the main thread). */
    fun bitmap(i:Int,width:Int,ready:()->Unit):Bitmap?{val k=key(i,width);cache.get(k)?.let{return it}
        if(loading.add(k))io.execute{val b=try{source.render(i,width)}catch(_:Throwable){null};root.post{loading.remove(k);if(b!=null){cache.put(k,b);ready()}}};return null}
    private fun go(i:Int,animate:Boolean=true){val t=i.coerceIn(0,n-1);if(t==page&&paged?.settled!=false)return;page=t;paged?.show(t,animate);ribbon?.scrollTo(t);paint();save(false)}
    private fun paint(){pageLabel.text="Страница ${page+1}";val left=n-1-page;leftLabel.text=if(left>0)"ещё $left стр" else "последняя"
        markIcon.isSelected=page in marks();markIcon.invalidate()
        for(i in 0 until strip.childCount)(strip.getChildAt(i) as? Thumb)?.setOn(i==page)
        strip.getChildAt(page)?.let{t->val target=t.left+t.width/2-stripScroll.width/2;if(reduced)stripScroll.scrollTo(target,0) else stripScroll.smoothScrollTo(target,0)}}
    private fun setMode(m:String,animate:Boolean){mode=m;book.put("pageMode",m);stage.removeAllViews();paged=null;ribbon=null
        if(m=="ribbon")ribbon=RibbonView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.scrollTo(page)}}
        else paged=PagedView().also{stage.addView(it,FrameLayout.LayoutParams(-1,-1));it.post{it.show(page,false)}}
        if(animate&&!reduced){stage.alpha=0f;stage.animate().alpha(1f).setDuration(220).start()}
        // the strip runs the way the pages turn
        strip.layoutDirection=if(m=="rtl")View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        root.post{paint()}}
    fun toggleChrome()=setChrome(!chrome)
    private fun setChrome(on:Boolean){chrome=on;listOf(top to -1,bottom to 1).forEach{(v,s)->v.animate().cancel()
        if(reduced){v.alpha=if(on)1f else 0f;v.visibility=if(on)View.VISIBLE else View.INVISIBLE;return@forEach}
        if(on){v.visibility=View.VISIBLE;v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
        else v.animate().alpha(0f).translationY(s*v.height.coerceAtLeast(a.d(16)).toFloat()*.6f).setDuration(180).withEndAction{if(!chrome)v.visibility=View.INVISIBLE}.start()}}

    // ── panels: the same bars, icons and sheets as the book reader ──
    private fun icon(kind:String,label:String,fn:()->Unit)=IconView(a,kind,ink()).apply{icons.add(this);contentDescription=label;isClickable=true;setOnClickListener{fn()};a.press(this)}
    private fun buildChrome(){
        top=a.line().apply{gravity=Gravity.TOP;setPadding(a.d(8),a.d(4),a.d(8),a.d(34))}
        top.addView(icon("chevdown","Закрыть книгу"){close()},LinearLayout.LayoutParams(a.d(48),a.d(48)))
        val texts=a.column()
        titleView=a.text(book.str("title"),15f,ink(),true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};texts.addView(titleView)
        authorView=a.text(MainActivity.authors(book),13f,mutedInk()).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;visibility=if(text.isBlank())View.GONE else View.VISIBLE};texts.addView(authorView)
        top.addView(texts,LinearLayout.LayoutParams(0,a.d(48),1f));texts.gravity=Gravity.CENTER_VERTICAL
        soundChip=SoundChip(a){AmbienceSheet(a,bgColor()).show()};top.addView(soundChip,LinearLayout.LayoutParams(-2,a.d(28)).apply{marginEnd=a.d(2);marginStart=a.d(6);topMargin=a.d(10)})
        top.addView(icon("more","Меню книги"){menu()},LinearLayout.LayoutParams(a.d(48),a.d(48)))
        top.forceHasOverlappingRendering(false)
        root.addView(top,FrameLayout.LayoutParams(-1,a.d(82),Gravity.TOP))
        bottom=a.column().apply{setPadding(a.d(18),a.d(44),a.d(18),a.d(4));clipChildren=false;clipToPadding=false;forceHasOverlappingRendering(false)}
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
        markIcon=icon("bm","Закладка на этой странице"){toggleMark()};tools.addView(markIcon,LinearLayout.LayoutParams(0,a.d(48),1f))
        tools.addView(icon("aa","Оформление"){settingsPanel()},LinearLayout.LayoutParams(0,a.d(48),1f))
        soundButton=SoundButton(a,ink()).apply{isClickable=true;setOnClickListener{AmbienceSheet(a,bgColor()).show()};a.press(this)}
        tools.addView(soundButton,LinearLayout.LayoutParams(0,a.d(48),1f))
        bottom.addView(tools)
        root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }
    /** Backdrop, bars and ink for the chosen background. */
    private fun applyLook(){val c=bgColor();root.setBackgroundColor(c)
        dialog.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(c));ReaderWindow.configure(w,true,c)}
        top.background=Shade(c,true,a.d(30).toFloat());bottom.background=Shade(c,false,a.d(36).toFloat())
        icons.forEach{it.ink=ink();it.cut=c;it.invalidate()};soundButton.ink=ink();soundChip.repaint()
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
        caption("Листание")
        val modes=listOf("paged","ribbon","rtl")
        sheet.addView(segment(listOf("Страницы","Лента","Справа налево"),modes.indexOf(mode).coerceAtLeast(0)){k->if(mode!=modes[k])root.postDelayed({setMode(modes[k],true)},if(reduced)0L else 300L)},LinearLayout.LayoutParams(-1,H))
        caption("Масштаб")
        sheet.addView(segment(listOf("Страница целиком","По ширине"),if(widthFit)1 else 0){k->a.prefs.put("comicFit",if(k==1)"width" else "page");a.savePreferences();paged?.refit()},LinearLayout.LayoutParams(-1,H))
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root2.addView(sheet,lp)
        var entrance:LiquidSpring?=null;var closing=false
        fun dismiss(){if(closing)return;closing=true;entrance?.stop();if(reduced){dlg.dismiss();return}
            sheet.animate().translationY(sheet.height+a.d(24).toFloat()).setDuration(240).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{dlg.dismiss()}.start()}
        root2.setOnClickListener{dismiss()}
        dlg.setContentView(root2)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,bgColor())
            if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        applyBrightness(dlg.window)
        SafeArea.bind(root2){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;sheet.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){dismiss();true}else false}
        dlg.show()
        if(!reduced){sheet.translationY=a.d(1200).toFloat();sheet.post{val h=sheet.height+a.d(24).toFloat();val spring=LiquidSpring{v,_->sheet.translationY=h*v};entrance=spring;spring.snap(1f);spring.to(0f)}}
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
        fun setOn(v:Boolean){if(on==v)return;on=v;val s=if(v)1.18f else 1f;animate().cancel()
            if(reduced||!isAttachedToWindow){scaleX=s;scaleY=s} else animate().scaleX(s).scaleY(s).setDuration(260).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start();invalidate()}
        override fun onDraw(c:Canvas){val r=a.d(5).toFloat();val w=width.toFloat();val h=height.toFloat()
            val vis=Rect();val shown=getGlobalVisibleRect(vis)
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
        private val rtl get()=mode=="rtl"
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
                else if(scale<=1.01f&&!scaler.isInProgress){anim?.cancel();settled=false
                    // past the first or the last page the page only gives a little, there is nothing to pull in
                    val edge=(shift-dx>0&&!has(-1))||(shift-dx<0&&!has(1));shift-=if(edge)dx*.3f else dx;invalidate()};return true}
            override fun onFling(e1:MotionEvent?,e2:MotionEvent,vx:Float,vy:Float):Boolean{
                if(axis==2){val to=(pan-vy*.28f).coerceIn(0f,maxPan(page));panAnim?.cancel();if(reduced){pan=to;invalidate()}else panAnim=ValueAnimator.ofFloat(pan,to).apply{duration=420;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pan=it.animatedValue as Float;invalidate()};start()};return true}
                if(scale<=1.01f&&kotlin.math.abs(vx)>a.d(500)&&kotlin.math.abs(vx)>kotlin.math.abs(vy)){flung=true;step(if((vx<0)!=rtl)1 else -1,kotlin.math.abs(vx))};return true}})
        private var flung=false
        override fun onTouchEvent(e:MotionEvent):Boolean{scaler.onTouchEvent(e);if(e.actionMasked==MotionEvent.ACTION_DOWN){flung=false;axis=0;panAnim?.cancel()};gestures.onTouchEvent(e)
            if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&scale<=1.01f&&!flung&&shift!=0f){
                val dir=if(shift< -width*.22f)1 else if(shift>width*.22f)-1 else 0;if(dir!=0)step(if(rtl)-dir else dir) else settle(0f)};return true}
        /** Is there a page on that side of the screen (-1 left, +1 right)? */
        private fun has(side:Int)=(page+(if(rtl)-side else side)) in 0 until n
        /** +1: the next page. The page carries on from exactly where the finger left it (no jump back), and a quick
         *  throw settles faster, like the year pages in the tier list. */
        fun turn(d:Int)=step(d)
        private fun step(d:Int,velocity:Float=0f){val t=page+d;if(t<0||t>=n){settle(0f,velocity);return};val visual=if(rtl)-d else d
            val from=shift;go(t,false);pan=0f;shift=from+visual*width.toFloat();settle(0f,velocity)}
        fun show(i:Int,animate:Boolean){scale=1f;px=0f;py=0f;pan=0f;if(!animate){shift=0f;invalidate()}}
        private fun settle(to:Float,velocity:Float=0f){anim?.cancel();settled=false;if(reduced){shift=to;settled=true;invalidate();return}
            val span=kotlin.math.abs(to-shift)
            anim=ValueAnimator.ofFloat(shift,to).apply{duration=if(velocity>0f)(span/velocity*1000f).toLong().coerceIn(160L,340L) else (180+160*span/width.coerceAtLeast(1)).toLong().coerceIn(180L,340L);interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shift=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){settled=true}});start()}}
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
            setOnScrollChangeListener{_,_,y,_,_->val mid=y+height/2;for(i in 0 until column.childCount){val v=column.getChildAt(i);if(mid>=v.top&&mid<v.bottom){if(i!=page){page=i;paint();save(false)};break}}}}
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

/** The comics bars' backdrop: the page's own colour, solid behind the controls and fading out over [fade] toward the page. */
internal class Shade(private val color:Int,private val top:Boolean,private val fade:Float):android.graphics.drawable.Drawable(){
    private val p=Paint();private var shader:LinearGradient?=null
    override fun onBoundsChange(b:Rect){val rgb=color and 0xffffff;val solid=rgb or 0xf2000000.toInt();val h=b.height().toFloat()
        val edge=if(top)(h-fade)/h else fade/h
        shader=if(top)LinearGradient(0f,b.top.toFloat(),0f,b.bottom.toFloat(),intArrayOf(solid,solid,rgb),floatArrayOf(0f,edge.coerceIn(0f,1f),1f),Shader.TileMode.CLAMP)
            else LinearGradient(0f,b.top.toFloat(),0f,b.bottom.toFloat(),intArrayOf(rgb,solid,solid),floatArrayOf(0f,edge.coerceIn(0f,1f),1f),Shader.TileMode.CLAMP)
        p.shader=shader}
    override fun draw(c:Canvas){c.drawRect(bounds,p)}
    override fun setAlpha(alpha:Int){p.alpha=alpha}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
}
