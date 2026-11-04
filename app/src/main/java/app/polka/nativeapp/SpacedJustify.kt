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
    private fun apply(l:Layout,t:Spannable){
        clear(t)
        for(line in 0 until l.lineCount){
            val s=l.getLineStart(line);val e=l.getLineEnd(line).coerceAtMost(t.length)
            if(e>=t.length||e<=s||t[e-1]=='\n')continue // a paragraph's last line stays as it is
            var vis=e;while(vis>s&&t[vis-1].isWhitespace())vis--
            var spaces=0;for(i in s until vis)if(t[i]==' '||t[i]==' ')spaces++
            if(spaces==0)continue
            val gap=l.getParagraphRight(line)-l.getLineRight(line)
            if(gap<=.5f||gap.isNaN())continue
            t.setSpan(LineStretch(gap/spaces),s,vis,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
