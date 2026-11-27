package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * Filters of «Мои книги» (approved: a row of chips under the search, as Google Play Книги has it): Статус, Автор,
 * Серия, Жанр, Тип, Язык. A chip shows only where the library has more than one thing to choose from; it opens its
 * own short list with ticks and the count on the button; chosen, it shows the choice and a cross. Within a chip any
 * of the ticked match, between chips all must. Kept in the preferences.
 */
internal object LibraryFilters {
    val groups=listOf("status","author","series","genre","type","lang")
    fun title(g:String)=when(g){"status"->"Статус";"author"->"Автор";"series"->"Серия";"genre"->"Жанр";"type"->"Тип";else->"Язык"}
    private val STATUS=listOf("want","reading","done","paused")
    private val TYPES=listOf("books","comics","docs")
    fun type(b:JSONObject)=when{isComic(b)->"comics";PageDocs.isDoc(b)||b.str("format").lowercase() in setOf("pdf","docx","rtf","txt","html")->"docs";else->"books"}
    private fun lang(b:JSONObject)=b.str("lang").trim().lowercase().take(2).takeIf{it.length==2&&it.all{c->c in 'a'..'z'}}
    fun values(b:JSONObject,g:String):List<String> = when(g){
        "status"->listOf(Library.status(b))
        "author"->b.optJSONArray("authors")?.strings()?.map{it.trim()}?.filter{it.isNotBlank()}.orEmpty()
        "series"->listOfNotNull(b.str("series").trim().takeIf{it.isNotBlank()})
        "genre"->b.optJSONArray("genres")?.strings()?.mapNotNull{Genres.name(it)}?.distinct().orEmpty()
        "type"->listOf(type(b))
        else->listOfNotNull(lang(b))}
    /** An author as a list shows them: the surname first («Брэдбери Рэй»). */
    fun surnameFirst(name:String):String{val w=name.trim().split(Regex("\\s+"));return if(w.size<2)name.trim() else (listOf(w.last())+w.dropLast(1)).joinToString(" ")}
    fun label(g:String,v:String):String=when(g){
        "status"->BookStatus.name(v)
        "type"->when(v){"comics"->"Комиксы";"docs"->"Документы";else->"Книги"}
        "lang"->java.util.Locale(v).getDisplayLanguage(java.util.Locale("ru")).replaceFirstChar{it.uppercase()}.ifBlank{v.uppercase()}
        "author"->surnameFirst(v)
        else->v}
    /** The short form on a chip: surnames only for authors. */
    private fun short(g:String,v:String)=if(g=="author")v.trim().split(Regex("\\s+")).last() else label(g,v)

    fun selected(a:MainActivity,g:String):Set<String> = a.prefs.optJSONObject("filters")?.optJSONArray(g)?.strings()?.toSet().orEmpty()
    fun put(a:MainActivity,g:String,vals:Set<String>){val f=a.prefs.optJSONObject("filters")?:JSONObject().also{a.prefs.put("filters",it)}
        if(vals.isEmpty())f.remove(g) else f.put(g,JSONArray(vals.toList()));a.savePreferences()}
    fun clear(a:MainActivity){a.prefs.remove("filters");a.savePreferences()}
    fun active(a:MainActivity)=groups.any{selected(a,it).isNotEmpty()}
    /** [list] as the chips allow; [with] stands in for one chip's choice still being made in its sheet. */
    fun apply(a:MainActivity,list:List<JSONObject>,with:Pair<String,Set<String>>?=null)=list.filter{b->groups.all{g->
        val s=if(with?.first==g)with.second else selected(a,g);s.isEmpty()||values(b,g).any{it in s}}}
    /** What [g] can be set to, with how many books each would show (the other chips as they are). */
    fun options(a:MainActivity,g:String,books:List<JSONObject>):List<Pair<String,Int>>{
        val base=apply(a,books,g to emptySet());val counts=LinkedHashMap<String,Int>()
        base.forEach{b->values(b,g).forEach{v->counts[v]=(counts[v]?:0)+1}}
        selected(a,g).forEach{if(it !in counts)counts[it]=0}
        val order=when(g){"status"->STATUS;"type"->TYPES;else->null}
        return if(order!=null)counts.entries.sortedBy{order.indexOf(it.key)}.map{it.key to it.value}
            else counts.entries.sortedBy{key(g,it.key)}.map{it.key to it.value}}
    fun key(g:String,v:String)=label(g,v).lowercase()
    /** A chip is offered when the whole library has two things or more to tell apart there (or one is chosen). */
    fun offered(a:MainActivity,g:String,books:List<JSONObject>)=selected(a,g).isNotEmpty()||books.flatMap{values(it,g)}.distinct().size>=2

