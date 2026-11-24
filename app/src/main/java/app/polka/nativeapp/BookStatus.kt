package app.polka.nativeapp

import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** «Хочу прочитать», «Читаю», «Прочитано», «Отложено»: one status per book, set by hand or moved forward by reading. */
internal object BookStatus {
    fun name(s:String)=when(s){"reading"->"Читаю";"done"->"Прочитано";"paused"->"Отложено";else->"Хочу прочитать"}
    fun icon(s:String)=when(s){"reading"->"book";"done"->"done";"paused"->"paused";else->"bookmark"}
}

/** An icon whose kind can change (the status tag swaps it when the status does). */
internal class StatusIcon(private val a:MainActivity,kind:String,private val ink:Int):View(a){
    var kind=kind;set(v){field=v;invalidate()}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val s=minOf(width,height)/24f;c.save();c.translate((width-24f*s)/2f,(height-24f*s)/2f);c.scale(s,s);IosIcons.draw(c,kind,ink);c.restore()}
}

/** Sets the status by hand. Marking a book read keeps the place in it; the dates that go with the status are set once. */
internal fun MainActivity.setBookStatus(book:JSONObject,state:String){
    val latest=books.firstOrNull{it.str("id")==book.str("id")}?:book
    val was=Library.status(latest);if(was==state)return
    val now=System.currentTimeMillis();val c=latest.copyJson().put("state",state)
    if(state=="done")c.put("completedAt",now)
    if(state=="reading"&&was=="want"&&c.optLong("startedAt")==0L)c.put("startedAt",now)
    saveBook(c)
}

/** The book's own shelves (several at once). Older books kept one shelf in «shelf»; it counts as the first. */
internal object BookShelves {
    fun of(book:JSONObject):List<String> = book.optJSONArray("shelves")?.strings()?.filter{it.isNotBlank()}
        ?: book.optString("shelf").takeIf{it.isNotBlank()}?.let{listOf(it)} ?: emptyList()
    fun on(book:JSONObject,name:String)=of(book).contains(name)
    /** [book] with exactly these shelves (the old single field goes). */
    fun put(book:JSONObject,list:List<String>):JSONObject{book.put("shelves",JSONArray(list.filter{it.isNotBlank()}.distinct()));book.remove("shelf");return book}
}

/** Approved «Статус и полки»: one sheet for both — the four statuses (the current one ticked) and every shelf with a
 *  box to tick (a book can stand on several), then a new shelf. Each tap is saved at once; the sheet stays open. */
internal fun MainActivity.statusSheet(book:JSONObject){
    val id=book.str("id")
    fun latest()=books.firstOrNull{it.str("id")==id}?:book
    val box=column()
    fun header(s:String,top:Int)=box.addView(text(s,20f,fg,true).apply{setPadding(d(6),0,d(6),0)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(top);bottomMargin=d(6)})
    header("Статус",0)
    var current=Library.status(latest())
    val ticks=HashMap<String,View>()
    listOf("reading","want","done","paused").forEach{s->
        val row=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(52);setPadding(d(6),0,d(6),0);isClickable=true;isFocusable=true
            contentDescription=BookStatus.name(s)+if(s==current)", выбрано" else ""}
        row.addView(StatusIcon(this,BookStatus.icon(s),fg),LinearLayout.LayoutParams(d(22),d(22)))
        row.addView(text(BookStatus.name(s),16f).apply{setPadding(d(14),0,d(8),0)},LinearLayout.LayoutParams(0,-2,1f))
        val tick=StatusIcon(this,"check",fg).apply{visibility=if(s==current)View.VISIBLE else View.INVISIBLE}
        row.addView(tick,LinearLayout.LayoutParams(d(20),d(20)));ticks[s]=tick
        row.setOnClickListener{if(s==current)return@setOnClickListener;current=s;ticks.forEach{(k,v)->v.visibility=if(k==s)View.VISIBLE else View.INVISIBLE}
            setBookStatus(latest(),s)}
        press(row);box.addView(row,LinearLayout.LayoutParams(-1,d(52)))
    }
    header("Полки",20)
    val list=column();box.addView(list)
    fun names()=(prefs.optJSONArray("shelves")?.strings().orEmpty()+books.flatMap{BookShelves.of(it)}).filter{it.isNotBlank()}.distinct()
    fun fill(){list.removeAllViews()
        names().forEach{n->
            val row=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(6),0,d(6),0);isClickable=true;isFocusable=true}
            row.addView(text(n,16f).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
            val tick=TickBox(this,BookShelves.on(latest(),n));row.addView(tick,LinearLayout.LayoutParams(d(24),d(24)))
            fun describe(){row.contentDescription=n+if(tick.on)", на полке" else ""};describe()
            row.setOnClickListener{tick.on=!tick.on;describe()
                val b=latest().copyJson();val now=BookShelves.of(b);saveBook(BookShelves.put(b,if(tick.on)now+n else now-n))}
            press(row);list.addView(row,LinearLayout.LayoutParams(-1,d(50)))}
        val add=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(6),0,d(6),0);isClickable=true;isFocusable=true;contentDescription="Новая полка"}
        add.addView(IconView(this,"plus",muted),LinearLayout.LayoutParams(d(22),d(22)))
        add.addView(text("Новая полка",16f,muted).apply{setPadding(d(14),0,0,0)})
        add.setOnClickListener{inputDialog("Новая полка",hint="Название полки",action="Создать"){raw->val n=raw.trim();if(n.isBlank())return@inputDialog
            if(!names().any{it.equals(n,true)}){prefs.put("shelves",JSONArray((prefs.optJSONArray("shelves")?.strings().orEmpty()+n).distinct()));savePreferences()}
            val real=names().firstOrNull{it.equals(n,true)}?:n
            val b=latest().copyJson();saveBook(BookShelves.put(b,BookShelves.of(b)+real)){fill()}}}
        press(add);list.addView(add,LinearLayout.LayoutParams(-1,d(50)))}
    fill()
    dialog().setView(box).show()
}

/** A square box to tick: an outline when empty, filled with a check when ticked. */
internal class TickBox(private val a:MainActivity,on:Boolean):View(a){
    var on=on;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val r=a.d(7).toFloat();val w=width.toFloat();val h=height.toFloat()
        if(on){p.style=Paint.Style.FILL;p.color=a.fg;c.drawRoundRect(0f,0f,w,h,r,r,p)
            c.save();val s=w*.68f/24f;c.translate((w-24f*s)/2f,(h-24f*s)/2f);c.scale(s,s);IosIcons.draw(c,"check",a.bg);c.restore()}
        else{val k=a.d(1)*1.75f;p.style=Paint.Style.STROKE;p.strokeWidth=k;p.color=if(a.light)0x40000000 else 0x5affffff;c.drawRoundRect(k/2,k/2,w-k/2,h-k/2,r,r,p)}}
}
