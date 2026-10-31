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
  tabs.addView(a.segment(listOf("Оглавление","Блокнот","Поиск"),selected){switchTo(it)})
  fill();panel=a.dialog().setTitle("${r.book.str("title")}").setView(box).setTall(true).show()
  a.observeBook(box,r.book.str("id")){fresh->replaceJson(r.book,fresh);if(selected==1)drawNotebook()}
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
  toc.forEach{item->val current=item.optInt("ch")==r.chapter;pane.addView(a.text(item.str("title"),16f,if(current)a.accent else a.fg,current).apply{minHeight=a.d(48);gravity=Gravity.CENTER_VERTICAL;if(current)background=a.shape((a.accent and 0xffffff) or (0x1f shl 24),14);setPadding(a.d(12+item.optInt("depth").coerceIn(0,8)*12),a.d(10),a.d(8),a.d(10));setOnClickListener{close();r.goToLocation(item.optInt("ch"),0,item.str("anchor"))}})}
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
   if(!animate||reduced()){shape.setColor(bg);chip.setTextColor(ink);continue}
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
   notebookResults.addView(a.text(if(query.isNotBlank())"Ничего не найдено" else if(counts.sum()==0)"Ваш книжный блокнот" else "Здесь пока нет записей",22f,bold=true,serif=true).apply{gravity=Gravity.CENTER;setPadding(0,a.d(24),0,a.d(12))})
   notebookResults.addView(a.text(if(query.isNotBlank())"Попробуйте другое слово или выберите «Все»." else "Сохраняйте цитаты, записывайте мысли к выделенному тексту или создайте отдельную заметку. Закладки тоже будут здесь.",15f,a.muted));fadeResults(animate);return
  }
  rows.forEach{(kind,item)->
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
  val input=a.edit("","Слово или фраза").apply{contentDescription="Поиск по книге";imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH};val results=a.column();pane.addView(searchField(input));a.space(pane,14);pane.addView(results)
  fun run(){val generation=++searchGeneration;val q=input.text.toString().trim();results.removeAllViews();if(q.length<2){results.addView(a.text("Поиск идёт по всему тексту книги. Введите хотя бы две буквы.",15f,a.muted));return};results.addView(a.text("Поиск…",15f,a.muted));r.searchEntries(q){rows->if(generation==searchGeneration&&selected==2&&box.isAttachedToWindow){results.removeAllViews();results.addView(a.text("Найдено: ${rows.size}",13f,a.muted));rows.forEach{row->results.addView(a.text(row.third,15f).apply{setPadding(0,a.d(12),0,a.d(12));setOnClickListener{close();r.goToLocation(row.first,row.second)}})}}}}
  input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){input.removeCallbacks(searchTask);searchTask=Runnable{run()};input.postDelayed(searchTask,250)};override fun afterTextChanged(s:Editable?){} });run()
 }
 private fun searchField(input:EditText):View{
  val field=FrameLayout(a);input.setPadding(a.d(38),0,a.d(12),0);input.textSize=16f;a.controlSurface(input,11f);field.addView(input,FrameLayout.LayoutParams(-1,a.d(38)));field.addView(IconView(a,"search",a.muted),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER_VERTICAL).apply{leftMargin=a.d(12)});return field
 }
 private var searchTask=Runnable{}
}
