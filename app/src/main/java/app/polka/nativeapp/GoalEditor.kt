package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.*

// ───────────────────────── «Цель на год» (approved animation) ─────────────────────────

/** The year's road with plain milestones (no covers), the rolling number and a ruler you drag or fling. Saved on close;
 *  0 means no goal. */
internal fun MainActivity.goalSheet(year:Int,initial:Int,done:Int,saved:(Int?)->Unit){
    var value=initial.coerceIn(0,999);val start=value
    val box=column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    val road=GoalRoadView(this,done,if(value>0)value else max(done+5,12))
    box.addView(road,LinearLayout.LayoutParams(-1,d(150)).apply{topMargin=d(6)})
    val row=line().apply{gravity=Gravity.BOTTOM}
    val number=Odometer(this,if(value>0)"$value" else "0",56f,fg,true);row.addView(number)
    fun unitFor(n:Int)=if(n<=0)"без цели" else MainActivity.countBooks(n).substringAfter(' ')+" за год"
    val unit=text(unitFor(value),16f,muted).apply{setPadding(d(6),0,0,d(10))};row.addView(unit)
    box.addView(row,LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(14)})
    fun paceFor(n:Int)=if(n<=0)"прочитано $done" else "примерно ${max(1,(n/12f).roundToInt())} в месяц · прочитано $done"
    val pace=text(paceFor(value),14f,muted).apply{gravity=Gravity.CENTER}
    box.addView(pace,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(4)})
    val ruler=GoalRuler(this,value)
    box.addView(ruler,LinearLayout.LayoutParams(-1,d(64)).apply{topMargin=d(22);leftMargin=-d(20);rightMargin=-d(20)})
    ruler.onChange={n->val old=value;value=n;number.set("$n","$old",up=n>old);unit.text=unitFor(n);pace.text=paceFor(n);if(n>0)road.goal=n}
    ruler.onValue={v->if(v>=1f)road.goalFloat=v}
    val sheet=dialog().setView(box).show()
    sheet.setOnDismissListener{road.stop();ruler.stop();if(value!=start){val n=value.takeIf{it>0};updateStatistics{changeAnnualGoal(it,year,n)};saved(n)}}
}

/** The road: read milestones white, the rest faint, the read share green; every milestone glides on its own spring to its
 *  new place (those near the flag answer first); for big goals single milestones give way to labelled marks; the
 *  flag gives a little wave on every new book; «you are here» breathes. */
