package app.polka.nativeapp

import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.ceil
import kotlin.math.floor
import java.util.concurrent.Executors

/** Spatial colour comes from the actual rendered cover, not an average/palette/seed colour. */
internal object BookGlowRaster {
 const val PADDING=84
 const val SPREAD=1.08f
 private val executor=Executors.newSingleThreadExecutor{r->Thread(r,"polka-cover-light").apply{isDaemon=true}}
 private val main=Handler(Looper.getMainLooper())
 private val cache=object:android.util.LruCache<String,Bitmap>(24*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
 fun cached(key:String)=cache.get(key)
 fun store(key:String,bitmap:Bitmap){cache.put(key,bitmap)}
 fun build(artwork:Bitmap):Bitmap {
  val layer=Bitmap.createBitmap(artwork.width+PADDING*2,artwork.height+PADDING*2,Bitmap.Config.ARGB_8888)
  try {
   // A brightness lift so every cover gives a visible light: a mostly black cover is raised until its colours
   // glow; a cover that is already bright is left as it is. Saturation is lifted a little too.
   val probe=Bitmap.createScaledBitmap(artwork,16,24,true);var sum=0f;var n=0
   for(y in 0 until probe.height)for(x in 0 until probe.width){val p=probe.getPixel(x,y);sum+=maxOf(Color.red(p),Color.green(p),Color.blue(p));n++}
   if(probe!==artwork)probe.recycle()
   val mean=(sum/maxOf(1,n)/255f).coerceAtLeast(.04f);val lift=(.62f/mean).coerceIn(1f,5f)
   val cm=ColorMatrix().apply{setSaturation(1.25f)};cm.postConcat(ColorMatrix(floatArrayOf(lift,0f,0f,0f,0f, 0f,lift,0f,0f,0f, 0f,0f,lift,0f,0f, 0f,0f,0f,1f,0f)))
   Canvas(layer).drawBitmap(artwork,PADDING.toFloat(),PADDING.toFloat(),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(cm)})
   // A wide, very soft gaussian (computed at a quarter of the size), then fine noise at full size so the long
   // falloff never shows steps.
   val small=Bitmap.createScaledBitmap(layer,maxOf(1,layer.width/4),maxOf(1,layer.height/4),true)
   val soft=try{ShelfFrost.blur(small,7f)}finally{small.recycle()}
   // enlarged to about the size it is shown at, so the noise grains are screen pixels
   val blurred=Bitmap.createScaledBitmap(soft,layer.width*3,layer.height*3,true).also{soft.recycle()}
   val w=blurred.width;val h=blurred.height;val px=IntArray(w*h);blurred.getPixels(px,0,w,0,0,w,h);var seed=0x5bd1e995
   for(k in px.indices){val c=px[k];val al=c ushr 24;if(al==0)continue
    seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5);val n1=(seed and 0xffff)/65535f
    seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5);val nz=(n1-(seed and 0xffff)/65535f)*1.5f
    val na=(al+nz).toInt().coerceIn(0,255);px[k]=(na shl 24) or (c and 0xffffff)}
   val out=Bitmap.createBitmap(px,w,h,Bitmap.Config.ARGB_8888);blurred.recycle()
   return out
  }finally{layer.recycle()}
 }
 /** The overall colour of the light [build] makes from this cover (the same lift and saturation, averaged), so a
  *  light elsewhere — the halo of «Продолжить чтение» — reads as the same light as in the book card. */
 fun tone(artwork:Bitmap):Int{
  val probe=Bitmap.createScaledBitmap(artwork,16,24,true);var sum=0f;var n=0
  for(y in 0 until probe.height)for(x in 0 until probe.width){val p=probe.getPixel(x,y);sum+=maxOf(Color.red(p),Color.green(p),Color.blue(p));n++}
  val mean=(sum/maxOf(1,n)/255f).coerceAtLeast(.04f);val lift=(.62f/mean).coerceIn(1f,5f)
  val cm=ColorMatrix().apply{setSaturation(1.25f)};cm.postConcat(ColorMatrix(floatArrayOf(lift,0f,0f,0f,0f, 0f,lift,0f,0f,0f, 0f,0f,lift,0f,0f, 0f,0f,0f,1f,0f)))
  val lit=Bitmap.createBitmap(16,24,Bitmap.Config.ARGB_8888);Canvas(lit).drawBitmap(probe,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(cm)})
  if(probe!==artwork)probe.recycle()
  var r=0f;var g=0f;var b=0f
  for(y in 0 until 24)for(x in 0 until 16){val p=lit.getPixel(x,y);r+=Color.red(p);g+=Color.green(p);b+=Color.blue(p)}
  lit.recycle();val k=16f*24f
  return Color.rgb((r/k).toInt().coerceIn(0,255),(g/k).toInt().coerceIn(0,255),(b/k).toInt().coerceIn(0,255))
 }
 fun prepare(artwork:Bitmap,done:(Bitmap?)->Unit){executor.execute{
  val result=try{build(artwork)}catch(_:Exception){null}finally{artwork.recycle()}
  main.post{done(result)}
 }}
}

