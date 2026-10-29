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
    if(groups.isEmpty()){quoteBookId=null;space(page,40);page.addView(text("“",110f,accent,true,true).apply{gravity=Gravity.CENTER;alpha=.55f},LinearLayout.LayoutParams(-1,d(64)));space(page,24);page.addView(text("Здесь будут ваши цитаты",25f,bold=true,serif=true).apply{gravity=Gravity.CENTER});return}
    val group=groups.firstOrNull{it.book.str("id")==quoteBookId};if(quoteBookId!=null && group==null)quoteBookId=null
    val sec=line().apply{tag="quote-header"}
    if(group==null){val n=groups.sumOf{it.quotes.size};sec.addView(text("$n ${quoteWord(n)}",20f,bold=true),LinearLayout.LayoutParams(0,-2,1f));sec.addView(text("из ${groups.size} ${if(groups.size==1)"книги" else "книг"}",14f,muted));page.addView(sec,LinearLayout.LayoutParams(-1,d(40)));space(page,18)
        groups.forEach{g->page.addView(quoteStack(g.book,g.quotes).apply{tag="quote-item"});space(page,14)}
    }else{
        val back=backControl("Цитаты","Назад к цитатам"){openQuoteGroup(null)};sec.addView(back,LinearLayout.LayoutParams(0,d(44),1f));sec.addView(text("${group.quotes.size} ${quoteWord(group.quotes.size)}",14f,muted));page.addView(sec);space(page,16)
        val h=line().apply{tag="quote-header";setOnClickListener{detail(group.book)}};h.addView(CoverView(this,group.book),LinearLayout.LayoutParams(d(52),d(78)));val meta=column().apply{setPadding(d(14),0,0,0)};meta.addView(text(group.book.str("title"),20f,bold=true,serif=true));space(meta,2);meta.addView(text(MainActivity.authors(group.book),14f,muted));h.addView(meta,LinearLayout.LayoutParams(0,-2,1f));page.addView(h);space(page,18)
        group.quotes.sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")}).forEach{q->page.addView(quoteCardNative(group.book,q).apply{tag="quote-item"});space(page,14)}
    }
}
private fun quoteWord(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
internal class QuoteStackLayer(val a:MainActivity,val count:Int):FrameLayout(a){private val p=Paint(3);init{setWillNotDraw(false);setPadding(0,0,0,a.d(if(count>2)15 else if(count>1)8 else 0))};override fun onDraw(c:Canvas){val bottom=height.toFloat();p.color=if(a.light)0xffe9e9e6.toInt() else 0xff2a2a2d.toInt();if(count>2){p.alpha=140;c.drawRoundRect(a.d(26).toFloat(),bottom-a.d(30),width-a.d(26).toFloat(),bottom,a.d(18).toFloat(),a.d(18).toFloat(),p)};if(count>1){p.alpha=255;val y=bottom-a.d(if(count>2)7 else 0);c.drawRoundRect(a.d(12).toFloat(),y-a.d(30),width-a.d(12).toFloat(),y,a.d(18).toFloat(),a.d(18).toFloat(),p)}}}
private class QuoteStripe(val a:MainActivity):FrameLayout(a){private val p=Paint(3);init{setWillNotDraw(false)};override fun onDraw(c:Canvas){p.color=a.accentFill;c.drawRoundRect(0f,0f,a.d(3).toFloat(),height.toFloat(),a.d(1).toFloat(),a.d(1).toFloat(),p)}}
internal fun MainActivity.quoteStack(b:JSONObject,qs:List<JSONObject>,onOpen:(()->Unit)?=null):View {
    val stack=QuoteStackLayer(this,qs.size);val card=column().apply{background=controlSurface(this,20f);setPadding(d(14),d(14),d(14),d(16))};stack.addView(card,FrameLayout.LayoutParams(-1,-2));stack.contentDescription="${b.str("title")}: ${qs.size} ${quoteWord(qs.size)}";stack.setOnClickListener{if(onOpen!=null)onOpen()else openQuoteGroup(b.str("id"))};press(stack)
    val top=line();top.addView(CoverView(this,b),LinearLayout.LayoutParams(d(34),d(51)));val meta=column().apply{setPadding(d(12),0,d(12),0)};meta.addView(text(b.str("title"),15f,bold=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});meta.addView(text(MainActivity.authors(b),12.5f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END});top.addView(meta,LinearLayout.LayoutParams(0,-2,1f));val count=line().apply{background=controlSurface(this,14f);setPadding(d(10),0,d(4),0)};count.addView(text("${qs.size}",14f,accent,true));count.addView(IconView(this,"next",muted),LinearLayout.LayoutParams(d(16),d(16)));top.addView(count,LinearLayout.LayoutParams(-2,d(28)));card.addView(top);space(card,12)
    val excerpt=FrameLayout(this);val stripe=QuoteStripe(this);stripe.setPadding(d(14),0,d(30),0);stripe.addView(text(qs.first().str("text"),16f,muted,serif=true).apply{cssLeading(1.5f);maxLines=3;ellipsize=android.text.TextUtils.TruncateAt.END});excerpt.addView(stripe,FrameLayout.LayoutParams(-1,-2));excerpt.addView(text("“",64f,accent,true,true).apply{alpha=.16f},FrameLayout.LayoutParams(d(35),d(64),Gravity.TOP or Gravity.END).apply{topMargin=-d(10);rightMargin=d(2)});card.addView(excerpt);return stack
}
internal fun MainActivity.quoteCardNative(b:JSONObject,q:JSONObject,onOpen:(()->Unit)?=null):View {
    val card=column().apply{background=controlSurface(this,20f);setPadding(d(8),d(6),d(8),d(8))};val body=FrameLayout(this);val stripe=QuoteStripe(this).apply{setPadding(d(14),d(12),d(46),d(6));setOnClickListener{if(onOpen!=null)onOpen()else openBookAt(b,q.optInt("ch"),q.optInt("start"))}};stripe.addView(text(q.str("text"),17f,serif=true).apply{cssLeading(1.55f);maxLines=9;ellipsize=android.text.TextUtils.TruncateAt.END});body.addView(stripe);body.addView(text("“",84f,accent,true,true).apply{alpha=.16f;isClickable=false},FrameLayout.LayoutParams(d(46),d(84),Gravity.TOP or Gravity.END).apply{topMargin=-d(10)});card.addView(body)
    b.optJSONArray("notes")?.objects().orEmpty().filter{it.str("quoteId")==q.str("id")}.forEach{n->val note=column().apply{background=controlSurface(this,12f);setPadding(d(12),d(12),d(12),d(12));setOnClickListener{editThought(b,n,quote=q)}};note.addView(text("Моя мысль",12f,accent));space(note,5);note.addView(text(n.str("text"),15f).apply{cssLeading(1.5f)});card.addView(note,LinearLayout.LayoutParams(-1,-2).apply{setMargins(d(12),d(8),d(12),0)})}
    space(card,8);card.addView(View(this).apply{setBackgroundColor(if(light)0x181a1a1c else 0x18ffffff)},LinearLayout.LayoutParams(-1,1));val foot=line().apply{setPadding(d(10),d(8),d(4),0)};val date=java.text.SimpleDateFormat("d MMMM",java.util.Locale("ru")).format(java.util.Date(q.optLong("at",System.currentTimeMillis())));foot.addView(text(date,12.5f,muted).apply{setPadding(d(4),0,0,0)},LinearLayout.LayoutParams(0,-2,1f))
    foot.addView(icon("copy","Копировать цитату"){(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Цитата","«${q.str("text")}»\n— ${MainActivity.authors(b)}, «${b.str("title")}»"))}.apply{background=null},LinearLayout.LayoutParams(d(40),d(40)))
    foot.addView(icon("trash","Удалить цитату"){
        val remove={saveBook(b.copyJson().put("quotes",JSONArray(b.optJSONArray("quotes")!!.objects().filterNot{it.str("id")==q.str("id")})))}
        if(prefs.str("motion")=="reduced")remove() else NativeMotion(card,MotionPose(),MotionPose(0f,scale=.96f),200,curve=android.view.animation.AccelerateInterpolator(),complete=remove).start()
    }.apply{background=null},LinearLayout.LayoutParams(d(40),d(40)));card.addView(foot);return card
}
