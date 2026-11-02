package app.polka.nativeapp

import android.graphics.*
import android.os.Build
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
 // The book on the visible card is the likeliest next open: get its text ready in the background.
 private val warm=(readingBooks.firstOrNull{it.str("id")==a.continueBookId}?:readingBooks.firstOrNull())?.also{a.prefetchContent(it)}
 private val signature=readingBooks.joinToString{it.toString()}
 private val light=a.light
 private val fontScale=resources.configuration.fontScale
 fun matches(all:List<JSONObject>)=light==a.light&&fontScale==resources.configuration.fontScale&&signature==ReadingQueue.books(all).joinToString{it.toString()}
 private var restoring=true
 private var pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0)
 private val cards=mutableListOf<View>()
 private val holders=mutableListOf<FrameLayout>()
 private var lastViewport=0
 private val strip=a.line().apply{clipChildren=false;clipToPadding=false}
 private val position=a.text("",12f,a.muted)
 private val carousel=object:HorizontalScrollView(a){
  var stride=1;private var flung=false;private var startPage=0
  private fun last()=readingBooks.lastIndex.coerceAtLeast(0)
  // One gesture moves at most one card: the target is always next to the card where the finger went down.
  override fun dispatchTouchEvent(e:android.view.MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN){startPage=(scrollX.toFloat()/stride).roundToInt().coerceIn(0,last());flung=false};return super.dispatchTouchEvent(e)}
  init{isHorizontalScrollBarEnabled=false;isHorizontalFadingEdgeEnabled=false;isVerticalFadingEdgeEnabled=false;setFadingEdgeLength(0);overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;contentDescription="Книги в процессе чтения"}
  override fun onMeasure(w:Int,h:Int){resize(MeasureSpec.getSize(w));super.onMeasure(w,h)}
  override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);evenHeights();if(restoring){scrollTo(pendingIndex*stride,0);restoring=false;position.text="${pendingIndex+1} / ${readingBooks.size}"};focus(scrollX)}
  override fun fling(velocityX:Int){flung=true;val dir=if(velocityX>0)1 else if(velocityX<0)-1 else 0;val page=(startPage+dir).coerceIn(0,last());if(a.prefs.str("motion")=="reduced")scrollTo(page*stride,0)else smoothScrollTo(page*stride,0)}
  override fun onTouchEvent(e:android.view.MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN)flung=false;val handled=super.onTouchEvent(e);if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&!flung)post{snap()};return handled}
  fun snap(){val n=(scrollX.toFloat()/stride).roundToInt().coerceIn((startPage-1).coerceAtLeast(0),(startPage+1).coerceAtMost(last()));if(a.prefs.str("motion")=="reduced")scrollTo(n*stride,0)else smoothScrollTo(n*stride,0)}
  override fun onScrollChanged(x:Int,y:Int,ox:Int,oy:Int){super.onScrollChanged(x,y,ox,oy);focus(x);if(restoring)return;val n=(x.toFloat()/stride).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0));position.text="${n+1} / ${readingBooks.size}";if(a.continueBookId!=readingBooks.getOrNull(n)?.str("id"))readingBooks.getOrNull(n)?.let{a.prefetchContent(it)};a.continueBookId=readingBooks.getOrNull(n)?.str("id")}
 }
 init{
  orientation=VERTICAL;clipChildren=false;clipToPadding=false
  val heading=a.line();heading.addView(a.text("Продолжить чтение",20f,bold=true,serif=true),LayoutParams(0,-2,1f));position.text="1 / ${readingBooks.size}";if(readingBooks.size>1)heading.addView(position);addView(heading);a.space(this,14)
  readingBooks.forEachIndexed{i,book->
   val card=FrameLayout(a).apply{floating(this);isFocusable=true;contentDescription="Продолжить: ${book.str("title")}"}
   val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),a.d(16),a.d(14),a.d(16));clipChildren=false;clipToPadding=false}
   card.addView(CoverBackdrop(a,book),FrameLayout.LayoutParams(-1,-1));card.addView(row,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL))
   val cover=CoverView(a,book,a.cover(book));row.addView(cover,LayoutParams(a.d(64),a.d(96)))
   card.setOnClickListener{a.openBook(book,cover)};a.press(card)
   val words=a.column().apply{setPadding(a.d(14),0,0,0)}
   words.addView(a.text(book.str("title"),17f,bold=true,serif=true).apply{maxLines=1;isSingleLine=true;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,2);words.addView(a.text(MainActivity.authors(book),12f,a.muted).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END});a.space(words,14)
   val pct=(book.optJSONObject("progress")?.optDouble("pct",0.0)?:0.0).toFloat().coerceIn(0f,1f)
   words.addView(SheenProgress(a,pct),LayoutParams(-1,a.d(10)));a.space(words,6)
   words.addView(a.text("${(pct*100).roundToInt()}%",12f,a.muted));a.space(words,12)
   val buttons=a.line();buttons.addView(pill("Читать",true,"Читать: ${book.str("title")}"){a.openBook(book,cover)},LayoutParams(0,-2,1f));buttons.addView(pill("О книге",false,"О книге ${book.str("title")}"){a.detail(book,cover)},LayoutParams(0,-2,1f).apply{marginStart=a.d(8)});words.addView(buttons)
   row.addView(words,LayoutParams(0,-2,1f));cards.add(card)
   // The focus transform lives on a holder, so the press feedback on the card never fights it.
   val holder=FrameLayout(a).apply{clipChildren=false;clipToPadding=false};holder.addView(card,FrameLayout.LayoutParams(a.d(284),-2));holders.add(holder)
   strip.addView(holder,LayoutParams(-2,-2).apply{if(i>0)marginStart=a.d(12)})
  }
  carousel.addView(strip,FrameLayout.LayoutParams(-2,-2));addView(carousel,LayoutParams(-1,-2).apply{marginStart=-a.d(20);marginEnd=-a.d(20);topMargin=-a.d(6);bottomMargin=-a.d(14)})
 }
 /** Same floating card as the statistics plates: no border, a wide soft shadow underneath. */
 private fun floating(v:View){
  val radius=a.d(20).toFloat()
  v.background=GradientDrawable().apply{setColor(if(light)Color.WHITE else 0xff242426.toInt());cornerRadius=radius}
  v.elevation=a.d(if(light)8 else 10).toFloat()
  v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,radius)}}
  if(Build.VERSION.SDK_INT>=28){v.outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();v.outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
 }
 private fun pill(label:String,primary:Boolean,description:String,click:()->Unit):TextView=a.text(label,13.5f,if(primary)a.bg else a.fg,true).apply{
  gravity=Gravity.CENTER;minHeight=a.d(38);maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(a.d(10),0,a.d(10),0);contentDescription=description;isClickable=true;isFocusable=true
  background=if(primary)a.shape(a.fg,19) else GradientDrawable().apply{setColor(if(light)0x99ffffff.toInt() else 0x33ffffff);cornerRadius=a.d(19).toFloat()}
  setOnClickListener{click()};a.press(this)}
 private fun resize(viewport:Int){if(viewport<=0||viewport==lastViewport)return;lastViewport=viewport;val content=(viewport-a.d(40)).coerceAtLeast(1)
  // The card in focus sits at the normal page margin on the left; the next one peeks in on the right.
  val width=if(cards.size<=1)content else (content*.86f).toInt();val gap=a.d(10);carousel.stride=width+gap
  cards.forEach{c->c.layoutParams=(c.layoutParams as FrameLayout.LayoutParams).apply{this.width=width}}
  holders.forEachIndexed{i,h->h.layoutParams=(h.layoutParams as LayoutParams).apply{marginStart=if(i>0)gap else 0}}
  strip.setPadding(a.d(20),a.d(6),(viewport-width-a.d(20)).coerceAtLeast(a.d(20)),a.d(26))
  pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0);restoring=true}
 /** Every card gets the height of the tallest one, so all of them scale the same way. */
 private fun evenHeights(){if(cards.size<=1)return;val tallest=cards.maxOf{it.measuredHeight};if(tallest<=0)return
  cards.forEach{c->if(c.minimumHeight!=tallest){c.minimumHeight=tallest}}}
 /** The focused card (at the left margin) is full size and sharp; the others are smaller, dimmer and softly blurred
  *  (on Android 12+), continuously with their distance from focus — like iOS card stacks. */
 private fun focus(x:Int){if(holders.size<=1)return;val stride=carousel.stride.toFloat().coerceAtLeast(1f);val anchor=x+a.d(20)
  holders.forEachIndexed{i,h->if(h.width<=0)return@forEachIndexed;val d=(h.left-anchor)/stride;val k=kotlin.math.abs(d).coerceAtMost(1f)
   val s=1f-.08f*k;h.pivotX=if(d>0)0f else h.width.toFloat();h.pivotY=h.height/2f;h.scaleX=s;h.scaleY=s;h.alpha=1f-.42f*k
   // the card in focus floats higher (deeper shadow); the others sink a little
   cards[i].translationZ=a.d(8)*(1f-k)
   if(Build.VERSION.SDK_INT>=31)h.setRenderEffect(null)}}
}

