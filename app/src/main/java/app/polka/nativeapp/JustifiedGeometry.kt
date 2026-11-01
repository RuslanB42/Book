package app.polka.nativeapp

import android.graphics.Path
import android.text.Layout
import android.widget.TextView

/**
 * Android draws inter-word justified text with widened spaces, but Layout's positions (getPrimaryHorizontal,
 * getOffsetForHorizontal, getSelectionPath) are those of the unjustified line. Selection marks and hit-testing built
 * on them drift left on justified lines. These helpers add the widening back, so marks sit exactly on the drawn words.
 * When the platform already reports justified positions the measured extra is zero and nothing changes.
 */
internal object JustifiedGeometry {
    private fun justified(tv:TextView,l:Layout,line:Int):Boolean{
        if(tv.justificationMode==Layout.JUSTIFICATION_MODE_NONE)return false
        val t=l.text;val end=l.getLineEnd(line);if(end>=t.length||t[end-1]=='\n')return false
        return l.getParagraphAlignment(line)==Layout.Alignment.ALIGN_NORMAL
    }
    private fun visibleEnd(l:Layout,line:Int):Int{val t=l.text;val start=l.getLineStart(line);var v=l.getLineEnd(line);while(v>start&&t[v-1].isWhitespace())v--;return v}
    /** Unjustified x of [o] on [line], valid also for the offset right after the line's last character. */
    private fun natural(tv:TextView,l:Layout,line:Int,o:Int):Float{
        val start=l.getLineStart(line);val end=l.getLineEnd(line)
        return if(o<end||o<=start)l.getPrimaryHorizontal(o.coerceAtMost(maxOf(start,end-1)).let{if(o<end)o else it}) else l.getPrimaryHorizontal(o-1)+tv.paint.measureText(l.text,o-1,o)
    }
    private fun extraPerSpace(tv:TextView,l:Layout,line:Int):Pair<Float,Int>{
        if(!justified(tv,l,line))return 0f to 0
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);if(vis<=start)return 0f to 0
        var spaces=0;for(i in start until vis)if(t[i]==' ')spaces++;if(spaces==0)return 0f to 0
        var right=l.getPrimaryHorizontal(vis-1)+tv.paint.measureText(t,vis-1,vis)
        val end=l.getLineEnd(line);if(vis==end&&end<t.length&&t[end-1].isLetter()&&t[end].isLetter())right+=tv.paint.measureText("-")
        val extra=(l.getParagraphRight(line)-right)/spaces
        return if(extra>.5f)extra to start else 0f to 0
    }
    /** Drawn x (layout coordinates) of the caret position [o] on [line]. */
    fun x(tv:TextView,l:Layout,line:Int,o:Int):Float{
        val base=natural(tv,l,line,o);val (extra,start)=extraPerSpace(tv,l,line);if(extra==0f)return base
        val t=l.text;val upto=minOf(o,visibleEnd(l,line));var n=0;for(i in start until upto)if(t[i]==' ')n++
        return base+extra*n
    }
    fun x(tv:TextView,l:Layout,o:Int):Float{val line=l.getLineForOffset(o.coerceIn(0,l.text.length));return x(tv,l,line,o)}
    /** Offset whose drawn position is nearest to [px] on [line]. */
    fun offset(tv:TextView,l:Layout,line:Int,px:Float):Int{
        val start=l.getLineStart(line);val vis=visibleEnd(l,line)
        if(extraPerSpace(tv,l,line).first==0f)return l.getOffsetForHorizontal(line,px)
        var best=start;var dist=Float.MAX_VALUE
        for(o in start..vis){val d=kotlin.math.abs(x(tv,l,line,o)-px);if(d<dist){dist=d;best=o}}
        return best
    }
    /** Ink band of a line: from just above the letters to just below them (not the paragraph spacing). */
    fun band(tv:TextView,l:Layout,line:Int):Pair<Float,Float>{val fm=tv.paint.fontMetrics;val b=l.getLineBaseline(line).toFloat();return (b+fm.ascent*1.02f) to (b+fm.descent*1.25f)}
    /** Highlight shape for [s,e) with each line's piece exactly over the drawn words. */
    fun path(tv:TextView,l:Layout,s:Int,e:Int,out:Path,radius:Float){
        out.rewind();if(e<=s)return
        val first=l.getLineForOffset(s);val last=l.getLineForOffset(maxOf(s,e-1))
        for(line in first..last){val ls=maxOf(s,l.getLineStart(line));val le=minOf(e,visibleEnd(l,line).coerceAtLeast(ls))
            val left=x(tv,l,line,ls);var right=x(tv,l,line,le);if(line<last&&le>=visibleEnd(l,line)&&extraPerSpace(tv,l,line).first>0f)right=l.getParagraphRight(line).toFloat()
            if(right-left<1f)continue
            val (top,bottom)=band(tv,l,line);out.addRoundRect(left-radius*.4f,top,right+radius*.4f,bottom,radius,radius,Path.Direction.CW)}
    }
}