    /** The chip row under the search (empty when the library gives nothing to filter by). */
    fun row(a:MainActivity):View?{
        val books=a.homeBooks();val shown=groups.filter{offered(a,it,books)};if(shown.isEmpty())return null
        val strip=a.line().apply{setPadding(a.d(2),0,a.d(2),0)}
        shown.forEach{g->strip.addView(chip(a,g),LinearLayout.LayoutParams(-2,a.d(Size.TOUCH)).apply{marginEnd=a.d(8)})}
        if(active(a))strip.addView(a.text("Сбросить",Type.TEXT,a.muted).apply{typeface=PolkaFonts.sans(500);gravity=Gravity.CENTER;setPadding(a.d(6),0,a.d(6),0);isClickable=true
            setOnClickListener{clear(a);a.render()}},LinearLayout.LayoutParams(-2,a.d(Size.TOUCH)))
        return HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(strip)}}

    private fun chip(a:MainActivity,g:String):View{
        val on=selected(a,g);val c=a.line().apply{isClickable=true;isFocusable=true}
        if(on.isEmpty()){
            c.setPadding(a.d(15),0,a.d(10),0);a.controlSurface(c,18f).seenHeight=36f
            c.addView(a.text(title(g),Type.TEXT,a.fg).apply{typeface=PolkaFonts.sans(500)})
            c.addView(IconView(a,"chevdown",a.muted),LinearLayout.LayoutParams(a.d(16),a.d(16)).apply{marginStart=a.d(4)})
            c.contentDescription=title(g)
        }else{
            c.setPadding(a.d(15),0,0,0);c.background=android.graphics.drawable.InsetDrawable(a.shape(a.fg,Radius.R20),0,a.d(6),0,a.d(6))
            val words=on.map{short(g,it)}.sortedBy{it.lowercase()}.joinToString(", ")
            c.addView(a.text(words,Type.TEXT,a.bg).apply{typeface=PolkaFonts.sans(500);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=a.d(190)})
            // the cross takes its own touch: it lets the chip go; the rest of the chip opens its list
            c.addView(IconView(a,"x",a.bg).apply{contentDescription="Убрать фильтр «${title(g)}»";isClickable=true;setPadding(a.d(4),0,a.d(8),0)
                setOnClickListener{put(a,g,emptySet());a.render()}},LinearLayout.LayoutParams(a.d(34),-1))
            c.contentDescription="${title(g)}: $words"
        }
        a.press(c);c.setOnClickListener{FilterSheet(a,g).show()};return c}
}

/** One chip's list (approved «Автор»): a search when the list is long, letters over it, the number of books by each,
 *  ticks; nothing changes until «Показать …» (its count follows the ticks). */
internal class FilterSheet(private val a:MainActivity,private val g:String){
    private val books=a.homeBooks()
    private val picked=LibraryFilters.selected(a,g).toMutableSet()
    private val options=LibraryFilters.options(a,g,books)
    private val ticks=HashMap<String,TickBox>()
    private val button=a.button("Показать",true){commit()}
    private var sheet:android.app.AlertDialog?=null

