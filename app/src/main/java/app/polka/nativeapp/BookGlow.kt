package app.polka.nativeapp

import kotlin.math.roundToInt
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
   // The whole light stays in floating point — blur, enlargement and all — and is rounded to 8 bits only once, at
   // the very end and with dither. (Rounding the small blurred picture first and then stretching it 12× is what
   // made visible rings: every 8-bit step became a 12-pixel-wide terrace.)
   try{return softLight(small,7f,layer.width*3,layer.height*3)}finally{small.recycle()}
  }finally{layer.recycle()}
 }
 private fun softLight(src:Bitmap,sigma:Float,ow:Int,oh:Int):Bitmap{
  val w=src.width;val h=src.height;val n=w*h;val px=IntArray(n);src.getPixels(px,0,w,0,0,w,h)
  // premultiplied float planes
  val A=FloatArray(n);val R=FloatArray(n);val G=FloatArray(n);val B=FloatArray(n)
  for(i in 0 until n){val c=px[i];val a=(c ushr 24)/255f;A[i]=a;R[i]=((c shr 16) and 255)/255f*a;G[i]=((c shr 8) and 255)/255f*a;B[i]=(c and 255)/255f*a}
  val radius=kotlin.math.ceil(sigma*3).toInt();val ker=FloatArray(radius*2+1){i->kotlin.math.exp(-((i-radius)*(i-radius))/(2*sigma*sigma))};val ks=ker.sum();for(i in ker.indices)ker[i]/=ks
  fun pass(planes:Array<FloatArray>,horizontal:Boolean):Array<FloatArray>{val out=Array(4){FloatArray(n)}
   for(y in 0 until h)for(x in 0 until w){var a=0f;var r=0f;var g=0f;var b=0f
    for(k in -radius..radius){val xx=if(horizontal)x+k else x;val yy=if(horizontal)y else y+k;if(xx<0||xx>=w||yy<0||yy>=h)continue;val j=yy*w+xx;val q=ker[k+radius];a+=planes[0][j]*q;r+=planes[1][j]*q;g+=planes[2][j]*q;b+=planes[3][j]*q}
    val i=y*w+x;out[0][i]=a;out[1][i]=r;out[2][i]=g;out[3][i]=b};return out}
  val blurred=pass(pass(arrayOf(A,R,G,B),true),false)
  // enlarge with a cubic B-spline: smooth to the second derivative, so no facets or creases appear
  fun bs(t:Float,wt:FloatArray){val t2=t*t;val t3=t2*t;val u=1-t;wt[0]=u*u*u/6f;wt[1]=(3*t3-6*t2+4)/6f;wt[2]=(-3*t3+3*t2+3*t+1)/6f;wt[3]=t3/6f}
  val mid=Array(4){FloatArray(ow*h)};val wt=FloatArray(4)
  for(x in 0 until ow){val u=(x+.5f)*w/ow-.5f;val x0=kotlin.math.floor(u).toInt();bs(u-x0,wt)
   for(y in 0 until h)for(c in 0 until 4){var v=0f;for(k in 0..3){val xx=x0-1+k;if(xx<0||xx>=w)continue;v+=blurred[c][y*w+xx]*wt[k]};mid[c][y*ow+x]=v}}
  val out=IntArray(ow*oh);var seed=0x5bd1e995
  fun rnd():Float{seed=seed xor (seed shl 13);seed=seed xor (seed ushr 17);seed=seed xor (seed shl 5);return (seed and 0xffff)/65535f}
  for(y in 0 until oh){val v=(y+.5f)*h/oh-.5f;val y0=kotlin.math.floor(v).toInt();bs(v-y0,wt)
   for(x in 0 until ow){var a=0f;var r=0f;var g=0f;var b=0f
    for(k in 0..3){val yy=y0-1+k;if(yy<0||yy>=h)continue;val j=yy*ow+x;val q=wt[k];a+=mid[0][j]*q;r+=mid[1][j]*q;g+=mid[2][j]*q;b+=mid[3][j]*q}
    if(a<=.0005f){out[y*ow+x]=0;continue}
    // triangular dither of one 8-bit step on every channel
    val d=rnd()-rnd()
    val ai=(a*255f+d).roundToInt().coerceIn(0,255);if(ai==0){out[y*ow+x]=0;continue}
    out[y*ow+x]=(ai shl 24) or (((r/a)*255f+d).roundToInt().coerceIn(0,255) shl 16) or (((g/a)*255f+d).roundToInt().coerceIn(0,255) shl 8) or ((b/a)*255f+d).roundToInt().coerceIn(0,255)}}
  return Bitmap.createBitmap(out,ow,oh,Bitmap.Config.ARGB_8888)
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
