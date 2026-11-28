package app.polka.nativeapp

import android.content.*
import android.text.*
import android.view.*
import android.widget.*
import org.json.*

/** The same three navigation sections as the source: contents, notebook and search. */
/** The same three navigation sections as the source: contents, notebook and search. They open inside the reader's
 *  bottom panel (approved «Правило окон»: the reader's tools live in its panel), the tabs fixed at the top and each
 *  section scrolling under them. */
internal class ReaderNavigation(private val a:MainActivity,private val r:NativeReader,initial:Int=0){
 private val box=a.column()
 /** The sections' room: a fixed height, so moving between tabs never changes the panel's size. */
 private val pane=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
 private var paneHeight=0
 private var selected=initial
 private var filter=0
 private var searchGeneration=0
 private val fields=listOf("quotes","bookmarks")
 /** The scrolling lists reach the panel's sides, where the cards' shadows fall. */
 private val side get()=a.d(18)
 fun show(){
  // One segment for the whole life of the page, so its thumb can slide; only the pane below is swapped.
  box.addView(roundTabs(listOf("Оглавление","Блокнот","Поиск")),LinearLayout.LayoutParams(-1,-2))
  paneHeight=(r.panel.room()-a.d(38+16)).coerceAtLeast(a.d(200))
  box.addView(pane,LinearLayout.LayoutParams(-1,paneHeight).apply{topMargin=a.d(16);leftMargin=-side;rightMargin=-side})
  fill()
  r.panel.open(ReaderPanel.Page(r.book.str("title"),box,onLeave={leave()}))
  a.observeBook(box,r.book.str("id")){fresh->replaceJson(r.book,fresh);if(selected==1)drawNotebook()}
 }
 // Fully round track and thumb, matching the pills used across the app.
 private fun roundTabs(labels:List<String>):LinearLayout{
  val box=SlidingSegment(a,selected,labels.size,quiet(),Ui.selected(a.light),Radius.R24)
  fun tint(index:Int){for(i in 0 until box.childCount)(box.getChildAt(i) as TextView).apply{isSelected=i==index;setTextColor(if(isSelected)a.fg else a.muted)}}
  labels.forEachIndexed{i,s->box.addView(a.text(s,Type.TEXT).apply{gravity=Gravity.CENTER;minHeight=a.d(38);a.press(this);setOnClickListener{if(i!=selected){box.choose(i);tint(i);switchTo(i)}}},LinearLayout.LayoutParams(0,-2,1f))}
  tint(selected);return box
 }
 /** The page goes (a chapter or a note chosen, «‹», Back or a tap on the book): the keyboard and the lifted panel too. */
 private fun leave(){follow(false);r.refreshBookmark()}
 private fun close(){r.panel.home()}
 /** Search lifts the panel over the keyboard; the page under it is left as it is. */
 private fun follow(on:Boolean){
  if(on){r.imeFollower={h->val by=r.liftPanel(h);pane.layoutParams=pane.layoutParams.apply{height=(paneHeight-by).coerceAtLeast(a.d(120))}};return}
  if(r.imeFollower==null)return
  r.imeFollower=null;a.releaseTextFocus();r.liftPanel(0);pane.layoutParams=pane.layoutParams.apply{height=paneHeight}}
 private var transition=0
 private fun reduced()=a.reducedMotion
 private fun switchTo(index:Int){
  if(index==selected)return;val direction=if(index>selected)1 else -1;selected=index;val token=++transition
  if(reduced()||!pane.isAttachedToWindow){pane.animate().cancel();pane.alpha=1f;pane.translationX=0f;fill();return}
  // the tabs move like the app's own tabs (see MotionSpec)
  val shift=a.d(MotionSpec.TAB_SHIFT).toFloat();pane.animate().cancel()
  pane.animate().alpha(0f).translationX(-direction*shift).setDuration(MotionSpec.TAB_OUT).setInterpolator(MotionSpec.exit).withEndAction{
   if(token!=transition)return@withEndAction;fill();pane.translationX=direction*shift
   pane.animate().alpha(1f).translationX(0f).setDuration(MotionSpec.TAB_IN).setInterpolator(MotionSpec.enter).start()
  }.start()
 }
 private fun fill(){
  pane.removeAllViews();follow(selected==2)
  when(selected){0->contents();1->notebook();2->search()}
 }
 /** A list that scrolls in the pane, fading out under the tabs and at the panel's foot. */
 private fun scroller(content:View)=SheetScroll(a,r.panel.color,30,18).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false
  setPadding(side,0,side,a.d(8));addView(content)}
 private fun contents(){
  val toc=r.contents()
  var here:View?=null
  val list=a.column();val holder=scroller(list)
  pane.addView(holder,FrameLayout.LayoutParams(-1,-1))
  toc.forEach{item->val current=item.optInt("ch")==r.chapter;list.addView(a.text(r.prettyTitle(item.str("title")),Type.BODY,if(current)a.accent else a.fg,current).apply{minHeight=a.d(48);gravity=Gravity.CENTER_VERTICAL;if(current)background=a.shape(Ui.alpha(a.accent,0x1f),Radius.R16);setPadding(a.d(12+item.optInt("depth").coerceIn(0,8)*12),a.d(10),a.d(8),a.d(10));setOnClickListener{close();r.goToLocation(item.optInt("ch"),0,item.str("anchor"))}}.also{if(current&&here==null)here=it})}
  // the list opens at the chapter being read (a little of the one before it still showing above)
  here?.let{row->holder.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
   if(row.height==0)return true;holder.viewTreeObserver.removeOnPreDrawListener(this)
   holder.scrollTo(0,(row.top-a.d(56)).coerceAtLeast(0));return true}})}
 }
 private lateinit var notebookResults:LinearLayout
 private val chipViews=mutableListOf<TextView>()
 private val chipAnimators=mutableMapOf<TextView,android.animation.ValueAnimator>()
 private val chipLabels=listOf("Все","Цитаты","Закладки")
 private fun quiet()=Ui.press(a.light)
 private fun notebook(){
  // Filter chips are built once; selecting one only recolours them (no rebuild), and the list below fades in.
  chipViews.clear();val chips=a.line()
  chipLabels.forEachIndexed{index,_->
   val chip=a.text("",Type.TEXT,a.fg,true).apply{gravity=Gravity.CENTER;minHeight=a.d(38);maxLines=1;setPadding(a.d(16),0,a.d(16),0);tag=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(Radius.R20).toFloat()};background=tag as android.graphics.drawable.Drawable;isClickable=true;isFocusable=true;setOnClickListener{selectFilter(index)};a.press(this)}
   chipViews.add(chip);chips.addView(chip,LinearLayout.LayoutParams(-2,a.d(38)).apply{if(index>0)leftMargin=a.d(8)})
  }
  val column=a.column().apply{clipChildren=false;clipToPadding=false}
  val strip=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(chips);setPadding(0,a.d(2),0,a.d(4))};column.addView(strip)
  a.space(column,16);notebookResults=a.column().apply{clipChildren=false;clipToPadding=false};column.addView(notebookResults)
  // the cards' shadows are wider than the cards: the list reaches out to the panel's sides
  pane.addView(scroller(column).apply{clipChildren=false},FrameLayout.LayoutParams(-1,-1))
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
   chipAnimators[chip]=(android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.STANDARD;addUpdateListener{val t=it.animatedFraction;shape.setColor(android.animation.ArgbEvaluator().evaluate(t,fromBg,bg) as Int);chip.setTextColor(android.animation.ArgbEvaluator().evaluate(t,fromInk,ink) as Int)};start()})
  }
 }
 private fun drawNotebook(animate:Boolean=false){
  if(!::notebookResults.isInitialized||selected!=1)return
  if(chipViews.isNotEmpty()&&!animate)updateChips(false)
  notebookResults.removeAllViews()
  val rows=fields.flatMapIndexed{index,key->r.book.optJSONArray(key)?.objects().orEmpty().map{index to it}}.filter{(kind,_)->filter==0||(filter==1&&kind==0)||(filter==2&&kind==1)}.sortedByDescending{it.second.optLong("updatedAt",it.second.optLong("at"))}
  if(rows.isEmpty()){
   val box=if(filter==2)a.emptyView("bookmark","Нет закладок","Нажмите на закладку в нижней панели.",100) else a.emptyView("note",if(filter==1)"Нет цитат" else "Нет записей","Выделите текст и нажмите «Цитата» или «Заметка».",100)
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
  val card=a.line().apply{gravity=Gravity.CENTER_VERTICAL;a.floatCard(this,24);background=Floating(a,android.graphics.drawable.GradientDrawable().apply{setColor(Ui.control(a.light));cornerRadius=a.d(Radius.R24).toFloat();setStroke(1,(0x14 shl 24) or tone)},a.d(24).toFloat())
   setPadding(a.d(14),a.d(10),a.d(10),a.d(10));isClickable=true;setOnClickListener{go()};a.press(this);contentDescription="Закладка: ${r.chapterName(item.optInt("ch"))}"}
  card.addView(IconView(a,"bookmark",a.muted),LinearLayout.LayoutParams(a.d(20),a.d(20)).apply{rightMargin=a.d(12)})
  val texts=a.column()
  texts.addView(a.text(r.chapterName(item.optInt("ch")),Type.BODY,a.fg,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
  val page=r.pageOf(item.optInt("ch"),item.optInt("nativeOffset",0),item.optInt("page"))
  val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale.forLanguageTag("ru")).format(java.util.Date(item.optLong("at",System.currentTimeMillis())))
  texts.addView(a.text(listOfNotNull(page?.let{"стр. $it"},date).joinToString("   "),Type.SECONDARY,a.muted).apply{setPadding(0,a.d(2),0,0)})
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
  notebookResults.animate().alpha(1f).translationY(0f).setDuration(Motion.STANDARD).setStartDelay(40).setInterpolator(MotionSpec.enter).start()
 }
 private fun search(){
  val input=a.edit("","Поиск").apply{contentDescription="Поиск по книге";imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH};val results=a.column()
  val column=a.column();column.addView(searchField(input),LinearLayout.LayoutParams(-1,-2).apply{leftMargin=side;rightMargin=side});a.space(column,14)
  column.addView(scroller(results),LinearLayout.LayoutParams(-1,0,1f));pane.addView(column,FrameLayout.LayoutParams(-1,-1))
  fun run(){val generation=++searchGeneration;val q=input.text.toString().trim();results.removeAllViews();if(q.length<2)return;results.addView(a.text("Поиск…",Type.TEXT,a.muted));r.searchEntries(q){rows->if(generation==searchGeneration&&selected==2&&box.isAttachedToWindow){results.removeAllViews();if(rows.isEmpty()){results.addView(a.emptyView("search","Ничего не нашлось","Этого слова нет в книге.",100).apply{setPadding(0,a.d(52),0,a.d(24))},LinearLayout.LayoutParams(-1,-2));return@searchEntries};results.addView(a.text("Найдено: ${rows.size}",Type.SECONDARY,a.muted));rows.forEach{row->results.addView(a.text(row.third,Type.TEXT).apply{setPadding(0,a.d(12),0,a.d(12));setOnClickListener{close();r.goToLocation(row.first,row.second)}})}}}}
  input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){input.removeCallbacks(searchTask);searchTask=Runnable{run()};input.postDelayed(searchTask,250)};override fun afterTextChanged(s:Editable?){} });run()
 }
 private fun searchField(input:EditText):View{
  val field=FrameLayout(a);input.setPadding(a.d(38),0,a.d(12),0);input.textSize=Type.BODY;input.background=OpaqueSurface(a,22f);field.addView(input,FrameLayout.LayoutParams(-1,a.d(44)));field.addView(IconView(a,"search",a.muted),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER_VERTICAL).apply{leftMargin=a.d(12)});return field
 }
 private var searchTask=Runnable{}
}
