package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import org.json.JSONObject
import kotlin.math.*

/** Glass card of the statistics: a faint diagonal sheen over graphite, a hairline rim and a light top edge. */
internal class GlassCardDrawable(private val a:MainActivity,private val radius:Float):Drawable(){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val r=RectF()
    override fun draw(c:Canvas){val b=bounds;if(b.width()<=0)return;r.set(b);val light=a.light
        p.shader=null;p.style=Paint.Style.FILL;p.color=if(light)0xffffffff.toInt() else 0xff242426.toInt();c.drawRoundRect(r,radius,radius,p)
        p.color=Color.WHITE;p.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
            if(light)intArrayOf(0x00ffffff,0x05000000,0x02000000) else intArrayOf(0x0cffffff,0x02ffffff,0x04ffffff),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(r,radius,radius,p);p.shader=null
        val d=a.d(1).toFloat();p.style=Paint.Style.STROKE;p.strokeWidth=d
        p.color=if(light)0x12000000 else 0x14ffffff;c.drawRoundRect(RectF(r.left+d/2,r.top+d/2,r.right-d/2,r.bottom-d/2),radius-d/2,radius-d/2,p)
        if(!light){p.color=Color.WHITE;p.shader=LinearGradient(r.left,0f,r.right,0f,intArrayOf(0x00ffffff,0x1cffffff,0x00ffffff),null,Shader.TileMode.CLAMP)
            c.save();c.clipRect(r.left,r.top,r.right,r.top+radius);c.drawRoundRect(RectF(r.left+d,r.top+d,r.right-d,r.bottom),radius-d,radius-d,p);c.restore();p.shader=null}
        p.style=Paint.Style.FILL}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
    override fun getOutline(o:Outline){o.setRoundRect(bounds,radius)}
}







