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
        p.shader=null;p.style=Paint.Style.FILL;p.color=if(light)0xffffffff.toInt() else 0xff1e1e20.toInt();c.drawRoundRect(r,radius,radius,p)
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

/** The streak's flame as a soft glowing object; it breathes a little while the streak is alive. */
internal class FlameOrb(private val a:MainActivity,private val lit:Boolean,private val motion:Boolean):View(a){
    private val outer=Path().apply{moveTo(58f,8f);cubicTo(63f,32f,90f,44f,90f,74f);cubicTo(90f,98f,72f,112f,54f,112f);cubicTo(36f,112f,20f,98f,20f,76f);cubicTo(20f,59f,30f,50f,35f,40f);cubicTo(38f,51f,43f,55f,48f,56f);cubicTo(46f,35f,50f,22f,58f,8f);close()}
    private val inner=Path().apply{moveTo(55f,66f);cubicTo(57f,76f,68f,79f,68f,92f);cubicTo(68f,102f,61f,108f,54f,108f);cubicTo(47f,108f,41f,102f,41f,93f);cubicTo(41f,85f,45f,81f,48f,77f);cubicTo(49f,82f,51f,84f,53f,84f);cubicTo(52f,77f,53f,71f,55f,66f);close()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val mask=Paint(Paint.ANTI_ALIAS_FLAG).apply{xfermode=PorterDuffXfermode(PorterDuff.Mode.DST_IN)}
    private var t=0f;private var clock:ValueAnimator?=null
    /** 0 — the flame is cut by the card's edge; 1 — its foot melts away (the month is open below). */
    var fade=0f;set(v){field=v;invalidate()}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    companion object{
        /** Scale of the 100×116 flame box in a view of [w]×[h] px: it fits the height and never the width beyond 96. */
        fun scale(w:Float,h:Float)=min(h/104f,w/96f)
        /** Distance (px) from the view's right edge to the flame's left edge. */
        fun reach(w:Float,h:Float)=72f*scale(w,h)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(lit&&motion&&clock==null)clock=ValueAnimator.ofFloat(0f,(2*PI).toFloat()).apply{duration=2600;repeatCount=ValueAnimator.INFINITE;interpolator=null;addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){if(width<1||height<1)return
        val layer=if(fade>0f)c.saveLayer(0f,0f,width.toFloat(),height.toFloat(),null) else -1
        val s=scale(width.toFloat(),height.toFloat());c.save();c.translate(width-92f*s,height-106f*s);c.scale(s,s)
        val breathe=1f+.012f*sin(t);val sway=.6f*sin(t*.5f)
        c.save();c.scale(1f,breathe,55f,112f);c.skew(sway/100f,0f)
        p.shader=RadialGradient(56f,88f,52f,intArrayOf(if(lit)0x99ff5a1e.toInt() else 0x30808080,0x00ff5a1e),null,Shader.TileMode.CLAMP);c.drawCircle(56f,88f,52f,p)
        p.shader=if(lit)RadialGradient(52f,92f,80f,intArrayOf(0xfffff6d0.toInt(),0xffffc04a.toInt(),0xffff6a2a.toInt(),0xffe0381e.toInt(),0xffb8241a.toInt()),floatArrayOf(0f,.26f,.58f,.86f,1f),Shader.TileMode.CLAMP)
            else RadialGradient(52f,92f,80f,intArrayOf(0xff8e8e93.toInt(),0xff5a5a5e.toInt(),0xff3a3a3d.toInt()),null,Shader.TileMode.CLAMP)
        c.drawPath(outer,p)
        p.shader=RadialGradient(46f,40f,34f,intArrayOf(if(lit)0x66ffffff else 0x26ffffff,0x00ffffff),null,Shader.TileMode.CLAMP);c.drawPath(outer,p)
        p.shader=RadialGradient(54f,100f,22f,intArrayOf(if(lit)0xfffffbe8.toInt() else 0x80d0d0d4.toInt(),if(lit)0xf2ffe2a0.toInt() else 0x40d0d0d4),null,Shader.TileMode.CLAMP);c.drawPath(inner,p);p.shader=null
        c.restore();c.restore()
        if(layer>=0){mask.shader=LinearGradient(0f,height*.55f,0f,height.toFloat(),Color.BLACK,(((1-fade)*255).toInt() shl 24),Shader.TileMode.CLAMP);c.drawRect(0f,0f,width.toFloat(),height.toFloat(),mask);c.restoreToCount(layer)}}
}

/** Reading time as day (or month) bars: the chosen bar glows and carries a label; press and slide to read any bar. */
internal class DayBars(private val a:MainActivity,private val values:List<Double>,private val labels:List<String>,private val tips:List<String>,
                       initial:Int,private val average:Double,private val averageText:String,private val accent:Int,private val motion:Boolean,private val sparseLabels:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=a.d(11).toFloat();typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
    private var selected=initial.coerceIn(0,max(0,values.lastIndex));private var grow=if(motion)0f else 1f
    init{contentDescription=tips.getOrNull(selected).orEmpty();isClickable=true}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(grow<1f)ValueAnimator.ofFloat(0f,1f).apply{duration=520;startDelay=140;interpolator=Ease.outExpo;addUpdateListener{grow=it.animatedValue as Float;invalidate()};start()}}
    private val top get()=a.d(30).toFloat();private val bottom get()=height-a.d(22).toFloat()
    private val restColors get()=if(a.light)intArrayOf(0xffc4b6f7.toInt(),0xffe6e0fb.toInt()) else intArrayOf(0xff6a5aa6.toInt(),0xff2f2949.toInt())
    private val selectedColors get()=if(a.light)intArrayOf(0xff8d6cf5.toInt(),0xff6a47e8.toInt()) else intArrayOf(0xffe0d6ff.toInt(),0xff7c5cff.toInt())
    private val glowTint=0xff8c6cff.toInt()
    private fun slot()=width/max(1,values.size).toFloat()
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE->{parent?.requestDisallowInterceptTouchEvent(true)
        val i=(e.x/slot()).toInt().coerceIn(0,values.lastIndex);if(i!=selected){selected=i;contentDescription=tips[i];performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);invalidate()}}};return true}
    override fun onDraw(c:Canvas){if(values.isEmpty()||width<1)return;val light=a.light
        val max=max(values.maxOrNull()?:0.0,max(average,1.0));val h=bottom-top;val sl=slot();val bw=min(a.d(22).toFloat(),sl*.62f)
        // average line
        val ay=bottom-(average/max*h).toFloat()*grow
        p.style=Paint.Style.STROKE;p.strokeWidth=a.d(1).toFloat()*1.4f;p.color=if(light)0x2e000000 else 0x2effffff;p.pathEffect=DashPathEffect(floatArrayOf(a.d(4).toFloat(),a.d(4).toFloat()),0f)
        c.drawLine(0f,ay,width.toFloat(),ay,p);p.pathEffect=null;p.style=Paint.Style.FILL
        tp.color=a.muted;tp.textAlign=Paint.Align.LEFT;c.drawText(averageText,0f,ay-a.d(5),tp)
        for(i in values.indices){p.color=Color.WHITE;val cx=sl*i+sl/2;val v=(values[i]/max*h).toFloat()*grow;val bh=max(v,bw*.0f+a.d(3).toFloat()*grow)
            val rect=RectF(cx-bw/2,bottom-bh,cx+bw/2,bottom);if(rect.height()<a.d(6)*grow)rect.top=bottom-a.d(6)*grow
            val on=i==selected
            if(on&&!light){val rx=min(sl*.75f,bw*2.2f);val ry=max(rect.height()/2+bw*.9f,rx);c.save();c.translate(cx,rect.centerY());c.scale(1f,ry/rx)
                p.shader=RadialGradient(0f,0f,rx,intArrayOf((glowTint and 0xffffff) or 0x47000000,(glowTint and 0xffffff) or 0x14000000,glowTint and 0xffffff),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);c.drawCircle(0f,0f,rx,p);p.shader=null;c.restore()}
            p.shader=LinearGradient(0f,rect.top,0f,rect.bottom,if(on)selectedColors else restColors,null,Shader.TileMode.CLAMP)
            c.drawRoundRect(rect,bw/2,bw/2,p);p.shader=null
            if(!sparseLabels||i==0||i==values.lastIndex||on||(i+1)%5==0){tp.textAlign=Paint.Align.CENTER;tp.color=if(on)a.fg else a.muted;tp.isFakeBoldText=on;c.drawText(labels[i],cx,height-a.d(4).toFloat(),tp);tp.isFakeBoldText=false}
            if(on){val text=tips[i];tp.textSize=a.d(11.5f).toFloat();val tw=tp.measureText(text)+a.d(16);val th=a.d(22).toFloat()
                val l=(cx-tw/2).coerceIn(0f,width-tw);val r=RectF(l,0f,l+tw,th)
                p.color=if(light)0xff161617.toInt() else 0xfff2f2f2.toInt();c.drawRoundRect(r,th/2,th/2,p)
                p.color=(p.color and 0xffffff) or 0x80000000.toInt();c.drawRect(cx-a.d(1)*.75f,th,cx+a.d(1)*.75f,min(rect.top-a.d(4),th+a.d(10)),p)
                tp.color=if(light)Color.WHITE else 0xff111111.toInt();tp.textAlign=Paint.Align.CENTER;tp.isFakeBoldText=true;c.drawText(text,r.centerX(),r.centerY()-(tp.ascent()+tp.descent())/2,tp);tp.isFakeBoldText=false;tp.textSize=a.d(11).toFloat()}
        }}
    private fun lighten(c:Int,f:Float)=Color.rgb((Color.red(c)+(255-Color.red(c))*f).toInt(),(Color.green(c)+(255-Color.green(c))*f).toInt(),(Color.blue(c)+(255-Color.blue(c))*f).toInt())
}