    fun show(){
        val box=a.column()
        val long=options.size>8
        val list=a.column()
        val host=FastScroll(a,list)
        if(long){
            val field=EditText(a).apply{hint=when(g){"author"->"Найти автора";"series"->"Найти серию";"genre"->"Найти жанр";else->"Найти"};setHintTextColor(a.muted);setTextColor(a.fg);textSize=Type.TEXT;typeface=PolkaFonts.sans(400);background=null;setSingleLine(true);setPadding(0,0,0,0)}
            val bar=a.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(16),0);background=a.shape(Ui.fill(a.light),Radius.R24)}
            bar.addView(IconView(a,"search",a.muted),LinearLayout.LayoutParams(a.d(18),a.d(18)).apply{marginEnd=a.d(10)});bar.addView(field,LinearLayout.LayoutParams(0,-2,1f))
            box.addView(bar,LinearLayout.LayoutParams(-1,a.d(44)).apply{bottomMargin=a.d(4)})
            field.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){};override fun onTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){}
                override fun afterTextChanged(s:Editable?){fill(list,host,s?.toString()?.trim().orEmpty(),true)}})
        }
        box.addView(host,LinearLayout.LayoutParams(-1,-2).apply{marginEnd=-a.d(14)})
        fill(list,host,"",long)
        val foot=a.column().apply{setPadding(a.d(20),a.d(10),a.d(20),a.d(16));addView(button,LinearLayout.LayoutParams(-1,-2))}
        count()
        sheet=a.dialog().setTitle(LibraryFilters.title(g)).setView(box).setTall(long).setFooter(foot)
            .setHeaderAction("Сбросить"){picked.clear();ticks.values.forEach{it.on=false};count()}.show()
    }

    private fun fill(list:LinearLayout,host:FastScroll,q:String,letters:Boolean){
        list.removeAllViews();ticks.clear();val marks=ArrayList<Pair<String,View>>();var last=""
        options.filter{q.isBlank()||LibraryFilters.label(g,it.first).contains(q,true)}.forEach{(v,n)->
            val name=LibraryFilters.label(g,v)
            if(letters){val l=name.take(1).uppercase();if(l!=last){last=l;val h=a.text(l,Type.CAPTION,a.muted,true).apply{setPadding(a.d(2),a.d(12),0,a.d(2))};list.addView(h);marks.add(l to h)}}
            list.addView(row(v,name,n).apply{minimumHeight=a.d(Size.ROW)},LinearLayout.LayoutParams(-1,-2))}
        if(list.childCount==0)list.addView(a.text("Ничего не нашлось",Type.TEXT,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(24),0,a.d(24))},LinearLayout.LayoutParams(-1,-2))
        host.sections=if(options.size>25)marks else emptyList()}

    private fun row(v:String,name:String,n:Int):View{
        val r=a.line().apply{setPadding(a.d(2),0,0,0);isClickable=true;isFocusable=true}
        when(g){
            "status"->r.addView(StatusIcon(a,BookStatus.icon(v),a.muted),LinearLayout.LayoutParams(a.d(22),a.d(22)).apply{marginEnd=a.d(14)})
            "author"->r.addView(Initials(a,name),LinearLayout.LayoutParams(a.d(36),a.d(36)).apply{marginEnd=a.d(12)})
        }
        r.addView(a.text(name,Type.BODY,a.fg).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
        r.addView(a.text(n.toString(),Type.TEXT,a.muted),LinearLayout.LayoutParams(-2,-2).apply{marginStart=a.d(10);marginEnd=a.d(12)})
        val t=TickBox(a,v in picked);ticks[v]=t;r.addView(t,LinearLayout.LayoutParams(a.d(24),a.d(24)))
        fun describe(){r.contentDescription=name+if(t.on)", выбрано" else ""};describe()
        r.setOnClickListener{t.on=!t.on;if(t.on)picked.add(v) else picked.remove(v);describe();count()};a.press(r)
        return r}

    private fun count(){val n=LibraryFilters.apply(a,books,g to picked).size
        button.text=if(n==0)"Ничего не найдено" else "Показать ${MainActivity.countBooks(n)}";button.isEnabled=n>0;button.alpha=if(n>0)1f else .4f}

    private fun commit(){LibraryFilters.put(a,g,picked);sheet?.dismiss();a.render()}

    /** An author's initials in a circle. */
    private class Initials(private val a:MainActivity,name:String):View(a){
        private val text=name.trim().split(Regex("\\s+")).take(2).joinToString(""){it.take(1).uppercase()}
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(600);textAlign=Paint.Align.CENTER}
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onDraw(c:Canvas){val r=min(width,height)/2f;p.color=Ui.press(a.light);c.drawCircle(width/2f,height/2f,r,p)
            p.color=Ui.ink2(a.light);p.textSize=r*.72f;val fm=p.fontMetrics;c.drawText(text,width/2f,height/2f-(fm.ascent+fm.descent)/2f,p)}
    }
}

/** The order (approved «Сортировка» SO1–SO3): the chosen order is the lit row; its way is a small pill in it (an arrow
 *  and the words), and a tap on it, or on the row again, turns the order. «Мой порядок» has no other way. */
