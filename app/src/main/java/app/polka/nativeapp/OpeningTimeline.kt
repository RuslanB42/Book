package app.polka.nativeapp

/** A reversible, time-based cover approach → hinge opening → live-page approach. */
object OpeningTimeline {
 /** Detail glow fades out with the initial cover approach, before the cover unfolds. */
 fun light(progress:Float):Float {val t=(progress/.2f).coerceIn(0f,1f);return 1-t*t*(3-2*t)}

 // Two beats, like taking a book from a shelf: it comes to you and settles (GATE, zero velocity), then the cover
 // swings open and the page grows to fill the screen. The opening can wait at GATE for the text without any jolt.
 const val GATE=.36f
 const val DURATION=1000L
 const val CLOSE_DURATION=820L
 data class Pose(val x:Float,val y:Float,val sx:Float,val sy:Float,val rx:Float,val ry:Float)
 data class Frame(val pose:Pose,val swing:Float,val zoom:Float,val volume:Float,val chrome:Float,val shade:Float,val gutter:Float,val light:Float,val innerLight:Float)
 fun bezier(t:Float,x1:Float,y1:Float,x2:Float,y2:Float):Float {
  if(t<=0)return 0f;if(t>=1)return 1f
  fun q(u:Float,a:Float,b:Float):Float {val v=1-u;return 3*v*v*u*a+3*v*u*u*b+u*u*u}
  var lo=0f;var hi=1f;var u=t
  repeat(20){u=(lo+hi)*.5f;if(q(u,x1,x2)<t)lo=u else hi=u};return q(u,y1,y2)
 }
 private fun mix(a:Float,b:Float,t:Float)=a+(b-a)*t
 fun chrome(t:Float):Float {if(t>=1f)return 1f;if(t<=.80f)return 0f;val u=((t-.80f)/.20f).coerceIn(0f,1f);return u*u*u*(10f+u*(-15f+6f*u))}
 fun sample(t0:Float,W:Float,H:Float,w:Float,h:Float,source:Pose,dp:Float=1f):Frame {
  val t=t0.coerceIn(0f,1f)
  fun ease(from:Float,to:Float):Float {val u=((t-from)/(to-from)).coerceIn(0f,1f);return u*u*u*(10f+u*(-15f+6f*u))}
  fun interpolate(a:Pose,b:Pose,u:Float)=Pose(mix(a.x,b.x,u),mix(a.y,b.y,u),mix(a.sx,b.sx,u),mix(a.sy,b.sy,u),mix(a.rx,b.rx,u),mix(a.ry,b.ry,u))
  val scale=maxOf(1.16f,source.sx*1.10f).coerceAtMost(minOf(W*.82f/w,H*.72f/h))
  val cy=maxOf(24*dp,(H-h*scale)*.46f)
  val spread=Pose(maxOf(16*dp,W-w*scale-20*dp),cy,scale,scale,0f,0f)
  val full=Pose(0f,0f,W/w,H/h,0f,0f)
  // Beat 1: responsive start, flat (zero-velocity) arrival.
  val travel=bezier((t/GATE).coerceIn(0f,1f),.3f,.6f,.2f,1f)
  // Beat 2: the cover swings with an even ease-in-out; the page zoom overlaps its second half.
  val opening=ease(GATE,.80f)
  val zoom=ease(.56f,1f)
  val lean=kotlin.math.sin(travel*Math.PI).toFloat()
  val pose=interpolate(interpolate(source,spread,travel),full,zoom).let{Pose(it.x,it.y,it.sx,it.sy,-3f*lean,-6f*lean)}
  val swing=-166f*opening
  val volume=1-ease(.74f,1f)
  val shade=kotlin.math.sin(opening*Math.PI).toFloat()*.30f
  return Frame(pose,swing,zoom,volume,chrome(t),shade,opening*.42f*(1-zoom),opening,opening*.45f*(1-zoom))
 }
}
