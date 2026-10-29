package app.polka.nativeapp

import android.text.Spanned
import org.jsoup.Jsoup
import org.jsoup.nodes.*

/** Non-visual source coordinates travel with the native rendering, not guessed text searches. */
data class SourceText(val path:List<Int>,val offsets:IntArray)
data class SourceBreak(val path:List<Int>,val before:Boolean)
data class SourceAtomic(val path:List<Int>)
data class PageFormat(val kind:String)
fun sourcePath(node:Node):List<Int> { val path=mutableListOf<Int>();var n=node;while(n.parent()!=null && n.parent() !is Document){path.add(n.siblingIndex());n=n.parent()!!};return path.reversed() }

/** Patches only changed text nodes. No chapter-wide Html.toHtml round trip. */
object PageHtmlPatch {
 data class Change(val start:Int,val end:Int,val text:String)
 data class Format(val start:Int,val end:Int,val kind:String)
 private data class RawEdit(val start:Int,val end:Int,val text:String)
 private fun document(html:String)=Jsoup.parse(html).also { it.outputSettings().prettyPrint(false);it.select("script,style,iframe,object,embed,form,noscript").remove() }
 private fun resolve(doc:Document,path:List<Int>):Node { var n:Node=doc.childNodes().first{it is Element && it.tagName()=="html"};path.forEach{n=n.childNode(it)};return n }
 // Hirschberg LCS: bounded memory even for large-font pages and a full-page paste.
 fun changes(old:String,new:String):List<Change> {
  if(old==new)return emptyList()
  val pairs=mutableListOf<Pair<Int,Int>>()
  fun row(a0:Int,a1:Int,b0:Int,b1:Int,reverse:Boolean):IntArray {
   val n=b1-b0;var prev=IntArray(n+1)
   for(i in 0 until a1-a0){val curr=IntArray(n+1);val c=old[if(reverse)a1-1-i else a0+i];for(j in 1..n)curr[j]=if(c==new[if(reverse)b1-j else b0+j-1])prev[j-1]+1 else maxOf(prev[j],curr[j-1]);prev=curr};return prev
  }
  fun match(a0:Int,a1:Int,b0:Int,b1:Int){
   var x=a0;var y=b0;while(x<a1 && y<b1 && old[x]==new[y]){pairs.add(x to y);x++;y++}
   if(x==a1||y==b1)return
   if(a1-x==1){for(j in y until b1)if(old[x]==new[j]){pairs.add(x to j);break};return}
   val mid=(x+a1)/2;val left=row(x,mid,y,b1,false);val right=row(mid,a1,y,b1,true);var split=0;var best=-1
   for(k in 0..b1-y){val v=left[k]+right[b1-y-k];if(v>best){best=v;split=k}}
   match(x,mid,y,y+split);match(mid,a1,y+split,b1)
  }
  // Leave a long unchanged suffix out of the quadratic part.
  var endA=old.length;var endB=new.length;while(endA>0&&endB>0&&old[endA-1]==new[endB-1]){endA--;endB--}
  match(0,endA,0,endB);for(i in 0 until old.length-endA)pairs.add(endA+i to endB+i)
  val result=mutableListOf<Change>();var a=0;var b=0
  for((x,y) in pairs+listOf(old.length to new.length)){if(x>a||y>b)result.add(Change(a,x,new.substring(b,y)));a=x+1;b=y+1};return result
 }
 fun apply(html:String,rich:Spanned,start:Int,end:Int,edited:String):String {
  require(start in 0..end && end<=rich.length)
  val changes=changes(rich.subSequence(start,end).toString(),edited);if(changes.isEmpty())return html
  val doc=document(html);val textRuns=rich.getSpans(0,rich.length,SourceText::class.java).toList();val edits=linkedMapOf<TextNode,MutableList<RawEdit>>();val breaks=linkedSetOf<SourceBreak>();val atomic=linkedSetOf<SourceAtomic>()
  val nodes=textRuns.associateWith{resolve(doc,it.path) as TextNode}
  fun insert(at:Int,text:String){if(text.isEmpty())return
   val run=textRuns.firstOrNull{at>=rich.getSpanStart(it)&&at<rich.getSpanEnd(it)} ?: textRuns.lastOrNull{rich.getSpanEnd(it)<=at} ?: textRuns.firstOrNull() ?: error("На этой странице нет редактируемого текста")
   val off=(at-rich.getSpanStart(run)).coerceIn(0,run.offsets.lastIndex);val raw=run.offsets[off];edits.getOrPut(nodes.getValue(run)){mutableListOf()}.add(RawEdit(raw,raw,text.replace("\r\n","\n").replace('\r','\n').replace('\n','\u0000')))
  }
  for(change in changes){val from=start+change.start;val to=start+change.end
   rich.getSpans(from,to,SourceAtomic::class.java).filter{from<rich.getSpanEnd(it)&&to>rich.getSpanStart(it)}.forEach{require(from<=rich.getSpanStart(it)&&to>=rich.getSpanEnd(it)){"Таблица или иллюстрация сохранена без изменений. Правьте текст вокруг неё."};atomic.add(it)}
   for(run in textRuns){val a=maxOf(from,rich.getSpanStart(run));val b=minOf(to,rich.getSpanEnd(run));if(a<b)edits.getOrPut(nodes.getValue(run)){mutableListOf()}.add(RawEdit(run.offsets[a-rich.getSpanStart(run)],run.offsets[b-rich.getSpanStart(run)],""))}
   if(to>from)rich.getSpans(from,to,SourceBreak::class.java).filter{rich.getSpanStart(it)>=from&&rich.getSpanEnd(it)<=to}.forEach{breaks.add(it)}
   insert(from,change.text)
  }
  // Resolve before mutations; paths must never be looked up after a node is split or moved.
  val breakNodes=breaks.map{it to resolve(doc,it.path)};val atomicNodes=atomic.map{resolve(doc,it.path)}
  for((node,ops) in edits){var text=node.wholeText
   // Deletions precede insertions at the same raw position.
   for(op in ops.sortedWith(compareByDescending<RawEdit>{it.start}.thenByDescending{it.end}))text=text.substring(0,op.start)+op.text+text.substring(op.end)
   if('\u0000' !in text)node.text(text) else {val lines=text.split('\u0000');lines.forEachIndexed{i,line->if(i>0)node.before(Element("br"));if(line.isNotEmpty())node.before(TextNode(line))};node.remove()}
  }
  atomicNodes.forEach{it.remove()}
  for((mark,node) in breakNodes.asReversed()){
   val el=node as? Element ?: continue
   if(el.tagName()=="br"){el.remove();continue}
   val first=if(mark.before)el.previousElementSibling()else el;val next=if(mark.before)el else el.nextElementSibling()
   if(first!=null&&next!=null&&first.parent()===next.parent()&&first.tagName() in setOf("p","div","h1","h2","h3","h4","h5","h6")&&next.tagName() in setOf("p","div","h1","h2","h3","h4","h5","h6")){
    if(next.id().isNotBlank())first.appendChild(Element("span").attr("id",next.id()))
    next.childNodes().toList().forEach{first.appendChild(it)};next.remove()
   }
  }
  return doc.body().html()
 }
 fun format(html:String,rich:Spanned,formats:List<Format>):String {
  val doc=document(html);val runs=rich.getSpans(0,rich.length,SourceText::class.java).toList();val targets=runs.associateWith{resolve(doc,it.path) as TextNode}
  // Formatting is applied after text changes and fresh source mapping.
  for((run,node) in targets){val selected=formats.filter{it.start<rich.getSpanEnd(run)&&it.end>rich.getSpanStart(run)};if(selected.isEmpty())continue
   val cuts=sortedSetOf(0,node.wholeText.length);for(f in selected){cuts.add(run.offsets[(f.start-rich.getSpanStart(run)).coerceIn(0,run.offsets.lastIndex)]);cuts.add(run.offsets[(f.end-rich.getSpanStart(run)).coerceIn(0,run.offsets.lastIndex)])}
   val raw=node.wholeText
   for((a,b) in cuts.toList().zipWithNext()){var piece:Node=TextNode(raw.substring(a,b));for(f in selected){val lo=run.offsets[(f.start-rich.getSpanStart(run)).coerceIn(0,run.offsets.lastIndex)];val hi=run.offsets[(f.end-rich.getSpanStart(run)).coerceIn(0,run.offsets.lastIndex)];if(a>=lo&&b<=hi){piece=Element(when(f.kind){"bold"->"strong";"italic"->"em";"heading"->"span";else->"span"}).also{it.appendChild(piece)}}};node.before(piece)}
   // Block commands follow the original editor's formatBlock behavior.
   selected.filter{it.kind=="heading"||it.kind=="quote"}.forEach{f->val block=generateSequence(node.parent()){it.parent()}.filterIsInstance<Element>().firstOrNull{it.tagName() in setOf("p","div","h1","h2","h3","h4","h5","h6")};if(block!=null){if(f.kind=="heading")block.tagName("h3")else if(block.parent()?.tagName()!="blockquote")block.wrap("<blockquote></blockquote>")}}
   node.remove()
  };return doc.body().html()
 }
}
