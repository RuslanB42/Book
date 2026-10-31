package app.polka.nativeapp

import android.app.AlertDialog
import android.content.*
import android.view.*
import android.widget.*
import org.json.*
import java.io.File

fun MainActivity.detail(book:JSONObject,source:View?=null) { BookDetailSheet(this,book,source).show() }
fun MainActivity.ratingStars(book:JSONObject,onChange:(Int)->Unit):View {
    return NativeRating(this,book.optInt("rating"),true,onChange)

}
fun MainActivity.editThought(book:JSONObject,note:JSONObject?=null,scope:String="chapter",quote:JSONObject?=null,ch:Int=0) {
    val input=edit(note?.str("text").orEmpty(),"Ваши мысли",true)
    dialog().setTitle(if(scope=="book") "Мысли о книге" else "Заметка").setView(input).setNegativeButton("Отмена",null).setPositiveButton("Сохранить") { _,_ ->
        val list=book.optJSONArray("notes")?.objects().orEmpty().filterNot { note!=null && it.str("id")==note.str("id") }.toMutableList()
        if(input.text.isNotBlank()) list.add((note?.copyJson() ?: JSONObject().put("id",uid()).put("at",System.currentTimeMillis())).put("text",input.text.toString()).put("scope",scope).put("ch",ch).apply { quote?.let { put("quoteId",it.str("id")); put("quote",it.str("text")) } })
        book.put("notes",JSONArray(list)); saveBook(book) {}
    }.show()
}
/** «Данные книги»: a live cover preview on top, grouped borderless fields on floating cards, one primary action. */
fun MainActivity.metadataEditor(book:JSONObject) {
    val box=column().apply{clipChildren=false;clipToPadding=false;setPadding(0,d(4),0,d(8))}; val fields=linkedMapOf<String,EditText>()
    // The preview is a copy: typing changes the typeset cover instantly, nothing is stored until «Сохранить».
    val preview=book.copyJson()
    val coverBox=FrameLayout(this).apply{clipChildren=false;clipToPadding=false}
    val c=CoverView(this,preview,cover(book)).apply{softShadow=true}; coverEditorBook=preview; coverEditorView=c
    coverBox.addView(c,FrameLayout.LayoutParams(d(104),d(156),Gravity.CENTER_HORIZONTAL))
    box.addView(coverBox,LinearLayout.LayoutParams(-1,d(168))); space(box,10)
    val coverActions=line().apply{gravity=Gravity.CENTER}
    fun chip(label:String,fn:()->Unit)=text(label,14f,fg,true).apply{gravity=Gravity.CENTER;minHeight=d(38);setPadding(d(16),0,d(16),0);background=shape(if(light)0xffefefec.toInt() else 0xff2c2c2f.toInt(),19);press(this);setOnClickListener{fn()}}
    val pick=chip("Сменить обложку"){ coverTarget=book.str("id"); startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) },15) }
    lateinit var drop:TextView
    drop=chip("Убрать"){ preview.remove("cover"); preview.remove("coverThumb"); book.remove("cover"); book.remove("coverThumb"); book.put("coverChanged",true); saveBook(book) {}; c.invalidate(); drop.visibility=View.GONE }
    drop.visibility=if(book.optJSONObject("cover")!=null)View.VISIBLE else View.GONE
    coverActions.addView(pick); coverActions.addView(drop,LinearLayout.LayoutParams(-2,-2).apply{leftMargin=d(8)}); box.addView(coverActions)
    fun card()=column().apply{floatCard(this,20);setPadding(d(16),d(4),d(16),d(4))}
    fun divider(parent:LinearLayout){parent.addView(View(this).apply{setBackgroundColor(if(light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1))}
    fun value(key:String)=when(key) { "authors"->MainActivity.authors(book); "genres"->book.optJSONArray(key)?.strings()?.joinToString(", ").orEmpty(); "seriesNo"->book.optDouble(key,0.0).let{if(it<=0)"" else if(it%1==0.0)it.toInt().toString() else it.toString()}; else->book.str(key) }
    fun field(parent:LinearLayout,key:String,name:String,hint:String,multi:Boolean=false,lp:LinearLayout.LayoutParams=LinearLayout.LayoutParams(-1,-2)):EditText{
        val cell=column().apply{setPadding(0,d(10),0,d(8))}
        cell.addView(text(name,12f,muted))
        val input=EditText(this).apply{setText(value(key));setHint(hint);setTextColor(fg);setHintTextColor((muted and 0x00ffffff) or 0x88000000.toInt());textSize=16f;background=null;setPadding(0,d(4),0,d(2));isSingleLine=!multi;if(multi){minLines=3;maxLines=12;gravity=Gravity.TOP};imeOptions=if(multi)android.view.inputmethod.EditorInfo.IME_ACTION_NONE else android.view.inputmethod.EditorInfo.IME_ACTION_NEXT;if(key=="seriesNo")inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL}
        if(key=="title"||key=="authors")input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,d:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,d:Int){};override fun afterTextChanged(s:android.text.Editable?){val v=s?.toString().orEmpty();if(key=="title")preview.put("title",v) else preview.put("authors",JSONArray(v.split(Regex("[,;]")).map{it.trim()}.filter{it.isNotBlank()}));c.invalidate()}})
        cell.addView(input);parent.addView(cell,lp);fields[key]=input;return input
    }
    fun section(title:String){space(box,20);box.addView(text(title.uppercase(),12f,muted,true).apply{letterSpacing=.06f;setPadding(d(4),0,0,0)});space(box,8)}
    section("Книга")
    val main=card();field(main,"title","Название","Как называется книга");divider(main);field(main,"authors","Авторы","Через запятую");box.addView(main)
    section("Серия")
    val serie=card();val row=line().apply{gravity=Gravity.TOP};field(row,"series","Серия","Название серии",lp=LinearLayout.LayoutParams(0,-2,1f));row.addView(View(this).apply{setBackgroundColor(if(light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(1,-1).apply{setMargins(d(12),d(10),d(12),d(10))});field(row,"seriesNo","Номер","—",lp=LinearLayout.LayoutParams(d(72),-2));serie.addView(row);box.addView(serie)
    section("Подробности")
    val more=card();field(more,"shelf","Полка","Без полки");divider(more);field(more,"genres","Жанры","Через запятую");divider(more);field(more,"lang","Язык","ru, en…");box.addView(more)
    section("Описание")
    val about=card();field(about,"annotation","Аннотация","О чём эта книга",true);box.addView(about)
    val error=text("",13f,0xffe5534b.toInt()).apply{visibility=View.GONE;setPadding(d(4),d(10),0,0)}; box.addView(error)
    var sheet:AlertDialog?=null
    space(box,20)
    box.addView(button("Сохранить",true) {
        if(fields.getValue("title").text.isBlank()) { error.text="Укажите название"; error.visibility=View.VISIBLE; fields.getValue("title").requestFocus(); return@button }
        val updated=book.copyJson(); fields.forEach { (k,f) -> val v=f.text.toString().trim(); when(k) { "authors","genres" -> updated.put(k,JSONArray(v.split(Regex("[,;]")).map { it.trim() }.filter { it.isNotBlank() })); "seriesNo" -> updated.put(k,v.replace(',','.').toDoubleOrNull() ?: 0.0); else->updated.put(k,v) } }
        preview.optJSONObject("cover")?.let{ if(it.toString()!=book.optJSONObject("cover")?.toString()) updated.put("cover",it).put("coverChanged",true) }
        updated.put("modifiedAt",System.currentTimeMillis()); saveBook(updated) { sheet?.dismiss() }
    },LinearLayout.LayoutParams(-1,d(52)))
    sheet=dialog().setTitle("Данные книги").setView(box).setTall(true).show()
}
fun MainActivity.bookActions(book:JSONObject) {
    val actions=mutableListOf("О книге","Изменить данные","Переместить на полку","Оценить","Прикрепить файл","Отметить прочитанной")
    if(!book.optBoolean("noFile")) actions.addAll(listOf("Скачать FB2","Скачать EPUB")); actions.add("Удалить книгу")
    dialog().setTitle(book.str("title")).setItemIcons(*(listOf("information","pen","move","star","paperclip","check")+if(!book.optBoolean("noFile"))listOf("download","download","trash")else listOf("trash")).toTypedArray()).setDestructiveItems(actions.lastIndex).setItems(actions.toTypedArray()) { _,i -> when(actions[i]) {
        "О книге"->detail(book); "Изменить данные"->metadataEditor(book); "Переместить на полку"->moveBook(book); "Оценить"->dialog().setTitle("Ваша оценка").setView(ratingStars(book) { saveBook(book.copyJson().put("rating",it)) {} }).show()
        "Прикрепить файл" -> { attachId=book.str("id"); pickBooks(14) }; "Отметить прочитанной" -> saveBook(book.copyJson().put("progress",(book.optJSONObject("progress")?.copyJson() ?: JSONObject()).put("pct",1.0)).put("completedAt",System.currentTimeMillis()))
        "Скачать FB2"->exportBook(book,"fb2"); "Скачать EPUB"->exportBook(book,"epub")
        "Удалить книгу" -> confirm("Удалить книгу?","Текст, цитаты и заметки этой книги будут удалены из библиотеки.") { work({ ArchiveIO.checkpoint(library,File(filesDir,"checkpoints"),"safety"); library.db.runInTransaction { library.dao.deleteBook(book.str("id")); library.dao.deleteContent(book.str("id")); library.pruneAssets(); library.updateMarker() } }) { reload() } }
    } }.show()
}
fun MainActivity.settings() {
    val box=column(); var sheet:AlertDialog?=null
    label("Оформление",box); box.addView(segment(listOf("Тёмное","Светлое","Как в системе"),listOf("dark","light","system").indexOf(prefs.str("ui",prefs.str("theme","dark"))).coerceAtLeast(0)) { i -> prefs.put("ui",listOf("dark","light","system")[i]); savePreferences(); sheet?.dismiss(); render(true,false); if(profileOverlay!=null) refreshProfile(); settings() })
    label("Чтение",box)
    val flameSetting=button("Огонёк"){editStreakThreshold()};box.addView(flameSetting)
    var threshold=10
    work({threshold=library.dao.meta("stats")?.json?.let{JSONObject(it).optJSONObject("data")?.optInt("goal",10)} ?: 10}){flameSetting.text="Огонёк · $threshold мин в день";flameSetting.setOnClickListener{editStreakThreshold{goal->flameSetting.text="Огонёк · $goal мин в день"}}}
    label("Поведение",box)
    box.addView(PolkaSwitch(this).apply { text="Тактильный отклик"; setTextColor(fg); minHeight=d(48); isChecked=prefs.optBoolean("haptics",true); setOnCheckedChangeListener { _,on -> prefs.put("haptics",on); savePreferences() } })
    box.addView(PolkaSwitch(this).apply { text="Уменьшить движение"; setTextColor(fg); minHeight=d(48); isChecked=prefs.str("motion")=="reduced"; setOnCheckedChangeListener { _,on -> prefs.put("motion",if(on) "reduced" else "full"); savePreferences() } })
    label("Хранение и резервные копии",box)
    box.addView(text("Книги и тексты хранятся в локальной базе SQLite на устройстве.",14f,muted)); space(box,16)
    box.addView(button("Создать внешнюю копию .polka",true) { exportBackup() }); space(box,10); box.addView(button("Восстановить из файла") { pickBackup() }); space(box,10); box.addView(button("Локальные точки восстановления") { localRecovery() })
    label("Каталог",box); box.addView(button("Загрузить каталог рекомендаций") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type="*/*"; addCategory(Intent.CATEGORY_OPENABLE) },13) })
    space(box,16); val time=getSharedPreferences("health",0).getLong("exportedAt",0); box.addView(text("Внешняя копия: ${if(time==0L) "ещё не сохранялась" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))}",14f,muted))
    sheet=dialog().setTitle("Настройки").setView(box).show()
}
/** Commit order without rebuilding the grid or moving the scroll position. */
fun MainActivity.saveGridOrder(ordered:List<JSONObject>) {
    val ids=ordered.map{it.str("id")}
    var saved=emptyList<JSONObject>()
    work({ saved=library.saveOrder(ids) },failed={reload()}) {
            saved.forEach { row -> books.firstOrNull{it.str("id")==row.str("id")}?.put("pos",row.optInt("pos")) }
            sort="manual";prefs.put("sort","manual");sortControl?.text=sortName()
            checkpointSoon()
    }
}
fun MainActivity.openBookAt(book:JSONObject,ch:Int,offset:Int) { val p=book.optJSONObject("progress")?.copyJson() ?: JSONObject(); p.put("ch",ch).put("nativeOffset",offset).remove("nativeFraction"); openBook(book.copyJson().put("progress",p)) }
fun MainActivity.exportBook(book:JSONObject,format:String) { pendingExport=book to format; startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type=if(format=="epub") "application/epub+zip" else "application/x-fictionbook+xml"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE,book.str("title").replace(Regex("[/\\\\:*?\"<>|]"),"_")+".$format") },16) }
fun MainActivity.recoveryScreen(error:Throwable) {
    root.removeAllViews(); val box=column().apply { setPadding(d(24),d(40),d(24),d(40)); setBackgroundColor(bg) }; root.addView(box,FrameLayout.LayoutParams(-1,-1)); box.addView(text("Библиотека не загружена",28f,bold=true,serif=true)); space(box,16); box.addView(text("Запись остановлена. Прежние данные и локальные копии не заменяются пустой библиотекой.",16f,muted)); space(box,12); box.addView(text(error.message ?: error.javaClass.simpleName,14f,muted)); space(box,24); box.addView(button("Локальные точки восстановления",true) { localRecovery() }); space(box,10); box.addView(button("Восстановить из .polka") { pickBackup() }); space(box,10); box.addView(button("Попробовать создать внешнюю копию") { exportBackup() })
}

/** Threshold is configured in settings, never advertised as a statistics-card action. */
internal fun MainActivity.editStreakThreshold(saved:(Int)->Unit={}) {
    val values=listOf(5,10,15,30,60)
    dialog().setTitle("Огонёк: минут в день").setItems(values.map{"$it минут"}.toTypedArray()){_,i->updateStatistics{it.put("goal",values[i])};saved(values[i])}.show()
}