/** The book's own cover, softly blurred and veiled, as the card's background (not a CoverView: it is only a backdrop). */
internal class CoverBackdrop(private val a:MainActivity,private val book:JSONObject):View(a) {
 private val radius=a.d(20).toFloat()
 private var blurred:Bitmap?=null
 private fun prepare(source:Bitmap?):Bitmap?=try{source?.takeIf{!it.isRecycled&&it.width>0&&it.height>0}?.let{src->ShelfFrost.prepared("backdrop:${System.identityHashCode(src)}"){
  val w=28;val h=(w*src.height/src.width.toFloat()).toInt().coerceIn(16,56);val small=Bitmap.createScaledBitmap(src,w,h,true)
  try{ShelfFrost.blur(small,2.4f)}finally{if(small!==src)small.recycle()}}}}catch(e:Exception){null}
 private var requested=false
 /** The cover may still be decoding when the card is built: ask for it and fade the backdrop in when it arrives. */
 private fun ensure(){
  if(blurred!=null)return
  prepare(a.cover(book))?.let{blurred=it;return}
  if(requested||book.optJSONObject("cover")==null)return;requested=true
  a.requestCover(book){bmp->prepare(bmp)?.let{b->blurred=b;alpha=0f;invalidate();if(isAttachedToWindow&&a.prefs.str("motion")!="reduced")animate().alpha(1f).setDuration(320).start() else alpha=1f}}
 }
 override fun onAttachedToWindow(){super.onAttachedToWindow();ensure()}
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.3f)})}
 private val clip=Path();private val src=Rect();private val dst=RectF();private val veil=Paint()
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){clip.reset();clip.addRoundRect(0f,0f,w.toFloat(),h.toFloat(),radius,radius,Path.Direction.CW)}
 // Cover and veil are composed once into one small bitmap and painted as ONE anti-aliased rounded rect.
 // Drawing them as two separately clipped layers let the bright cover leak through the veil's soft edge —
 // the thin light arcs on the card's corners.
 private var composed:Bitmap?=null;private var composedKey=""
 private val fill=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private val m=Matrix()
 private fun compose(b:Bitmap):Bitmap{val key="${System.identityHashCode(b)}:$width:$height:${a.light}";composed?.takeIf{key==composedKey}?.let{return it}
  val k=3;val w=(width/k).coerceAtLeast(1);val h=(height/k).coerceAtLeast(1);val out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(out)
  val aspect=width.toFloat()/height;val zoom=1.25f;var sw=b.width/zoom;var sh=sw/aspect;if(sh>b.height/zoom){sh=b.height/zoom;sw=sh*aspect}
  val cx=b.width/2f;val cy=b.height/2f;src.set((cx-sw/2).toInt().coerceAtLeast(0),(cy-sh/2).toInt().coerceAtLeast(0),(cx+sw/2).toInt().coerceAtMost(b.width),(cy+sh/2).toInt().coerceAtMost(b.height))
  dst.set(0f,0f,w.toFloat(),h.toFloat());c.drawBitmap(b,src,dst,paint)
  // Cover colours on the left melting into the app's usual card grey on the right.
  val base=if(a.light)0xffffffff.toInt() else 0xff242426.toInt();val rgb=base and 0xffffff
  veil.shader=LinearGradient(0f,0f,w.toFloat(),h*.35f,intArrayOf(rgb or ((if(a.light)0x55 else 0x4d) shl 24),rgb or (0xb0 shl 24),base),floatArrayOf(0f,.42f,.82f),Shader.TileMode.CLAMP)
  c.drawRect(0f,0f,w.toFloat(),h.toFloat(),veil)
  composed=out;composedKey=key;return out}
 override fun onDraw(c:Canvas){
  ensure();val b=blurred?:return;if(width<1||height<1)return
  val bmp=compose(b);m.setScale(width/bmp.width.toFloat(),height/bmp.height.toFloat())
  fill.shader=BitmapShader(bmp,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP).apply{setLocalMatrix(m)}
  c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,fill)
 }
}

