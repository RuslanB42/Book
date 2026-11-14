package app.polka.nativeapp

import android.graphics.*
import android.os.Build
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import android.text.TextUtils
import org.json.JSONObject
import android.animation.ValueAnimator
import kotlin.math.roundToInt

internal object ReadingQueue {
 fun books(all:List<JSONObject>)=all.filter{!it.optBoolean("noFile")&&Library.status(it)=="reading"}.sortedWith(compareByDescending<JSONObject>{it.optLong("openedAt")}.thenBy{it.str("id")})
}
/** Native scrolling with a spring snap; the next book remains visible. Kindle-style cards: the real cover stands on
 *  the left and rises above the card's top edge; the card is a gradient of the cover's own colour; title, author,
 *  progress and the two buttons on the right. */
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
 private val cards=mutableListOf<RisingCoverCard>()
 private val covers=mutableListOf<CoverView>()
 private val texts=mutableListOf<LinearLayout>()
 private val bars=mutableListOf<HeroProgress>()
 private val tints=IntArray(readingBooks.size)
 private val glow=AmbientGlow(a)
 private val holders=mutableListOf<FrameLayout>()
 private var lastViewport=0
 private val reduced get()=a.prefs.str("motion")=="reduced"
 /** How far the cover rises above the card. */
 private val rise=a.d(22)
 private val strip=a.line().apply{clipChildren=false;clipToPadding=false}
 private val carousel=object:HorizontalScrollView(a){
  var stride=1;private var flung=false;private var startPage=0;private var glide:ValueAnimator?=null
  private fun last()=readingBooks.lastIndex.coerceAtLeast(0)
  // One gesture moves at most one card: the target is always next to the card where the finger went down.
  override fun dispatchTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN){glide?.cancel();startPage=(scrollX.toFloat()/stride).roundToInt().coerceIn(0,last());flung=false};return super.dispatchTouchEvent(e)}
  init{isHorizontalScrollBarEnabled=false;isHorizontalFadingEdgeEnabled=false;isVerticalFadingEdgeEnabled=false;setFadingEdgeLength(0);overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false;contentDescription="Книги в процессе чтения"}
  override fun onMeasure(w:Int,h:Int){resize(MeasureSpec.getSize(w));super.onMeasure(w,h)}
  override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);evenHeights();if(restoring){scrollTo(pendingIndex*stride,0);restoring=false};focus(scrollX)}
  override fun fling(velocityX:Int){flung=true;val dir=if(velocityX>0)1 else if(velocityX<0)-1 else 0;val want=startPage+dir
   springTo(want.coerceIn(0,last()),velocityX)}
  override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN)flung=false;val handled=super.onTouchEvent(e);if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&!flung)post{snap()};return handled}
  fun snap(){springTo((scrollX.toFloat()/stride).roundToInt().coerceIn((startPage-1).coerceAtLeast(0),(startPage+1).coerceAtMost(last())),0)}
  /** Settles the way the years page in the tier list does: one even glide, no overshoot, quicker after a quick throw. */
  fun springTo(page:Int,velocity:Int){val target=page*stride;glide?.cancel();if(reduced||target==scrollX){scrollTo(target,0);return}
   val v=kotlin.math.abs(velocity).toFloat();val span=kotlin.math.abs(target-scrollX).toFloat()
   glide=ValueAnimator.ofInt(scrollX,target).apply{duration=if(v>0f)(span/v*1000f).toLong().coerceIn(160L,340L) else 340L
    interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{scrollTo(it.animatedValue as Int,0)};start()}}
  override fun onScrollChanged(x:Int,y:Int,ox:Int,oy:Int){super.onScrollChanged(x,y,ox,oy);focus(x);if(restoring)return;val n=(x.toFloat()/stride).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0))
   if(a.continueBookId!=readingBooks.getOrNull(n)?.str("id"))readingBooks.getOrNull(n)?.let{a.prefetchContent(it)};a.continueBookId=readingBooks.getOrNull(n)?.str("id")}
 }
 init{
  orientation=VERTICAL;clipChildren=false;clipToPadding=false
  addView(a.text("Продолжить чтение",17f,bold=true))
  readingBooks.forEachIndexed{i,book->
   val card=RisingCoverCard(a).apply{clipChildren=false;clipToPadding=false;isFocusable=true;contentDescription="Продолжить: ${book.str("title")}"}
   floating(card,book,i)
   val cover=CoverView(a,book,a.cover(book));card.addView(cover,FrameLayout.LayoutParams(a.d(92),a.d(138),Gravity.BOTTOM or Gravity.START).apply{marginStart=a.d(16);bottomMargin=a.d(14)})
   card.cover=cover;covers.add(cover)
   val words=a.column().apply{gravity=Gravity.BOTTOM;setPadding(a.d(124),a.d(16),a.d(18),a.d(16));clipChildren=false;clipToPadding=false}
   words.addView(a.text(book.str("title"),15f,a.fg,true).apply{maxLines=1;isSingleLine=true;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,3);words.addView(a.text(MainActivity.authors(book),12.5f,a.muted).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,12)
   val pct=(book.optJSONObject("progress")?.optDouble("pct",0.0)?:0.0).toFloat().coerceIn(0f,1f)
   val meter=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
   val bar=HeroProgress(a,pct,!reduced);bars.add(bar)
   meter.addView(bar,LayoutParams(0,a.d(8),1f))
   meter.addView(a.text("${(pct*100).roundToInt()}%",11f,a.muted),LayoutParams(-2,-2).apply{marginStart=a.d(8)})
   words.addView(meter,LayoutParams(-1,-2));a.space(words,12)
   val buttons=a.line()
   buttons.addView(pill("Читать",true,"Читать: ${book.str("title")}"){a.openBook(book,cover)},LayoutParams(0,-2,1f))
   buttons.addView(pill("О книге",false,"О книге ${book.str("title")}"){a.detail(book,cover)},LayoutParams(0,-2,1f).apply{marginStart=a.d(8)})
   words.addView(buttons)
   card.addView(words,0,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));card.words=words;texts.add(words)
   card.setOnClickListener{a.openBook(book,cover)};a.press(card)
   cards.add(card)
   // The focus transform lives on a holder, so the press feedback on the card never fights it. The holder's top
   // padding makes room for the rising cover INSIDE its bounds: when the holder is dimmed (alpha < 1) its layer is
   // clipped to those bounds, and without the room the cover's top was cut off on the neighbouring cards.
   val holder=FrameLayout(a).apply{clipChildren=false;clipToPadding=false;setPadding(0,rise+a.d(6),0,0)
    // dimmed without an offscreen layer: a layer is clipped to the holder, so the card's shadow blinked
    // (cut off, then whole again) every time the opacity left or reached 1 while paging
    forceHasOverlappingRendering(false)};holder.addView(card,FrameLayout.LayoutParams(a.d(300),-2));holders.add(holder)
   strip.addView(holder,LayoutParams(-2,-2).apply{if(i>0)marginStart=a.d(12)})
  }
  carousel.addView(strip,FrameLayout.LayoutParams(-2,-2))
  // a soft glow in the focused book's colour behind the cards; it changes colour as you scroll
  val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
  // the light layer reaches well above and below the cards, so its soft edge is never cut
  // (the glow behind the cards was removed on request — the cards sit on the plain page)
  stage.addView(carousel,FrameLayout.LayoutParams(-1,-2))
  addView(stage,LayoutParams(-1,-2).apply{marginStart=-a.d(20);marginEnd=-a.d(20);bottomMargin=-a.d(14)})
 }
 /** Card fill: the cover's colour, lighter on the left (next to the cover) and deepening to the right, like Kindle. */
 private fun floating(card:FrameLayout,book:JSONObject,i:Int){
  val radius=a.d(18).toFloat()
  // the halo has the colour of the book card's own light (the same lifted cover), so both read as one light
  fun paint(c:IntArray,b:Bitmap?=null){tints[i]=b?.let{runCatching{BookGlowRaster.tone(it)}.getOrNull()}?:glow.tone(c[0]);glow.invalidate()}
  val first=shades(null,book);paint(first)
  // the usual card grey; the cover's colour only tints the glow behind the cards
  val fill=GradientDrawable().apply{setColor(if(light)Color.WHITE else 0xff242426.toInt());cornerRadius=radius}
  card.background=Floating(a,fill,radius)
  a.cover(book).let{b->if(b!=null)shades(b,book).let{paint(it,b)} else if(book.optJSONObject("cover")!=null)a.requestCover(book){bmp->if(bmp!=null)shades(bmp,book).let{paint(it,bmp);focus(carousel.scrollX)}}}
  card.elevation=0f
  card.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,radius)}}
  if(Build.VERSION.SDK_INT>=28){card.outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();card.outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
 }
 private fun shades(b:Bitmap?,book:JSONObject):IntArray{
  // the cover's leading colour: pixels weighted by how colourful and bright they are, so a yellow cover with black
  // type gives an amber card, not the muddy average of all its pixels
  val base=if(b!=null){val s=Bitmap.createScaledBitmap(b,12,18,true);var r=0f;var g=0f;var bl=0f;var w=0f;val t=FloatArray(3)
   for(y in 0 until 18)for(x in 0 until 12){val p=s.getPixel(x,y);Color.colorToHSV(p,t);val k=(t[1]*t[2]).let{it*it}+.004f;r+=Color.red(p)*k;g+=Color.green(p)*k;bl+=Color.blue(p)*k;w+=k}
   if(s!==b)s.recycle();Color.rgb((r/w).toInt(),(g/w).toInt(),(bl/w).toInt())} else CoverView(a,book,null).colors(book).first
  val hsv=FloatArray(3);Color.colorToHSV(base,hsv);val sat=(hsv[1]*.9f).coerceIn(.12f,.62f)
  fun at(v:Float,s:Float)=Color.HSVToColor(floatArrayOf(hsv[0],s,v))
  return if(light)intArrayOf(at(.62f,sat),at(.52f,sat*.9f),at(.38f,sat*.7f)) else intArrayOf(at(.52f,sat),at(.40f,sat*.85f),at(.21f,sat*.5f))}
 private fun pill(label:String,primary:Boolean,description:String,click:()->Unit):TextView=a.text(label,12.5f,if(primary)a.bg else a.fg,true).apply{
  gravity=Gravity.CENTER;minHeight=a.d(32);maxLines=1;ellipsize=TextUtils.TruncateAt.END;setPadding(a.d(6),0,a.d(6),0);contentDescription=description;isClickable=true;isFocusable=true
  background=if(primary)GradientDrawable().apply{setColor(a.fg);cornerRadius=a.d(16).toFloat()} else GradientDrawable().apply{setColor((a.fg and 0xffffff) or ((if(light)0x10 else 0x1c) shl 24));cornerRadius=a.d(16).toFloat()}
  setOnClickListener{click()};pressy(this)}
 /** The buttons sink clearly under the finger and spring back when released. */
 private fun pressy(v:View){v.setOnTouchListener{view,e->view.background?.setHotspot(e.x,e.y);if(reduced)return@setOnTouchListener false
   when(e.actionMasked){
    MotionEvent.ACTION_DOWN->view.animate().scaleX(.9f).scaleY(.9f).alpha(.82f).setDuration(110).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()
    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val sp=Spring.of(.5,460.0);view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}}
   false}}
 private fun resize(viewport:Int){if(viewport<=0||viewport==lastViewport)return;lastViewport=viewport;val content=(viewport-a.d(40)).coerceAtLeast(1)
  // The card in focus sits at the normal page margin on the left; the next one peeks in on the right.
  val width=if(cards.size<=1)content else (content*.9f).toInt().coerceAtMost(a.d(340));val gap=a.d(12);carousel.stride=width+gap
  cards.forEach{c->c.layoutParams=(c.layoutParams as FrameLayout.LayoutParams).apply{this.width=width}}
  holders.forEachIndexed{i,h->h.layoutParams=(h.layoutParams as LayoutParams).apply{marginStart=if(i>0)gap else 0}}
  strip.setPadding(a.d(20),a.d(8),(viewport-width-a.d(20)).coerceAtLeast(a.d(20)),a.d(22))
  pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0);restoring=true}
 /** Every card gets the height of the tallest one, so all of them scale the same way. */
 private fun evenHeights(){if(cards.size<=1)return;val tallest=cards.maxOf{it.measuredHeight};if(tallest<=0)return
  cards.forEach{c->if(c.floor<tallest){c.floor=tallest;c.requestLayout()}}}
 /** Everything follows the scroll position continuously (no timers), so it tracks the finger exactly:
  *  - neighbours are smaller and dimmer; the focused card floats higher;
  *  - the cover in focus stands tall above its card, leaning a touch toward the scroll; on the neighbours it
  *    shrinks back into the card (scaled from its foot, so it never pokes out below);
  *  - the text lines slide at different speeds (title slowest, buttons fastest) — a layered parallax;
  *  - the glow behind blends from one book's colour into the next;
  *  - when a card lands in focus for the first time, its progress bar fills up. */
 private fun focus(x:Int){val stride=carousel.stride.toFloat().coerceAtLeast(1f);val anchor=x+a.d(20)
  holders.forEachIndexed{i,h->if(h.width<=0)return@forEachIndexed;val d=(h.left-anchor)/stride;val k=kotlin.math.abs(d).coerceAtMost(1f)
   if(k<.08f)bars[i].reveal()
   if(holders.size<=1)return@forEachIndexed
   // a light depth: the neighbour is a touch smaller and dimmer and grows in evenly as it is pulled to the middle
   val s=1f-.04f*k;h.pivotX=if(d>0)0f else h.width.toFloat();h.pivotY=h.height.toFloat();h.scaleX=s;h.scaleY=s;h.alpha=1f-.3f*k}
  halos(x)}
 /** Each card's halo follows the card itself (its place and scale), strongest on the card in focus. */
 private val gAt=IntArray(2);private val cAt=IntArray(2)
 private fun halos(x:Int){if(reduced||cards.isEmpty()||glow.width<=0)return;val stride=carousel.stride.toFloat().coerceAtLeast(1f);val anchor=x+a.d(20)
  glow.getLocationInWindow(gAt);val rects=ArrayList<RectF>();val colors=ArrayList<Int>();val strengths=ArrayList<Float>()
  cards.forEachIndexed{i,c->if(c.width<=0)return@forEachIndexed;val h=holders[i];val k=kotlin.math.abs((h.left-anchor)/stride).coerceAtMost(1f)
   c.getLocationInWindow(cAt);val s=h.scaleX;val l=(cAt[0]-gAt[0]).toFloat();val t=(cAt[1]-gAt[1]).toFloat()
   rects.add(RectF(l,t,l+c.width*s,t+c.height*s));colors.add(tints[i]);strengths.add((1f-k)*(1f-k))}
  cards.firstOrNull{it.width>0}?.let{glow.baseW=it.width;glow.baseH=it.height}
  glow.set(rects,colors,strengths,light)}
 private fun blend(p:Int,q:Int,t:Float):Int{
  val r=(Color.red(p)*(1-t)+Color.red(q)*t).roundToInt()
  val g=(Color.green(p)*(1-t)+Color.green(q)*t).roundToInt()
  val b=(Color.blue(p)*(1-t)+Color.blue(q)*t).roundToInt()
  return Color.rgb(r,g,b)
 }
}

