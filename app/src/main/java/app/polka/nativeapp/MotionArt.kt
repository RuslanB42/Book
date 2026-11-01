package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import kotlin.math.*

/** Shared motion pieces: the flame that can be grey or lit, the ignition burst, rolling digits, the week capsule, a breathing glow, floating covers and the spine loader. */
internal object FlamePaths {
    val paths:List<Path> by lazy { listOf(
        "M60 145C32 145 12 129 9 108C5 86 15 66 31 51C29 63 31 74 38 81C35 61 48 48 57 34C65 21 62 10 54 3C82 5 101 25 99 49C98 62 92 73 97 81C101 87 108 82 111 72C124 90 119 112 105 128C94 140 78 146 60 145Z",
        "M60 145C43 145 31 133 30 117C29 105 34 94 42 86C43 94 46 99 51 101C44 84 49 66 63 52C67 47 72 44 77 42C71 59 75 72 84 85C94 99 99 111 94 125C89 139 76 146 60 145Z",
        "M60 145C49 145 41 137 42 126C42 116 46 107 51 101C51 110 56 114 61 115C57 106 58 97 63 89C65 101 74 109 78 119C85 134 76 145 60 145Z"
    ).map{androidx.core.graphics.PathParser.createPathFromPathData(it)!!} }
    private fun c(s:String)=Color.parseColor(s)
    val on:List<Shader> by lazy { listOf(
        LinearGradient(0f,3f,0f,145f,intArrayOf(c("#f52d32"),c("#ff4c16"),c("#ff8006"),c("#ffb522")),floatArrayOf(0f,.3f,.65f,1f),Shader.TileMode.CLAMP),
        LinearGradient(0f,42f,0f,145f,intArrayOf(c("#ffe347"),c("#ffe54d"),c("#fff17b"),c("#fff4ab")),floatArrayOf(0f,.48f,.8f,1f),Shader.TileMode.CLAMP),
        LinearGradient(0f,89f,0f,145f,intArrayOf(c("#fff075"),c("#fff7ad"),c("#fffef0")),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)) }
    val off:List<Shader> by lazy { listOf(
        LinearGradient(0f,3f,0f,145f,intArrayOf(c("#a5a5ac"),c("#83838c"),c("#62626c")),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP),
        LinearGradient(0f,42f,0f,145f,intArrayOf(c("#c5c5cb"),c("#b1b1b9"),c("#dedee2")),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP),
        LinearGradient(0f,89f,0f,145f,intArrayOf(c("#e2e2e7"),c("#f5f5f7")),null,Shader.TileMode.CLAMP)) }
}

/** The app's flame drawn bottom-centred in its box; [lit] cross-fades grey → fire. Squash and stretch use the view's own scale with the pivot on the base. */
internal class FlameArt(context:android.content.Context,lit:Float=1f):View(context){
    var lit=lit; set(v){field=v.coerceIn(0f,1f);invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{setLayerType(LAYER_TYPE_HARDWARE,null)}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){pivotX=w/2f;pivotY=h.toFloat()}
    override fun onDraw(c:Canvas){
        val s=min(width/120f,height/150f);c.save();c.translate((width-120*s)/2f,height-150*s);c.scale(s,s)
        if(lit<1f){p.alpha=255;FlamePaths.paths.forEachIndexed{i,path->p.shader=FlamePaths.off[i];c.drawPath(path,p)}}
        if(lit>0f){p.alpha=(255*lit).toInt();FlamePaths.paths.forEachIndexed{i,path->p.shader=FlamePaths.on[i];c.drawPath(path,p)}}
        c.restore()
    }
    private var flicker:ValueAnimator?=null
    /** Barely-there living motion once the flame is lit. */
    fun startFlicker(){flicker?.cancel();flicker=ValueAnimator.ofFloat(0f,(2*PI).toFloat()).apply{duration=1700;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
        addUpdateListener{val t=it.animatedValue as Float;scaleX=1f+.028f*sin(t);scaleY=1f-.024f*sin(t)+.012f*sin(2*t)};start()}}
    fun stopFlicker(){flicker?.cancel();flicker=null}
    override fun onDetachedFromWindow(){stopFlicker();super.onDetachedFromWindow()}
}

