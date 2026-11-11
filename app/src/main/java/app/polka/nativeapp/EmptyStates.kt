package app.polka.nativeapp

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.graphics.PathParser

/** Approved empty-state drawings: one 64-unit grid, 2-unit round strokes in a quiet grey, one detail in colour
 *  (stroked and softly filled), inside a soft disc with a faint glow of the same colour behind it. */
internal class EmptyArt(private val a:MainActivity,private val kind:String,private val discDp:Int):View(a){
    private class Part(val d:String,val accent:Boolean=false,val fill:Int=0,val dashed:Boolean=false,val width:Float=2f)
    private val accent=when(kind){"books","bookmark"->0xfff9601f.toInt();"search"->0xff4cb0e5.toInt();"shelf"->0xff9d98f2.toInt();"open"->0xff37c080.toInt();else->0xfff0a742.toInt()}
    private val parts:List<Part> = when(kind){
        "books"->listOf(Part("M8 50h48"),Part("M14 22h6a2 2 0 0 1 2 2v26h-10V24a2 2 0 0 1 2-2z"),Part("M44 22h6a2 2 0 0 1 2 2v26h-10V24a2 2 0 0 1 2-2z"),Part("M15.5 28h3M45.5 28h3"),
            Part("M29 16h6a2 2 0 0 1 2 2v32h-10V18a2 2 0 0 1 2-2z",true,0x2e),Part("M30.5 22h3",true))
        "search"->listOf(Part("M16 10h20a4 4 0 0 1 4 4v28a4 4 0 0 1-4 4H16a4 4 0 0 1-4-4V14a4 4 0 0 1 4-4z"),Part("M18 19h16M18 26h16M18 33h9"),
            Part("M31 40a9 9 0 1 0 18 0a9 9 0 1 0-18 0z",true,0x2e),Part("M46.5 46.5l7 7",true))
        "shelf"->listOf(Part("M7 50h50"),Part("M14 20h6a2.5 2.5 0 0 1 2.5 2.5V50h-11V22.5A2.5 2.5 0 0 1 14 20z",dashed=true),Part("M44 20h6a2.5 2.5 0 0 1 2.5 2.5V50h-11V22.5A2.5 2.5 0 0 1 44 20z",dashed=true),
            Part("M29 20h6a2.5 2.5 0 0 1 2.5 2.5V50h-11V22.5A2.5 2.5 0 0 1 29 20z",true,0x2e),Part("M32 31.5v7M28.5 35h7",true))
        "open"->listOf(Part("M32 18c-6-4-13-5-21-4v26c8-1 15 0 21 4 6-4 13-5 21-4V14c-8-1-15 0-21 4z"),Part("M32 18v26"),
            Part("M16 22c4-.5 8 0 11 1.5M16 29c4-.5 8 0 11 1.5M37 23.5c3-1.5 7-2 11-1.5M37 30.5c3-1.5 7-2 11-1.5"),Part("M14 53h36"),
            Part("M14 53h13",true,width=3.2f),Part("M24 53a3 3 0 1 0 6 0a3 3 0 1 0-6 0z",true,0xff))
        "quote"->listOf(Part("M14 14h36a6 6 0 0 1 6 6v18a6 6 0 0 1-6 6H28l-9 7v-7h-5a6 6 0 0 1-6-6V20a6 6 0 0 1 6-6z"),
            Part("M21.4 31a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0zM33.4 31a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0-7.2 0z",true,0xff),Part("M21.4 31c0-5 1.6-8.2 5.4-10M33.4 31c0-5 1.6-8.2 5.4-10",true))
        "note"->listOf(Part("M17 8h22a4 4 0 0 1 4 4v32a4 4 0 0 1-4 4H17a4 4 0 0 1-4-4V12a4 4 0 0 1 4-4z"),Part("M19 17h18M19 24h18M19 31h10"),
            Part("M35 50l15-15a3 3 0 0 1 4.2 0l.8.8a3 3 0 0 1 0 4.2L40 55h-5z",true,0x2e))
        else->listOf(Part("M14 8h22l14 14v32a4 4 0 0 1-4 4H18a4 4 0 0 1-4-4V12a4 4 0 0 1 4-4z"),Part("M20 30h24M20 37h24M20 44h14"),
            Part("M36 8v10a4 4 0 0 0 4 4h10z",true,0x2e))
    }
    private val paths=parts.map{PathParser.createPathFromPathData(it.d)}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val dash=DashPathEffect(floatArrayOf(2.5f,3.6f),0f)
    /** The glow reaches beyond the disc, so the view is larger than the disc itself. */
    val pad=a.d(26)
    override fun onMeasure(w:Int,h:Int){val s=a.d(discDp)+pad*2;setMeasuredDimension(s,s)}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val r=a.d(discDp)/2f;val light=a.light
        // the glow of the accent and the shade under the disc: baked once with noise, so the falloff has no rings
        c.drawBitmap(back(light,r),0f,0f,p)
        p.style=Paint.Style.FILL;p.pathEffect=null
        p.shader=RadialGradient(cx,cy-r*.4f,r*1.4f,if(light)intArrayOf(0xffffffff.toInt(),0xffefefed.toInt()) else intArrayOf(0xff26262a.toInt(),0xff1d1d20.toInt()),null,Shader.TileMode.CLAMP)
        c.drawCircle(cx,cy,r,p);p.shader=null
        // the drawing, 60% of the disc
        val v=r*2*.6f;c.save();c.translate(cx-v/2,cy-v/2);c.scale(v/64f,v/64f)
        val grey=if(light)0xff9a9aa0.toInt() else 0xff8e8e94.toInt()
        parts.forEachIndexed{i,part->val col=if(part.accent)accent else grey
            if(part.fill!=0){p.style=Paint.Style.FILL;p.pathEffect=null;p.color=withA(col,part.fill);c.drawPath(paths[i],p)}
            if(part.fill!=0xff){p.style=Paint.Style.STROKE;p.strokeWidth=part.width;p.color=col;p.pathEffect=if(part.dashed)dash else null;c.drawPath(paths[i],p)}
            else{p.style=Paint.Style.STROKE;p.strokeWidth=part.width;p.color=col;p.pathEffect=null;c.drawPath(paths[i],p)}}
        c.restore()
    }
    private fun withA(c:Int,alpha:Int)=(c and 0xffffff) or (alpha shl 24)
    private fun back(light:Boolean,r:Float):Bitmap=cache.getOrPut("$kind:$width:$light"){val cx=width/2f;val cy=height/2f;val out=cx
        fun fall(dist:Float,from:Float,to:Float):Float{val t=((dist-from)/(to-from)).coerceIn(0f,1f);val k=1-t;return k*k*(3-2*k)*k}
        val glow=SoftShade.bake(width,height,accent,if(light).07f else .10f){x,y->fall(kotlin.math.hypot(x-cx,y-cy),r*.5f,out)}
        val sy=cy+a.d(6);val shade=SoftShade.bake(width,height,0,if(light).10f else .30f){x,y->fall(kotlin.math.hypot(x-cx,y-sy),r*.82f,r+a.d(16))}
        Canvas(glow).drawBitmap(shade,0f,0f,null);glow}
    private companion object{val cache=HashMap<String,Bitmap>()}
}