/** The annual goal as a small stack of books: read ones in their cover colours (with a page block), the rest dashed. */
internal class GoalStack(private val a:MainActivity,private val filled:Int,private val slots:Int,private val colors:List<Int>,private val motion:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var drop=if(motion)0f else 1f
    private val settle=Ease.outCubic
    private val offsets=intArrayOf(6,0,10,2,8,4,0,6);private val widths=floatArrayOf(.94f,1f,.88f,.96f,.9f,.92f,.86f,.9f)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(drop<1f)ValueAnimator.ofFloat(0f,1f).apply{duration=260L+filled*90L;startDelay=200;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{drop=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat();val bh=a.d(17).toFloat();val gap=d;val full=width.toFloat()*.86f
        for(i in 0 until slots){val y=height-(i+1)*(bh+gap)-0f;val w=full*widths[i%widths.size];val x=a.d(offsets[i%offsets.size]).toFloat()
            val r=RectF(x,y,x+w,y+bh)
            if(i<filled){val k=((drop*filled)-i).coerceIn(0f,1f);if(k<=0f)continue
                val y0=1f-settle.getInterpolation(k);c.save();c.translate(0f,-y0*a.d(24));val col=colors[i%colors.size]
                p.alpha=(k*255).toInt();p.setShadowLayer(4*d,0f,2*d,0x66000000);p.color=col;c.drawRoundRect(r,4*d,4*d,p);p.clearShadowLayer()
                p.shader=LinearGradient(0f,r.top,0f,r.bottom,0x2effffff,0x26000000,Shader.TileMode.CLAMP);c.drawRoundRect(r,4*d,4*d,p);p.shader=null
                val pg=RectF(r.right-4*d-9*d,r.top+3*d,r.right-4*d,r.bottom-3*d);p.shader=LinearGradient(pg.left,0f,pg.right,0f,0xffefe6cf.toInt(),0xffcbbf9f.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(pg,2*d,2*d,p);p.shader=null
                p.alpha=255;c.restore()}
            else{p.style=Paint.Style.STROKE;p.strokeWidth=1.2f*d;p.color=if(a.light)0x40000000 else 0x3dffffff;p.pathEffect=DashPathEffect(floatArrayOf(4*d,3*d),0f)
                c.drawRoundRect(RectF(r.left+d,r.top+d,r.right-d,r.bottom-d),4*d,4*d,p);p.pathEffect=null;p.style=Paint.Style.FILL}}}
}

/** Two faint orbits behind the floating covers of the year's books. */
internal class Orbits(private val a:MainActivity):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){p.strokeWidth=a.d(1).toFloat();p.color=if(a.light)0x12000000 else 0x10ffffff;val cx=width*.5f;val cy=height*.5f
        c.drawCircle(cx,cy,min(width,height)*.62f,p);c.drawCircle(cx,cy,min(width,height)*.36f,p)}
}

