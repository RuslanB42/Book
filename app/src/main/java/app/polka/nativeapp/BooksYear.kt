package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.*

/** One finished book of the year: when it was first opened, when it was finished, and the time spent on it. */
internal data class YearBook(val book:JSONObject,val start:LocalDate,val end:LocalDate,val ms:Long)

internal fun yearBooks(model:ReadingStatistics,data:JSONObject,year:Int):List<YearBook>{
    val records=data.optJSONObject("books")
    return model.finished.filter{it.date?.year==year}.map{f->val id=f.book.str("id");val r=records?.optJSONObject(id)
        val first=r?.optLong("first")?:0L
        val start=if(first>0)Instant.ofEpochMilli(first).atZone(ZoneId.systemDefault()).toLocalDate() else f.date!!
        YearBook(f.book,if(start.isAfter(f.date!!))f.date else start,f.date,r?.optLong("ms")?:0L)}.sortedBy{it.end}
}

/**
 * The year as a road (approved prototype): it winds from 1 January (green ring) to a flag on 31 December with the
 * goal on it. Every book stands as its cover on the day it was finished; the road under it glows for the days it was
 * read. Books finished close together form a stack with a count; a tap opens the stack as a fan above the road, a
 * thin line running from each cover to its exact day. Past today the road is dashed.
 */
