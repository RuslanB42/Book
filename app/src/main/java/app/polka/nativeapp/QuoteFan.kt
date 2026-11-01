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
    private fun reduced()=a.prefs.str("motion")=="reduced"
    init{orientation=VERTICAL;clipChildren=false;clipToPadding=false;addView(reveal,LayoutParams(-1,-2));render()}
    private fun render(animated:Boolean=false,unfold:Boolean=false){
        folding=false;val cards=mutableListOf<View>();val extras=mutableListOf<View>()
        val fan=unfold&&animated&&!reduced()
        reveal.change(animated,dissolveInk=!unfold,crossfadeInk=fan,duration=if(fan)580L else 360L,clip=!fan,curve=if(fan)android.view.animation.PathInterpolator(.32f,.06f,.16f,1f) else MotionSpec.settle){
            body.removeAllViews()
            if(!open){body.addView(a.quoteStack(book,quotes){open=true;a.openQuoteStacks.add(book.str("id"));render(true,true)}.apply{contentDescription="Показать цитаты: ${book.str("title")}"});return@change}
            val sorted=quotes.sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")})
            val pages=maxOf(1,(sorted.size+2)/3);page=page.coerceIn(0,pages-1)
            sorted.drop(page*3).take(3).forEach{q->val card=a.quoteCardNative(book,q);body.addView(card,LayoutParams(-1,-2).apply{bottomMargin=a.d(14)});cards.add(card)}
            val controls=a.column()
            fun round(label:String,turn:Float,enabled:Boolean,fn:()->Unit)=a.icon("back",label){fn()}.apply{background=a.shape(if(a.light)0xffefefec.toInt() else 0xff2c2c2f.toInt(),20);rotation=turn;isEnabled=enabled;alpha=if(enabled)1f else .35f}
            if(pages>1){val pager=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
                pager.addView(round("Предыдущие цитаты",0f,page>0){page--;render(true)},LayoutParams(a.d(40),a.d(40)))
                pager.addView(a.text("${page+1} из $pages",13f,a.muted).apply{gravity=Gravity.CENTER},LayoutParams(0,-2,1f))
                pager.addView(round("Следующие цитаты",180f,page<pages-1){page++;render(true)},LayoutParams(a.d(40),a.d(40)))
                controls.addView(pager,LayoutParams(-1,-2).apply{bottomMargin=a.d(12)})}
            controls.addView(a.button("Свернуть"){collapse(cards)}.apply{contentDescription="Свернуть цитаты"},LayoutParams(-1,a.d(48)))
            body.addView(controls,LayoutParams(-1,-2).apply{topMargin=a.d(2)});extras.add(controls)
        }
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
    /** Cards slide up into the pile and fade, the block shrinks to the pile's height, the pile's picture fades in, then the real pile replaces it. */
    private fun collapse(cards:List<View>){
        if(folding)return
        a.openQuoteStacks.remove(book.str("id"))
        if(reduced()||cards.isEmpty()||!reveal.isLaidOut||body.width<=0){open=false;page=0;render(true);return}
        val w=body.width;val probe=a.quoteStack(book,quotes){};probe.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));probe.layout(0,0,w,probe.measuredHeight)
        val picture=android.graphics.Bitmap.createBitmap(w,probe.measuredHeight.coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888);probe.draw(android.graphics.Canvas(picture))
        val pile=a.d(9).toFloat();val inset=a.d(12).toFloat();val extras=(0 until body.childCount).map{body.getChildAt(it)}.filter{it !in cards}
        folding=true;cards.forEach{it.animate().cancel();it.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
        reveal.overlay=picture;reveal.overlayAlpha=0f
        reveal.animateHeight(probe.measuredHeight,440L,android.view.animation.PathInterpolator(.33f,0f,.2f,1f),{p->
            cards.forEachIndexed{i,c->if(c.width>0){val k=minOf(i,2);val back=cards.size-1-i;val q=((p-back*.05f)/(1f-.1f)).coerceIn(0f,1f)
                c.pivotX=c.width/2f;c.pivotY=0f;c.translationY=(-c.top+pile*k)*q;val sx=(c.width-2*inset*k)/c.width;c.scaleX=1f+(sx-1f)*q;c.translationZ=-a.d(3)*k*q
                c.alpha=1f-((q-.55f)/.45f).coerceIn(0f,1f)}}
            extras.forEach{e->e.alpha=(1f-p/.3f).coerceIn(0f,1f)}
            reveal.overlayAlpha=((p-.45f)/.5f).coerceIn(0f,1f)
        }){cards.forEach{it.setLayerType(View.LAYER_TYPE_NONE,null)};open=false;page=0;render()}
    }
}