internal fun MainActivity.sortSheet(){
    val box=column();val rows=ArrayList<()->Unit>()
    // the lit row reaches a little past the sheet's text edge, so every name stays on one line
    // (the sheet's content clips to its padding and its children to their bounds; it lets the list's rows out)
    box.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewDetachedFromWindow(v:View){}
        override fun onViewAttachedToWindow(v:View){(box.parent as? ViewGroup)?.apply{clipToPadding=false;clipChildren=false}}})
    fun pick(key:String){if(sort==key){if(key=="manual")return;prefs.put("sortReverse",!prefs.optBoolean("sortReverse"))}else{sort=key;prefs.put("sort",key);prefs.put("sortReverse",false)}
        savePreferences();rows.forEach{it()};render()}
    listOf("manual","recent","added","title","author","done").forEach{key->
        val r=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(12),0,d(10),0);isClickable=true;isFocusable=true}
        val name=text(sortName(key),Type.BODY,fg);r.addView(name,LinearLayout.LayoutParams(0,-2,1f))
        val pill=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(9),0,d(13),0);background=shape(Ui.press(light),Radius.R20);isClickable=true;isFocusable=true;setOnClickListener{pick(key)}}
        val arrow=FrameLayout(this);pill.addView(arrow,LinearLayout.LayoutParams(d(17),d(17)).apply{marginEnd=d(6)})
        val way=text("",Type.SECONDARY,fg).apply{typeface=PolkaFonts.sans(600)};pill.addView(way)
        press(pill);r.addView(pill,LinearLayout.LayoutParams(-2,d(34)))
        fun show(){val on=sort==key;val rev=prefs.optBoolean("sortReverse");val turns=on&&key!="manual"
            r.background=if(on)shape(Ui.fill(light),Radius.R20) else null
            name.setTextColor(if(on)fg else ColorUtilsCompat.mute(this));name.typeface=PolkaFonts.sans(if(on)600 else 400)
            pill.visibility=if(turns)View.VISIBLE else View.GONE
            if(turns){way.text=sortWay(key,rev);arrow.removeAllViews();arrow.addView(IconView(this,if(sortUp(key,rev))"arrowup" else "arrowdown",fg),FrameLayout.LayoutParams(-1,-1))
                pill.contentDescription="Порядок: "+sortWay(key,rev)+", сменить"}
            r.contentDescription=sortName(key)+if(on)", выбрано"+(if(turns)", "+sortWay(key,rev) else "") else ""}
        show();rows.add(::show)
        r.setOnClickListener{pick(key)}
        press(r);box.addView(r.apply{minimumHeight=d(Size.ROW)},LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-d(12);rightMargin=-d(12)})}
    dialog().setTitle("Сортировка").setView(box).show()
}
/** Text orders go А→Я first, dates newest first; [rev] turns either. Up is А→Я and oldest first. */
private fun sortUp(key:String,rev:Boolean)=(key=="title"||key=="author")!=rev
private fun sortWay(key:String,rev:Boolean)=if(key=="title"||key=="author")(if(rev)"Я–А" else "А–Я") else (if(rev)"Сначала старые" else "Сначала новые")
private object ColorUtilsCompat{fun mute(a:MainActivity)=androidx.core.graphics.ColorUtils.blendARGB(a.fg,a.muted,.35f)}

/**
 * The list's quick scroll (approved «Б: ручка прокрутки»): nothing at rest; while the list moves a thin handle shows
 * at its edge, and dragged it takes the list along with the letter it is at, big beside it. Only for long lists
 * ([sections] given).
 */
