package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.*

// ───────────────────────── «Огонёк»: minutes a day (approved animation) ─────────────────────────

private val flameSteps=listOf(15 to "Пара страниц перед сном",30 to "Спокойный вечер с книгой",45 to "Глава в день",60 to "Читатель всерьёз",Int.MAX_VALUE to "Книжный марафон")
private fun stepOf(m:Int)=flameSteps.indexOfFirst{m<it.first}
private val stepFrom=intArrayOf(5,15,30,45,60);private val stepTo=intArrayOf(15,30,45,60,90)

/** The window: the app's flame (always its own crisp shape) on a warm stage, the rolling number, the step's name and the «heat» bar.
 *  Saved when the window closes. */
internal fun MainActivity.flameSheet(initial:Int,saved:(Int)->Unit){
    var value=((initial.coerceIn(5,90)+2)/5)*5;val start=value
    val stage=FrameLayout(this).apply{clipChildren=false;clipToPadding=false}
    val back=FlameStage(this,true);val front=FlameStage(this,false);front.shared=back
    stage.addView(back,FrameLayout.LayoutParams(-1,-1))
    val col=column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(d(20),0,d(20),d(24))}
    col.addView(View(this),LinearLayout.LayoutParams(-1,d(212)))
    val numRow=line().apply{gravity=Gravity.BOTTOM}
    val number=Odometer(this,"$value",58f,fg,true,serif=true);numRow.addView(number)
    numRow.addView(text("мин в день",16f,muted).apply{setPadding(d(6),0,0,d(10))})
    col.addView(numRow,LinearLayout.LayoutParams(-2,-2))
    val names=FrameLayout(this)
    val nameA=text(flameSteps[stepOf(value)].second,14f,muted).apply{gravity=Gravity.CENTER};val nameB=text("",14f,muted).apply{gravity=Gravity.CENTER;alpha=0f}
    names.addView(nameA,FrameLayout.LayoutParams(-1,-2));names.addView(nameB,FrameLayout.LayoutParams(-1,-2))
    col.addView(names,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(4)})
    val bar=HeatBar(this,value)
    col.addView(bar,LinearLayout.LayoutParams(-1,d(56)).apply{topMargin=d(30)})
    stage.addView(col,FrameLayout.LayoutParams(-1,-2))
    stage.addView(front,FrameLayout.LayoutParams(-1,-1))
    back.bar=bar;back.setMinutes(value,true)
    var shownStep=stepOf(value);var useA=true
    bar.onChange={v->val old=value;value=v;number.set("$v","$old",up=v>old);back.setMinutes(v,false)
        val s=stepOf(v);if(s!=shownStep){shownStep=s;val (out,inn)=if(useA)nameA to nameB else nameB to nameA;useA=!useA;inn.text=flameSteps[s].second
            if(prefs.str("motion")=="reduced"){out.alpha=0f;inn.alpha=1f}else{out.animate().alpha(0f).translationY(-d(8).toFloat()).setDuration(220).start();inn.translationY=d(8).toFloat();inn.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}}
    // the stage spans the whole sheet (past the content padding), so the light and the sparks are only ever cut by the sheet's own edge
    val holder=FrameLayout(this).apply{clipChildren=false;addView(stage,FrameLayout.LayoutParams(-1,-2).apply{leftMargin=-d(20);rightMargin=-d(20);bottomMargin=-d(24)})}
    val sheet=dialog().setBare().setView(holder).show()
    // the sheet itself warms with the fire: an ember tint rises from its bottom
    stage.post{run{var g:ViewParent?=holder.parent;while(g is ViewGroup&&g.background==null){g.clipToPadding=false;g.clipChildren=false;g=g.parent}}
        var v:View?=stage.parent as? View;while(v!=null&&v.background !is OpaqueSurface&&v.background !is GradientDrawable)v=v.parent as? View
        v?.let{panel->val r=d(22).toFloat();val tint=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0,0)).apply{cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
            panel.background=LayerDrawable(arrayOf(panel.background,tint));back.tint=tint;back.tintCorner=d(22).toFloat()}}
    sheet.setOnDismissListener{back.stop();if(value!=start){val v=value;updateStatistics{it.put("goal",v)};saved(v)}}
}

/** The fire. [behind]: the light, the waves, the stars and the flame (under the texts and the bar); otherwise the sparks
 *  thrown off the bar, drawn on top. Both share one state, kept by the one behind. */