/** A speedometer glyph for the speed card. */
internal class GaugeGlyph(private val a:MainActivity,private val tint:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val s=min(width,height)/24f;c.save();c.translate((width-24*s)/2,(height-24*s)/2);c.scale(s,s)
        p.color=tint;p.strokeWidth=1.9f;c.drawArc(4f,4f,20f,20f,150f,240f,false,p)
        p.strokeWidth=1.6f;c.drawLine(7.2f,9.4f,8.1f,10.1f,p);c.drawLine(12f,6.6f,12f,7.8f,p);c.drawLine(16.8f,9.4f,15.9f,10.1f,p)
        p.color=a.fg;p.strokeWidth=2.1f;c.drawLine(12f,15f,15.6f,10.7f,p);p.style=Paint.Style.FILL;c.drawCircle(12f,15f,1.7f,p);p.style=Paint.Style.STROKE;c.restore()}
}

/** The speed scale: a cool gradient bar with a glowing marker where you are; a faint tick marks the average. */
internal class SpeedScale(private val a:MainActivity,private val fraction:Float?,private val average:Float,private val tint:Int,private val motion:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var k=if(motion)0f else 1f
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(k<1f)ValueAnimator.ofFloat(0f,1f).apply{duration=800;startDelay=250;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat();val cy=height/2f;val h=6*d
        p.shader=LinearGradient(0f,0f,width.toFloat(),0f,intArrayOf((tint and 0xffffff) or 0x26000000,(tint and 0xffffff) or 0x99000000.toInt(),tint),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(0f,cy-h/2,width.toFloat(),cy+h/2,h/2,h/2,p);p.shader=null
        p.color=if(a.light)0x40000000 else 0x55ffffff;val ax=width*average;c.drawRect(ax-d*.5f,cy-h,ax+d*.5f,cy+h,p)
        val f=fraction?:return;val x=(width*f*k).coerceIn(2*d,width-2*d)
        p.color=tint and 0xffffff or 0x66000000;p.maskFilter=null;c.drawRoundRect(x-4*d,cy-12*d,x+4*d,cy+12*d,4*d,4*d,p)
        p.color=if(a.light)0xff161617.toInt() else Color.WHITE;c.drawRoundRect(x-1.5f*d,cy-10*d,x+1.5f*d,cy+10*d,1.5f*d,1.5f*d,p)}
}

/** Cover colour for the goal stack: the dominant pigment of the cover, or a stable palette colour. */
internal fun MainActivity.spineColor(book:JSONObject,i:Int):Int{
    val palette=intArrayOf(0xffc0563c.toInt(),0xff2f6fb0.toInt(),0xffe2b33c.toInt(),0xff3a7d5c.toInt(),0xff8a4fb0.toInt(),0xffc98a3a.toInt())
    val c=BookCoverVolume.binding(cover(book),palette[i%palette.size]);val hsv=FloatArray(3);Color.colorToHSV(c,hsv)
    if(hsv[1]<.18f)return palette[i%palette.size] // grey / black / white covers read badly as a spine
    hsv[1]=hsv[1].coerceIn(.35f,.85f);hsv[2]=hsv[2].coerceIn(.55f,.9f);return Color.HSVToColor(hsv)
}