/** This separate layer is mounted ONLY inside book-information heroes. CoverView stays glow-free. */
internal class BookGlowView(private val a:MainActivity,private val cover:CoverView):View(a) {
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 // Handle opacity in the bitmap paint, not View's implicit bounded alpha layer.
 // The penumbra must not be clipped while alpha < 1 and suddenly escape at alpha = 1.
 internal var inkOpacity=255;private set
 override fun onSetAlpha(alpha:Int):Boolean {inkOpacity=alpha;invalidate();return true}
 internal fun haloLayout(coverWidth:Int,coverHeight:Int,top:Int=0):FrameLayout.LayoutParams {
  val scale=coverWidth/96f*BookGlowRaster.SPREAD
  val w=ceil((96+BookGlowRaster.PADDING*2)*scale).toInt()+4
  val h=ceil((144+BookGlowRaster.PADDING*2)*scale).toInt()+4
  return FrameLayout.LayoutParams(w,h,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=floor(top+coverHeight*.5f-h*.5f).toInt()}
 }
 internal fun rasterBounds():RectF {
  val scale=cover.width/96f*BookGlowRaster.SPREAD
  val cx=cover.left-left+cover.width*.5f;val cy=cover.top-top+cover.height*.5f
  val w=(96+BookGlowRaster.PADDING*2)*scale;val h=(144+BookGlowRaster.PADDING*2)*scale
  return RectF(cx-w/2,cy-h/2,cx+w/2,cy+h/2)
 }
 private var raster:Bitmap?=null
 private var key="";private var pending="";private var generation=0
 private val waiters=mutableListOf<()->Unit>()
 private var preparing=false
 private var attemptedKey:String?=null
 internal var rasterBuilds=0;private set
 internal val ready get()=raster!=null
 init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;cover.artworkChanged={refresh()}}
 fun refresh(){if(isAttachedToWindow)prepare{};invalidate()}
 internal fun finishReveal(){invalidate()}
 fun prepare(done:()->Unit){
  if((ready&&key==cover.glowKey())||(!preparing&&attemptedKey==cover.glowKey())){done();return}
  waiters.add(done);if(preparing)return
  preparing=true;attemptedKey=cover.glowKey();val token=generation
  var resolved=false
  fun complete(result:Bitmap?,wanted:String){
   if(resolved||token!=generation)return
   resolved=true;preparing=false;pending=""
   if(result!=null){raster=result;key=wanted}
   val callbacks=waiters.toList();waiters.clear();invalidate();callbacks.forEach{it()}
  }
  // Never leave an invisible modal blocking input if a damaged image cannot decode.
  postDelayed({complete(null,"")},700)
  cover.prepareArtwork{
   if(!resolved&&token==generation){
    val wanted=cover.glowKey();val hit=BookGlowRaster.cached(wanted)
    if(hit!=null)complete(hit,wanted) else {
     pending=wanted;rasterBuilds++
     BookGlowRaster.prepare(cover.glowArtwork()){result->
      if(result!=null)BookGlowRaster.store(wanted,result)
      if(!resolved&&token==generation)complete(result,wanted)
     }
    }
   }
  }
 }
 override fun onDraw(c:Canvas){
  if(cover.width<=0||cover.height<=0)return
  val bitmap=raster?:return
  // Slightly enlarge the blurred cover, making the coloured penumbra visible outside the book.
  // Moderated intensity keeps light covers luminous without an opaque rectangular cloud.
  paint.alpha=((if(a.light)185 else 210)*inkOpacity/255f).toInt()
  c.drawBitmap(bitmap,null,rasterBounds(),paint)
 }
 override fun onDetachedFromWindow(){generation++;pending="";key="";preparing=false;attemptedKey=null;waiters.clear();raster=null;cover.artworkChanged=null;super.onDetachedFromWindow()}
 override fun onAttachedToWindow(){super.onAttachedToWindow();cover.artworkChanged={refresh()};prepare{};invalidate()}
}
