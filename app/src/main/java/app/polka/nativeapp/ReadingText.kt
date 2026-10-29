package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.text.*
import android.text.style.*
import org.jsoup.Jsoup
import org.jsoup.nodes.*
import kotlin.math.ceil

/** Native reading styles from .r-content, not arbitrary publisher CSS. */
class ReadingText(private val a:MainActivity,private val textSize:Float,private val width:Int,private val maxImageHeight:Int,private val images:Map<String,ByteArray>,private val ink:Int=a.fg,private val leading:Float=1.6f,private val metricsOnly:Boolean=false) {
    data class Result(val text:SpannableStringBuilder,val anchors:Map<String,Int>)
    private val out=SpannableStringBuilder(); private val anchors=linkedMapOf<String,Int>()
    private val px=textSize
    private fun span(s:Any,start:Int,end:Int=out.length) { if(end>start) out.setSpan(s,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    private fun newline(node:Node?=null, before:Boolean=false) { if(out.isNotEmpty() && out.last()!='\n') { val start=out.length;out.append('\n');if(node!=null)span(SourceBreak(sourcePath(node),before),start) } }
    private fun para(s:Any,start:Int) { if(out.length>start) out.setSpan(s,start,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    fun render(html:String):Result {
        val doc=Jsoup.parse(html); doc.select("script,style,iframe,object,embed,form,noscript").remove()
        doc.body().childNodes().forEach { visit(it) }
        if(out.isNotEmpty()) out.setSpan(ReadingLeading(px*leading),0,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE or (255 shl Spanned.SPAN_PRIORITY_SHIFT))
        return Result(out,anchors)
    }
    private fun visit(node:Node,pre:Boolean=false) {
        if(Thread.currentThread().isInterrupted)throw InterruptedException()
        if(node is TextNode) {
            val raw=node.wholeText;val text=StringBuilder();val offsets=mutableListOf<Int>();var at=0
            while(at<raw.length){val from=at;val ch=raw[at];if(!pre && ch in "\t\n\r "){while(at<raw.length && raw[at] in "\t\n\r ")at++;if(text.isEmpty() && (out.isEmpty() || out.last()=='\n' || out.last()==' '))continue;text.append(' ');offsets.add(from)}else{text.append(ch);offsets.add(at);at++}}
            offsets.add(raw.length);val start=out.length;out.append(text);if(text.isNotEmpty())span(SourceText(sourcePath(node),offsets.toIntArray()),start);return
        }
        val el=node as? Element ?: return; val tag=el.tagName().substringAfter(':').lowercase()
        if(el.id().isNotBlank()) anchors[el.id()]=out.length
        if(tag=="br") { val start=out.length;out.append('\n');span(SourceBreak(sourcePath(el),false),start);return }
        if(tag in listOf("img","image","svg")) { val start=out.length;image(el);span(SourceAtomic(sourcePath(el)),start);return }
        if(tag=="table") { val start=out.length;table(el);span(SourceAtomic(sourcePath(el)),start);return }
        if(tag in listOf("script","style","iframe","object","embed","form","noscript")) return
        val block=tag in setOf("p","div","section","article","h1","h2","h3","h4","h5","h6","blockquote","ul","ol","li","pre","figure","figcaption","hr")
        if(block) newline(el,true)
        val start=out.length
        if(tag=="hr") { out.append("────────"); newline(); para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),start); return }
        if(tag=="li") {
            val parent=el.parent(); val number=(parent?.children()?.takeWhile { it!==el }?.count { it.tagName()=="li" } ?: 0)+(parent?.attr("start")?.toIntOrNull() ?: 1)
            out.append(if(parent?.tagName()=="ol") "$number. " else "• ")
        }
        el.childNodes().forEach { visit(it,pre || tag=="pre") }
        if(block) { if(el.hasClass("empty") && out.length==start) out.append('\u00a0'); newline(el) }
        val end=out.length
        when(tag) {
            "strong","b" -> span(StyleSpan(Typeface.BOLD),start)
            "em","i" -> span(StyleSpan(Typeface.ITALIC),start)
            "s","strike","del" -> span(StrikethroughSpan(),start)
            "u" -> span(UnderlineSpan(),start)
            "sup" -> { span(SuperscriptSpan(),start); span(RelativeSizeSpan(.72f),start) }
            "sub" -> { span(SubscriptSpan(),start); span(RelativeSizeSpan(.72f),start) }
            "code","pre" -> { span(TypefaceSpan("monospace"),start); span(RelativeSizeSpan(.85f),start) }
            "a" -> { val href=el.attr("data-href").ifBlank { el.attr("href") }; if(href.isNotBlank() && !href.startsWith("javascript:",true)) span(URLSpan(href),start) }
        }
        if(end<=start || !block) return
        val heading=tag.matches(Regex("h[1-6]")); val parents=el.parents(); val epigraph=el.hasClass("epigraph") || parents.any { it.hasClass("epigraph") }; val poem=el.hasClass("poem") || parents.any { it.hasClass("poem") }; val cite=tag=="blockquote" || parents.any { it.tagName()=="blockquote" || it.hasClass("cite") }
        if(heading) {
            val scale=when(tag) { "h1"->1.65f; "h2"->1.42f; "h3"->1.2f; else->1.05f }
            span(RelativeSizeSpan(scale),start); span(StyleSpan(Typeface.BOLD),start); para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_NORMAL),start)
            para(ParagraphSpace(if(start==0) 0 else (px*scale*2.3f).toInt(),(px*scale*1.1f).toInt(),px*scale*1.25f),start)
        } else if(tag=="p") {
            val special=el.hasClass("subtitle") || el.hasClass("author") || el.hasClass("v") || el.hasClass("date") || el.hasClass("empty") || epigraph || cite || poem || parents.any { it.tagName().matches(Regex("h[1-6]")) }
            val prev=el.previousElementSibling(); val follows=prev!=null && (prev.tagName().matches(Regex("h[1-6]")) || prev.tagName() in listOf("blockquote","img","svg","figure") || prev.classNames().any { it in listOf("subtitle","epigraph","empty","poem","img") })
            if(!special && !follows) para(LeadingMarginSpan.Standard((px*1.4f).toInt(),0),start)
            if(special) para(AlignmentSpan.Standard(if(el.hasClass("author") || el.hasClass("date")) Layout.Alignment.ALIGN_OPPOSITE else if(el.hasClass("subtitle")) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL),start)
        }
        if(el.hasClass("subtitle")) { span(StyleSpan(Typeface.BOLD),start); para(ParagraphSpace((px*1.3f).toInt(),(px*.9f).toInt()),start) }
        if(el.hasClass("author")) { span(StyleSpan(Typeface.BOLD),start); span(RelativeSizeSpan(.9f),start) }
        if(el.hasClass("date")) span(StyleSpan(Typeface.ITALIC),start)
        if(tag=="li") para(LeadingMarginSpan.Standard((px*1.4f).toInt()),start)
        if(tag=="pre") para(ParagraphSpace(0,0,px*.85f*leading),start)
        if(tag=="blockquote" || el.hasClass("epigraph") || el.hasClass("poem")) {
            para(LeadingMarginSpan.Standard((if(epigraph) width*.22f else px*(if(poem) 1.6f else 1.4f)).toInt()),start)
            if(epigraph) span(StyleSpan(Typeface.ITALIC),start)
            if(!poem) span(RelativeSizeSpan(if(epigraph) .92f else .95f),start)
            para(ParagraphSpace(if(start==0 || epigraph) 0 else (px*1.2f).toInt(),(px*(if(epigraph) 2.2f else 1.2f)).toInt()),start)
        }
    }
    private fun image(el:Element) {
        newline(); val start=out.length
        val source=el.attr("data-src").ifBlank { el.attr("src").ifBlank { el.attr("xlink:href").ifBlank { el.attr("href") } } }
        val bytes=if(el.tagName()=="svg") el.outerHtml().toByteArray() else images[source]
        if(metricsOnly) { if(bytes!=null) out.append('\ufffc') else if(el.attr("alt").isNotBlank()) out.append(el.attr("alt")); newline(); return }
        val bitmap=bytes?.let { BookImages.decode(it) }
        if(bitmap!=null) {
            val scale=minOf(width.toFloat()/bitmap.width,maxImageHeight.toFloat()/bitmap.height,1f)
            val drawable=BitmapDrawable(a.resources,bitmap); drawable.setBounds(0,0,(bitmap.width*scale).toInt().coerceAtLeast(1),(bitmap.height*scale).toInt().coerceAtLeast(1))
            out.append('\ufffc'); span(ImageSpan(drawable,ImageSpan.ALIGN_BASELINE),start)
        } else if(el.attr("alt").isNotBlank()) out.append(el.attr("alt"))
        newline(); para(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER),start)
        para(ParagraphSpace((px*1.4f).toInt(),(px*1.4f).toInt()),start)
    }
    private fun table(el:Element) {
        newline(); val tableStart=out.length
        el.selectFirst("caption")?.let { visit(it); newline() }
        val rows=el.select("tr").toList().filter { it.closest("table")===el }
        if(rows.isEmpty()) return
        data class Pending(val row:Int,val col:Int,val cs:Int,val rs:Int,val element:Element)
        val pending=mutableListOf<Pending>(); val occupied=mutableMapOf<Pair<Int,Int>,Boolean>(); var columns=1
        rows.forEachIndexed { r,row -> var col=0
            row.children().toList().filter { it.tagName() in listOf("td","th") }.forEach { cell ->
                while(occupied[r to col]==true) col++
                val cs=(cell.attr("colspan").toIntOrNull() ?: 1).coerceIn(1,32); val rs=(cell.attr("rowspan").toIntOrNull() ?: 1).coerceIn(1,rows.size-r)
                pending.add(Pending(r,col,cs,rs,cell)); for(y in r until r+rs) for(x in col until col+cs) occupied[y to x]=true
                columns=maxOf(columns,col+cs); col+=cs
            }
        }
        columns=columns.coerceAtMost(64)
        val padding=(px*.3f).toInt(); val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { textSize=px*.9f; typeface=PolkaFonts.reader(a,a.prefs.str("font","literata")); color=ink }
        val heights=IntArray(rows.size) { (px*1.5f).toInt() }; val pieces=pending.map { cell ->
            val w=(width.toFloat()*cell.cs/columns).toInt().coerceAtLeast(8); val rich=ReadingText(a,px*.9f,(w-padding*4).coerceAtLeast(8),maxImageHeight,images,ink,1.4f,metricsOnly).render(cell.element.html())
            if(cell.element.tagName()=="th" && rich.text.isNotEmpty()) rich.text.setSpan(StyleSpan(Typeface.BOLD),0,rich.text.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val text=rich.text.trimEnd('\n'); val layout=StaticLayout.Builder.obtain(text,0,text.length,paint,(w-padding*4).coerceAtLeast(8)).setIncludePad(false).build()
            if(cell.rs==1) heights[cell.row]=maxOf(heights[cell.row],layout.height+padding*2)
            NativeTableCell(cell.row,cell.col,cell.cs,cell.rs,layout,rich.anchors,text)
        }
        pieces.filter { it.rowspan>1 }.forEach { cell -> val existing=(cell.row until cell.row+cell.rowspan).sumOf { heights[it] }; var need=cell.layout.height+padding*2-existing
            for(r in cell.row until cell.row+cell.rowspan) if(need>0) { val add=ceil(need.toDouble()/(cell.row+cell.rowspan-r)).toInt(); heights[r]+=add; need-=add }
        }
        val model=NativeTableModel(width,columns,padding,heights,pieces,ink,a.muted)
        rows.forEachIndexed { r,_ ->
            val rowCells=pieces.filter { it.row==r }; val full=SpannableStringBuilder()
            rowCells.forEachIndexed { i,cell -> if(i>0) full.append('\t'); cell.anchors.forEach { (name,offset) -> anchors[name]=out.length+full.length+offset }; full.append(cell.text) }
            val rowHeight=heights[r]; val sliceHeight=maxImageHeight.coerceAtLeast(40); val slices=ceil(rowHeight.toDouble()/sliceHeight).toInt().coerceAtLeast(1)
            repeat(slices) { piece -> val start=out.length; out.append(if(piece==0 && full.isNotEmpty()) full.toString() else "\u200b"); span(TableRowSpan(model,model.rowTop(r)+piece*sliceHeight,minOf(sliceHeight,rowHeight-piece*sliceHeight)),start); out.append('\n') }
        }
        if(out.length>tableStart) { val firstEnd=out.indexOf("\n",tableStart)+1; out.setSpan(ParagraphSpace((px*1.2f).toInt(),0),tableStart,firstEnd,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); val lastStart=out.lastIndexOf("\n",out.length-2)+1; out.setSpan(ParagraphSpace(0,(px*1.2f).toInt()),lastStart,out.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }
}

class ReadingLeading(private val height:Float):LineHeightSpan {
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt) {
        if(text is Spanned && (text.getSpans(start,end,TableRowSpan::class.java).isNotEmpty() || text.getSpans(start,end,ImageSpan::class.java).isNotEmpty())) return
        val extra=height.toInt()-(fm.descent-fm.ascent); fm.ascent-=extra/2; fm.descent+=extra-extra/2; fm.top=fm.ascent; fm.bottom=fm.descent
    }
}

/** Per-paragraph margins and heading leading included by StaticLayout pagination. */
class ParagraphSpace(private val before:Int,private val after:Int,private val line:Float=0f):LineHeightSpan {
    fun continuation(trimBefore:Boolean,trimAfter:Boolean)=ParagraphSpace(if(trimBefore) 0 else before,if(trimAfter) 0 else after,line)
    override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt) {
        if(line>0) { val extra=line.toInt()-(fm.descent-fm.ascent); fm.ascent-=extra/2; fm.descent+=extra-extra/2 }
        val sp=text as? Spanned ?: return
        if(start<=sp.getSpanStart(this)) fm.ascent-=before
        if(end>=sp.getSpanEnd(this)) fm.descent+=after
        fm.top=fm.ascent; fm.bottom=fm.descent
    }
}