/** Ignition burst: a yellow ring that blooms and fades, and sparks that fly out and fall under gravity. */
internal class IgnitionBurst(private val a:MainActivity):View(a){
    private class Spark(val vx:Float,val vy:Float,val r:Float,val color:Int,val spin:Float)
    private val sparks=List(28){val ang=-PI/2+(Math.random()-.5)*PI*1.75;val sp=a.d(330)*(0.55f+Math.random().toFloat()*.65f)
        Spark((cos(ang)*sp).toFloat(),(sin(ang)*sp).toFloat(),a.d(2)*(1f+Math.random().toFloat()*1.4f),if(Math.random()<.5)0xffffd23f.toInt() else 0xffff9a2b.toInt(),(Math.random().toFloat()-.5f)*14f)}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var t=-1f
    var originY=.62f
    fun play(){if(t>=0f&&t<1f)return;ValueAnimator.ofFloat(0f,1f).apply{duration=1100;interpolator=LinearInterpolator();addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){if(t<0f||t>=1f)return;val cx=width/2f;val cy=height*originY;val secs=t*1.1f
        // ring: fast bloom, then thins and fades
        val rt=(t/.5f).coerceAtMost(1f);if(rt<1f){val e=1f-(1f-rt).pow(3);p.style=Paint.Style.STROKE;p.color=0xffffd23f.toInt();p.alpha=(255*(1f-rt).pow(.7f)).toInt();p.strokeWidth=a.d(10)*(1f-e*.75f);c.drawCircle(cx,cy,a.d(30)+a.d(70)*e,p)}
        p.style=Paint.Style.FILL;val g=a.d(900).toFloat()
        for(s in sparks){val x=cx+s.vx*secs*.6f;val y=cy+s.vy*secs*.6f+.5f*g*secs*secs*.6f;val life=(1f-t).coerceIn(0f,1f);p.color=s.color;p.alpha=(255*life).toInt()
            c.save();c.translate(x,y);c.rotate(s.spin*t*60f);c.drawRoundRect(-s.r,-s.r*.6f,s.r,s.r*.6f,s.r*.3f,s.r*.3f,p);c.restore()}
    }
}

/** Soft warm glow that breathes slowly while the flame is alive. */
internal class BreathGlow(context:android.content.Context,private val color:Int=0xffff7a1e.toInt(),private val strength:Float=.32f,private val breathe:Boolean=true):View(context){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var anim:ValueAnimator?=null
    var level=1f; set(v){field=v;invalidate()}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val r=min(w,h)/2f;if(r<=0f)return;p.shader=RadialGradient(w/2f,h/2f,r,intArrayOf((color and 0xffffff) or ((255*strength).toInt() shl 24),(color and 0xffffff) or ((255*strength*.35f).toInt() shl 24),color and 0xffffff),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)}
    override fun onDraw(c:Canvas){p.alpha=(255*level.coerceIn(0f,1f)).toInt();c.drawCircle(width/2f,height/2f,min(width,height)/2f,p)}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(breathe&&(context as? MainActivity)?.prefs?.str("motion")!="reduced")anim=ValueAnimator.ofFloat(0f,1f).apply{duration=3600;repeatCount=ValueAnimator.INFINITE;repeatMode=ValueAnimator.REVERSE;interpolator=AccelerateDecelerateInterpolator();addUpdateListener{val f=it.animatedValue as Float;scaleX=1f+.08f*f;scaleY=scaleX;alpha=1f-.4f*f};start()}}
    override fun onDetachedFromWindow(){anim?.cancel();anim=null;super.onDetachedFromWindow()}
}

/**
 * Rolling digits: every digit sits in its own column and turns like a counter, left columns slightly ahead of the right.
 * [rollAll] false rolls only the leading number of the text ("12 из 24" rolls 12, not 24).
 */
