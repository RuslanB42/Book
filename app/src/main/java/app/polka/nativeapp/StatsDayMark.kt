package app.polka.nativeapp
import android.graphics.*
import android.view.View
internal class StatsDayMark(private val a:MainActivity,private val met:Boolean,private val partial:Boolean,private val today:Boolean):View(a){
 override fun onDraw(c:Canvas){val p=Paint(Paint.ANTI_ALIAS_FLAG);val cx=width/2f;val cy=height/2f;val r=width*.43f;p.color=if(met)0xff26180f.toInt()else 0x65ffffff;c.drawCircle(cx,cy,r,p)
  if(today){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(1).toFloat();p.color=0xff26180f.toInt();c.drawCircle(cx,cy,width*.49f,p);p.style=Paint.Style.FILL}
  if(met){p.color=Color.WHITE;p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;val path=Path();path.moveTo(width*.30f,height*.50f);path.lineTo(width*.44f,height*.64f);path.lineTo(width*.71f,height*.36f);c.drawPath(path,p)}else if(partial){p.color=0xff26180f.toInt();c.drawCircle(cx,cy,width*.10f,p)}
 }
}
