package app.polka.nativeapp

import android.text.*
import org.json.JSONObject

/** Only called on the CPU executor. Keeps full chapters out of TextView in paged mode. */
class ReaderLayoutEngine {
 data class PageData(val text:SpannableStringBuilder,val anchors:Map<String,Int>,val starts:List<Int>,val worker:String)
 private val cache=object:LinkedHashMap<String,PageData>(4,.75f,true){override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,PageData>?)=size>3}
 var builds=0;private set
 // Layout constants are the API-23 aliases of the identical LineBreaker integer values.
 @android.annotation.SuppressLint("WrongConstant")
 @Synchronized fun prepare(key:String,parse:()->ReadingText.Result,paint:TextPaint,width:Int,height:Int,justify:Int):PageData {
  cache[key]?.let{return it};if(Thread.currentThread().isInterrupted)throw InterruptedException()
  val rich=parse();if(Thread.currentThread().isInterrupted)throw InterruptedException()
  val layout=StaticLayout.Builder.obtain(rich.text,0,rich.text.length,paint,width).setLineSpacing(0f,1f).setIncludePad(false).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY).setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL).setJustificationMode(justify).build()
  val starts=mutableListOf(0);var first=0
  for(line in 1 until layout.lineCount){if(Thread.currentThread().isInterrupted)throw InterruptedException();if(layout.getLineBottom(line)-layout.getLineTop(first)>height){val off=layout.getLineStart(line);if(off>starts.last())starts.add(off);first=line}}
  builds++;return PageData(rich.text,rich.anchors,starts,Thread.currentThread().name).also{cache[key]=it}
 }
 @Synchronized fun clear(){cache.clear()}
}