internal class Odometer(private val a:MainActivity,initial:String,size:Float,color:Int,bold:Boolean=true,serif:Boolean=false):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=size*a.resources.displayMetrics.scaledDensity;this.color=color;typeface=if(serif)PolkaFonts.serif(a,bold) else Typeface.create("sans-serif",if(bold)Typeface.BOLD else Typeface.NORMAL);fontFeatureSettings="tnum";letterSpacing=-.02f}
    var text=initial; private set
    private var from=IntArray(0); private var steps=IntArray(0); private var prog=FloatArray(0)
    private val anims=mutableListOf<Animator>()
    var gravityEnd=false
    fun setColor(c:Int){p.color=c;invalidate()}
    private val lineH get()=p.fontMetrics.let{it.descent-it.ascent}
    override fun onMeasure(w:Int,h:Int){val want=ceil(p.measureText(text)).toInt()+a.d(2);setMeasuredDimension(resolveSize(want,w),ceil(lineH).toInt())}
    /** Shows [value]; with motion, digits roll from [old] (or from 0) to it. */
    fun set(value:String,old:String?=null,rollAll:Boolean=true,delay:Long=0,up:Boolean=true){
        anims.forEach{it.cancel()};anims.clear()
        val grew=value.length!=text.length;text=value;contentDescription=value
        val src=(old ?: "").padStart(value.length,' ').takeLast(value.length)
        val lead=value.indexOfFirst{!it.isDigit()}.let{if(it<0)value.length else it}
        from=IntArray(value.length);steps=IntArray(value.length);prog=FloatArray(value.length){1f}
        val reduced=a.prefs.str("motion")=="reduced"
        var col=0
        value.forEachIndexed{i,ch->if(!ch.isDigit()||reduced)return@forEachIndexed;if(!rollAll&&i>=lead)return@forEachIndexed
            val d=ch-'0';val s=src[i].takeIf{it.isDigit()}?.minus('0') ?: 0
            val n=if(up)(d-s+10)%10 else -((s-d+10)%10);if(n==0)return@forEachIndexed
            from[i]=s;steps[i]=n;prog[i]=0f
            val k=col++;anims+=ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay+k*70L;duration=480L+abs(n)*55L;interpolator=PathInterpolator(.2f,1.18f,.36f,1f);addUpdateListener{prog[i]=it.animatedValue as Float;invalidate()};start()}}
        if(grew)requestLayout();invalidate()
    }
    override fun onDetachedFromWindow(){anims.forEach{it.end()};anims.clear();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val fm=p.fontMetrics;val lh=lineH;val base=-fm.ascent;var x=if(gravityEnd)width-p.measureText(text) else 0f
        c.save();c.clipRect(0f,0f,width.toFloat(),height.toFloat())
        text.forEachIndexed{i,ch->val w=p.measureText(ch.toString())
            if(i<steps.size&&steps[i]!=0&&prog[i]<1f){val pos=from[i]+steps[i]*prog[i];val lo=floor(pos);val frac=pos-lo;val d0=Math.floorMod(lo.toInt(),10);val d1=Math.floorMod(lo.toInt()+1,10)
                c.drawText(d0.toString(),x,base-frac*lh,p);c.drawText(d1.toString(),x,base+(1f-frac)*lh,p)}
            else c.drawText(ch.toString(),x,base,p)
            x+=w}
        c.restore()
    }
}

/**
 * The week as seven cells; consecutive met days join into one warm capsule with checks. Today is ringed until met.
 * [grow] animates the capsules in from the left ([growToday] only extends the run onto today, as in the celebration).
 */
internal class WeekCapsule(private val a:MainActivity,private val met:BooleanArray,private val today:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val check=Path()
    private var fill=1f; private var todayFill=1f
    private val ticks=FloatArray(7){1f}
    private val anims=mutableListOf<Animator>()
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),a.d(32))
    private fun cell(i:Int):RectF{val gap=a.d(5).toFloat();val cw=(width-gap*6)/7f;val x=i*(cw+gap);return RectF(x,0f,x+cw,height.toFloat())}
    /** Full entrance: runs grow from the left, checks pop one after another. */
    fun grow(delay:Long=0){if(a.prefs.str("motion")=="reduced")return;stop();fill=0f;for(i in 0..6)ticks[i]=0f
        anims+=ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay;duration=760;interpolator=PathInterpolator(.22f,1f,.36f,1f);addUpdateListener{fill=it.animatedValue as Float;invalidate()};start()}
        var k=0;for(i in 0..6)if(met[i]){val idx=i;anims+=ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay+180L+k*80L;duration=420;interpolator=OvershootInterpolator(2.4f);addUpdateListener{ticks[idx]=it.animatedValue as Float;invalidate()};start()};k++}}
    /** Today joins the run: the capsule stretches onto today, then its check pops. */
    fun prepareToday(){if(!met[today])return;todayFill=0f;ticks[today]=0f;invalidate()}
    fun growToday(delay:Long=0,done:()->Unit={}){if(!met[today]){done();return}
        anims+=ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay;duration=620;interpolator=PathInterpolator(.3f,1.25f,.4f,1f);addUpdateListener{todayFill=it.animatedValue as Float;invalidate()};start()}
        anims+=ValueAnimator.ofFloat(0f,1f).apply{startDelay=delay+360;duration=440;interpolator=OvershootInterpolator(3f);addUpdateListener{ticks[today]=it.animatedValue as Float;invalidate()};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){done()}});start()}}
    private fun stop(){anims.forEach{it.cancel()};anims.clear()}
    override fun onDetachedFromWindow(){anims.forEach{it.end()};anims.clear();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val quiet=if(a.light)0xffeeeeec.toInt() else 0xff2f2f31.toInt();val r=height/2f
        p.style=Paint.Style.FILL;p.shader=null
        for(i in 0..6){p.color=quiet;val rc=cell(i);if(i==today&&!met[i]){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=a.accentFill;rc.inset(a.d(1).toFloat(),a.d(1).toFloat());c.drawRoundRect(rc,r,r,p);p.style=Paint.Style.FILL}else c.drawRoundRect(rc,r,r,p)}
        // runs of met days
        var i=0;val total=width*fill
        while(i<7){if(!met[i]){i++;continue};var j=i;while(j+1<7&&met[j+1])j++
            val left=cell(i).left;var right=cell(j).right
            if(j==today&&todayFill<1f){val prev=if(j>i)cell(j-1).right else cell(j).left+height.toFloat();right=prev+(right-prev)*todayFill.coerceAtLeast(0f)}
            right=min(right,total);if(right>left){val rc=RectF(left,0f,max(right,left+height.toFloat()),height.toFloat())
                p.shader=LinearGradient(rc.left,0f,rc.right,0f,0xffff5a2c.toInt(),0xffff9b2b.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(rc,r,r,p);p.shader=null
                p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=0xffffd23f.toInt();rc.inset(a.d(1).toFloat(),a.d(1).toFloat());c.drawRoundRect(rc,r-a.d(1),r-a.d(1),p);p.style=Paint.Style.FILL}
            i=j+1}
        // checks
        p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2.4f);p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=Color.WHITE
        for(k in 0..6)if(met[k]&&ticks[k]>0f){val rc=cell(k);val s=ticks[k];val cx=rc.centerX();val cy=rc.centerY();val u=a.d(6)*s
            check.reset();check.moveTo(cx-u*.95f,cy+u*.05f);check.lineTo(cx-u*.25f,cy+u*.72f);check.lineTo(cx+u,cy-u*.6f);c.drawPath(check,p)}
        p.style=Paint.Style.FILL
    }
}
internal fun MainActivity.d(value:Float):Float=value*resources.displayMetrics.density

