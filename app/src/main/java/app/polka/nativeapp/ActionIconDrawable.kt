package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable

/** Reuses the application's 24-unit vector family; no emoji, raster scaling or platform glyphs. */
internal class ActionIconDrawable(private val context:Context,internal val kind:String,private val ink:Int):Drawable(){
 private var opacity=255
 override fun draw(canvas:Canvas){val checkpoint=canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
  if(opacity==255)OriginalIcons.draw(context,canvas,kind,ink)else{val layer=canvas.saveLayerAlpha(0f,0f,24f,24f,opacity);OriginalIcons.draw(context,canvas,kind,ink);canvas.restoreToCount(layer)};canvas.restoreToCount(checkpoint)
 }
 override fun setAlpha(alpha:Int){opacity=alpha;invalidateSelf()}
 override fun setColorFilter(filter:ColorFilter?){}
 @Deprecated("Drawable")override fun getOpacity()=PixelFormat.TRANSLUCENT
}
