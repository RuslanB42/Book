package app.polka.nativeapp

import android.animation.TimeInterpolator
import kotlin.math.*

/**
 * Motion toolkit ported from anime.js v4 (MIT, Julian Garnier) to native Android:
 * the same easing family, the same physical spring solver, and the same stagger maths —
 * so animations here move the way anime.js animations do on the web.
 */
internal object Ease {
    private fun power(p:Float)={t:Float->t.toDouble().pow(p.toDouble()).toFloat()}
    private val sine={t:Float->1f-cos(t*PI.toFloat()/2f)}
    private val circ={t:Float->1f-sqrt(1f-t*t)}
    private val expo={t:Float->if(t==0f)0f else 2.0.pow(10.0*t-10.0).toFloat()}
    private val bounce={t0:Float->var t=t0;var b=4;var pow2:Double
        while(true){b--;pow2=2.0.pow(b.toDouble());if(t>=(pow2-1)/11)break}
        (1/4.0.pow(3.0-b)-7.5625*((pow2*3-2)/22-t).pow(2)).toFloat()}
    private fun back(overshoot:Float=1.7f)={t:Float->(overshoot+1)*t*t*t-overshoot*t*t}
    private fun elastic(amplitude:Float=1f,period:Float=.3f):(Float)->Float{val a=amplitude.coerceIn(1f,10f);val p=period.coerceIn(1e-4f,2f)
        val s=(p/(2*PI.toFloat()))*asin(1f/a);val e=2*PI.toFloat()/p
        return {t->if(t==0f||t==1f)t else -a*2.0.pow(-10.0*(1-t)).toFloat()*sin(((1-t)-s)*e)}}
    // easeTypes of anime.js
    private fun inn(f:(Float)->Float)=TimeInterpolator{t->f(t)}
    private fun out(f:(Float)->Float)=TimeInterpolator{t->1-f(1-t)}
    private fun inOut(f:(Float)->Float)=TimeInterpolator{t->if(t<.5f)f(t*2)/2 else 1-f(t*-2+2)/2}
    private fun outIn(f:(Float)->Float)=TimeInterpolator{t->if(t<.5f)(1-f(1-t*2))/2 else (f(t*2-1)+1)/2}

    val linear=TimeInterpolator{it}
    val inQuad=inn(power(2f));val outQuad=out(power(2f));val inOutQuad=inOut(power(2f))
    val inCubic=inn(power(3f));val outCubic=out(power(3f));val inOutCubic=inOut(power(3f))
    val inQuart=inn(power(4f));val outQuart=out(power(4f));val inOutQuart=inOut(power(4f))
    val inQuint=inn(power(5f));val outQuint=out(power(5f));val inOutQuint=inOut(power(5f))
    val inSine=inn(sine);val outSine=out(sine);val inOutSine=inOut(sine)
    val inCirc=inn(circ);val outCirc=out(circ);val inOutCirc=inOut(circ)
    val inExpo=inn(expo);val outExpo=out(expo);val inOutExpo=inOut(expo);val outInExpo=outIn(expo)
    val inBounce=inn(bounce);val outBounce=out(bounce);val inOutBounce=inOut(bounce)
    fun inBack(o:Float=1.7f)=inn(back(o));fun outBack(o:Float=1.7f)=out(back(o));fun inOutBack(o:Float=1.7f)=inOut(back(o))
    fun inElastic(a:Float=1f,p:Float=.3f)=inn(elastic(a,p));fun outElastic(a:Float=1f,p:Float=.3f)=out(elastic(a,p));fun inOutElastic(a:Float=1f,p:Float=.3f)=inOut(elastic(a,p))
    /** anime.js `out(power)` / `inOut(power)` with a custom exponent (default 1.68). */
    fun out(p:Float=1.68f)=out(power(p));fun inOut(p:Float=1.68f)=inOut(power(p))
}

/**
 * anime.js `createSpring`: a damped harmonic oscillator. Either physical parameters (mass, stiffness, damping,
 * velocity) or the perceptual pair (bounce −1…1, duration ms — Apple's SwiftUI model). [interpolator] maps the
 * animation's 0…1 to the spring's travel; [duration] is how long it takes to settle — use it as the animator duration.
 */