/** Covers drifting in three depth layers instead of an empty shelf. [scatter] pushes them off to the sides for the import loader. */
internal class FloatingCovers(private val a:MainActivity):View(a){
    private class Card(val fx:Float,val fy:Float,val depth:Int,val rot:Float,val phase:Float,val c1:Int,val c2:Int)
    private val cards=listOf(Card(.14f,.30f,1,-5f,0f,0xff2b2440.toInt(),0xff0f0b19.toInt()),Card(.80f,.22f,1,6f,1.7f,0xff3b5b4e.toInt(),0xff132119.toInt()),
        Card(.50f,.12f,2,-3f,3.1f,0xffc7742c.toInt(),0xff4a2108.toInt()),Card(.86f,.66f,2,5f,4.2f,0xff7b1f2b.toInt(),0xff2a0a10.toInt()),
        Card(.24f,.70f,3,4f,2.4f,0xff2c5d78.toInt(),0xff0d1f2b.toInt()),Card(.56f,.58f,3,-6f,5.3f,0xffd7cdb8.toInt(),0xff7b6c54.toInt()))
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    var scatter=0f; set(v){field=v;invalidate()}
    private var start=0L
    private val reduced get()=a.prefs.str("motion")=="reduced"
    override fun onAttachedToWindow(){super.onAttachedToWindow();start=System.nanoTime()}
    override fun onDraw(c:Canvas){
        val t=(System.nanoTime()-start)/1e9f;val base=min(width*.22f,a.d(84).toFloat())
        for(k in cards.sortedBy{it.depth}){val s=when(k.depth){1->.62f;2->.8f;else->1f};val w=base*s;val h=w*1.5f;val sp=.45f+k.depth*.17f
            val dx=if(reduced)0f else sin(t*sp*.7f+k.phase)*a.d(5)*k.depth*.6f;val dy=if(reduced)0f else cos(t*sp+k.phase)*a.d(6)*k.depth*.5f
            val side=if(k.fx<.5f)-1f else 1f;val sx=scatter*side*width*.45f;val sy=-scatter*a.d(36)
            val cx=k.fx*width+dx+sx;val cy=k.fy*height+dy+sy+h/2f
            c.save();c.translate(cx,cy);c.rotate(k.rot+if(reduced)0f else sin(t*sp*.8f+k.phase)*2.2f)
            val alpha=((1f-scatter)*when(k.depth){1->.55f;2->.8f;else->1f}*255).toInt().coerceIn(0,255);if(alpha==0){c.restore();continue}
            val rc=RectF(-w/2,-h/2,w/2,h/2)
            p.shader=null;p.color=0x55000000;p.alpha=alpha*85/255;c.drawRoundRect(RectF(rc.left+a.d(2),rc.top+a.d(6),rc.right+a.d(2),rc.bottom+a.d(8)),a.d(6).toFloat(),a.d(6).toFloat(),p)
            p.shader=LinearGradient(rc.left,rc.top,rc.right,rc.bottom,k.c1,k.c2,Shader.TileMode.CLAMP);p.alpha=alpha;c.drawRoundRect(rc,a.d(3).toFloat(),a.d(3).toFloat(),p)
            p.shader=LinearGradient(rc.left,0f,rc.left+w*.12f,0f,0x55000000,0x00000000,Shader.TileMode.CLAMP);p.alpha=alpha;c.drawRect(rc.left,rc.top,rc.left+w*.12f,rc.bottom,p)
            p.shader=null;p.color=if(k.c1==0xffd7cdb8.toInt())0x553a2e1e else 0x66ffffff;p.alpha=alpha*(if(k.c1==0xffd7cdb8.toInt())0x55 else 0x66)/255
            c.drawRoundRect(rc.left+w*.18f,rc.top+h*.14f,rc.right-w*.16f,rc.top+h*.14f+a.d(3),a.d(2).toFloat(),a.d(2).toFloat(),p)
            c.drawRoundRect(rc.left+w*.18f,rc.top+h*.14f+a.d(7),rc.right-w*.36f,rc.top+h*.14f+a.d(10),a.d(2).toFloat(),a.d(2).toFloat(),p)
            c.restore()}
        if(!reduced&&isShown)postInvalidateOnAnimation()
    }
}

