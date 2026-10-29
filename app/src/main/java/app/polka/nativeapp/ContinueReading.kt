package app.polka.nativeapp

import android.view.*
import android.widget.*
import android.text.TextUtils
import org.json.JSONObject
import kotlin.math.roundToInt

internal object ReadingQueue {
 fun books(all:List<JSONObject>)=all.filter{!it.optBoolean("noFile")&&Library.status(it)=="reading"}.sortedWith(compareByDescending<JSONObject>{it.optLong("openedAt")}.thenBy{it.str("id")})
}
/** Native scrolling, no animation loop or automatic page changes. The next book remains visible. */
internal class ContinueReading(private val a:MainActivity,all:List<JSONObject>):LinearLayout(a) {
 val readingBooks=ReadingQueue.books(all)
 private val signature=readingBooks.joinToString{it.toString()}
 private val light=a.light
 private val fontScale=resources.configuration.fontScale
 fun matches(all:List<JSONObject>)=light==a.light&&fontScale==resources.configuration.fontScale&&signature==ReadingQueue.books(all).joinToString{it.toString()}
 private var restoring=true
 private var pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0)
 private val cards=mutableListOf<View>()
 private var lastViewport=0
 private val strip=a.line().apply{clipChildren=false;clipToPadding=false}
 private val position=a.text("",12f,a.muted)
 private val carousel=object:HorizontalScrollView(a){
  var stride=1;private var flung=false
  init{isHorizontalScrollBarEnabled=false;isHorizontalFadingEdgeEnabled=false;isVerticalFadingEdgeEnabled=false;setFadingEdgeLength(0);overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;contentDescription="Книги в процессе чтения"}
  override fun onMeasure(w:Int,h:Int){resize(MeasureSpec.getSize(w));super.onMeasure(w,h)}
  override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);if(restoring){scrollTo(pendingIndex*stride,0);restoring=false;position.text="${pendingIndex+1} / ${readingBooks.size}"}}
  override fun fling(velocityX:Int){flung=true;val page=(scrollX.toFloat()/stride+velocityX/5000f).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0));smoothScrollTo(page*stride,0)}
  override fun onTouchEvent(e:android.view.MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN)flung=false;val handled=super.onTouchEvent(e);if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&!flung)post{snap()};return handled}
  fun snap(){val n=(scrollX.toFloat()/stride).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0));if(a.prefs.str("motion")=="reduced")scrollTo(n*stride,0)else smoothScrollTo(n*stride,0)}
  override fun onScrollChanged(x:Int,y:Int,ox:Int,oy:Int){super.onScrollChanged(x,y,ox,oy);if(restoring)return;val n=(x.toFloat()/stride).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0));position.text="${n+1} / ${readingBooks.size}";a.continueBookId=readingBooks.getOrNull(n)?.str("id")}
 }
 init{
  orientation=VERTICAL;clipChildren=false;clipToPadding=false
  val heading=a.line();heading.addView(a.text("Продолжить чтение",20f,bold=true,serif=true),LayoutParams(0,-2,1f));position.text="1 / ${readingBooks.size}";if(readingBooks.size>1)heading.addView(position);addView(heading);a.space(this,14)
  readingBooks.forEachIndexed{i,book->
   val card=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),a.d(16),a.d(14),a.d(16));background=a.controlSurface(this,20f);clipChildren=false;clipToPadding=false;isFocusable=true;contentDescription="Продолжить: ${book.str("title")}"}
   val cover=CoverView(a,book,a.cover(book));card.addView(cover,LayoutParams(a.d(64),a.d(96)))
   card.setOnClickListener{a.openBook(book,cover)};a.press(card)
   val words=a.column().apply{setPadding(a.d(14),0,0,0)}
   words.addView(a.text(book.str("title"),17f,bold=true,serif=true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,4);words.addView(a.text(MainActivity.authors(book),12f,a.muted).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END});a.space(words,12);a.progressRow(words,book)
   words.addView(a.text("О книге →",12f,a.muted).apply{minHeight=a.d(36);gravity=Gravity.CENTER_VERTICAL;contentDescription="О книге ${book.str("title")}";setOnClickListener{a.detail(book,cover)}})
   card.addView(words,LayoutParams(0,-2,1f));cards.add(card);strip.addView(card,LayoutParams(a.d(284),-2).apply{if(i>0)marginStart=a.d(12)})
  }
  carousel.addView(strip,FrameLayout.LayoutParams(-2,-2));addView(carousel,LayoutParams(-1,-2).apply{marginStart=-a.d(20);marginEnd=-a.d(20)})
 }
 private fun resize(viewport:Int){if(viewport<=0||viewport==lastViewport)return;lastViewport=viewport;val content=(viewport-a.d(40)).coerceAtLeast(1);val width=if(cards.size<=1)content else (content*.9f).toInt();carousel.stride=width+a.d(12);cards.forEach{it.layoutParams=(it.layoutParams as LayoutParams).apply{this.width=width}};strip.setPadding(a.d(20),0,viewport-width-a.d(20),0);pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0);restoring=true}
}
