package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.pow

/** One way of turning pages on the readers' «Листание» cards: a little page in the reader's own colours, filling
 *  the view, that keeps showing its turn over and over (the card draws the ground and the ring). [kind] is curl,
 *  slide, cover, none or scroll. */
internal class TurnTile(private val a:MainActivity,private val kind:String,private val paper:Int,private val ink:Int):View(a){
    private val reduced get()=a.reducedMotion
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var clock:ValueAnimator?=null;private var t=1.15f
    private val d=a.resources.displayMetrics.density
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(reduced)return
        clock=ValueAnimator.ofFloat(0f,2.6f).apply{duration=2600;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;super.onDetachedFromWindow()}
    private fun ease(x:Float)=if(x<.5f)4*x*x*x else 1-(-2*x+2).pow(3)/2
    /** A page of text at [x0]: nine lines, a paragraph indent; [second] is the page after, laid out a little differently. */
    private fun page(c:Canvas,x0:Float,y0:Float,w:Float,h:Float,second:Boolean){
        p.shader=null;p.style=Paint.Style.FILL;p.color=paper;c.drawRect(x0,y0,x0+w,y0+h,p)
        p.color=ink;p.alpha=150;val pad=w*.14f;val lh=h/11f;val indents=if(second)intArrayOf(0,5) else intArrayOf(3);val shorts=if(second)intArrayOf(4,8) else intArrayOf(2,8)
        for(i in 0 until 9){val ind=if(i in indents)w*.08f else 0f;val full=w-2*pad-ind;val ww=full*(if(i in shorts).5f else (.86f+((i*7+(if(second)12 else 3))%4)*.045f)).coerceAtMost(1f)
            val y=y0+pad*.9f+i*lh;c.drawRoundRect(x0+pad+ind,y,x0+pad+ind+ww,y+maxOf(1.6f*d*.6f,lh*.28f),d,d,p)}
        p.alpha=255}
    override fun onDraw(c:Canvas){c.save();c.clipPath(Path().apply{addRoundRect(RectF(0f,0f,width.toFloat(),height.toFloat()),9*d,9*d,Path.Direction.CW)});turn(c,0f,0f,width.toFloat(),height.toFloat());c.restore()}
    /** The page at [x],[y] turning its way at the moment of the loop. */
    private fun turn(c:Canvas,x:Float,y:Float,w:Float,h:Float){
        val ph=if(reduced)1.15f else t;val q=when{ph<.7f->0f;ph<1.6f->ease((ph-.7f)/.9f);else->1f}
        when(kind){
            "none"->page(c,x,y,w,h,ph>=1.15f)
            "slide"->{page(c,x-w*q,y,w,h,false);page(c,x+w-w*q,y,w,h,true)}
            "cover"->{page(c,x,y,w,h,false);p.color=Color.argb((90*q).toInt(),0,0,0);c.drawRect(x,y,x+w,y+h,p)
                val sx=x+w-w*q;p.shader=LinearGradient(sx,0f,sx-8*d,0f,0x55000000,0,Shader.TileMode.CLAMP);c.drawRect(sx-8*d,y,sx,y+h,p);p.shader=null;page(c,sx,y,w,h,true)}
            "scroll"->{val off=(ph/2.6f)*h;c.save();c.translate(0f,-off);page(c,x,y,w,h,false);page(c,x,y+h,w,h,true);c.restore()}
            else->curl(c,x,y,w,h,q)}}
    /** The sheet folds over along a slanted crease that sweeps from right to left; the folded part is its back. */
    private fun curl(c:Canvas,x:Float,y:Float,w:Float,h:Float,q:Float){
        page(c,x,y,w,h,true);if(q>=1f)return
        val tl=w*.14f;val b=x+(w+2)*(1-q)-tl*q;val at=b+tl*(1-q*.4f)
        val fx=b-at;val fy=h;val len=hypot(fx,fy);val ux=fx/len;val uy=fy/len
        fun refl(px:Float,py:Float):PointF{val dx=px-at;val dy=py-y;val s=dx*ux+dy*uy;val qx=at+ux*s;val qy=y+uy*s;return PointF(2*qx-px,2*qy-py)}
        c.save();c.clipPath(Path().apply{moveTo(x,y);lineTo(at,y);lineTo(b,y+h);lineTo(x,y+h);close()});page(c,x,y,w,h,false);c.restore()
        val r1=refl(x+w,y);val r2=refl(x+w,y+h)
        val flap=Path().apply{moveTo(at,y);lineTo(r1.x,r1.y);lineTo(r2.x,r2.y);lineTo(b,y+h);close()}
        p.style=Paint.Style.FILL;p.shader=null;p.color=0x40000000;p.setShadowLayer(6*d,-2*d,0f,0x33000000)
        val back=if(Color.luminance(paper)>.4f)Color.rgb((Color.red(paper)*.9f).toInt(),(Color.green(paper)*.9f).toInt(),(Color.blue(paper)*.9f).toInt()) else Color.rgb(minOf(255,Color.red(paper)+40),minOf(255,Color.green(paper)+40),minOf(255,Color.blue(paper)+44))
        p.color=back;c.drawPath(flap,p);p.clearShadowLayer()}
}