internal class GoalRoadView(private val a:MainActivity,private val done:Int,start:Int):View(a){
    var goal=start;set(v){if(v!=field){field=v;wave=1f}}
    var goalFloat=start.toFloat()
    private val d=a.resources.displayMetrics.density
    private val road=Path();private val pm=PathMeasure();private var length=0f
    private val pos=FloatArray(2)
    private val f=FloatArray(1000);private val fv=FloatArray(1000);private val al=FloatArray(1000)
    private var wave=0f;private var last=0L;private var running=true
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(500)}
    private val green get()=if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt()
    private val green2 get()=if(a.light)0xff54b581.toInt() else 0xff7bdca6.toInt()
    init{for(i in f.indices){f[i]=(i+1f)/start;al[i]=if(i<start-1)1f else 0f};importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun stop(){running=false}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat();val H=h.toFloat()
        fun x(t:Float)=14*d+(W-48*d)*t;fun y(t:Float)=32*d+(H-44*d)*t
        road.reset();road.moveTo(x(0f),y(.92f));road.cubicTo(x(.16f),y(1f),x(.24f),y(.45f),x(.44f),y(.5f));road.cubicTo(x(.64f),y(.55f),x(.7f),y(.95f),x(.86f),y(.7f));road.cubicTo(x(.95f),y(.56f),x(.98f),y(.3f),x(1f),y(.2f))
        pm.setPath(road,false);length=pm.length}
    private fun at(t:Float):FloatArray{pm.getPosTan(length*t.coerceIn(0f,1f),pos,null);return floatArrayOf(pos[0],pos[1])}
    override fun onDraw(c:Canvas){if(length<=0f)return
        val now=System.nanoTime()/1_000_000;val dt=if(last==0L)16f else (now-last).coerceIn(1,48).toFloat();last=now
        val reduced=a.prefs.str("motion")=="reduced";val g=goalFloat.coerceAtLeast(1f);val gi=goal
        // springs: each milestone to its share of the road
        for(i in 0 until min(f.size,max(gi,60)+2)){val want=(i+1f)/g;val exist=i<gi-1
            if(reduced){f[i]=want;al[i]=if(exist)1f else 0f;continue}
            val acc=(want-f[i])*.0009f-fv[i]*.007f;fv[i]+=acc*dt;f[i]+=fv[i]*dt;al[i]+=((if(exist)1f else 0f)-al[i])*min(1f,dt/140f)}
        wave=max(0f,wave-dt/420f)
        val dense=((g-34f)/14f).coerceIn(0f,1f)
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x0f000000 else 0x12ffffff;p.strokeWidth=16*d;c.drawPath(road,p)
        p.strokeWidth=1.3f*d;p.color=if(a.light)0x38000000 else 0x38ffffff;p.pathEffect=DashPathEffect(floatArrayOf(5*d,7*d),0f);c.drawPath(road,p);p.pathEffect=null
        val walked=(done/g).coerceIn(0f,1f)
        if(walked>0f){val seg=Path();pm.getSegment(0f,length*walked,seg,true);p.shader=LinearGradient(0f,0f,width.toFloat(),0f,green,green2,Shader.TileMode.CLAMP);p.strokeWidth=5*d;c.drawPath(seg,p);p.shader=null}
        p.style=Paint.Style.FILL
        val light=a.light
        for(i in 0 until min(f.size,max(gi,60)+2)){if(al[i]<.01f||f[i]>1.02f)continue;val q=at(f[i]);val read=i<done
            val alpha=al[i]*(if(read)1f else (1-dense*.85f));val r=(if(read)3.4f else 2.4f*(1-dense*.4f))*d*(.5f+.5f*al[i])
            p.color=if(read)(if(light)green else Color.WHITE) else (if(light)0x40000000 else 0x52ffffff);p.alpha=(p.alpha*alpha).toInt();c.drawCircle(q[0],q[1],r,p)}
        p.alpha=255
        if(dense>0f){val step=if(g>200)50 else if(g>100)20 else 10;tp.textSize=10*a.resources.displayMetrics.scaledDensity;tp.textAlign=Paint.Align.CENTER
            var v=step;while(v<g-step*.4f){val q=at(v/g);p.color=if(light)0x59000000 else 0x73ffffff;p.alpha=(p.alpha*dense).toInt();c.drawCircle(q[0],q[1],3*d,p)
                tp.color=a.muted;tp.alpha=(255*dense).toInt();c.drawText("$v",q[0],q[1]+20*d,tp);v+=step};p.alpha=255;tp.alpha=255}
        // you are here
        val h=at(walked);val ring=(now%1600)/1600f
        p.style=Paint.Style.STROKE;p.strokeWidth=1.6f*d;p.color=green;p.alpha=((1-ring)*.8f*255).toInt();c.drawCircle(h[0],h[1],(5+ring*7)*d,p);p.alpha=255;p.style=Paint.Style.FILL
        p.color=if(light)green else Color.WHITE;c.drawCircle(h[0],h[1],4.6f*d,p)
        tp.textSize=11*a.resources.displayMetrics.scaledDensity;tp.textAlign=Paint.Align.CENTER;tp.color=a.fg;tp.typeface=PolkaFonts.sans(600);c.drawText("$done",h[0],h[1]-12*d,tp);tp.typeface=PolkaFonts.sans(500)
        // start ring and the flag
        val s0=at(0f);p.color=if(light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt();c.drawCircle(s0[0],s0[1],5.5f*d,p);p.style=Paint.Style.STROKE;p.strokeWidth=2.8f*d;p.color=green;c.drawCircle(s0[0],s0[1],5.5f*d,p);p.style=Paint.Style.FILL
        val e=at(1f);p.color=a.fg;c.drawRect(e[0]-d,e[1]-28*d,e[0]+d,e[1],p);val wv=(sin(now/60.0)*wave*3*d).toFloat()
        c.drawPath(Path().apply{moveTo(e[0]+d,e[1]-28*d);quadTo(e[0]+9*d,e[1]-27*d+wv,e[0]+17*d,e[1]-23*d+wv*.5f);lineTo(e[0]+d,e[1]-17*d);close()},p)
        if(running&&!reduced)postInvalidateOnAnimation()}
}

