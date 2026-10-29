package app.polka.nativeapp
import android.app.AlertDialog
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.*
import org.json.*
import java.io.File
import kotlin.math.*

fun MainActivity.recommendations() {
    val host=column().apply{clipChildren=false;clipToPadding=false}; page.addView(host)
    work({ val catalog=JSONObject(File(filesDir,"recs.json").readText()); val state=library.dao.meta("recs")?.let { JSONObject(it.json).optJSONObject("data") } ?: JSONObject().put("v",1).put("picks",JSONArray()).put("sw",JSONObject()).put("onboarded",false)
        runOnUiThread { if(host.isAttachedToWindow) RecommendationDeck(this,host,catalog,state).start() }
    })
}
class RecommendationDeck(val a:MainActivity,val host:LinearLayout,val catalog:JSONObject,val state:JSONObject) {
    private val seed=System.nanoTime().toInt()
    private val renderedTheme=a.light
    private val catalogStamp=File(a.filesDir,"recs.json").lastModified()
    private var libraryStamp=a.books.joinToString { it.toString() }
    /** Closing a description is not navigation: keep the exact deck, order and scroll position. */
    internal fun refreshAfterOverlay():Boolean {
        if(!host.isAttachedToWindow || renderedTheme!=a.light || catalogStamp!=File(a.filesDir,"recs.json").lastModified())return false
        val current=a.books.joinToString { it.toString() }
        if(current!=libraryStamp) {
            libraryStamp=current
            // Update personalisation for the *next* vote/rank, never reshuffle cards at handoff.
            countLabel?.text=countText()
        }
        return true
    }
    var list=listOf<JSONObject>(); var index=0; var busy=false
    private var activeDeck:FrameLayout?=null
    private var countLabel:TextView?=null
    private var actions:LinearLayout?=null
    private var undoButton:View?=null
    private var sticky:View?=null
    private var pullClock:android.animation.ValueAnimator?=null
    private var flightClock:android.animation.ValueAnimator?=null
    private var pulled=0f
    private val selection=linkedSetOf<String>()
    var starterPage=0
    data class Undo(val book:JSONObject,val previous:JSONObject?,val addedId:String?,val action:String)
    val history=java.util.ArrayDeque<Undo>()
    init { host.clipChildren=false; host.clipToPadding=false; host.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener { override fun onViewAttachedToWindow(v:View) {} ; override fun onViewDetachedFromWindow(v:View) { if(a.recommendationDeck===this@RecommendationDeck)a.recommendationDeck=null; pullClock?.cancel(); flightClock?.cancel(); val old=sticky; sticky=null; a.root.post { (old?.parent as? ViewGroup)?.removeView(old) } } }) }
    private fun rm()=a.prefs.str("motion")=="reduced"
    private fun removeSticky() { sticky?.let { (it.parent as? ViewGroup)?.removeView(it) }; sticky=null }
    fun persist(done:()->Unit={}) { val copy=state.copyJson(); a.work({ a.library.dao.putMeta(MetaRow("recs",JSONObject().put("id","recs").put("data",copy).toString())) }) { a.checkpointSoon(); done() } }
    fun start() { a.recommendationDeck=this; if(!state.optBoolean("onboarded") && RecommendationEngine.positive(catalog,a.books,state)<3) onboarding() else rank() }
    fun rank() { list=RecommendationEngine.rank(catalog,a.books,emptySet(),state,seed); index=0; paint() }
    fun onboarding() {
        removeSticky(); activeDeck=null; host.removeAllViews()
        host.addView(a.text("Какие из этих книг вам понравились?",24f,bold=true,serif=true)); a.space(host,8)
        val known=RecommendationEngine.positive(catalog,a.books,state)
        host.addView(a.text(if(known>0) "$known ${MainActivity.countBooks(known).substringAfter(' ')} из вашей библиотеки мы уже учли. Отметьте ещё несколько — с них начнётся подборка." else "Отметьте несколько книг, которые вам нравятся, — с них начнётся подборка.",15f,a.muted).apply { gravity=Gravity.CENTER; setPadding(a.d(10),a.d(30),a.d(10),a.d(30)); cssLeading(1.45f) }); a.space(host,22)
        val pages=RecommendationEngine.startPages(catalog,a.books,state); val subset=pages.getOrElse(starterPage%maxOf(1,pages.size)) { emptyList() }
        val go=a.button("Подобрать книги",true) { val merged=(state.optJSONArray("picks")?.strings().orEmpty()+selection).distinct(); state.put("picks",JSONArray(merged)).put("onboarded",true); selection.clear(); persist { rank() } }
        fun updateGo() { go.text="Подобрать книги"+if(selection.isNotEmpty()) " · ${selection.size}" else "" }
        subset.chunked(3).forEach { batch -> val row=a.line().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }; batch.forEachIndexed { i,b -> if(i>0) row.addView(View(a),LinearLayout.LayoutParams(a.d(14),1)); val cell=a.column().apply { clipChildren=false; clipToPadding=false }; val selectedCover=SelectedRecommendation(a,b,selection.contains(b.str("rec"))); cell.addView(selectedCover,LinearLayout.LayoutParams(-1,-2)); a.space(cell,7); cell.addView(a.text(b.str("title"),12.5f,a.muted).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END }); cell.contentDescription="${b.str("title")}, ${MainActivity.authors(b)}"; cell.isFocusable=true; cell.setOnClickListener { val id=b.str("rec"); if(!selection.add(id)) selection.remove(id); cell.isSelected=id in selection; selectedCover.choose(cell.isSelected); updateGo() }; cell.isSelected=b.str("rec") in selection; row.addView(cell,LinearLayout.LayoutParams(0,-2,1f)) }; host.addView(row); a.space(host,18) }
        host.addView(link("Показать другие") { starterPage++; onboarding() }); a.space(host,130)
        val bar=a.column().apply { setPadding(a.d(20),a.d(18),a.d(20),0); background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(a.bg and 0xffffff,a.bg,a.bg)) }
        bar.addView(go); bar.addView(link("Пропустить") { state.put("onboarded",true); persist { rank() } }); updateGo(); sticky=bar
        a.root.addView(bar,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        a.followInsets(bar){i->bar.setPadding(a.d(20)+i.left,a.d(18),a.d(20)+i.right,0);bar.layoutParams=(bar.layoutParams as FrameLayout.LayoutParams).apply{bottomMargin=a.d(66)+a.navigationBottom(i)}}
    }
    private fun link(label:String,fn:()->Unit)=a.text(label,16f,a.accent).apply { gravity=Gravity.CENTER; minHeight=a.d(44); setOnClickListener { fn() }; a.press(this) }
    private fun countText():String { val n=RecommendationEngine.positive(catalog,a.books,state); return if(n>0) "Подборка учитывает $n ${when { n%100 in 11..14 -> "книг"; n%10==1 -> "книгу"; n%10 in 2..4 -> "книги"; else -> "книг" }}" else "Отмечайте книги — подборка будет точнее" }
    fun paint() {
        removeSticky(); host.removeAllViews(); busy=false
        val head=a.line(); countLabel=a.text(countText(),14f,a.muted); head.addView(countLabel,LinearLayout.LayoutParams(0,-2,1f)); head.addView(a.icon("sliders","Настроить подборку") { tune() }.apply { background=null },LinearLayout.LayoutParams(a.d(44),a.d(44))); host.addView(head); a.space(host,12)
        val deck=FrameLayout(a).apply { clipChildren=false; clipToPadding=false }; activeDeck=deck; host.addView(deck,LinearLayout.LayoutParams(-1,a.d(420)))
        actions=null;undoButton=null
        a.followInsets(deck) { deck.post { sizeDeck() } }
        fill(true)
        deck.post { sizeDeck(); if(!state.optBoolean("coached") && list.isNotEmpty()) coach(deck) }
    }
    private fun sizeDeck() {
        val deck=activeDeck ?: return; if(!deck.isAttachedToWindow) return
        val pos=IntArray(2); deck.getLocationInWindow(pos); val rootPos=IntArray(2); a.root.getLocationInWindow(rootPos)
        val barTop=rootPos[1]+a.root.height-a.navigationBottom()-a.d(58); val available=barTop-pos[1]-0-a.d(34)
        val availableHeight=available.coerceIn(a.d(120),a.d(600));val width=minOf(((availableHeight-a.d(28))/1.5f).toInt(),(deck.width*.8f).toInt().coerceAtLeast(a.d(100)))
        val coverH=(width*1.5f).toInt();val height=coverH+a.d(28)
        if(deck.layoutParams.height!=height)deck.layoutParams=deck.layoutParams.apply { this.height=height }
        for(i in 0 until deck.childCount) { val child=deck.getChildAt(i); if(child is RecommendationCard && (child.layoutParams.width!=width || child.layoutParams.height!=coverH)) child.layoutParams=(child.layoutParams as FrameLayout.LayoutParams).apply { this.width=width; this.height=coverH } }
    }
    private fun fill(first:Boolean=false,undo:Undo?=null) {
        val deck=activeDeck ?: return; val old=(0 until deck.childCount).mapNotNull{deck.getChildAt(it) as? RecommendationCard}.associateBy{it.book.str("rec")}; pullClock?.cancel(); pulled=0f;
        val wanted=list.take(3).map{it.str("rec")}.toSet();for(i in deck.childCount-1 downTo 0){val c=deck.getChildAt(i);if(c !is RecommendationCard || c.book.str("rec") !in wanted)deck.removeViewAt(i)}; countLabel?.text=countText(); undoButton?.let { it.isEnabled=history.isNotEmpty(); it.alpha=if(it.isEnabled) 1f else .35f }
        if(list.isEmpty()) { deck.removeAllViews();val done=a.column().apply { gravity=Gravity.CENTER; setPadding(a.d(28),a.d(28),a.d(28),a.d(28)) }; done.addView(a.text("На сегодня всё",21f,bold=true,serif=true).apply { gravity=Gravity.CENTER }); a.space(done,8); done.addView(a.text("Вы посмотрели все книги, которые мы смогли подобрать. Добавляйте и оценивайте книги — появятся новые.",14f,a.muted).apply { gravity=Gravity.CENTER }); deck.addView(done,FrameLayout.LayoutParams(-1,-1)); return }
        for(depth in minOf(2,list.lastIndex) downTo 0) {
            val book=list[depth]; val reused=old[book.str("rec")]; val card=reused ?: RecommendationCard(a,book); card.animate().cancel(); card.animate().setStartDelay(0).setUpdateListener(null); card.stamp(null,0f); card.pivotY=card.height.takeIf{it>0}?.toFloat() ?: a.d(420).toFloat(); card.translationX=0f; card.rotation=0f; card.alpha=1f; if(reused==null || first || rm()){card.scaleX=1-depth*.05f;card.scaleY=1-depth*.05f;card.translationY=a.d(depth*12).toFloat()}else{val from=card.colorDepth;card.animate().setUpdateListener{card.setColorFilterDepth(from+(depth-from)*it.animatedFraction)}.scaleX(1-depth*.05f).scaleY(1-depth*.05f).translationY(a.d(depth*12).toFloat()).setDuration(500).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start()}
            if(reused==null || first || rm())card.setColorFilterDepth(depth)
            if(card.parent==null){val h=deck.layoutParams.height-a.d(28);val w=minOf((h/1.5f).toInt(),(deck.width*.8f).toInt().coerceAtLeast(a.d(100)));deck.addView(card,0,FrameLayout.LayoutParams(w,h,Gravity.TOP or Gravity.CENTER_HORIZONTAL))}
            card.bringToFront(); 
            if(depth==0) attachDrag(card,book)
            if(!rm()) { if(undo!=null && depth==0) { card.translationX=if(undo.action=="want") a.root.width.toFloat() else if(undo.action=="nope") -a.root.width.toFloat() else 0f; card.translationY=if(undo.action=="read") -a.root.height.toFloat() else a.d(40).toFloat(); card.rotation=if(undo.action=="want") 20f else if(undo.action=="nope") -20f else 0f; card.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(520).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start() }
                else if(first) { card.alpha=0f; card.translationY+=a.d(40+depth*10); card.animate().alpha(1f).translationY(a.d(depth*12).toFloat()).setDuration(560).setStartDelay(depth*60L).setInterpolator(PathInterpolator(.22f,1f,.36f,1f)).start() }
            }
        }; deck.post { sizeDeck() }
    }
    private fun pull(amount:Float) {
        val deck=activeDeck ?: return; pullClock?.cancel()
        fun apply(value:Float){pulled=value;for(i in 0 until deck.childCount-1){val child=deck.getChildAt(i);val depth=deck.childCount-1-i;val d=(depth-value).coerceAtLeast(0f);child.translationY=a.d(12)*d;child.scaleX=1-d*.05f;child.scaleY=1-d*.05f;(child as? RecommendationCard)?.setColorFilterDepth(d)}}
        if(rm())apply(amount)else{pullClock=android.animation.ValueAnimator.ofFloat(pulled,amount).apply{duration=500;interpolator=PathInterpolator(.22f,1f,.36f,1f);addUpdateListener{apply(it.animatedValue as Float)};start()}}
    }
    private fun attachDrag(card:RecommendationCard,book:JSONObject) {
        var dx=0f; var dy=0f; var downX=0f; var downY=0f; var moved=false; var side=1f; var start=0L; val samples=java.util.ArrayDeque<Triple<Float,Float,Long>>()
        card.isFocusable=true; card.contentDescription="${book.str("title")}, ${MainActivity.authors(book)}. Нажмите, чтобы открыть описание"; card.setOnClickListener { if(!busy) expanded(book,card) }
        card.setOnTouchListener { v,e -> if(busy) return@setOnTouchListener true
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.parent.requestDisallowInterceptTouchEvent(true); v.animate().cancel(); downX=e.rawX; downY=e.rawY; dx=0f; dy=0f; moved=false; side=if(e.y<v.height/2) 1f else -1f; start=e.eventTime; samples.clear(); samples.add(Triple(e.rawX,e.rawY,e.eventTime)); true }
                MotionEvent.ACTION_MOVE -> { samples.add(Triple(e.rawX,e.rawY,e.eventTime));while(samples.size>5)samples.removeFirst(); dx=e.rawX-downX; dy=e.rawY-downY; if(dy>0) dy*=.35f; if(hypot(dx,dy)>a.d(7)) moved=true; if(moved) { v.parent.requestDisallowInterceptTouchEvent(true); v.translationX=dx; v.translationY=dy; v.rotation=dx/a.resources.displayMetrics.density*.06f*side; card.stamp(direction(dx,dy),progress(direction(dx,dy),dx,dy)); pull((hypot(dx,dy)/a.d(150)).coerceIn(0f,1f)) }; true }
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { v.parent.requestDisallowInterceptTouchEvent(false); val first=samples.first();val last=samples.last();val dt=maxOf(16L,last.third-first.third).toFloat();val vx=(last.first-first.first)/dt/a.resources.displayMetrics.density;val vy=(last.second-first.second)/dt/a.resources.displayMetrics.density
                    val dir=direction(dx,dy); val p=progress(dir,dx,dy); val fling=when(dir) { "want"->vx>.6f;"nope"->vx < -.6f;"read"->vy < -.6f;else->false }
                    if(e.actionMasked==MotionEvent.ACTION_UP && moved && dir!=null && (p>=1 || fling && p>.25f)) fly(v,dir,vx,vy,side,true)
                    else { if(e.actionMasked==MotionEvent.ACTION_UP && !moved && e.eventTime-start<500) {v.animate().cancel();v.translationX=0f;v.translationY=0f;v.rotation=0f;v.performClick()} else settle(v) }; true }
                else->false
            }
        }
        card.setOnKeyListener { _,key,event -> if(event.action!=KeyEvent.ACTION_UP || busy) false else { when(key) { KeyEvent.KEYCODE_DPAD_RIGHT ->fly(card,"want");KeyEvent.KEYCODE_DPAD_LEFT->fly(card,"nope");KeyEvent.KEYCODE_DPAD_UP->fly(card,"read");KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_CENTER->card.performClick();else->return@setOnKeyListener false }; true } }
    }
    private fun direction(dx:Float,dy:Float):String?=when { -dy>a.d(24) && -dy>abs(dx)*1.15 ->"read";dx>a.d(16)->"want";dx < -a.d(16)->"nope";else->null }
    private fun progress(dir:String?,dx:Float,dy:Float):Float=(when(dir) { "read"->-dy;"want"->dx;"nope"->-dx;else->0f }/a.d(100)).coerceIn(0f,1f)
    fun settle(v:View) { (v as? RecommendationCard)?.stamp(null,0f); pull(0f); v.animate().translationX(0f).translationY(0f).rotation(0f).setDuration(if(rm()) 0 else 480).setInterpolator(PathInterpolator(.3f,1.35f,.5f,1f)).start() }
    fun fly(v:View,action:String,vx:Float=0f,vy:Float=0f,side:Float=1f,drag:Boolean=false) {
        if(busy || list.isEmpty()) return; busy=true; (v as? RecommendationCard)?.stamp(action,1f); pull(1f)
        var animationDone=rm(); var ratingDone=action!="read"; var rating=0; var committed=false
        fun finish() { if(animationDone && ratingDone && !committed) { committed=true; vote(action,rating) } }
        if(!rm()) {
            val density=a.resources.displayMetrics.density
            val flight=RecommendationFlight(a.root.width/density,a.root.height/density,action,v.translationX/density,v.translationY/density,vx,vy,side,drag)
            flightClock?.cancel()
            flightClock=android.animation.ValueAnimator.ofFloat(0f,1f).apply {
                duration=flight.duration;interpolator=PathInterpolator(if(drag).2f else .45f,if(drag).7f else 0f,if(drag).4f else .6f,1f)
                addUpdateListener{val pose=flight.sample(it.animatedValue as Float);v.translationX=pose.x*density;v.translationY=pose.y*density;v.rotation=pose.rotation}
                addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false;override fun onAnimationCancel(anim:android.animation.Animator){cancelled=true};override fun onAnimationEnd(anim:android.animation.Animator){if(!cancelled){animationDone=true;finish()}}})
                start()
            }
        }
        if(action=="read") rate(list.first()) { rating=it; ratingDone=true; finish() } else finish()
    }
    private fun rate(book:JSONObject,done:(Int)->Unit) {
        val box=a.column().apply { gravity=Gravity.CENTER_HORIZONTAL; setPadding(a.d(4),a.d(6),a.d(4),a.d(6)) }; box.addView(CoverView(a,book),LinearLayout.LayoutParams(a.d(74),a.d(111))); a.space(box,16); box.addView(a.text("Как вам «${book.str("title") }»?",21f,bold=true,serif=true).apply { gravity=Gravity.CENTER }); a.space(box,6); box.addView(a.text("Оценка поможет точнее подбирать книги",14f,a.muted).apply { gravity=Gravity.CENTER; setPadding(a.d(10),a.d(30),a.d(10),a.d(30)) }); a.space(box,14)
        val label=a.text("Понравилась — будет больше похожих",13f,a.muted).apply { gravity=Gravity.CENTER }; var value=0; var dialog:AlertDialog?=null; var finished=false
        fun complete(v:Int) { if(finished) return; finished=true; dialog?.dismiss(); done(v) }
        val go=a.button("Готово",true) { if(value>0) complete(value) }.apply { isEnabled=false; alpha=.4f }
        box.addView(NativeRating(a,0,false) { value=it; label.text=if(it>=4) "Понравилась — будет больше похожих" else if(it==3) "Так себе — похожих станет чуть больше" else "Не понравилась — похожих станет меньше"; go.isEnabled=true; go.alpha=1f },LinearLayout.LayoutParams(a.d(270),a.d(52))); a.space(box,4); box.addView(label); a.space(box,18); box.addView(go,LinearLayout.LayoutParams(-1,-2)); box.addView(link("Без оценки") { complete(0) },LinearLayout.LayoutParams(-1,-2))
        dialog=a.dialog().setTitle("").setView(box).show(); dialog.setOnDismissListener { if(!finished) complete(0) }
    }
    fun vote(action:String,rating:Int) {
        val before=state.copyJson(); val book=list.firstOrNull() ?: return; val sw=state.optJSONObject("sw") ?: JSONObject().also { state.put("sw",it) }; val previous=sw.optJSONObject(book.str("rec"))?.copyJson(); sw.put(book.str("rec"),JSONObject().put("a",action).put("r",rating).put("t",System.currentTimeMillis())); state.put("onboarded",true); val copy=state.copyJson(); var added:String?=null
        a.work({ a.library.db.runInTransaction { a.library.dao.putMeta(MetaRow("recs",JSONObject().put("id","recs").put("data",copy).toString())); if(action=="want" && a.library.allBooks().none { it.str("rec")==book.str("rec") || RecommendationEngine.sameBook(it,book) }) { val b=book.copyJson().put("id",uid()).put("shelf",REC_SHELF).put("origin","recommendation").put("noFile",true).put("genres",JSONArray()).put("lang","ru").put("openedAt",0).put("pos",minOf(0.0,a.books.minOfOrNull { it.optDouble("pos",0.0) } ?: 0.0)-1); a.library.save(b); added=b.str("id"); a.library.updateMarker() }; }; a.loadData() }, failed={ replaceJson(state,before); busy=false; fill() }) { history.addLast(Undo(book,previous,added,action)); if(history.size>30) history.removeFirst(); val keep=list.getOrNull(1)?.str("rec"); list=RecommendationEngine.rank(catalog,a.books,emptySet(),state,seed); if(keep!=null) { val k=list.indexOfFirst { it.str("rec")==keep }; if(k>0) list=list.toMutableList().apply { add(0,removeAt(k)) } }; index=0; busy=false; a.checkpointSoon(); fill() }
    }
    fun undo() {
        if(history.isEmpty() || busy) return; busy=true; val before=state.copyJson(); val u=history.peekLast()!!; val sw=state.optJSONObject("sw") ?: JSONObject(); if(u.previous==null) sw.remove(u.book.str("rec")) else sw.put(u.book.str("rec"),u.previous); state.put("sw",sw); val copy=state.copyJson()
        a.work({ a.library.db.runInTransaction { a.library.dao.putMeta(MetaRow("recs",JSONObject().put("id","recs").put("data",copy).toString())); u.addedId?.let { id -> val b=a.library.dao.book(id)?.let { JSONObject(it.json) }; if(b?.optBoolean("noFile")==true) { a.library.dao.deleteBook(id); a.library.updateMarker() } } }; a.loadData() }, failed={ replaceJson(state,before); busy=false; fill() }) { history.removeLast(); list=listOf(u.book)+RecommendationEngine.rank(catalog,a.books,emptySet(),state,seed).filterNot { it.str("rec")==u.book.str("rec") }; index=0; busy=false; fill(undo=u); a.checkpointSoon() }
    }
    fun expanded(book:JSONObject,card:View) { if(busy) return; busy=true;card.animate().cancel();card.alpha=1f;card.translationX=0f;card.translationY=0f;card.rotation=0f;pull(0f);RecommendationExpansion(a,book,card) { busy=false }.show() }
    private fun coach(deck:FrameLayout) {
        val box=a.column().apply { background=a.controlSurface(this,26f,sheet=true); gravity=Gravity.CENTER; setPadding(a.d(22),a.d(26),a.d(22),a.d(22)) }
        box.addView(a.text("↑  Уже читал",16f,0xff8fd3b0.toInt(),true)); a.space(box,32); box.addView(a.text("←  Не то        Хочу прочитать  →",15f,a.fg,true).apply { gravity=Gravity.CENTER }); a.space(box,32); box.addView(a.text("Нажмите на карточку — откроются описание и подробности",14f,a.muted).apply { gravity=Gravity.CENTER }); a.space(box,18); box.addView(a.button("Понятно",true) { state.put("coached",true); persist(); if(rm()) deck.removeView(box) else box.animate().alpha(0f).setDuration(220).withEndAction { deck.removeView(box) }.start() }); deck.addView(box,FrameLayout.LayoutParams(-1,-1))
    }
    fun tune() {
        val hidden=state.optJSONObject("sw")?.let { sw -> sw.keys().asSequence().count { sw.optJSONObject(it)?.str("a")=="nope" } } ?: 0
        val options=mutableListOf("Отметить любимые книги"); if(hidden>0) options.add("Вернуть скрытые книги · $hidden"); options.add("Начать заново")
        val box=a.column(); var sheet:AlertDialog?=null
        options.forEachIndexed { i,label -> box.addView(a.button(label) { sheet?.dismiss(); when { i==0 -> { selection.clear(); onboarding() }; label.startsWith("Вернуть") -> { val sw=state.optJSONObject("sw") ?: JSONObject(); sw.keys().asSequence().toList().filter { sw.optJSONObject(it)?.str("a")=="nope" }.forEach { sw.remove(it) }; persist { rank() } }; else ->a.confirm("Начать подборку заново?","Отметки «Не то», «Читал» и выбранные любимые книги забудутся. Книги на полках останутся.") { state.put("sw",JSONObject()).put("picks",JSONArray()).put("onboarded",false); selection.clear(); history.clear(); persist { onboarding() } } } }); a.space(box,8) }
        val lib=a.books.filter { !it.optBoolean("sample") && !it.optBoolean("noFile") }; if(lib.isNotEmpty()) { a.space(box,14); box.addView(a.text("Мы узнали ${lib.count { RecommendationEngine.match(catalog,it)!=null }} из ${lib.size} книг вашей библиотеки. Остальных пока нет в каталоге — по ним подборка не строится.",13f,a.muted)) }
        a.space(box,14); box.addView(a.text("В каталоге ${catalog.getJSONArray("b").length()} книг. Связи между ними посчитаны по открытым оценкам читателей (Goodbooks-10k, CC BY-SA 4.0). Подборка считается на вашем устройстве — ничего никуда не отправляется.",13f,a.muted)); sheet=a.dialog().setTitle("Подборка").setView(box).show()
    }
}
