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
fun MainActivity.openQuoteGroup(id:String?) {
    if(quoteBookId==id)return
    screenMotion.stop();val ghost=screenMotion.captureOutgoing(page,id!=null);quoteBookId=id
    page.removeAllViews();quotes();scrollAncestor(page)?.scrollTo(0,0)
    // Arm incoming states BEFORE the first draw; posting alpha=0 flashes the final layout once.
    var headerIndex=0;var cardIndex=0
    for(i in 0 until page.childCount){val child=page.getChildAt(i)
        if(child.tag=="quote-header")screenMotion.play(child,MotionPose(0f,x=d(if(id!=null)8 else -8).toFloat(),y=d(6).toFloat()),MotionSpec.QUOTE_HEADER,55+headerIndex++*30L)
        else if(child.tag=="quote-item")screenMotion.play(child,MotionPose(0f,x=d(if(id!=null)10 else -10).toFloat(),y=d(if(id!=null)16 else 10).toFloat()),MotionSpec.QUOTE_CARD,80+minOf(cardIndex++,7)*32L)
    };ghost()

}
fun MainActivity.quotes() {
    val groups=quoteGroups()
    if(groups.isEmpty()){quoteBookId=null;space(page,40);page.addView(lottie("empty_quotes",40),LinearLayout.LayoutParams(-1,d(190)));space(page,16);page.addView(text("Здесь будут ваши цитаты",25f,bold=true,serif=true).apply{gravity=Gravity.CENTER});return}
    val group=groups.firstOrNull{it.book.str("id")==quoteBookId};if(quoteBookId!=null && group==null)quoteBookId=null
    val sec=line().apply{tag="quote-header"}
    if(group==null){space(page,8)
        groups.forEach{g->page.addView(QuoteDeck(this,g.book,g.quotes).apply{tag="quote-item"},LinearLayout.LayoutParams(-1,-2));space(page,30)}
    }else{
        val back=backControl("Цитаты","Назад к цитатам"){openQuoteGroup(null)};sec.addView(back,LinearLayout.LayoutParams(0,d(44),1f));sec.addView(text("${group.quotes.size} ${quoteWord(group.quotes.size)}",14f,muted));page.addView(sec);space(page,16)
        val h=line().apply{tag="quote-header";setOnClickListener{detail(group.book)}};h.addView(CoverView(this,group.book),LinearLayout.LayoutParams(d(52),d(78)));val meta=column().apply{setPadding(d(14),0,0,0)};meta.addView(text(group.book.str("title"),20f,bold=true,serif=true));space(meta,2);meta.addView(text(MainActivity.authors(group.book),14f,muted));h.addView(meta,LinearLayout.LayoutParams(0,-2,1f));page.addView(h);space(page,18)
        group.quotes.sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")}).forEach{q->page.addView(quoteCardNative(group.book,q).apply{tag="quote-item"});space(page,16)}
    }
}
private fun quoteWord(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
/** A floating card: soft ambient + key shadow following the rounded outline, as on the statistics screen. */
/** Fully rounded floating pill with a soft, wide shadow that lifts it off the page. */
internal fun MainActivity.pillSurface(v:View){
    v.setPadding(d(14),d(10),d(22),d(10));v.minimumHeight=d(52)
    v.background=android.graphics.drawable.GradientDrawable().apply{setColor(if(light)Color.WHITE else 0xff2c2c2f.toInt());cornerRadius=d(200).toFloat()}
    v.elevation=d(18).toFloat();v.translationZ=0f
    v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,view.height/2f)}}
    if(android.os.Build.VERSION.SDK_INT>=28){v.outlineAmbientShadowColor=if(light)0x22000000 else 0x99000000.toInt();v.outlineSpotShadowColor=if(light)0x33000000 else 0xcc000000.toInt()}
}
/** Notice pill in the app's own card colour (the same surface as statistics and quote cards), with a hairline edge and a soft wide shadow. */
internal fun MainActivity.noticeSurface(v:View){
    v.setPadding(d(10),d(10),d(24),d(10));v.minimumHeight=d(62)
    v.background=android.graphics.drawable.GradientDrawable().apply{setColor(if(light)Color.WHITE else 0xff242426.toInt());cornerRadius=d(200).toFloat();setStroke(d(1),if(light)0x08000000 else 0x14ffffff)}
    v.elevation=d(16).toFloat()
    v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,view.height/2f)}}
    if(android.os.Build.VERSION.SDK_INT>=28){v.outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();v.outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
}
internal fun MainActivity.floatCard(v:View,radiusDp:Int=20,color:Int=if(light)Color.WHITE else 0xff242426.toInt(),lift:Int=if(light)8 else 10){
    val r=d(radiusDp).toFloat();v.background=android.graphics.drawable.GradientDrawable().apply{setColor(color);cornerRadius=r};v.elevation=d(lift).toFloat()
    v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,r)}}
    if(android.os.Build.VERSION.SDK_INT>=28){v.outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();v.outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
}
/** A real pile: one or two narrower sheets peek out under the front card, each with its own shadow. */
internal class QuoteStackLayer(val a:MainActivity,val count:Int):FrameLayout(a){
    private val layers=(count-1).coerceIn(0,2)
    private val sheets=List(layers){i->View(a).apply{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;a.floatCard(this,20,if(a.light)(if(i==0)0xfff7f7f5.toInt() else 0xffefefec.toInt()) else (if(i==0)0xff202022.toInt() else 0xff1c1c1e.toInt()),if(a.light)5-i*2 else 7-i*2)}}
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
    val stack=QuoteStackLayer(this,qs.size);val card=column().apply{floatCard(this,20);setPadding(d(16),d(16),d(16),d(18))}
    stack.addView(card,FrameLayout.LayoutParams(-1,-2));stack.contentDescription="${b.str("title")}: ${qs.size} ${quoteWord(qs.size)}";stack.isFocusable=true;stack.setOnClickListener{if(onOpen!=null)onOpen()else openQuoteGroup(b.str("id"))};press(stack)
    val top=line();top.addView(CoverView(this,b),LinearLayout.LayoutParams(d(36),d(54)))
    val meta=column().apply{setPadding(d(12),0,d(12),0)};meta.addView(text(b.str("title"),15f,bold=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});space(meta,2);meta.addView(text(MainActivity.authors(b).ifBlank{"Автор не указан"},12.5f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});top.addView(meta,LinearLayout.LayoutParams(0,-2,1f))
    val badge=line().apply{background=shape((accent and 0x00ffffff) or (if(light)0x1f000000 else 0x29000000),14);setPadding(d(11),0,d(6),0)}
    badge.addView(text("${qs.size}",14f,accent,true));badge.addView(IconView(this,"next",accent),LinearLayout.LayoutParams(d(16),d(16)).apply{leftMargin=d(2)});top.addView(badge,LinearLayout.LayoutParams(-2,d(28)))
    card.addView(top);space(card,14)
    val excerpt=line().apply{gravity=Gravity.TOP};excerpt.addView(quoteMark(),LinearLayout.LayoutParams(d(22),d(26)))
    excerpt.addView(text(qs.first().str("text"),16f,(fg and 0x00ffffff) or 0xd9000000.toInt(),serif=true).apply{cssLeading(1.5f);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(4)})
    card.addView(excerpt);return stack
}
private fun mix(a:Int,b:Int,t:Float)=Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
/** The quote's own colour (as chosen when it was saved). */
internal fun quoteInk(q:JSONObject)=(0xff shl 24) or QuoteColors.rgb(q.optString("color").ifBlank{"amber"})
/** Approved «тонированная карточка»: the card glows with the quote's colour from its top-left corner, with a thin edge of it. */
internal fun MainActivity.quoteTint(col:Int,strength:Float=.22f):android.graphics.drawable.GradientDrawable{val base=if(light)Color.WHITE else 0xff242426.toInt()
    return android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(mix(base,col,if(light)strength*.9f else strength),mix(base,col,.05f),base)).apply{cornerRadius=d(22).toFloat();setStroke(d(1).coerceAtLeast(1),(0x38 shl 24) or (col and 0xffffff))}}
