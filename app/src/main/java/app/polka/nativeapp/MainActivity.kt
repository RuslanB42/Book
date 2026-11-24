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
    /** Which quote is on top of each book's deck on the «Цитаты» tab. */
    internal val quoteDeckIndex=mutableMapOf<String,Int>()
    private val tabScroll=mutableMapOf<String,Int>()
    private val io = Executors.newSingleThreadScheduledExecutor()
    private var checkpointTask: java.util.concurrent.ScheduledFuture<*>? = null
    private var initialized = false
    private var bootFinished = false
    var prefs = JSONObject()
    var books = listOf<JSONObject>()
    val covers = object:android.util.LruCache<String,Bitmap>((Runtime.getRuntime().maxMemory()/1024/4).toInt().coerceAtLeast(16*1024)) { override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024 }
    lateinit var root: FrameLayout
    lateinit var page: LinearLayout
    internal lateinit var scroll: ScrollView
    internal var nativeNavigation:PolkaNavigation?=null
    internal var tab = "home"
    internal var shelf: String? = null
    internal var auto: String? = null
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
    /** Safe area without the keyboard, and the keyboard's height as it is right now (it follows the keyboard's own
     *  slide frame by frame, so nothing jumps when it opens or closes). */
    internal var baseInsets=androidx.core.graphics.Insets.NONE
    internal var imeBottom=0
    internal var imeAnimating=false
    /** The keyboard's height on screen this very frame, whoever follows it. */
    internal var imeNow=0
    /** Whether the library page makes room for the keyboard: only when it types into the page itself (search), never
     *  for a sheet over it (a shelf name, a note) — the page then stays exactly where it is. */
    internal var pageIme=false
    /** The keyboard's height the last time it was fully open (0 until it has been). */
    /** Whether the keyboard types into the reader's own page (the inline chapter editor). A sheet or dialog over the
     *  reader (a note, a search) is another window: the page must not give the keyboard room then, or it re-lays its
     *  text out and jumps each time the keyboard opens and closes. */
    internal fun readerTyping()=window.decorView.hasWindowFocus()&&currentFocus is android.widget.EditText
    /** While comics are open (their own full-screen window), sheets keep the system bars hidden too. */
    internal var immersiveSheets=false
    /** The reader's safe area without the keyboard, so its keyboard room can follow the slide. */
    internal var readerBase=intArrayOf(0,0,0,0)
    /** While set, it gets the keyboard's height on every frame of its slide and moves what it needs with a transform;
     *  the page is not re-padded or laid out again at all. Dropped when the keyboard has fully gone. */
    internal var imeFollower:((Int)->Unit)?=null
    private var imeShown=false
    internal fun pushInsets(){val s=baseInsets;updateAppInsets(androidx.core.graphics.Insets.of(s.left,s.top,s.right,maxOf(s.bottom,imeBottom)))}
    internal fun setIme(bottom:Int){if(bottom!=imeBottom){imeBottom=bottom;pushInsets()}}
    /** The keyboard went away (back, swipe, «готово»): the text field lets go too, so no caret keeps blinking. */
    internal fun imeVisible(shown:Boolean){if(imeShown&&!shown)root.post{releaseTextFocus()};imeShown=shown}
    internal fun releaseTextFocus(){if(isFinishing||isDestroyed)return;val f=currentFocus as? android.widget.EditText?:return;if(!f.isAttachedToWindow)return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)
        androidx.core.view.WindowCompat.getInsetsController(window,f).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        root.isFocusableInTouchMode=true;root.requestFocus();f.clearFocus()}
    internal fun updateAppInsets(safe:androidx.core.graphics.Insets){
        appInsets=safe
        // Backgrounds, scrims and scroll viewports ALWAYS occupy the entire window.
        root.setPadding(0,0,0,0)
        insetConsumers.values.toList().forEach{it(safe)}
    }
    internal var shelvesEditing=false
    internal var shelfEditor:ShelfEditor?=null
    /** Adds a just-created shelf to the «Полки» page in place (it grows in at the end). */
    internal var addShelfRow:((String)->Unit)?=null
    /** Removes a shelf from the «Полки» page in place (it slides away and the gap closes); false if it is not shown. */
    internal var removeShelfRow:((String)->Boolean)?=null
    internal var settingsPage:View?=null
    internal var wordPage:View?=null
    internal var addMenu:AddMenu?=null
    internal var addButton:View?=null
    internal var shelvesTopBarLabel:TextView?=null
    internal var shelfReorder:ShelfReorder?=null
    var sort = "manual"
    var attachId: String? = null
    private var importDialog: Notice? = null
    /** Books deleted a moment ago that can still be brought back («Вернуть»): hidden, removed when the notice goes. */
    internal val pendingDeletes=mutableSetOf<String>()
    /** Books deleted while one «Книга удалена» notice is up: they share it (one «Вернуть» brings them all back). */
    internal var deleteBatch=mutableListOf<JSONObject>();internal var deleteNotice:Notice?=null
    internal var freshBookIds=mutableSetOf<String>()
    /** Set by add/delete: the next render animates the grid from its old layout. */
    internal var gridMotion=false
    internal fun hasScroll()=::scroll.isInitialized
    internal var bookCalendar:BookCalendarDialog?=null
    internal var goalRoad:GoalRoadOverlay?=null
    internal var tierList:TierListOverlay?=null
    /** The tier-list card on the statistics screen now (the screen is rebuilt when a book is saved). */
    var pendingCalendarImage:File?=null
    var profileOverlay:View?=null
    var profileStats:JSONObject?=null
    var profileRange=0
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
    /** After a file or cover changed in the database: decode the new cover first, then update every open view of the book at once. */
    fun refreshBookViews(id:String?,then:()->Unit={}) {
        val fresh=books.firstOrNull { it.str("id")==id }
        fun publish() { if(fresh!=null){ bookObservers.values.toList().filter { it.first==fresh.str("id") }.forEach { it.second(fresh.copyJson()) }; coverEditorBook?.takeIf { it.str("id")==fresh.str("id") }?.let { e-> fresh.optJSONObject("cover")?.let { e.put("cover",it) } ?: e.remove("cover"); coverEditorView?.invalidate() } }; then() }
        if(fresh?.optJSONObject("cover")?.str("__polkaBlob").isNullOrBlank()) publish() else requestCover(fresh!!) { publish() }
    }
    fun refreshProfile() { val data=profileStats ?: return; val y=(profileOverlay as? ScrollView)?.scrollY ?: 0; profileOverlay?.let { root.removeView(it) }; showProfile(data,profileRange,false); profileOverlay?.post { (profileOverlay as? ScrollView)?.scrollTo(0,y) } }
    var coverTarget:String?=null
    var coverEditorBook:JSONObject?=null
    var coverEditorView:View?=null
    var pendingExport:Pair<JSONObject,String>?=null
    private var pendingActivityResult:Triple<Int,Int,Intent>?=null
    var reader: NativeReader? = null
    /** The comic/PDF reader on screen, for pausing its count with the app. */
    internal var pageReading:PageReader?=null
    internal var bookClosing=false
    val light get() = when(prefs.str("ui",prefs.str("theme","dark"))) { "light" -> true; "system" -> resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES; else -> false }
    val bg get() = if(light) 0xfff2f2f0.toInt() else 0xff161617.toInt()
    val surface get() = if(light) 0xffffffff.toInt() else 0xff1f1f21.toInt()
    val fg get() = if(light) 0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
    val muted get() = if(light) 0xff686868.toInt() else 0xffa4a4a4.toInt()
    /** No coloured accent: highlights use the interface's own ink (near-black / near-white). */
    val accent get() = if(light) 0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
    fun d(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 16): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius=d(radius).toFloat() }
    @android.annotation.SuppressLint("WrongConstant")
    fun text(value: String, size: Float=16f, color: Int=fg, bold: Boolean=false, serif: Boolean=false): TextView = TextView(this).apply { text=value; textSize=size; setTextColor(color); typeface=if(serif) PolkaFonts.serif(this@MainActivity,bold) else PolkaFonts.sans(if(bold)600 else 400); includeFontPadding=false; cssLeading(1.2f); if(serif) breakStrategy=android.text.Layout.BREAK_STRATEGY_BALANCED }
    fun button(value: String, primary: Boolean=false, onClick: () -> Unit): TextView = text(value,16f, if(primary) bg else fg,true).apply { gravity=Gravity.CENTER; minHeight=d(48); setPadding(d(16),d(12),d(16),d(12)); background=if(primary)shape(fg,28)else this@MainActivity.controlSurface(this,28f); setOnClickListener { onClick() }; press(this) }
    fun icon(kind:String, label:String, selected:Boolean=false, action:()->Unit): View = IconView(this,kind,if(selected) bg else fg).apply { contentDescription=label; isClickable=true; isFocusable=true; background=if(selected)shape(fg,28)else this@MainActivity.controlSurface(this,28f); setOnClickListener { action() };press(this) }
    fun col()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    fun space(parent: LinearLayout, height: Int) { parent.addView(View(this),LinearLayout.LayoutParams(1,d(height))) }
    fun addIcon(parent: LinearLayout, kind:String,label:String,action:()->Unit) { parent.addView(icon(kind,label,action=action),LinearLayout.LayoutParams(d(44),d(44)).apply { marginStart=if(parent.childCount==0) -d(4) else d(8) }) }
    /** Called with every finger-down anywhere in the window — lets a popover close on a tap outside of it. */
    /** Taps inside these do not take focus away from the text field being typed in. */
    internal val focusKeepers=java.util.Collections.newSetFromMap(java.util.WeakHashMap<View,Boolean>())
    internal val touchWatchers=java.util.concurrent.CopyOnWriteArrayList<(android.view.MotionEvent)->Unit>()
    private val swipeBack by lazy{SwipeBack(this)}
    private var wasDeep=false
    /** The list under a shelf / a book's quotes, as it was when it was left (see render), and where it was scrolled. */
    internal var backdrop:android.graphics.Bitmap?=null
    internal var backdropY=0
    private var backdropTab="shelves"
    internal fun captureBackdrop(){wasDeep=true;if(!::scroll.isInitialized||root.width<=0||root.height<=0)return
        // the whole screen as it is, the navigation included: it goes with the list it belongs to
        backdrop=try{val b=android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888)
            val c=android.graphics.Canvas(b);c.drawColor(bg);root.draw(c);backdropY=scroll.scrollY;backdropTab=tab;b}catch(e:Throwable){null}}
    override fun dispatchTouchEvent(ev:android.view.MotionEvent):Boolean{if(ev.actionMasked==android.view.MotionEvent.ACTION_DOWN)touchWatchers.forEach{it(ev)}
        if(::scroll.isInitialized&&swipeBack.handle(ev){val now=android.os.SystemClock.uptimeMillis();val c=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,ev.x,ev.y,0);super.dispatchTouchEvent(c);c.recycle()})return true
        return super.dispatchTouchEvent(ev)}
    /** An overlay (settings, profile) was swiped off the screen: it goes without a second animation. */
    internal fun swipedAway(v:View){when{v===settingsPage->settingsPage=null;v===wordPage->wordPage=null;v===profileOverlay->{profileOverlay=null;profileStats=null}};root.removeView(v)}
    /** A swipe back finished: the list this page came from is already uncovered; it is built live where it was. */
    internal fun swipedBack(){if(shelf==null&&auto==null)return;navRevealed=true
        val y=backdropY;val back=if(backdrop!=null)backdropTab else "shelves";shelf=null;auto=null;query="";tab=back;render(false,false);restoreScroll(y)}
    /** Scrolls the new page to [y] after its layout but before its first frame (a posted scroll showed a frame at the top). */
    private fun restoreScroll(y:Int){val sv=scroll;sv.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{sv.viewTreeObserver.removeOnPreDrawListener(this);sv.scrollTo(0,y);return true}})}
    /** Into a shelf (yours or a status one). From its card on «Полки» ([from], its fan) the fanned covers fly into
     *  the shelf's first places while its page fades in (see [ShelfFlight]); otherwise see [Depth]: the shelf's page
     *  comes in from the right over the list (a picture of it, the navigation in it), which moves left and darkens. */
    internal fun enterShelf(from:ShelfFan?=null,change:()->Unit){val before=if(::scroll.isInitialized)scroll else null
        val spots=from?.let{f->f.books.indices.mapNotNull{i->f.spot(i)?.let{(r,t)->ShelfFlight.Spot(f.books[i],r,t)}}}.orEmpty()
        val fanAt=from?.takeIf{spots.isNotEmpty()}?.let{f->val at=IntArray(2);f.getLocationOnScreen(at);android.graphics.RectF(at[0].toFloat(),at[1].toFloat(),(at[0]+f.width).toFloat(),(at[1]+f.height).toFloat())}
        change();render(false,false)
        val b=backdrop;val page=scroll;if(b==null||page===before||prefs.str("motion")=="reduced")return
        val img=ImageView(this).apply{setImageBitmap(b);scaleType=ImageView.ScaleType.MATRIX;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        root.addView(img,0,FrameLayout.LayoutParams(b.width,b.height))
        // the page is see-through (the window colour shows through it); over the list it must be solid
        val bgWas=page.background;page.setBackgroundColor(bg)
        if(fanAt!=null){ShelfFlight.open(this,page,img,fanAt,spots){root.removeView(img);page.background=bgWas};return}
        Depth(this,page).enter{root.removeView(img);page.background=bgWas}}
    /** Out of a shelf by its back button or the system back: the shelf's page goes to the right and the list it was
     *  opened from comes back from the left, out of the dark, where it was left. */
    fun leaveShelf(){
        val pic=if(prefs.str("motion")=="reduced"||root.width<=0||root.height<=0)null else try{android.graphics.Bitmap.createBitmap(root.width,root.height,android.graphics.Bitmap.Config.ARGB_8888).also{val c=android.graphics.Canvas(it);c.drawColor(bg);root.draw(c)}}catch(e:Throwable){null}
        // back to «Полки»: the first books fly home into the shelf's fan (see [ShelfFlight.back])
        val key=shelf?.let{"s:$it"}?:auto?.let{"a:$it"};val cells=if(pic!=null)ShelfFlight.cells(this) else emptyMap()
        navRevealed=true
        val y=backdropY;val back=if(backdrop!=null)backdropTab else "shelves";shelf=null;auto=null;query="";tab=back;render(false,false);restoreScroll(y)
        if(pic==null)return
        val img=ImageView(this).apply{setImageBitmap(pic);isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        root.addView(img,FrameLayout.LayoutParams(-1,-1))
        val done={root.removeView(img);img.setImageDrawable(null);pic.recycle()}
        ShelfFlight.back(this,key,cells,img,pic,done){Depth(this,img).leave(done)}}
    override fun onCreate(savedInstanceState: Bundle?) {
        PolkaFonts.init(this)
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        attachId=savedInstanceState?.getString("attachId"); coverTarget=savedInstanceState?.getString("coverTarget")
        window.statusBarColor=Color.TRANSPARENT; window.navigationBarColor=Color.TRANSPARENT
        library=Library(LibraryDb.open(applicationContext))
        root=FrameLayout(this).apply{clipChildren=false}; root.setBackgroundColor(bg)
        ReaderWindow.configure(window,false,bg)
        SafeArea.bind(root) { insets -> ReaderWindow.apply(this,insets.toWindowInsets()!!) }
        // follow the keyboard's slide instead of jumping to its final height
        androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(root,object:androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE){
            private fun isIme(list:List<androidx.core.view.WindowInsetsAnimationCompat>)=list.any{it.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime()!=0}
            override fun onPrepare(animation:androidx.core.view.WindowInsetsAnimationCompat){if(animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime()!=0){imeAnimating=true;if(imeBottom==0)pageIme=readerTyping()}}

            override fun onProgress(insets:androidx.core.view.WindowInsetsCompat,running:List<androidx.core.view.WindowInsetsAnimationCompat>):androidx.core.view.WindowInsetsCompat{
                if(isIme(running)){val h=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom;imeNow=h;val r=reader
                    if(r==null){val f=imeFollower;if(f!=null)f(h) else setIme(if(pageIme)h else 0)}else if(readerTyping()){val b=readerBase;r.safeArea(b[0],b[1],b[2],maxOf(b[3],h))}};return insets}
            override fun onEnd(animation:androidx.core.view.WindowInsetsAnimationCompat){if(animation.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime()==0)return
                imeAnimating=false;val now=androidx.core.view.ViewCompat.getRootWindowInsets(root);val ime=now?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0
                imeNow=ime;val r=reader
                if(r==null){val f=imeFollower;if(f!=null){f(ime);if(ime==0)imeFollower=null}else setIme(if(pageIme)ime else 0)}else{val b=readerBase;r.safeArea(b[0],b[1],b[2],maxOf(b[3],if(readerTyping())ime else 0))};imeVisible(ime>0)}})
        ImeKeeper(root,d(20)){imeNow}.attach()
        // a tap anywhere outside the focused text field closes the keyboard and lets the field go
        touchWatchers.add{e->val f=currentFocus as? android.widget.EditText;if(f!=null&&reader==null){val r=android.graphics.Rect();f.getGlobalVisibleRect(r);val x=e.rawX.toInt();val y=e.rawY.toInt();if(!r.contains(x,y)&&focusKeepers.none{k->k.isAttachedToWindow&&android.graphics.Rect().also{q->k.getGlobalVisibleRect(q)}.contains(x,y)})root.post{releaseTextFocus()}}}
        setContentView(root)
        root.requestApplyInsets()
        root.addView(bootLogo(),FrameLayout.LayoutParams(-1,-1))
        work({ library.initialize(); File(filesDir,"recs.json").delete(); loadData() }) { initialized=true; bootFinished=true; savedInstanceState?.getString("calendarImage")?.let{pendingCalendarImage=File(File(cacheDir,"calendar-share"),it)}; savedInstanceState?.getString("exportId")?.let { id -> books.firstOrNull { it.str("id")==id }?.let { pendingExport=it to (savedInstanceState.getString("exportFormat") ?: "fb2") } }; tab=savedInstanceState?.getString("tab") ?: "home"; shelf=savedInstanceState?.getString("shelf"); auto=savedInstanceState?.getString("auto"); query=savedInstanceState?.getString("query").orEmpty();searchOpen=savedInstanceState?.getBoolean("searchOpen",query.isNotEmpty()) ?: false; val bootView=root.findViewWithTag<View>("polka-boot-logo"); render(); revealFromBoot(bootView); savedInstanceState?.getString("readerId")?.let { id -> books.firstOrNull { it.str("id")==id }?.let { openBook(it) } }; pendingActivityResult?.let { pendingActivityResult=null; onActivityResult(it.first,it.second,it.third) } }
        if(savedInstanceState==null)openIncoming(intent)
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); openIncoming(intent) }
    /** A book opened from another app («Открыть с помощью» in Telegram, a file manager…) is added like a picked one. */
    @Suppress("DEPRECATION")
    private fun openIncoming(incoming:Intent?) {
        if(incoming?.action!=Intent.ACTION_VIEW)return
        val uri=incoming.data?:return
        incoming.action=null
        if(reader!=null)closeReader()
        onActivityResult(10,RESULT_OK,Intent().setData(uri))
    }
    fun loadData() {
        prefs=library.prefs(); if(!prefs.has("size")) prefs.put("size",prefs.optInt("fontSize",19)); if(!prefs.has("ui")) prefs.put("ui",prefs.str("theme","dark")); books=library.allBooks().filterNot { it.str("id") in pendingDeletes }.onEach { if(QuoteNotes.fold(it)) library.save(it) }.also { AuthorPhotos.prefetch(this,it) }; sort=prefs.str("sort","manual")
    }
    fun requestCover(book:JSONObject,done:(Bitmap?)->Unit) {
        val id=book.optJSONObject("cover")?.str("__polkaBlob")?.takeIf { it.isNotBlank() } ?: return
        covers.get(id)?.let { done(it); return }
        work({
            val bytes=library.dao.asset(id)?.bytes
            val bitmap=bytes?.let { BookImages.decode(it,900) }
            if(bitmap!=null) covers.put(id,bitmap)
            runOnUiThread { if(!isDestroyed) done(bitmap) }
        })
    }

    /** The cover in its original resolution (up to 2400 px) for large views; a tiny cache of its own. */
    private val fullCovers = object:android.util.LruCache<String,Bitmap>(3) {}
    fun requestFullCover(book:JSONObject,done:(Bitmap?)->Unit) {
        val id=book.optJSONObject("cover")?.str("__polkaBlob")?.takeIf { it.isNotBlank() } ?: return
        fullCovers.get(id)?.let { done(it); return }
        work({ val bitmap=library.dao.asset(id)?.bytes?.let { BookImages.decode(it,2400) }; if(bitmap!=null) fullCovers.put(id,bitmap); runOnUiThread { if(!isDestroyed) done(bitmap) } })
    }
    fun cover(book:JSONObject): Bitmap? = book.optJSONObject("cover")?.str("__polkaBlob")?.let { covers.get(it) }
    fun <T> compute(action:()->T,failed:((Exception)->Unit)?=null,done:(T)->Unit):java.util.concurrent.Future<*> = cpu.submit { try { val result=action();runOnUiThread { if(!isFinishing && !isDestroyed)done(result) } }catch(e:Exception){if(e !is InterruptedException && !Thread.currentThread().isInterrupted)runOnUiThread { if(!isFinishing && !isDestroyed){failed?.invoke(e);errorDialog(e)} }} }
    fun work(action:()->Unit, failed:((Exception)->Unit)?=null, done:()->Unit={}) { if(io.isShutdown) return; io.execute { try { action(); runOnUiThread { if(!isFinishing && !isDestroyed) done() } } catch(e:Exception) { runOnUiThread { importDialog?.dismiss(); importDialog=null; if(!isFinishing && !isDestroyed) { failed?.invoke(e); if(!initialized) { bootFinished=true; recoveryScreen(e) } else errorDialog(e) } } } } }
    fun dialog() = PolkaSheetBuilder(this)
    fun errorDialog(e:Throwable) { dialog().setTitle("Не удалось выполнить действие").setMessage(e.message ?: e.javaClass.simpleName).setPositiveButton("Закрыть",null).show() }
    fun reload() { work({loadData()}) { initialized=true; books.forEach { fresh -> bookObservers.values.toList().filter { it.first==fresh.str("id") }.forEach { it.second(fresh.copyJson()) } }; render(true,false); if(profileOverlay!=null) refreshProfile(); checkpointSoon() } }
    fun checkpointSoon() { checkpointTask?.cancel(false); checkpointTask=io.schedule({ try { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints")); getSharedPreferences("health",0).edit().remove("checkpointError").apply() } catch(e:Exception) { getSharedPreferences("health",0).edit().putString("checkpointError",e.message).apply() } }, 3, TimeUnit.SECONDS) }
    fun saveBook(book:JSONObject, done:()->Unit={}) { val copy=book.copyJson().put("modifiedAt",System.currentTimeMillis()); reader?.book?.takeIf { it.str("id")==copy.str("id") && it!==book }?.let { replaceJson(it,copy) }; work({library.save(copy)}) { publishBook(copy); if(reader==null) { render(true,false); if(profileOverlay!=null) refreshProfile() }; checkpointSoon(); done() } }
    fun savePreferences() { val copy=prefs.copyJson(); work({ library.savePrefs(copy) }) { checkpointSoon() } }
    fun title():String = when { query.isNotBlank() -> "Поиск"; shelf!=null -> shelf!!; auto!=null -> autoTitle(auto!!); tab=="shelves" -> "Полки"; tab=="quotes" -> "Цитаты"; tab=="words" -> "Слова"; else -> "Мои книги" }
    fun homeBooks()=books.toList()
    fun currentBooks():List<JSONObject> {
        if(query.isNotBlank())return searchBooks()
        var list=when { shelf!=null -> books.filter { it.str("shelf")==shelf }; auto!=null -> books.filter { Library.status(it)==auto }; else -> homeBooks() }
        if(query.isNotBlank()) list=list.filter { (it.str("title")+" "+authors(it)).contains(query,true) }
        return sortBooks(list)
    }
    /** One order for a shelf everywhere: inside it (where books can be dragged into place) and on its row on «Полки». */
    fun sortBooks(list:List<JSONObject>):List<JSONObject> = when(sort) { "title" -> list.sortedBy { it.str("title").lowercase() }; "author" -> list.sortedBy(::authors); "recent" -> list.sortedByDescending { maxOf(it.optLong("openedAt"),it.optLong("addedAt")) }; else -> list.sortedWith(compareBy<JSONObject> { it.optDouble("pos",it.optLong("addedAt").toDouble()) }.thenBy { it.str("title") }) }
    private var renderDeferred=false
    private var destroying=false
    fun flushDeferredRender(){if(!destroying&&!bookClosing&&!loadingReader&&renderDeferred&&reader==null&&bookDetailSheet==null&&shelfReorder==null&&!isFinishing&&!isDestroyed){renderDeferred=false;render(true,false)}}
    fun render(preservePosition:Boolean=false,animateEntry:Boolean=true,preparingBookReturn:Boolean=false) {
        if(reader!=null||destroying)return
        if(bookClosing&&!preparingBookReturn){renderDeferred=true;return}
        if(loadingReader){renderDeferred=true;return}
        // Going one level in (a shelf, a status shelf, a book's quotes): keep a picture of the list being left, so a
        // swipe back uncovers it under the finger, the same way the settings and the profile uncover the page below.
        val deep=shelf!=null||auto!=null
        if(deep&&!wasDeep)captureBackdrop()
        if(!deep)backdrop=null
        wasDeep=deep
        if(shelfReorder!=null || bookDetailSheet?.isShowing==true){renderDeferred=true;return}
        renderDeferred=false
        val oldY=if(preservePosition && ::scroll.isInitialized) scroll.scrollY else 0
        val gridSnap=pendingGrid?.also { pendingGrid=null; gridMotion=false } ?: if((gridMotion||android.os.SystemClock.uptimeMillis()<gridMovingUntil) && ::scroll.isInitialized && root.width>0) { gridMotion=false; captureGrid(books.map { it.str("id") }.toSet()) } else { gridMotion=false; null }
        val overlay=profileOverlay;val words=wordPage
        screenMotion.stop(); root.removeAllViews(); addMenu=null; root.setBackgroundColor(bg)
        if(!preparingBookReturn){ReaderWindow.configure(window,false,bg); root.requestApplyInsets()}
        if(!preparingBookReturn&&Build.VERSION.SDK_INT>=30) window.insetsController?.setSystemBarsAppearance(if(light) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false; clipChildren=false }
        val wrapper=col().apply { clipChildren=false; clipToPadding=false; setPadding(d(20),d(8),d(20),d(112)) };page=wrapper
        followInsets(wrapper){i->wrapper.setPadding(d(20)+i.left,d(8)+i.top,d(20)+i.right,d(82)+navigationBottom(i))}
        scroll.addView(wrapper); root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        // The top bar depends on the screen: search, profile and filters only where they mean something.
        val inShelf=shelf!=null||auto!=null
        if(!(tab=="shelves"&&!inShelf))shelvesEditing=false
        librarySearch=null
        when{
            tab=="home"&&!inShelf->{val top=LibrarySearchBar(this).apply{elevation=d(12).toFloat()};librarySearch=top;page.addView(top,LinearLayout.LayoutParams(-1,d(48)));space(page,20)}
            tab=="quotes"->{val head=row();head.addView(text("Цитаты",26f,fg,true,serif=true).apply{setPadding(d(2),0,d(14),0)},LinearLayout.LayoutParams(-2,-2))
                val top=LibrarySearchBar(this,compact=true).apply{elevation=d(12).toFloat()};librarySearch=top;head.addView(top,LinearLayout.LayoutParams(0,d(48),1f));page.addView(head,LinearLayout.LayoutParams(-1,d(48)));space(page,20)}
            tab=="shelves"&&!inShelf->{query="";searchOpen=false;page.addView(shelvesTopBar(),LinearLayout.LayoutParams(-1,d(48)));space(page,12)}
            tab=="words"->{query="";searchOpen=false;page.addView(text("Слова",26f,fg,true,serif=true).apply{setPadding(d(2),0,0,0);gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(-1,d(48)));space(page,14)}
            else->{query="";searchOpen=false;space(page,6)}
        }
        // The entry fade must not render the page into an offscreen layer: such a layer is clipped to the page,
        // which cut off the shelf glass (it reaches into the side margins) until the fade ended.
        page=col().apply{clipChildren=false;clipToPadding=false;forceHasOverlappingRendering(false)};wrapper.addView(page,LinearLayout.LayoutParams(-1,-2))
        renderLibraryResults()
        navigation(); overlay?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }; words?.let { root.addView(it,FrameLayout.LayoutParams(-1,-1)) }; tierList?.reattach(); if(preservePosition) scroll.post { scroll.scrollTo(0,oldY) }; gridSnap?.let { playGrid(it,if(preservePosition) oldY else null) }; if(gridSnap==null&&animateEntry)screenMotion.play(page,MotionPose(1f,y=d(10).toFloat()),MotionSpec.PAGE_IN)
    }
    internal fun searchBooks():List<JSONObject> {val q=query.trim();return (if(tab=="home")homeBooks()else books).filter{(it.str("title")+" "+authors(it)+" "+it.str("series")+" "+it.str("shelf")).contains(q,true)}.sortedByDescending{maxOf(it.optLong("openedAt"),it.optLong("addedAt"))}}
    internal fun renderLibraryResults(){
        if(reader!=null || !::page.isInitialized)return
        screenMotion.stop();page.removeAllViews()

        when {
            tab=="words" -> wordsPage()
            tab=="quotes" -> quotes()
            query.isNotBlank() -> {val found=searchBooks();if(found.isEmpty()){space(page,96);page.addView(emptyView("search","Ничего не нашлось","Попробуйте название, автора или серию."),LinearLayout.LayoutParams(-1,-2))}else{val row=line();row.addView(text("Найдено",17f,bold=true),LinearLayout.LayoutParams(0,-2,1f));row.addView(text(countBooks(found.size),14f,muted));page.addView(row);space(page,14);bookGrid(found)}}
            shelf!=null || auto!=null -> { val list=currentBooks(); shelfHead(list); if(list.isEmpty()){space(page,96);page.addView(if(auto=="reading")emptyView("open","Ничего не читаете","Откройте любую книгу — она появится здесь.") else emptyView("shelf","Полка пустая","Добавьте книги через «О книге» или в режиме редактирования."),LinearLayout.LayoutParams(-1,-2))} else shelfGrid(list) }
            tab=="shelves" && query.isBlank() -> shelfRows()
            tab=="quotes" -> quotes()
            else -> {
                if(query.isBlank()) continueCard()
                val list=currentBooks()
                if(list.isEmpty()) { space(page,96); page.addView(if(query.isNotBlank()) emptyView("search","Ничего не нашлось","Попробуйте название, автора или серию.") else emptyView("books","Пока нет книг","Добавьте epub или fb2 — с телефона или из Telegram.",action="＋  Добавить книги"){ pickBooks() },LinearLayout.LayoutParams(-1,-2)) } else bookGrid(list)
            }
        }

    }
    /** An open shelf's head (approved «Полка открыта»): back and filters in the corners, the shelf's name in the middle,
     *  how many books are on it, and a search through this shelf only. */
    private fun shelfHead(list:List<JSONObject>) { val head=FrameLayout(this)
        head.addView(icon("back","Назад к полкам") { leaveShelf() },FrameLayout.LayoutParams(d(44),d(44),Gravity.START or Gravity.CENTER_VERTICAL).apply{marginStart=-d(4)})
        head.addView(text(title(),26f,fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply{leftMargin=d(52);rightMargin=d(52)})
        head.addView(icon("filter","Фильтры и сортировка",action={ filters() }),FrameLayout.LayoutParams(d(44),d(44),Gravity.END or Gravity.CENTER_VERTICAL))
        page.addView(head,LinearLayout.LayoutParams(-1,d(44)))
        if(list.isEmpty()){space(page,18);return}
        page.addView(text(countBooks(list.size),13.5f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(4)})
        shelfSearch=null
        val field=EditText(this).apply{hint="Найти на полке";setHintTextColor(muted);setTextColor(fg);textSize=15f;typeface=PolkaFonts.sans(400);background=null;setSingleLine(true);setPadding(0,0,0,0)
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH;setOnEditorActionListener{v,_,_->(getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(v.windowToken,0);true}}
        val bar=row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(16),0,d(16),0);background=shape(if(light)0x0f000000 else 0x12ffffff,22)}
        bar.addView(IconView(this,"search",muted),LinearLayout.LayoutParams(d(18),d(18)).apply{marginEnd=d(10)});bar.addView(field,LinearLayout.LayoutParams(0,-2,1f))
        page.addView(bar,LinearLayout.LayoutParams(-1,d(44)).apply{topMargin=d(16);bottomMargin=d(22)})
        shelfSearch=field }
    private var shelfSearch:EditText?=null
    /** The open shelf's books; its search narrows them in place (no dragging into a new order while it does). */
    private fun shelfGrid(list:List<JSONObject>) { val host=col().apply{clipChildren=false;clipToPadding=false};page.addView(host,LinearLayout.LayoutParams(-1,-2))
        fun show(q:String){host.removeAllViews();val found=if(q.isBlank())list else list.filter{(it.str("title")+" "+authors(it)+" "+it.str("series")).contains(q,true)}
            if(found.isEmpty())host.addView(text("Ничего не нашлось",15f,muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(40)})
            else bookGrid(found,host,reorder=q.isBlank())}
        show("");shelfSearch?.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
            override fun afterTextChanged(s:android.text.Editable?){show(s?.toString()?.trim().orEmpty())}}) }
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
    fun bookGrid(source:List<JSONObject>,into:LinearLayout=page,reorder:Boolean=true) {
        val list=source.toMutableList()
        val width=(resources.configuration.screenWidthDp-40-(columns()-1)*24)/columns()
        fun cell(index:Int):View { val book=list[index];return col().apply {
            clipChildren=false;clipToPadding=false
            addView(CoverView(this@MainActivity,book,cover(book)),LinearLayout.LayoutParams(-1,-2))
            space(this,16); addView(text(book.str("title"),15f).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }); space(this,4); addView(text(authors(book),14f,muted).apply { maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END }); if((book.optJSONObject("progress")?.optDouble("pct") ?: 0.0)>0) { space(this,12); progressRow(this,book,true) }
            setOnClickListener { detail(book,this) }; contentDescription="${book.str("title")}, ${authors(book)}"; tag="grid:"+book.str("id")
        } }
        val cols=columns();val gap=d(24);val paint=android.text.TextPaint().apply { textSize=15*resources.displayMetrics.scaledDensity;typeface=PolkaFonts.sans(400) }
        val virtual=VirtualGrid(this,list.size,cols,gap,d(30),{ row,w -> val batch=list.subList(row*cols,minOf(list.size,(row+1)*cols)); val lines=batch.maxOfOrNull { if(paint.measureText(it.str("title"))>w)2 else 1 } ?: 1
            (w*1.5f).toInt()+d(16)+(18*resources.displayMetrics.scaledDensity*lines).toInt()+d(4)+(17*resources.displayMetrics.scaledDensity).toInt()+if(batch.any{(it.optJSONObject("progress")?.optDouble("pct") ?: 0.0)>0})d(38)else 0
        },::cell);if(reorder)virtual.reorder=ShelfReorder(this,virtual,list) { saveGridOrder(it) };into.addView(col().apply{clipChildren=false;clipToPadding=false;setPadding(d(2),d(6),d(5),0);addView(virtual,LinearLayout.LayoutParams(-1,-2))},LinearLayout.LayoutParams(-1,-2))
    }
    fun navigation() {
        val nav=nativeNavigation ?: PolkaNavigation(this).also { nativeNavigation=it }
        nav.bind(tab) { key -> goTab(key) }
        root.addView(nav,FrameLayout.LayoutParams(d(230),d(58),Gravity.BOTTOM or Gravity.START))
        followInsets(nav){i->nav.layoutParams=(nav.layoutParams as FrameLayout.LayoutParams).apply{leftMargin=d(16)+i.left;bottomMargin=navigationBottom(baseInsets)}}
        val add=icon("plus","Добавить",true) { if(addMenu!=null) addMenu?.close() else AddMenu(this).open() }; addButton=add;add.background=Floating(this,add.background,d(29).toFloat());add.elevation=0f
        root.addView(add,FrameLayout.LayoutParams(d(58),d(58),Gravity.BOTTOM or Gravity.END))
        followInsets(add){i->add.layoutParams=(add.layoutParams as FrameLayout.LayoutParams).apply{rightMargin=d(16)+i.right;bottomMargin=navigationBottom(baseInsets)}}
        // Inside a shelf there is no navigation, as in the profile: it goes into the shelf with the list it belongs to
        // (in its picture, see enterShelf) and comes back with it.
        val inside=shelf!=null||auto!=null;val was=navAway;navAway=inside
        val reduced=prefs.str("motion")=="reduced"
        listOf<View>(nav,add).forEach{v->v.animate().cancel();v.clipBounds=null
            when{
                inside->{v.visibility=View.INVISIBLE;v.alpha=1f;v.translationY=0f}
                was&&!navRevealed&&!reduced->{v.visibility=View.VISIBLE;v.alpha=0f;v.translationY=d(24).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(MotionSpec.PAGE_IN).setInterpolator(MotionSpec.enter).start()}
                else->{v.visibility=View.VISIBLE;v.alpha=1f;v.translationY=0f}}}
        navRevealed=false
    }
    private var navAway=false
    /** A swipe back has already uncovered the navigation: it does not come in a second time. */
    internal var navRevealed=false
    /** Switches to the tab [key] the way a tap on the navigation does: the pages slide, the pill glides. */
    fun goTab(key:String) {
            if(tab!=key || shelf!=null || auto!=null) { val from=listOf("home","shelves","quotes","words").indexOf(tab);val to=listOf("home","shelves","quotes","words").indexOf(key);tabScroll[tab]=scroll.scrollY;val oldScroll=scroll;screenMotion.stop();librarySearch?.close(false);tab=key;shelf=null;auto=null;query="";render(false,false)
                // Restore the tab's scroll after layout but BEFORE the first frame (a posted scroll showed one frame
                // at the top — with its top buttons — and then jumped).
                val target=tabScroll[key] ?: 0;val sv=scroll;sv.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{sv.viewTreeObserver.removeOnPreDrawListener(this);sv.scrollTo(0,target);return true}})
                screenMotion.pushTab(oldScroll,to>from) } else scroll.smoothScrollTo(0,0)
    }
    fun filters() {
        val box=column()
        label("Сортировка книг",box)
        val group=RadioGroup(this); listOf("manual","recent","title","author").forEach { value -> group.addView(RadioButton(this).apply { id=View.generateViewId(); text=sortName(value); setTextColor(fg); textSize=16f; minHeight=d(48); buttonTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,muted)); tag=value; isChecked=sort==value; setOnClickListener { sort=value; prefs.put("sort",value); savePreferences(); render() } }) }; box.addView(group)
        dialog().setTitle("Фильтры и сортировка").setView(box).show()
    }
    fun sortName(key:String=sort):String = when(key) { "recent" -> "Недавние"; "title" -> "По названию"; "author" -> "По автору"; else -> "Мой порядок" }
    fun search() { librarySearch?.toggle() }
    /** «Новая полка» (the «+» menu, the home shelves): the page glides over to «Полки», the usual name window opens,
     *  and the new shelf then grows in at the end of the shelves while the page follows it down. */
    fun createShelf() {
        val reduced=prefs.str("motion")=="reduced"
        val away=tab!="shelves"||shelf!=null||auto!=null||query.isNotBlank()
        if(away)goTab("shelves")
        root.postDelayed({
            inputDialog("Новая полка",hint="Например, «Отпуск» или «Классика»",helper="Полку потом можно переименовать и переставить") { raw -> val name=raw.trim(); if(name.isBlank())return@inputDialog
                val taken=(prefs.optJSONArray("shelves")?.strings().orEmpty()+books.map{it.str("shelf")}).any{it.equals(name,true)}
                if(taken){Notice.short(this,Notice.Kind.SHELF,"Полка «$name» уже есть");return@inputDialog}
                prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+name).distinct())); savePreferences()
                val grow=addShelfRow
                if(grow==null||tab!="shelves"){render();return@inputDialog}
                // after the window has gone: the shelf grows in, the page rides down with it
                root.postDelayed({grow(name);if(reduced)scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}},if(reduced)0L else 640L)
            }
        },if(away&&!reduced)340L else 0L)
    }
    /** [quiet]: the page has already shown the new name in place — update the data without rebuilding it. */
    fun renameShelf(name:String,next:String,quiet:Boolean=false) { if(next.isBlank() || next==name) return
        prefs.optJSONArray("shelfOrder")?.strings()?.let { order -> prefs.put("shelfOrder",JSONArray(order.map { if(it=="s:$name") "s:$next" else it })) }
        work({ library.db.runInTransaction { books.filter { it.str("shelf")==name }.forEach { library.save(it.copyJson().put("shelf",next)) }; prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty().filterNot { it==name }+next).distinct())); library.savePrefs(prefs) } }) { if(shelf==name) shelf=next; if(quiet) books.filter { it.str("shelf")==name }.forEach { it.put("shelf",next) } else reload() } }
    /** Remove a shelf the page has already animated away: the books stay in the library, nothing is rebuilt. */
    fun removeShelfQuietly(name:String) { work({ library.db.runInTransaction { books.filter { it.str("shelf")==name }.forEach { library.save(it.copyJson().put("shelf","")) }; prefs.put("shelves",JSONArray(prefs.optJSONArray("shelves")?.strings().orEmpty().filterNot { it==name })); library.savePrefs(prefs) } }) { books.filter { it.str("shelf")==name }.forEach { it.put("shelf","") }; if(shelf==name) shelf=null } }
    fun deleteShelf(name:String) { confirm("Удалить полку?","Книги останутся в библиотеке.") { if(tab=="shelves"&&shelf==null&&auto==null&&removeShelfRow!=null&&shelfEditor!=null){val gone=removeShelfRow;root.postDelayed({if(gone?.invoke(name)!=true)removeShelfQuietly(name).also{render()}},260);return@confirm}; work({ library.db.runInTransaction { books.filter { it.str("shelf")==name }.forEach { library.save(it.copyJson().put("shelf","")) }; prefs.put("shelves",JSONArray(prefs.optJSONArray("shelves")?.strings().orEmpty().filterNot { it==name })); library.savePrefs(prefs) } }) { shelf=null; reload() } } }
    fun shelfActions(name:String) { dialog().setTitle(name).setItemIcons("pen","plus","trash").setDestructiveItems(2).setItems(arrayOf("Переименовать","Добавить книги на полку","Удалить полку")) { _,i -> when(i) {
        0 -> inputDialog("Название полки",name,hint="Название полки",action="Сохранить") { next -> renameShelf(name,next) }
        1 -> { val checked=BooleanArray(books.size) { books[it].str("shelf")==name }; dialog().setTitle("Книги на полке").setMultiChoiceItems(books.map { it.str("title") }.toTypedArray(),checked) { _,which,on -> checked[which]=on }.setPositiveButton("Сохранить") { _,_ -> work({ library.db.runInTransaction { books.forEachIndexed { j,b -> if(checked[j]) library.save(b.copyJson().put("shelf",name)); else if(b.str("shelf")==name) library.save(b.copyJson().put("shelf","")) } } }) { reload() } }.setNegativeButton("Отмена",null).show() }
        2 -> deleteShelf(name)
    } }.show() }
    /** The quick menu of a status shelf («Хочу прочитать», «Читаю»…): open it, or put more books on it (they get that status). */
    fun autoShelfActions(key:String) { val title=autoTitle(key)
        dialog().setTitle(title).setItemIcons("next","plus").setItems(arrayOf("Открыть полку","Добавить книги на полку")) { _,i -> when(i) {
            0 -> enterShelf{auto=key}
            1 -> { val others=books.filter{Library.status(it)!=key}
                if(others.isEmpty())return@setItems
                val checked=BooleanArray(others.size)
                dialog().setTitle(title).setMultiChoiceItems(others.map{it.str("title")}.toTypedArray(),checked){_,which,on->checked[which]=on}
                    .setPositiveButton("Сохранить"){_,_->others.filterIndexed{j,_->checked[j]}.forEach{setBookStatus(it,key)}}.setNegativeButton("Отмена",null).show() }
        } }.show() }
    fun confirm(title:String,message:String,action:()->Unit) { dialog().setTitle(title).setMessage(message).setNegativeButton("Отмена",null).setPositiveButton("Продолжить") { _,_ -> action() }.show() }
    fun moveBook(book:JSONObject) { val names=(listOf("Без полки")+prefs.optJSONArray("shelves")?.strings().orEmpty()+books.map { it.str("shelf") }.filter { it.isNotBlank() }).distinct(); dialog().setTitle("Переместить на полку").setItemIcons(*names.map{"shelves"}.toTypedArray()).setItems(names.toTypedArray()) { _,i -> saveBook(book.copyJson().put("shelf",if(i==0) "" else names[i])) }.show() }
    private var loadingReader=false
    /** Book whose card the reader was opened from; closing the book returns to that card. */
    var returnDetailId:String?=null
    /** Parsed text of recently opened/likely-to-open books. Reading the whole book from the database and parsing
     *  it is the slow part of opening, so it is done ahead of time (detail card, «Продолжить чтение») and reused. */
    private val openedContent=object:LinkedHashMap<String,Pair<JSONObject,Map<String,ByteArray>>>(4,.75f,true){override fun removeEldestEntry(e:MutableMap.MutableEntry<String,Pair<JSONObject,Map<String,ByteArray>>>?)=size>2}
    private fun contentKey(book:JSONObject)="${book.str("id")}:${book.optLong("size")}:${book.optInt("edits")}:${book.optInt("chars")}:${book.str("format")}"
    private fun loadContent(book:JSONObject):Pair<JSONObject,Map<String,ByteArray>> {
        val key=contentKey(book);synchronized(openedContent){openedContent[key]}?.let{return it}
        val id=book.getString("id");val current=book.optJSONObject("progress")?.optInt("ch",0)?:0
        // Fast path: small index + only the chapter being read. Images are fetched when a page needs them.
        ChapterStore.read(filesDir,id,key,current)?.let{index->
            return (index to (LazyImages(library,index.optJSONObject("images")) as Map<String,ByteArray>)).also{synchronized(openedContent){openedContent[key]=it}}
        }
        val content=library.dao.content(id) ?: error("Текст книги не найден"); val parsed=JSONObject(content.json)
        val result:Pair<JSONObject,Map<String,ByteArray>> =parsed to LazyImages(library,parsed.optJSONObject("images"))
        synchronized(openedContent){openedContent[key]=result}
        // Next time this book opens from the per-chapter copy.
        io.execute{ChapterStore.write(filesDir,id,key,parsed)}
        return result
    }
    /** Chapter titles/lengths for a book without parsing its whole text when avoidable. */
    fun chapterIndex(book:JSONObject):org.json.JSONArray? {
        val key=contentKey(book);synchronized(openedContent){openedContent[key]}?.let{return it.first.optJSONArray("chapters")}
        ChapterStore.index(filesDir,book.str("id"),key)?.let{return it.optJSONArray("chapters")}
        return library.dao.content(book.str("id"))?.let{JSONObject(it.json).optJSONArray("chapters")}
    }
    private val prefetching=mutableSetOf<String>()
    fun prefetchContent(book:JSONObject){
        if(book.optBoolean("noFile")||reader!=null)return;val key=contentKey(book)
        if(synchronized(openedContent){openedContent.containsKey(key)}||!prefetching.add(key))return
        work({runCatching{loadContent(book)}},failed={prefetching.remove(key)}){prefetching.remove(key)}
    }
    fun openBook(book:JSONObject,source:View?=null) {
        if(reader!=null || loadingReader || bookClosing)return
        if(book.optBoolean("noFile")) { bookActions(book); return }
        if(PageDocs.isDoc(book)) { if(!PageDocs.dir(this,book).exists()) { Notice.short(this,Notice.Kind.ERROR,"Файл книги не найден"); return }; try { PageReader(this,book.copyJson()).show() } catch(e:Exception) { Notice.short(this,Notice.Kind.ERROR,e.message ?: "Не удалось открыть") }; return }
        if(bookDetailSheet==null)returnDetailId=null
        loadingReader=true
        val veil=if(prefs.str("motion")=="reduced") null else fadeVeil().also{root.addView(it,FrameLayout.LayoutParams(-1,-1))}
        var loaded:Pair<JSONObject,Map<String,ByteArray>>?=null;var dark=veil==null;var built=false;var picture:android.graphics.Bitmap?=null
        val pictureKey=PageImages.signature(this,root.width,root.height,book.optJSONObject("progress")?.optInt("ch",0)?:0,book.optJSONObject("progress")?.optInt("nativeOffset",-1)?:-1)
        fun build(){
            val content=loaded?:return;if(!dark||built)return;built=true;loadingReader=false
            if(isFinishing||isDestroyed){veil?.let{root.removeView(it)};return}
            val r=NativeReader(this,book.copyJson(),content.first,content.second);reader=r;ReaderWindow.configure(window,true,bg);root.setPadding(0,0,0,0);root.requestApplyInsets()
            (0 until root.childCount).map{root.getChildAt(it)}.filter{it!==veil}.forEach{root.removeView(it)}
            root.addView(r.view,0,FrameLayout.LayoutParams(-1,-1));r.start()
            if(veil==null){picture?.recycle();return}
            // The page as it was left: visible the moment the fade lifts; the live page replaces it when ready.
            val still=picture?.let{bmp->android.widget.ImageView(this).apply{setImageBitmap(bmp);scaleType=android.widget.ImageView.ScaleType.FIT_XY;isClickable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}}
            still?.let{root.addView(it,1,FrameLayout.LayoutParams(-1,-1));veil.animate().alpha(0f).setDuration(MotionSpec.DARK_OUT).setInterpolator(MotionSpec.enter).withEndAction{root.removeView(veil)}.start()}
            // Lift the dark only when the first page is laid out and stable.
            var stable=0;var signature="";val observer=r.view.viewTreeObserver
            observer.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
                if(veil.parent==null&&still==null){if(observer.isAlive)observer.removeOnPreDrawListener(this);return true}
                val next="${r.view.width}:${r.view.height}:${r.body.text.hashCode()}:${r.pageBase()}"
                if(!r.openingReady()||next!=signature){signature=next;stable=0;r.view.postInvalidateOnAnimation();return true}
                if(++stable<2){r.view.postInvalidateOnAnimation();return true}
                if(observer.isAlive)observer.removeOnPreDrawListener(this)
                if(still!=null){root.removeView(still);picture?.recycle()}else veil.animate().alpha(0f).setDuration(MotionSpec.DARK_OUT).setInterpolator(MotionSpec.enter).withEndAction{root.removeView(veil)}.start();return true}})
        }
        veil?.animate()?.alpha(1f)?.setDuration(MotionSpec.DARK_IN)?.setInterpolator(MotionSpec.exit)?.withEndAction{dark=true;build()}?.start()
        work({ loaded=loadContent(book);if(veil!=null)picture=PageImages.load(this,book.str("id"),pictureKey) }, failed={ loadingReader=false;veil?.let{v->v.animate().cancel();root.removeView(v)};flushDeferredRender() }) { build() }
    }
    fun closeReader() {
        if(bookClosing)return
        val r=reader?:return
        if(!r.requestClose())return
        root.childrenOpening()?.finish()
        if(prefs.str("motion")!="reduced")r.pageImage()?.let{(key,image)->PageImages.save(this,r.book.str("id"),key,image)}
        // closed on the last page after reading it: the book is read, and its end is shown once it has closed
        val finished=r.finishOnClose()
        r.dispose() // Save the final position once, before any reverse-motion time is credited.
        val updated=r.book.copyJson();books=books.map{if(it.str("id")==updated.str("id"))updated else it}
        reader=null
        if(prefs.str("motion")=="reduced" || r.view.width<=0 || isFinishing || isDestroyed){ReaderWindow.configure(window,false,bg);root.requestApplyInsets();render(true,false);if(finished&&!isFinishing&&!isDestroyed)BookFinish(this,updated){}.show();return}
        closeWithFade(r)
        if(finished)root.postDelayed({if(!isFinishing&&!isDestroyed)BookFinish(this,updated){}.show()},520)
    }
    internal fun finishBookReturn(){bookClosing=false;renderDeferred=false;ReaderWindow.configure(window,false,bg);root.requestApplyInsets()}
    fun pickBooks(code:Int=10) { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,code==10) },code) }
    private var soundPicked:((org.json.JSONObject)->Unit)?=null
    /** Picks an audio file for the background sounds; [done] gets it once it is checked and stored. */
    fun pickSound(done:(org.json.JSONObject)->Unit) { soundPicked=done; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="audio/*"; addCategory(Intent.CATEGORY_OPENABLE) },19) }
    private var fontPicked:((String)->Unit)?=null
    /** Picks a TTF/OTF file; [done] gets the new font's key once it is checked and stored. */
    fun pickFont(done:(String)->Unit) { fontPicked=done; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("font/*","application/x-font-ttf","application/x-font-otf","application/font-sfnt","application/vnd.ms-opentype","application/zip","application/x-zip-compressed","application/octet-stream")) },18) }
    private var authorPhotoPicked:((ByteArray)->Unit)?=null
    /** A note waiting for its photos (see [NotePhotos.pick]); [cameraFile] is where the camera writes one. */
    internal var notePhotosPicked:((List<ByteArray>)->Unit)?=null
    internal var cameraFile:File?=null
    /** Picks the reader's own photo for an author; [done] gets the image's bytes (read off the main thread). */
    fun pickAuthorPhoto(done:(ByteArray)->Unit) { authorPhotoPicked=done; startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) },20) }
    fun pickBackup() { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },11) }
    fun exportBackup() { startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type="application/octet-stream"; putExtra(Intent.EXTRA_TITLE,"polka-${java.time.LocalDate.now()}.polka"); addCategory(Intent.CATEGORY_OPENABLE) },12) }
    fun fileName(uri:Uri):String { contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) return it.getString(0) }; return "book.fb2" }
    fun readLimited(uri:Uri):ByteArray { val input=contentResolver.openInputStream(uri) ?: error("Файл недоступен"); return input.use { val out=ByteArrayOutputStream(); val buf=ByteArray(32768); while(true) { val n=it.read(buf); if(n<0) break; require(out.size()+n<=128*1024*1024) { "Файл больше 128 МБ" }; out.write(buf,0,n) }; out.toByteArray() } }
    @Deprecated("Android activity result API") override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        // the camera gives no data back: the photo is in the file it was given
        if(requestCode==22) { val f=cameraFile; cameraFile=null; if(resultCode==RESULT_OK && f!=null && f.length()>0) NotePhotos.received(this) { listOf(f.readBytes()).also { f.delete() } } else { f?.delete(); notePhotosPicked=null }; return }
        if(requestCode==21 && resultCode!=RESULT_OK) notePhotosPicked=null
        if(resultCode!=RESULT_OK || data==null) return; if(!bootFinished) { pendingActivityResult=Triple(requestCode,resultCode,data); return }
        when(requestCode) {
            21 -> { val uris=data.clipData?.let { c -> (0 until c.itemCount).map { c.getItemAt(it).uri } } ?: listOfNotNull(data.data)
                NotePhotos.received(this) { uris.take(NotePhotos.MAX).mapNotNull { u -> runCatching { readLimited(u) }.getOrNull()?.takeIf { it.size<=40*1024*1024 } } } }
            20 -> data.data?.let { uri -> val done=authorPhotoPicked ?: return; authorPhotoPicked=null; var bytes:ByteArray?=null
                work({ bytes=readLimited(uri).takeIf { it.size<=30*1024*1024 } }) { bytes?.let(done) ?: Notice.short(this,Notice.Kind.ERROR,"Файл слишком большой") } }
            19 -> data.data?.let { uri -> val done=soundPicked ?: return; soundPicked=null; val name=fileName(uri); var added:org.json.JSONObject?=null; var reason=""
                work({ try { val bytes=readLimited(uri); require(bytes.size<=80*1024*1024) { "Файл слишком большой" }; added=Ambience.add(this,bytes,name) } catch(e:Exception) { reason=e.message ?: "Не удалось открыть звук" } }) {
                    val got=added; if(got!=null) { Ambience.register(this,got); savePreferences(); done(got); Notice.short(this,Notice.Kind.OK,"Звук «${got.str("name")}» добавлен") } else Notice.short(this,Notice.Kind.ERROR,reason) } }
            18 -> data.data?.let { uri -> val done=fontPicked ?: return; fontPicked=null; val name=fileName(uri); var added:List<Pair<String,String>> =emptyList(); var reason=""
                work({ try { val bytes=readLimited(uri); require(bytes.size<=60*1024*1024) { "Файл слишком большой" }; added=CustomFonts.add(this,bytes,name) } catch(e:Exception) { reason=e.message ?: "Не удалось открыть шрифт" } }) {
                    if(added.isNotEmpty()) { added.forEach { CustomFonts.register(this,it.first,it.second) }; done(added[0].first)
                        Notice.short(this,Notice.Kind.OK,if(added.size==1) "Шрифт «${added[0].second}» добавлен" else "Добавлено шрифтов: ${added.size}") } else Notice.short(this,Notice.Kind.ERROR,reason) } }
            17 -> data.data?.let { uri -> val file=pendingCalendarImage ?: return;work({contentResolver.openOutputStream(uri,"wt")!!.use{out->file.inputStream().use{it.copyTo(out)}}}) }
            15 -> data.data?.let { uri -> val id=coverTarget ?: return; work({ val bytes=readLimited(uri); require(BitmapFactory.decodeByteArray(bytes,0,bytes.size)!=null) { "Неверный формат изображения" }; val asset=uid(); library.db.runInTransaction { library.dao.putAsset(AssetRow(asset,contentResolver.getType(uri) ?: "image/jpeg",bytes)); val b=JSONObject(library.dao.book(id)!!.json).put("cover",JSONObject().put("__polkaBlob",asset)).put("coverChanged",true); library.save(b) }; loadData() }) { refreshBookViews(id) { render() } } }
            16 -> data.data?.let { uri -> val spec=pendingExport ?: return; work({ val bytes=if(spec.second=="doc") PageDocs.export(this,spec.first).second else BookExport.build(library,spec.first,spec.second); contentResolver.openOutputStream(uri,"wt")!!.use { it.write(bytes) } }) }
            10,14 -> {
                val uris=if(data.clipData!=null) (0 until data.clipData!!.itemCount).map { data.clipData!!.getItemAt(it).uri } else listOfNotNull(data.data)
                val attaching=requestCode==14
                importDialog=Notice.card(this,Notice.State(Notice.Kind.LOADING,if(attaching) "Прикрепляю файл…" else if(uris.size==1) "Добавляю книгу…" else "Добавляю книги",if(uris.size==1) fileName(uris[0]) else "1 из ${uris.size}",blank=true,progress=if(uris.size>1) 0f else null))
                val target=if(attaching) attachId else null
                work({
                    val parsed=mutableListOf<Imported>(); val failures=mutableListOf<String>(); val known=mutableListOf<JSONObject>(); val existing=library.allBooks(); var mismatch=""
                    uris.forEachIndexed { i,uri -> try {
                        val name=fileName(uri)
                        runOnUiThread { if(uris.size>1) importDialog?.setProgress("Добавляю книги","${i+1} из ${uris.size} · $name",i/uris.size.toFloat()) }
                        var item=if(PageDocs.isPagesName(name)) PageDocs.import(this,uri,null,name) else { val bytes=readLimited(uri); val kind=TextFormats.kind(name,bytes.copyOf(minOf(bytes.size,128)))
                            fun magic(m:String)=bytes.size>=m.length&&m.indices.all{bytes[it]==m[it].code.toByte()}
                            if(magic("%PDF")||magic("Rar!")||magic("7z\u00bc\u00af")||(magic("PK")&&PageDocs.zipIsComic(bytes))) PageDocs.import(this,null,bytes,name) else if(kind!=null) TextFormats.parse(kind,bytes,name) else BookParser.parse(bytes,name) }
                        val old=if(target!=null) existing.firstOrNull { it.str("id")==target } else existing.firstOrNull { it.optBoolean("noFile") && it.str("title").equals(item.book.str("title"),true) && authors(it).equals(authors(item.book),true) }
                        if(old!=null) {
                            if(target!=null && (!old.str("title").equals(item.book.str("title"),true) || !authors(old).equals(authors(item.book),true))) mismatch="В файле: «${item.book.str("title")}», ${authors(item.book)}.\n\nПрикрепить его к «${old.str("title")}»?"
                            val merged=item.book.copyJson().put("id",old.getString("id")).put("shelf",old.str("shelf"))
                            listOf("quotes","notes","bookmarks","rating","review","reviewTags","reviewAt","rec","origin","addedAt","progress","openedAt","completedAt","state","startedAt","pos","coverChanged","title","authors","series","seriesNo","annotation","genres","lang").forEach { key -> if(old.has(key)) merged.put(key,old.get(key)) }
                            item=Imported(merged,item.content.copyJson().put("id",old.getString("id")),item.assets)
                        } else if(shelf!=null) item.book.put("shelf",shelf)
                        val same=if(old==null) (existing+parsed.map { it.book }).firstOrNull { it.str("title").equals(item.book.str("title"),true) && authors(it).equals(authors(item.book),true) && !it.optBoolean("noFile") } else null
                        if(same!=null) known.add(same) else parsed.add(item)
                    } catch(e:Exception) { failures.add("${fileName(uri)}: ${e.message ?: "не удалось прочитать файл"}") } }
                    runOnUiThread {
                        val loader=importDialog; importDialog=null
                        fun commit(loader:Notice?) { work({ if(target!=null && parsed.isNotEmpty()) ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety"); if(parsed.isNotEmpty()) library.importBooks(parsed); loadData() },{ e -> val n=loader ?: Notice.card(this,Notice.State(Notice.Kind.LOADING,"",blank=true)); n.update(Notice.State(Notice.Kind.ERROR,if(uris.size==1) "Книга не добавлена" else "Книги не добавлены","Не удалось сохранить в библиотеку",blank=true)) }) {
                            if(target==null) freshBookIds.addAll(parsed.map { it.book.str("id") })
                            reader=null; gridMotion=true; refreshBookViews(target) { render(true,false) }; root.postDelayed({ freshBookIds.clear() },1500); checkpointSoon()
                            val ids=parsed.map { it.book.str("id") }; val added=ids.mapNotNull { id -> books.firstOrNull { it.str("id")==id } }
                            val details:()->Unit={ val lines=failures+known.map { "«${it.str("title")}»: уже в библиотеке" }; dialog().setTitle("Не добавлено").setMessage(lines.joinToString("\n\n")).setPositiveButton("Закрыть",null).show() }
                            val reasons=listOfNotNull(failures.size.takeIf { it>0 }?.let { n -> if(n==1) "1 файл не удалось прочитать" else "$n ${fileWord(n)} не удалось прочитать" },known.size.takeIf { it>0 }?.let { n -> if(n==1) "1 уже в библиотеке" else "$n уже в библиотеке" }).joinToString(" · ")
                            val state=when {
                                target!=null && added.isNotEmpty() -> Notice.State(Notice.Kind.ATTACHED,"Файл прикреплён",added[0].str("title"),added)
                                added.isEmpty() && failures.isEmpty() && known.size==1 -> known[0].let { k -> Notice.State(Notice.Kind.KNOWN,"Уже в библиотеке",k.str("title"),listOf(k),action="Открыть",onAction={ books.firstOrNull { it.str("id")==k.str("id") }?.let { detail(it) } }) }
                                added.isEmpty() && failures.isEmpty() -> Notice.State(Notice.Kind.KNOWN,"Уже в библиотеке","Все ${countBooks(known.size)}",known)
                                added.isEmpty() -> Notice.State(Notice.Kind.ERROR,if(uris.size==1) (if(attaching) "Файл не прикреплён" else "Книга не добавлена") else "Книги не добавлены",if(uris.size==1) "Не удалось прочитать файл" else reasons,blank=true,action="Подробнее",onAction=details)
                                failures.isEmpty() && known.isEmpty() -> Notice.State(Notice.Kind.OK,if(added.size==1) "Книга добавлена" else "Добавлено ${countBooks(added.size)}",added.joinToString(", ") { it.str("title") },added)
                                else -> Notice.State(Notice.Kind.PARTIAL,"Добавлено ${added.size} из ${uris.size}",reasons,added,action="Подробнее",onAction=details)
                            }
                            if(loader!=null) loader.update(state) else Notice.card(this,state)
                        } }
                        if(mismatch.isNotBlank()) { loader?.dismiss(); confirm("Данные книги отличаются",mismatch) { commit(Notice.card(this,Notice.State(Notice.Kind.LOADING,"Прикрепляю файл…",blank=true))) } } else commit(loader)
                    }
                })
            }
            11 -> data.data?.let { uri -> confirm("Восстановить библиотеку?","Текущая библиотека будет заменена. Перед заменой сохранится локальная точка восстановления.") { work({ val temp=File(cacheDir,"restore-input.polka"); try { contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { input.copyTo(it) } }; ArchiveIO.restore(applicationContext,library,temp) { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety") }; loadData() } finally { temp.delete() } }) { initialized=true; reader=null; shelf=null; auto=null; render(); checkpointSoon() } } }
            12 -> data.data?.let { uri -> work({
                val temp=File(cacheDir,"export.polka"); try { ArchiveIO.write(library,temp,File(filesDir,"docs")); contentResolver.openOutputStream(uri,"wt")!!.use { out -> FileInputStream(temp).use { it.copyTo(out) } }; getSharedPreferences("health",0).edit().putLong("exportedAt",System.currentTimeMillis()).apply() } finally { temp.delete() }
            }) }
        }
    }
    fun localRecovery() {
        val files=File(filesDir,"checkpoints").listFiles()?.filter { it.extension=="polka" }?.sortedByDescending { it.lastModified() }.orEmpty()
        val labels=files.map { (when(it.nameWithoutExtension) { "latest"->"Последняя"; "previous"->"Предыдущая"; else->"Перед изменением" })+" · "+java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it.lastModified())) }
        dialog().setTitle("Локальные копии").setItemIcons(*(files.map{"undo"}+"plus").toTypedArray()).setItems((labels+"Создать точку сейчас").toTypedArray()) { _,i -> if(i==files.size) work({ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"))}); else confirm("Восстановить точку?","Текущая библиотека будет заменена.") { work({ val source=File(cacheDir,"recovery-input.polka"); try { files[i].copyTo(source,true); ArchiveIO.restore(applicationContext,library,source) { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety") }; loadData() } finally { source.delete() } }) { initialized=true; reader=null; shelf=null; auto=null; render(); checkpointSoon() } } }.setNegativeButton("Закрыть",null).show()
    }
    @Deprecated("Back dispatcher") override fun onBackPressed() { when { bookClosing -> Unit; goalRoad!=null -> goalRoad?.back(); tierList!=null && bookDetailSheet?.isShowing!=true -> tierList?.back(); addMenu!=null -> addMenu?.close(); shelvesEditing && reader==null -> { shelvesTopBarLabel?.performClick() }; bookCalendar?.isShowing==true -> bookCalendar?.dismiss(); shelfReorder!=null -> { shelfReorder?.cancel();flushDeferredRender() };bookDetailSheet?.isShowing==true -> bookDetailSheet?.dismiss();wordPage!=null -> closeWord(); settingsPage!=null -> closeSettings(); profileOverlay!=null -> dismissProfile(); reader!=null -> closeReader(); searchOpen || query.isNotBlank() -> librarySearch?.close(); shelf!=null || auto!=null -> leaveShelf(); query.isNotBlank() -> { query=""; render() }; tab!="home" -> { tab="home"; render() }; else -> super.onBackPressed() } }
    override fun onSaveInstanceState(out:Bundle) { reader?.save(); out.putString("calendarImage",pendingCalendarImage?.name); out.putString("attachId",attachId); out.putString("coverTarget",coverTarget); pendingExport?.let { out.putString("exportId",it.first.str("id")); out.putString("exportFormat",it.second) }; out.putString("tab",tab); out.putString("shelf",shelf); out.putString("auto",auto); out.putString("query",query); out.putBoolean("searchOpen",searchOpen); reader?.let { out.putString("readerId",it.book.str("id")) }; super.onSaveInstanceState(out) }
    override fun onPause() { shelfReorder?.cancel();screenMotion.stop();root.childrenOpening()?.finish();reader?.pause();pageReading?.pause(); super.onPause() }
    override fun onResume() { super.onResume(); pageReading?.resume(); if(reader!=null)reader?.resume() else if(::root.isInitialized){ReaderWindow.configure(window,false,bg);root.requestApplyInsets()} }
    /** The extra keyboard trigger: when the app's window gets the focus back (a sheet or dialog over it has closed) and
     *  a text field of the page still holds the view focus — for one, a search field keeps it invisibly after its
     *  search closed — Android shows the keyboard for that field by itself (adjustResize + a focused text editor), and
     *  the page puts it away again a moment later: the keyboard popped up after the name window had closed. Before
     *  Android looks (this runs before it starts the input), the focus goes to the page itself, which is no text field,
     *  unless the field is really in use (an open search, the reader's own editor). */
    private fun keepKeyboardDown(){if(!::root.isInitialized)return;val f=currentFocus as? android.widget.EditText?:return
        if(searchOpen||reader!=null)return
        root.isFocusable=true;root.isFocusableInTouchMode=true;root.requestFocus();f.clearFocus();if(currentFocus is android.widget.EditText)root.requestFocus()}
    override fun onWindowFocusChanged(hasFocus:Boolean){if(hasFocus)keepKeyboardDown();super.onWindowFocusChanged(hasFocus);if(hasFocus&&::root.isInitialized){ReaderWindow.configure(window,reader!=null,bg);root.requestApplyInsets()}}
    override fun onDestroy() { destroying=true; Notice.current?.takeIf { it.a===this }?.dismissNow();root.childrenOpening()?.finish();bookCalendar?.dismiss();shelfReorder?.cancel();reader?.dispose();bookDetailSheet?.finish();checkpointTask?.cancel(false); io.execute { if(initialized) try { ArchiveIO.checkpoint(library,File(filesDir,"checkpoints")) } catch(_:Exception) {}; library.db.close() }; io.shutdown();cpu.shutdownNow(); super.onDestroy() }
    companion object {
        fun authors(book:JSONObject)=book.optJSONArray("authors")?.strings()?.joinToString(", ").orEmpty()
        fun autoTitle(key:String)=when(key) { "reading"->"Читаю"; "done"->"Прочитано"; "paused"->"Отложено"; else->"Хочу прочитать" }
        fun fileWord(n:Int)=if(n%100 in 11..14) "файлов" else when(n%10) { 1->"файл";2,3,4->"файла";else->"файлов" }
        fun countBooks(n:Int):String { val word=if(n%100 in 11..14) "книг" else when(n%10) { 1->"книга";2,3,4->"книги";else->"книг" }; return "$n $word" }
    }
}
