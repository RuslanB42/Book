package app.polka.nativeapp

import android.app.AlertDialog
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.Selection
import android.view.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlin.math.abs

@android.annotation.SuppressLint("WrongConstant")
class NativeReader(private val a:MainActivity, val book:JSONObject, private val content:JSONObject, private val images:Map<String,ByteArray>) {
    val view=FrameLayout(a).apply { setBackgroundColor(a.bg);clipToPadding=false }
    private lateinit var topBar:LinearLayout
    private val folio=TextView(a)
    val body=TextView(a)
    val scroll=ReaderViewport(a).apply{lockPage={paged()&&!editorOpen}}
    internal val quoteSelection by lazy { ReaderQuoteSelection(a,this) }
    private val footer=TextView(a)
    private val remaining=TextView(a)
    private val chromeIcons=mutableListOf<IconView>()
    private val bottom=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL; setPadding(a.d(18),a.d(8),a.d(18),a.d(4)) }
    private val slider=ReaderRange(a)
    private val percent=TextView(a)
    private var scrubbing=false
    private val handler=Handler(Looper.getMainLooper())
    private var restoring=false
    var chapter=book.optJSONObject("progress")?.optInt("ch",0) ?: 0
    val chapters=content.getJSONArray("chapters")
    private var openedAt=System.currentTimeMillis()
    private var activeAt=openedAt
    private var lastCredit=openedAt
    private var paused=false
    private var opening=false
    private var openingReflowPending=false
    fun openingReady()=!preparing && chapterRich.isNotEmpty() && scroll.width>0 && scroll.height>0 && !view.isLayoutRequested && !body.isLayoutRequested && !scroll.isLayoutRequested
    fun openingArea():RectF { val r=Rect(); scroll.getDrawingRect(r); view.offsetDescendantRectToMyCoords(scroll,r); return RectF((r.left+body.paddingLeft).toFloat(),(r.top+body.paddingTop).toFloat(),(r.right-body.paddingRight).toFloat(),(r.bottom-body.paddingBottom).toFloat()) }
    fun openingChrome(alpha:Float){topBar.alpha=if(calm)0f else alpha;bottom.alpha=if(calm)0f else alpha;folio.alpha=alpha}
    fun openingState(on:Boolean){ opening=on; lastCredit=System.currentTimeMillis(); activeAt=lastCredit;if(!on && openingReflowPending){openingReflowPending=false;handler.post(reflow)} }
    private var completed=false
    /** Books that were already finished when opened never ask for a rating again. */
    private val startedDone=Library.status(book)=="done"
    private var promptArmed=false
    private var ratingShown=false
    private var calm=false
    private var lastPosition=0
    private var creditedChars=0L
    private var sessionRecorded=false
    private var editorOpen=false
    internal var pageEditor:ChapterEditor?=null;private set
    internal val backgroundColor get()=readerBackground
    data class PageDraft(val chapter:Int,val start:Int,val end:Int,val text:CharSequence,val rich:android.text.Spanned,val images:Map<String,ByteArray>,val calm:Boolean)
    private var lastTouchY=0f
    private var lastTouchX=0f
    private var chapterRich:CharSequence=""
    private var chapterAnchors:Map<String,Int> = emptyMap()
    private var pageStarts=listOf(0)
    private var pageIndex=0
    private val heartbeat=object:Runnable { override fun run() { if(!paused) { save(); handler.postDelayed(this,5000) } } }
    private val saveTask=Runnable { save() }
    private val engine=ReaderLayoutEngine()
    private var layoutJob:java.util.concurrent.Future<*>?=null
    private var layoutGeneration=0
    private var preparing=false
    private var disposed=false
    private var requestKey=""
    private var committedKey=""
    private var loadedChapter=-1
    private var pendingOffset:Int?=null
    private var pendingFraction:Double?=null
    private var pendingLastPage=false
    private var pendingAnchor:String?=null
    var lastLayoutWorker="";private set
    val layoutBuilds get()=engine.builds
    val isPreparing get()=preparing
    private val reflow=Runnable { if(opening){openingReflowPending=true}else if(!editorOpen && !disposed)paintChapter() }

    private var readerBackground=a.bg
    private var curl:PageCurlView?=null
    private var turnClock:android.animation.ValueAnimator?=null
    private var pageSlidePending=false
    private var pagePreDraw:ViewTreeObserver.OnPreDrawListener?=null
    private var touchDragging=false;private var touchHeld=false;private var touchDirection=0
    private var velocity:VelocityTracker?=null
    private var dragProgress=0f;private var dragY=0f;private var gestureTurn=false
    private var released:Boolean?=null;private var releaseVelocity=0f
    private var targetPage:TurnPage?=null;private var curlToken=0;private var targetCommitted=false
    private var selectionTurnDone:((Boolean)->Unit)?=null
    private data class TurnPage(val chapter:Int,val index:Int,val rich:CharSequence,val starts:List<Int>,val anchors:Map<String,Int>,val key:String,val worker:String)
    private val previewEngine=ReaderLayoutEngine()
    private val previewPages=mutableMapOf<Int,Pair<String,ReaderLayoutEngine.PageData>>()
    private val previewJobs=mutableMapOf<Int,java.util.concurrent.Future<*>>()
    private var previewGeneration=0
    private var cachedNext:Pair<String,Bitmap>?=null
    private var snapshotWarm:Runnable?=null

    init {
        chapter=chapter.coerceIn(0,chapters.length()-1)
        val bar=LinearLayout(a).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(a.d(8),a.d(4),a.d(8),a.d(4)) }
        fun action(label:String,kind:String,fn:()->Unit) { bar.addView(IconView(a,kind,a.fg).apply { chromeIcons.add(this);a.press(this);contentDescription=label; setOnClickListener { fn() } },LinearLayout.LayoutParams(a.d(48),a.d(48))) }
        topBar=bar
        action("Закрыть книгу","chevdown") { a.closeReader() }
        val metadata=a.column();metadata.addView(a.text(book.str("title"),15f,bold=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});metadata.addView(a.text(MainActivity.authors(book),13f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});bar.addView(metadata,LinearLayout.LayoutParams(0,-2,1f))
         action("Меню книги","more") { readerMenu() }; view.addView(bar,FrameLayout.LayoutParams(-1,a.d(52),Gravity.TOP))
        body.setTextColor(a.fg); body.setTextSize(a.prefs.optDouble("size",a.prefs.optDouble("fontSize",19.0)).toFloat()); body.typeface=PolkaFonts.reader(a,a.prefs.str("font","literata")); body.setLineSpacing(0f,1f); body.includeFontPadding=false; body.setPadding(a.d(24),a.d(22),a.d(24),a.d(42)); body.breakStrategy=android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY; body.hyphenationFrequency=android.text.Layout.HYPHENATION_FREQUENCY_NORMAL; body.setTextIsSelectable(true); body.defaultFocusHighlightEnabled=false; body.background=null; body.foreground=null; body.linksClickable=true; body.movementMethod=android.text.method.LinkMovementMethod.getInstance()
        body.customSelectionActionModeCallback=object:ActionMode.Callback {
            override fun onCreateActionMode(mode:ActionMode,menu:Menu):Boolean { menu.add(0,700,0,"Цитата").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS); menu.add(0,701,1,"Заметка"); return true }
            override fun onPrepareActionMode(mode:ActionMode,menu:Menu)=false
            override fun onActionItemClicked(mode:ActionMode,item:MenuItem):Boolean { if(item.itemId !in 700..701) return false; val start=minOf(body.selectionStart,body.selectionEnd); val end=maxOf(body.selectionStart,body.selectionEnd); if(start>=0 && end>start) { val text=body.text.subSequence(start,end).toString(); if(item.itemId==700) addQuote(start+pageBase(),end+pageBase(),text) else addNote(text) }; mode.finish(); return true }
            override fun onDestroyActionMode(mode:ActionMode) {}
        }
        scroll.defaultFocusHighlightEnabled=false;scroll.isVerticalScrollBarEnabled=false;scroll.overScrollMode=View.OVER_SCROLL_NEVER;scroll.isFillViewport=true;scroll.addView(body);view.addView(scroll,0,FrameLayout.LayoutParams(-1,-1))
        scroll.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob -> if(r-l!=or-ol || b-t!=ob-ot) { handler.removeCallbacks(reflow); handler.post(reflow) } }
        scroll.accessibilityDelegate=object:View.AccessibilityDelegate(){
            override fun onInitializeAccessibilityNodeInfo(v:View,info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(v,info);if(paged()){info.isScrollable=true;info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,"Следующая страница"));info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,"Предыдущая страница"))}}
            override fun performAccessibilityAction(v:View,action:Int,args:android.os.Bundle?):Boolean {if(paged()&&action in listOf(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)){turn(if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)1 else -1);return true};return super.performAccessibilityAction(v,action,args)}
        }
        scroll.setOnScrollChangeListener { _,_,_,_,_ -> if(!restoring && !editorOpen) { activeAt=System.currentTimeMillis(); handler.removeCallbacks(saveTask); handler.postDelayed(saveTask,600) }; updateFooter() }
        val positionRow=a.line()
        val chapterButton=a.line().apply{contentDescription="Оглавление";minimumHeight=a.d(44);setOnClickListener{navigation()}}
        chapterButton.addView(IconView(a,"toc",a.fg).apply{chromeIcons.add(this)},LinearLayout.LayoutParams(a.d(20),a.d(20)))
        footer.setTextColor(a.fg);footer.gravity=Gravity.CENTER_VERTICAL;footer.textSize=15f;footer.maxLines=1;footer.ellipsize=android.text.TextUtils.TruncateAt.END;footer.setPadding(a.d(12),0,a.d(6),0);footer.setOnClickListener{navigation()}
        chapterButton.addView(footer,LinearLayout.LayoutParams(0,a.d(44),1f));positionRow.addView(chapterButton,LinearLayout.LayoutParams(0,a.d(44),1f))
        remaining.setTextColor(a.muted);remaining.textSize=14f;remaining.gravity=Gravity.CENTER_VERTICAL;positionRow.addView(remaining,LinearLayout.LayoutParams(-2,a.d(44)).apply{leftMargin=a.d(12)});bottom.addView(positionRow)
        footer.setOnLongClickListener { settings(); true }
        slider.max=1000; slider.progressTintList=android.content.res.ColorStateList.valueOf(a.fg); slider.thumbTintList=android.content.res.ColorStateList.valueOf(a.fg)
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,v:Int,user:Boolean) { if(user) percent.text="${v/10}% от всей книги" }; override fun onStartTrackingTouch(s:SeekBar?) { scrubbing=true }; override fun onStopTrackingTouch(s:SeekBar?) { scrubbing=false; jumpProgress((s?.progress ?: 0)/1000.0) } }); bottom.addView(slider,LinearLayout.LayoutParams(-1,a.d(28)))
        percent.setTextColor(a.muted); percent.textSize=13f; percent.gravity=Gravity.CENTER; bottom.addView(percent)
        val tools=a.line().apply { gravity=Gravity.CENTER;setPadding(0,a.d(4),0,0) }
        listOf("contents" to "Оглавление и заметки","bm" to "Добавить закладку","aa" to "Настройки текста","pen" to "Править текущую страницу").forEachIndexed { i,p -> tools.addView(IconView(a,p.first,a.fg).apply { chromeIcons.add(this);contentDescription=p.second; setOnClickListener { when(i) { 0->navigation(); 1->addBookmark(); 2->settings(); 3->editChapter() } }; a.press(this) },LinearLayout.LayoutParams(0,a.d(48),1f)) }; bottom.addView(tools);view.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));folio.setTextColor(a.muted);folio.textSize=12f;folio.gravity=Gravity.CENTER;folio.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;view.addView(folio,1,FrameLayout.LayoutParams(-1,a.d(16),Gravity.BOTTOM).apply{bottomMargin=a.d(14)});calm=paged()&&a.prefs.optBoolean("seenReader",false);setCalm(calm,false)

        val gestures=object:GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e:MotionEvent)=true
            override fun onLongPress(e:MotionEvent){if(paged()&&!isTurning()&&!preparing&&!editorOpen){quoteSelection.begin(selectionOffset(e.x,e.y));touchHeld=true}}
            override fun onSingleTapConfirmed(e:MotionEvent):Boolean {
                if(touchHeld||quoteSelection.active)return true
                if(openPageLink(e.x,e.y))return true
                if(e.x<body.width*.25f)turn(-1)else if(e.x>body.width*.75f)turn(1)else toggleChrome();return true
            }
        }
        val detector=GestureDetector(a,gestures)
        fun pageTouch(e:MotionEvent):Boolean {
            if(!paged()||editorOpen)return false
            activeAt=System.currentTimeMillis()
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{if(isTurning())return true;lastTouchX=e.x;lastTouchY=e.y;touchHeld=false;touchDragging=false;touchDirection=0;velocity?.recycle();velocity=VelocityTracker.obtain();velocity?.addMovement(e);detector.onTouchEvent(e);return true}
                MotionEvent.ACTION_MOVE->{velocity?.addMovement(e);if(touchHeld){quoteSelection.continueHold(e);return true};if(quoteSelection.active)return true
                    val dx=e.x-lastTouchX;val dy=e.y-lastTouchY
                    if(!touchDragging&&abs(dx)>a.d(10)&&abs(dx)>abs(dy)*1.1f){touchDragging=true;touchDirection=if(dx<0)1 else -1;body.cancelLongPress();val cancel=MotionEvent.obtain(e);cancel.action=MotionEvent.ACTION_CANCEL;detector.onTouchEvent(cancel);cancel.recycle();scroll.parent.requestDisallowInterceptTouchEvent(true);beginCurl(touchDirection,true,lastTouchY)}
                    if(touchDragging){dragProgress=(-dx*touchDirection/scroll.width.coerceAtLeast(1)).coerceIn(0f,1f);dragY=e.y;curl?.pose(dragProgress,dragY+scroll.top,lastTouchY+scroll.top)}else detector.onTouchEvent(e);return true}
                MotionEvent.ACTION_POINTER_DOWN->{if(touchDragging)releaseCurl(false);touchDragging=false;touchHeld=true;scroll.parent.requestDisallowInterceptTouchEvent(false);val cancel=MotionEvent.obtain(e);cancel.action=MotionEvent.ACTION_CANCEL;detector.onTouchEvent(cancel);cancel.recycle();return true}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.addMovement(e);velocity?.computeCurrentVelocity(1000);val vx=velocity?.xVelocity?:0f
                    if(touchDragging){dragProgress=(-(e.x-lastTouchX)*touchDirection/scroll.width.coerceAtLeast(1)).coerceIn(0f,1f);dragY=e.y;curl?.pose(dragProgress,dragY+scroll.top,lastTouchY+scroll.top);val commit=e.actionMasked==MotionEvent.ACTION_UP && (if(abs(vx)>a.d(650)) -vx*touchDirection>0 else dragProgress>.32f);releaseCurl(commit,vx);scroll.parent.requestDisallowInterceptTouchEvent(false)}else if(touchHeld)quoteSelection.continueHold(e)else detector.onTouchEvent(e)
                    velocity?.recycle();velocity=null;touchDragging=false;return true}
            };return true
        }
        body.setOnTouchListener { _,e -> pageTouch(e) }
        scroll.setOnTouchListener { _,e -> pageTouch(e) }
        view.addView(quoteSelection,1,FrameLayout.LayoutParams(-1,-1))
        scroll.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_->positionSelection() }
        applyTypography()

    }
    fun safeArea(left:Int,top:Int,right:Int,bottom:Int){if(view.paddingLeft!=left||view.paddingTop!=top||view.paddingRight!=right||view.paddingBottom!=bottom){stopPageMotion();view.setPadding(left,top,right,bottom)
        topBar.layoutParams=(topBar.layoutParams as FrameLayout.LayoutParams).apply{height=a.d(52)+top;topMargin=-top;leftMargin=-left;rightMargin=-right}
        topBar.setPadding(a.d(8)+left,a.d(4)+top,a.d(8)+right,a.d(4))
        this.bottom.layoutParams=(this.bottom.layoutParams as FrameLayout.LayoutParams).apply{bottomMargin=-bottom;leftMargin=-left;rightMargin=-right}
        this.bottom.setPadding(a.d(18)+left,a.d(8),a.d(18)+right,a.d(4)+bottom)
        positionSelection()}}
    fun start() {
        ReaderWindow.configure(a.window,true,readerBackground);a.root.requestApplyInsets()
        book.put("openedAt",openedAt);a.prefs.put("seenReader",true);val preferenceSnapshot=a.prefs.copyJson();a.work({a.library.savePrefs(preferenceSnapshot)})
        val progress=book.optJSONObject("progress")
        pendingOffset=if(progress?.has("nativeOffset")==true)progress.optInt("nativeOffset")else null
        pendingFraction=if(progress?.has("nativeFraction")==true)progress.optDouble("nativeFraction")else{
            val before=(0 until chapter).sumOf{chapters.getJSONObject(it).optInt("len")};val total=chapters.objects().sumOf{it.optInt("len")}.coerceAtLeast(1)
            (((progress?.optDouble("pct",0.0)?:0.0)*total-before)/chapters.getJSONObject(chapter).optInt("len",1).coerceAtLeast(1)).coerceIn(0.0,1.0)
        }
        view.post{if(!disposed)paintChapter()};handler.postDelayed(heartbeat,5000);persist()
    }
    /** Parse + image/table preparation + whole-chapter line breaking run off the main looper. */
    fun paintChapter() {
        if(disposed || editorOpen)return
        val top=if(paged())22 else 84;val bottomInset=if(paged())42 else 110;if(body.paddingTop!=a.d(top)||body.paddingBottom!=a.d(bottomInset))body.setPadding(a.d(24),a.d(top),a.d(24),a.d(bottomInset))
        val width=(scroll.width-body.paddingLeft-body.paddingRight).takeIf{it>0}?: (a.resources.displayMetrics.widthPixels-a.d(48))
        val height=(scroll.height-body.paddingTop-body.paddingBottom-a.d(6)).takeIf{it>a.d(100)}?: (a.resources.displayMetrics.heightPixels-a.d(230))
        val html=chapters.getJSONObject(chapter).str("html");val size=body.textSize;val ink=body.currentTextColor;val leading=lineHeight();val paint=android.text.TextPaint(body.paint);val justify=body.justificationMode
        val quoteSnapshot=book.optJSONArray("quotes")?.toString()?:"[]";val key="$chapter:$width:$height:$size:$ink:$leading:${a.prefs.str("font")}:$justify:${paged()}:${html.hashCode()}:${quoteSnapshot.hashCode()}"
        if(key==requestKey && preparing)return
        if(key==committedKey && loadedChapter==chapter){val offset=pendingAnchor?.let{chapterAnchors[it]} ?: pendingOffset;pendingAnchor=null;pendingOffset=null;pendingFraction=null;if(offset!=null)jumpOffset(offset);return}
        if(!preparing && loadedChapter==chapter && pendingOffset==null && pendingFraction==null)pendingOffset=if(paged())pageBase()else body.layout?.let{it.getLineStart(it.getLineForVertical(scroll.scrollY))}?:0
        layoutJob?.cancel(true);val generation=++layoutGeneration;requestKey=key;preparing=true;lastCredit=System.currentTimeMillis();footer.text="Подготовка главы…"
        val targetChapter=chapter;val quotes=JSONArray(quoteSnapshot).objects();val maxImage=(height-size*3).toInt().coerceAtLeast(a.d(80))
        layoutJob=a.compute({engine.prepare(key,{
            val rendered=ReadingText(a,size,width,maxImage,images,ink,leading).render(html);val rich=rendered.text
            quotes.filter{it.optInt("ch")==targetChapter}.forEach{q->val raw=q.str("rawText",q.str("text"));val range=TextAnchors.locate(rich.toString(),raw,q.optInt("start"));if(range!=null)rich.setSpan(android.text.style.BackgroundColorSpan(0x44f0a742),range.first,range.last+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
            rendered
        },paint,width,height,justify)},failed={if(generation==layoutGeneration){preparing=false;view.alpha=1f}},done={result->
            if(!disposed && generation==layoutGeneration && chapter==targetChapter){
                chapterRich=android.text.SpannableStringBuilder(result.text);chapterAnchors=result.anchors;pageStarts=result.starts;lastLayoutWorker=result.worker;committedKey=key;loadedChapter=chapter
                val rich=chapterRich as android.text.SpannableStringBuilder
                rich.getSpans(0,rich.length,android.text.style.URLSpan::class.java).forEach{span->val begin=rich.getSpanStart(span);val end=rich.getSpanEnd(span);val href=span.url;rich.removeSpan(span);rich.setSpan(object:android.text.style.ClickableSpan(){override fun onClick(v:View){openLink(href)};override fun updateDrawState(ds:android.text.TextPaint){ds.color=a.accent;ds.isUnderlineText=false}},begin,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
                val offset=if(pendingLastPage)rich.length else pendingAnchor?.let{chapterAnchors[it]} ?: pendingOffset ?: ((pendingFraction?:0.0)*rich.length).toInt();pendingOffset=null;pendingAnchor=null;pendingFraction=null;pendingLastPage=false;preparing=false
                restoring=true;body.animate().cancel();body.alpha=1f;body.translationX=0f
                if(paged()){pageIndex=pageStarts.indexOfLast{it<=offset}.coerceAtLeast(0);paintPage();revealPage()}else{body.text=rich;body.post{jumpOffset(offset)}}
                body.post{restoring=false;lastPosition=position();lastCredit=System.currentTimeMillis();updateFooter()}
            }
        })
    }
    fun pageBase()=if(paged()) pageStarts.getOrElse(pageIndex) { 0 } else 0
    fun fraction():Double = if(paged()) pageBase().toDouble()/maxOf(1,chapterRich.length) else if(body.height<=scroll.height) 0.0 else (scroll.scrollY.toDouble()/(body.height-scroll.height)).coerceIn(0.0,1.0)
    /**
     * The last page of the last chapter IS the end of the book. Paged progress is measured from the START of the
     * current page, so on the final page it used to stop at ~99% and the book could never become "read".
     */
    internal fun atEnd():Boolean {
        if(disposed||preparing||restoring||opening||chapters.length()==0||chapter!=chapters.length()-1||chapterRich.isEmpty()||scroll.width<=0||scroll.height<=0)return false
        return if(paged())pageStarts.isNotEmpty()&&pageIndex>=pageStarts.lastIndex else body.height<=scroll.height||scroll.scrollY>=body.height-scroll.height-a.d(4)
    }
    private fun checkFinished() {
        if(!atEnd())return
        if(!completed){completed=true;if(!startedDone)book.put("completedAt",System.currentTimeMillis());handler.post{if(!disposed)save()}}
        if(!startedDone&&!ratingShown&&!promptArmed&&book.optInt("rating")==0){promptArmed=true;handler.postDelayed(promptTask,1300)}
    }
    private val promptTask=Runnable {
        promptArmed=false
        if(disposed||ratingShown||editorOpen||isTurning()||!atEnd()||a.ratingPrompt?.isShowing==true)return@Runnable
        ratingShown=true
        a.ratingPrompt=RatingPrompt(a,book){n->book.put("rating",n);a.saveBook(book){}}.also{it.show()}
    }
    fun progress():Double { if(atEnd())return 1.0; val total=chapters.objects().sumOf { it.optInt("len") }.coerceAtLeast(1); val before=(0 until chapter).sumOf { chapters.getJSONObject(it).optInt("len") }; return ((before+chapters.getJSONObject(chapter).optInt("len")*fraction())/total).coerceIn(0.0,1.0) }
    fun updateFooter() { folio.text=if(paged())"${pageIndex+1} из ${pageStarts.size}" else "";if(!scrubbing) slider.progress=(progress()*1000).toInt(); percent.text="${(progress()*100).toInt()}% от всей книги"; footer.text=chapters.getJSONObject(chapter).str("title");remaining.text=if(paged()){val left=pageStarts.size-pageIndex-1;if(left>0)"ещё $left стр" else "конец главы"}else "${(fraction()*100).toInt()}% главы";checkFinished() }
    fun changeChapter(next:Int) { if(next !in 0 until chapters.length())return;quoteSelection.clear();save();stopPageMotion();chapter=next;pendingOffset=0;pendingFraction=null;paintChapter() }
    fun persist() { val snap=book.copyJson(); a.saveBook(snap) {} }
    fun pause() { save();paused=true;handler.removeCallbacks(heartbeat) }
    fun dispose(){if(disposed)return;a.ratingPrompt?.let{(it.parent as? ViewGroup)?.removeView(it)};a.ratingPrompt=null;quoteSelection.clear();cachedNext=null;previewGeneration++;previewJobs.values.forEach{it.cancel(true)};previewJobs.clear();pageEditor?.detach();pause();disposed=true;layoutGeneration++;layoutJob?.cancel(true);handler.removeCallbacksAndMessages(null);body.animate().cancel();stopPageMotion()}
    fun resume() { ReaderWindow.configure(a.window,true,readerBackground);a.root.requestApplyInsets();paused=false; activeAt=System.currentTimeMillis(); lastCredit=activeAt; handler.removeCallbacks(heartbeat); handler.postDelayed(heartbeat,5000) }
    fun save() {
        handler.removeCallbacks(saveTask)
        if(restoring || editorOpen || opening || preparing || disposed) return
        val p=book.optJSONObject("progress")?.copyJson() ?: JSONObject(); p.put("ch",chapter).put("pct",if(completed) 1.0 else progress()).put("nativeFraction",fraction()).put("nativeOffset",if(paged()) pageBase() else body.layout?.let { it.getLineStart(it.getLineForVertical(scroll.scrollY)) } ?: 0).put("block",0).put("off",0); book.put("progress",p)
        val now=System.currentTimeMillis(); val elapsed=if(!paused && now-activeAt<120000) (now-lastCredit).coerceIn(0,120000) else 0; lastCredit=now
        val pos=position(); val delta=pos-lastPosition; val chars=if(delta in 1 until 12000) delta.toLong() else 0L; lastPosition=pos; val credit=if(elapsed>0) chars else 0L
        val newSession=!sessionRecorded; sessionRecorded=true
        val snap=book.copyJson()
        a.work({
            a.library.db.runInTransaction {
                a.library.save(snap)
                val raw=a.library.dao.meta("stats") ?: return@runInTransaction
                val meta=JSONObject(raw.json); val data=meta.getJSONObject("data"); val id=snap.getString("id")
                creditReading(data,id,now,elapsed,credit)
                val records=data.optJSONObject("books") ?: JSONObject().also { data.put("books",it) }; val b=records.optJSONObject(id) ?: JSONObject().put("sessions",0).put("first",openedAt).put("chars",0).also { records.put(id,it) }; b.put("ms",b.optLong("ms")+elapsed).put("chars",b.optLong("chars")+credit).put("sessions",b.optInt("sessions")+if(newSession) 1 else 0).put("total",chapters.objects().sumOf { it.optInt("len") }).put("last",now).put("title",snap.str("title")); a.library.dao.putMeta(MetaRow("stats",meta.toString()))
            }
        }) { a.checkpointSoon() }
    }
    fun addQuote(start:Int,end:Int,text:String) { val quotes=book.optJSONArray("quotes") ?: JSONArray(); if(quotes.objects().none { it.optInt("ch")==chapter && it.str("text")==text }) quotes.put(JSONObject().put("id",uid()).put("ch",chapter).put("start",start).put("end",end).put("block",0).put("text",text).put("rawText",text).put("at",System.currentTimeMillis())); book.put("quotes",quotes); val offset=pageBase(); paintChapter(); body.post { jumpOffset(offset) }; persist() }
    fun addNote(selected:String="") { val input=EditText(a).apply { setTextColor(a.fg); setHintTextColor(a.muted); setPadding(a.d(24),a.d(16),a.d(24),a.d(16)); minLines=3; hint="Заметка" }; a.dialog().setTitle("Заметка").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить") { _,_ -> if(input.text.isNotBlank()) { val notes=book.optJSONArray("notes") ?: JSONArray(); notes.put(JSONObject().put("id",uid()).put("ch",chapter).put("text",input.text.toString()).put("quote",selected).put("at",System.currentTimeMillis())); book.put("notes",notes); persist() } }.show() }
    fun addBookmark() { val list=book.optJSONArray("bookmarks")?.objects().orEmpty().toMutableList(); val offset=if(paged()) pageBase() else body.layout?.let { it.getLineStart(it.getLineForVertical(scroll.scrollY)) } ?: 0; val match=list.indexOfFirst { it.optInt("ch")==chapter && abs(it.optInt("nativeOffset",0)-offset)<100 }; if(match>=0) list.removeAt(match) else list.add(JSONObject().put("id",uid()).put("ch",chapter).put("block",0).put("nativeOffset",offset).put("text",chapters.getJSONObject(chapter).str("title")).put("nativeFraction",fraction()).put("at",System.currentTimeMillis())); book.put("bookmarks",JSONArray(list)); persist(); if(a.prefs.optBoolean("haptics",true)) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
    fun jumpProgress(pct:Double) { val total=chapters.objects().sumOf { it.optInt("len") }.coerceAtLeast(1); var at=(pct*total).toInt(); var next=0; while(next<chapters.length()-1 && at>=chapters.getJSONObject(next).optInt("len")) { at-=chapters.getJSONObject(next).optInt("len"); next++ }; changeChapter(next); val offset=at; body.post { jumpOffset(offset); save() } }
    fun navigation(tab:Int=0){ReaderNavigation(a,this,tab).show()}
    fun contents()=content.optJSONArray("toc")?.objects().orEmpty().ifEmpty{chapters.objects().mapIndexed{index,ch->ch.copyJson().put("ch",index)}}
    fun chapterName(index:Int)=chapters.optJSONObject(index)?.str("title")?:"Книга"
    fun goToLocation(next:Int,offset:Int=0,anchor:String=""){
        if(next !in 0 until chapters.length())return
        quoteSelection.clear();save();stopPageMotion();chapter=next;pendingOffset=offset;pendingFraction=null;pendingAnchor=anchor.takeIf{it.isNotBlank()};paintChapter()
    }
    fun paged()=a.prefs.str("mode","paged")=="paged"
    fun pageHeight()=maxOf(1,scroll.height-a.d(18))
    fun position():Int { val before=(0 until chapter).sumOf { chapters.getJSONObject(it).optInt("len") }; val offset=if(paged()) pageBase() else body.layout?.let { it.getLineStart(it.getLineForVertical(scroll.scrollY)) } ?: 0; return before+offset }
    fun paginate(offset:Int=pageBase()) {pendingOffset=offset;paintChapter()}
    private fun pageText(rich:CharSequence,starts:List<Int>,index:Int):CharSequence {
        val start=starts.getOrElse(index){0};val end=starts.getOrElse(index+1){rich.length};val page=android.text.SpannableStringBuilder(rich,start,end)
        val full=rich as? android.text.Spanned
        if(full!=null) {
            full.getSpans(start,end,android.text.style.LeadingMarginSpan::class.java).forEach { margin -> if(full.getSpanStart(margin)<start) { page.removeSpan(margin); page.setSpan(android.text.style.LeadingMarginSpan.Standard(margin.getLeadingMargin(false)),0,(full.getSpanEnd(margin)-start).coerceIn(0,page.length),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
            full.getSpans(start,end,ParagraphSpace::class.java).forEach { spacing -> val from=full.getSpanStart(spacing); val spanEnd=full.getSpanEnd(spacing); val trimBefore=(from < start); val trimAfter=(spanEnd > end); if(trimBefore || trimAfter) { page.removeSpan(spacing); page.setSpan(spacing.continuation(trimBefore,trimAfter),(from-start).coerceIn(0,page.length),(spanEnd-start).coerceIn(0,page.length),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
        }
        return page
    }
    fun paintPage() {body.text=pageText(chapterRich,pageStarts,pageIndex);scroll.scrollTo(0,0);updateFooter();positionSelection();quoteSelection.invalidate();warmPages();warmSnapshot()}
    internal fun selectionFullText()=chapterRich
    internal fun pageLimit()=pageStarts.getOrElse(pageIndex+1){chapterRich.length}
    internal fun selectionOffset(x:Float,y:Float):Int {val layout=body.layout?:return pageBase();val line=layout.getLineForVertical((y-body.paddingTop).toInt());return pageBase()+layout.getOffsetForHorizontal(line,x-body.paddingLeft).coerceIn(0,body.length())}
    private fun positionSelection(){if(quoteSelection.parent!=view)return;quoteSelection.layoutParams=(quoteSelection.layoutParams as FrameLayout.LayoutParams).apply{width=scroll.width; height=scroll.height;leftMargin=scroll.left-view.paddingLeft;topMargin=scroll.top-view.paddingTop}}
    private fun openPageLink(x:Float,y:Float):Boolean {
        val layout=body.layout?:return false;val sp=body.text as? android.text.Spanned?:return false
        val localY=y-body.totalPaddingTop+body.scrollY;val localX=x-body.totalPaddingLeft+body.scrollX;val line=layout.getLineForVertical(localY.toInt());val offset=layout.getOffsetForHorizontal(line,localX).coerceIn(0,sp.length)
        sp.getSpans(offset,minOf(sp.length,offset+1),android.text.style.ClickableSpan::class.java).firstOrNull()?.let{it.onClick(body);return true}
        val first=layout.getLineStart(line);val table=sp.getSpans(first,minOf(sp.length,first+1),TableRowSpan::class.java).firstOrNull()
        if(table!=null){val bottom=layout.getLineBaseline(line)+body.paint.fontMetricsInt.descent.coerceAtLeast(0);table.linkAt(localX-layout.getLineLeft(line),localY-(bottom-table.sliceHeight))?.let{openLink(it);return true}};return false
    }
    private fun jumpAnchor(id:String){if(preparing){pendingAnchor=id;pendingOffset=null}else jumpOffset(chapterAnchors[id]?:0)}
    fun jumpOffset(offset:Int) { if(preparing || loadedChapter!=chapter){pendingOffset=offset;pendingFraction=null;return};if(paged()) { pageIndex=pageStarts.indexOfLast { it<=offset }.coerceAtLeast(0); paintPage() } else { val layout=body.layout ?: return; val at=offset.coerceIn(0,body.text.length); scroll.scrollTo(0,layout.getLineTop(layout.getLineForOffset(at))) }; lastPosition=position() }
    fun lineHeight()=when(a.prefs.str("lh","normal")) { "tight"->1.42f; "loose"->1.82f; else->1.6f }
    internal val pageMotionPending get()=pageSlidePending
    internal val pageMotionRunning get()=turnClock!=null
    internal val outgoingPage get()=curl
    internal fun samplePageMotion(ms:Long){turnClock?.let{it.pause();if(ms>=it.duration)it.end()else it.currentPlayTime=ms}}
    private fun isTurning()=pageSlidePending||turnClock!=null||curl!=null
    private var folioFrom="";private var folioAlpha=1f;private var folioTurning=false
    /** The page counter follows the hand: the old number fades out, the new one fades in as the sheet passes half way. */
    private fun turnFolio(p:Float){val t=targetPage?:return;if(!paged())return;folioTurning=true
        val text=if(p>=.5f)"${t.index+1} из ${t.starts.size}" else folioFrom
        if(folio.text.toString()!=text)folio.text=text
        folio.alpha=folioAlpha*(.35f+.65f*abs(2f*p-1f))}
    private fun restoreFolio(){if(folioTurning){folioTurning=false;folio.alpha=folioAlpha};updateFooter()}
    fun stopPageMotion(){
        curlToken++;pagePreDraw?.let{if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(it)};pagePreDraw=null
        turnClock?.removeAllListeners();turnClock?.cancel();turnClock=null;scroll.translationX=0f
        curl?.let{view.removeView(it)};if(curl!=null)restoreFolio();curl=null;pageSlidePending=false;targetPage=null;gestureTurn=false;released=null;dragProgress=0f;targetCommitted=false
        selectionTurnDone?.also{selectionTurnDone=null;it(false)}
    }
    private fun chapterKey(index:Int):String {
        val width=(scroll.width-body.paddingLeft-body.paddingRight).coerceAtLeast(1);val height=(scroll.height-body.paddingTop-body.paddingBottom-a.d(6)).coerceAtLeast(1)
        return "$index:${view.width}:${view.height}:${scroll.left}:${scroll.top}:$width:$height:${body.textSize}:${body.currentTextColor}:${lineHeight()}:${a.prefs.str("font")}:${body.justificationMode}:${paged()}:${chapters.getJSONObject(index).str("html").hashCode()}:${(book.optJSONArray("quotes")?.toString()?:"[]").hashCode()}"
    }
    private fun preparePreview(index:Int,done:(ReaderLayoutEngine.PageData,String)->Unit={_,_->}){
        if(index !in 0 until chapters.length()||disposed)return
        val key=chapterKey(index);previewPages[index]?.takeIf{it.first==key}?.let{done(it.second,key);return}
        val generation=previewGeneration;val width=(scroll.width-body.paddingLeft-body.paddingRight).coerceAtLeast(1);val height=(scroll.height-body.paddingTop-body.paddingBottom-a.d(6)).coerceAtLeast(1)
        val paint=android.text.TextPaint(body.paint);val size=body.textSize;val ink=body.currentTextColor;val leading=lineHeight();val justify=body.justificationMode;val html=chapters.getJSONObject(index).str("html");val quotes=book.optJSONArray("quotes")?.toString()?:"[]"
        // Warm jobs can share the engine cache; completion is keyed by geometry,
        // theme, chapter content and quote revision, never by page index alone.
        previewJobs[index]=a.compute({previewEngine.prepare(key,{
            ReadingText(a,size,width,(height-size*3).toInt().coerceAtLeast(a.d(80)),images,ink,leading).render(html).also{rendered->JSONArray(quotes).objects().filter{it.optInt("ch")==index}.forEach{q->TextAnchors.locate(rendered.text.toString(),q.str("rawText",q.str("text")),q.optInt("start"))?.let{range->rendered.text.setSpan(android.text.style.BackgroundColorSpan(0x44f0a742),range.first,range.last+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}}
        },paint,width,height,justify)},failed={previewJobs.remove(index);if(isTurning())stopPageMotion()},done={data->previewJobs.remove(index);if(!disposed&&generation==previewGeneration&&chapterKey(index)==key){previewPages[index]=key to data;previewPages.keys.filter{abs(it-chapter)>1}.toList().forEach{previewPages.remove(it)};done(data,key)}})
    }
    private fun warmPages(){if(!paged()||scroll.width<=0)return;if(pageIndex==0&&chapter>0&&!previewJobs.containsKey(chapter-1))preparePreview(chapter-1);if(pageIndex==pageStarts.lastIndex&&chapter<chapters.length()-1&&!previewJobs.containsKey(chapter+1))preparePreview(chapter+1)}
    private fun snapshotKey(target:TurnPage)="${chapterKey(target.chapter)}:${target.index}"
    private fun warmSnapshot(){
        snapshotWarm?.let{handler.removeCallbacks(it)}
        if(!paged()||disposed||preparing||a.prefs.str("motion")=="reduced"||scroll.width<=0)return
        val fromChapter=chapter;val fromPage=pageIndex
        snapshotWarm=Runnable{if(!disposed&&!isTurning()&&chapter==fromChapter&&pageIndex==fromPage)findTarget(1){target->
            if(target!=null&&!disposed&&!isTurning()&&chapter==fromChapter&&pageIndex==fromPage){val key=snapshotKey(target);if(cachedNext?.first!=key){activateLinks(target.rich);cachedNext=key to snapshot(pageText(target.rich,target.starts,target.index))}}
        }}.also{handler.postDelayed(it,72)}
    }
    private fun findTarget(direction:Int,done:(TurnPage?)->Unit){
        val index=pageIndex+direction
        if(index in pageStarts.indices){done(TurnPage(chapter,index,chapterRich,pageStarts,chapterAnchors,committedKey,lastLayoutWorker));return}
        val next=chapter+direction;if(next !in 0 until chapters.length()){done(null);return}
        preparePreview(next){data,key->done(TurnPage(next,if(direction>0)0 else data.starts.lastIndex,data.text,data.starts,data.anchors,key,data.worker))}
    }
    private fun snapshot(text:CharSequence?=null):Bitmap {
        // Paper occupies the whole physical reader, including safe-area bands. Text keeps its
        // existing viewport/padding; only the sheet geometry extends to the screen edges.
        val b=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(b);canvas.drawColor(readerBackground)
        canvas.save();canvas.translate(scroll.left.toFloat(),scroll.top.toFloat());canvas.clipRect(0,0,scroll.width,scroll.height)
        body.isPressed=false;scroll.isPressed=false;body.cancelLongPress()
        if(text==null)scroll.draw(canvas)else{
            val preview=TextView(a);preview.paint.set(body.paint);preview.setTextColor(body.currentTextColor);preview.typeface=body.typeface;preview.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,body.textSize);preview.includeFontPadding=body.includeFontPadding;preview.setLineSpacing(0f,1f);preview.breakStrategy=body.breakStrategy;preview.hyphenationFrequency=body.hyphenationFrequency;preview.justificationMode=body.justificationMode;preview.textDirection=body.textDirection;preview.setPadding(body.paddingLeft,body.paddingTop,body.paddingRight,body.paddingBottom);preview.text=text
            preview.measure(View.MeasureSpec.makeMeasureSpec(scroll.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));preview.layout(0,0,scroll.width,preview.measuredHeight);preview.draw(canvas)
        };canvas.restore();return b
    }
    private fun beginCurl(direction:Int,interactive:Boolean,y:Float):Boolean {
        if(preparing||disposed||editorOpen||isTurning()||!paged()||scroll.width<=0||scroll.height<=0)return false
        val next=pageIndex+direction;if(next<0&&chapter==0||next>pageStarts.lastIndex&&chapter==chapters.length()-1)return false
        if(direction>0&&!calm&&selectionTurnDone==null)setCalm(true,true)
        pageSlidePending=true;gestureTurn=interactive;released=null;targetCommitted=false;dragProgress=0f;dragY=y
        val token=++curlToken;val source=snapshot()
        findTarget(direction){target->if(token!=curlToken||disposed)return@findTarget;if(target==null){stopPageMotion();return@findTarget};targetPage=target;activateLinks(target.rich)
            val upcoming=cachedNext?.takeIf{it.first==snapshotKey(target)}?.second ?: snapshot(pageText(target.rich,target.starts,target.index))
            val overlay=PageCurlView(a,source,upcoming,direction,readerBackground,a.prefs.str("motion")=="reduced");curl=overlay
            view.clipToPadding=false
            view.addView(overlay,1,FrameLayout.LayoutParams(view.width,view.height).apply{leftMargin=-view.paddingLeft;topMargin=-view.paddingTop})
            overlay.measure(View.MeasureSpec.makeMeasureSpec(view.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(view.height,View.MeasureSpec.EXACTLY));overlay.layout(0,0,view.width,view.height);folioFrom=folio.text.toString();folioAlpha=folio.alpha;overlay.onPose={turnFolio(it)};overlay.pose(dragProgress,dragY+scroll.top,y+scroll.top)
            if(!interactive){commitTarget();revealPage()}else{pageSlidePending=false;released?.let{releaseCurl(it,releaseVelocity)}}
        };return true
    }
    private fun activateLinks(rich:CharSequence){val sp=rich as? android.text.Spannable?:return;sp.getSpans(0,sp.length,android.text.style.URLSpan::class.java).forEach{span->val start=sp.getSpanStart(span);val end=sp.getSpanEnd(span);val href=span.url;sp.removeSpan(span);sp.setSpan(object:android.text.style.ClickableSpan(){override fun onClick(v:View){openLink(href)};override fun updateDrawState(ds:android.text.TextPaint){ds.color=a.accent;ds.isUnderlineText=false}},start,end,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}}
    private fun commitTarget(){val target=targetPage?:return;if(targetCommitted)return
        save();targetCommitted=true
        if(target.chapter!=chapter){chapter=target.chapter;chapterRich=target.rich;pageStarts=target.starts;chapterAnchors=target.anchors;committedKey=target.key;requestKey=target.key;loadedChapter=chapter;lastLayoutWorker=target.worker;activateLinks(chapterRich)}
        pageIndex=target.index;paintPage();if(selectionTurnDone==null)quoteSelection.clear()
    }
    private fun releaseCurl(commit:Boolean,vx:Float=0f){
        released=commit;releaseVelocity=vx;if(curl==null)return
        if(commit){commitTarget();revealPage()}else animateCurl(false)
    }
    private fun revealPage(){
        if(curl==null)return
        pageSlidePending=true;pagePreDraw?.let{if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(it)}
        val listener=object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
            if(disposed){stopPageMotion();return true}
            if(body.isLayoutRequested||body.layout==null||body.layout.text.toString()!=body.text.toString()){view.postInvalidateOnAnimation();return true}
            if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(this);pagePreDraw=null;pageSlidePending=false;animateCurl(true);return true
        }};pagePreDraw=listener;view.viewTreeObserver.addOnPreDrawListener(listener);view.postInvalidateOnAnimation()
    }
    private fun animateCurl(commit:Boolean){
        val overlay=curl?:return;turnClock?.removeAllListeners();turnClock?.cancel()
        val start=overlay.progress;val end=if(commit)1f else 0f
        if(a.prefs.str("motion")=="reduced"){overlay.pose(end,dragY);completeCurl(commit);return}
        val remaining=abs(end-start)
        turnClock=android.animation.ValueAnimator.ofFloat(start,end).apply{duration=if(gestureTurn)(180+remaining*360).toLong()else 620L;interpolator=android.view.animation.PathInterpolator(.2f,.7f,.25f,1f)
            addUpdateListener{overlay.pose(it.animatedValue as Float,dragY)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(anim:android.animation.Animator){completeCurl(commit)}});start()}
    }
    private fun completeCurl(commit:Boolean){val done=selectionTurnDone;selectionTurnDone=null;stopPageMotion();if(commit){handler.removeCallbacks(saveTask);handler.post(saveTask)};warmSnapshot();done?.invoke(commit)}
    internal fun turnWithSelection(direction:Int,done:(Boolean)->Unit){
        if(pageIndex+direction !in pageStarts.indices||isTurning()){done(false);return}
        selectionTurnDone=done
        if(!beginCurl(direction,false,scroll.height*.9f)){selectionTurnDone=null;done(false)}
    }
    fun turn(direction:Int) {
        activeAt=System.currentTimeMillis();if(preparing||disposed||editorOpen||isTurning())return
        if(!paged()){scroll.smoothScrollBy(0,direction*pageHeight());return}
        quoteSelection.clear()
        if(a.prefs.str("motion")=="reduced"){
            if(direction>0)setCalm(true,false)
            findTarget(direction){target->if(target!=null){targetPage=target;commitTarget();targetPage=null;targetCommitted=false;save()}};return
        }
        beginCurl(direction,false,scroll.height*.9f)
    }
    fun finishBook() { a.confirm("Книга прочитана?","Отметить книгу прочитанной?") { completed=true; book.put("completedAt",System.currentTimeMillis()); save(); a.closeReader() } }
    internal fun showChrome(show:Boolean)=setCalm(!show,false)
    fun toggleChrome(){if(!editorOpen)setCalm(!calm,true)}
    private fun setCalm(value:Boolean,animate:Boolean){calm=value;val reduced=!animate||a.prefs.str("motion")=="reduced";listOf(topBar to -1,bottom to 1).forEach{(v,sign)->v.animate().cancel();if(reduced){v.alpha=if(value)0f else 1f;v.translationY=if(value)sign*v.height.toFloat() else 0f;v.visibility=if(value)View.INVISIBLE else View.VISIBLE;if(value)v.post{if(calm&&v.visibility==View.INVISIBLE)v.translationY=sign*v.height.toFloat()}}else{v.visibility=View.VISIBLE;v.animate().alpha(if(value)0f else 1f).translationY(if(value)sign*v.height.toFloat() else 0f).setDuration(360).setInterpolator(MotionSpec.settle).withEndAction{if(calm)v.visibility=View.INVISIBLE}.start()}};folio.visibility=if(paged()&&value)View.VISIBLE else View.INVISIBLE}
    fun applyTypography() {
        quoteSelection.clear();stopPageMotion();cachedNext=null;snapshotWarm?.let{handler.removeCallbacks(it)};previewGeneration++;previewPages.clear();previewJobs.values.forEach{it.cancel(true)};previewJobs.clear()
        val savedOffset=if(paged())pageBase()else body.layout?.let{it.getLineStart(it.getLineForVertical(scroll.scrollY))}?:0
        val pair=when(a.prefs.str("rt")) { "black"->0xff000000.toInt() to 0xffc9c7c1.toInt(); "sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt(); "paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt(); "night"->0xff18181a.toInt() to 0xffdedcd6.toInt(); else->a.bg to a.fg }
        readerBackground=pair.first;a.root.setBackgroundColor(pair.first);ReaderWindow.configure(a.window,true,pair.first);view.setBackgroundColor(pair.first);listOf(topBar to 22f,bottom to 26f).forEach{(bar,radius)->a.controlSurface(bar,radius,sheet=true).apply{themeLight=android.graphics.Color.red(pair.first)>128;flushTop=bar===topBar;flushBottom=bar===bottom;refresh()}}; slider.foreground=pair.second;slider.invalidate();chromeIcons.forEach{it.ink=pair.second;it.invalidate()};footer.setTextColor(pair.second);body.setTextColor(pair.second); body.textSize=a.prefs.optDouble("size",19.0).toFloat(); body.typeface=PolkaFonts.reader(a,a.prefs.str("font","literata")); body.setLineSpacing(0f,1f); body.includeFontPadding=false; body.justificationMode=if(a.prefs.optBoolean("justify",true)) android.text.Layout.JUSTIFICATION_MODE_INTER_WORD else android.text.Layout.JUSTIFICATION_MODE_NONE; if(chapterRich.isNotEmpty()){pendingOffset=savedOffset;handler.removeCallbacks(reflow);handler.postDelayed(reflow,120)} 
    }
    fun settings() {
        val box=a.column(); var panel:AlertDialog?=null
        a.label("Размер текста",box)
        box.addView(SeekBar(a).apply { min=14; max=30; progress=a.prefs.optInt("size",19); progressTintList=android.content.res.ColorStateList.valueOf(a.accent); setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,v:Int,u:Boolean) { if(u) { a.prefs.put("size",v); applyTypography() } }; override fun onStartTrackingTouch(s:SeekBar?) {}; override fun onStopTrackingTouch(s:SeekBar?) { a.savePreferences() } }) })
        a.label("Шрифт",box); box.addView(a.segment(listOf("Literata","PT Serif","Без засечек"),listOf("literata","ptserif","sans").indexOf(a.prefs.str("font","literata"))) { i -> a.prefs.put("font",listOf("literata","ptserif","sans")[i]); a.savePreferences(); applyTypography() })
        a.label("Межстрочный интервал",box); box.addView(a.segment(listOf("Плотно","Обычно","Свободно"),listOf("tight","normal","loose").indexOf(a.prefs.str("lh","normal"))) { i -> a.prefs.put("lh",listOf("tight","normal","loose")[i]); a.savePreferences(); applyTypography() })
        a.label("Режим чтения",box); box.addView(a.segment(listOf("Страницы","Прокрутка"),if(paged()) 0 else 1) { i -> val offset=if(paged())pageBase()else body.layout?.let{it.getLineStart(it.getLineForVertical(scroll.scrollY))}?:0; a.prefs.put("mode",if(i==0) "paged" else "scroll"); a.savePreferences(); pendingOffset=offset; pendingFraction=null; setCalm(false,false); paintChapter() })
        a.label("Тема страницы",box); box.addView(a.segment(listOf("Ночь","Чёрная","Сепия","Бумага"),listOf("night","black","sepia","paper").indexOf(a.prefs.str("rt","night"))) { i -> a.prefs.put("rt",listOf("night","black","sepia","paper")[i]); a.savePreferences(); applyTypography() })
        box.addView(PolkaSwitch(a).apply { text="По ширине"; setTextColor(a.fg); minHeight=a.d(48); isChecked=a.prefs.optBoolean("justify",true); setOnCheckedChangeListener { _,on -> a.prefs.put("justify",on); a.savePreferences(); applyTypography() } })
        panel=a.dialog().setTitle("Оформление чтения").setView(box).show()
    }
    fun readerMenu() { a.dialog().setTitle(book.str("title")).setItemIcons("search","notebook","pen","download").setItems(arrayOf("Поиск по книге","Блокнот: цитаты и мысли","Данные книги","Скачать файл")) { _,i -> when(i) { 0->navigation(2); 1->notebook(); 2->a.metadataEditor(book); 3->a.dialog().setTitle("Скачать файл").setItemIcons("download","download").setItems(arrayOf("FB2","EPUB")){_,type->a.exportBook(book,if(type==0)"fb2" else "epub")}.show() } }.show() }
    fun fullSearch() {
        val input=a.edit("","Слово или фраза")
        a.dialog().setTitle("Поиск по книге").setView(input).setPositiveButton("Найти") { _,_ ->
            val term=input.text.toString().trim(); if(term.isBlank()) return@setPositiveButton
            val width=(scroll.width-body.paddingLeft-body.paddingRight).coerceAtLeast(a.d(100)); val height=(scroll.height-body.paddingTop-body.paddingBottom-body.textSize*3f).toInt().coerceAtLeast(a.d(80)); val fontSize=body.textSize; val ink=body.currentTextColor; val leading=lineHeight()
            val results=mutableListOf<Triple<Int,Int,String>>()
            a.work({
                for((index,ch) in chapters.objects().withIndex()) {
                    if(results.size>=500) break
                    val text=ReadingText(a,fontSize,width,height,images,ink,leading,true).render(ch.str("html")).text.toString(); var offset=0
                    while(results.size<500) { val at=text.indexOf(term,offset,true); if(at<0) break; results.add(Triple(index,at,ch.str("title")+"\n…"+text.substring(maxOf(0,at-35),minOf(text.length,at+term.length+70)).replace('\n',' ')+"…")); offset=at+term.length }
                }
            }) { a.dialog().setTitle("Найдено: ${results.size}").setItems(results.map { it.third }.toTypedArray()) { _,index -> val result=results[index]; changeChapter(result.first); body.post { jumpOffset(result.second) } }.show() }
        }.show()
    }
    fun notebook()=navigation(1)
    fun searchEntries(term:String,done:(List<Triple<Int,Int,String>>)->Unit){
        val width=(scroll.width-body.paddingLeft-body.paddingRight).coerceAtLeast(a.d(100));val height=(scroll.height-body.paddingTop-body.paddingBottom-body.textSize*3f).toInt().coerceAtLeast(a.d(80));val fontSize=body.textSize;val ink=body.currentTextColor;val leading=lineHeight()
        a.compute({
            val results=mutableListOf<Triple<Int,Int,String>>()
            for((index,ch) in chapters.objects().withIndex()){
                if(results.size>=500)break
                val text=ReadingText(a,fontSize,width,height,images,ink,leading,true).render(ch.str("html")).text.toString();var offset=0
                while(results.size<500){val at=text.indexOf(term,offset,true);if(at<0)break;results.add(Triple(index,at,ch.str("title")+"\n…"+text.substring(maxOf(0,at-35),minOf(text.length,at+term.length+70)).replace('\n',' ')+"…"));offset=at+term.length}
            };results.toList()
        },done={if(!disposed)done(it)})
    }
    fun openLink(href:String) {
        if(!href.contains(":")) {
            val path=href.substringBefore('#'); val id=href.substringAfter('#',"")
            val candidates=chapters.objects().mapIndexedNotNull { index,ch ->
                if(path.isNotBlank() && ch.str("path").isNotBlank() && ch.str("path")!=path) return@mapIndexedNotNull null
                val element=if(id.isNotBlank()) Jsoup.parse(ch.str("html")).getElementById(id) else null
                val saved=ch.optJSONObject("anchors")?.str(id)
                if(element!=null) Triple(index,element.text(),element.attr("epub:type").contains("note") || element.attr("role")=="doc-footnote" || ch.optBoolean("notes"))
                else if(!saved.isNullOrBlank()) Triple(index,saved,ch.optBoolean("notes"))
                else if(id.isBlank() && ch.str("path")==path) Triple(index,"",false) else null
            }
            val found=candidates.firstOrNull { it.first==chapter } ?: candidates.firstOrNull() ?: return
            fun jump() { changeChapter(found.first); jumpAnchor(id) }
            if(found.third) a.dialog().setTitle("Примечание").setMessage(found.second).setPositiveButton("Перейти") { _,_ -> jump() }.show() else jump()
        } else if(href.startsWith("https://") || href.startsWith("http://") || href.startsWith("mailto:")) a.confirm("Открыть ссылку?",href) { try { a.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(href))) } catch(_:android.content.ActivityNotFoundException) { a.dialog().setMessage("Нет приложения для открытия ссылки").setPositiveButton("Закрыть",null).show() } }
    }
    fun editChapter() {
        if(editorOpen||preparing||disposed||pageSlidePending||turnClock!=null||chapterRich.isEmpty())return
        save();stopPageMotion();val full=android.text.SpannableStringBuilder(chapterRich)
        val start=if(paged())pageBase()else body.layout?.let{it.getLineStart(it.getLineForVertical(scroll.scrollY))}?:0
        val end=if(paged())pageStarts.getOrElse(pageIndex+1){full.length}else body.layout?.let{it.getLineEnd(it.getLineForVertical(scroll.scrollY+scroll.height-body.paddingTop-body.paddingBottom))}?:full.length
        val draft=PageDraft(chapter,start,end,android.text.SpannableStringBuilder(body.text,if(paged())0 else start,if(paged())body.text.length else end),full,images,calm)
        editorOpen=true;handler.removeCallbacks(reflow);pageEditor=ChapterEditor(a,this,book,content,draft).also{it.show()}
    }
    internal fun mountEditor(editor:EditText,header:View,toolbar:View){
        topBar.animate().cancel();bottom.animate().cancel();topBar.visibility=View.INVISIBLE;bottom.visibility=View.INVISIBLE;folio.visibility=View.INVISIBLE
        scroll.layoutParams=(scroll.layoutParams as FrameLayout.LayoutParams).apply{topMargin=a.d(52);bottomMargin=a.d(80)}
        scroll.removeView(body);scroll.addView(editor,FrameLayout.LayoutParams(-1,-2));scroll.scrollTo(0,0)
        toolbar.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->val lp=scroll.layoutParams as FrameLayout.LayoutParams;val inset=toolbar.height+a.d(24);if(lp.bottomMargin!=inset){lp.bottomMargin=inset;scroll.layoutParams=lp}}
        // The editable viewport reserves chrome space; the keyboard/caret cannot
        // push text underneath either bar.
        view.addView(header,FrameLayout.LayoutParams(-1,a.d(52),Gravity.TOP));view.addView(toolbar,FrameLayout.LayoutParams(a.d(260),-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=a.d(12)})
    }
    internal fun unmountEditor(editor:EditText,header:View,toolbar:View,draft:PageDraft,changed:Boolean){
        view.removeView(header);view.removeView(toolbar);scroll.removeView(editor);scroll.layoutParams=(scroll.layoutParams as FrameLayout.LayoutParams).apply{topMargin=0;bottomMargin=0};scroll.addView(body,FrameLayout.LayoutParams(-1,-2));editorOpen=false;scroll.scrollTo(0,0);pageEditor=null;lastCredit=System.currentTimeMillis();activeAt=lastCredit
        setCalm(draft.calm,false);if(changed){committedKey="";pendingOffset=draft.start;paintChapter()}else if(paged())paintPage()else body.post{jumpOffset(draft.start)}
    }
    fun requestClose():Boolean {val edit=pageEditor?:return true;edit.cancel();return false}
}