/** Copy / delete drawn as line icons (two sheets with rounded corners; a bin with a lid and slots). */
internal class QuoteActionIcon(c:Context,private val kind:String,private val ink:Int):View(c){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=ink}
    private val path=Path()
    override fun onDraw(cv:Canvas){val s=minOf(width,height)/24f*.84f;cv.save();cv.translate(width/2f-12f*s,height/2f-12f*s);cv.scale(s,s);p.strokeWidth=1.8f
        path.rewind()
        if(kind=="copy"){path.addRoundRect(RectF(8.5f,8.5f,19.5f,20.5f),2.6f,2.6f,Path.Direction.CW)
            path.moveTo(15.5f,8.5f);path.lineTo(15.5f,6.6f);path.quadTo(15.5f,4.5f,13.4f,4.5f);path.lineTo(6.6f,4.5f);path.quadTo(4.5f,4.5f,4.5f,6.6f);path.lineTo(4.5f,14.4f);path.quadTo(4.5f,16.5f,6.6f,16.5f);path.lineTo(8.5f,16.5f)}
        else{path.moveTo(4.5f,6.8f);path.lineTo(19.5f,6.8f)
            path.moveTo(9.3f,6.8f);path.lineTo(9.3f,5.2f);path.quadTo(9.3f,3.8f,10.7f,3.8f);path.lineTo(13.3f,3.8f);path.quadTo(14.7f,3.8f,14.7f,5.2f);path.lineTo(14.7f,6.8f)
            path.moveTo(6.6f,6.8f);path.lineTo(7.4f,18.2f);path.quadTo(7.5f,20.2f,9.5f,20.2f);path.lineTo(14.5f,20.2f);path.quadTo(16.5f,20.2f,16.6f,18.2f);path.lineTo(17.4f,6.8f)
            path.moveTo(10.4f,10.8f);path.lineTo(10.4f,16.2f);path.moveTo(13.6f,10.8f);path.lineTo(13.6f,16.2f)}
        cv.drawPath(path,p);cv.restore()}
}
internal fun MainActivity.quoteCardNative(b:JSONObject,q:JSONObject,onOpen:(()->Unit)?=null):View {
    val col=quoteInk(q);val chipInk=if(light)mix(col,Color.BLACK,.28f) else col
    val card=column().apply{floatCard(this,22);background=quoteTint(col);setPadding(d(18),d(14),d(14),d(10))}
    val open={if(onOpen!=null)onOpen()else openBookAt(b,q.optInt("ch"),q.optInt("start"))}
    // the quote mark in the quote's colour, the date quietly opposite it
    val top=line().apply{gravity=Gravity.TOP}
    top.addView(text("\u201c",46f,col,true,true).apply{includeFontPadding=false;gravity=Gravity.TOP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(30),d(30)))
    top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
    val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale("ru")).format(java.util.Date(q.optLong("at",System.currentTimeMillis())))
    top.addView(text(date,12f,muted).apply{setPadding(0,d(4),0,0)})
    card.addView(top,LinearLayout.LayoutParams(-1,-2))
    card.addView(text(q.str("text"),17f,serif=true).apply{cssLeading(1.55f);maxLines=9;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,d(4),d(4),0);setOnClickListener{open()}})
    b.optJSONArray("notes")?.objects().orEmpty().filter{it.str("quoteId")==q.str("id")}.forEach{n->val note=column().apply{background=shape(if(light)0xfff4f4f1.toInt() else 0xff2e2e31.toInt(),14);setPadding(d(14),d(12),d(14),d(12));setOnClickListener{editThought(b,n,quote=q)}};note.addView(text("Моя мысль",12f,accent,true));space(note,5);note.addView(text(n.str("text"),15f).apply{cssLeading(1.5f)});card.addView(note,LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,d(12),d(4),0)})}
    card.addView(View(this).apply{setBackgroundColor((0x33 shl 24) or (col and 0xffffff))},LinearLayout.LayoutParams(-1,1).apply{topMargin=d(14)})
    val foot=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,d(10),0,d(2))}
    // where the quote is from: a chip in its colour
    val chapter=q.str("chTitle").trim().takeIf{it.isNotBlank()&&it.length<=40}?:"Глава ${q.optInt("ch")+1}"
    foot.addView(text(chapter,12.5f,chipInk,true).apply{background=shape((0x2e shl 24) or (col and 0xffffff),14);setPadding(d(10),d(4),d(10),d(5));maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=d(190)},LinearLayout.LayoutParams(-2,-2))
    foot.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
    fun round(kind:String,desc:String,act:()->Unit)=FrameLayout(this).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor((0x29 shl 24) or (col and 0xffffff))}
        isClickable=true;contentDescription=desc;addView(QuoteActionIcon(this@quoteCardNative,kind,col),FrameLayout.LayoutParams(d(22),d(22),Gravity.CENTER))
        setOnTouchListener{v,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->v.animate().scaleX(.88f).scaleY(.88f).setDuration(90).start();MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->v.animate().scaleX(1f).scaleY(1f).setDuration(240).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()};false}
        setOnClickListener{act()}}
    foot.addView(round("copy","Копировать цитату"){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата","«${q.str("text")}»\n— ${MainActivity.authors(b)}, «${b.str("title")}»"));Toast.makeText(this,"Цитата скопирована",Toast.LENGTH_SHORT).show()},LinearLayout.LayoutParams(d(38),d(38)))
    foot.addView(round("trash","Удалить цитату"){
        val remove={saveBook(b.copyJson().put("quotes",JSONArray(b.optJSONArray("quotes")!!.objects().filterNot{it.str("id")==q.str("id")})))}
        if(prefs.str("motion")=="reduced")remove() else NativeMotion(card,MotionPose(),MotionPose(0f,scale=.96f),200,curve=android.view.animation.AccelerateInterpolator(),complete=remove).start()
    },LinearLayout.LayoutParams(d(38),d(38)).apply{leftMargin=d(8)})
    card.addView(foot);return card
}
