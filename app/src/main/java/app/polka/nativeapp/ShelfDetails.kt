package app.polka.nativeapp

import android.graphics.*

/** Recessed washer + metal head + rotated cross. A fresh Paint prevents tint/alpha leaks. */
internal object ShelfScrew {
 val turns=floatArrayOf(18f,-19f,38f,6f)
 fun draw(c:Canvas,x:Float,y:Float,size:Float,density:Float,turn:Float){
  c.save();c.translate(x,y);val r=size/2;val p=Paint(Paint.ANTI_ALIAS_FLAG)
  // CSS outer shadows: recessed white lip over a small soft drop shadow.
  p.color=0xff303538.toInt();p.setShadowLayer(1.1f*density,0f,density,0x80000000.toInt());c.drawCircle(0f,0f,r,p);p.clearShadowLayer();p.color=0x25ffffff;c.drawCircle(0f,.5f*density,r,p)
  // CSS conic-gradient(from 25deg, ...); only the drive rotates, not the lighting.
  p.color=Color.WHITE
  p.shader=SweepGradient(0f,0f,intArrayOf(0xff6b7073.toInt(),0xffd5d9da.toInt(),0xff777c80.toInt(),0xff42474b.toInt(),0xffaeb4b7.toInt(),0xff6b7073.toInt()),floatArrayOf(0f,.18f,.43f,.62f,.83f,1f)).apply{setLocalMatrix(Matrix().apply{setRotate(-65f)})}
  c.drawCircle(0f,0f,r,p);p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=.5f*density;p.color=0xff303538.toInt();c.drawCircle(0f,0f,r-.25f*density,p);p.style=Paint.Style.FILL
  val head=(r-density).coerceAtLeast(r*.4f)
  p.color=Color.WHITE;p.shader=SweepGradient(0f,0f,intArrayOf(0xff8e969b.toInt(),0xffc1c8cc.toInt(),0xff7e878c.toInt(),0xffcbd0d3.toInt(),0xff8e969b.toInt()),floatArrayOf(0f,.28f,.57f,.82f,1f)).apply{setLocalMatrix(Matrix().apply{setRotate(30f)})};c.drawCircle(0f,0f,head,p)
  p.shader=RadialGradient(-r*.36f,-r*.56f,size*.58f,intArrayOf(0xa0ffffff.toInt(),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);c.drawCircle(0f,0f,head,p);p.shader=null
  // A small upper lip and lower recess, both independent of the previous canvas Paint.
  p.style=Paint.Style.STROKE;p.strokeWidth=.6f*density;p.color=0x70ffffff;c.drawArc(-head,-head,head,head,205f,130f,false,p);p.color=0x7030383b;c.drawArc(-head,-head,head,head,25f,130f,false,p);p.style=Paint.Style.FILL
  c.rotate(turn);val half=size*.28f;val stem=half*.22f
  fun cross(dy:Float,color:Int){p.color=color;val path=Path();path.moveTo(-stem,-half+dy);path.lineTo(stem,-half+dy);path.lineTo(stem,-stem+dy);path.lineTo(half,-stem+dy);path.lineTo(half,stem+dy);path.lineTo(stem,stem+dy);path.lineTo(stem,half+dy);path.lineTo(-stem,half+dy);path.lineTo(-stem,stem+dy);path.lineTo(-half,stem+dy);path.lineTo(-half,-stem+dy);path.lineTo(-stem,-stem+dy);path.close();c.drawPath(path,p)}
  cross(.5f*density,0x90ffffff.toInt());cross(0f,0xff3b4246.toInt());c.restore()
 }
}
