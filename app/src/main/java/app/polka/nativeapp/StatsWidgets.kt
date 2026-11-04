package app.polka.nativeapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.*

/** Statistics widgets (approved mockup «вариант A»): dark cards, each with its own colour glowing softly from inside. */
internal class WidgetCardDrawable(private val a:MainActivity,private val radius:Float,private val glow:Int?,private val fx:Float,private val fy:Float,private val reach:Float):Drawable(){
    private val base=Paint(Paint.ANTI_ALIAS_FLAG);private val light=Paint(Paint.ANTI_ALIAS_FLAG);private val r=RectF()
    override fun draw(c:Canvas){val b=bounds;if(b.width()<=0||b.height()<=0)return;r.set(b)
        base.color=if(a.light)Color.WHITE else 0xff1c1c1f.toInt();c.drawRoundRect(r,radius,radius,base)
        val g=glow?:return
        // one radial glow, stretched to the card's shape; its edge fades to nothing well inside the card
        val cx=r.left+r.width()*fx;val cy=r.top+r.height()*fy;val rx=max(r.width(),r.height())*reach
        val alpha=if(a.light)0x2a else 0x5c
        light.shader=RadialGradient(cx,cy,rx,intArrayOf((g and 0xffffff) or (alpha shl 24),(g and 0xffffff) or ((alpha/4) shl 24),g and 0xffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(r,radius,radius,light)}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.TRANSLUCENT
}

/** The flame's outline (from the streak flame), used as a tiny glyph inside the week's day circles. */
internal object FlameGlyph{
    val path:Path by lazy{androidx.core.graphics.PathParser.createPathFromPathData("M60 145C32 145 12 129 9 108C5 86 15 66 31 51C29 63 31 74 38 81C35 61 48 48 57 34C65 21 62 10 54 3C82 5 101 25 99 49C98 62 92 73 97 81C101 87 108 82 111 72C124 90 119 112 105 128C94 140 78 146 60 145Z")}
}

/** The streak's week: seven days as circles. A kept day burns (orange with a small flame), a missed one is an empty
 *  ring, and today's ring fills as you read toward the daily goal. Weekday names underneath; today in bold. */
internal class WeekFlames(private val a:MainActivity,private val names:List<String>,private val kept:BooleanArray,private val todayFraction:Float,private val motion:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
    private var k=if(motion)0f else 1f
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onMeasure(w:Int,h:Int){setMeasuredDimension(MeasureSpec.getSize(w),a.d(62))}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(k<1f)ValueAnimator.ofFloat(0f,1f).apply{duration=900;startDelay=180;interpolator=Ease.outCubic;addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat();val n=names.size;if(n==0)return
        val r=min(18*d,width/n/2f-3*d);val slot=width/n.toFloat();val cy=r+2*d
        for(i in 0 until n){val cx=slot*i+slot/2;val today=i==n-1
            // the days light up one after another
            val t=((k*(n+2))-i).coerceIn(0f,1f);val pop=if(t<1f)Ease.outBack(1.8f).getInterpolation(t) else 1f
            if(kept[i]){c.save();c.scale(pop,pop,cx,cy);p.color=Color.WHITE
                p.shader=RadialGradient(cx,cy,r*1.45f,intArrayOf(0x2eff6e28,0x00ff6e28),null,Shader.TileMode.CLAMP);if(!a.light)c.drawCircle(cx,cy,r*1.45f,p)
                p.shader=LinearGradient(cx-r,cy-r,cx+r*.4f,cy+r,0xffffb522.toInt(),0xffff5a1e.toInt(),Shader.TileMode.CLAMP);c.drawCircle(cx,cy,r,p);p.shader=null
                val s=r*1.0f/150f*1.05f;c.save();c.translate(cx-60*s,cy-80*s);c.scale(s,s);p.color=Color.WHITE;c.drawPath(FlameGlyph.path,p);c.restore()
                c.restore()}
            else{p.style=Paint.Style.STROKE;p.strokeWidth=3*d;p.color=if(a.light)0x1a000000 else 0x24ffffff;c.drawCircle(cx,cy,r-1.5f*d,p)
                if(today&&todayFraction>0f){p.color=0xffffb522.toInt();p.strokeCap=Paint.Cap.ROUND;c.drawArc(cx-r+1.5f*d,cy-r+1.5f*d,cx+r-1.5f*d,cy+r-1.5f*d,-90f,360f*todayFraction*k,false,p);p.strokeCap=Paint.Cap.BUTT}
                p.style=Paint.Style.FILL}
            tp.textSize=11*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(if(today)600 else 400)
            tp.color=if(today)a.fg else a.muted;c.drawText(names[i],cx,height-3*d,tp)}}
}

/** Three-way pill switch (Неделя / Месяц / Год): the chosen one is a light pill with dark text. */
internal class PeriodPills(private val a:MainActivity,labels:List<String>,selected:Int,private val onSelect:(Int)->Unit):LinearLayout(a){
    private val items=labels.map{s->a.text(s,12f,a.muted).apply{gravity=Gravity.CENTER;setPadding(a.d(9),a.d(5),a.d(9),a.d(5));isClickable=true}}
    private var current=selected
    init{orientation=HORIZONTAL;setPadding(a.d(3),a.d(3),a.d(3),a.d(3))
        background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0x0d000000 else 0x12ffffff);cornerRadius=a.d(12).toFloat()}
        items.forEachIndexed{i,t->addView(t);t.setOnClickListener{if(i!=current){current=i;paint();onSelect(i)}}};paint()}
    private fun paint(){items.forEachIndexed{i,t->val on=i==current
        t.setTextColor(if(on)(if(a.light)Color.WHITE else 0xff111111.toInt()) else a.muted);t.typeface=PolkaFonts.sans(if(on)600 else 400)
        t.background=if(on)android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0xff161617.toInt() else 0xfff2f2f2.toInt());cornerRadius=a.d(9).toFloat()} else null}}
}

