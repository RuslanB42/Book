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
  toc.forEach{item->val current=item.optInt("ch")==r.chapter;list.addView(a.text(item.str("title"),16f,if(current)a.accent else a.fg,current).apply{minHeight=a.d(48);gravity=Gravity.CENTER_VERTICAL;if(current)background=a.shape((a.accent and 0xffffff) or (0x1f shl 24),14);setPadding(a.d(12+item.optInt("depth").coerceIn(0,8)*12),a.d(10),a.d(8),a.d(10));setOnClickListener{close();r.goToLocation(item.optInt("ch"),0,item.str("anchor"))}}.also{if(current&&here==null)here=it})}
  // the list opens at the chapter being read (a little of the one before it still showing above)
  here?.let{row->holder.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
   if(row.height==0)return true;holder.viewTreeObserver.removeOnPreDrawListener(this)
   holder.scrollTo(0,(row.top-a.d(56)).coerceAtLeast(0));return true}})}
 }
 private lateinit var notebookResults:LinearLayout
 private val chipViews=mutableListOf<TextView>()
 private val chipAnimators=mutableMapOf<TextView,android.animation.ValueAnimator>()
 private val chipLabels=listOf("Все","Цитаты","Заметки","Закладки")
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
  val counts=fields.map{r.book.optJSONArray(it)?.length()?:0};val all=listOf(counts.sum())+counts
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
  val rows=fields.flatMapIndexed{index,key->r.book.optJSONArray(key)?.objects().orEmpty().map{index to it}}.filter{(kind,item)->(filter==0||kind==filter-1)&&("${item.str("text")} ${fragment(kind,item)} ${r.chapterName(item.optInt("ch"))}").contains(query,true)}.sortedByDescending{it.second.optLong("updatedAt",it.second.optLong("at"))}
  if(rows.isEmpty()){
   // a quiet empty state: an icon, a short title and one hint
   val box=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(24),a.d(44),a.d(24),a.d(24))}
   val icon=listOf("quotes","quotes","pen","bookmark")[filter.coerceIn(0,3)]
   box.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(quiet())};addView(IconView(a,if(query.isNotBlank())"search" else icon,a.muted),FrameLayout.LayoutParams(a.d(30),a.d(30),Gravity.CENTER))},LinearLayout.LayoutParams(a.d(72),a.d(72)))
   val title=if(query.isNotBlank())"Ничего не нашлось" else listOf("Пока нет записей","Пока нет цитат","Пока нет заметок","Пока нет закладок")[filter.coerceIn(0,3)]
   box.addView(a.text(title,22f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18)})
   val hint=if(query.isNotBlank())"Попробуйте другое слово." else if(filter==3)"Добавьте закладку кнопкой в нижней панели — она появится здесь." else "Выделите текст в книге и нажмите «Цитата» или «Заметка» — запись появится здесь."
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
   val card=a.column().apply{background=a.controlSurface(this,18f);setPadding(a.d(16),a.d(16),a.d(16),a.d(16));contentDescription="${listOf("Цитата","Заметка","Закладка")[kind]}: ${item.str("text")}"}
   val whole=kind==1&&item.str("scope")=="book"
   val meta=a.line();meta.addView(a.text(if(whole)"О книге" else listOf("Цитата","Заметка","Закладка")[kind],12f,a.muted),LinearLayout.LayoutParams(0,-2,1f));meta.addView(a.text(java.text.SimpleDateFormat("d MMMM yyyy 'г.'",java.util.Locale.forLanguageTag("ru")).format(java.util.Date(item.optLong("at",System.currentTimeMillis()))),12f,a.muted));card.addView(meta)
   val fragment=fragment(kind,item)
   if(fragment.isNotBlank()){a.space(card,12);card.addView(a.text(fragment,16f,a.muted,serif=true).apply{cssLeading(1.55f);setPadding(a.d(12),0,0,0);background=object:android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT){override fun draw(c:android.graphics.Canvas){super.draw(c);c.drawRect(0f,0f,a.d(2).toFloat(),bounds.height().toFloat(),android.graphics.Paint().apply{color=a.accent})}}})}
   if(kind==1){a.space(card,12);card.addView(a.text(item.str("text"),16f))}
   if(kind==0&&filter==1)r.book.optJSONArray("notes")?.objects().orEmpty().filter{it.str("quoteId")==item.str("id")}.forEach{note->
    a.space(card,12);val thought=a.column().apply{background=a.controlSurface(this,12f);setPadding(a.d(12),a.d(12),a.d(12),a.d(12));setOnClickListener{a.editThought(r.book,note,quote=item)}}
    thought.addView(a.text("Моя мысль",12f,a.accent));a.space(thought,5);thought.addView(a.text(note.str("text"),15f).apply{cssLeading(1.5f)});card.addView(thought)
   }
   a.space(card,12);card.addView(a.text(if(whole)"Впечатления о книге" else r.chapterName(item.optInt("ch")),12f,a.muted));a.space(card,8)
   a.space(card,4);card.addView(View(a).apply{setBackgroundColor(if(a.light)0x18000000 else 0x17ffffff)},LinearLayout.LayoutParams(-1,a.d(1)));a.space(card,6)
   val actions=a.line()
   actions.addView(a.text(if(whole)"О всей книге" else "К фрагменту",13f,if(whole)a.muted else a.accent).apply{minHeight=a.d(44);gravity=Gravity.CENTER_VERTICAL;if(!whole)setOnClickListener{close();r.goToLocation(item.optInt("ch"),item.optInt("start",item.optInt("nativeOffset",0))) }},LinearLayout.LayoutParams(0,-2,1f))
   fun icon(kind:String,label:String,fn:()->Unit){actions.addView(IconView(a,kind,a.fg).apply{contentDescription=label;setOnClickListener{fn()}},LinearLayout.LayoutParams(a.d(44),a.d(44)))}
   if(kind!=2)icon("pen",if(kind==1)"Изменить мысль" else "Добавить мысль"){if(kind==1)a.editThought(r.book,item,item.str("scope","chapter"),ch=item.optInt("ch"))else a.editThought(r.book,quote=item,ch=item.optInt("ch"))}
   icon("copy","Копировать запись"){
    val text=if(kind==1)(if(fragment.isBlank())"" else "«$fragment»\n\n")+item.str("text")else "«${item.str("text") }»\n— ${MainActivity.authors(r.book)}, «${r.book.str("title") }»"
    (a.getSystemService(Context.CLIPBOARD_SERVICE)as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("Блокнот",text))
   }
   icon("trash","Удалить ${listOf("цитату","мысль","закладку")[kind]}"){
    a.confirm("Удалить ${listOf("цитату","мысль","закладку")[kind]}?",if(kind==0)"Мысли к этому фрагменту останутся в блокноте." else "Удалить выбранную запись?"){
     val field=fields[kind];r.book.put(field,JSONArray(r.book.optJSONArray(field)?.objects().orEmpty().filter{it.str("id")!=item.str("id")}));r.persist();if(kind==0)r.paginate();drawNotebook();r.refreshBookmark()
    }
   }
   card.addView(actions);notebookResults.addView(card);a.space(notebookResults,12)
  }
  fadeResults(animate)
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
