package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.*

/** Statistics widgets: flat grey cards like the rest of the app; colour only in the data (approved «Цвет только в данных»). */
internal class WidgetCardDrawable(private val a:MainActivity,private val radius:Float,private val glow:Int?,private val fx:Float,private val fy:Float,private val reach:Float):Drawable(){
    private val base=Paint(Paint.ANTI_ALIAS_FLAG);private val light=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private val r=RectF()
    private var tex:Bitmap?=null;private var texKey="";private var fade=1f
    override fun draw(c:Canvas){draw0(c)}
    private fun draw0(c:Canvas){val b=bounds;if(b.width()<=0||b.height()<=0)return;r.set(b)
        base.color=Ui.control(a.light);c.drawRoundRect(r,radius,radius,base)
        val g=glow?:return
        // one soft light, pre-rendered with a gaussian falloff and fine noise so the 8-bit steps never show as rings
        // rendered once per card width; when the card's height changes (a section opens) the light is simply stretched
        val key="${b.width()}${a.light}"
        if(key!=texKey){texKey=key;fade=1f;tex=render(b.width(),b.height(),g);light.shader=tex?.let{BitmapShader(it,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}}
        val t=tex?:return;light.alpha=(255*fade).toInt();(light.shader as? BitmapShader)?.setLocalMatrix(Matrix().apply{setScale(b.width().toFloat()/t.width,b.height().toFloat()/t.height)})
        if(light.shader==null)return
        c.save();c.translate(r.left,r.top);c.drawRoundRect(0f,0f,r.width(),r.height(),radius,radius,light);c.restore()}
    /** The card's face with its light baked in at full resolution: base colour plus a gaussian glow, each channel
     *  dithered with fine triangular noise in output space, so no 8-bit rings can appear. Cached per size. */
    private fun render(w:Int,h:Int,g:Int):Bitmap?{val key="$w:$g:$fx:$fy:$reach:${a.light}";synchronized(cache){cache[key]?.let{if(!it.isRecycled)return it}}
        val base=if(a.light)Color.WHITE else Ui.sheet(false);val rx=max(w,h)*reach*1.15f
        // gaussian exp(-3.2 q²) reaches ~4 % at the radius: the gradient spans 1.6 radii so it fades fully to nothing
        return Glow.face(w,h,base,g,w*fx,h*fy,rx*1.6f,if(a.light).10f else .2f,3.2f*1.6f*1.6f)?.also{synchronized(cache){cache[key]=it}}}
    private companion object{val cache=HashMap<String,Bitmap>()}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** Three-way pill switch (Неделя / Месяц / Год): the chosen one is a light pill with dark text. */
internal class PeriodPills(private val a:MainActivity,labels:List<String>,selected:Int,stretch:Boolean=false,private val onSelect:(Int)->Unit):FrameLayout(a){
    /** Capsule segmented control: a light capsule glides under the chosen period and the labels change colour with it. */
    private val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
    // stretched (books / comics across the page), the halves share the width and stand a little taller
    private val items=labels.map{s->a.text(s,if(stretch)14f else 13f,a.muted).apply{gravity=Gravity.CENTER;setPadding(a.d(14),a.d(if(stretch)10 else 7),a.d(14),a.d(if(stretch)10 else 7));isClickable=true}}
    private val thumb=View(a).apply{background=android.graphics.drawable.GradientDrawable().apply{setColor(Ui.selected(a.light));cornerRadius=a.d(21).toFloat()}}
    private var current=selected;private var slide:ValueAnimator?=null
    private val on get()=a.fg
    init{setPadding(a.d(3),a.d(3),a.d(3),a.d(3))
        background=android.graphics.drawable.GradientDrawable().apply{setColor(Ui.pressed(a.light));cornerRadius=a.d(24).toFloat()}
        addView(thumb,LayoutParams(0,0));addView(row,LayoutParams(if(stretch)-1 else -2,-2))
        items.forEachIndexed{i,t->row.addView(t,if(stretch)LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-2,-2));t.setOnClickListener{if(i!=current)select(i)}}
        items.forEachIndexed{i,t->t.setTextColor(if(i==current)on else a.muted);t.typeface=PolkaFonts.sans(400)}}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);if(slide?.isRunning!=true)place(items[current].left.toFloat(),items[current].width.toFloat())}
    private fun place(x:Float,w:Float){val lp=thumb.layoutParams;val h=items[0].height;if(lp.width!=w.toInt()||lp.height!=h){lp.width=w.toInt();lp.height=h;thumb.layoutParams=lp}
        thumb.translationX=x;thumb.translationY=0f}
    private fun select(i:Int){val from=current;current=i;onSelect(i)
        val x0=thumb.translationX;val w0=thumb.layoutParams.width.toFloat();val x1=items[i].left.toFloat();val w1=items[i].width.toFloat()
        if(a.reducedMotion){place(x1,w1);items.forEachIndexed{k,t->t.setTextColor(if(k==i)on else a.muted)};return}
        val ev=android.animation.ArgbEvaluator()
        slide?.cancel();slide=ValueAnimator.ofFloat(0f,1f).apply{duration=MotionSpec.SWITCH;interpolator=MotionSpec.move
            addUpdateListener{val k=it.animatedValue as Float;place(x0+(x1-x0)*k,w0+(w1-w0)*k)
                items[from].setTextColor(ev.evaluate(k,on,a.muted) as Int);items[i].setTextColor(ev.evaluate(k,a.muted,on) as Int)};start()}}
}