/** Reading time as a smooth glowing line (violet → pink) over a soft fill, with the average as a dashed line.
 *  Press and HOLD to read a day (a plain tap does nothing); slide to move between days; a tap anywhere else closes it. */
internal class ReadingLine(private val a:MainActivity,private val values:List<Double>,private val labels:List<String>,private val tips:List<String>,
                           private val average:Double,private val averageText:String,private val motion:Boolean,private val sparseLabels:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG)
    private val c1=0xff7c5cff.toInt();private val c2=0xffff5aa0.toInt()
    private var draw=if(motion)0f else 1f
    private val line=Path();private val area=Path();private val pts=ArrayList<PointF>()
    init{contentDescription=tips.lastOrNull().orEmpty();isClickable=true}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(draw<1f)ValueAnimator.ofFloat(0f,1f).apply{duration=900;startDelay=160;interpolator=Ease.outExpo;addUpdateListener{draw=it.animatedValue as Float;invalidate()};start()}}
    private val top get()=a.d(40).toFloat();private val bottom get()=height-a.d(26).toFloat()
    private fun xOf(i:Int)=a.d(10)+(width-a.d(20))*(if(values.size<=1).5f else i/(values.size-1f))
    private fun build(){pts.clear();line.reset();area.reset();if(values.isEmpty())return
        val max=max(values.maxOrNull()?:0.0,max(average,1.0)*1.15);val h=bottom-top
        values.forEachIndexed{i,v->pts.add(PointF(xOf(i),bottom-(v/max*h).toFloat()))}
        line.moveTo(pts[0].x,pts[0].y)
        for(i in 1 until pts.size){val p0=pts[i-1];val p1=pts[i];val mx=(p0.x+p1.x)/2;line.cubicTo(mx,p0.y,mx,p1.y,p1.x,p1.y)}
        area.set(line);area.lineTo(pts.last().x,bottom+a.d(2));area.lineTo(pts[0].x,bottom+a.d(2));area.close()}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){build();glowBitmap=null}
    private var glowBitmap:Bitmap?=null
    private val glowPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{alpha=0xc0}
    /** The line, drawn thick at a third of the size and gaussian-blurred once: a soft halo that fades smoothly. */
    private fun glow():Bitmap?{glowBitmap?.let{return it};if(width<3||height<3||pts.isEmpty())return null
        return try{val k=3;val small=Bitmap.createBitmap(width/k,height/k,Bitmap.Config.ARGB_8888);val c=Canvas(small);c.scale(1f/k,1f/k)
            val gp=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;strokeWidth=a.d(6).toFloat()
                shader=LinearGradient(0f,0f,width.toFloat(),0f,c1,c2,Shader.TileMode.CLAMP)}
            c.drawPath(line,gp);val out=ShelfFrost.blur(small,a.d(9).toFloat()/k/2.2f);small.recycle();glowBitmap=out;out}catch(e:Exception){null}}
    // ── details on long press (same behaviour as the chart it replaces) ──
    private var tipIndex=-1;private var tipShow=0f;private var tipX=0f
    private var showAnim:ValueAnimator?=null;private var moveAnim:ValueAnimator?=null
    private var downX=0f;private var downY=0f;private var scrubbing=false;private var tapStartedOpen=false
    private val slop=ViewConfiguration.get(a).scaledTouchSlop
    private val hold=Runnable{scrubbing=true;parent?.requestDisallowInterceptTouchEvent(true);performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);pick(index(downX),tipShow>0f);show(true)}
    private val outside:(MotionEvent)->Unit={e->if(tipIndex>=0){val loc=IntArray(2);getLocationOnScreen(loc)
        if(e.rawX<loc[0]||e.rawX>loc[0]+width||e.rawY<loc[1]||e.rawY>loc[1]+height)show(false)}}
    private fun index(x:Float):Int{if(values.size<=1)return 0;return ((x-a.d(10))/(width-a.d(20))*(values.size-1)).roundToInt().coerceIn(0,values.lastIndex)}
    private fun pick(i:Int,glide:Boolean){if(i==tipIndex)return;val first=tipIndex<0;tipIndex=i;contentDescription=tips[i]
        if(!first)performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        val target=xOf(i);moveAnim?.cancel()
        if(!glide||!motion){tipX=target;invalidate();return}
        val sp=Spring.of(.15,260.0);moveAnim=ValueAnimator.ofFloat(tipX,target).apply{duration=sp.duration;interpolator=sp.interpolator;addUpdateListener{tipX=it.animatedValue as Float;invalidate()};start()}}
    private fun show(on:Boolean){showAnim?.cancel()
        if(on)a.touchWatchers.add(outside) else a.touchWatchers.remove(outside)
        if(!motion){tipShow=if(on)1f else 0f;if(!on)tipIndex=-1;invalidate();return}
        val sp=if(on)Spring.of(.3,360.0) else Spring.of(0.0,220.0)
        showAnim=ValueAnimator.ofFloat(tipShow,if(on)1f else 0f).apply{duration=sp.duration;interpolator=sp.interpolator;addUpdateListener{tipShow=it.animatedValue as Float;invalidate()}
            if(!on)addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(tipShow<=0.001f){tipIndex=-1;invalidate()}}});start()}}
    override fun onDetachedFromWindow(){a.touchWatchers.remove(outside);removeCallbacks(hold);super.onDetachedFromWindow()}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{downX=e.x;downY=e.y;scrubbing=false;tapStartedOpen=tipIndex>=0;postDelayed(hold,ViewConfiguration.getLongPressTimeout().toLong())}
        MotionEvent.ACTION_MOVE->{if(scrubbing)pick(index(e.x),true) else if(abs(e.x-downX)>slop||abs(e.y-downY)>slop)removeCallbacks(hold)}
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{removeCallbacks(hold);if(!scrubbing&&e.actionMasked==MotionEvent.ACTION_UP&&tapStartedOpen)show(false);scrubbing=false}}
        return true}
    override fun onDraw(c:Canvas){if(values.isEmpty()||width<1)return;if(pts.size!=values.size)build();val d=a.d(1).toFloat()
        val max=max(values.maxOrNull()?:0.0,max(average,1.0)*1.15)
        // average
        val ay=bottom-(average/max*(bottom-top)).toFloat()
        p.style=Paint.Style.STROKE;p.strokeWidth=1.3f*d;p.color=if(a.light)0x26000000 else 0x2effffff;p.pathEffect=DashPathEffect(floatArrayOf(4*d,4*d),0f)
        c.drawLine(0f,ay,width.toFloat(),ay,p);p.pathEffect=null
        
        // the line draws itself in from the left
        c.save();c.clipRect(0f,0f,width*draw+2*d,height.toFloat())
        p.style=Paint.Style.FILL;p.shader=LinearGradient(0f,top,0f,bottom,(c2 and 0xffffff) or ((if(a.light)0x30 else 0x47) shl 24),c2 and 0xffffff,Shader.TileMode.CLAMP);c.drawPath(area,p)
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND
        val grad=LinearGradient(0f,0f,width.toFloat(),0f,c1,c2,Shader.TileMode.CLAMP)
        if(!a.light)glow()?.let{g->p.shader=null;p.alpha=255;p.style=Paint.Style.FILL;c.drawBitmap(g,null,RectF(0f,0f,width.toFloat(),height.toFloat()),glowPaint);p.style=Paint.Style.STROKE}
        p.shader=grad;p.alpha=255;p.strokeWidth=3*d;c.drawPath(line,p);p.shader=null;p.style=Paint.Style.FILL
        c.restore()
        // day labels; the open one sits in a light pill
        tp.textAlign=Paint.Align.CENTER;tp.textSize=11*a.resources.displayMetrics.scaledDensity
        for(i in values.indices){val on=i==tipIndex&&tipShow>0f;val last=i==values.lastIndex
            if(sparseLabels&&!(i==0||last||on||(i+1)%5==0))continue
            val x=xOf(i);val y=height-7*d
            if(on){val w=tp.measureText(labels[i])+12*d;val k=tipShow.coerceIn(0f,1f);p.color=if(a.light)0xff161617.toInt() else 0xfff2f2f2.toInt();p.alpha=(255*k).toInt();c.drawRoundRect(x-w/2,y-13*d,x+w/2,y+5*d,6*d,6*d,p);p.alpha=255}
            tp.typeface=PolkaFonts.sans(if(on||last)600 else 400);tp.color=if(on)(if(a.light)Color.WHITE else 0xff111111.toInt()) else if(last)a.fg else a.muted
            c.drawText(labels[i],x,y,tp)}
        drawTip(c)}
    private fun drawTip(c:Canvas){val i=tipIndex;if(i<0||tipShow<=0.001f||i>=pts.size)return;val d=a.d(1).toFloat();val k=tipShow.coerceIn(0f,1.15f);val alpha=(255*k.coerceAtMost(1f)).toInt()
        val y=pts[i].y;val x=tipX
        // the vertical guide from the point down to the axis, and the point itself
        p.color=if(a.light)0xff161617.toInt() else 0xfff2f2f2.toInt();p.alpha=alpha;c.drawRect(x-.75f*d,y,x+.75f*d,bottom+2*d,p)
        p.color=Color.WHITE;p.alpha=alpha;c.drawCircle(x,y,6*d*k.coerceAtMost(1f),p);p.style=Paint.Style.STROKE;p.strokeWidth=3*d;p.color=c2;p.alpha=alpha;c.drawCircle(x,y,6*d*k.coerceAtMost(1f),p);p.style=Paint.Style.FILL
        val text=tips[i];tp.textSize=12.5f*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(600);tp.textAlign=Paint.Align.CENTER
        val tw=tp.measureText(text)+a.d(20);val th=a.d(26).toFloat()
        val l=(x-tw/2).coerceIn(0f,width-tw);val top=(y-th-a.d(10)).coerceAtLeast(0f);val r=RectF(l,top,l+tw,top+th)
        c.save();c.translate(0f,(1f-k.coerceAtMost(1f))*a.d(8));val sc=.82f+.18f*k;c.scale(sc,sc,x.coerceIn(r.left,r.right),r.bottom)
        p.color=if(a.light)0xff161617.toInt() else 0xfff2f2f2.toInt();p.alpha=alpha;c.drawRoundRect(r,th/2,th/2,p)
        tp.color=if(a.light)Color.WHITE else 0xff111111.toInt();tp.alpha=alpha;c.drawText(text,r.centerX(),r.centerY()-(tp.ascent()+tp.descent())/2,tp)
        c.restore();tp.alpha=255;p.alpha=255}
}

