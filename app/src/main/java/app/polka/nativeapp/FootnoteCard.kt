package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import org.jsoup.nodes.Element

/**
 * A footnote opened where it is read (approved «Карточка у знака»): a card right under the line with the mark (over
 * it when there is no room below), a small tail pointing at the mark, the note in the book's own type, and «Все
 * примечания» to go to the notes. A tap beside it or «Назад» closes it. A note too long for a card opens in a sheet.
 */
internal class FootnoteCard(private val a:MainActivity,private val host:FrameLayout,private val page:Int,private val ink:Int,private val face:Typeface?,private val sizePx:Float,
                            private val label:String,private val text:CharSequence,private val at:FloatArray?,private val closed:()->Unit,private val all:()->Unit){
    private var layer:FrameLayout?=null
    private val motion get()=a.prefs.str("motion")!="reduced"

    fun show(){
        val surface=blend(page,ink,.075f)
        val cardW=host.width-a.d(32)
        val card=a.column().apply{setPadding(a.d(18),a.d(14),a.d(18),a.d(6))
            a.overSurface(this,surface,20);elevation=a.d(16).toFloat();isClickable=true}
        if(label.isNotBlank())card.addView(TextView(a).apply{this.text=label;textSize=11.5f;letterSpacing=.12f;typeface=PolkaFonts.sans(600);setTextColor(fade(ink,.5f))},LinearLayout.LayoutParams(-2,-2).apply{bottomMargin=a.d(6)})
        val words=TextView(a).apply{this.text=this@FootnoteCard.text;setTextColor(ink);typeface=face;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizePx);setLineSpacing(0f,1.08f)
            movementMethod=android.text.method.LinkMovementMethod.getInstance();setTextIsSelectable(false)}
        val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(words)}
        card.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        card.addView(TextView(a).apply{this.text="Все примечания";textSize=14f;typeface=PolkaFonts.sans(500);setTextColor(NoteMark.color(ink));gravity=Gravity.CENTER_VERTICAL
            isClickable=true;setOnClickListener{close(true);all()}},LinearLayout.LayoutParams(-2,a.d(40)).apply{topMargin=a.d(4)})
        // how tall it would be: a long note goes to a sheet instead of covering half the page
        card.measure(View.MeasureSpec.makeMeasureSpec(cardW,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val h=card.measuredHeight;val p=at
        val below=p!=null&&p[2]+a.d(12)+h<=host.height-a.d(24);val above=p!=null&&p[1]-a.d(12)-h>=a.d(24)
        if(p==null||h>host.height*.5f||(!below&&!above)){sheet();closed();return}

        val l=FrameLayout(a).apply{isClickable=true;setOnClickListener{close(true)}}
        val top=if(below)p[2]+a.d(12) else p[1]-a.d(12)-h
        val tailX=p[0].coerceIn(a.d(36).toFloat(),host.width-a.d(36).toFloat())
        // the tail lies over the card's shade (added after it), so the shade never darkens it
        val tail=View(a).apply{background=GradientDrawable().apply{setColor(surface);cornerRadius=a.d(3).toFloat()};rotation=45f;elevation=a.d(16).toFloat();noNativeShadow(this)}
        val t=a.d(16)
        l.addView(card,FrameLayout.LayoutParams(cardW,h).apply{leftMargin=a.d(16);topMargin=top.toInt()})
        l.addView(tail,FrameLayout.LayoutParams(t,t).apply{leftMargin=(tailX-t/2f).toInt();topMargin=(if(below)top-t/2f else top+h-t/2f).toInt()})
        host.addView(l,FrameLayout.LayoutParams(-1,-1));layer=l
        // it grows out of the mark (see MotionSpec, asks)
        if(motion)listOf(card,tail).forEach{v->v.alpha=0f;v.scaleX=.96f;v.scaleY=.96f
            v.pivotX=if(v===card)tailX-a.d(16) else t/2f;v.pivotY=if(v===card)(if(below)0f else h.toFloat()) else t/2f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(MotionSpec.ASK_IN).setInterpolator(MotionSpec.enter).start()}
    }
    /** Closes the card ([animated] or at once, when another one takes its place). */
    fun close(animated:Boolean){val l=layer?:return;layer=null;closed()
        if(!animated||!motion){host.removeView(l);return}
        for(i in 0 until l.childCount)l.getChildAt(i).animate().alpha(0f).scaleX(.97f).scaleY(.97f).setDuration(MotionSpec.ASK_OUT).setInterpolator(MotionSpec.exit).start()
        l.postDelayed({host.removeView(l)},MotionSpec.ASK_OUT+20)}

    private fun sheet(){val words=TextView(a).apply{this.text=this@FootnoteCard.text;setTextColor(a.fg);typeface=face;setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,sizePx);setLineSpacing(0f,1.08f)
            movementMethod=android.text.method.LinkMovementMethod.getInstance();setPadding(a.d(22),0,a.d(22),a.d(8))}
        a.dialog().setTitle(if(label.isNotBlank())"Примечание $label" else "Примечание").setView(ScrollView(a).apply{addView(words)}).setPositiveButton("Все примечания"){_,_->all()}.show()}

    companion object{
        private val mark=Regex("^\\s*[\\[({]?\\s*(\\d{1,4}|[*†‡§]{1,3})\\s*[\\])}.]?\\s*$")
        /** The note's number (when it has one) and its words without it: a heading «1», a leading back-link «1» or
         *  «[1]», a leading «1.» — none of them repeated in the card. */
        fun noteText(el:Element):Pair<String,String>{val e=el.clone();var label=""
            e.children().firstOrNull()?.let{f->if((f.tagName().matches(Regex("h[1-6]"))||f.tagName()=="title"||f.hasClass("title"))&&mark.matches(f.text())){label=f.text().filter{it.isLetterOrDigit()||it in "*†‡§"};f.remove()}}
            // a link back to the text at its very start (EPUB notes)
            e.selectFirst("a[href]")?.let{ln->if(mark.matches(ln.text())&&e.text().trim().startsWith(ln.text().trim())){if(label.isBlank())label=ln.text().filter{it.isLetterOrDigit()||it in "*†‡§"};ln.remove()}}
            // «1. Слово…» at the start of the first paragraph
            e.selectFirst("p")?.let{p0->val first=p0.textNodes().firstOrNull();val m=first?.text()?.let{Regex("^\\s*[\\[(]?(\\d{1,4}|[*†‡§]{1,3})[\\])]?[.)]?\\s+").find(it)}
                if(first!=null&&m!=null){if(label.isBlank())label=m.groupValues[1];first.text(first.text().substring(m.range.last+1))}}
            return label to e.outerHtml()}
        private fun blend(a:Int,b:Int,k:Float)=Color.rgb((Color.red(a)+(Color.red(b)-Color.red(a))*k).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*k).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*k).toInt())
        private fun fade(c:Int,k:Float)=(c and 0x00ffffff) or ((255*k).toInt() shl 24)
    }
}
