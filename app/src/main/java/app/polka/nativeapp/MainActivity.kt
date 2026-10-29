package app.polka.nativeapp

import android.app.*
import android.os.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.*
import android.widget.*
import org.json.*
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {
    lateinit var library: Library
    private val cpu=Executors.newFixedThreadPool(2)
    val screenMotion by lazy { ScreenMotion(this) }
    var quoteBookId:String?=null
    private var pendingMotion:Pair<MotionPose,Long>?=null
    private val tabScroll=mutableMapOf<String,Int>()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var checkpointTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var initialized = false
    private var bootFinished = false
    var prefs = JSONObject()
    var books = listOf<JSONObject>()
    val covers = object:android.util.LruCache<String,Bitmap>(16*1024) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024 }
    lateinit var root: FrameLayout
    lateinit var page: LinearLayout
    internal lateinit var scroll: ScrollView
    private var nativeNavigation:PolkaNavigation?=null
    private var tab = "home"
    private var shelf: String? = null
    private var auto: String? = null
    var recommendationExpansion:RecommendationExpansion?=null
    internal var recommendationDeck:RecommendationDeck?=null
    var bookDetailSheet:BookDetailSheet?=null
    var query = ""
    internal var searchOpen=false
    internal var librarySearch:LibrarySearchBar?=null
    internal var appInsets=androidx.core.graphics.Insets.NONE
    internal fun navigationBottom(safe:androidx.core.graphics.Insets=appInsets)=maxOf(d(4),safe.bottom)
    private val insetConsumers=linkedMapOf<View,(androidx.core.graphics.Insets)->Unit>()
    internal fun followInsets(view:View,consume:(androidx.core.graphics.Insets)->Unit) {
        insetConsumers[view]=consume;consume(appInsets)
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v:View){insetConsumers[v]=consume;consume(appInsets)}
            override fun onViewDetachedFromWindow(v:View){insetConsumers.remove(v)}
        })
    }
    internal fun updateAppInsets(safe:androidx.core.graphics.Insets){
        appInsets=safe
        // Backgrounds, scrims and scroll viewports ALWAYS occupy the entire window.
        root.setPadding(0,0,0,0)
        insetConsumers.values.toList().forEach{it(safe)}
    }
    internal var sortControl:TextView?=null
    internal var shelfReorder:ShelfReorder?=null
    var sort = "manual"
    var attachId: String? = null
    private var importDialog: ImportNotice? = null
    internal var bookCalendar:BookCalendarDialog?=null
    var pendingCalendarImage:File?=null
    var profileOverlay:View?=null
    var profileStats:JSONObject?=null
    var profileRange=0
    var statisticsMonth:java.time.YearMonth?=null
    var statisticsDay:java.time.LocalDate?=null
    var statisticsGoalYear:Int?=null
    private val bookObservers=linkedMapOf<View,Pair<String,(JSONObject)->Unit>>()
    fun observeBook(owner:View,id:String,changed:(JSONObject)->Unit) {
        bookObservers[owner]=id to changed
        owner.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v:View) {} ; override fun onViewDetachedFromWindow(v:View) { bookObservers.remove(owner) } })
    }
    fun publishBook(saved:JSONObject) {
        val current=books.firstOrNull { it.str("id")==saved.str("id") }
        if(current!=null && current!==saved) replaceJson(current,saved)
        else if(current==null) books=books+saved.copyJson()
        reader?.book?.takeIf { it.str("id")==saved.str("id") && it!==saved }?.let { replaceJson(it,saved) }
        bookObservers.values.toList().filter { it.first==saved.str("id") }.forEach { it.second(saved.copyJson()) }
    }
    fun refreshProfile() { val data=profileStats ?: return; val y=(profileOverlay as? ScrollView)?.scrollY ?: 0; profileOverlay?.let { root.removeView(it) }; showProfile(data,profileRange,false); profileOverlay?.post { (profileOverlay as? ScrollView)?.scrollTo(0,y) } }
    var coverTarget:String?=null
    var coverEditorBook:JSONObject?=null
    var coverEditorView:View?=null
    var pendingExport:Pair<JSONObject,String>?=null
    private var pendingActivityResult:Triple<Int,Int,Intent>?=null
    var reader: NativeReader? = null
    internal var bookClosing=false
    val light get() = when(prefs.str("ui",prefs.str("theme","dark"))) { "light" -> true; "system" -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES; else -> false }
    val bg get() = if(light) 0xfff2f2f0.toInt() else 0xff161617.toInt()
    val surface get() = if(light) 0xffffffff.toInt() else 0xff1f1f21.toInt()
    val fg get() = if(light) 0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
    val muted get() = if(light) 0xff686868.toInt() else 0xffa4a4a4.toInt()
    val accent get() = if(light) 0xff9b5a00.toInt() else 0xfff3b35a.toInt()
    val accentFill get()=if(light) 0xffd48a1a.toInt() else 0xfff0a742.toInt()
    fun d(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=d(radius).toFloat() }
    @android.annotation.SuppressLint("WrongConstant")
    fun text(value: String, size: Float=16f, color: Int=fg, bold: Boolean=false, serif: Boolean=false): TextView = TextView(this).apply { text=value; textSize=size; setTextColor(color); typeface=if(serif) PolkaFonts.serif(this@MainActivity,bold) else Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL); includeFontPadding=false; cssLeading(1.2f); if(serif) breakStrategy=android.text.Layout.BREAK_STRATEGY_BALANCED }
    fun button(value: String, primary: Boolean=false, onClick: () -> Unit): TextView = text(value,16f, if(primary) bg else fg,true).apply { gravity=Gravity.CENTER; minHeight=d(48); setPadding(d(16),d(12),d(16),d(12)); background=if(primary)shape(fg,28)else this@MainActivity.controlSurface(this,28f); setOnClickListener { onClick() }; press(this) }
    fun icon(kind:String, label:String, selected:Boolean=false, action:()->Unit): View = IconView(this,kind,if(selected) bg else fg).apply { contentDescription=label; isClickable=true; isFocusable=true; background=if(selected)shape(fg,28)else this@MainActivity.controlSurface(this,28f); setOnClickListener { action() };press(this) }
    fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(this),LinearLayout.LayoutParams(1,d(height))) }
    fun addIcon(parent: LinearLayout, kind:String,label:String,action:()->Unit) { parent.addView(icon(kind,label,action=action),LinearLayout.LayoutParams(d(44),d(44)).apply { marginStart=if(parent.childCount==0) -d(4) else d(8) }) }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        attachId=savedInstanceState?.getString("attachId"); coverTarget=savedInstanceState?.getString("coverTarget")
        window.statusBarColor=Color.TRANSPARENT; window.navigationBarColor=Color.TRANSPARENT
        library=Library(LibraryDb.open(applicationContext))
        root=FrameLayout(this); root.setBackgroundColor(bg)
        ReaderWindow.configure(window,false,bg)
        SafeArea.bind(root) { insets -> ReaderWindow.apply(this,insets.toWindowInsets()!!) }
        setContentView(root)
        root.requestApplyInsets()
        root.addView(bootLogo(),FrameLayout.LayoutParams(-1,-1))
        work({ library.initialize(); if(!File(filesDir,"recs.json").exists()) assets.open("recs.json").use { input -> File(filesDir,"recs.json").outputStream().use { input.copyTo(it) } }; loadData() }) { initialized=true; bootFinished=true; savedInstanceState?.getString("calendarImage")?.let{pendingCalendarImage=File(File(cacheDir,"calendar-share"),it)}; savedInstanceState?.getString("exportId")?.let { id -> books.firstOrNull { it.str("id")==id }?.let { pendingExport=it to (savedInstanceState.getString("exportFormat") ?: "fb2") } }; tab=savedInstanceState?.getString("tab") ?: "home"; shelf=savedInstanceState?.getString("shelf"); auto=savedInstanceState?.getString("auto"); query=savedInstanceState?.getString("query").orEmpty();searchOpen=savedInstanceState?.getBoolean("searchOpen",query.isNotEmpty()) ?: false; render(); savedInstanceState?.getString("readerId")?.let { id -> books.firstOrNull { it.str("id")==id }?.let { openBook(it) } }; pendingActivityResult?.let { pendingActivityResult=null; onActivityResult(it.first,it.second,it.third) } }
    }
    fun loadData() {
        prefs=library.prefs(); if(!prefs.has("size")) prefs.put("size",prefs.optInt("fontSize",19)); if(!prefs.has("ui")) prefs.put("ui",prefs.str("theme","dark")); books=library.allBooks(); sort=prefs.str("sort","manual")
    }
    fun requestCover(book:JSONObject,done:(Bitmap?)->Unit) {
        val id=book.optJSONObject("cover")?.str("__polkaBlob")?.takeIf { it.isNotBlank() } ?: return
        covers.get(id)?.let { done(it); return }
        work({
            val bytes=library.dao.asset(id)?.bytes
            val bitmap=bytes?.let { BookImages.decode(it,700) }
            if(bitmap!=null) covers.put(id,bitmap)
            runOnUiThread { if(!isDestroyed) done(bitmap) }
        })
    }

    fun cover(book:JSONObject): Bitmap? = book.optJSONObject("cover")?.str("__polkaBlob")?.let { covers.get(it) }
    fun <T> compute(action:()->T,failed:((Exception)->Unit)?=null,done:(T)->Unit):java.util.concurrent.Future<*> = cpu.submit { try { val result=action();runOnUiThread { if(!isFinishing && !isDestroyed)done(result) } }catch(e:Exception){if(e !is InterruptedException && !Thread.currentThread().isInterrupted)runOnUiThread { if(!isFinishing && !isDestroyed){failed?.invoke(e);errorDialog(e)} }} }
    fun work(action:()->Unit, failed:((Exception)->Unit)?=null, done:()->Unit={}) { io.execute { try { action(); runOnUiThread { if(!isFinishing && !isDestroyed) done() } } catch(e:Exception) { runOnUiThread { importDialog?.dismiss(); importDialog=null; if(!isFinishing && !isDestroyed) { failed?.invoke(e); if(!initialized) { bootFinished=true; recoveryScreen(e) } else errorDialog(e) } } } } }
    fun dialog() = PolkaSheetBuilder(this)
    fun errorDialog(e:Throwable) { dialog().setTitle("Не удалось выполнить действие").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("Закрыть",null).show() }
    fun reload() { work({loadData()}) { initialized=true; books.forEach { fresh -> bookObservers.values.toList().filter { it.first==fresh.str("id") }.forEach { it.second(fresh.copyJson()) } }; render(true,false); if(profileOverlay!=null) refreshProfile(); checkpointSoon() } }
    fun checkpointSoon() { checkpointTask?.cancel(false); checkpointTask=io.schedule({ try { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints")); getSharedPreferences("health",0).edit().remove("checkpointError").apply() } catch(e:Exception) { getSharedPreferences("health",0).edit().putString("checkpointError",e.message).apply() } }, 3, TimeUnit.SECONDS) }
    fun saveBook(book:JSONObject, done:()->Unit={}) { val copy=book.copyJson().put("modifiedAt",System.currentTimeMillis()); reader?.book?.takeIf { it.str("id")==copy.str("id") && it!==book }?.let { replaceJson(it,copy) }; work({library.save(copy)}) { publishBook(copy); if(reader==null) { render(true,false); if(profileOverlay!=null) refreshProfile() }; checkpointSoon(); done() } }
    fun savePreferences() { val copy=prefs.copyJson(); work({ library.savePrefs(copy) }) { checkpointSoon() } }
    fun title():String = when { query.isNotBlank() -> "Поиск"; shelf!=null -> shelf!!; auto!=null -> autoTitle(auto!!); tab=="shelves" -> "Полки"; tab=="quotes" -> "Цитаты"; tab=="foryou" -> "Для вас"; else -> "Мои книги" }
    fun homeBooks()=Library.home(books,prefs.optBoolean("showRecommendations"))
    fun currentBooks():List<JSONObject> {
        if(query.isNotBlank())return searchBooks()
        var list=when { shelf!=null -> books.filter { it.str("shelf")==shelf }; auto!=null -> books.filter { Library.status(it)==auto }; else -> homeBooks() }
        if(query.isNotBlank()) list=list.filter { (it.str("title")+" "+authors(it)).contains(query,true) }
        return when(sort) { "title" -> list.sortedBy { it.str("title").lowercase() }; "author" -> list.sortedBy(::authors); "recent" -> list.sortedByDescending { maxOf(it.optLong("openedAt"),it.optLong("addedAt")) }; else -> list.sortedWith(compareBy<JSONObject> { it.optDouble("pos",it.optLong("addedAt").toDouble()) }.thenBy { it.str("title") }) }
    }
    private var renderDeferred=false
    private var destroying=false
    fun flushDeferredRender(){if(!destroying&&!bookClosing&&renderDeferred&&reader==null&&bookDetailSheet==null&&recommendationExpansion==null&&shelfReorder==null&&!isFinishing&&!isDestroyed){renderDeferred=false;if(tab=="foryou" && query.isBlank() && shelf==null && auto==null && recommendationDeck?.refreshAfterOverlay()==true)return;render(true,false)}}
    fun render(preservePosition:Boolean=false,animateEntry:Boolean=true,preparingBookReturn:Boolean=false) {
        if(reader!=null||destroying)return
        if(bookClosing&&!preparingBookReturn){renderDeferred=true;return}
        if(shelfReorder!=null || recommendationExpansion?.isShowing==true || bookDetailSheet?.isShowing==true){renderDeferred=true;return}
        renderDeferred=false
        val oldY=if(preservePosition && ::scroll.isInitialized) scroll.scrollY else 0
        val overlay=profileOverlay
        screenMotion.stop(); root.removeAllViews(); root.setBackgroundColor(bg)
        if(!preparingBookReturn){ReaderWindow.configure(window,false,bg); root.requestApplyInsets()}
        if(!preparingBookReturn&&Build.VERSION.SDK_INT>=30) window.insetsController?.setSystemBarsAppearance(if(light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false; clipChildren=false }
        val wrapper=col().apply { clipChildren=false; clipToPadding=false; setPadding(d(20),d(8),d(20),d(112)) };page=wrapper
        followInsets(wrapper){i->wrapper.setPadding(d(20)+i.left,d(8)+i.top,d(20)+i.right,d(82)+navigationBottom(i))}
        scroll.addView(wrapper); root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val top=LibrarySearchBar(this).apply{elevation=d(12).toFloat()};librarySearch=top;page.addView(top,LinearLayout.LayoutParams(-1,d(48)))
        space(page,20)
        page=col().apply{clipChildren=false;clipToPadding=false};wrapper.addView(page,LinearLayout.LayoutParams(-1,-2))
        renderLibraryResults()
        navigation(); overlay?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }; if(preservePosition) scroll.post { scroll.scrollTo(0,oldY) }; val motion=pendingMotion;pendingMotion=null;if(motion!=null)screenMotion.play(page,motion.first,motion.second) else if(animateEntry)screenMotion.play(page,MotionPose(1f,y=d(10).toFloat()),450,curve=MotionSpec.settle)
    }
    internal fun searchBooks():List<JSONObject> {val q=query.trim();return (if(tab=="home")homeBooks()else books).filter{(it.str("title")+" "+authors(it)+" "+it.str("series")+" "+it.str("shelf")).contains(q,true)}.sortedByDescending{maxOf(it.optLong("openedAt"),it.optLong("addedAt"))}}
    internal fun renderLibraryResults(){
        if(reader!=null || !::page.isInitialized)return
        screenMotion.stop();page.removeAllViews()

        when {
            query.isNotBlank() -> {val found=searchBooks();if(found.isEmpty()){page.addView(text("Ничего не нашлось по запросу «${query.trim()}». Поиск идёт по названию, автору, серии и полке.",15f,muted))}else{val row=line();row.addView(text("Найдено",20f,bold=true),LinearLayout.LayoutParams(0,-2,1f));row.addView(text(countBooks(found.size),14f,muted));page.addView(row);space(page,14);bookGrid(found)}}
            shelf!=null || auto!=null -> { backAndCount(); val list=currentBooks(); if(list.isEmpty()) emptyState(if(auto=="reading") "Пока ничего не читаете" else "На полке пока пусто") else bookGrid(list) }
            tab=="shelves" && query.isBlank() -> shelfGrid()
            tab=="quotes" -> quotes()
            tab=="foryou" -> recommendations()
            else -> {
                if(query.isBlank()) continueCard()
                val list=currentBooks(); countRow(list.size)
                if(list.isEmpty()) { emptyState(if(query.isNotBlank()) "Ничего не найдено" else "Здесь будут ваши книги"); if(query.isBlank()) page.addView(button("＋  Добавить книги",true) { pickBooks() }) } else bookGrid(list)
            }
        }

    }
    fun countRow(n:Int) { val r=row(); r.addView(text(countBooks(n),20f,bold=true),LinearLayout.LayoutParams(0,-2,1f)); r.addView(text(sortName(),14f,muted).apply { sortControl=this; setPadding(d(8),0,0,0); gravity=Gravity.CENTER_VERTICAL; minHeight=d(36); setOnClickListener { filters() } }); page.addView(r,LinearLayout.LayoutParams(-1,d(36))); space(page,14) }
    fun backAndCount() { val r=row(); r.addView(backControl("Полки","Назад к полкам") { pendingMotion=MotionPose(.4f,x=-d(18).toFloat()) to MotionSpec.SHELF;shelf=null;auto=null;query="";tab="shelves";render(false,false) },LinearLayout.LayoutParams(0,d(44),1f)); r.addView(text(countBooks(currentBooks().size),14f,muted)); addIcon(r,"more","Действия с полкой") { if(shelf!=null) shelfActions(shelf!!) else filters() }; page.addView(r); space(page,18) }
    fun emptyState(label:String) { space(page,40); page.addView(ShelfArt(this,emptyList(),light),LinearLayout.LayoutParams(d(180),d(160)).apply { gravity=Gravity.CENTER_HORIZONTAL }); space(page,24); page.addView(text(label,25f,bold=true,serif=true).apply { gravity=Gravity.CENTER }); space(page,24) }
    internal var continueBookId:String?=null
    private var continueCarousel:ContinueReading?=null
    fun continueCard() {
        if(ReadingQueue.books(books).isEmpty())return
        val carousel=continueCarousel?.takeIf{it.matches(books)}?:ContinueReading(this,books).also{continueCarousel=it}
        (carousel.parent as? ViewGroup)?.removeView(carousel)
        page.addView(carousel,LinearLayout.LayoutParams(-1,-2));space(page,30)
    }

    fun progressRow(parent:LinearLayout,book:JSONObject,stacked:Boolean=false) {
        val pct=(book.optJSONObject("progress")?.optDouble("pct",0.0) ?: 0.0).coerceIn(0.0,1.0)
        val bar=ThinProgress(this,pct,fg,if(light) 0xffdddddd.toInt() else 0xff333335.toInt())
        if(stacked) { parent.addView(bar,LinearLayout.LayoutParams(-1,d(3))); space(parent,6); parent.addView(text("${(pct*100).toInt()}%",12f,muted).apply { gravity=Gravity.END },LinearLayout.LayoutParams(-1,d(17))) }
        else { val row=row(); row.addView(bar,LinearLayout.LayoutParams(0,d(3),1f)); row.addView(text("${(pct*100).toInt()}%",13f,muted).apply { setPadding(d(10),0,0,0) }); parent.addView(row) }
    }
    fun columns():Int = when { resources.configuration.screenWidthDp>=980 ->4; resources.configuration.screenWidthDp>=680 ->3; else ->2 }
    fun grid(items:List<View>, cols:Int, gap:Int=20) {
        items.chunked(cols).forEach { batch -> val r=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.TOP; clipChildren=false; clipToPadding=false; if(gap==24) setPadding(d(2),d(6),d(5),0) }; for(i in 0 until cols) { if(i>0) r.addView(View(this),LinearLayout.LayoutParams(d(gap),1)); r.addView(batch.getOrNull(i) ?: View(this),LinearLayout.LayoutParams(0,-2,1f)) }; page.addView(r,LinearLayout.LayoutParams(-1,-2)); space(page,if(gap==24) 30 else if(resources.configuration.screenWidthDp>=680) 40 else 30) }
    }
    fun bookGrid(source:List<JSONObject>) {
        val list=source.toMutableList()
        val width=(resources.configuration.screenWidthDp-40-(columns()-1)*24)/columns()
        fun cell(index:Int):View { val book=list[index];return col().apply {
            clipChildren=false;clipToPadding=false
            addView(CoverView(this@MainActivity,book,cover(book)),LinearLayout.LayoutParams(-1,-2))
            space(this,16); addView(text(book.str("title"),15f).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }); space(this,4); addView(text(authors(book),14f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); if((book.optJSONObject("progress")?.optDouble("pct") ?: 0.0)>0) { space(this,12); progressRow(this,book,true) }
            setOnClickListener { detail(book,this) }; contentDescription="${book.str("title")}, ${authors(book)}"
        } }
        val cols=columns();val gap=d(24);val paint=android.text.TextPaint().apply { textSize=15*resources.displayMetrics.scaledDensity;typeface=Typeface.DEFAULT }
        val virtual=VirtualGrid(this,list.size,cols,gap,d(30),{ row,w -> val batch=list.subList(row*cols,minOf(list.size,(row+1)*cols)); val lines=batch.maxOfOrNull { if(paint.measureText(it.str("title"))>w)2 else 1 } ?: 1
            (w*1.5f).toInt()+d(16)+(18*resources.displayMetrics.scaledDensity*lines).toInt()+d(4)+(17*resources.displayMetrics.scaledDensity).toInt()+if(batch.any{(it.optJSONObject("progress")?.optDouble("pct") ?: 0.0)>0})d(38)else 0
        },::cell);virtual.reorder=ShelfReorder(this,virtual,list) { saveGridOrder(it) };page.addView(col().apply{clipChildren=false;clipToPadding=false;setPadding(d(2),d(6),d(5),0);addView(virtual,LinearLayout.LayoutParams(-1,-2))},LinearLayout.LayoutParams(-1,-2))
    }
    fun shelfGrid() {
        val byShelf=books.groupBy{it.str("shelf")};val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys.filter{it.isNotBlank()}+REC_SHELF).distinct()
        val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
        data class ShelfItem(val name:String,val list:List<JSONObject>,val add:Boolean=false,val action:()->Unit)
        val items=manual.map{name->ShelfItem(name,byShelf[name].orEmpty()){pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;shelf=name;render(false,false)}}.toMutableList()
        val status=books.groupBy{Library.status(it)};listOf("want","reading","done").forEach{key->items.add(ShelfItem(autoTitle(key),status[key].orEmpty()){pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;auto=key;render(false,false)})};items.add(ShelfItem("Новая полка",emptyList(),true){createShelf()})
        val grid=VirtualGrid(this,items.size,columns(),d(if(resources.configuration.screenWidthDp>=680)28 else 18),d(if(resources.configuration.screenWidthDp>=680)40 else 30),{_,w->(w*.94f).toInt()+d(16)+(d(62)*resources.configuration.fontScale.coerceAtLeast(1f)).toInt()},{i->val item=items[i];shelfCell(item.name,item.list,item.add,item.action).apply{if(i<manual.size)setOnLongClickListener{shelfActions(item.name);true}}});page.addView(grid,LinearLayout.LayoutParams(-1,-2))
    }
    fun shelfCell(name:String,list:List<JSONObject>,add:Boolean,click:()->Unit):View {
        val width=(resources.configuration.screenWidthDp-40-(columns()-1)*20)/columns()
        return col().apply {
            clipChildren=false;clipToPadding=false
            addView(ShelfArt(this@MainActivity,list.take(3).map { it to cover(it) },light,add),LinearLayout.LayoutParams(-1,-2))
            space(this,12)
            addView(text(name,15f,if(add) muted else fg).apply { gravity=Gravity.CENTER; maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END },LinearLayout.LayoutParams(-1,(d(42)*resources.configuration.fontScale.coerceAtLeast(1f)).toInt()))
            space(this,4)
            addView(text(if(add) " " else countBooks(list.size),14f,muted).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(-1,(d(20)*resources.configuration.fontScale.coerceAtLeast(1f)).toInt())); setOnClickListener { click() }
        }
    }
    fun navigation() {
        val nav=nativeNavigation ?: PolkaNavigation(this).also { nativeNavigation=it }
        nav.bind(tab) { key ->
            if(tab!=key || shelf!=null || auto!=null || quoteBookId!=null) { val from=listOf("home","shelves","quotes","foryou").indexOf(tab);val to=listOf("home","shelves","quotes","foryou").indexOf(key);tabScroll[tab]=scroll.scrollY;val outgoing=screenMotion.captureOutgoing(page,to>from);pendingMotion=null;librarySearch?.close(false);tab=key;shelf=null;auto=null;quoteBookId=null;query="";render(false,false);outgoing();scroll.post { scroll.scrollTo(0,tabScroll[key] ?: 0) } } else scroll.smoothScrollTo(0,0)
        }
        root.addView(nav,FrameLayout.LayoutParams(d(230),d(58),Gravity.BOTTOM or Gravity.START))
        followInsets(nav){i->nav.layoutParams=(nav.layoutParams as FrameLayout.LayoutParams).apply{leftMargin=d(16)+i.left;bottomMargin=navigationBottom(i)}}
        val add=icon("plus","Добавить книги",true) { pickBooks() }
        root.addView(add,FrameLayout.LayoutParams(d(58),d(58),Gravity.BOTTOM or Gravity.END))
        followInsets(add){i->add.layoutParams=(add.layoutParams as FrameLayout.LayoutParams).apply{rightMargin=d(16)+i.right;bottomMargin=navigationBottom(i)}}
    }
    fun filters() {
        val box=column()
        if(tab=="home" && shelf==null && auto==null) { label("Фильтры",box); val row=line(); val caption=column(); caption.addView(text("Рекомендации на главной",16f)); space(caption,6); caption.addView(text("Показывать в «Мои книги» вместе с вашими файлами",14f,muted)); row.addView(caption,LinearLayout.LayoutParams(0,-2,1f)); row.addView(PolkaSwitch(this).apply { contentDescription="Рекомендации на главной"; isChecked=prefs.optBoolean("showRecommendations"); thumbTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE); trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(0xff2ec95b.toInt(),0xff66666a.toInt())); setOnCheckedChangeListener { _,checked -> prefs.put("showRecommendations",checked); savePreferences(); render() } }); box.addView(row) }
        label("Сортировка книг",box)
        val group=RadioGroup(this); listOf("manual","recent","title","author").forEach { value -> group.addView(RadioButton(this).apply { id=View.generateViewId(); text=sortName(value); setTextColor(fg); textSize=16f; minHeight=d(48); buttonTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted)); tag=value; isChecked=sort==value; setOnClickListener { sort=value; prefs.put("sort",value); savePreferences(); render() } }) }; box.addView(group)
        dialog().setTitle("Фильтры и сортировка").setView(box).show()
    }
    fun sortName(key:String=sort):String = when(key) { "recent" -> "Недавние"; "title" -> "По названию"; "author" -> "По автору"; else -> "Мой порядок" }
    fun search() { librarySearch?.toggle() }
    fun inputDialog(title:String,value:String="",done:(String)->Unit) { val input=EditText(this).apply { setTextColor(fg); setHintTextColor(muted); setText(value); isSingleLine=true; setPadding(d(24),d(16),d(24),d(16)) }; dialog().setTitle(title).setView(input).setNegativeButton("Отмена",null).setPositiveButton("Готово") { _,_ -> done(input.text.toString().trim()) }.show() }
    fun createShelf() { inputDialog("Новая полка") { name -> if(name.isNotBlank()) { prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+name).distinct())); savePreferences(); render() } } }
    fun shelfActions(name:String) { dialog().setTitle(name).setItemIcons("pen","plus","trash").setDestructiveItems(2).setItems(arrayOf("Переименовать","Добавить книги на полку","Удалить полку")) { _,i -> when(i) {
        0 -> inputDialog("Название полки",name) { next -> if(next.isNotBlank() && next!=name) work({ library.db.runInTransaction { books.filter { it.str("shelf")==name }.forEach { library.save(it.copyJson().put("shelf",next)) }; prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty().filterNot { it==name }+next).distinct())); library.savePrefs(prefs) } }) { if(shelf==name) shelf=next; reload() } }
        1 -> { val checked=BooleanArray(books.size) { books[it].str("shelf")==name }; dialog().setTitle("Книги на полке").setMultiChoiceItems(books.map { it.str("title") }.toTypedArray(),checked) { _,which,on -> checked[which]=on }.setPositiveButton("Сохранить") { _,_ -> work({ library.db.runInTransaction { books.forEachIndexed { j,b -> if(checked[j]) library.save(b.copyJson().put("shelf",name)); else if(b.str("shelf")==name) library.save(b.copyJson().put("shelf","")) } } }) { reload() } }.setNegativeButton("Отмена",null).show() }
        2 -> confirm("Удалить полку?","Книги останутся в библиотеке.") { work({ library.db.runInTransaction { books.filter { it.str("shelf")==name }.forEach { library.save(it.copyJson().put("shelf","")) }; prefs.put("shelves",JSONArray(prefs.optJSONArray("shelves")?.strings().orEmpty().filterNot { it==name })); library.savePrefs(prefs) } }) { shelf=null; reload() } }
    } }.show() }
    fun confirm(title:String,message:String,action:()->Unit) { dialog().setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Продолжить") { _,_ -> action() }.show() }
    fun moveBook(book:JSONObject) { val names=(listOf("Без полки")+prefs.optJSONArray("shelves")?.strings().orEmpty()+books.map { it.str("shelf") }.filter { it.isNotBlank() }+REC_SHELF).distinct(); dialog().setTitle("Переместить на полку").setItemIcons(*names.map{"shelves"}.toTypedArray()).setItems(names.toTypedArray()) { _,i -> saveBook(book.copyJson().put("shelf",if(i==0) "" else names[i])) }.show() }
    private var loadingReader=false
    fun openBook(book:JSONObject,source:View?=null) {
        if(reader!=null || loadingReader || bookClosing)return
        if(book.optBoolean("noFile")) { bookActions(book); return }
        loadingReader=true
        val snapshot=openingSnapshot(book.str("id"),source)
        val preparing=snapshot?.let{BookOpeningHold(this,it).also{hold->hold.elevation=d(60).toFloat();root.addView(hold,FrameLayout.LayoutParams(-1,-1))}}
        work({ val content=library.dao.content(book.getString("id")) ?: error("Текст книги не найден"); val parsed=JSONObject(content.json); val images=mutableMapOf<String,ByteArray>(); parsed.optJSONObject("images")?.let { refs -> refs.keys().forEach { key -> refs.optJSONObject(key)?.str("__polkaBlob")?.let { id -> library.dao.asset(id)?.let { images[key]=it.bytes } } } }; runOnUiThread { loadingReader=false;if(isFinishing || isDestroyed) { preparing?.let{(it.parent as? ViewGroup)?.removeView(it)};snapshot?.release(); return@runOnUiThread }; val r=NativeReader(this,book.copyJson(),parsed,images); reader=r; ReaderWindow.configure(window,true,bg);root.setPadding(0,0,0,0);root.requestApplyInsets(); root.removeAllViews(); root.addView(r.view,FrameLayout.LayoutParams(-1,-1)); r.start(); if(snapshot!=null){r.view.alpha=0f;val hold=preparing?:BookOpeningHold(this,snapshot);root.addView(hold,FrameLayout.LayoutParams(-1,-1));var stable=0;var signature="";var handedOff=false;val observer=r.view.viewTreeObserver
                val detached=object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){if(!handedOff)snapshot.release();v.removeOnAttachStateChangeListener(this)}};r.view.addOnAttachStateChangeListener(detached)
                observer.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener {override fun onPreDraw():Boolean{val next="${r.view.width}:${r.view.height}:${r.body.text.hashCode()}:${r.pageBase()}";if(!r.openingReady() || next!=signature){signature=next;stable=0;r.view.postInvalidateOnAnimation();return true};if(++stable<2){r.view.postInvalidateOnAnimation();return true};if(observer.isAlive)observer.removeOnPreDrawListener(this);handedOff=true;r.view.removeOnAttachStateChangeListener(detached);r.view.alpha=1f;root.removeView(hold);revealNativeBook(snapshot,r.view);return true}})} } }, failed={ loadingReader=false;preparing?.let{(it.parent as? ViewGroup)?.removeView(it)};snapshot?.release() })
    }
    fun closeReader() {
        if(bookClosing)return
        val r=reader?:return
        if(!r.requestClose())return
        root.childrenOpening()?.finish()
        r.dispose() // Save the final position once, before any reverse-motion time is credited.
        val updated=r.book.copyJson();books=books.map{if(it.str("id")==updated.str("id"))updated else it}
        reader=null
        if(prefs.str("motion")=="reduced" || r.view.width<=0 || isFinishing || isDestroyed){ReaderWindow.configure(window,false,bg);root.requestApplyInsets();render(true,false);return}
        returnNativeBook(r)
    }
    internal fun waitingForReturnDeck()=tab=="foryou"&&recommendationDeck==null
    internal fun finishBookReturn(){bookClosing=false;renderDeferred=false;ReaderWindow.configure(window,false,bg);root.requestApplyInsets()}
    fun pickBooks(code:Int=10) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,code==10) },code) }
    fun pickBackup() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },11) }
    fun exportBackup() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type="application/octet-stream"; putExtra(Intent.EXTRA_TITLE,"polka-${java.time.LocalDate.now()}.polka"); addCategory(Intent.CATEGORY_OPENABLE) },12) }
    fun fileName(uri:Uri):String { contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) return it.getString(0) }; return "book.fb2" }
    fun readLimited(uri:Uri):ByteArray { val input=contentResolver.openInputStream(uri) ?: error("Файл недоступен"); return input.use { val out=ByteArrayOutputStream(); val buf=ByteArray(32768); while(true) { val n=it.read(buf); if(n<0) break; require(out.size()+n<=128*1024*1024) { "Файл больше 128 МБ" }; out.write(buf,0,n) }; out.toByteArray() } }
    @Deprecated("Android activity result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK || data==null) return; if(!bootFinished) { pendingActivityResult=Triple(requestCode,resultCode,data); return }
        when(requestCode) {
            17 -> data.data?.let { uri -> val file=pendingCalendarImage ?: return;work({contentResolver.openOutputStream(uri,"wt")!!.use{out->file.inputStream().use{it.copyTo(out)}}}) }
            15 -> data.data?.let { uri -> val id=coverTarget ?: return; work({ val bytes=readLimited(uri); require(BitmapFactory.decodeByteArray(bytes,0,bytes.size)!=null) { "Неверный формат изображения" }; val asset=uid(); library.db.runInTransaction { library.dao.putAsset(AssetRow(asset,contentResolver.getType(uri) ?: "image/jpeg",bytes)); val b=JSONObject(library.dao.book(id)!!.json).put("cover",JSONObject().put("__polkaBlob",asset)).put("coverChanged",true); library.save(b) }; loadData() }) { books.firstOrNull { it.str("id")==id }?.let { fresh -> coverEditorBook?.takeIf { it.str("id")==id }?.put("cover",fresh.get("cover"))?.put("coverChanged",true); coverEditorView?.invalidate() }; render() } }
            16 -> data.data?.let { uri -> val spec=pendingExport ?: return; work({ val bytes=BookExport.build(library,spec.first,spec.second); contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) } }) }
            10,14 -> {
                val uris=if(data.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data.data)
                importDialog=ImportNotice(this,"Добавляю файл…")
                val target=if(requestCode==14) attachId else null
                work({
                    val parsed=mutableListOf<Imported>(); val errors=mutableListOf<String>(); val existing=library.allBooks(); var mismatch=""
                    uris.forEachIndexed { i,uri -> try {
                        runOnUiThread { importDialog?.setMessage(if(uris.size==1) "Добавляю файл…" else "Добавляю файл ${i+1} из ${uris.size}…") }
                        val name=fileName(uri); var item=BookParser.parse(readLimited(uri),name)
                        val old=if(target!=null) existing.firstOrNull { it.str("id")==target } else existing.firstOrNull { it.optBoolean("noFile") && it.str("title").equals(item.book.str("title"),true) && authors(it).equals(authors(item.book),true) }
                        if(old!=null) {
                            if(target!=null && (!old.str("title").equals(item.book.str("title"),true) || !authors(old).equals(authors(item.book),true))) mismatch="В файле: «${item.book.str("title")}», ${authors(item.book)}.\n\nПрикрепить его к «${old.str("title")}»?"
                            val merged=item.book.copyJson().put("id",old.getString("id")).put("shelf",old.str("shelf"))
                            listOf("quotes","notes","bookmarks","rating","rec","origin","addedAt","progress","openedAt","completedAt","pos","coverChanged","title","authors","series","seriesNo","annotation","genres","lang").forEach { key -> if(old.has(key)) merged.put(key,old.get(key)) }
                            item=Imported(merged,item.content.copyJson().put("id",old.getString("id")),item.assets)
                        } else if(shelf!=null) item.book.put("shelf",shelf)
                        if(old==null && (existing+parsed.map { it.book }).any { it.str("title").equals(item.book.str("title"),true) && authors(it).equals(authors(item.book),true) && !it.optBoolean("noFile") }) errors.add("$name: уже добавлена") else parsed.add(item)
                    } catch(e:Exception) { errors.add("${fileName(uri)}: ${e.message}") } }
                    runOnUiThread {
                        importDialog?.dismiss(); importDialog=null
                        fun commit() { work({ if(target!=null && parsed.isNotEmpty()) ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety"); if(parsed.isNotEmpty()) library.importBooks(parsed); loadData() }) {
                            reader=null; render(); val resultNotice=ImportNotice(this,"Добавлено книг: ${parsed.size}",false); android.os.Handler(mainLooper).postDelayed({ resultNotice.dismiss() },2400); checkpointSoon()
                            if(errors.isNotEmpty()) dialog().setTitle("Не добавлены файлы").setMessage(errors.joinToString("\n\n")).setPositiveButton("Закрыть",null).show()
                        } }
                        if(mismatch.isNotBlank()) confirm("Данные книги отличаются",mismatch) { commit() } else commit()
                    }
                })
            }
            11 -> data.data?.let { uri -> confirm("Восстановить библиотеку?","Текущая библиотека будет заменена. Перед заменой сохранится локальная точка восстановления.") { work({ val temp=File(cacheDir,"restore-input.polka"); try { contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }; ArchiveIO.restore(applicationContext,library,temp) { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety") }; loadData() } finally { temp.delete() } }) { initialized=true; reader=null; shelf=null; auto=null; render(); checkpointSoon() } } }
            12 -> data.data?.let { uri -> work({
                val temp=File(cacheDir,"export.polka"); try { ArchiveIO.write(library,temp); contentResolver.openOutputStream(uri,"wt")!!.use { out -> FileInputStream(temp).use { it.copyTo(out) } }; getSharedPreferences("health",0).edit().putLong("exportedAt",System.currentTimeMillis()).apply() } finally { temp.delete() }
            }) }
            13 -> data.data?.let { uri -> work({ val bytes=readLimited(uri); val cat=JSONObject(String(bytes,Charsets.UTF_8)); require(cat.getJSONArray("b").length()>0) { "Каталог пуст" }; val temp=File(filesDir,"recs.tmp"); temp.writeBytes(bytes); java.nio.file.Files.move(temp.toPath(),File(filesDir,"recs.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE) }) { render() } }
        }
    }
    fun localRecovery() {
        val files=File(filesDir,"checkpoints").listFiles()?.filter { it.extension=="polka" }?.sortedByDescending { it.lastModified() }.orEmpty()
        val labels=files.map { (when(it.nameWithoutExtension) { "latest"->"Последняя"; "previous"->"Предыдущая"; else->"Перед изменением" })+" · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())) }
        dialog().setTitle("Локальные копии").setItemIcons(*(files.map{"undo"}+"plus").toTypedArray()).setItems((labels+"Создать точку сейчас").toTypedArray()) { _,i -> if(i==files.size) work({ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"))}); else confirm("Восстановить точку?","Текущая библиотека будет заменена.") { work({ val source=File(cacheDir,"recovery-input.polka"); try { files[i].copyTo(source,true); ArchiveIO.restore(applicationContext,library,source) { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety") }; loadData() } finally { source.delete() } }) { initialized=true; reader=null; shelf=null; auto=null; render(); checkpointSoon() } } }.setNegativeButton("Закрыть",null).show()
    }
    @Deprecated("Back dispatcher") override fun onBackPressed() { when { bookClosing -> Unit; bookCalendar?.isShowing==true -> bookCalendar?.dismiss(); shelfReorder!=null -> { shelfReorder?.cancel();flushDeferredRender() }; recommendationExpansion?.isShowing==true -> recommendationExpansion?.dismiss();bookDetailSheet?.isShowing==true -> bookDetailSheet?.dismiss();quoteBookId!=null && reader==null -> openQuoteGroup(null); profileOverlay!=null -> dismissProfile(); reader!=null -> closeReader(); searchOpen || query.isNotBlank() -> librarySearch?.close(); shelf!=null || auto!=null -> { shelf=null; auto=null; tab="shelves"; render() }; query.isNotBlank() -> { query=""; render() }; tab!="home" -> { tab="home"; render() }; else -> super.onBackPressed() } }
    override fun onSaveInstanceState(out:Bundle) { reader?.save(); out.putString("calendarImage",pendingCalendarImage?.name); out.putString("attachId",attachId); out.putString("coverTarget",coverTarget); pendingExport?.let { out.putString("exportId",it.first.str("id")); out.putString("exportFormat",it.second) }; out.putString("tab",tab); out.putString("shelf",shelf); out.putString("auto",auto); out.putString("query",query); out.putBoolean("searchOpen",searchOpen); reader?.let { out.putString("readerId",it.book.str("id")) }; super.onSaveInstanceState(out) }
    override fun onPause() { shelfReorder?.cancel();screenMotion.stop();root.childrenOpening()?.finish();reader?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); if(reader!=null)reader?.resume() else if(::root.isInitialized){ReaderWindow.configure(window,false,bg);root.requestApplyInsets()} }
    override fun onWindowFocusChanged(hasFocus:Boolean){super.onWindowFocusChanged(hasFocus);if(hasFocus&&::root.isInitialized){ReaderWindow.configure(window,reader!=null,bg);root.requestApplyInsets()}}
    override fun onDestroy() { destroying=true;root.childrenOpening()?.finish();bookCalendar?.dismiss();shelfReorder?.cancel();reader?.dispose(); recommendationExpansion?.stopMotion();bookDetailSheet?.finish();checkpointTask?.cancel(false); io.execute { if(initialized) try { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints")) } catch(_:Exception) {}; library.db.close() }; io.shutdown();cpu.shutdownNow(); super.onDestroy() }
    companion object {
        fun authors(book:JSONObject)=book.optJSONArray("authors")?.strings()?.joinToString(", ").orEmpty()
        fun autoTitle(key:String)=when(key) { "reading"->"Читаю"; "done"->"Прочитано"; else->"Хочу прочитать" }
        fun countBooks(n:Int):String { val word=if(n%100 in 11..14) "книг" else when(n%10) { 1->"книга";2,3,4->"книги";else->"книг" }; return "$n $word" }
    }
}
