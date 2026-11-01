package app.polka.nativeapp

import android.content.Context
import android.graphics.Typeface
import android.text.*
import android.text.style.*
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.*

/** Inline page editing: the same reader, ScrollView, typography and location. No dialog. */
class ChapterEditor(private val a:MainActivity,private val reader:NativeReader,private val book:JSONObject,private val content:JSONObject,private val draft:NativeReader.PageDraft) {
 val editor=EditText(a)
 private val toolbar=LinearLayout(a)
 private val error=TextView(a)
 private val undo=java.util.ArrayDeque<SpannableStringBuilder>()
 private val redo=java.util.ArrayDeque<SpannableStringBuilder>()
 private var recording=true
 var saving=false;private set
 private var closed=false
 private val chapter=draft.chapter
 private val sourceHtml=content.getJSONArray("chapters").getJSONObject(chapter).str("html")
 internal var commit:(JSONObject,JSONObject)->Unit={updated,next->ChapterStore.drop(a.filesDir,book.str("id"));PageImages.drop(a,book.str("id"));a.library.db.runInTransaction { a.library.dao.putContent(ContentRow(book.str("id"),updated.toString()));a.library.save(next) }}
 private fun snapshot()=SpannableStringBuilder(editor.text)
 private fun remember(){if(undo.size>=60)undo.removeFirst();undo.addLast(snapshot());redo.clear()}
 fun dirty()=editor.text.toString()!=draft.text.toString() || editor.text.getSpans(0,editor.length(),PageFormat::class.java).isNotEmpty()
 @android.annotation.SuppressLint("WrongConstant") // API-23 Layout constants alias LineBreaker values on API29+.
 fun show(){
  val body=reader.body;val ink=body.currentTextColor;val pageBg=reader.backgroundColor
  val lightPage=android.graphics.Color.luminance(pageBg)>.5f
  // The editor is a twin of the page: same metrics, paddings, spacing, hyphenation and justification,
  // so entering edit mode leaves every word exactly where it was.
  editor.apply{
   background=null;defaultFocusHighlightEnabled=false;setTextColor(ink);setHintTextColor(a.muted);highlightColor=(a.accent and 0x00ffffff) or 0x55000000
   inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
   imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
   setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,body.textSize);typeface=body.typeface;letterSpacing=body.letterSpacing;textLocale=body.textLocale
   setLineSpacing(0f,1f);includeFontPadding=body.includeFontPadding;gravity=Gravity.TOP;textDirection=body.textDirection
   setPadding(body.paddingLeft,body.paddingTop,body.paddingRight,body.paddingBottom);setHorizontallyScrolling(false);minHeight=0;minimumHeight=0
   breakStrategy=body.breakStrategy;hyphenationFrequency=body.hyphenationFrequency;justificationMode=body.justificationMode
   if(android.os.Build.VERSION.SDK_INT>=29)paint.wordSpacing=body.paint.wordSpacing
   setText(draft.text);contentDescription="Текст текущей страницы"
  }
  // Quote highlights are read-only decoration; links keep their colour but stop being clickable.
  editor.text.getSpans(0,editor.length(),BackgroundColorSpan::class.java).forEach{editor.text.removeSpan(it)}
  editor.text.getSpans(0,editor.length(),ClickableSpan::class.java).forEach{span->val from=editor.text.getSpanStart(span);val to=editor.text.getSpanEnd(span);editor.text.removeSpan(span);if(to>from)editor.text.setSpan(ForegroundColorSpan(a.accent),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
  toolbar.orientation=LinearLayout.VERTICAL;toolbar.setPadding(a.d(10),a.d(8),a.d(10),a.d(10))
  a.controlSurface(toolbar,26f,sheet=true).apply{themeLight=lightPage;refresh()}
  toolbar.elevation=a.d(12).toFloat();toolbar.isClickable=true
  val actions=a.line()
  fun action(label:String,description:String,icon:String?=null,fn:()->Unit){val control=if(icon!=null)IconView(a,icon,ink)else a.text(label,18f,ink,true,serif=true).apply{gravity=Gravity.CENTER;if(label=="К")typeface=Typeface.create(typeface,Typeface.ITALIC)};a.press(control);control.contentDescription=description;control.setOnClickListener{if(!saving)fn()};actions.addView(control,LinearLayout.LayoutParams(0,a.d(44),1f))}
  action("","Отменить действие","undo"){undo()};action("","Повторить действие","redo"){redo()}
  actions.addView(View(a).apply{setBackgroundColor((ink and 0x00ffffff) or 0x22000000)},LinearLayout.LayoutParams(1,a.d(22)))
  action("Ж","Полужирный"){format("bold")};action("К","Курсив"){format("italic")};action("","Заголовок","head"){format("heading")}
  toolbar.addView(actions)
  error.textSize=12f;error.setTextColor(if(lightPage)0xffa52820.toInt()else 0xffff8a75.toInt());error.visibility=View.GONE;error.setPadding(a.d(6),a.d(4),a.d(6),0);toolbar.addView(error)
  val buttons=a.line().apply{setPadding(0,a.d(8),0,0)}
  fun pill(label:String,filled:Boolean,description:String,fn:()->Unit)=a.text(label,15f,if(filled)pageBg else ink,filled).apply{gravity=Gravity.CENTER;background=a.shape(if(filled)ink else (ink and 0x00ffffff) or 0x1a000000,22);contentDescription=description;a.press(this);setOnClickListener{fn()}}
  buttons.addView(pill("Отмена",false,"Отменить правки страницы"){cancel()},LinearLayout.LayoutParams(0,a.d(44),1f).apply{rightMargin=a.d(5)})
  buttons.addView(pill("Сохранить",true,"Сохранить страницу"){save()},LinearLayout.LayoutParams(0,a.d(44),1f).apply{leftMargin=a.d(5)})
  toolbar.addView(buttons)
  reader.mountEditor(editor,toolbar)
  editor.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){if(recording)remember()};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){};override fun afterTextChanged(s:Editable?){error.visibility=View.GONE}})
  // No caret jump and no keyboard yet: the reader taps the word that needs fixing.
  editor.isFocusableInTouchMode=true
 }
 private fun restore(value:SpannableStringBuilder){recording=false;editor.setText(value);editor.setSelection(minOf(value.length,editor.length()));recording=true}
 fun undo(){if(undo.isNotEmpty()&&!saving){redo.addLast(snapshot());restore(undo.removeLast())}}
 fun redo(){if(redo.isNotEmpty()&&!saving){undo.addLast(snapshot());restore(redo.removeLast())}}
 fun format(kind:String){var from=minOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(0);var to=maxOf(editor.selectionStart,editor.selectionEnd).coerceAtLeast(0);if(kind=="heading"||kind=="quote"){from=editor.text.lastIndexOf('\n',maxOf(0,from-1))+1;to=editor.text.indexOf('\n',to).takeIf{it>=0}?:editor.length()};if(to<=from)return;remember();editor.text.setSpan(PageFormat(kind),from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);val style:Any=when(kind){"bold"->StyleSpan(Typeface.BOLD);"italic"->StyleSpan(Typeface.ITALIC);"heading"->RelativeSizeSpan(1.2f);else->QuoteSpan(a.accent)};editor.text.setSpan(style,from,to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
 fun save(){if(saving||closed)return;if(!dirty()){finish(false);return}
  reader.ensureAllHtml()
  saving=true;editor.isEnabled=false;error.visibility=View.GONE;val edited=snapshot();val formats=edited.getSpans(0,edited.length,PageFormat::class.java).map{PageHtmlPatch.Format(draft.start+edited.getSpanStart(it),draft.start+edited.getSpanEnd(it),it.kind)};val updated=content.copyJson();val next=book.copyJson()
  a.work({
   check(content.getJSONArray("chapters").getJSONObject(chapter).str("html")==sourceHtml){"Текст изменился. Правки остались в редакторе."}
   var html=PageHtmlPatch.apply(sourceHtml,draft.rich,draft.start,draft.end,edited.toString())
   fun render()=ReadingText(a,reader.body.textSize,(reader.scroll.width-reader.body.paddingLeft-reader.body.paddingRight).coerceAtLeast(1),reader.scroll.height.coerceAtLeast(1),draft.images,reader.body.currentTextColor,reader.lineHeight()).render(html)
   var rendered=render();if(formats.isNotEmpty()){html=PageHtmlPatch.format(html,rendered.text,formats);rendered=render()}
   val ch=updated.getJSONArray("chapters").getJSONObject(chapter);ch.put("html",html).put("len",rendered.text.length).put("edited",true)
   val anchors=JSONObject();val doc=org.jsoup.Jsoup.parse(html);rendered.anchors.keys.forEach{id->anchors.put(id,doc.getElementById(id)?.text()?:"")};ch.put("anchors",anchors)
   next.put("chars",updated.getJSONArray("chapters").objects().sumOf{it.optInt("len")}).put("edits",next.optInt("edits")+1).put("modifiedAt",System.currentTimeMillis())
   next.optJSONArray("quotes")?.objects().orEmpty().filter{it.optInt("ch")==chapter}.forEach{q->val range=TextAnchors.locate(rendered.text.toString(),q.str("rawText",q.str("text")),q.optInt("start"));q.put("start",range?.first?:-1).put("end",range?.let{it.last+1}?:-1)}
   val delta=rendered.text.length-draft.rich.length;for(key in listOf("bookmarks","notes"))next.optJSONArray(key)?.objects().orEmpty().filter{it.optInt("ch")==chapter&&it.has("nativeOffset")}.forEach{mark->val at=mark.optInt("nativeOffset");if(at>=draft.end)mark.put("nativeOffset",at+delta)}
   commit(updated,next)
  },failed={e->saving=false;editor.isEnabled=true;error.text="Не сохранено. Правки остались здесь: ${e.message?:"ошибка хранилища"}";error.visibility=View.VISIBLE},done={
   if(!closed){val original=content.getJSONArray("chapters").getJSONObject(chapter);val fresh=updated.getJSONArray("chapters").getJSONObject(chapter);fresh.keys().forEach{original.put(it,fresh.get(it))};next.keys().forEach{book.put(it,next.get(it))};saving=false;finish(true);a.checkpointSoon()}
  })
 }
 fun cancel(){if(saving||closed)return;if(dirty())a.confirm("Отменить правки?","Изменения текущей страницы не сохранятся."){finish(false)}else finish(false)}
 private fun finish(changed:Boolean){if(closed)return;closed=true;(a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(editor.windowToken,0);editor.clearFocus();reader.unmountEditor(editor,toolbar,draft,changed)}
 fun detach(){closed=true}
}
