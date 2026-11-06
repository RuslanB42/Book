package app.polka.nativeapp

import android.app.AlertDialog
import android.content.*
import android.graphics.Typeface
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
 private var query=""
 private var searchGeneration=0
 private val fields=listOf("quotes","notes","bookmarks")
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
 private fun fill(){
  pane.removeAllViews()
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
  toc.forEach{item->val current=item.optInt("ch")==r.chapter;list.addView(a.text(item.str("title"),16f,if(current)a.accent else a.fg,current).apply{minHeight=a.d(48);gravity=Gravity.CENTER_VERTICAL;if(current)background=a.shape((a.accent and 0xffffff) or (0x1f shl 24),14);setPadding(a.d(12+item.optInt("depth").coerceIn(0,8)*12),a.d(10),a.d(8),a.d(10));setOnClickListener{close();r.goToLocation(item.optInt("ch"),0,item.str("anchor"))}}.also{if(current&&here==null)here=it})}
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
  a.space(pane,16);notebookResults=a.column();pane.addView(notebookResults)
  updateChips(false);drawNotebook()
 }
 private fun selectFilter(index:Int){if(index==filter)return;filter=index;updateChips(true);drawNotebook(true)}
 private fun updateChips(animate:Boolean){
  // a thought tied to a quote is part of that quote: counted once, with the quotes
  val quoteIds=r.book.optJSONArray("quotes")?.objects().orEmpty().map{it.str("id")}.toSet()
  val loose=r.book.optJSONArray("notes")?.objects().orEmpty().count{it.str("quoteId").isBlank()||it.str("quoteId") !in quoteIds}
  val q=(r.book.optJSONArray("quotes")?.length()?:0)+loose;val b=r.book.optJSONArray("bookmarks")?.length()?:0;val all=listOf(q+b,q,b)
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
  val quoteIds=r.book.optJSONArray("quotes")?.objects().orEmpty().map{it.str("id")}.toSet()
  val rows=fields.flatMapIndexed{index,key->r.book.optJSONArray(key)?.objects().orEmpty().map{index to it}}.filter{(kind,item)->!(kind==1&&item.str("quoteId") in quoteIds&&item.str("quoteId").isNotBlank())}.filter{(kind,item)->(filter==0||(filter==1&&kind<=1)||(filter==2&&kind==2))&&("${item.str("text")} ${fragment(kind,item)} ${r.chapterName(item.optInt("ch"))}").contains(query,true)}.sortedByDescending{it.second.optLong("updatedAt",it.second.optLong("at"))}
  if(rows.isEmpty()){
   // a quiet empty state: an icon, a short title and one hint
   val box=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(24),a.d(44),a.d(24),a.d(24))}
   val icon=listOf("quotes","quotes","bookmark")[filter.coerceIn(0,2)]
   box.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(quiet())};addView(IconView(a,if(query.isNotBlank())"search" else icon,a.muted),FrameLayout.LayoutParams(a.d(30),a.d(30),Gravity.CENTER))},LinearLayout.LayoutParams(a.d(72),a.d(72)))
   val title=if(query.isNotBlank())"Ничего не нашлось" else listOf("Пока нет записей","Пока нет цитат","Пока нет закладок")[filter.coerceIn(0,2)]
   box.addView(a.text(title,22f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18)})
   val hint=if(query.isNotBlank())"Попробуйте другое слово." else if(filter==2)"Добавьте закладку кнопкой в нижней панели — она появится здесь." else "Выделите текст в книге и нажмите «Цитата» или «Заметка» — запись появится здесь."
   box.addView(a.text(hint,14f,a.muted).apply{gravity=Gravity.CENTER;cssLeading(1.5f)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(8)})
   notebookResults.addView(box,LinearLayout.LayoutParams(-1,-2));fadeResults(animate);return
  }
  rows.forEach{(kind,item)->
   // quotes are the same cards as on «Цитаты» and the book card (no fan), the thought inside them
   if(kind==0){
    val go={close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0)))}
    val card=a.quoteCardNative(r.book,item,onOpen=go,onGo=go,onThought={n->a.editThought(r.book,n,quote=item,ch=item.optInt("ch"))},onDelete={
     a.confirm("Удалить цитату?","Мысли к этому фрагменту останутся в блокноте."){r.book.put("quotes",JSONArray(r.book.optJSONArray("quotes")?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();r.paginate();drawNotebook()}})
    notebookResults.addView(card,LinearLayout.LayoutParams(-1,-2));a.space(notebookResults,14);return@forEach}
   notebookResults.addView(entryCard(kind,item),LinearLayout.LayoutParams(-1,-2));a.space(notebookResults,if(kind==2)10 else 14)
  }
  fadeResults(animate)
 }
 /** Notes and bookmarks as the same kind of card as the quotes (approved look), in a quiet neutral tone: a small
  *  label and the date on top, the fragment in the book's serif, the thought, then the chapter and round buttons. */
 /** A bookmark: one compact line — the chapter once, the page and the date under it, go and remove on the right. */
 private fun bookmarkCard(item:JSONObject):View{
  val tone=a.fg and 0xffffff
  val go={close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0)))}
  val card=a.line().apply{gravity=Gravity.CENTER_VERTICAL;a.floatCard(this,20);background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)android.graphics.Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(20).toFloat();setStroke(1,(0x14 shl 24) or tone)}
   setPadding(a.d(14),a.d(10),a.d(10),a.d(10));isClickable=true;setOnClickListener{go()};a.press(this);contentDescription="Закладка: ${r.chapterName(item.optInt("ch"))}"}
  card.addView(IconView(a,"bookmark",a.muted),LinearLayout.LayoutParams(a.d(20),a.d(20)).apply{rightMargin=a.d(12)})
  val texts=a.column()
  texts.addView(a.text(r.chapterName(item.optInt("ch")),15.5f,a.fg,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
  val page=r.pageOf(item.optInt("ch"),item.optInt("nativeOffset",0),item.optInt("page"))
  val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale.forLanguageTag("ru")).format(java.util.Date(item.optLong("at",System.currentTimeMillis())))
  texts.addView(a.text(listOfNotNull(page?.let{"стр. $it"},date).joinToString(" · "),12.5f,a.muted).apply{setPadding(0,a.d(2),0,0)})
  card.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
  card.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor((0x14 shl 24) or tone)};isClickable=true;contentDescription="Удалить закладку"
   addView(QuoteActionIcon(a,"trash",a.fg),FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.CENTER));a.press(this)
   setOnClickListener{a.confirm("Удалить закладку?","Удалить выбранную запись?"){r.book.put("bookmarks",JSONArray(r.book.optJSONArray("bookmarks")?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();drawNotebook();r.refreshBookmark()}}},LinearLayout.LayoutParams(a.d(36),a.d(36)))
  return card
 }
 private fun entryCard(kind:Int,item:JSONObject):View{
  if(kind==2)return bookmarkCard(item)
  val whole=kind==1&&item.str("scope")=="book";val ink=a.fg;val tone=(ink and 0xffffff)
  val card=a.column().apply{a.floatCard(this,22);background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)android.graphics.Color.WHITE else 0xff242426.toInt());cornerRadius=a.d(22).toFloat();setStroke(1,(0x14 shl 24) or tone)}
   setPadding(a.d(18),a.d(14),a.d(14),a.d(10));contentDescription="${listOf("Цитата","Заметка","Закладка")[kind]}: ${item.str("text")}"}
  val go={close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0)))}
  val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
  top.addView(IconView(a,if(kind==2)"bookmark" else "pen",a.muted),LinearLayout.LayoutParams(a.d(16),a.d(16)).apply{rightMargin=a.d(6)})
  top.addView(a.text(if(whole)"О книге" else listOf("Цитата","Заметка","Закладка")[kind],12f,a.muted,true),LinearLayout.LayoutParams(0,-2,1f))
  top.addView(a.text(java.text.SimpleDateFormat("d MMMM",java.util.Locale.forLanguageTag("ru")).format(java.util.Date(item.optLong("at",System.currentTimeMillis()))),12f,a.muted))
  card.addView(top)
  val fragment=fragment(kind,item)
  if(fragment.isNotBlank())card.addView(a.text(fragment,if(kind==2)17f else 15.5f,if(kind==2)a.fg else a.muted,serif=true).apply{cssLeading(1.55f);maxLines=if(kind==2)6 else 4;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(10),0,0);if(!whole)setOnClickListener{go()}})
  if(kind==1)card.addView(a.text(item.str("text"),16f).apply{cssLeading(1.5f);setPadding(0,a.d(10),0,0);setOnClickListener{a.editThought(r.book,item,item.str("scope","chapter"),ch=item.optInt("ch"))}})
  card.addView(View(a).apply{setBackgroundColor((0x1f shl 24) or tone)},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14)})
  val foot=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(10),0,a.d(2))}
  foot.addView(a.text(if(whole)"Вся книга" else r.chapterName(item.optInt("ch")),12.5f,a.fg,true).apply{background=a.shape((0x14 shl 24) or tone,14);setPadding(a.d(10),a.d(4),a.d(10),a.d(5));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=a.d(150)})
  if(kind==2)r.pageOf(item.optInt("ch"),item.optInt("nativeOffset",0),item.optInt("page"))?.let{p->foot.addView(a.text("стр. $p",12.5f,a.fg,true).apply{background=a.shape((0x14 shl 24) or tone,14);setPadding(a.d(10),a.d(4),a.d(10),a.d(5))},LinearLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(6)})}
  foot.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
  fun round(icon:String,desc:String,fn:()->Unit){foot.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor((0x14 shl 24) or tone)};isClickable=true;contentDescription=desc
   addView(QuoteActionIcon(a,icon,a.fg),FrameLayout.LayoutParams(a.d(22),a.d(22),Gravity.CENTER));a.press(this);setOnClickListener{fn()}},LinearLayout.LayoutParams(a.d(38),a.d(38)).apply{leftMargin=a.d(8)})}
  if(!whole)round("go","К месту в книге"){go()}
  if(kind==1)round("pen","Изменить мысль"){a.editThought(r.book,item,item.str("scope","chapter"),ch=item.optInt("ch"))}
  if(kind!=2)round("copy","Копировать запись"){
   val text=(if(fragment.isBlank())"" else "«$fragment»\n\n")+item.str("text")
   (a.getSystemService(Context.CLIPBOARD_SERVICE)as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Блокнот",text))}
  round("trash","Удалить ${listOf("цитату","мысль","закладку")[kind]}"){
   a.confirm("Удалить ${listOf("цитату","мысль","закладку")[kind]}?","Удалить выбранную запись?"){
    val field=fields[kind];r.book.put(field,JSONArray(r.book.optJSONArray(field)?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();drawNotebook();r.refreshBookmark()}}
  card.addView(foot);return card
 }
 private fun fadeResults(animate:Boolean){
  notebookResults.animate().cancel()
  if(!animate||reduced()){notebookResults.alpha=1f;notebookResults.translationY=0f;return}
  notebookResults.alpha=0f;notebookResults.translationY=a.d(8).toFloat()
  notebookResults.animate().alpha(1f).translationY(0f).setDuration(240).setStartDelay(40).setInterpolator(android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start()
 }
 private fun fragment(kind:Int,item:JSONObject):String=if(kind!=1)item.str("text")else r.book.optJSONArray("quotes")?.objects()?.firstOrNull{it.str("id")==item.str("quoteId")&&item.str("quoteId").isNotBlank()}?.str("text")?:item.str("quoteText",item.str("quote"))
 private fun search(){
  val input=a.edit("","Поиск").apply{contentDescription="Поиск по книге";imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH};val results=a.column();pane.addView(searchField(input));a.space(pane,14);pane.addView(results)
  fun run(){val generation=++searchGeneration;val q=input.text.toString().trim();results.removeAllViews();if(q.length<2)return;results.addView(a.text("Поиск…",15f,a.muted));r.searchEntries(q){rows->if(generation==searchGeneration&&selected==2&&box.isAttachedToWindow){results.removeAllViews();results.addView(a.text("Найдено: ${rows.size}",13f,a.muted));rows.forEach{row->results.addView(a.text(row.third,15f).apply{setPadding(0,a.d(12),0,a.d(12));setOnClickListener{close();r.goToLocation(row.first,row.second)}})}}}}
  input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){input.removeCallbacks(searchTask);searchTask=Runnable{run()};input.postDelayed(searchTask,250)};override fun afterTextChanged(s:Editable?){} });run()
 }
 private fun searchField(input:EditText):View{
  val field=FrameLayout(a);input.setPadding(a.d(38),0,a.d(12),0);input.textSize=16f;input.background=OpaqueSurface(a,22f);field.addView(input,FrameLayout.LayoutParams(-1,a.d(44)));field.addView(IconView(a,"search",a.muted),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER_VERTICAL).apply{leftMargin=a.d(12)});return field
 }
 private var searchTask=Runnable{}
}