internal class YearRoad(private val a:MainActivity,private val year:Int,private val books:List<YearBook>,private val goal:Int,
                        private val today:LocalDate,private val motion:Boolean,private val onPick:(Int)->Unit):FrameLayout(a){
    var selected=books.lastIndex;private set
    private var fan=-1   // first book of the open stack, -1 when none is open
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
    private val road=Path();private val pm=PathMeasure();private var length=0f
    private val covers=books.map{b->CoverView(a,b.book,a.cover(b.book)).apply{softShadow=true}}
    private val badges=mutableMapOf<Int,TextView>()
    private class Group(val x:Float,val y:Float,val items:MutableList<Int>)
    private var groups=listOf<Group>()
    private val pos=FloatArray(2)
    private val d=a.d(1).toFloat()
    init{setWillNotDraw(false);clipChildren=false;clipToPadding=false;isClickable=true
        covers.forEachIndexed{i,c->addView(c,LayoutParams(a.d(24),a.d(36)));c.isClickable=true;c.contentDescription=books[i].book.str("title");c.setOnClickListener{tap(i)}}}
    override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec(a.d(200),MeasureSpec.EXACTLY))}
    private fun frac(date:LocalDate):Float{if(date.year<year)return 0f;if(date.year>year)return 1f;return (date.dayOfYear-1f)/date.lengthOfYear()}
    private fun at(f:Float):FloatArray{pm.getPosTan(length*f.coerceIn(0f,1f),pos,null);return floatArrayOf(pos[0],pos[1])}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat();val H=h.toFloat()
        fun x(f:Float):Float{return d*10+(W-d*34)*f}
        fun y(f:Float):Float{return d*16+(H-d*30)*f}
        road.reset();road.moveTo(x(0f),y(.97f))
        road.cubicTo(x(.2f),y(1f),x(.2f),y(.58f),x(.41f),y(.58f))
        road.cubicTo(x(.62f),y(.58f),x(.64f),y(.93f),x(.82f),y(.72f))
        road.cubicTo(x(1f),y(.51f),x(1f),y(.22f),x(1f),y(.16f))
        pm.setPath(road,false);length=pm.length
        val list=mutableListOf<Group>()
        books.forEachIndexed{i,b->val q=at(frac(b.end));val g=list.lastOrNull();if(g!=null&&abs(q[0]-g.x)<d*30)g.items.add(i) else list.add(Group(q[0],q[1],mutableListOf(i)))}
        groups=list
        badges.values.forEach{removeView(it)};badges.clear()
        groups.filter{it.items.size>1}.forEach{g->badges[g.items[0]]=a.text("${g.items.size}",10.5f,0xff141416.toInt(),true).apply{gravity=Gravity.CENTER;includeFontPadding=false
            background=android.graphics.drawable.GradientDrawable().apply{setColor(0xfff3f3f5.toInt());cornerRadius=d*9};elevation=d*4;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}.also{addView(it,LayoutParams(a.d(18),a.d(18)))}}
        post{requestLayout()}}
    private fun groupOf(i:Int)=groups.firstOrNull{i in it.items}
    private fun tap(i:Int){val g=groupOf(i)?:return
        if(g.items.size>1&&fan!=g.items[0]){fan=g.items[0];if(selected !in g.items)selected=g.items[0];relayout();onPick(selected);return}
        select(i)}
    /** Show [i]: its stack opens when it belongs to one, any other open stack closes. */
    fun select(i:Int){if(i !in books.indices)return;selected=i;val g=groupOf(i);fan=if(g!=null&&g.items.size>1)g.items[0] else -1;relayout();onPick(i)}
    override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP&&fan>=0){fan=-1;relayout()};return true}
    // ── layout of the covers (animated from where they were) ──
    private val last=HashMap<Int,RectF>()
    private fun relayout(){
        covers.forEachIndexed{i,c->last[i]=RectF(c.left+c.translationX,c.top+c.translationY,c.right+c.translationX,c.bottom+c.translationY)}
        requestLayout();invalidate()}
    private data class Spot(val r:RectF,val rot:Float,val alpha:Float,val z:Float,val visible:Boolean)
    private fun spots():Map<Int,Spot>{val out=HashMap<Int,Spot>();val W=width.toFloat()
        val open=groups.firstOrNull{it.items.size>1&&it.items[0]==fan}
        for(g in groups){val n=g.items.size
            if(g===open){val w=d*30;val h=d*45;val gap=d*10;val total=n*w+(n-1)*gap
                val rows=if(total>W-d*56)2 else 1;val perRow=ceil(n/rows.toFloat()).toInt();val rowTotal=perRow*w+(perRow-1)*gap
                val left=max(d*4,min(W-rowTotal-d*48,g.x-rowTotal/2))
                g.items.forEachIndexed{k,i->val row=k/perRow;val col=k%perRow;val x=left+col*(w+gap);val y=d*2+row*(h+d*18)
                    out[i]=Spot(RectF(x,y,x+w,y+h),0f,1f,if(i==selected)d*6 else d*3,true)}
                continue}
            val has=selected in g.items;val top=if(has)selected else g.items.last()
            val w=d*(if(has)28 else 24);val h=w*1.5f;val lift=if(has)d*6 else 0f
            val r=RectF(g.x-w/2,g.y-d*14-h-lift,g.x+w/2,g.y-d*14-lift)
            val dim=if(open!=null)(.4f) else if(has)1f else .75f
            val rest=g.items.filter{it!=top}
            g.items.forEach{i->when(i){
                top->out[i]=Spot(r,0f,dim,d*4,true)
                rest.getOrNull(0)->out[i]=Spot(RectF(r).apply{offset(-d*4,d*2)},-8f,dim*.85f,d*1,true)
                rest.getOrNull(1)->out[i]=Spot(RectF(r).apply{offset(d*4,d*2)},7f,dim*.85f,d*2,true)
                else->out[i]=Spot(r,0f,0f,0f,false)}}}
        return out}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){if(length<=0f)return
        val s=spots()
        covers.forEachIndexed{i,c->val sp=s[i]?:return@forEachIndexed
            c.layout(sp.r.left.roundToInt(),sp.r.top.roundToInt(),sp.r.right.roundToInt(),sp.r.bottom.roundToInt())
            c.visibility=if(sp.visible)VISIBLE else INVISIBLE;c.translationZ=sp.z
            val was=last.remove(i)
            if(was!=null&&motion&&was.width()>0){c.pivotX=c.width/2f;c.pivotY=c.height.toFloat()
                c.translationX=was.centerX()-sp.r.centerX();c.translationY=was.centerY()-sp.r.centerY();val k=was.width()/sp.r.width()
                c.scaleX=k;c.scaleY=k;val sp2=Spring.of(.18,380.0)
                c.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).rotation(sp.rot).alpha(sp.alpha).setStartDelay(0).setDuration(sp2.duration).setInterpolator(sp2.interpolator).start()}
            else{c.rotation=sp.rot;c.alpha=sp.alpha}}
        badges.forEach{(first,tv)->val g=groups.firstOrNull{it.items[0]==first};val open=fan==first
            val top=s[g?.items?.let{if(selected in it)selected else it.last()}?:return@forEach]?.r?:return@forEach
            tv.visibility=if(open)INVISIBLE else VISIBLE;tv.layout((top.right-d*11).roundToInt(),(top.top-d*7).roundToInt(),(top.right+d*7).roundToInt(),(top.top+d*11).roundToInt());tv.translationZ=d*8}
        invalidate()}
    override fun onDraw(c:Canvas){if(length<=0f)return
        val now=when{year==today.year->frac(today);year<today.year->1f;else->0f}
        p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x0f000000 else 0x12ffffff;p.strokeWidth=d*16;c.drawPath(road,p)
        p.strokeCap=Paint.Cap.BUTT;p.strokeWidth=d*1.4f
        if(now>0f){val past=Path();pm.getSegment(0f,length*now,past,true);p.color=if(a.light)0x29000000 else 0x29ffffff;c.drawPath(past,p)}
        if(now<1f){val future=Path();pm.getSegment(length*now,length,future,true);p.color=if(a.light)0x40000000 else 0x42ffffff;p.pathEffect=DashPathEffect(floatArrayOf(d*5,d*7),0f);c.drawPath(future,p);p.pathEffect=null}
        p.style=Paint.Style.FILL;p.color=if(a.light)0x40000000 else 0x4dffffff
        for(m in 1 until 12){val q=at(m/12f);c.drawCircle(q[0],q[1],d*1.7f,p)}
        // reading spans; the selected one is bright and thick
        p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        val green=if(a.light)0xff1f9d63.toInt() else 0xff2fd38a.toInt();val green2=if(a.light)0xff52c68f.toInt() else 0xff9df5c8.toInt()
        books.forEachIndexed{i,b->val f0=frac(b.start);val f1=frac(b.end);val seg=Path();pm.getSegment(length*f0,max(length*f1,length*f0+d*3),seg,true)
            val on=i==selected;p.shader=LinearGradient(0f,0f,width.toFloat(),0f,green,green2,Shader.TileMode.CLAMP)
            p.alpha=if(on)255 else 140;p.strokeWidth=d*(if(on)6 else 4);c.drawPath(seg,p);p.shader=null;p.alpha=255}
        p.style=Paint.Style.FILL
        // start ring
        val s0=at(0f);p.color=if(a.light)Color.WHITE else 0xff1c1c1f.toInt();c.drawCircle(s0[0],s0[1],d*5.5f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d*3;p.color=green;c.drawCircle(s0[0],s0[1],d*5.5f,p);p.style=Paint.Style.FILL
        // today
        if(now>0f&&now<1f){val q=at(now);p.color=a.fg;c.drawCircle(q[0],q[1],d*4,p)}
        // flag with the goal
        val e=at(1f);p.color=a.fg;c.drawRect(e[0]-d,e[1]-d*30,e[0]+d,e[1],p)
        c.drawPath(Path().apply{moveTo(e[0]+d,e[1]-d*30);lineTo(e[0]+d*18,e[1]-d*25);lineTo(e[0]+d,e[1]-d*19);close()},p)
        if(goal>0){tp.textAlign=Paint.Align.LEFT;tp.textSize=11*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(600);tp.color=a.fg;c.drawText("$goal",e[0]+d*21,e[1]-d*20,tp)}
        // stems of the closed covers; leader lines and dates of the open fan
        val s=spots();val open=groups.firstOrNull{it.items.size>1&&it.items[0]==fan}
        p.strokeCap=Paint.Cap.ROUND
        for(g in groups){if(g===open)continue;p.style=Paint.Style.STROKE;p.strokeWidth=d*2;p.color=if(a.light)0x66000000 else 0x8cffffff.toInt();p.alpha=if(open!=null)90 else 255;c.drawLine(g.x,g.y-d*14,g.x,g.y,p);p.alpha=255}
        if(open!=null)for(i in open.items){val r=s[i]?.r?:continue;val q=at(frac(books[i].end));val on=i==selected
            p.style=Paint.Style.STROKE;p.strokeWidth=d*(if(on)1.6f else 1.2f);p.color=if(a.light)(if(on)0xcc000000.toInt() else 0x4d000000) else (if(on)0xccffffff.toInt() else 0x4dffffff)
            c.drawPath(Path().apply{moveTo(r.centerX(),r.bottom+d*16);cubicTo(r.centerX(),r.bottom+d*44,q[0],q[1]-d*30,q[0],q[1]-d*5)},p)
            p.style=Paint.Style.FILL;p.color=if(on)a.fg else (a.fg and 0xffffff) or 0x99000000.toInt();c.drawCircle(q[0],q[1],d*(if(on)3.6f else 2.6f),p)
            tp.textAlign=Paint.Align.CENTER;tp.textSize=9.5f*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(if(on)600 else 400);tp.color=if(on)a.fg else a.muted
            c.drawText(dayLabel(books[i].end),r.centerX(),r.bottom+d*12,tp)}
        p.style=Paint.Style.FILL}
    private fun dayLabel(dt:LocalDate)="${dt.dayOfMonth} ${MONTHS_SHORT[dt.monthValue-1]}"
    companion object{val MONTHS_SHORT=listOf("янв","фев","мар","апр","мая","июн","июл","авг","сен","окт","ноя","дек")}
}

/** An empty, dashed star: the book has no rating yet. */
internal class EmptyStar(private val a:MainActivity):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(36),a.d(36))
    override fun onDraw(c:Canvas){val s=min(width,height)/100f;c.save();c.translate((width-100*s)/2,(height-100*s)/2);c.scale(s,s)
        val star=Path();for(i in 0 until 10){val r=if(i%2==1)21f else 46f;val t=Math.toRadians(-90.0+36*i);val x=(50+r*cos(t)).toFloat();val y=(53+r*sin(t)).toFloat();if(i==0)star.moveTo(x,y) else star.lineTo(x,y)};star.close()
        p.strokeWidth=3f;p.color=a.muted;p.pathEffect=DashPathEffect(floatArrayOf(7f,6f),0f);p.strokeJoin=Paint.Join.ROUND;c.drawPath(star,p);c.restore()}
}
