package app.polka.nativeapp

import android.app.AlertDialog
import android.content.*
import android.text.*
import android.view.*
import android.widget.*
import org.json.*

/** The same three navigation sections as the source: contents, notebook and search. */
internal class ReaderNavigation(private val a:MainActivity,private val r:NativeReader,initial:Int=0){
 private val box=a.column()
 private val tabs=a.column()
 private val pane=a.column()
 private var panel:AlertDialog?=null
 private var selected=initial
 private var filter=0
 private var searchGeneration=0
 private val fields=listOf("quotes","bookmarks")
 fun show(){
  box.addView(tabs);a.space(box,16);box.addView(pane)
  // One segment for the whole life of the sheet, so its thumb can slide; only the pane below is swapped.
  tabs.addView(roundTabs(listOf("Оглавление","Блокнот","Поиск")))
  fill();panel=a.dialog().setTitle("${r.book.str("title")}").setView(box).setTall(true).show()
  a.observeBook(box,r.book.str("id")){fresh->replaceJson(r.book,fresh);if(selected==1)drawNotebook()}
 }
 // Fully round track and thumb, matching the pills used across the app.
 private fun roundTabs(labels:List<String>):LinearLayout{
  val box=SlidingSegment(a,selected,labels.size,quiet(),if(a.light)android.graphics.Color.WHITE else 0xff48484c.toInt(),24)
  fun tint(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply{isSelected=i==index;setTextColor(if(isSelected)a.fg else a.muted)}}
  labels.forEachIndexed{i,s->box.addView(a.text(s,14f).apply{gravity=Gravity.CENTER;minHeight=a.d(38);a.press(this);setOnClickListener{if(i!=selected){box.choose(i);tint(i);switchTo(i)}}},LinearLayout.LayoutParams(0,-2,1f))}
  tint(selected);return box
 }
 private fun close(){r.refreshBookmark();panel?.dismiss()}
 private var transition=0
 private fun reduced()=a.prefs.str("motion")=="reduced"
 private fun switchTo(index:Int){
  if(index==selected)return;val direction=if(index>selected)1 else -1;selected=index;val token=++transition
  if(reduced()||!pane.isAttachedToWindow){pane.animate().cancel();pane.alpha=1f;pane.translationX=0f;fill();return}
  val shift=a.d(18).toFloat();pane.animate().cancel()
  pane.animate().alpha(0f).translationX(-direction*shift).setDuration(110).setInterpolator(android.view.animation.AccelerateInterpolator()).withEndAction{
   if(token!=transition)return@withEndAction;fill();pane.translationX=direction*shift
   pane.animate().alpha(1f).translationX(0f).setDuration(260).setInterpolator(android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start()
  }.start()
 }
 /** Containers the notebook opened up for its cards' shadows, with how they were; the other tabs get them back clipped,
  *  or the chapter list would draw over the tabs when it scrolls. */
 private val unclipped=HashMap<ViewGroup,Pair<Boolean,Boolean>>()
 private fun fill(){
  pane.removeAllViews()
  if(selected!=1){unclipped.forEach{(v,o)->v.clipChildren=o.first;v.clipToPadding=o.second};unclipped.clear()}
  when(selected){0->contents();1->notebook();2->search()}
 }
 private fun contents(){
  val toc=r.contents()
  var here:View?=null
  // the chapters scroll on their own, under the tabs: opening at the current chapter never hides «Оглавление / Блокнот / Поиск»
  val list=a.column();val holder=SheetScroll(a,if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt(),30,18).apply{isVerticalScrollBarEnabled=false;addView(list)}
  pane.addView(holder,LinearLayout.LayoutParams(-1,(a.resources.displayMetrics.heightPixels*.6f).toInt()))
  // the list runs down to the bottom edge of the screen (under the gesture bar), like every other list in the app
  holder.clipToPadding=false;holder.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
   val sv=generateSequence(holder.parent){it.parent}.filterIsInstance<android.widget.ScrollView>().firstOrNull()?:return true
   val at=IntArray(2);holder.getLocationOnScreen(at);val s=IntArray(2);sv.getLocationOnScreen(s);val c=IntArray(2);sv.getChildAt(0).getLocationOnScreen(c)
   val below=(c[1]+sv.getChildAt(0).height)-(at[1]+holder.height)
   val want=(s[1]+sv.height-at[1]-below).coerceAtLeast(a.d(200))
   holder.setPadding(0,0,0,sv.paddingBottom+a.d(16))
   val lp=holder.layoutParams;if(lp.height!=want){lp.height=want;holder.layoutParams=lp;return false};return true}})
  toc.forEach{item->val current=item.optInt("ch")==r.chapter;list.addView(a.text(r.prettyTitle(item.str("title")),16f,if(current)a.accent else a.fg,current).apply{minHeight=a.d(48);gravity=Gravity.CENTER_VERTICAL;if(current)background=a.shape((a.accent and 0xffffff) or (0x1f shl 24),14);setPadding(a.d(12+item.optInt("depth").coerceIn(0,8)*12),a.d(10),a.d(8),a.d(10));setOnClickListener{close();r.goToLocation(item.optInt("ch"),0,item.str("anchor"))}}.also{if(current&&here==null)here=it})}
  // the list opens at the chapter being read (a little of the one before it still showing above)
  here?.let{row->holder.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
   if(row.height==0)return true;holder.viewTreeObserver.removeOnPreDrawListener(this)
   holder.scrollTo(0,(row.top-a.d(56)).coerceAtLeast(0));return true}})}
 }
 private lateinit var notebookResults:LinearLayout
 private val chipViews=mutableListOf<TextView>()
 private val chipAnimators=mutableMapOf<TextView,android.animation.ValueAnimator>()
 private val chipLabels=listOf("Все","Цитаты","Закладки")
 private fun quiet()=if(a.light)0xffeeeeec.toInt() else 0xff2f2f31.toInt()
 private fun notebook(){
  // Filter chips are built once; selecting one only recolours them (no rebuild), and the list below fades in.
  chipViews.clear();val chips=a.line()
  chipLabels.forEachIndexed{index,_->
   val chip=a.text("",14f,a.fg,true).apply{gravity=Gravity.CENTER;minHeight=a.d(38);maxLines=1;setPadding(a.d(16),0,a.d(16),0);tag=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(19).toFloat()};background=tag as android.graphics.drawable.Drawable;isClickable=true;isFocusable=true;setOnClickListener{selectFilter(index)};a.press(this)}
   chipViews.add(chip);chips.addView(chip,LinearLayout.LayoutParams(-2,a.d(38)).apply{if(index>0)leftMargin=a.d(8)})
  }
  val strip=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(chips);setPadding(0,a.d(2),0,a.d(4))};pane.addView(strip)
  a.space(pane,16);notebookResults=a.column().apply{clipChildren=false;clipToPadding=false};pane.addView(notebookResults)
  // the cards' shadows are wider than the cards: no container between them and the scrolling sheet may cut them
  pane.post{if(selected!=1)return@post;var v:android.view.ViewParent?=pane;while(v is ViewGroup&&v !is android.widget.ScrollView){val g:ViewGroup=v;unclipped.getOrPut(g){g.clipChildren to g.clipToPadding};g.clipChildren=false;g.clipToPadding=false;v=g.parent};(v as? ViewGroup)?.let{unclipped.getOrPut(it){it.clipChildren to it.clipToPadding};it.clipToPadding=false}}
  updateChips(false);drawNotebook()
 }
 private fun selectFilter(index:Int){if(index==filter)return;filter=index;updateChips(true);drawNotebook(true)}
 private fun updateChips(animate:Boolean){
  // a note is part of its quote: only quotes and bookmarks are counted
  val q=r.book.optJSONArray("quotes")?.length()?:0;val b=r.book.optJSONArray("bookmarks")?.length()?:0;val all=listOf(q+b,q,b)
  chipViews.forEachIndexed{i,chip->
   chip.text="${chipLabels[i]} ${all[i]}";val on=i==filter;chip.isSelected=on
   val shape=chip.tag as android.graphics.drawable.GradientDrawable;val bg=if(on)a.fg else quiet();val ink=if(on)a.bg else a.fg
   chipAnimators.remove(chip)?.cancel()
   if(!animate||reduced()){shape.setColor(bg);chip.setTextColor(ink);return@forEachIndexed}
   val fromBg=(shape.color?.defaultColor)?:bg;val fromInk=chip.currentTextColor
   chipAnimators[chip]=(android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=200;addUpdateListener{val t=it.animatedFraction;shape.setColor(android.animation.ArgbEvaluator().evaluate(t,fromBg,bg) as Int);chip.setTextColor(android.animation.ArgbEvaluator().evaluate(t,fromInk,ink) as Int)};start()})
  }
 }
 private fun drawNotebook(animate:Boolean=false){
  if(!::notebookResults.isInitialized||selected!=1)return
  if(chipViews.isNotEmpty()&&!animate)updateChips(false)
  notebookResults.removeAllViews()
  val rows=fields.flatMapIndexed{index,key->r.book.optJSONArray(key)?.objects().orEmpty().map{index to it}}.filter{(kind,item)->(filter==0||(filter==1&&kind==0)||(filter==2&&kind==1))&&("${item.str("text")} ${item.str("note")} ${r.chapterName(item.optInt("ch"))}")}.sortedByDescending{it.second.optLong("updatedAt",it.second.optLong("at"))}
  if(rows.isEmpty()){
   val box=if(filter==2)a.emptyView("bookmark","Пока нет закладок","Нажмите на закладку в нижней панели.",100) else a.emptyView("note",if(filter==1)"Пока нет цитат" else "Пока нет записей","Выделите текст и нажмите «Цитата» или «Заметка».",100)
   box.setPadding(0,a.d(52),0,a.d(24))
   notebookResults.addView(box,LinearLayout.LayoutParams(-1,-2));fadeResults(animate);return
  }
  rows.forEach{(kind,item)->
   // quotes are the same cards as on «Цитаты» and the book card (no fan), the note inside them; a tap on the quote goes to its place
   if(kind==0){
    val go={close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0)))}
    val card=a.quoteCardNative(r.book,item,onOpen=go,onDelete={
     a.confirm("Удалить цитату?",if(item.str("note").isBlank())"Удалить выбранную запись?" else "Заметка к ней тоже удалится."){r.book.put("quotes",JSONArray(r.book.optJSONArray("quotes")?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();r.paginate();drawNotebook()}})
    notebookResults.addView(card,LinearLayout.LayoutParams(-1,-2));a.space(notebookResults,14);return@forEach}
   notebookResults.addView(bookmarkCard(item),LinearLayout.LayoutParams(-1,-2));a.space(notebookResults,10)
  }
  fadeResults(animate)
 }
 /** A bookmark: one compact line — the chapter once, the page and the date under it, go and remove on the right. */
 private fun bookmarkCard(item:JSONObject):View{
  val tone=a.fg and 0xffffff
  val go={close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0)))}
  val card=a.line().apply{gravity=Gravity.CENTER_VERTICAL;a.floatCard(this,24);background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)android.graphics.Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(24).toFloat();setStroke(1,(0x14 shl 24) or tone)}
   setPadding(a.d(14),a.d(10),a.d(10),a.d(10));isClickable=true;setOnClickListener{go()};a.press(this);contentDescription="Закладка: ${r.chapterName(item.optInt("ch"))}"}
  card.addView(IconView(a,"bookmark",a.muted),LinearLayout.LayoutParams(a.d(20),a.d(20)).apply{rightMargin=a.d(12)})
  val texts=a.column()
  texts.addView(a.text(r.chapterName(item.optInt("ch")),15.5f,a.fg,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
  val page=r.pageOf(item.optInt("ch"),item.optInt("nativeOffset",0),item.optInt("page"))
  val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale.forLanguageTag("ru")).format(java.util.Date(item.optLong("at",System.currentTimeMillis())))
  texts.addView(a.text(listOfNotNull(page?.let{"стр. $it"},date).joinToString("   "),12.5f,a.muted).apply{setPadding(0,a.d(2),0,0)})
  card.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
  card.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor((0x14 shl 24) or tone)};isClickable=true;contentDescription="Удалить закладку"
   addView(QuoteActionIcon(a,"trash",a.fg),FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.CENTER));a.press(this)
   setOnClickListener{a.confirm("Удалить закладку?","Удалить выбранную запись?"){r.book.put("bookmarks",JSONArray(r.book.optJSONArray("bookmarks")?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();drawNotebook();r.refreshBookmark()}}},LinearLayout.LayoutParams(a.d(36),a.d(36)))
  return card
 }
 private fun fadeResults(animate:Boolean){
  notebookResults.animate().cancel()
  if(!animate||reduced()){notebookResults.alpha=1f;notebookResults.translationY=0f;return}
  notebookResults.alpha=0f;notebookResults.translationY=a.d(8).toFloat()
  notebookResults.animate().alpha(1f).translationY(0f).setDuration(240).setStartDelay(40).setInterpolator(android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start()
 }
 private fun search(){
  val input=a.edit("","Поиск").apply{contentDescription="Поиск по книге";imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH};val results=a.column();pane.addView(searchField(input));a.space(pane,14);pane.addView(results)
  fun run(){val generation=++searchGeneration;val q=input.text.toString().trim();results.removeAllViews();if(q.length<2)return;results.addView(a.text("Поиск…",15f,a.muted));r.searchEntries(q){rows->if(generation==searchGeneration&&selected==2&&box.isAttachedToWindow){results.removeAllViews();if(rows.isEmpty()){results.addView(a.emptyView("search","Ничего не нашлось","Этого слова нет в книге.",100).apply{setPadding(0,a.d(52),0,a.d(24))},LinearLayout.LayoutParams(-1,-2));return@searchEntries};results.addView(a.text("Найдено: ${rows.size}",13f,a.muted));rows.forEach{row->results.addView(a.text(row.third,15f).apply{setPadding(0,a.d(12),0,a.d(12));setOnClickListener{close();r.goToLocation(row.first,row.second)}})}}}}
  input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){input.removeCallbacks(searchTask);searchTask=Runnable{run()};input.postDelayed(searchTask,250)};override fun afterTextChanged(s:Editable?){} });run()
 }
 private fun searchField(input:EditText):View{
  val field=FrameLayout(a);input.setPadding(a.d(38),0,a.d(12),0);input.textSize=16f;input.background=OpaqueSurface(a,22f);field.addView(input,FrameLayout.LayoutParams(-1,a.d(44)));field.addView(IconView(a,"search",a.muted),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER_VERTICAL).apply{leftMargin=a.d(12)});return field
 }
 private var searchTask=Runnable{}
}
