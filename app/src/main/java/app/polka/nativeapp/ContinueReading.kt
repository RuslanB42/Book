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
/** Native scrolling with a spring snap; the next book remains visible. Kindle-style cards: a plate in the cover's
 *  colour with the real cover rising above its top edge, title and progress beside it (no page counter). */
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
 private val cards=mutableListOf<TiltCard>()
 private val tints=mutableListOf<ContinueTint>()
 private val contents=mutableListOf<View>()
 private val holders=mutableListOf<FrameLayout>()
 private var lastViewport=0
 private var focused=-1
 private val reduced get()=a.prefs.str("motion")=="reduced"
 private val strip=a.line().apply{clipChildren=false;clipToPadding=false}
 private val carousel=object:HorizontalScrollView(a){
  var stride=1;private var flung=false;private var startPage=0;private var glide:ValueAnimator?=null
  private fun last()=readingBooks.lastIndex.coerceAtLeast(0)
  // One gesture moves at most one card: the target is always next to the card where the finger went down.
  override fun dispatchTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN){glide?.cancel();startPage=(scrollX.toFloat()/stride).roundToInt().coerceIn(0,last());flung=false};return super.dispatchTouchEvent(e)}
  init{isHorizontalScrollBarEnabled=false;isHorizontalFadingEdgeEnabled=false;isVerticalFadingEdgeEnabled=false;setFadingEdgeLength(0);overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;contentDescription="Книги в процессе чтения"}
  override fun onMeasure(w:Int,h:Int){resize(MeasureSpec.getSize(w));super.onMeasure(w,h)}
  override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);evenHeights();if(restoring){scrollTo(pendingIndex*stride,0);restoring=false};focus(scrollX)}
  override fun fling(velocityX:Int){flung=true;val dir=if(velocityX>0)1 else if(velocityX<0)-1 else 0;springTo((startPage+dir).coerceIn(0,last()),velocityX)}
  override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN)flung=false;val handled=super.onTouchEvent(e);if((e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)&&!flung)post{snap()};return handled}
  fun snap(){springTo((scrollX.toFloat()/stride).roundToInt().coerceIn((startPage-1).coerceAtLeast(0),(startPage+1).coerceAtMost(last())),0)}
  /** anime.js spring instead of the linear smooth scroll: a quick throw settles with a small, soft overshoot. */
  fun springTo(page:Int,velocity:Int){val target=page*stride;glide?.cancel();if(reduced||target==scrollX){scrollTo(target,0);return}
   val bounce=if(kotlin.math.abs(velocity)>a.d(900))0.22 else 0.12;val sp=Spring.of(bounce,520.0)
   glide=ValueAnimator.ofInt(scrollX,target).apply{duration=sp.duration;interpolator=sp.interpolator;addUpdateListener{scrollTo(it.animatedValue as Int,0)};start()}}
  override fun onScrollChanged(x:Int,y:Int,ox:Int,oy:Int){super.onScrollChanged(x,y,ox,oy);focus(x);if(restoring)return;val n=(x.toFloat()/stride).roundToInt().coerceIn(0,readingBooks.lastIndex.coerceAtLeast(0))
   if(n!=focused){if(focused>=0)performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);focused=n}
   if(a.continueBookId!=readingBooks.getOrNull(n)?.str("id"))readingBooks.getOrNull(n)?.let{a.prefetchContent(it)};a.continueBookId=readingBooks.getOrNull(n)?.str("id")}
 }
 init{
  orientation=VERTICAL;clipChildren=false;clipToPadding=false
  addView(a.text("Продолжить чтение",20f,bold=true,serif=true));a.space(this,14)
  readingBooks.forEachIndexed{i,book->
   val card=TiltCard(a).apply{floating(this);isFocusable=true;clipChildren=false;clipToPadding=false;contentDescription="Продолжить: ${book.str("title")}"}
   // Kindle-like plate: tinted with the cover's colour; the real cover stands on it and rises above its top edge.
   val panel=FrameLayout(a).apply{minimumHeight=a.d(PANEL)}
   val tint=ContinueTint(a,book);panel.addView(tint,FrameLayout.LayoutParams(-1,-1));tints.add(tint)
   val words=a.column().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,a.d(14),a.d(16),a.d(14));minimumHeight=a.d(PANEL)}
   words.addView(a.text(book.str("title"),16f,Color.WHITE,bold=true,serif=true).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END})
   val pct=(book.optJSONObject("progress")?.optDouble("pct",0.0)?:0.0).toFloat().coerceIn(0f,1f)
   val ch=book.optJSONObject("progress")?.optInt("ch",-1)?:-1
   val meta=listOfNotNull(MainActivity.authors(book).takeIf{it.isNotBlank()},if(ch>=0)"Глава ${ch+1}" else null,"${(pct*100).roundToInt()}%").joinToString("  ·  ")
   a.space(words,6);words.addView(a.text(meta,12f,0xc8ffffff.toInt()).apply{maxLines=1;ellipsize=TextUtils.TruncateAt.END})
   a.space(words,10);words.addView(HeroProgress(a,pct),LayoutParams(-1,a.d(8)))
   panel.addView(words,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_VERTICAL).apply{marginStart=a.d(14+COVER_W+14)})
   card.addView(panel,FrameLayout.LayoutParams(-1,-2).apply{topMargin=a.d(OVERHANG)});contents.add(words)
   val cover=CoverView(a,book,a.cover(book)).apply{isClickable=true;contentDescription="О книге ${book.str("title")}";setOnClickListener{a.detail(book,this)}}
   card.addView(cover,FrameLayout.LayoutParams(a.d(COVER_W),a.d(COVER_W*3/2),Gravity.TOP or Gravity.START).apply{marginStart=a.d(14)})
   card.setOnClickListener{a.openBook(book,cover)}
   cards.add(card)
   // The focus transform lives on a holder, so the press tilt on the card never fights it.
   val holder=FrameLayout(a).apply{clipChildren=false;clipToPadding=false};holder.addView(card,FrameLayout.LayoutParams(a.d(284),-2));holders.add(holder)
   strip.addView(holder,LayoutParams(-2,-2).apply{if(i>0)marginStart=a.d(12)})
  }
  carousel.addView(strip,FrameLayout.LayoutParams(-2,-2));addView(carousel,LayoutParams(-1,-2).apply{marginStart=-a.d(20);marginEnd=-a.d(20);topMargin=-a.d(6);bottomMargin=-a.d(14)})
 }
 override fun onAttachedToWindow(){super.onAttachedToWindow();intro()}
 /** First appearance in the session: the cards slide in one after another on a spring, and the bars fill up. */
 private fun intro(){if(introPlayed)return;introPlayed=true;if(reduced)return
  val delays=Stagger.delays(cards.size,70,start=40,ease=Ease.outQuad)
  cards.forEachIndexed{i,c->c.alpha=0f;c.translationX=a.d(56).toFloat();val sp=Spring.of(.16,560.0)
   c.animate().alpha(1f).translationX(0f).setStartDelay(delays[i]).setDuration(sp.duration).setInterpolator(sp.interpolator).start()
   (contents[i] as ViewGroup).let{w->findProgress(w)?.grow(delays[i]+120)}}}
 private fun findProgress(v:View):HeroProgress?{if(v is HeroProgress)return v;if(v is ViewGroup)for(i in 0 until v.childCount)findProgress(v.getChildAt(i))?.let{return it};return null}
 /** Same floating card as the statistics plates: no border of its own, a wide soft shadow underneath. */
 private fun floating(v:View){
  val radius=a.d(20).toFloat()
  v.elevation=a.d(if(light)8 else 10).toFloat()
  v.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,a.d(OVERHANG),view.width,view.height,radius)}}
  if(Build.VERSION.SDK_INT>=28){v.outlineAmbientShadowColor=if(light)0x1a000000 else 0x80000000.toInt();v.outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}
 }
 private fun resize(viewport:Int){if(viewport<=0||viewport==lastViewport)return;lastViewport=viewport;val content=(viewport-a.d(40)).coerceAtLeast(1)
  // The card in focus sits at the normal page margin on the left; the next one peeks in on the right.
  val width=if(cards.size<=1)content else (content*.86f).toInt();val gap=a.d(10);carousel.stride=width+gap
  cards.forEach{c->c.layoutParams=(c.layoutParams as FrameLayout.LayoutParams).apply{this.width=width}}
  holders.forEachIndexed{i,h->h.layoutParams=(h.layoutParams as LayoutParams).apply{marginStart=if(i>0)gap else 0}}
  strip.setPadding(a.d(20),a.d(6),(viewport-width-a.d(20)).coerceAtLeast(a.d(20)),a.d(26))
  pendingIndex=readingBooks.indexOfFirst{it.str("id")==a.continueBookId}.coerceAtLeast(0);restoring=true}
 /** Every card gets the height of the tallest one, so all of them scale the same way. */
 private fun evenHeights(){if(cards.size<=1)return;val tallest=cards.maxOf{it.measuredHeight};if(tallest<=0)return
  cards.forEach{c->if(c.minimumHeight<tallest){c.minimumHeight=tallest}}}
 /** Continuous with the distance from focus: neighbours are smaller and dimmer, their text sinks and fades, and the
  *  cover inside each card drifts the other way (parallax), so the cards feel like windows onto the covers. */
 private fun focus(x:Int){val stride=carousel.stride.toFloat().coerceAtLeast(1f);val anchor=x+a.d(20)
  holders.forEachIndexed{i,h->if(h.width<=0)return@forEachIndexed;val d=((h.left-anchor)/stride).coerceIn(-1.5f,1.5f);val k=kotlin.math.abs(d).coerceAtMost(1f)
   if(holders.size<=1)return@forEachIndexed
   val s=1f-.09f*k;h.pivotX=if(d>0)0f else h.width.toFloat();h.pivotY=h.height/2f;h.scaleX=s;h.scaleY=s;h.alpha=1f-.38f*k
   contents[i].alpha=1f-.55f*k;contents[i].translationY=a.d(12)*k
   // the card in focus floats higher (deeper shadow); the others sink a little
   cards[i].translationZ=a.d(8)*(1f-k)}}
 companion object{private var introPlayed=false;private const val OVERHANG=22;private const val PANEL=112;private const val COVER_W=76}
}

