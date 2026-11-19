package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*




/** Your rating as a small chip in the cover's top-right corner: dark glass, a gold star and the number (like ratings on posters). */
/** Rating medal: a faceted metal star, the same for every score; the material climbs with it —
 *  1 bronze, 2 silver, 3 gold, 4 platinum, 5 diamond. Ten half-facets lit from the upper left, fine light ridges,
 *  a soft sheen, and the score engraved into the centre (no outlines, nothing cartoonish). */
internal class RatingMedal(private val a:MainActivity,private val rating:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    /** deep, mid, light tones of the metal */
    private val tone=when(rating.coerceIn(1,5)){
        1->intArrayOf(0xff4a2510.toInt(),0xff9a5a2e.toInt(),0xffd89a66.toInt())
        2->intArrayOf(0xff3e434a.toInt(),0xff8b929b.toInt(),0xffd3d8de.toInt())
        3->intArrayOf(0xff5a3a08.toInt(),0xffb8862a.toInt(),0xffecc865.toInt())
        4->intArrayOf(0xff3f3c52.toInt(),0xff9a96b4.toInt(),0xffe2def3.toInt())
        else->intArrayOf(0xff0f4a66.toInt(),0xff4fb0d4.toInt(),0xffbdeeff.toInt())}
    init{contentDescription="Оценка $rating из 5";setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(36),a.d(36))
    private fun pt(r:Float,i:Int):PointF{val t=Math.toRadians(-90.0+36*i);return PointF((50+r*Math.cos(t)).toFloat(),(53+r*Math.sin(t)).toFloat())}
    private val tips=(0 until 10).map{pt(if(it%2==1)21f else 48f,it)}
    private val star=Path().apply{tips.forEachIndexed{i,q->if(i==0)moveTo(q.x,q.y) else lineTo(q.x,q.y)};close()}
    private fun shade(d:Float)=if(d<.5f)mix(tone[0],tone[1],d*2) else mix(tone[1],tone[2],(d-.5f)*2)
    override fun onDraw(c:Canvas){val s=minOf(width,height)/100f;c.save();c.translate((width-100*s)/2,(height-100*s)/2);c.scale(s,s)
        val dp=a.d(1)/s
        p.reset();p.isAntiAlias=true;p.color=tone[0];p.setShadowLayer(3f*dp,0f,1.3f*dp,0x73000000);c.drawPath(star,p);p.clearShadowLayer()
        // ten half-facets, each lit by how its slope faces the light (upper left, from the front)
        val lx=-.55f;val ly=-.75f;val lz=.38f;val ln=sqrt(lx*lx+ly*ly+lz*lz)
        for(i in 0 until 10){val q0=tips[i];val q1=tips[(i+1)%10];val mx=(q0.x+q1.x)/2-50;val my=(q0.y+q1.y)/2-53;val m=hypot(mx,my)
            val d=(((mx/m*.62f)*lx+(my/m*.62f)*ly+.78f*lz)/ln+.2f).div(1.1f).coerceIn(0f,1f)
            val f=Path().apply{moveTo(50f,53f);lineTo(q0.x,q0.y);lineTo(q1.x,q1.y);close()}
            p.shader=LinearGradient(50f,53f,mx+50,my+53,shade(d),shade((d*.8f)),Shader.TileMode.CLAMP);c.drawPath(f,p);p.shader=null}
        p.style=Paint.Style.STROKE;p.strokeWidth=.6f;p.color=0x38ffffff
        for(i in 0 until 10 step 2)c.drawLine(50f,53f,tips[i].x,tips[i].y,p)
        p.strokeWidth=.8f;p.color=0x59ffffff;c.drawPath(star,p);p.style=Paint.Style.FILL
        c.save();c.clipPath(star)
        p.shader=RadialGradient(36f,30f,40f,0x47ffffff,0x00ffffff,Shader.TileMode.CLAMP);c.drawRect(0f,0f,100f,100f,p);p.shader=null
        if(rating>=5)for((x,y,r) in listOf(Triple(34f,34f,3.2f),Triple(64f,62f,2.2f))){p.color=0xe6ffffff.toInt()
            c.drawPath(Path().apply{moveTo(x,y-r*2);quadTo(x,y,x+r*2,y);quadTo(x,y,x,y+r*2);quadTo(x,y,x-r*2,y);quadTo(x,y,x,y-r*2);close()},p)}
        c.restore()
        // engraved score: a dark recess with a light lower lip
        p.typeface=PolkaFonts.display();p.textSize=30f;p.textAlign=Paint.Align.CENTER
        val ty=57f-(p.ascent()+p.descent())/2
        p.color=0x73ffffff;c.drawText("$rating",50f,ty+.9f,p);p.color=mix(tone[0],tone[1],.2f);c.drawText("$rating",50f,ty,p)
        c.restore()}
    private fun mix(x:Int,y:Int,f:Float)=Color.rgb((Color.red(x)+(Color.red(y)-Color.red(x))*f).toInt(),(Color.green(x)+(Color.green(y)-Color.green(x))*f).toInt(),(Color.blue(x)+(Color.blue(y)-Color.blue(x))*f).toInt())
}