internal class FlameStage(private val a:MainActivity,private val behind:Boolean):View(a){
    var shared:FlameStage?=null
    var bar:HeatBar?=null
    var tint:GradientDrawable?=null;var tintCorner=0f
    private val d=a.resources.displayMetrics.density
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var minutes=10f;private var step=0
    // springs: size, squash (+ wider and lower, − taller), colour position (0..4 across the step palettes)
    private var s=.62f;private var sv=0f;private var q=0f;private var qv=0f;private var col=0f;private var colv=0f
    private var flash=0f;private var sheen=-1f
    private class Ring(var t:Float)
    private class Star(var x:Float,var y:Float,var vx:Float,var vy:Float,var life:Float,val r:Float,var rot:Float,val spin:Float,val color:Int)
    private class Spark(var x:Float,var y:Float,var vx:Float,var vy:Float,var life:Float,val dur:Float)
    private val rings=mutableListOf<Ring>();private val stars=mutableListOf<Star>();private val twinkles=mutableListOf<Star>();private val sparks=mutableListOf<Spark>()
    private val rnd=java.util.Random(7)
    private var last=0L;private var running=true
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val pals=arrayOf(
        arrayOf(intArrayOf(0xffd8431c.toInt(),0xffe8581a.toInt(),0xfff07c1c.toInt(),0xfff39a26.toInt()),intArrayOf(0xffffbf3a.toInt(),0xffffca4d.toInt(),0xffffd977.toInt(),0xffffe39a.toInt()),intArrayOf(0xffffe07a.toInt(),0xffffedae.toInt(),0xfffff8e0.toInt())),
        arrayOf(intArrayOf(0xfff52d32.toInt(),0xffff4c16.toInt(),0xffff8006.toInt(),0xffffb522.toInt()),intArrayOf(0xffffe347.toInt(),0xffffe54d.toInt(),0xfffff17b.toInt(),0xfffff4ab.toInt()),intArrayOf(0xfffff075.toInt(),0xfffff7ad.toInt(),0xfffffef0.toInt())),
        arrayOf(intArrayOf(0xffff3b2a.toInt(),0xffff5c12.toInt(),0xffff9208.toInt(),0xffffc52e.toInt()),intArrayOf(0xffffe95a.toInt(),0xffffee6e.toInt(),0xfffff59a.toInt(),0xfffff8c4.toInt()),intArrayOf(0xfffff6a0.toInt(),0xfffffbd0.toInt(),0xffffffff.toInt())),
        arrayOf(intArrayOf(0xffff5a0a.toInt(),0xffff7a00.toInt(),0xffffa214.toInt(),0xffffd040.toInt()),intArrayOf(0xfffff07a.toInt(),0xfffff38e.toInt(),0xfffff8b8.toInt(),0xfffffbe0.toInt()),intArrayOf(0xfffffbe0.toInt(),0xffffffff.toInt(),0xffffffff.toInt())),
        arrayOf(intArrayOf(0xffff2e63.toInt(),0xffff4a3c.toInt(),0xffff7c1a.toInt(),0xffffbc2a.toInt()),intArrayOf(0xffffe06a.toInt(),0xffffe98a.toInt(),0xfffff4b8.toInt(),0xfffffbe6.toInt()),intArrayOf(0xffffffff.toInt(),0xffffffff.toInt(),0xffffffff.toInt())))
    private val stops=arrayOf(floatArrayOf(0f,.3f,.65f,1f),floatArrayOf(0f,.48f,.8f,1f),floatArrayOf(0f,.55f,1f));private val y0=floatArrayOf(3f,42f,89f)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun stop(){running=false}
    private fun within(m:Float):Float{val k=stepOf(m.roundToInt());return ((m-stepFrom[k])/(stepTo[k]-stepFrom[k]).toFloat()).coerceIn(0f,1f)}
    fun setMinutes(m:Int,first:Boolean){val k=stepOf(m);minutes=m.toFloat()
        if(first||reduced){step=k;s=sizeFor(m.toFloat());col=k+within(m.toFloat())*.85f;invalidate();return}
        if(k!=step){val up=k>step;step=k
            if(up){qv+=.0042f;flash=1f;rings.add(Ring(0f));rings.add(Ring(-.18f));sheen=0f
                val c=pals[k][0];repeat(14+k*4){val ang=rnd.nextFloat()*PI.toFloat()*2;val v=(.05f+rnd.nextFloat()*.12f)*d
                    stars.add(Star(width/2f,base()-62*d,cos(ang)*v,sin(ang)*v-.05f*d,1f,(2+rnd.nextFloat()*3.5f)*d,rnd.nextFloat()*3,(rnd.nextFloat()-.5f)*.01f,c[2+it%2]))}
                if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CONFIRM.takeIf{android.os.Build.VERSION.SDK_INT>=30}?:HapticFeedbackConstants.CLOCK_TICK)}
            else qv-=.0016f}
        invalidate()}
    private fun sizeFor(m:Float)=.62f+((m-5f)/85f)*.44f
    private fun base()=212*d-12*d
    private fun heat()=((minutes-5f)/85f).coerceIn(0f,1f)
    private fun stepSpring(x:Float,v:Float,goal:Float,k:Float,damp:Float,dt:Float):Pair<Float,Float>{val acc=(goal-x)*k-v*damp;val nv=v+acc*dt;return (x+nv*dt) to nv}
    private fun tick(dt:Float){
        val g=sizeFor(minutes);stepSpring(s,sv,g,.00016f,.021f,dt).let{s=it.first;sv=it.second}
        stepSpring(q,qv,0f,.00032f,.02f,dt).let{q=it.first;qv=it.second}
        stepSpring(col,colv,step+within(minutes)*.85f,.00014f,.022f,dt).let{col=it.first;colv=it.second}
        flash=max(0f,flash-dt/650f);if(sheen>=0){sheen+=dt/750f;if(sheen>1)sheen=-1f}
        rings.forEach{it.t+=dt/900f};rings.removeAll{it.t>1}
        stars.forEach{it.x+=it.vx*dt;it.y+=it.vy*dt;it.vx*=.994f;it.vy=it.vy*.994f+.00003f*d*dt;it.rot+=it.spin*dt;it.life-=dt/1150f};stars.removeAll{it.life<=0}
        if(step>=2&&rnd.nextFloat()<(step-1)*.5f*dt/60f)twinkles.add(Star(width/2f+(rnd.nextFloat()-.5f)*170*d,base()-64*d+(rnd.nextFloat()-.5f)*110*d,0f,0f,1f,(1.6f+rnd.nextFloat()*2.4f)*d,rnd.nextFloat()*3,0f,0))
        twinkles.forEach{it.life-=dt/900f};twinkles.removeAll{it.life<=0}
        // sparks thrown off the bar's hot end, more the hotter it is
        bar?.let{b->val h=heat();if(rnd.nextFloat()<(h*h*6+(if(h>.3f).6f else 0f))*dt/60f){val ang=-PI.toFloat()/2+(rnd.nextFloat()-.5f)*1.6f;val sp=(.06f+rnd.nextFloat()*.14f*(1+h))*d
            sparks.add(Spark(b.left+b.edgeX(),b.top+b.height/2f-10*d+(rnd.nextFloat()-.5f)*20*d,cos(ang)*sp,sin(ang)*sp,1f,350+rnd.nextFloat()*400))}}
        sparks.forEach{it.x+=it.vx*dt;it.y+=it.vy*dt;it.vy+=.00018f*d*dt;it.life-=dt/it.dur};sparks.removeAll{it.life<=0}
        // the sheet's ember tint
        tint?.let{t->val hw=(col/4f).coerceIn(0f,1f);val bottom=if(a.light)Color.argb(((.07f+hw*.16f+flash*.05f)*255).toInt(),255,150,90) else Color.argb(((.1f+hw*.22f+flash*.08f)*255).toInt(),(120+hw*60).toInt(),(40+hw*20).toInt(),16)
            t.colors=intArrayOf(0,bottom)}
    }
    private fun mix(a1:Int,b1:Int,t:Float)=androidx.core.graphics.ColorUtils.blendARGB(a1,b1,t)
    private fun star(c:Canvas,x:Float,y:Float,r:Float,rot:Float){val path=Path();for(i in 0 until 8){val rr=if(i%2==1)r*.28f else r;val an=i*PI.toFloat()/4+rot;if(i==0)path.moveTo(x+cos(an)*rr,y+sin(an)*rr) else path.lineTo(x+cos(an)*rr,y+sin(an)*rr)};path.close();c.drawPath(path,p)}
    override fun onDraw(c:Canvas){
        if(!behind){shared?.drawSparks(c);if(running&&!reduced)postInvalidateOnAnimation();return}
        val now=System.nanoTime()/1_000_000;val dt=if(last==0L)16f else (now-last).coerceIn(1,48).toFloat();last=now
        if(!reduced)tick(dt)
        val cx=width/2f;val base=base();val T=now.toFloat()
        // round warm light behind, brighter each step; small enough to fade out inside the window
        val ci=col.roundToInt().coerceIn(0,4);val lc=pals[ci][0][2];val R=(96+col*7+flash*10)*d
        p.shader=RadialGradient(cx,base-62*d,R,intArrayOf((lc and 0xffffff) or (((.15f+col*.045f+flash*.28f).coerceAtMost(.6f)*255).toInt() shl 24),(lc and 0xffffff) or (((.05f+col*.02f)*255).toInt() shl 24),lc and 0xffffff),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
        c.drawCircle(cx,base-62*d,R,p);p.shader=null
        // soft waves of light, easing out
        p.style=Paint.Style.STROKE
        rings.forEach{r->if(r.t<0)return@forEach;val e=1-(1-r.t).pow(3);val rr=(34+e*72)*d
            p.color=Color.argb(((1-r.t)*(1-r.t)*.32f*255).toInt(),255,214,150);p.strokeWidth=(10*(1-r.t)+1)*d;c.drawCircle(cx,base-62*d,rr,p)
            p.color=Color.argb(((1-r.t)*.35f*255).toInt(),255,240,210);p.strokeWidth=1.2f*d;c.drawCircle(cx,base-62*d,rr,p)}
        p.style=Paint.Style.FILL
        p.xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD)
        twinkles.forEach{t->val al=sin(t.life*PI.toFloat());p.color=Color.argb((al*.8f*255).toInt(),255,236,190);star(c,t.x,t.y,t.r*al,t.rot)}
        stars.forEach{st->val e=sin(min(1f,st.life*1.3f)*PI.toFloat()/2);p.color=st.color;p.alpha=(e*.35f*255).toInt();p.style=Paint.Style.STROKE;p.strokeWidth=1.2f*d;c.drawLine(st.x,st.y,st.x-st.vx*60,st.y-st.vy*60,p)
            p.style=Paint.Style.FILL;p.alpha=(e*255).toInt();star(c,st.x,st.y,st.r*(.55f+.45f*e),st.rot)}
        p.xfermode=null;p.alpha=255
        // the flame: only scale, squash and a little tilt — never bent
        val breathe=if(reduced)0f else sin(T/640f)*.016f;val tilt=if(reduced)0f else sin(T/1100f)*.02f+sin(T/2100f)*.012f
        val sx=s*(1+q*.9f-breathe*.5f)*d;val sy=s*(1-q+breathe)*d
        val fl=col.coerceIn(0f,4f);val i0=floor(fl).toInt();val i1=min(4,i0+1);val t=fl-i0
        c.save();c.translate(cx,base);c.rotate(Math.toDegrees(tilt.toDouble()).toFloat());c.scale(sx,sy);c.translate(-60f,-145f)
        FlamePaths.paths.forEachIndexed{i,path->val cols=IntArray(stops[i].size){k->mix(pals[i0][i][k],pals[i1][i][k],t)}
            p.shader=LinearGradient(0f,y0[i],0f,145f,cols,stops[i],Shader.TileMode.CLAMP);c.drawPath(path,p)}
        p.shader=null
        // a shine sweeps across once on every new step
        if(sheen in 0f..1f){c.save();c.clipPath(FlamePaths.paths[0]);val x=-40+sheen*200;c.skew(-.32f,0f)
            p.shader=LinearGradient(x-26,0f,x+26,0f,intArrayOf(0x00ffffff,0x8cffffff.toInt(),0x00ffffff),null,Shader.TileMode.CLAMP);c.drawRect(x-26,0f,x+26,150f,p);p.shader=null;c.restore()}
        c.restore()
        // the bar's hot end glows (behind the bar, round, fading out inside the window)
        bar?.let{b->val h=heat();if(h>.05f){val gx=b.left+b.edgeX()-b.height/2f+6*d;val gy=b.top+b.height/2f;val rr=(24+h*30)*d
            p.shader=RadialGradient(gx,gy,rr,intArrayOf(Color.argb(((.18f+.3f*h)*255).toInt(),255,140,40),0x00ff8c28),null,Shader.TileMode.CLAMP);p.xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD);c.drawCircle(gx,gy,rr,p);p.xfermode=null;p.shader=null}}
        bar?.heat=heat();bar?.invalidate()
        if(running&&!reduced)postInvalidateOnAnimation()
    }
    internal fun drawSparks(c:Canvas){val d=d;p.style=Paint.Style.STROKE;p.strokeWidth=1.1f*d;p.xfermode=PorterDuffXfermode(PorterDuff.Mode.ADD)
        sparks.forEach{s->p.color=Color.argb((s.life*255).toInt(),255,(190+s.life*60).toInt(),90);c.drawLine(s.x,s.y,s.x-s.vx*24,s.y-s.vy*24,p)};p.xfermode=null;p.style=Paint.Style.FILL}
}