/** The streak flame (approved look): the classic three-layer flame with a warm halo, a richer outer gradient, light
 *  from the core, soft glows between layers and depth at the foot. It never moves itself — only tiny sparks rise from
 *  its tips, drift and fade. Without a streak it is grey and still; with reduced motion there are no sparks. */
internal class StreakFlameView(private val a:MainActivity,private val active:Boolean,private val motion:Boolean):View(a){
    private val paths=listOf("M60 145C32 145 12 129 9 108C5 86 15 66 31 51C29 63 31 74 38 81C35 61 48 48 57 34C65 21 62 10 54 3C82 5 101 25 99 49C98 62 92 73 97 81C101 87 108 82 111 72C124 90 119 112 105 128C94 140 78 146 60 145Z",
        "M60 145C43 145 31 133 30 117C29 105 34 94 42 86C43 94 46 99 51 101C44 84 49 66 63 52C67 47 72 44 77 42C71 59 75 72 84 85C94 99 99 111 94 125C89 139 76 146 60 145Z",
        "M60 145C49 145 41 137 42 126C42 116 46 107 51 101C51 110 56 114 61 115C57 106 58 97 63 89C65 101 74 109 78 119C85 134 76 145 60 145Z").map{androidx.core.graphics.PathParser.createPathFromPathData(it)!!}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var time=0f;private var clock:ValueAnimator?=null
    private data class Spark(val x:Float,val d:Float,val life:Float,val dr:Float,val r:Float)
    private val sparks=List(7){i->Spark(34f+((i*37)%56),((i*.37f)%1f),1.3f+((i*.53f)%.7f),(if(i%2==1)1 else -1)*(4f+(i*3)%6),1.2f+((i*.7f)%1.4f))}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;if(android.os.Build.VERSION.SDK_INT<28)setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(active&&motion&&clock==null)clock=ValueAnimator.ofFloat(0f,1000f).apply{duration=1_000_000L;interpolator=null;repeatCount=ValueAnimator.INFINITE;addUpdateListener{time=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;super.onDetachedFromWindow()}
    private fun lin(y0:Float,y1:Float,colors:IntArray,stops:FloatArray)=LinearGradient(0f,y0,0f,y1,colors,stops,Shader.TileMode.CLAMP)
    override fun onDraw(c:Canvas){draw0(c)}
    private fun draw0(c:Canvas){if(width<1||height<1)return
        val s=minOf(width/150f,height/185f);c.save();c.translate(width/2f-60*s,height-165*s);c.scale(s,s)
        p.reset();p.isAntiAlias=true;p.color=Color.WHITE
        if(!active){
            p.shader=lin(3f,145f,intArrayOf(0xffa5a5ac.toInt(),0xff83838c.toInt(),0xff62626c.toInt()),floatArrayOf(0f,.55f,1f));c.drawPath(paths[0],p)
            p.shader=lin(42f,145f,intArrayOf(0xffc5c5cb.toInt(),0xffb1b1b9.toInt(),0xffdedee2.toInt()),floatArrayOf(0f,.6f,1f));c.drawPath(paths[1],p)
            p.shader=lin(89f,145f,intArrayOf(0xffe2e2e7.toInt(),0xfff5f5f7.toInt()),floatArrayOf(0f,1f));c.drawPath(paths[2],p);p.shader=null;c.restore();return}
        // halo
        p.shader=RadialGradient(60f,98f,62f,intArrayOf(0x66ff6e1e,0x1fff5a1e,0x00ff5a1e),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);c.drawCircle(60f,98f,62f,p)
        // outer flame
        p.shader=lin(3f,145f,intArrayOf(0xffe8231c.toInt(),0xffff3d1a.toInt(),0xffff6312.toInt(),0xffff8a10.toInt(),0xffffb21e.toInt()),floatArrayOf(0f,.22f,.45f,.7f,1f));c.drawPath(paths[0],p)
        c.save();c.clipPath(paths[0])
        p.shader=RadialGradient(62f,118f,62f,intArrayOf(0x8cffd65a.toInt(),0x00ffd65a),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,120f,150f,p)   // the core lights it from inside
        p.shader=RadialGradient(24f,78f,34f,intArrayOf(0x33ffffff,0x00ffffff),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,120f,150f,p)           // sheen on the left tongue
        p.shader=lin(118f,147f,intArrayOf(0x00961e0a,0x47961e0a),floatArrayOf(0f,1f));c.drawRect(0f,0f,120f,150f,p)                                 // depth at the foot
        c.restore()
        // middle and core, each with a soft glow over the layer below
        p.shader=lin(42f,145f,intArrayOf(0xffffcf2e.toInt(),0xffffdd48.toInt(),0xffffec7a.toInt(),0xfffff3a6.toInt()),floatArrayOf(0f,.4f,.75f,1f))
        p.setShadowLayer(14f,0f,0f,0xe6ffc83c.toInt());c.drawPath(paths[1],p);p.clearShadowLayer()
        p.shader=RadialGradient(60f,126f,30f,intArrayOf(0xffffffff.toInt(),0xfffffbe0.toInt(),0xfffff1a0.toInt()),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP)
        p.setShadowLayer(10f,0f,0f,0xf2fffad2.toInt());c.drawPath(paths[2],p);p.clearShadowLayer();p.shader=null
        // sparks above the tips
        if(motion)for(sp in sparks){val ph=((time/sp.life)+sp.d)%1f;val y=34f-ph*58f;val x=sp.x+sp.dr*kotlin.math.sin(ph*Math.PI.toFloat())+(sp.x-60f)*.2f*ph
            val al=(kotlin.math.sin(Math.PI.toFloat()*ph)*(.75f+.25f*kotlin.math.sin(time*18f+sp.x))).coerceIn(0f,1f);val r=sp.r*(1-ph*.5f)
            p.shader=RadialGradient(x,y,r*3.2f,intArrayOf(((.55f*al*255).toInt() shl 24) or 0xffc850,0x00ff8c28),null,Shader.TileMode.CLAMP);c.drawCircle(x,y,r*3.2f,p);p.shader=null
            p.color=((al*255).toInt() shl 24) or 0xfff0be;c.drawCircle(x,y,r,p);p.color=Color.WHITE}
        c.restore()}
}

/** A big numeral that counts up, drawn directly (number in the display serif, the words after it smaller in the sans).
 *  Its size is fixed by the final value, so counting never triggers a layout. */
internal class CountNumber(private val a:MainActivity,private val n:Int,private val unit:String,size:Float,unitScale:Float,unitColor:Int,private val delay:Long,private val motion:Boolean):View(a){
    private val sd=a.resources.displayMetrics.scaledDensity
    private val pn=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.display();textSize=size*sd;color=a.fg;letterSpacing=-.01f;fontFeatureSettings="lnum, tnum"}
    private val pu=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(400);textSize=size*unitScale*sd;color=unitColor}
    private var shown=if(motion&&n>0)0 else n;private var started=false
    init{contentDescription="$n$unit";importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    override fun onMeasure(w:Int,h:Int){val fm=pn.fontMetrics;setMeasuredDimension(ceil(pn.measureText("$n")+pu.measureText(unit)).toInt()+a.d(2),ceil(fm.descent-fm.ascent).toInt())}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(started||shown>=n)return;started=true
        ValueAnimator.ofInt(0,n).apply{startDelay=delay;duration=Motion.LONG;interpolator=MotionSpec.move
            addUpdateListener{val v=it.animatedValue as Int;if(v!=shown){shown=v;invalidate()}};start()}}
    override fun onDraw(c:Canvas){draw0(c)}
    private fun draw0(c:Canvas){val base=-pn.fontMetrics.ascent;val s="$shown";val full=pn.measureText("$n")
        // the number grows towards the words (right-aligned in the final number's width), the words never move
        c.drawText(s,full-pn.measureText(s),base,pn);c.drawText(unit,full,base,pu)}
}

