package app.polka.nativeapp
import android.graphics.*
import android.view.View
/** Day bubble: amber with a check when the goal is met, a ring around today, a quiet disc otherwise, a dot for partial reading. */
internal class StatsDayMark(private val a:MainActivity,private val met:Boolean,private val partial:Boolean,private val today:Boolean):View(a){
 override fun onDraw(c:Canvas){val p=Paint(Paint.ANTI_ALIAS_FLAG);val cx=width/2f;val cy=height/2f;val outer=width/2f;val ring=a.d(2).toFloat()
  val amber=a.accentFill;val quiet=if(a.light)0xffeeeeec.toInt()else 0xff2f2f31.toInt()
  if(today){p.style=Paint.Style.STROKE;p.strokeWidth=ring;p.color=if(a.light)0xffb9b9be.toInt()else 0xff9a9a9f.toInt();c.drawCircle(cx,cy,outer-ring/2f,p);p.style=Paint.Style.FILL}
  val inner=if(today)outer-a.d(4) else outer
  p.color=if(met)amber else quiet;c.drawCircle(cx,cy,inner,p)
  if(met){p.color=if(a.light)Color.WHITE else 0xff2a1a05.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;val path=Path();path.moveTo(width*.30f,height*.52f);path.lineTo(width*.44f,height*.66f);path.lineTo(width*.71f,height*.36f);c.drawPath(path,p)}
  else if(partial){p.color=amber;c.drawCircle(cx,cy,width*.09f,p)}
 }
}
