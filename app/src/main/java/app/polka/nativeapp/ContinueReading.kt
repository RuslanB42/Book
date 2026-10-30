package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.GradientDrawable
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
   val card=FrameLayout(a).apply{background=a.controlSurface(this,20f);clipChildren=false;clipToPadding=false;isFocusable=true;contentDescription="Продолжить: ${book.str("title")}"}
   val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),a.d(16),a.d(14),a.d(16));clipChildren=false;clipToPadding=false}
   card.addView(CoverBackdrop(a,a.cover(book)),FrameLayout.LayoutParams(-1,-1));card.addView(row,FrameLayout.LayoutParams(-1,-2))
   val cover=CoverView(a,book,a.cover(book));row.addView(cover,LayoutParams(a.d(64),a.d(96)))
   card.setOnClickListener{a.openBook(book,cover)};a.press(card)
   val words=a.column().apply{setPadding(a.d(14),0,0,0)}
   words.addView(a.text(book.str("title"),17f,bold=true,serif=true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,4);words.addView(a.text(MainActivity.authors(book),12f,a.muted).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END});a.space(words,12);a.progressRow(words,book);a.space(words,12)
   val buttons=a.line();buttons.addView(pill("Читать",true,"Читать: ${book.str("title")}"){a.openBook(book,cover)},LayoutParams(0,-2,1f));buttons.addView(pill("О книге",false,"О книге ${book.str("title")}"){a.detail(book,cover)},LayoutParams(0,-2,1f).apply{marginStart=a.d(8)});words.addView(buttons)
   row.addView(words,LayoutParams(0,-2,1f));cards.add(card);strip.addView(card,LayoutParams(a.d(284),-2).apply{if(i>0)marginStart=a.d(12)})
  }
  carousel.addView(strip,FrameLayout.LayoutParams(-2,-2));addView(carousel,LayoutParams(-1,-2).apply{marginStart=-a.d(20);marginEnd=-a.d(20)})
 }
 private fun pill(label:String,primary:Boolean,description:String,click:()->Unit):TextView=a.text(label,13.5f,if(primary)a.bg else a.fg,true).apply{
  gravity=Gravity.CENTER;minHeight=a.d(38);maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(a.d(10),0,a.d(10),0);contentDescription=description;isClickable=true;isFocusable=true
  background=if(primary)a.shape(a.fg,19) else GradientDrawable().apply{setColor(if(light)0x73ffffff else 0x2effffff);setStroke(a.d(1),if(light)0x24000000 else 0x38ffffff);cornerRadius=a.d(19).toFloat()}
  setOnClickListener{click()};a.press(this)}
 private fun resize(viewport:Int){if(viewport<=0||viewport==lastViewport)return;lastViewport=viewport;val content=(viewport-a.d(40)).coerceAtLeast(1);val width=if(cards.size<=1)content else (content*.9f).toInt();carousel.stride=width+a.d(12);cards.forEach{it.layoutParams=(it.layoutParams as LayoutParams).apply{this.width=width}};strip.setPadding(a.d(20),0,viewport-width-a.d(20),0);pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0);restoring=true}
}

/** The book's own cover, softly blurred and veiled, as the card's background (not a CoverView: it is only a backdrop). */
internal class CoverBackdrop(private val a:MainActivity,source:Bitmap?):View(a) {
 private val radius=a.d(20).toFloat()
 private val blurred:Bitmap?=try{source?.takeIf{!it.isRecycled&&it.width>0&&it.height>0}?.let{src->ShelfFrost.prepared("backdrop:${System.identityHashCode(src)}"){
  val w=28;val h=(w*src.height/src.width.toFloat()).toInt().coerceIn(16,56);val small=Bitmap.createScaledBitmap(src,w,h,true)
  try{ShelfFrost.blur(small,2.4f)}finally{if(small!==src)small.recycle()}}}}catch(e:Exception){null}
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.3f)})}
 private val clip=Path();private val src=Rect();private val dst=RectF()
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){clip.reset();clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)}
 override fun onDraw(c:Canvas){
  val b=blurred?:return;if(width<1||height<1)return
  c.save();c.clipPath(clip)
  // Centre-crop the blurred cover to the card, zoomed in a little so only soft colour remains.
  val aspect=width.toFloat()/height;val zoom=1.25f;var sw=b.width/zoom;var sh=sw/aspect;if(sh>b.height/zoom){sh=b.height/zoom;sw=sh*aspect}
  val cx=b.width/2f;val cy=b.height/2f;src.set((cx-sw/2).toInt().coerceAtLeast(0),(cy-sh/2).toInt().coerceAtLeast(0),(cx+sw/2).toInt().coerceAtMost(b.width),(cy+sh/2).toInt().coerceAtMost(b.height))
  dst.set(0f,0f,width.toFloat(),height.toFloat());c.drawBitmap(b,src,dst,paint)
  c.drawColor(if(a.light)0xa8fbfaf7.toInt() else 0xb0121214.toInt());c.restore()
  val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1).toFloat();color=if(a.light)0x14000000 else 0x24ffffff}
  c.drawRoundRect(RectF(.5f,.5f,width-.5f,height-.5f),radius,radius,edge)
 }
}
