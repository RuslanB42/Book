package app.polka.nativeapp

import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.widget.TextView

/** Extra width for every space of one line; read at draw and measure time only, so line breaking is untouched. */
internal class LineStretch(val extra:Float):CharacterStyle(){
    override fun updateDrawState(tp:TextPaint){if(android.os.Build.VERSION.SDK_INT>=29)tp.wordSpacing+=extra}
}

/**
 * Word spacing as a wider space glyph. The paint's own word spacing is drawn but not counted by the line breaker,
 * so lines broke as if the spaces were narrow and then ran past the right edge. A horizontal scale on each space is
 * counted everywhere — line breaking, measuring, drawing, page pictures — so the lines simply hold fewer words.
 */
internal class WordGap(private val scale:Float):android.text.style.MetricAffectingSpan(){
    override fun updateMeasureState(tp:TextPaint){tp.textScaleX*=scale}
    override fun updateDrawState(tp:TextPaint){tp.textScaleX*=scale}
    companion object{
        /** How much wider a space must be for an extra [ws] (share of the text size) at this paint. */
        fun scale(paint:TextPaint,ws:Float):Float{if(ws==0f)return 1f;val space=paint.measureText(" ").coerceAtLeast(.5f);return ((space+ws*paint.textSize)/space).coerceIn(.3f,6f)}
        fun apply(t:android.text.Spannable,scale:Float){if(scale==1f)return
            for(i in 0 until t.length)if(t[i]==' ')t.setSpan(WordGap(scale),i,i+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)}
    }
}

/**
 * Android's own inter-word justification resets the paint's letter and word spacing on the lines it stretches
 * (the lines are broken with the spacing but drawn without it: they overflow or fall short of the right edge).
 * So the layout is left unjustified and each line is stretched here instead:
 * its spaces get exactly the width that brings the line to the right edge, with the spacing kept.
 */
internal object SpacedJustify {
    // Justified text is always stretched here (Android 10+): drawing, page pictures and selection geometry then follow
    // one formula on every phone, instead of each Android build's own idea of where justified words are.
    fun needed(a:MainActivity)=android.os.Build.VERSION.SDK_INT>=29&&a.prefs.optBoolean("justify",true)
    fun clear(t:Spannable){runCatching{t.getSpans(0,t.length,LineStretch::class.java).forEach{t.removeSpan(it)}}}
    fun apply(tv:TextView){val l=tv.layout?:return
        // the spans go on the text the layout draws (a TextView may lay out its own copy of what it was given)
        val t=(l.text as? Spannable)?:(tv.text as? Spannable)?.takeIf{it===l.text}?:return
        try{apply(l,t)}catch(e:Exception){lastError=e.toString();android.util.Log.w("Polka","line stretch skipped",e);clear(t)}}
    @Volatile internal var lastError:String?=null
    private var probedFor=Float.NaN;private var probed=true
    /** Whether Layout's line measurement leaves the paint's word spacing out (it differs between Android builds). */
    private fun countsWordSpacing(p:android.text.TextPaint):Boolean{val ws=p.wordSpacing;if(ws==0f)return true
        if(ws==probedFor)return probed
        val with=android.text.TextPaint(p);val without=android.text.TextPaint(p).apply{wordSpacing=0f}
        fun width(tp:android.text.TextPaint)=android.text.StaticLayout.Builder.obtain("a b c d",0,7,tp,100000).build().getLineRight(0)
        probed=width(with)-width(without)>ws*1.5f;probedFor=ws;return probed}
    private var uncounted=false;private var ws=0f
    private fun apply(l:Layout,t:Spannable){
        clear(t)
        ws=if(android.os.Build.VERSION.SDK_INT>=29)l.paint.wordSpacing else 0f
        uncounted=ws!=0f&&!countsWordSpacing(l.paint)
        for(line in 0 until l.lineCount){
            val s=l.getLineStart(line);val e=l.getLineEnd(line).coerceAtMost(t.length)
            if(e>=t.length||e<=s||t[e-1]=='\n')continue // a paragraph's last line stays as it is
            var vis=e;while(vis>s&&t[vis-1].isWhitespace())vis--
            var spaces=0;for(i in s until vis)if(t[i]==' '||t[i]==' ')spaces++
            if(spaces==0)continue
            // Layout measures a line without the paint's word spacing while it is drawn with it: take that share off,
            // or every justified line would run past the right edge by spaces × word spacing.
            val gap=l.getParagraphRight(line)-l.getLineRight(line)-if(uncounted)spaces*ws else 0f
            // negative too: a line that came out wider than the column (spacing the breaker did not count) is tightened back
            if(gap.isNaN()||kotlin.math.abs(gap)<=.5f||(!uncounted&&gap<=.5f))continue
            t.setSpan(LineStretch(gap/spaces),s,vis,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
