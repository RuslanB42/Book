package app.polka.nativeapp

import android.graphics.*
import kotlin.math.*

/** CSS filter blur(3px): isotropic Gaussian on a transparent, premultiplied layer.
 * The background is deliberately NOT part of the filter (brightness must not darken it).
 * Called on the CPU executor, once per visible cover set/size, never per animation frame.
 */
internal object ShelfFrost {
 private val cache=object:android.util.LruCache<String,Bitmap>(3072){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount/1024}
 internal var builds=0;private set
 @Synchronized fun prepared(key:String,build:()->Bitmap):Bitmap=cache.get(key)?:build().also{builds++;cache.put(key,it)}

 fun blur(source:Bitmap,sigma:Float):Bitmap {
  val w=source.width;val h=source.height;val n=w*h;val input=IntArray(n);source.getPixels(input,0,w,0,0,w,h)
  val radius=ceil(sigma*3).toInt().coerceAtLeast(1);val kernel=FloatArray(radius*2+1){i->exp(-((i-radius).toDouble().pow(2))/(2*sigma*sigma)).toFloat()};val sum=kernel.sum();kernel.indices.forEach{kernel[it]/=sum}
  val horizontal=FloatArray(n*4)
  for(y in 0 until h){if(Thread.currentThread().isInterrupted)throw InterruptedException();for(x in 0 until w){val at=(y*w+x)*4
   for(k in -radius..radius){val xx=x+k;if(xx !in 0 until w)continue;val c=input[y*w+xx];val weight=kernel[k+radius];val alpha=Color.alpha(c)/255f;horizontal[at]+=Color.alpha(c)*weight;horizontal[at+1]+=Color.red(c)*alpha*weight;horizontal[at+2]+=Color.green(c)*alpha*weight;horizontal[at+3]+=Color.blue(c)*alpha*weight}
  }}
  val output=IntArray(n)
  for(y in 0 until h){if(Thread.currentThread().isInterrupted)throw InterruptedException();for(x in 0 until w){var a=0f;var r=0f;var g=0f;var b=0f
   for(k in -radius..radius){val yy=y+k;if(yy !in 0 until h)continue;val at=(yy*w+x)*4;val weight=kernel[k+radius];a+=horizontal[at]*weight;r+=horizontal[at+1]*weight;g+=horizontal[at+2]*weight;b+=horizontal[at+3]*weight}
   output[y*w+x]=if(a<.5f)Color.TRANSPARENT else Color.argb(a.roundToInt().coerceIn(0,255),(r*255/a).roundToInt().coerceIn(0,255),(g*255/a).roundToInt().coerceIn(0,255),(b*255/a).roundToInt().coerceIn(0,255))
  }}
  return Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888)
 }
}