/** Thin bar like in streaming apps: a faint track, a fill that brightens toward its end, and a small soft spark of
 *  light at the end — an elongated glow, no hard block. */
internal class SheenProgress(private val a:MainActivity,private val fraction:Float):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onDraw(c:Canvas){val h=a.d(4).toFloat();val cy=height/2f;val w=width.toFloat();val r=h/2;val ink=a.fg
        p.reset();p.isAntiAlias=true
        p.color=(ink and 0xffffff) or ((if(a.light)0x1a else 0x24) shl 24);c.drawRoundRect(0f,cy-r,w,cy+r,r,r,p)
        if(fraction<=0f)return;val x=(w*fraction).coerceIn(h,w)
        // soft round glow at the end of the fill (an ellipse fading to nothing — no edges)
        val glow=if(a.light)0xffffa63a.toInt() else 0xfffff0c8.toInt();val gx=a.d(16).toFloat()
        c.save();c.translate(x-r,cy);c.scale(1f,.55f)
        p.shader=RadialGradient(0f,0f,gx,intArrayOf((glow and 0xffffff) or ((if(a.light)0x70 else 0x80) shl 24),(glow and 0xffffff) or ((if(a.light)0x22 else 0x28) shl 24),glow and 0xffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(0f,0f,gx,p);p.shader=null;c.restore()
        // the fill itself: solid, brightening toward the end
        p.color=(ink and 0xffffff) or 0xb3000000.toInt();c.drawRoundRect(0f,cy-r,x,cy+r,r,r,p)
        p.shader=LinearGradient((x-a.d(48)).coerceAtLeast(0f),0f,x,0f,(ink and 0xffffff),(if(a.light)ink else 0xfffffaf0.toInt()),Shader.TileMode.CLAMP);c.drawRoundRect(0f,cy-r,x,cy+r,r,r,p);p.shader=null}
}
