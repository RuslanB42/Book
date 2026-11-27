package app.polka.nativeapp

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.PathParser

/**
 * The streak freeze (approved «кубик льда, отражение по диагонали»): a rounded ice cube in light blues with a frozen
 * white snowflake inside; the light is one soft diagonal reflection over its upper-left half that fades toward the
 * middle. An empty place is pale ice with a pale flake that fills from the bottom as the next one is earned.
 */
internal object IceArt {
    /** The flame in a 40×48 box (the same shape as the streak's own flame). */
    val flame:Path by lazy{PathParser.createPathFromPathData("M20 46c-9.5 0-16-6.3-16-15.2 0-8.1 5.9-12.3 8.8-19.2.8 4.2 3.2 6.9 5.9 8.1C19.6 12 23 5.4 29.4 1c-1 7.4 2.9 11.8 5.9 16.2 2.4 3.4 3.9 7.1 3.9 12C39.2 39.4 31.8 46 20 46Z")}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    /** The snowflake (approved): six arms with a V on each, all in one path so it is stroked once. */
    fun flake(cx:Float,cy:Float,r:Float):Path{val path=Path()
        for(k in 0 until 6){val a=Math.toRadians(k*60.0-90.0);val ex=cx+r*Math.cos(a).toFloat();val ey=cy+r*Math.sin(a).toFloat();path.moveTo(cx,cy);path.lineTo(ex,ey)
            val bx=cx+r*.58f*Math.cos(a).toFloat();val by=cy+r*.58f*Math.sin(a).toFloat();val l=r*.36f
            for(side in intArrayOf(-1,1)){val b=a+side*.75;path.moveTo(bx,by);path.lineTo(bx+l*Math.cos(b).toFloat(),by+l*Math.sin(b).toFloat())}}
        return path}
    private val flake26 by lazy{flake(13f,13f,6.2f)}
    private fun strokeFlake(c:Canvas,color:Int,width:Float){p.shader=null;p.pathEffect=null;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.strokeWidth=width;p.color=color;c.drawPath(flake26,p);p.style=Paint.Style.FILL}
    /** The cube in [box] (drawn in a 26-unit square inside it). */
    fun cube(c:Canvas,box:RectF){val s=box.width()/26f;c.save();c.translate(box.left,box.top);c.scale(s,s)
        val r=RectF(1.5f,1.5f,24.5f,24.5f)
        p.style=Paint.Style.FILL;p.shader=LinearGradient(0f,0f,26f,26f,intArrayOf(0xffd8f0ff.toInt(),0xff8fd0fa.toInt(),0xff3f93d6.toInt()),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        c.drawRoundRect(r,7f,7f,p)
        // the reflection: the upper-left half a little lighter, fading to the middle, cut off along the diagonal
        c.save();c.clipPath(Path().apply{addRoundRect(r,7f,7f,Path.Direction.CW)})
        p.shader=LinearGradient(1.5f,1.5f,24.5f,24.5f,intArrayOf(0x8cffffff.toInt(),0x1fffffff,0x00ffffff,0x00ffffff),floatArrayOf(0f,.5f,.5f,1f),Shader.TileMode.CLAMP)
        c.drawRect(r,p);c.restore();p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x66ffffff;c.drawRoundRect(r,7f,7f,p)
        strokeFlake(c,Color.WHITE,1.6f)
        c.restore()}
    /** An empty place for a freeze: pale ice with a pale flake. As the next one is earned ([progress] 0..1) the ice
     *  rises from the bottom, and the flake turns white where it has been reached. */
    fun slot(c:Canvas,box:RectF,progress:Float,light:Boolean){val s=box.width()/26f;c.save();c.translate(box.left,box.top);c.scale(s,s)
        val r=RectF(2f,2f,24f,24f)
        p.shader=null;p.pathEffect=null;p.style=Paint.Style.FILL;p.color=if(light)0x292b7fc0 else 0x295ab4f0;c.drawRoundRect(r,7f,7f,p)
        strokeFlake(c,if(light)0x992b7fc0.toInt() else 0x8c8fd0fa.toInt(),1.5f)
        val f=progress.coerceIn(0f,1f)
        if(f>0f){c.save();c.clipRect(0f,24f-22f*f,26f,26f)
            p.shader=LinearGradient(0f,0f,26f,26f,intArrayOf(0xffcfeaff.toInt(),0xff7cc4f4.toInt(),0xff3a8ad0.toInt()),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(r,7f,7f,p);p.shader=null
            strokeFlake(c,Color.WHITE,1.6f);c.restore()}
        c.restore()}
}

/** One place for a freeze on the streak card: a cube when it is there, the empty outline when not. */
internal class FreezeCell(private val a:MainActivity,private val full:Boolean,private val progress:Float):View(a){
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val s=minOf(width,height).toFloat();val box=RectF((width-s)/2,(height-s)/2,(width+s)/2,(height+s)/2)
        if(full)IceArt.cube(c,box) else IceArt.slot(c,box,progress,a.light)}
}

/** The freezes as they stand: two places side by side. */
internal fun MainActivity.freezeRow(ledger:ReadingStatistics.Ledger,size:Int):LinearLayout{
    val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    for(i in 0 until 2){val full=i<ledger.freezes;val prog=if(!full&&i==ledger.freezes&&ledger.toNext in 1..6)(7-ledger.toNext)/7f else 0f
        row.addView(FreezeCell(this,full,prog),LinearLayout.LayoutParams(d(size),d(size)).apply{if(i>0)marginStart=d(6)})}
    return row
}

/** «Заморозки»: the two places, what freezes do in one line, and three short facts — how one is earned, how many are
 *  kept, when the next one comes. */
internal fun MainActivity.freezeSheet(ledger:ReadingStatistics.Ledger){
    val box=column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    box.addView(freezeRow(ledger,52),LinearLayout.LayoutParams(-2,-2).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=d(6)})
    box.addView(text("Заморозки",Type.H2,fg,true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(16)})
    box.addView(text("Сохраняют серию, если день пропущен. Тратятся сами.",Type.TEXT,muted).apply{gravity=Gravity.CENTER;cssLeading(1.5f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(8);leftMargin=d(10);rightMargin=d(10)})
    // a flat card (no shadow to be cut off by the sheet) with plain grey line icons, like every other list
    val card=column().apply{background=shape(Ui.fill(light),20);setPadding(d(16),d(2),d(16),d(2))}
    val ink=Ui.ink2(light)
    fun lineIcon(draw:(Canvas,Paint)->Unit)=object:View(this){override fun onDraw(c:Canvas){val s=width/24f;c.save();c.scale(s,s)
        draw(c,Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=1.7f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=ink});c.restore()}}
    fun fact(icon:View,label:String,value:String?){if(card.childCount>0)card.addView(View(this).apply{setBackgroundColor(Ui.line(light))},LinearLayout.LayoutParams(-1,1))
        val r=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(48)}
        r.addView(icon,LinearLayout.LayoutParams(d(20),d(20)).apply{marginEnd=d(14)})
        r.addView(text(label,Type.TEXT),LinearLayout.LayoutParams(0,-2,1f))
        value?.let{r.addView(text(it,Type.TEXT,muted))}
        card.addView(r,LinearLayout.LayoutParams(-1,-2))}
    // a week: seven days in a row
    fact(lineIcon{c,p->c.drawRoundRect(3.5f,5f,20.5f,20f,3f,3f,p);c.drawLine(3.5f,9.5f,20.5f,9.5f,p);c.drawLine(8f,3.5f,8f,6.5f,p);c.drawLine(16f,3.5f,16f,6.5f,p)
        p.style=Paint.Style.FILL;for(i in 0..2)c.drawCircle(8f+i*4f,14.5f,1f,p)},"Новая за каждые 7 дней подряд",null)
    // a snowflake: three crossing strokes with small branches
    fact(lineIcon{c,p->c.drawPath(IceArt.flake(12f,12f,8.6f),p)},"Можно хранить две",null)
    fact(lineIcon{c,p->c.drawCircle(12f,12f,8.5f,p);c.drawLine(12f,7.5f,12f,12f,p);c.drawLine(12f,12f,15f,14f,p)},"Следующая",if(ledger.freezes>=2)"обе на месте" else "через ${daysWord(ledger.toNext)}")
    box.addView(card,LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(18)})
    dialog().setView(box).show()
}
