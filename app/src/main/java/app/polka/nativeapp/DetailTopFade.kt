package app.polka.nativeapp

import android.graphics.*
import android.view.View

/** Smooth optical fade at the actual sheet edge, independent of toolbar height. */
internal class DetailTopFade(private val a:MainActivity):View(a){
 companion object {
  const val DEPTH_DP=96
  fun opacity(position:Float):Float{val t=position.coerceIn(0f,1f);return 1-t*t*(3-2*t)}
  fun shader(depth:Float,color:Int):Shader=LinearGradient(0f,0f,0f,depth,IntArray(33){i->Color.argb((255*opacity(i/32f)).toInt(),Color.red(color),Color.green(color),Color.blue(color))},FloatArray(33){it/32f},Shader.TileMode.CLAMP)
 }
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
 init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;contentDescription=null}
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){paint.shader=shader(h.toFloat(),if(a.light)0xfff7f7f5.toInt()else 0xff1c1c1e.toInt())}
 override fun onDraw(c:Canvas){c.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)}
}

/** A scrolling sheet's content with the book card's top fade: the sheet colour softly covers what scrolls up under the
 *  top edge (the same smooth curve as [DetailTopFade]); nothing at the bottom. The fade grows in with the first
 *  [rampDp] of scrolling, so content at rest is never dimmed. */
internal class SheetScroll(private val a:MainActivity,private val color:Int,depthDp:Int=44,private val rampDp:Int=24):android.widget.ScrollView(a){
 var fadeEnabled=true;set(v){field=v;invalidate()}
 private val depth=a.d(depthDp).toFloat()
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=DetailTopFade.shader(depth,color)}
 private val m=Matrix()
 init{isVerticalFadingEdgeEnabled=false;isVerticalScrollBarEnabled=false}
 override fun draw(c:Canvas){super.draw(c);if(!fadeEnabled||scrollY<=0)return
  val k=(scrollY/a.d(rampDp).toFloat()).coerceIn(0f,1f);paint.alpha=(255*k).toInt();m.setTranslate(0f,scrollY.toFloat());paint.shader.setLocalMatrix(m)
  c.drawRect(scrollX.toFloat(),scrollY.toFloat(),(scrollX+width).toFloat(),scrollY+depth,paint)}
}
