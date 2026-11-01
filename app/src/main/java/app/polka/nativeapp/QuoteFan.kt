package app.polka.nativeapp

import android.view.*
import android.widget.*
import org.json.JSONObject

/**
 * A book's quotes on the «Цитаты» tab: the pile opens in place (cards fan out of it, three at a time with a pager)
 * and folds back with «Свернуть» — the same motion as on the book card, without leaving the page.
 */
internal class QuoteFan(private val a:MainActivity,private val book:JSONObject,private val quotes:List<JSONObject>):LinearLayout(a){
    private val reveal=DetailReveal(a)
    private val body=reveal.body.apply{clipChildren=false;clipToPadding=false}
    private var open=book.str("id") in a.openQuoteStacks
    private var page=0
    private var folding=false
    private fun word(n:Int)=if(n%100 in 11..14)"цитат" else when(n%10){1->"цитата";2,3,4->"цитаты";else->"цитат"}
    private fun reduced()=a.prefs.str("motion")=="reduced"
    init{orientation=VERTICAL;clipChildren=false;clipToPadding=false;addView(reveal,LayoutParams(-1,-2));render()}
    private var shown=listOf<View>()
    private fun render(animated:Boolean=false,unfold:Boolean=false,slide:Int=0){
        folding=false;val cards=mutableListOf<View>();val extras=mutableListOf<View>()
        val fan=unfold&&animated&&!reduced()
        val sliding=slide!=0&&animated&&!reduced()
        reveal.change(animated,dissolveInk=!unfold&&!sliding,crossfadeInk=fan,duration=if(fan)580L else if(sliding)420L else 360L,clip=!fan&&!sliding,curve=if(fan)android.view.animation.PathInterpolator(.32f,.06f,.16f,1f) else MotionSpec.settle){
            body.removeAllViews()
            if(!open){body.addView(a.quoteStack(book,quotes){open=true;a.openQuoteStacks.add(book.str("id"));render(true,true)}.apply{contentDescription="Показать цитаты: ${book.str("title")}"});return@change}
            // Where these quotes come from: the book, right above its cards (tap → the book's card).
            val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(2),0,a.d(2),a.d(14));isClickable=true;setOnClickListener{a.detail(book)};contentDescription="Цитаты из книги ${book.str("title")}"}
            head.addView(CoverView(a,book),LayoutParams(a.d(30),a.d(45)))
            val meta=a.column().apply{setPadding(a.d(12),0,a.d(8),0)}
            meta.addView(a.text(book.str("title"),15f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            meta.addView(a.text(listOf(MainActivity.authors(book),"${quotes.size} ${word(quotes.size)}").filter{it.isNotBlank()}.joinToString(" · "),12.5f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            head.addView(meta,LayoutParams(0,-2,1f));head.addView(IconView(a,"next",a.muted),LayoutParams(a.d(18),a.d(18)))
            body.addView(head,LayoutParams(-1,-2));extras.add(head)
            val sorted=quotes.sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")})
            val pages=maxOf(1,(sorted.size+2)/3);page=page.coerceIn(0,pages-1)
            sorted.drop(page*3).take(3).forEach{q->val card=a.quoteCardNative(book,q);body.addView(card,LayoutParams(-1,-2).apply{bottomMargin=a.d(14)});cards.add(card)}
            val controls=a.column()
            fun round(label:String,turn:Float,enabled:Boolean,fn:()->Unit)=a.icon("back",label){fn()}.apply{background=a.shape(if(a.light)0xffefefec.toInt() else 0xff2c2c2f.toInt(),20);rotation=turn;isEnabled=enabled;alpha=if(enabled)1f else .35f}
            if(pages>1){val pager=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
                pager.addView(round("Предыдущие цитаты",0f,page>0){turn(-1)},LayoutParams(a.d(40),a.d(40)))
                pager.addView(PageDots(a,pages,page),LayoutParams(0,a.d(40),1f))
                pager.addView(round("Следующие цитаты",180f,page<pages-1){turn(1)},LayoutParams(a.d(40),a.d(40)))
                controls.addView(pager,LayoutParams(-1,-2).apply{bottomMargin=a.d(12)})}
            controls.addView(a.button("Свернуть"){collapse(cards)}.apply{contentDescription="Свернуть цитаты"},LayoutParams(-1,a.d(48)))
            body.addView(controls,LayoutParams(-1,-2).apply{topMargin=a.d(2)});extras.add(controls)
            shown=cards.toList()
        }
        if(sliding){val ease=android.view.animation.PathInterpolator(.2f,0f,0f,1f)
            cards.forEachIndexed{i,c->c.alpha=0f;c.translationX=slide*a.d(44).toFloat();c.animate().alpha(1f).translationX(0f).setStartDelay(i*60L).setDuration(420).setInterpolator(ease).start()}}
        if(fan&&cards.isNotEmpty()){
            val pile=a.d(9).toFloat();val inset=a.d(12).toFloat()
            cards.forEach{it.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
            val driver=object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
                val p=reveal.progress
                cards.forEachIndexed{i,c->if(c.width>0){val k=minOf(i,2);val t=((p-i*.07f)/(1f-.14f)).coerceIn(0f,1f)
                    c.pivotX=c.width/2f;c.pivotY=0f;c.translationY=(-c.top+pile*k)*(1f-t);val sx=(c.width-2*inset*k)/c.width;c.scaleX=sx+(1f-sx)*t;c.translationZ=-a.d(3)*k*(1f-t);c.alpha=if(i<3)1f else t}}
                extras.forEach{e->val t=((p-.55f)/.45f).coerceIn(0f,1f);e.alpha=t;e.translationY=a.d(10)*(1f-t)}
                if(p>=1f){cards.forEach{it.setLayerType(View.LAYER_TYPE_NONE,null)};if(body.viewTreeObserver.isAlive)body.viewTreeObserver.removeOnPreDrawListener(this)}
                return true}}
            body.viewTreeObserver.addOnPreDrawListener(driver)
        }
    }
    private var turning=false
    /** Page through: the current cards glide out toward the direction of travel, the next three glide in one after another. */
    private fun turn(dir:Int){
        if(turning||folding)return
        if(reduced()){page+=dir;render(true);return}
        turning=true;val ease=android.view.animation.PathInterpolator(.4f,0f,1f,1f)
        shown.forEachIndexed{i,c->c.animate().cancel();c.animate().alpha(0f).translationX(-dir*a.d(44).toFloat()).setStartDelay(i*35L).setDuration(200).setInterpolator(ease).start()}
        postDelayed({turning=false;page+=dir;render(true,slide=dir)},200L+shown.size*35L)
    }
    /** Cards slide up into the pile and fade, the block shrinks to the pile's height, the pile's picture fades in, then the real pile replaces it. */
    private fun collapse(cards:List<View>){
        if(folding)return
        a.openQuoteStacks.remove(book.str("id"))
        if(reduced()||cards.isEmpty()||!reveal.isLaidOut||body.width<=0){open=false;page=0;render(true);return}
        // The real pile (with its own soft shadows) fades in over the folding cards; a shadowless picture made the shadow blink.
        val w=body.width;val probe=a.quoteStack(book,quotes){};probe.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        probe.alpha=0f;reveal.addView(probe,android.widget.FrameLayout.LayoutParams(-1,-2));probe.layout(0,0,w,probe.measuredHeight)
        val pile=a.d(9).toFloat();val inset=a.d(12).toFloat();val extras=(0 until body.childCount).map{body.getChildAt(it)}.filter{it !in cards}
        folding=true;cards.forEach{it.animate().cancel();it.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
        reveal.animateHeight(probe.measuredHeight,440L,android.view.animation.PathInterpolator(.33f,0f,.2f,1f),{p->
            cards.forEachIndexed{i,c->if(c.width>0){val k=minOf(i,2);val back=cards.size-1-i;val q=((p-back*.05f)/(1f-.1f)).coerceIn(0f,1f)
                c.pivotX=c.width/2f;c.pivotY=0f;c.translationY=(-c.top+pile*k)*q;val sx=(c.width-2*inset*k)/c.width;c.scaleX=1f+(sx-1f)*q;c.translationZ=-a.d(3)*k*q
                c.alpha=1f-((q-.55f)/.45f).coerceIn(0f,1f)}}
            extras.forEach{e->e.alpha=(1f-p/.3f).coerceIn(0f,1f)}
            probe.alpha=((p-.4f)/.55f).coerceIn(0f,1f)
        }){cards.forEach{it.setLayerType(View.LAYER_TYPE_NONE,null)};open=false;page=0;render();reveal.removeView(probe)}
    }
}

/** Page dots: the current page is a longer pill. */
internal class PageDots(private val a:MainActivity,private val count:Int,private val current:Int):View(a){
    private val p=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    init{contentDescription="Страница ${current+1} из $count"}
    override fun onDraw(c:android.graphics.Canvas){val dot=a.d(6).toFloat();val wide=a.d(18).toFloat();val gap=a.d(6).toFloat()
        val total=(count-1)*(dot+gap)+wide;var x=(width-total)/2f;val cy=height/2f
        for(i in 0 until count){val w=if(i==current)wide else dot;p.color=if(i==current)a.accentFill else a.muted;p.alpha=if(i==current)255 else 110
            c.drawRoundRect(x,cy-dot/2,x+w,cy+dot/2,dot/2,dot/2,p);x+=w+gap}}
}