/** The road of the year in miniature, for the empty goal card: no disc, just the road with its start and flag. */
internal class EmptyRoad(private val a:MainActivity):View(a){
    private val road=PathParser.createPathFromPathData("M22 82c10-20 40-10 34-30s20-26 28-30")
    private val flag=PathParser.createPathFromPathData("M85 8h11l-4 4 4 4H85z")
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    override fun onMeasure(w:Int,h:Int){val s=a.d(60);setMeasuredDimension(s,s)}
    override fun onDraw(c:Canvas){val light=a.light;c.save();c.scale(width/106f,height/106f)
        p.style=Paint.Style.STROKE;p.pathEffect=null;p.strokeWidth=7f;p.color=if(light)0xffe4e4e1.toInt() else 0xff3a3a3e.toInt();c.drawPath(road,p)
        p.strokeWidth=1.6f;p.color=if(light)0xffb4b4af.toInt() else 0xff5a5a60.toInt();p.pathEffect=DashPathEffect(floatArrayOf(4f,5f),0f);c.drawPath(road,p);p.pathEffect=null
        p.style=Paint.Style.FILL;p.color=0xff37c080.toInt();c.drawCircle(22f,82f,5f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=2f;p.color=a.fg;c.drawLine(84f,22f,84f,8f,p)
        p.style=Paint.Style.FILL;p.color=0xff37c080.toInt();c.drawPath(flag,p);c.restore()}
}

/** One empty state for every screen: the drawing, a serif title on one line, a short muted hint and, only where
 *  there is an obvious action, one button. [disc] is 112 on pages and 100 inside sheets. */
internal fun MainActivity.emptyView(kind:String,title:String,hint:String,disc:Int=112,action:String?=null,onAction:(()->Unit)?=null):LinearLayout{
    val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;clipChildren=false;clipToPadding=false}
    val art=EmptyArt(this,kind,disc)
    box.addView(art,LinearLayout.LayoutParams(-2,-2).apply{topMargin=-art.pad;bottomMargin=-art.pad})
    box.addView(text(title,22f,fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=1;letterSpacing=-.005f},LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(22)})
    box.addView(text(hint,13.5f,muted).apply{gravity=Gravity.CENTER;cssLeading(1.5f);maxWidth=d(236);breakStrategy=android.text.Layout.BREAK_STRATEGY_BALANCED},LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(8)})
    if(action!=null&&onAction!=null)box.addView(text(action,13.5f,bg,true).apply{gravity=Gravity.CENTER;setPadding(d(20),0,d(20),0);background=shape(fg,20);setOnClickListener{onAction()};press(this)},LinearLayout.LayoutParams(-2,d(44)).apply{topMargin=d(22)})
    return box
}

/** The goal card without books: the small road beside a short title and hint. */
internal fun MainActivity.emptyGoal(year:Int):LinearLayout{
    val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    row.addView(EmptyRoad(this),LinearLayout.LayoutParams(d(60),d(60)))
    val col=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
    col.addView(text("Пока ни одной книги за $year",15f,fg,true))
    col.addView(text("Дочитанные появятся на дороге года.",13.5f,muted).apply{cssLeading(1.5f)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(2)})
    row.addView(col,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=d(12)})
    return row
}