/** A ruler that scrolls under a fixed green needle: drag one book at a time (a tick each), or fling to fly through tens. */
internal class GoalRuler(private val a:MainActivity,start:Int):View(a){
    var onChange:(Int)->Unit={};var onValue:(Float)->Unit={}
    private val d=a.resources.displayMetrics.density;private val px get()=9*d
    private var v=start.toFloat();private var shown=start;private var vel=0f;private var last=0L;private var lastX=0f;private var dragging=false;private var tracker:VelocityTracker?=null
    private var fling:ValueAnimator?=null;private var running=true
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(500)}
    init{isClickable=true;contentDescription="Цель: $start книг"}
    fun stop(){running=false;fling?.cancel()}
    private fun moveTo(nv:Float){v=nv.coerceIn(0f,999f);onValue(v);val n=v.roundToInt();if(n!=shown){shown=n;contentDescription="Цель: $n книг";if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);onChange(n)};invalidate()}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);fling?.cancel();dragging=true;lastX=e.x;tracker?.recycle();tracker=VelocityTracker.obtain();tracker?.addMovement(e)}
        MotionEvent.ACTION_MOVE->{tracker?.addMovement(e);moveTo(v-(e.x-lastX)/px);lastX=e.x}
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{dragging=false;tracker?.addMovement(e);tracker?.computeCurrentVelocity(1000);val vx=tracker?.xVelocity?:0f;tracker?.recycle();tracker=null
            // books per second from the finger's speed; it slows down and lands exactly on a whole book
            val speed=-vx/px;val dist=speed*.32f;val target=(v+dist).roundToInt().toFloat().coerceIn(0f,999f);val from=v
            val dur=(220+abs(dist)*6).toLong().coerceAtMost(1400L)
            fling=ValueAnimator.ofFloat(0f,1f).apply{duration=if(a.prefs.str("motion")=="reduced")0 else dur;interpolator=PathInterpolator(.15f,.7f,.25f,1f);addUpdateListener{moveTo(from+(target-from)*(it.animatedValue as Float))};start()}}};return true}
    override fun onDraw(c:Canvas){val cx=width/2f;val y0=6*d
        for(n in floor(v).toInt()-30..ceil(v).toInt()+30){if(n<0)continue;val x=cx+(n-v)*px;val edge=1-min(1f,abs(x-cx)/(width/2f));if(edge<=0f)continue;val maj=n%5==0
            p.color=a.fg;p.alpha=((if(maj).55f else .25f)*edge*255).toInt();c.drawRoundRect(x-d,y0+(if(maj)6 else 12)*d,x+d,y0+(if(maj)30 else 26)*d,d,d,p)
            if(n%10==0){tp.textSize=10.5f*a.resources.displayMetrics.scaledDensity;tp.textAlign=Paint.Align.CENTER;tp.color=a.muted;tp.alpha=(edge*255).toInt();c.drawText("$n",x,y0+48*d,tp)}}
        p.alpha=255;p.color=if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt();p.setShadowLayer(8*d,0f,0f,p.color and 0xb3ffffff.toInt());c.drawRoundRect(cx-2*d,y0,cx+2*d,y0+36*d,2*d,2*d,p);p.clearShadowLayer()}
}
