package app.polka.nativeapp

/** A reversible, time-based cover approach → hinge opening → live-page approach. */
object OpeningTimeline {
 /** Detail glow fades out with the initial cover approach, before the cover unfolds. */
 fun light(progress:Float):Float {val t=(progress/.28f).coerceIn(0f,1f);return 1-t*t*(3-2*t)}

 const val DURATION=1320L
 const val CLOSE_DURATION=1160L
 data class Pose(val x:Float,val y:Float,val sx:Float,val sy:Float,val rx:Float,val ry:Float)
 data class Frame(val pose:Pose,val swing:Float,val zoom:Float,val volume:Float,val chrome:Float,val shade:Float,val gutter:Float,val light:Float,val innerLight:Float)
 fun bezier(t:Float,x1:Float,y1:Float,x2:Float,y2:Float):Float {
  if(t<=0)return 0f;if(t>=1)return 1f
  fun q(u:Float,a:Float,b:Float):Float {val v=1-u;return 3*v*v*u*a+3*v*u*u*b+u*u*u}
  var lo=0f;var hi=1f;var u=t
  repeat(20){u=(lo+hi)*.5f;if(q(u,x1,x2)<t)lo=u else hi=u};return q(u,y1,y2)
 }
 private fun mix(a:Float,b:Float,t:Float)=a+(b-a)*t
 fun chrome(t:Float):Float {if(t>=1f)return 1f;if(t<=.86f)return 0f;val u=((t-.86f)/.14f).coerceIn(0f,1f);return u*u*u*(10f+u*(-15f+6f*u))}
 fun sample(t0:Float,W:Float,H:Float,w:Float,h:Float,source:Pose,dp:Float=1f):Frame {
  val t=t0.coerceIn(0f,1f)
  fun ease(from:Float,to:Float):Float {val u=((t-from)/(to-from)).coerceIn(0f,1f);return u*u*u*(10f+u*(-15f+6f*u))}
  fun interpolate(a:Pose,b:Pose,u:Float)=Pose(mix(a.x,b.x,u),mix(a.y,b.y,u),mix(a.sx,b.sx,u),mix(a.sy,b.sy,u),mix(a.rx,b.rx,u),mix(a.ry,b.ry,u))
  // Approach with the cover still closed. No pause or secondary bounce at a phase boundary.
  val scale=maxOf(1.16f,source.sx*1.10f).coerceAtMost(minOf(W*.82f/w,H*.72f/h))
  val cy=maxOf(24*dp,(H-h*scale)*.46f)
  val lifted=Pose((W-w*scale)/2,cy,scale,scale,-4f,-8f)
  val spread=Pose(maxOf(16*dp,W-w*scale-20*dp),cy,scale,scale,0f,0f)
  val zoom=ease(.63f,1f)
  val pose=when {t<=.30f->interpolate(source,lifted,ease(0f,.30f));t<=.63f->interpolate(lifted,spread,ease(.30f,.63f));else->interpolate(spread,Pose(0f,0f,W/w,H/h,0f,0f),zoom)}
  val swing=-166f*ease(.30f,.63f)
  val volume=1-ease(.70f,1f)
  val opening=ease(.30f,.63f)
  val shade=kotlin.math.sin(opening*Math.PI).toFloat()*.30f
  return Frame(pose,swing,zoom,volume,chrome(t),shade,opening*.42f*(1-zoom),opening,opening*.45f*(1-zoom))
 }
}