internal class FastScroll(private val a:MainActivity,private val content:View):FrameLayout(a){
    companion object{ @Volatile var holding=false }
    var sections:List<Pair<String,View>> =emptyList();set(v){field=v;invalidate()}
    private var sv:ScrollView?=null
    private var shown=0f;private var bubble=0f;private var dragging=false
    private var fade:ValueAnimator?=null;private var pop:ValueAnimator?=null
    private val hide=Runnable{if(!dragging)fadeTo(0f)}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
    private val len=a.d(52).toFloat();private val thick=a.d(5).toFloat();private val edge=a.d(14).toFloat();private val pad=a.d(10).toFloat()
    private val scrolled=android.view.ViewTreeObserver.OnScrollChangedListener{if(sections.isNotEmpty()){fadeTo(1f);removeCallbacks(hide);if(!dragging)postDelayed(hide,1200)};invalidate()}
    init{setWillNotDraw(false);addView(content,LayoutParams(-1,-2));setPadding(0,0,a.d(14),0);clipChildren=false}
    override fun onAttachedToWindow(){super.onAttachedToWindow();var v=parent;while(v!=null&&v !is ScrollView)v=v.parent;sv=v as? ScrollView;viewTreeObserver.addOnScrollChangedListener(scrolled)}
    override fun onDetachedFromWindow(){viewTreeObserver.removeOnScrollChangedListener(scrolled);removeCallbacks(hide);fade?.cancel();pop?.cancel();holding=false;super.onDetachedFromWindow()}
    private fun fadeTo(t:Float){if(a.prefs.str("motion")=="reduced"){shown=t;invalidate();return};if(fade?.isRunning==true&&abs(t-shown)<.01f)return;fade?.cancel()
        fade=ValueAnimator.ofFloat(shown,t).apply{duration=if(t>shown)150 else 300;addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
    private fun popTo(t:Float){pop?.cancel();pop=ValueAnimator.ofFloat(bubble,t).apply{duration=if(t>bubble)140 else 220;interpolator=MotionSpec.enter;addUpdateListener{bubble=it.animatedValue as Float;invalidate()};start()}}
    // the visible window of the list, in this view's own coordinates
    private fun window():Pair<Float,Float>{val s=sv?:return 0f to height.toFloat();val loc=IntArray(2);val sl=IntArray(2);getLocationInWindow(loc);s.getLocationInWindow(sl)
        val top=(sl[1]-loc[1]).toFloat().coerceAtLeast(0f);return top to min(height.toFloat(),top+s.height-s.paddingBottom)}
    private fun range():Int{val s=sv?:return 1;return max(1,(s.getChildAt(0)?.height?:0)+s.paddingTop+s.paddingBottom-s.height)}
    private fun handleTop():Float{val s=sv?:return 0f;val (top,bottom)=window();val f=(s.scrollY.toFloat()/range()).coerceIn(0f,1f);return top+pad+f*(bottom-top-2*pad-len)}
    private fun letter():String?{val s=sv?:return null;val loc=IntArray(2);val sl=IntArray(2);var cur:String?=null
        sections.forEach{(l,v)->v.getLocationInWindow(loc);s.getLocationInWindow(sl);if(loc[1]-sl[1]<=a.d(24))cur=l};return cur?:sections.firstOrNull()?.first}
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
        if(e.actionMasked==MotionEvent.ACTION_DOWN&&sections.isNotEmpty()&&shown>.3f){val y=handleTop()
            if(e.x>=width-a.d(32)&&e.y in (y-a.d(24))..(y+len+a.d(24))){dragging=true;holding=true;parent?.requestDisallowInterceptTouchEvent(true);removeCallbacks(hide);popTo(1f);return true}}
        return false}
    override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);val s=sv?:return true
        when(e.actionMasked){
            MotionEvent.ACTION_MOVE->{val (top,bottom)=window();val f=((e.y-top-pad-len/2f)/(bottom-top-2*pad-len)).coerceIn(0f,1f);s.scrollTo(0,(f*range()).roundToInt());invalidate()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;holding=false;popTo(0f);postDelayed(hide,1200)}};return true}
    override fun dispatchDraw(c:Canvas){super.dispatchDraw(c);if(sections.isEmpty()||shown<=0f)return
        val y=handleTop();val x=width-edge+(edge-thick)/2f
        p.color=a.fg;p.alpha=(255*shown*(if(dragging)1f else .55f)).roundToInt();c.drawRoundRect(x,y,x+thick,y+len,thick/2f,thick/2f,p)
        if(bubble>0f){val l=letter()?:return;val h=a.d(56).toFloat();tp.typeface=PolkaFonts.serif(a,true);tp.textSize=a.d(28).toFloat();val w=max(h,tp.measureText(l)+a.d(40))
            val cx=x-a.d(14)-w/2f;val cy=y+len/2f;c.save();c.scale(.85f+.15f*bubble,.85f+.15f*bubble,cx+w/2f,cy)
            p.alpha=(255*bubble).roundToInt();c.drawRoundRect(cx-w/2f,cy-h/2f,cx+w/2f,cy+h/2f,h/2f,h/2f,p)
            tp.color=a.bg;tp.alpha=(255*bubble).roundToInt();val fm=tp.fontMetrics;c.drawText(l,cx,cy-(fm.ascent+fm.descent)/2f,tp);c.restore()}}
}