/** The «heat» capsule: an ember fill that gets hotter (deep red → yellow-white) and glows at its end, slow light running
 *  along it; quiet dots mark the steps; the value on the right. Snaps to 5 minutes with a tick. */
internal class HeatBar(private val a:MainActivity,start:Int):View(a){
    var onChange:(Int)->Unit={}
    var heat=0f;var glowAt=0f
    private var value=start;private var pos=frac(start.toFloat());private var settle:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(700)}
    private val d=a.resources.displayMetrics.density
    init{isClickable=true;contentDescription="Минут в день: $start"}
    private fun frac(v:Float)=((v-5f)/85f).coerceIn(0f,1f)
    fun edgeX():Float{val h=height.toFloat();return h+(width-h)*pos-6*d}
    private fun knob(x:Float):Float{val h=height.toFloat();return ((x-h*.5f)/(width-h).coerceAtLeast(1f)).coerceIn(0f,1f)}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);settle?.cancel();track(e.x)}
        MotionEvent.ACTION_MOVE->track(e.x)
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{track(e.x);val target=frac(value.toFloat());val from=pos
            settle=ValueAnimator.ofFloat(0f,1f).apply{duration=240;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pos=from+(target-from)*(it.animatedValue as Float);invalidate()};start()}}};return true}
    private fun track(x:Float){pos=knob(x);val n=(5+((pos*85f)/5f).roundToInt()*5).coerceIn(5,90)
        if(n!=value){value=n;contentDescription="Минут в день: $n";if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onChange(n)};invalidate()}
    private fun mix(a1:Int,b1:Int,k:Float)=androidx.core.graphics.ColorUtils.blendARGB(a1,b1,k)
    override fun onDraw(c:Canvas){val h=height.toFloat();val w=width.toFloat();val r=h/2;val fx=h+(w-h)*pos;val hot=heat
        p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        p.color=Color.WHITE // the shaders below must not inherit the faint track's alpha
        c.save();val clip=Path().apply{addRoundRect(0f,0f,fx,h,r,r,Path.Direction.CW)};c.clipPath(clip)
        p.shader=LinearGradient(0f,0f,fx,0f,intArrayOf(mix(0xff3a1a12.toInt(),0xff9a2a0c.toInt(),hot),mix(0xff8a3414.toInt(),0xffff5a14.toInt(),hot),mix(0xffc25a26.toInt(),0xffffd25a.toInt(),hot)),floatArrayOf(0f,.6f,1f),Shader.TileMode.CLAMP);c.drawRect(0f,0f,fx,h,p)
        if(a.prefs.str("motion")!="reduced"){val T=System.nanoTime()/1_000_000f;repeat(3){i->val ph=((T/(1400-hot*700))+i/3f)%1f;val bx=ph*(fx+80*d)-40*d
            p.shader=LinearGradient(bx-30*d,0f,bx+30*d,0f,intArrayOf(0x00fff0b4,Color.argb(((.08f+.22f*hot)*255).toInt(),255,240,180),0x00fff0b4),null,Shader.TileMode.CLAMP);c.drawRect(bx-30*d,0f,bx+30*d,h,p)}}
        c.restore();p.shader=null
        listOf(15,30,45,60).forEach{tk->val tx=h/2+(w-h)*frac(tk.toFloat());p.color=if(tx<fx-6*d)0x6bffffff else (if(a.light)0x29000000 else 0x29ffffff);c.drawCircle(tx,h/2,3*d,p)}
        t.textSize=16*a.resources.displayMetrics.scaledDensity;t.textAlign=Paint.Align.RIGHT;t.color=if(fx>w-30*d)0xff1d1d20.toInt() else a.fg;c.drawText("$value",w-20*d,h/2-(t.ascent()+t.descent())/2,t)}
}
