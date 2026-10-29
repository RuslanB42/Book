package app.polka.nativeapp
import android.graphics.*
import android.text.*
import android.text.style.*
/** A single layout shared by all page slices preserves merged cells and inline content. */
data class NativeTableCell(val row:Int,val column:Int,val colspan:Int,val rowspan:Int,val layout:StaticLayout,val anchors:Map<String,Int>,val text:CharSequence)
class NativeTableModel(val width:Int,val columns:Int,val pad:Int,val heights:IntArray,val cells:List<NativeTableCell>,val ink:Int,val hair:Int) {
    fun rowTop(row:Int)=(0 until row).sumOf { heights[it] }
    fun bounds(cell:NativeTableCell)=RectF(width*cell.column/columns.toFloat(),rowTop(cell.row).toFloat(),width*(cell.column+cell.colspan)/columns.toFloat(),rowTop(cell.row+cell.rowspan).toFloat())
    fun linkAt(x:Float,y:Float):String? {
        val cell=cells.firstOrNull { bounds(it).contains(x,y) } ?: return null; val r=bounds(cell); val localX=x-r.left-pad*2; val localY=y-r.top-pad
        if(localX<0 || localY<0 || localY>=cell.layout.height) return null
        val line=cell.layout.getLineForVertical(localY.toInt()); if(localX<cell.layout.getLineLeft(line) || localX>cell.layout.getLineRight(line)) return null; val offset=cell.layout.getOffsetForHorizontal(line,localX); val rich=cell.layout.text as? Spanned ?: return null
        return rich.getSpans(offset,offset,URLSpan::class.java).firstOrNull()?.url
    }
    fun draw(c:Canvas) {
        val border=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=(hair and 0xffffff) or 0x66000000; strokeWidth=1f; style=Paint.Style.STROKE }
        cells.forEach { cell -> val rect=bounds(cell); c.drawRect(rect,border); c.save(); c.clipRect(rect); c.translate(rect.left+pad*2,rect.top+pad); cell.layout.draw(c); c.restore() }
    }
}
class TableRowSpan(val model:NativeTableModel,val sliceTop:Int,val sliceHeight:Int):ReplacementSpan() {
    fun linkAt(x:Float,y:Float)=model.linkAt(x,y+sliceTop)
    override fun getSize(paint:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int { fm?.let { val descent=paint.fontMetricsInt.descent.coerceAtLeast(0); it.ascent=descent-sliceHeight; it.top=it.ascent; it.descent=descent; it.bottom=descent }; return model.width }
    override fun draw(canvas:Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,paint:Paint) {
        val baselineBottom=y+paint.fontMetricsInt.descent.coerceAtLeast(0); canvas.save(); canvas.clipRect(x,baselineBottom-sliceHeight.toFloat(),x+model.width,baselineBottom.toFloat()); canvas.translate(x,baselineBottom-sliceHeight-sliceTop.toFloat()); model.draw(canvas); canvas.restore()
    }
}
