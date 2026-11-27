package app.polka.nativeapp
import android.content.*
import android.graphics.*
import android.view.*
import android.widget.*
import org.json.*

private data class QuoteGroup(val book:JSONObject,val quotes:List<JSONObject>)
private fun MainActivity.quoteGroups():List<QuoteGroup> {
    val all=books.flatMap{b->b.optJSONArray("quotes")?.objects().orEmpty().filter{query.isBlank() || (it.str("text")+b.str("title")+MainActivity.authors(b)).contains(query,true)}.map{b to it}}.sortedByDescending{it.second.optLong("at")}
    return all.groupBy{it.first.str("id")}.values.map{items->QuoteGroup(items.first().first,items.map{it.second})}
}
fun MainActivity.quotes() {
    val groups=quoteGroups()
    if(groups.isEmpty()){space(page,96);page.addView(if(query.isNotBlank())emptyView("search","Ничего не нашлось","Поиск идёт по тексту цитат и по книгам.") else emptyView("quote","Пока нет цитат","Выделите фрагмент в книге и нажмите «Цитата»."),LinearLayout.LayoutParams(-1,-2));return}
    space(page,8)
    groups.forEach{g->page.addView(QuoteDeck(this,g.book,g.quotes).apply{tag="quote-item"},LinearLayout.LayoutParams(-1,-2));space(page,30)}
}
private fun quoteWord(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
internal fun MainActivity.floatCard(v:View,radiusDp:Int=24,color:Int=if(light)Color.WHITE else 0xff242426.toInt(),lift:Int=if(light)8 else 10){
    // the card floats on the drawn shade (see [FloatShade]); a lower card in a pile casts a weaker one. The elevation only
    // keeps the order of a pile; Android's own shadow is off, it would add a second, harder one.
    val r=d(radiusDp).toFloat();v.background=Floating(this,android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=r},r,lift/(if(light)8f else 10f));v.elevation=d(lift).toFloat()
    v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,r)}}
    if(android.os.Build.VERSION.SDK_INT>=28){v.outlineSpotShadowColor=Color.TRANSPARENT;v.outlineAmbientShadowColor=Color.TRANSPARENT}
    // faded without an offscreen layer: a layer is cut to the card, and so would be its shade
    v.forceHasOverlappingRendering(false)
}
/** A real pile: one or two narrower sheets peek out under the front card, each with its own shadow. */
internal class QuoteStackLayer(val a:MainActivity,val count:Int):FrameLayout(a){
    private val layers=(count-1).coerceIn(0,2)
    private val sheets=List(layers){i->View(a).apply{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;a.floatCard(this,24,if(a.light)(if(i==0)0xfff7f7f5.toInt() else 0xffefefec.toInt()) else (if(i==0)0xff202022.toInt() else 0xff1c1c1e.toInt()),if(a.light)5-i*2 else 7-i*2)}}
    private val step get()=a.d(9)
    init{clipChildren=false;clipToPadding=false;sheets.asReversed().forEach{addView(it)}}
    private fun front():View?=if(childCount>layers)getChildAt(childCount-1) else null
    override fun onMeasure(ws:Int,hs:Int){
        val w=MeasureSpec.getSize(ws);val f=front();f?.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));val fh=f?.measuredHeight?:0
        sheets.forEachIndexed{i,v->val inset=a.d(12)*(i+1);v.measure(MeasureSpec.makeMeasureSpec((w-inset*2).coerceAtLeast(0),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(fh,MeasureSpec.EXACTLY))}
        setMeasuredDimension(w,fh+layers*step)
    }
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){
        val w=r-l;val f=front();val fh=f?.measuredHeight?:0;f?.layout(0,0,w,fh)
        sheets.forEachIndexed{i,v->val inset=a.d(12)*(i+1);val off=step*(i+1);v.layout(inset,off,w-inset,off+fh)}
    }
}
private fun MainActivity.quoteMark()=text("\u201c",38f,accent,true,true).apply{includeFontPadding=false;gravity=Gravity.TOP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
internal fun MainActivity.quoteStack(b:JSONObject,qs:List<JSONObject>,onOpen:(()->Unit)?=null):View {
    val stack=QuoteStackLayer(this,qs.size);val card=column().apply{floatCard(this,24);setPadding(d(16),d(16),d(16),d(18))}
    stack.addView(card,FrameLayout.LayoutParams(-1,-2));stack.contentDescription="${b.str("title")}: ${qs.size} ${quoteWord(qs.size)}";stack.isFocusable=true;stack.setOnClickListener{onOpen?.invoke()};press(stack)
    val top=line();top.addView(CoverView(this,b),LinearLayout.LayoutParams(d(36),d(54)))
    val meta=column().apply{setPadding(d(12),0,d(12),0)};meta.addView(text(b.str("title"),15f,bold=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});space(meta,2);meta.addView(text(MainActivity.authors(b).ifBlank{"Автор не указан"},12.5f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});top.addView(meta,LinearLayout.LayoutParams(0,-2,1f))
    val badge=line().apply{background=shape((accent and 0x00ffffff) or (if(light)0x1f000000 else 0x29000000),14);setPadding(d(11),0,d(6),0)}
    badge.addView(text("${qs.size}",14f,accent,true));badge.addView(IconView(this,"next",accent),LinearLayout.LayoutParams(d(16),d(16)).apply{leftMargin=d(2)});top.addView(badge,LinearLayout.LayoutParams(-2,d(28)))
    card.addView(top);space(card,14)
    val excerpt=line().apply{gravity=Gravity.TOP};excerpt.addView(quoteMark(),LinearLayout.LayoutParams(d(22),d(26)))
    excerpt.addView(text(qs.first().str("text"),16f,(fg and 0x00ffffff) or 0xd9000000.toInt(),serif=true).apply{cssLeading(1.5f);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(4)})
    card.addView(excerpt);return stack
}
/** The quote's own colour (as chosen when it was saved). */
internal fun quoteInk(q:JSONObject)=(0xff shl 24) or QuoteColors.rgb(q.optString("color").ifBlank{"amber"})
/** Copy / delete drawn as line icons (two sheets with rounded corners; a bin with a lid and slots). */
internal class QuoteActionIcon(c:Context,private val kind:String,private val ink:Int):View(c){
    override fun onDraw(cv:Canvas){val s=minOf(width,height)/24f*.84f;cv.save();cv.translate(width/2f-12f*s,height/2f-12f*s);cv.scale(s,s)
        IosIcons.draw(cv,kind,ink);cv.restore()}
}
/** [onDelete] is the notebook's own removal (with a question); a tap on the quote opens its place in the book. */
internal fun MainActivity.quoteCardNative(b:JSONObject,q:JSONObject,onOpen:(()->Unit)?=null,onDelete:(()->Unit)?=null):View {
    // approved «кавычка над чертой»: a grey card like everywhere else; the quote's colour is only its mark and the line in the margin
    val col=quoteInk(q);val quiet=if(light)0x0d000000 else 0x12ffffff
    val card=column().apply{floatCard(this,24);setPadding(d(18),d(14),d(14),d(10))}
    val open={if(onOpen!=null)onOpen()else openBookAt(b,q.optInt("ch"),q.optInt("start"))}
    // the quote mark above, in the quote's colour; the text with a line of the same colour in its margin
    card.addView(text("\u201c",40f,col,true,true).apply{includeFontPadding=false;gravity=Gravity.TOP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(30),d(26)).apply{topMargin=d(4)})
    val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale("ru")).format(java.util.Date(q.optLong("at",System.currentTimeMillis())))
    val body=line().apply{gravity=Gravity.TOP}
    body.addView(View(this).apply{background=shape(col,2);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(3),-1).apply{rightMargin=d(14)})
    body.addView(text(q.str("text"),17f,serif=true).apply{cssLeading(1.55f);maxLines=5;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,0,d(4),0);setOnClickListener{open()}},LinearLayout.LayoutParams(0,-2,1f))
    card.addView(body,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(6)})
    // the reader's own note: plain text right under the fragment, no box and no label
    q.str("note").trim().takeIf{it.isNotBlank()}?.let{n->card.addView(text(n,16f).apply{cssLeading(1.5f);setOnClickListener{editQuoteNote(b,q)}},LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,d(14),d(4),0)})}
    // its photos: a row of small squares under the words; a tap shows them large
    NotePhotos.of(q).takeIf{it.isNotEmpty()}?.let{ids->val row=line();val size=d(64)
        ids.forEachIndexed{i,id->val img=NotePhotos.tile(this,size,d(10)).apply{isClickable=true;contentDescription="Фото ${i+1}";setOnClickListener{NotePhotos.show(this@quoteCardNative,ids,i)}}
            NotePhotos.load(this,id,size){bmp->img.setImageBitmap(bmp)};row.addView(img,LinearLayout.LayoutParams(size,size).apply{if(i>0)marginStart=d(8)})}
        card.addView(HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(row)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(12)})}
    card.addView(View(this).apply{setBackgroundColor(if(light)0x10000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1).apply{topMargin=d(16)})
    val foot=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,d(10),0,d(2))}
    // where the quote is from and when it was saved, quietly
    val chapter=q.str("chTitle").trim().takeIf{it.isNotBlank()&&it.length<=40}?:"Глава ${q.optInt("ch")+1}"
    val where=column();where.addView(text(chapter,12.5f,muted).apply{typeface=PolkaFonts.sans(500);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
    where.addView(text(date,11.5f,if(light)0xff86868b.toInt() else 0xff8a8a8e.toInt()).apply{setPadding(0,d(2),0,0)})
    foot.addView(where,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=d(8)})
    fun round(kind:String,desc:String,act:()->Unit)=FrameLayout(this).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(quiet)}
        isClickable=true;contentDescription=desc;addView(QuoteActionIcon(this@quoteCardNative,kind,fg),FrameLayout.LayoutParams(d(22),d(22),Gravity.CENTER))
        setOnTouchListener{v,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->v.animate().scaleX(.88f).scaleY(.88f).setDuration(90).start();MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()};false}
        setOnClickListener{act()}}
    foot.addView(round("pen",if(q.str("note").isBlank()&&NotePhotos.of(q).isEmpty())"Добавить заметку" else "Изменить заметку"){editQuoteNote(b,q)},LinearLayout.LayoutParams(d(38),d(38)).apply{rightMargin=d(8)})
    foot.addView(round("copy","Копировать цитату"){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата","«${q.str("text")}»\n— ${MainActivity.authors(b)}, «${b.str("title")}»"+q.str("note").trim().let{if(it.isBlank())"" else "\n\n$it"}));Notice.short(this,Notice.Kind.COPIED,"Цитата скопирована")},LinearLayout.LayoutParams(d(38),d(38)))
    foot.addView(round("trash","Удалить цитату"){
        if(onDelete!=null){onDelete();return@round}
        val remove={saveBook(b.copyJson().put("quotes",JSONArray(b.optJSONArray("quotes")!!.objects().filterNot{it.str("id")==q.str("id")})))}
        if(prefs.str("motion")=="reduced")remove() else NativeMotion(card,MotionPose(),MotionPose(0f,scale=.96f),200,curve=android.view.animation.AccelerateInterpolator(),complete=remove).start()
    },LinearLayout.LayoutParams(d(38),d(38)).apply{leftMargin=d(8)})
    card.addView(foot);return card
}

/** Every note belongs to a quote: it is the quote's "note". Older notes kept on their own (with the fragment they were
 *  written about) become quotes carrying that note; the reader finds the fragment in the chapter and marks it. */
internal object QuoteNotes {
    fun fold(b:JSONObject):Boolean{
        val notes=b.optJSONArray("notes")?.objects().orEmpty();if(notes.isEmpty())return false
        val quotes=b.optJSONArray("quotes")?.objects().orEmpty().toMutableList();val keep=mutableListOf<JSONObject>()
        notes.forEach{n->val t=n.str("text").trim();if(t.isBlank())return@forEach;val frag=n.str("quote").trim()
            val q=quotes.firstOrNull{n.str("quoteId").isNotBlank()&&it.str("id")==n.str("quoteId")}
                ?:frag.takeIf{it.isNotBlank()}?.let{f->quotes.firstOrNull{it.optInt("ch")==n.optInt("ch")&&it.str("text").trim()==f}
                    ?:JSONObject().put("id",n.str("id").ifBlank{uid()}).put("ch",n.optInt("ch")).put("start",n.optInt("nativeOffset",0)).put("end",n.optInt("nativeOffset",0)+f.length).put("block",0).put("text",f).put("rawText",f).put("color","amber").put("at",n.optLong("at",System.currentTimeMillis())).also{quotes.add(it)}}
            // a note with no fragment at all has nothing to belong to: it is kept as it was
            if(q==null){keep.add(n);return@forEach}
            val old=q.str("note").trim();q.put("note",if(old.isBlank())t else if(old.contains(t))old else "$old\n\n$t")}
        b.put("quotes",JSONArray(quotes));b.put("notes",JSONArray(keep));return true
    }
}
/** Writes or changes the note of quote [q] in [b]; an emptied note is removed. [prefill] is added for a new note
 *  (a translation or a word's meaning sent «В заметку»). */
internal fun MainActivity.editQuoteNote(b:JSONObject,q:JSONObject,prefill:String="",after:()->Unit={}){
    val was=q.str("note").trim()
    noteSheet(q.str("text"),quoteInk(q),listOf(was,prefill.trim()).filter{it.isNotBlank()}.joinToString("\n\n"),was,NotePhotos.of(q)){v,photos->
        // new photos are kept in the library first (like a cover), then the quote points to them
        val ids=ArrayList<String>()
        work({photos.forEach{p->ids.add(p.id?:uid().also{id->library.dao.putAsset(AssetRow(id,"image/jpeg",p.bytes!!))})}}){
            fun apply(o:JSONObject){if(v.isBlank())o.remove("note") else o.put("note",v);NotePhotos.put(o,ids);o.put("updatedAt",System.currentTimeMillis())}
            b.optJSONArray("quotes")?.objects()?.firstOrNull{it.str("id")==q.str("id")}?.let{apply(it)};apply(q)
            saveBook(b){after()}}}
}
/** The note sheet: the fragment it is about on top (with the quote's line), a roomy field under it with the note's
 *  photos in a row at its foot («Фото» at the end), then «Готово». */
internal fun MainActivity.noteSheet(fragment:String,ink:Int,value:String,saved:String,savedPhotos:List<String>,done:(String,List<NotePhotos.Photo>)->Unit){
    val box=column()
    val about=line().apply{gravity=Gravity.TOP}
    about.addView(View(this).apply{background=shape(ink,2);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(3),-1).apply{rightMargin=d(14)})
    about.addView(text(fragment,16f,serif=true).apply{cssLeading(1.5f);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
    box.addView(about,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=d(2)});space(box,16)
    val fieldBg=if(light)0xffffffff.toInt() else 0xff2a2a2d.toInt()
    val rest=if(light)0x1f000000 else 0x1fffffff;val focus=(fg and 0xffffff) or 0x66000000
    val frame=FrameLayout(this).apply{background=android.graphics.drawable.GradientDrawable().apply{setColor(fieldBg);cornerRadius=d(16).toFloat();setStroke(d(1),rest)}}
    val input=EditText(this).apply{
        typeface=PolkaFonts.sans(400);setText(value);hint="Ваша заметка";setTextColor(fg);setHintTextColor(muted);textSize=17f;background=null;isSingleLine=false;minLines=3;maxLines=7;gravity=Gravity.TOP or Gravity.START
        imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        setLineSpacing(0f,1.2f);setPadding(d(16),d(14),d(16),d(14))
        if(android.os.Build.VERSION.SDK_INT>=29){textCursorDrawable=android.graphics.drawable.GradientDrawable().apply{setColor(fg);setSize(d(2),d(22))};highlightColor=(fg and 0xffffff) or 0x33000000}
        setSelection(text.length)}
    val inside=column();inside.addView(input,LinearLayout.LayoutParams(-1,-2))
    // the photos: small squares with a cross, «Фото» last
    val photos=savedPhotos.map{NotePhotos.Photo(it,null)}.toMutableList()
    val strip=line().apply{clipChildren=false;clipToPadding=false}
    val stripScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;setPadding(d(16),0,d(16),d(14));addView(strip)}
    inside.addView(stripScroll,LinearLayout.LayoutParams(-1,-2))
    frame.addView(inside,FrameLayout.LayoutParams(-1,-2))
    box.addView(frame,LinearLayout.LayoutParams(-1,-2))
    lateinit var paintReady:()->Unit
    fun fillStrip(){strip.removeAllViews();val size=d(72)
        photos.forEachIndexed{i,p->val cell=FrameLayout(this)
            val img=NotePhotos.tile(this,size,d(12));cell.addView(img,FrameLayout.LayoutParams(size,size))
            if(p.id!=null)NotePhotos.load(this,p.id,size){bmp->img.setImageBitmap(bmp)} else img.setImageBitmap(NotePhotos.preview(p,size))
            img.setOnClickListener{p.id?.let{NotePhotos.show(this,photos.mapNotNull{it.id},photos.filter{it.id!=null}.indexOf(p))}}
            cell.addView(FrameLayout(this).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(0x8c000000.toInt())};isClickable=true;contentDescription="Убрать фото"
                addView(IconView(this@noteSheet,"x",Color.WHITE),FrameLayout.LayoutParams(d(11),d(11),Gravity.CENTER))
                setOnClickListener{photos.remove(p);fillStrip();paintReady()}},FrameLayout.LayoutParams(d(22),d(22),Gravity.TOP or Gravity.END).apply{setMargins(0,d(5),d(5),0)})
            strip.addView(cell,LinearLayout.LayoutParams(size,size).apply{marginEnd=d(8)})}
        if(photos.size<NotePhotos.MAX){val add=column().apply{gravity=Gravity.CENTER;isClickable=true;contentDescription="Добавить фото"
                background=android.graphics.drawable.GradientDrawable().apply{cornerRadius=d(12).toFloat();setStroke(d(1)+d(1)/2,if(light)0x2e000000 else 0x2effffff)};press(this)
                addView(IconView(this@noteSheet,"camera",muted),LinearLayout.LayoutParams(d(20),d(20)))
                addView(text("Фото",12f,muted).apply{gravity=Gravity.CENTER;setPadding(0,d(4),0,0)})
                setOnClickListener{NotePhotos.pick(this@noteSheet,NotePhotos.MAX-photos.size){got->got.take(NotePhotos.MAX-photos.size).forEach{photos.add(NotePhotos.Photo(null,it))};fillStrip();paintReady()
                    stripScroll.post{stripScroll.fullScroll(View.FOCUS_RIGHT)}}}}
            strip.addView(add,LinearLayout.LayoutParams(size,size))}}
    var stroke:android.animation.ValueAnimator?=null
    input.setOnFocusChangeListener{_,has->stroke?.cancel();val bg=frame.background as android.graphics.drawable.GradientDrawable
        stroke=android.animation.ValueAnimator.ofArgb(if(has)rest else focus,if(has)focus else rest).apply{duration=180;addUpdateListener{bg.setStroke(d(1),it.animatedValue as Int)};start()}}
    space(box,16)
    var sheet:android.app.AlertDialog?=null
    fun ready()=input.text.toString().trim()!=saved.trim()||photos.any{it.id==null}||photos.mapNotNull{it.id}!=savedPhotos
    val go=button("Готово",true){if(ready()){val v=input.text.toString().trim();sheet?.dismiss();done(v,photos.toList())}}
    fun paint(){val on=ready();go.isEnabled=on;go.animate().alpha(if(on)1f else .38f).setDuration(140).start()}
    paintReady={paint()};fillStrip()
    go.alpha=if(ready())1f else .38f;go.isEnabled=ready()
    box.addView(go,LinearLayout.LayoutParams(-1,d(52)))
    input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun afterTextChanged(s:android.text.Editable?){paint()}})
    sheet=dialog().setTitle("Заметка").setView(box).show()
    input.requestFocus()
    input.post{if(input.isAttachedToWindow){input.requestFocus();(getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}}
}
