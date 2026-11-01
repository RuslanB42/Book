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
    /** Platform x for unjustified lines (identical on every Android version), valid also right after the last character. */
    private fun natural(tv:TextView,l:Layout,line:Int,o:Int):Float{
        val start=l.getLineStart(line);val end=l.getLineEnd(line)
        return if(o<end||o<=start)l.getPrimaryHorizontal(o) else l.getPrimaryHorizontal(o-1)+tv.paint.measureText(l.text,o-1,o)
    }
    private fun spacesIn(t:CharSequence,from:Int,to:Int):Int{var n=0;for(i in from until to)if(t[i]==' ')n++;return n}
    /** Widening per space on a justified line, computed from text widths alone (not from platform positions, which
     *  include the widening on some Android versions and not on others). */
    private fun extraPerSpace(tv:TextView,l:Layout,line:Int):Float{
        if(!justified(tv,l,line))return 0f
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);if(vis<=start)return 0f
        val spaces=spacesIn(t,start,vis);if(spaces==0)return 0f
        var natural=Layout.getDesiredWidth(t,start,vis,tv.paint)
        val end=l.getLineEnd(line);if(vis==end&&end<t.length&&t[end-1].isLetter()&&t[end].isLetter())natural+=tv.paint.measureText("-")
        val left=l.getPrimaryHorizontal(start)
        return ((l.getParagraphRight(line)-left-natural)/spaces).coerceAtLeast(0f)
    }
    /** Drawn x (layout coordinates) of the caret position [o] on [line]. */
    fun x(tv:TextView,l:Layout,line:Int,o:Int):Float{
        val extra=extraPerSpace(tv,l,line);if(extra==0f)return natural(tv,l,line,o)
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);val upto=o.coerceIn(start,vis)
        return l.getPrimaryHorizontal(start)+Layout.getDesiredWidth(t,start,upto,tv.paint)+extra*spacesIn(t,start,upto)
    }
    fun x(tv:TextView,l:Layout,o:Int):Float{val line=l.getLineForOffset(o.coerceIn(0,l.text.length));return x(tv,l,line,o)}
    /** Offset whose drawn position is nearest to [px] on [line]. */
    fun offset(tv:TextView,l:Layout,line:Int,px:Float):Int{
        val start=l.getLineStart(line);val vis=visibleEnd(l,line)
        if(extraPerSpace(tv,l,line)==0f)return l.getOffsetForHorizontal(line,px)
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
            val left=x(tv,l,line,ls);var right=x(tv,l,line,le);if(line<last&&le>=visibleEnd(l,line)&&extraPerSpace(tv,l,line)>0f)right=l.getParagraphRight(line).toFloat()
            if(right-left<1f)continue
            val (top,bottom)=band(tv,l,line);out.addRoundRect(left-radius*.4f,top,right+radius*.4f,bottom,radius,radius,Path.Direction.CW)}
    }
}
