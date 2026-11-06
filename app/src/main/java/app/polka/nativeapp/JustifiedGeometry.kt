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
    private fun spacesIn(t:CharSequence,from:Int,to:Int):Int{var n=0;for(i in from until to)if(t[i]==' '||t[i]=='\u00a0')n++;return n}
    /** Widening per space on a justified line, computed from text widths alone (not from platform positions, which
     *  include the widening on some Android versions and not on others). */
    private fun extraPerSpace(tv:TextView,l:Layout,line:Int):Float{
        if(!justified(tv,l,line))return 0f
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);if(vis<=start)return 0f
        val spaces=spacesIn(t,start,vis);if(spaces==0)return 0f
        var natural=Layout.getDesiredWidth(t,start,vis,tv.paint)
        val end=l.getLineEnd(line);if(vis==end&&end<t.length&&t[end-1].isLetter()&&t[end].isLetter())natural+=tv.paint.measureText("-")
        val left=l.getPrimaryHorizontal(start)
        val extra=((l.getParagraphRight(line)-left-natural)/spaces).coerceAtLeast(0f)
        // Whether this device actually stretched the line differs between Android builds; look at the drawn ink.
        return if(extra>0f&&stretched(tv,l,line,left+natural))extra else 0f
    }
    private val probes=java.util.WeakHashMap<Layout,HashMap<Int,Boolean>>()
    /** Draws the layout once into a small alpha mask and checks if the line's ink reaches past its natural end. */
    private fun stretched(tv:TextView,l:Layout,line:Int,naturalRight:Float):Boolean{
        val cache=probes.getOrPut(l){HashMap()};cache[line]?.let{return it}
        val mask=masks.getOrPut(l){runCatching{android.graphics.Bitmap.createBitmap(l.width.coerceAtLeast(1),l.height.coerceAtLeast(1),android.graphics.Bitmap.Config.ALPHA_8).also{l.draw(android.graphics.Canvas(it))}}.getOrNull()}
        val result=if(mask==null)false else{
            val fm=tv.paint.fontMetrics;val base=l.getLineBaseline(line);val y0=(base+fm.ascent*.6f).toInt().coerceIn(0,mask.height-1);val y1=(base+fm.descent*.2f).toInt().coerceIn(0,mask.height-1)
            val from=(naturalRight+tv.resources.displayMetrics.density*3f).toInt().coerceIn(0,mask.width);val to=mask.width
            var ink=false;var x=to-1;while(x>=from&&!ink){var y=y0;while(y<=y1){if(android.graphics.Color.alpha(mask.getPixel(x,y))>60){ink=true;break};y++};x--};ink}
        cache[line]=result;return result
    }
    private val masks=java.util.WeakHashMap<Layout,android.graphics.Bitmap?>()
    /** The [LineStretch] of a line stretched by [SpacedJustify] (custom spacing + justification), if any. */
    internal fun stretchOf(l:Layout,line:Int):LineStretch?{val t=l.text as? android.text.Spanned?:return null;val start=l.getLineStart(line);val end=l.getLineEnd(line);if(end<=start)return null
        return t.getSpans(start,end,LineStretch::class.java).firstOrNull{t.getSpanStart(it)==start}}
    /** On a stretched line every space is wider by the stretch: positions are measured on the plain text and the widening added,
     *  so they never depend on whether the platform's position queries see the stretch. */
    private fun stretchedX(tv:TextView,l:Layout,line:Int,o:Int,st:LineStretch):Float{
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);val upto=o.coerceIn(start,vis)
        val plain=t.subSequence(start,upto).toString()
        // word spacing widens the plain spaces themselves ([WordGap]): count that too, or quotes and selection drift right
        val gap=(t as? android.text.Spanned)?.getSpans(start,vis,WordGap::class.java)?.firstOrNull()
        val wide=if(gap==null)0f else tv.paint.measureText(" ")*(gap.scale-1f)*t.subSequence(start,upto).count{it==' '}
        return l.getPrimaryHorizontal(start)+tv.paint.measureText(plain)+wide+st.extra*spacesIn(t,start,upto)
    }
    /** Drawn x (layout coordinates) of the caret position [o] on [line]. */
    fun x(tv:TextView,l:Layout,line:Int,o:Int):Float{
        stretchOf(l,line)?.let{return stretchedX(tv,l,line,o,it)}
        val extra=extraPerSpace(tv,l,line);if(extra==0f)return natural(tv,l,line,o)
        val t=l.text;val start=l.getLineStart(line);val vis=visibleEnd(l,line);val upto=o.coerceIn(start,vis)
        return l.getPrimaryHorizontal(start)+Layout.getDesiredWidth(t,start,upto,tv.paint)+extra*spacesIn(t,start,upto)
    }
    fun x(tv:TextView,l:Layout,o:Int):Float{val line=l.getLineForOffset(o.coerceIn(0,l.text.length));return x(tv,l,line,o)}
    /** Offset whose drawn position is nearest to [px] on [line]. */
    fun offset(tv:TextView,l:Layout,line:Int,px:Float):Int{
        val start=l.getLineStart(line);val vis=visibleEnd(l,line)
        if(stretchOf(l,line)==null&&extraPerSpace(tv,l,line)==0f)return l.getOffsetForHorizontal(line,px)
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
            val left=x(tv,l,line,ls);var right=x(tv,l,line,le);if(line<last&&le>=visibleEnd(l,line)&&(stretchOf(l,line)!=null||extraPerSpace(tv,l,line)>0f))right=l.getParagraphRight(line).toFloat()
            if(right-left<1f)continue
            val (top,bottom)=band(tv,l,line);out.addRoundRect(left-radius*.4f,top,right+radius*.4f,bottom,radius,radius,Path.Direction.CW)}
    }
}