/** Approved «ореол по периметру, ещё мягче»: a soft light in the cover's colour all round each card (stronger the
 *  closer the card is to focus, so it travels with the cards as you page). Two gaussian layers — a near one hugging
 *  the edge and a wide faint one — baked once per card size as an alpha mask and tinted while drawing. */
internal class AmbientGlow(private val a:MainActivity):View(a){
 private class Halo(val rect:RectF=RectF(),var color:Int=0,var strength:Float=0f)
 private val halos=mutableListOf<Halo>()
 private var light=false
 private val p=Paint(Paint.FILTER_BITMAP_FLAG)
 private var near:Bitmap?=null;private var far:Bitmap?=null;private var maskKey=""
 private val pad get()=a.d(110).toFloat()
 private val clip=Path()
 private val dst=RectF()
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 /** Cards in this view's coordinates (already scaled), their light colours and how much each one glows (0..1). */
 fun set(rects:List<RectF>,colors:List<Int>,strengths:List<Float>,l:Boolean){light=l
  while(halos.size<rects.size)halos.add(Halo());while(halos.size>rects.size)halos.removeAt(halos.size-1)
  rects.indices.forEach{i->halos[i].rect.set(rects[i]);halos[i].color=colors[i];halos[i].strength=strengths[i]};invalidate()}
 /** The light's colour at one steady brightness: blends between two books never dip or flare, and a dark (or black)
  *  cover still gives a clearly visible light; a colourless cover gives a soft warm white. */
 fun tone(c:Int):Int{val hsl=FloatArray(3);androidx.core.graphics.ColorUtils.colorToHSL(c,hsl)
  val hsv=FloatArray(3);Color.colorToHSV(c,hsv)
  if(hsv[1]<=.125f){hsl[0]=36f;hsl[1]=.3f}else hsl[1]=(hsl[1]*1.4f).coerceIn(.6f,.9f)
  hsl[2]=if(a.light).58f else .62f;return androidx.core.graphics.ColorUtils.HSLToColor(hsl)}
 /** A soft light BEHIND the card (as in the statistics plaques): one smooth radial falloff in the cover's colour,
  *  wider than the card so it shows round all its sides, dithered so it never bands or ripples. */
 private val m=Matrix()
 private fun light(c:Canvas,hl:Halo){val r=hl.rect;val cx=r.centerX();val cy=r.centerY()
  val rx=r.width()/2f+a.d(64);val ry=r.height()/2f+a.d(58)
  val peak=(if(light).42f else .62f)*hl.strength;val rgb=hl.color and 0xffffff
  val n=12;val stops=FloatArray(n){it/(n-1f)};val cols=IntArray(n){val t=stops[it];(((255*peak*kotlin.math.exp(-3.2f*t*t)).toInt().coerceIn(0,255)) shl 24) or rgb};cols[n-1]=rgb
  p.isDither=true;p.shader=RadialGradient(0f,0f,1f,cols,stops,Shader.TileMode.CLAMP);m.setScale(rx,ry);m.postTranslate(cx,cy);p.shader.setLocalMatrix(m)
  c.drawOval(cx-rx,cy-ry,cx+rx,cy+ry,p);p.shader=null}
 override fun onDraw(c:Canvas){if(width<1||height<1)return
  for(hl in halos){if(hl.strength<.01f||hl.color==0||hl.rect.width()<2f)continue
   // the light never lies over the neighbouring cards (they are dimmed, so it would show through them)
   c.save();clip.rewind();halos.forEach{o->if(o!==hl&&o.rect.width()>2f){val rr=a.d(18)*o.rect.width()/baseW;clip.addRoundRect(o.rect,rr,rr,Path.Direction.CW)}}
   if(!clip.isEmpty)c.clipOutPath(clip)
   light(c,hl);c.restore()}}
 /** The unscaled card size the masks are baked for. */
 var baseW=1;var baseH=1
}

