package app.polka.nativeapp

import android.graphics.*
import android.util.LruCache

/** Tiny pre-blurred copies of covers for the matte shelf glass: one per cover look, made once and kept. */
internal object FrostCache {
    /** The copy is drawn this much wider/taller than the cover on each side, so its blurred edge fades out softly. */
    const val MARGIN=.25f
    const val MARGIN_Y=MARGIN*2f/3f
    private const val W=32;private const val H=48;private const val PX=8;private const val PY=8
    private val cache=object:LruCache<String,Bitmap>(400){}
    fun get(cover:CoverView):Bitmap?{if(cover.waiting)return null;val key=cover.glowKey();cache.get(key)?.let{return it}
        return try{
            val art=cover.glowArtwork() // 96×144 render of the cover (picture or typeset placeholder)
            val small=Bitmap.createBitmap(W+2*PX,H+2*PY,Bitmap.Config.ARGB_8888)
            Canvas(small).drawBitmap(art,null,RectF(PX.toFloat(),PY.toFloat(),(PX+W).toFloat(),(PY+H).toFloat()),Paint(Paint.FILTER_BITMAP_FLAG))
            art.recycle()
            ShelfFrost.blur(small,1.4f).also{small.recycle();cache.put(key,it)}
        }catch(e:Exception){null}
    }
}