/** Five spines rising one after another on a little shelf; [merge] gathers them into one book before the loader leaves. */
internal class SpineLoader(private val a:MainActivity):View(a){
    private val colors=intArrayOf(0xffc7742c.toInt(),0xff6a5aa8.toInt(),0xff3b8a6e.toInt(),0xffb0303f.toInt(),0xff2f7aa8.toInt())
    private val heights=floatArrayOf(.72f,.9f,.8f,1f,.76f)
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private var loop:ValueAnimator?=null; private var t=0f; private var merge=0f
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(132),a.d(78))
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(a.prefs.str("motion")!="reduced")loop=ValueAnimator.ofFloat(0f,1f).apply{duration=1500;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{t=it.animatedValue as Float;invalidate()};start()} else t=.3f}
    override fun onDetachedFromWindow(){loop?.cancel();super.onDetachedFromWindow()}
    fun merge(done:()->Unit){val start=t;loop?.cancel();loop=null;if(a.prefs.str("motion")=="reduced"){done();return}
        ValueAnimator.ofFloat(0f,1f).apply{duration=380;interpolator=PathInterpolator(.4f,0f,.2f,1f);addUpdateListener{merge=it.animatedValue as Float;t=start;invalidate()};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){done()}});start()}}
    private fun rise(i:Int):Float{if(merge>0f)return 1f;var u=(t-i*.075f+1f)%1f;return when{u<.32f->{val q=u/.32f;1f-(1f-q).pow(3)};u<.7f->1f;u<.92f->{val q=(u-.7f)/.22f;1f-q*q};else->0f}}
    override fun onDraw(c:Canvas){
        val sw=a.d(16).toFloat();val gap=a.d(5).toFloat();val total=5*sw+4*gap;val x0=(width-total)/2f;val floor=height-a.d(8).toFloat();val maxH=height-a.d(16).toFloat()
        p.shader=null;p.color=if(a.light)0xffd9d9dc.toInt() else 0xff38383c.toInt();c.drawRoundRect(x0-a.d(10),floor+a.d(2),x0+total+a.d(10),floor+a.d(7),a.d(3).toFloat(),a.d(3).toFloat(),p)
        for(i in 0..4){val r=rise(i);val h=maxH*heights[i]*(.12f+.88f*r);var x=x0+i*(sw+gap);x+=((width/2f-sw/2f)-x)*merge
            p.color=colors[i];p.alpha=(255*(.35f+.65f*r)).toInt();c.drawRoundRect(x,floor-h,x+sw,floor,a.d(3).toFloat(),a.d(3).toFloat(),p)
            p.color=Color.WHITE;p.alpha=(90*r).toInt();c.drawRoundRect(x+a.d(3),floor-h+h*.14f,x+sw-a.d(3),floor-h+h*.14f+a.d(2),a.d(1).toFloat(),a.d(1).toFloat(),p)}
    }
}