/** Soft light, made by Skia (native and fast enough for the first frame, so nothing appears late). */
internal object Glow{
    /** Whatever [draw] paints, blurred with a gaussian of [sigma] (full-size pixels), into a bitmap 1/[k] the size. */
    fun blurred(w:Int,h:Int,k:Int,sigma:Float,draw:(Canvas,Paint)->Unit):Bitmap?{if(w<2||h<2)return null
        return try{val b=Bitmap.createBitmap(max(1,w/k),max(1,h/k),Bitmap.Config.ARGB_8888);val c=Canvas(b);c.scale(1f/k,1f/k)
            val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{maskFilter=BlurMaskFilter(max(.5f,sigma/.57735f),BlurMaskFilter.Blur.NORMAL)}
            draw(c,p);b}catch(e:Throwable){null}}
    /** An opaque face: [base] with a round gaussian light of [glow] at (cx, cy), dithered so it shows no rings. */
    fun face(w:Int,h:Int,base:Int,glow:Int,cx:Float,cy:Float,r:Float,peak:Float,fall:Float):Bitmap?{if(w<2||h<2)return null
        return try{val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(base)
            val n=14;val pos=FloatArray(n){it/(n-1f)};val rgb=glow and 0xffffff
            val cols=IntArray(n){val t=pos[it];(((255*peak*exp(-fall*t*t)).roundToInt().coerceIn(0,255)) shl 24) or rgb};cols[n-1]=rgb
            c.drawRect(0f,0f,w.toFloat(),h.toFloat(),Paint(Paint.ANTI_ALIAS_FLAG).apply{isDither=true;shader=RadialGradient(cx,cy,r,cols,pos,Shader.TileMode.CLAMP)});b}catch(e:Throwable){null}}
}
