package app.polka.nativeapp
import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.text.*
import org.json.JSONObject
@android.annotation.SuppressLint("WrongConstant")
object CoverTypeset {
 data class Lines(val left:Float,val top:Float,val title:StaticLayout,val author:StaticLayout)
 private val cache=android.util.LruCache<String,Lines>(256)
 @Synchronized fun get(context:Context,book:JSONObject,opening:Boolean=false):Lines {
  val title=book.str("title","Без названия");val author=MainActivity.authors(book).uppercase();val key="$opening|$title|$author"
  cache.get(key)?.let{return it};var hash=2166136261L.toInt();(title+MainActivity.authors(book)).forEach{hash=(hash xor it.code)*16777619}
  val palette=intArrayOf(0xffebe5d6.toInt(),0xfff3e6d6.toInt(),0xffe8edf3.toInt(),0xffece6d2.toInt(),0xfff6ead7.toInt(),0xffe6dccb.toInt(),0xfff4e9dc.toInt(),0xff2a2622.toInt(),0xff1d2a26.toInt(),0xffefe7f5.toInt(),0xffe3f0ec.toInt());val color=palette[((hash.toLong() and 0xffffffffL)%palette.size).toInt()]
  val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{textSize=if(opening)44f else 46f;typeface=PolkaFonts.serif(context,true);this.color=color}
  val width=if(opening)296 else 312
  val tl=StaticLayout.Builder.obtain(title,0,title.length,paint,width).setIncludePad(false).setLineSpacing(paint.textSize*1.12f-paint.fontSpacing,1f).setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).setBreakStrategy(if(opening)Layout.BREAK_STRATEGY_BALANCED else Layout.BREAK_STRATEGY_HIGH_QUALITY).build()
  val ap=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply{textSize=if(opening)27.2f else 25.6f;typeface=Typeface.create("sans-serif",Typeface.BOLD);this.color=(color and 0xffffff) or 0xc7000000.toInt();}
  val tracked=SpannableString(author);var offset=0;while(offset<author.length){val end=offset+Character.charCount(Character.codePointAt(author,offset));tracked.setSpan(CoverTracking(ap.textSize*(if(opening).1f else .12f)),offset,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);offset=end}
  val al=StaticLayout.Builder.obtain(tracked,0,tracked.length,ap,width).setIncludePad(false).setLineSpacing(ap.textSize*1.25f-ap.fontSpacing,1f).build()
  return Lines(if(opening)60f else 48f,if(opening)52f else 44f,tl,al).also{cache.put(key,it)}
 }
}

private class CoverTracking(private val tracking:Float):android.text.style.ReplacementSpan(){
 override fun getSize(p:Paint,text:CharSequence,start:Int,end:Int,fm:Paint.FontMetricsInt?):Int {if(fm!=null)p.getFontMetricsInt(fm);return kotlin.math.ceil(p.measureText(text,start,end)+tracking).toInt()}
 override fun draw(c:android.graphics.Canvas,text:CharSequence,start:Int,end:Int,x:Float,top:Int,y:Int,bottom:Int,p:Paint){c.drawText(text,start,end,x,y.toFloat(),p)}
}