internal class Spring private constructor(private val m:Double,private val s:Double,private val d:Double,private val v:Double){
    private val w0:Double=sqrt(s/m).coerceIn(1e-8,1000.0)
    private val zeta=d/(2*sqrt(s*m))
    private val wd:Double;private val b:Double
    private var solverDuration=0.0
    /** Settling time in ms. */
    val duration:Long
    init{
        if(zeta<1){wd=w0*sqrt(1-zeta*zeta);b=(zeta*w0-v)/wd}
        else if(zeta==1.0){wd=0.0;b=-v+w0}
        else{wd=w0*sqrt(zeta*zeta-1);b=(zeta*w0-v)/wd}
        // same settling search as anime.js: step .02 s until the motion rests within .0005 for 200 ms
        val step=.02;val maxRest=(200/step/1000).toInt();var t=0.0;var rest=0;var it=0
        while(rest<=maxRest&&it<=60000/step/1000){if(abs(1-solve(t))<.0005)rest++ else rest=0;solverDuration=t;t+=step;it++}
        duration=(solverDuration*1000).roundToLong().coerceAtLeast(16)
    }
    private fun solve(time:Double):Double{val t=when{
        zeta<1->exp(-time*zeta*w0)*(cos(wd*time)+b*sin(wd*time))
        zeta==1.0->(1+b*time)*exp(-time*w0)
        else->((1+b)*exp((-zeta*w0+wd)*time)+(1-b)*exp((-zeta*w0-wd)*time))/2};return 1-t}
    val interpolator=TimeInterpolator{t->if(t==0f||t==1f)t else solve(t*solverDuration).toFloat()}
    companion object{
        fun physical(mass:Double=1.0,stiffness:Double=100.0,damping:Double=10.0,velocity:Double=0.0)=Spring(mass.coerceAtLeast(1.0),stiffness.coerceAtLeast(1e-8),damping.coerceAtLeast(1e-8),velocity)
        /** Perceptual spring: [bounce] 0 = no overshoot, .5 = lively; [perceivedMs] ≈ how long it feels. */
        fun of(bounce:Double=.5,perceivedMs:Double=628.0):Spring{val bn=bounce.coerceIn(-1.0,1.0);val pds=perceivedMs.coerceAtLeast(10.0)/1000
            val stiffness=(2*PI/pds).pow(2);val damping=if(bn>=0)((1-bn)*4*PI)/pds else (4*PI)/(pds*(1+bn))
            return Spring(1.0,stiffness,damping.coerceAtMost(300.0),0.0)}
        // a few house springs
        val gentle by lazy{of(.12,520.0)}
        val lively by lazy{of(.32,480.0)}
        val snappy by lazy{of(.18,340.0)}
    }
}

/** anime.js `stagger`: a delay per element, measured from the first / center / last element or a grid position. */
internal object Stagger{
    sealed class From{object First:From();object Center:From();object Last:From();data class Index(val i:Int):From()}
    /**
     * Delay (ms) for each of [total] elements. With [cols] the elements form a grid and the delay follows the
     * distance from the origin cell (a radial wave). [ease] shapes how the delay grows with distance.
     */
    fun delays(total:Int,each:Long,from:From=From.First,cols:Int=0,start:Long=0,ease:TimeInterpolator?=null):LongArray{
        if(total<=0)return LongArray(0)
        val origin=when(from){From.First->0f;From.Center->(total-1)/2f;From.Last->(total-1).toFloat();is From.Index->from.i.toFloat()}
        val dist=FloatArray(total){i->
            if(cols>0){val rows=ceil(total/cols.toFloat());val ox=if(from==From.Center)(cols-1)/2f else origin%cols;val oy=if(from==From.Center)(rows-1)/2f else floor(origin/cols)
                hypot(i%cols-ox,floor(i/cols.toFloat())-oy)}
            else abs(origin-i)}
        val max=dist.maxOrNull()?.takeIf{it>0f}?:1f
        return LongArray(total){i->val n=dist[i]/max;val e=ease?.getInterpolation(n)?:n;start+(e*max*each).roundToLong()}
    }
}
