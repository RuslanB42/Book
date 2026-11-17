package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.hypot
import kotlin.math.pow

/** One way of turning pages in «Ещё» (approved variant А): a little page in the reader's own colours that keeps
 *  showing its turn over and over, with the ring of the chosen tile round it. [kind] is curl, slide, cover, none or scroll. */
internal class TurnTile(private val a:MainActivity,private val kind:String,private val paper:Int,private val ink:Int):View(a){
    private var k=0f;private var ring:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||!isAttachedToWindow||reduced){first=false;k=target;invalidate();return}
        ring?.cancel();ring=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var clock:ValueAnimator?=null;private var t=1.15f
    private val d=a.resources.displayMetrics.density
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);setMeasuredDimension(w,(w*1.3f).toInt())}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(reduced)return
        clock=ValueAnimator.ofFloat(0f,2.6f).apply{duration=2600;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;ring?.cancel();super.onDetachedFromWindow()}
    private fun ease(x:Float)=if(x<.5f)4*x*x*x else 1-(-2*x+2).pow(3)/2
    /** A page of text at [x0]: nine lines, a paragraph indent; [second] is the page after, laid out a little differently. */
    private fun page(c:Canvas,x0:Float,y0:Float,w:Float,h:Float,second:Boolean){
        p.shader=null;p.style=Paint.Style.FILL;p.color=paper;c.drawRect(x0,y0,x0+w,y0+h,p)
        p.color=ink;p.alpha=150;val pad=w*.14f;val lh=h/11f;val indents=if(second)intArrayOf(0,5) else intArrayOf(3);val shorts=if(second)intArrayOf(4,8) else intArrayOf(2,8)
        for(i in 0 until 9){val ind=if(i in indents)w*.08f else 0f;val full=w-2*pad-ind;val ww=full*(if(i in shorts).5f else (.86f+((i*7+(if(second)12 else 3))%4)*.045f)).coerceAtMost(1f)
            val y=y0+pad*.9f+i*lh;c.drawRoundRect(x0+pad+ind,y,x0+pad+ind+ww,y+maxOf(1.6f*d*.6f,lh*.28f),d,d,p)}
        p.alpha=255}
    override fun onDraw(c:Canvas){
        val inset=4*d;val box=RectF(inset,inset,width-inset,height-inset);val rad=12*d
        if(k>0f){p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;p.alpha=(255*k).toInt();val o=d+(1f-k)*2f*d;c.drawRoundRect(RectF(o,o,width-o,height-o),rad+inset-o,rad+inset-o,p);p.alpha=255}
        p.shader=null;p.style=Paint.Style.FILL;p.color=if(a.light)0x0f000000 else 0x11ffffff;c.drawRoundRect(box,rad,rad,p)
        val m=6*d;val x=box.left+m;val y=box.top+m;val w=box.width()-2*m;val h=box.height()-2*m
        c.save();c.clipPath(Path().apply{addRoundRect(RectF(x,y,x+w,y+h),5*d,5*d,Path.Direction.CW)})
        val ph=if(reduced)1.15f else t;val q=when{ph<.7f->0f;ph<1.6f->ease((ph-.7f)/.9f);else->1f}
        when(kind){
            "none"->page(c,x,y,w,h,ph>=1.15f)
            "slide"->{page(c,x-w*q,y,w,h,false);page(c,x+w-w*q,y,w,h,true)}
            "cover"->{page(c,x,y,w,h,false);p.color=Color.argb((90*q).toInt(),0,0,0);c.drawRect(x,y,x+w,y+h,p)
                val sx=x+w-w*q;p.shader=LinearGradient(sx,0f,sx-8*d,0f,0x55000000,0,Shader.TileMode.CLAMP);c.drawRect(sx-8*d,y,sx,y+h,p);p.shader=null;page(c,sx,y,w,h,true)}
            "scroll"->{val off=(ph/2.6f)*h;c.save();c.translate(0f,-off);page(c,x,y,w,h,false);page(c,x,y+h,w,h,true);c.restore()}
            else->curl(c,x,y,w,h,q)}
        c.restore()}
    /** The sheet folds over along a slanted crease that sweeps from right to left; the folded part is its back. */
    private fun curl(c:Canvas,x:Float,y:Float,w:Float,h:Float,q:Float){
        page(c,x,y,w,h,true);if(q>=1f)return
        val tl=w*.14f;val b=x+(w+2)*(1-q)-tl*q;val at=b+tl*(1-q*.4f)
        val fx=b-at;val fy=h;val len=hypot(fx,fy);val ux=fx/len;val uy=fy/len
        fun refl(px:Float,py:Float):PointF{val dx=px-at;val dy=py-y;val s=dx*ux+dy*uy;val qx=at+ux*s;val qy=y+uy*s;return PointF(2*qx-px,2*qy-py)}
        c.save();c.clipPath(Path().apply{moveTo(x,y);lineTo(at,y);lineTo(b,y+h);lineTo(x,y+h);close()});page(c,x,y,w,h,false);c.restore()
        val r1=refl(x+w,y);val r2=refl(x+w,y+h)
        val flap=Path().apply{moveTo(at,y);lineTo(r1.x,r1.y);lineTo(r2.x,r2.y);lineTo(b,y+h);close()}
        p.style=Paint.Style.FILL;p.shader=null;p.color=0x40000000;p.setShadowLayer(4*d,-2*d,0f,0x55000000)
        val back=if(Color.luminance(paper)>.4f)Color.rgb((Color.red(paper)*.9f).toInt(),(Color.green(paper)*.9f).toInt(),(Color.blue(paper)*.9f).toInt()) else Color.rgb(minOf(255,Color.red(paper)+40),minOf(255,Color.green(paper)+40),minOf(255,Color.blue(paper)+44))
        p.color=back;c.drawPath(flap,p);p.clearShadowLayer()}
}