/** The card's height comes from its text (at least 128dp), never from the cover: the taller cover hangs out above. */
internal class RisingCoverCard(private val a:MainActivity):FrameLayout(a){
 var words:View?=null
 var cover:View?=null
 var floor=a.d(128)
 private fun reduced()=a.prefs.str("motion")=="reduced"
 /** Pressing the card dips it (the shared press effect) and lifts the book out of it a little, like picking it up. */
 override fun dispatchTouchEvent(e:MotionEvent):Boolean{val c=cover
  if(c!=null&&!reduced())when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{c.pivotX=c.width/2f;c.pivotY=c.height.toFloat();val sp=Spring.of(.2,300.0);c.animate().translationY(-a.d(7).toFloat()).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val sp=Spring.of(.45,520.0);c.animate().translationY(0f).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
  }
  return super.dispatchTouchEvent(e)}
 override fun onMeasure(w:Int,h:Int){val text=words;var hh=floor
  if(text!=null){text.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(w),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));hh=maxOf(hh,text.measuredHeight)}
  super.onMeasure(w,MeasureSpec.makeMeasureSpec(hh,MeasureSpec.EXACTLY))}
}

/** Thin white bar on the card: a faint track and the fill with a soft spark at its end. */
internal class HeroProgress(private val a:MainActivity,private val fraction:Float,animated:Boolean=false):View(a){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var shown=if(animated)0f else 1f;private var revealed=!animated
 /** Fill the bar the first time its card comes into focus. */
 fun reveal(){if(revealed)return;revealed=true;ValueAnimator.ofFloat(0f,1f).apply{startDelay=120;duration=600;interpolator=android.view.animation.PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 fun grow(delay:Long){shown=0f;ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay;duration=900;interpolator=Ease.outExpo;addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
 override fun onDraw(c:Canvas){val h=a.d(4).toFloat();val cy=height/2f;val w=width.toFloat();val r=h/2
  val ink=a.fg and 0xffffff
  p.shader=null;p.color=ink or ((if(a.light)0x1a else 0x26) shl 24);c.drawRoundRect(0f,cy-r,w,cy+r,r,r,p)
  val f=fraction*shown;if(f<=0f)return;val x=(w*f).coerceIn(h,w)
  val g=a.d(12).toFloat();p.shader=RadialGradient(x-r,cy,g,intArrayOf(ink or (0x40 shl 24),ink),null,Shader.TileMode.CLAMP);c.drawCircle(x-r,cy,g,p)
  p.shader=null;p.color=a.fg;c.drawRoundRect(0f,cy-r,x,cy+r,r,r,p)}
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
