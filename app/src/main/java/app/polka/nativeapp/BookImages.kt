package app.polka.nativeapp

import android.graphics.*
import com.caverock.androidsvg.SVG

/** Decode only local book resources. No resolver, network, scripting or browser. */
object BookImages {
    fun decode(bytes:ByteArray,maxSide:Int=1600):Bitmap? {
        val head=bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart('\uFEFF',' ','\n','\r','\t')
        if(head.contains("<svg",true) || head.startsWith("<?xml") && bytes.toString(Charsets.UTF_8).contains("<svg",true)) return try {
            val svg=SVG.getFromInputStream(bytes.inputStream()); val vb=svg.documentViewBox
            val w=svg.documentWidth.takeIf { it>0 } ?: vb?.width() ?: 600f
            val h=svg.documentHeight.takeIf { it>0 } ?: vb?.height() ?: 800f
            val scale=minOf(1f,maxSide/maxOf(w,h)); val width=(w*scale).toInt().coerceIn(1,maxSide); val height=(h*scale).toInt().coerceIn(1,maxSide)
            Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { svg.setDocumentWidth(width.toFloat()); svg.setDocumentHeight(height.toFloat()); svg.renderToCanvas(Canvas(it)) }
        } catch(_:Exception) { null }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
        var sample=1; while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxSide) sample*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply { inSampleSize=sample })
    }
}