/** A card that leans toward the finger while pressed (3D tilt + a slight dip) and springs back on release. */
internal class TiltCard(private val a:MainActivity):FrameLayout(a){
 init{cameraDistance=a.resources.displayMetrics.density*9000;clipChildren=true}
 private fun reduced()=a.prefs.str("motion")=="reduced"
 override fun dispatchTouchEvent(e:MotionEvent):Boolean{
  if(!reduced()&&width>0&&height>0)when(e.actionMasked){
   MotionEvent.ACTION_DOWN->{pivotX=width/2f;pivotY=height/2f;lean(e,true)}
   MotionEvent.ACTION_MOVE->lean(e,false)
   MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val sp=Spring.of(.38,520.0);animate().rotationX(0f).rotationY(0f).scaleX(1f).scaleY(1f).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
  }
  return super.dispatchTouchEvent(e)}
 private fun lean(e:MotionEvent,first:Boolean){val nx=(e.x/width-.5f).coerceIn(-.5f,.5f);val ny=(e.y/height-.5f).coerceIn(-.5f,.5f)
  val rx=-ny*7f;val ry=nx*7f
  if(first){val sp=Spring.of(.1,260.0);animate().rotationX(rx).rotationY(ry).scaleX(.965f).scaleY(.965f).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
  else{animate().cancel();rotationX=rx;rotationY=ry;scaleX=.965f;scaleY=.965f}}
}

/** The plate behind the text: the cover's own colour, a little lighter on the left where the cover stands and
 *  deeper to the right, with a faint top sheen — like the Kindle "Books you read last" cards. */
internal class ContinueTint(private val a:MainActivity,private val book:JSONObject):View(a){
 private val radius=a.d(20).toFloat()
 private var tint=0;private var requested=false
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 private fun color():Int{if(tint!=0)return tint
  a.cover(book)?.let{tint=tintOf(it);return tint}
  if(book.optJSONObject("cover")!=null){if(!requested){requested=true;a.requestCover(book){bmp->tint=if(bmp!=null)tintOf(bmp) else deep(CoverView(a,book,null).colors(book).first);invalidate()}};return 0}
  tint=deep(CoverView(a,book,null).colors(book).first);return tint}
 private fun deep(c:Int):Int{val hsv=FloatArray(3);Color.colorToHSV(c,hsv);hsv[1]=(hsv[1]*1.1f).coerceIn(.18f,.55f);hsv[2]=.40f;return Color.HSVToColor(hsv)}
 private fun tintOf(b:Bitmap):Int{val s=Bitmap.createScaledBitmap(b,8,12,true);var r=0;var g=0;var bl=0;var n=0
  for(y in 0 until 12)for(x in 0 until 8){val p=s.getPixel(x,y);r+=Color.red(p);g+=Color.green(p);bl+=Color.blue(p);n++}
  if(s!==b)s.recycle();return deep(Color.rgb(r/n,g/n,bl/n))}
 private fun shade(c:Int,v:Float):Int{val hsv=FloatArray(3);Color.colorToHSV(c,hsv);hsv[2]=(hsv[2]*v).coerceIn(0f,1f);return Color.HSVToColor(hsv)}
 private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val sheen=Paint(Paint.ANTI_ALIAS_FLAG)
 override fun onDraw(c:Canvas){if(width<1||height<1)return;val w=width.toFloat();val h=height.toFloat()
  val base=color();if(base==0){p.shader=null;p.color=if(a.light)0xffe6e4df.toInt() else 0xff2a2a2d.toInt();c.drawRoundRect(0f,0f,w,h,radius,radius,p);return}
  p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,w,h,shade(base,1.18f),shade(base,.62f),Shader.TileMode.CLAMP)
  c.drawRoundRect(0f,0f,w,h,radius,radius,p)
  sheen.shader=LinearGradient(0f,0f,0f,h*.6f,0x1fffffff,0x00ffffff,Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,w,h,radius,radius,sheen)}
}

/** Thin white bar on the card's tint: a faint track, the fill with a soft spark at its end; grows in on first show. */
internal class HeroProgress(private val a:MainActivity,private val fraction:Float):View(a){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var shown=1f
 init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
 fun grow(delay:Long){shown=0f;ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay;duration=900;interpolator=Ease.outExpo;addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
 override fun onDraw(c:Canvas){val h=a.d(4).toFloat();val cy=height/2f;val w=width.toFloat();val r=h/2
  p.shader=null;p.color=0x38ffffff;c.drawRoundRect(0f,cy-r,w,cy+r,r,r,p)
  val f=fraction*shown;if(f<=0f)return;val x=(w*f).coerceIn(h,w)
  val g=a.d(12).toFloat();p.shader=RadialGradient(x-r,cy,g,intArrayOf(0x66ffffff,0x00ffffff),null,Shader.TileMode.CLAMP);c.drawCircle(x-r,cy,g,p)
  p.shader=null;p.color=Color.WHITE;c.drawRoundRect(0f,cy-r,x,cy+r,r,r,p)}
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
