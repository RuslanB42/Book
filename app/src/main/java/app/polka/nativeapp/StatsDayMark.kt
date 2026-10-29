package app.polka.nativeapp
import android.graphics.*
import android.view.View
/** Day bubble: solid ink with a check when the goal is met, coral dot for today, quiet disc otherwise. */
internal class StatsDayMark(private val a:MainActivity,private val met:Boolean,private val partial:Boolean,private val today:Boolean):View(a){
 override fun onDraw(c:Canvas){val p=Paint(Paint.ANTI_ALIAS_FLAG);val cx=width/2f;val cy=height/2f;val r=width*.5f
  val ink=if(a.light)0xff161617.toInt()else 0xfff3f2ef.toInt();val onInk=if(a.light)Color.WHITE else 0xff161617.toInt()
  val coral=if(a.light)0xffee6a4b.toInt()else 0xffff8a6b.toInt();val quiet=if(a.light)0xffeeeeec.toInt()else 0xff2c2c2e.toInt()
  p.color=when{met->ink;today->coral;else->quiet};c.drawCircle(cx,cy,r,p)
  if(met){p.color=onInk;p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;val path=Path();path.moveTo(width*.28f,height*.52f);path.lineTo(width*.43f,height*.67f);path.lineTo(width*.73f,height*.35f);c.drawPath(path,p)}
  else if(today){p.color=Color.WHITE;c.drawCircle(cx,cy,width*.12f,p)}
  else if(partial){p.color=ink;p.alpha=140;c.drawCircle(cx,cy,width*.10f,p)}
 }
}
