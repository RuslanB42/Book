package app.polka.nativeapp

import android.animation.TimeInterpolator
import kotlin.math.*

/**
 * The spring solver and stagger maths of anime.js v4 (MIT, Julian Garnier). The app's curves are only
 * [MotionSpec]'s three and its springs only [Motion.expand] and [Motion.response].
 */
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
        /** Perceptual spring: [bounce] 0 = no overshoot, .5 = lively; [perceivedMs] ≈ how long it feels. */
        fun of(bounce:Double=.5,perceivedMs:Double=628.0):Spring{val bn=bounce.coerceIn(-1.0,1.0);val pds=perceivedMs.coerceAtLeast(10.0)/1000
            val stiffness=(2*PI/pds).pow(2);val damping=if(bn>=0)((1-bn)*4*PI)/pds else (4*PI)/(pds*(1+bn))
            return Spring(1.0,stiffness,damping.coerceAtMost(300.0),0.0)}
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