/** Speed on a cool blue bar: a faint tick marks the average (~200 words a minute), a glowing knob marks you. */
internal class SpeedBar(private val a:MainActivity,private val fraction:Float?,private val average:Float,private val motion:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var k=if(motion)0f else 1f
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(k<1f){val sp=Spring.of(.22,720.0);ValueAnimator.ofFloat(0f,1f).apply{duration=sp.duration;startDelay=220;interpolator=sp.interpolator;addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat();val cy=height/2f;val h=8*d;val pad=9*d
        p.shader=LinearGradient(pad,0f,width-pad,0f,intArrayOf(0xff2f3f6e.toInt(),0xff4a72c9.toInt(),0xff8ab8ff.toInt()),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(pad,cy-h/2,width-pad,cy+h/2,h/2,h/2,p);p.shader=null
        val ax=pad+(width-2*pad)*average;p.color=if(a.light)0x66000000 else 0x8cffffff.toInt();c.drawRoundRect(ax-d,cy-9*d,ax+d,cy+9*d,d,d,p)
        val f=fraction?:return;val x=pad+(width-2*pad)*f*k
        if(!a.light){p.shader=RadialGradient(x,cy,16*d,intArrayOf(0x998ab8ff.toInt(),0x008ab8ff),null,Shader.TileMode.CLAMP);c.drawCircle(x,cy,16*d,p);p.shader=null}
        p.color=0x408ab8ff;c.drawCircle(x,cy,12*d,p);p.color=Color.WHITE;c.drawCircle(x,cy,8*d,p)
        if(a.light){p.style=Paint.Style.STROKE;p.strokeWidth=1.5f*d;p.color=0x33000000;c.drawCircle(x,cy,8*d,p);p.style=Paint.Style.FILL}}
}

/** The year's books as a road: it winds from the start (a green ring, January) to a finish flag. Each book of the goal
 *  is a milestone on it; the part already travelled glows green, and the latest book's cover stands where you are. */
internal class BookRoad(private val a:MainActivity,private val done:Int,private val target:Int,private val latest:org.json.JSONObject?,private val motion:Boolean):FrameLayout(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val road=Path();private val measure=PathMeasure();private var length=0f
    private val goal=max(1,target);private val fraction=(done.toFloat()/goal).coerceIn(0f,1f)
    private var k=if(motion)0f else 1f
    private val pin:CoverView?=latest?.let{b->CoverView(a,b,a.cover(b)).apply{softShadow=true}}
    init{setWillNotDraw(false);clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        pin?.let{addView(it,LayoutParams(a.d(26),a.d(39)));it.alpha=0f}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(k<1f){val sp=Spring.of(.12,1100.0);ValueAnimator.ofFloat(0f,1f).apply{duration=sp.duration;startDelay=260;interpolator=sp.interpolator;addUpdateListener{k=it.animatedValue as Float;place();invalidate()};start()}} else post{place()}}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat();val H=h.toFloat();val d=a.d(1).toFloat()
        // the same S-shaped road as the mockup, stretched to the card
        fun x(f:Float):Float{return 14*d+(W-34*d)*f}
        fun y(f:Float):Float{return 12*d+(H-24*d)*f}
        road.reset();road.moveTo(x(0f),y(.98f))
        road.cubicTo(x(.195f),y(1f),x(.195f),y(.6f),x(.4f),y(.6f))
        road.cubicTo(x(.6f),y(.6f),x(.63f),y(.93f),x(.82f),y(.74f))
        road.cubicTo(x(.93f),y(.63f),x(1f),y(.26f),x(1f),y(.2f))
        measure.setPath(road,false);length=measure.length;place()}
    private val pos=FloatArray(2)
    private fun at(f:Float):FloatArray{measure.getPosTan(length*f.coerceIn(0f,1f),pos,null);return pos}
    private fun place(){val v=pin?:return;if(length<=0f||done<=0)return;val q=at(fraction*k);v.translationX=q[0]-a.d(13);v.translationY=q[1]-a.d(52);v.alpha=k.coerceIn(0f,1f)}
    override fun onDraw(c:Canvas){if(length<=0f)return;val d=a.d(1).toFloat()
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x0f000000 else 0x12ffffff;p.strokeWidth=16*d;c.drawPath(road,p)
        p.color=if(a.light)0x33000000 else 0x38ffffff;p.strokeWidth=1.4f*d;p.pathEffect=DashPathEffect(floatArrayOf(5*d,7*d),0f);c.drawPath(road,p);p.pathEffect=null
        val go=fraction*k
        if(go>0f){val part=Path();measure.getSegment(0f,length*go,part,true)
            val green=if(a.light)0xff1f9d63.toInt() else 0xff2fd38a.toInt()
            if(!a.light){p.color=green;p.alpha=0x33;p.strokeWidth=12*d;c.drawPath(part,p);p.alpha=0x55;p.strokeWidth=8*d;c.drawPath(part,p);p.alpha=255}
            p.shader=LinearGradient(0f,0f,width.toFloat(),0f,green,if(a.light)0xff52c68f.toInt() else 0xff9df5c8.toInt(),Shader.TileMode.CLAMP);p.strokeWidth=5*d;c.drawPath(part,p);p.shader=null}
        p.style=Paint.Style.FILL
        // milestones: one per book of the goal (thinned out for very large goals)
        val step=max(1,ceil(goal/40f).toInt())
        var i=step;while(i<goal){val q=at(i/goal.toFloat());val passed=i<=done
            p.color=if(passed)(if(a.light)0xff1f9d63.toInt() else Color.WHITE) else if(a.light)0x40000000 else 0x47ffffff
            c.drawCircle(q[0],q[1],(if(passed)3.2f else 2.2f)*d,p);i+=step}
        // start ring
        val s=at(0f);p.color=if(a.light)Color.WHITE else 0xff1c1c1f.toInt();c.drawCircle(s[0],s[1],6*d,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=3*d;p.color=if(a.light)0xff1f9d63.toInt() else 0xff2fd38a.toInt();c.drawCircle(s[0],s[1],6*d,p);p.style=Paint.Style.FILL
        // finish flag
        val e=at(1f);p.color=a.fg;p.strokeWidth=2*d;c.drawRect(e[0]-d,e[1]-28*d,e[0]+d,e[1]+2*d,p)
        val flag=Path().apply{moveTo(e[0]+d,e[1]-28*d);lineTo(e[0]+18*d,e[1]-23*d);lineTo(e[0]+d,e[1]-17*d);close()};c.drawPath(flag,p)}
}

/** Small "Все ›" pill used on the books card. */
internal fun MainActivity.softPill(label:String,click:()->Unit):TextView=text(label,12.5f,fg,true).apply{
    setPadding(d(12),d(6),d(12),d(6));background=android.graphics.drawable.GradientDrawable().apply{setColor(if(light)0x12000000 else 0x24ffffff);cornerRadius=d(14).toFloat()}
    isClickable=true;setOnClickListener{click()};press(this)}

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
    override fun onDraw(c:Canvas){if(width<1||height<1)return
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
