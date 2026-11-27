package app.polka.nativeapp
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar
import kotlin.math.*

/**
 * The reader's progress slider. SeekBar still owns touch, keyboard and accessibility; the drawing is ours.
 * The knob is a drop of liquid (after the reference the user sent): it follows the value on a quick spring, and its
 * speed pulls it out into a teardrop, round at the front with a tail behind; when it stops, the tail snaps back and
 * the drop wobbles once into a circle.
 */
internal class ReaderRange(private val a:MainActivity):SeekBar(a){
 private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
 var foreground=a.fg
 private val path=Path()
 private val r=a.d(12).toFloat()
 // where the knob is drawn and how fast it goes; how far its tail is drawn out and how fast that changes
 private var x=Float.NaN;private var vx=0f;private var s=0f;private var vs=0f;private var dir=1f
 private var last=0L;private var running=false
 private val reduced get()=a.prefs.str("motion")=="reduced"
 private val frame=object:Runnable{override fun run(){step();if(running)postOnAnimation(this)}}
 init{background=null;setPadding(a.d(12),0,a.d(12),0);thumb=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.WHITE);setSize(a.d(24),a.d(24))};splitTrack=false}
 private fun target():Float{val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();return left+(right-left)*progress/max.coerceAtLeast(1)}
 override fun onDraw(c:Canvas){val y=height/2f;val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val to=target()
  if(x.isNaN()||reduced){x=to;vx=0f;s=0f;vs=0f}else if(abs(to-x)>.5f&&!running){running=true;last=0L;postOnAnimation(frame)}
  val h=a.d(2).toFloat()
  ink.color=Ui.press(a.light);c.drawRoundRect(left,y-h,right,y+h,h,h,ink)
  ink.color=foreground;c.drawRoundRect(left,y-h,x.coerceIn(left,right),y+h,h,h,ink)
  ink.color=Color.WHITE;drop(c,x,y)}
 private fun step(){val now=System.nanoTime();val dt=if(last==0L)1f/60f else ((now-last)/1e9f).coerceIn(.001f,.034f);last=now
  val to=target()
  // the knob: a stiff spring, nearly without overshoot, so it stays under the finger
  val k=900f;vx+=(k*(to-x)-2f*sqrt(k)*.95f*vx)*dt;x+=vx*dt
  // the tail: drawn out by the speed, and on a looser spring of its own, so it wobbles once as the knob stops
  if(abs(vx)>a.d(40))dir=sign(vx)
  val want=(abs(vx)/a.d(1100)).coerceIn(0f,1f)*r*.9f
  val ks=520f;vs+=(ks*(want-s)-2f*sqrt(ks)*.42f*vs)*dt;s+=vs*dt
  if(abs(to-x)<.5f&&abs(vx)<a.d(2)&&abs(s)<.3f&&abs(vs)<a.d(2)){x=to;vx=0f;s=0f;vs=0f;running=false}
  invalidate()}
 /** The drop, drawn heading right and turned to [dir]: a half circle in front, a tail of length [s] behind, a little
  *  flatter the longer it is. Pulled back past round (s < 0) it is a short upright oval. */
 private fun drop(c:Canvas,cx:Float,cy:Float){
  if(s<0f){val rx=r+s*.6f;val ry=r-s*.35f;c.drawOval(cx-rx,cy-ry,cx+rx,cy+ry,ink);return}
  if(s<.2f){c.drawCircle(cx,cy,r,ink);return}
  val t=(s/(r*.9f)).coerceIn(0f,1f);val lead=s*.12f;val ry=r*(1f-.08f*t);val k=.5523f;val tail=r+s;val pinch=ry*k*(1f-.5f*t)
  path.rewind();path.moveTo(0f,-ry)
  path.cubicTo(k*(r+lead),-ry,r+lead,-k*ry,r+lead,0f);path.cubicTo(r+lead,k*ry,k*(r+lead),ry,0f,ry)
  path.cubicTo(-(k*r+.35f*s),ry,-tail,pinch,-tail,0f);path.cubicTo(-tail,-pinch,-(k*r+.35f*s),-ry,0f,-ry);path.close()
  c.save();c.translate(cx,cy);c.scale(dir,1f);c.drawPath(path,ink);c.restore()}
 override fun onDetachedFromWindow(){running=false;removeCallbacks(frame);super.onDetachedFromWindow()}
}
