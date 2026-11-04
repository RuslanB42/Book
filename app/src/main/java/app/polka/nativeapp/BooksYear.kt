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
 * The goal as a road (approved prototype): from the start ring to the flag there is one milestone per book of the
 * goal. Finished books stand as covers on their milestones in the order they were finished; the way walked so far
 * glows green, the next milestone breathes, the rest is dashed. With a very large goal only the latest covers stand
 * on the road and the earlier books are bright milestones (tap them too).
 */
internal class YearRoad(private val a:MainActivity,private val books:List<YearBook>,private val goal:Int,private val motion:Boolean,private val onPick:(Int,Int)->Unit):FrameLayout(a){
    var selected=books.lastIndex;private set
    val current get()=books[selected].book
    private val slots=if(goal>0)max(goal,books.size) else books.size+3
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG)
    private val road=Path();private val pm=PathMeasure();private var length=0f
    private val d=a.d(1).toFloat()
    /** A big goal (over 20 books): no point per book — a few labelled marks, and the books read so far stand as one
     *  small stack where you are (the chosen book on top); tap the stack or swipe the line to go through them. */
    private val big=slots>20
    private val coverFrom=if(!big&&slots>16)max(0,books.size-6) else 0
    private val covers=books.mapIndexed{i,b->if(i<coverFrom)null else MiniCover(a,b.book)}
    private val badge=if(big&&books.size>1)a.text("${books.size}",11f,if(a.light)Color.WHITE else 0xff141416.toInt(),true).apply{gravity=Gravity.CENTER;minWidth=a.d(20)
        setPadding(a.d(6),a.d(2),a.d(6),a.d(2));background=android.graphics.drawable.GradientDrawable().apply{setColor(a.fg);cornerRadius=a.d(10).toFloat()};elevation=a.d(8).toFloat()} else null
    private val tickStep=listOf(5,10,20,25,50,100,200,250,500,1000,2000,5000).firstOrNull{slots/it in 2..5}?:max(1,slots/4)
    /** The stack, top first: the chosen book, then the ones read before it. */
    private fun stack():List<Int>{val order=(selected downTo 0)+(selected+1..books.lastIndex);return order.take(3)}
    private fun place(i:Int,c:View,animate:Boolean){val depth=stack().indexOf(i);val dd=if(depth<0)2 else depth
        val tx=floatArrayOf(0f,-d*12,-d*22)[dd];val ty=floatArrayOf(-d*8,d*2,d*8)[dd];val sc=floatArrayOf(SEL,BASE*.95f,BASE*.88f)[dd];val rot=floatArrayOf(0f,-7f,-13f)[dd]
        val al=if(depth<0)0f else if(dd==2).9f else 1f;c.translationZ=floatArrayOf(d*6,d*4,d*2)[dd];c.isClickable=depth>=0
        if(animate&&motion)c.animate().setUpdateListener{invalidate()}.translationX(tx).translationY(ty).scaleX(sc).scaleY(sc).rotation(rot).alpha(al).setStartDelay(0).setDuration(480).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()
        else{c.translationX=tx;c.translationY=ty;c.scaleX=sc;c.scaleY=sc;c.rotation=rot;c.alpha=al}}
    private var walk=1f;private var pulse=0f;private var clock:ValueAnimator?=null
    private val pos=FloatArray(2)
    init{setWillNotDraw(false);clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        covers.forEachIndexed{i,c->c?:return@forEachIndexed;addView(c,LayoutParams(a.d(40),a.d(60)));c.isClickable=true;c.contentDescription="${i+1}-я книга: ${books[i].book.str("title")}"
            c.setOnClickListener{if(big)select(if(selected>0)selected-1 else books.lastIndex) else select(i)}}
        badge?.let{addView(it,LayoutParams(-2,-2))}}
    // a big goal needs no room for covers along the whole road (they stand in one stack), so its road is lower and
    // ends lower — no empty field above the flag
    override fun onMeasure(w:Int,h:Int){super.onMeasure(w,MeasureSpec.makeMeasureSpec(a.d(if(big)178 else 232),MeasureSpec.EXACTLY))}
    private fun slot(i:Int)=(i+1f)/slots
    private fun at(f:Float):FloatArray{pm.getPosTan(length*f.coerceIn(0f,1f),pos,null);return floatArrayOf(pos[0],pos[1])}
    override fun onAttachedToWindow(){super.onAttachedToWindow()
        if(walk<1f){val wait=StatsEntrance.wait();ValueAnimator.ofFloat(0f,1f).apply{duration=1100;startDelay=wait+120;interpolator=Ease.outCubic;addUpdateListener{walk=it.animatedValue as Float;invalidate()};start()}
            covers.forEachIndexed{i,c->c?:return@forEachIndexed;c.alpha=0f;c.translationY=d*16;c.scaleX=BASE*.85f;c.scaleY=BASE*.85f
                val lift=if(i==selected)-d*8 else 0f;val k=if(i==selected)SEL else BASE
                c.animate().setUpdateListener{invalidate()}.alpha(1f).translationY(lift).scaleX(k).scaleY(k).setStartDelay(wait+180L+(i-coverFrom)*70L).setDuration(620).setInterpolator(android.view.animation.PathInterpolator(.16f,1f,.3f,1f)).start()}}
        if(motion&&books.size<slots)clock=ValueAnimator.ofFloat(0f,1f).apply{duration=1600;repeatCount=ValueAnimator.INFINITE;interpolator=Ease.outQuad;addUpdateListener{pulse=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){clock?.cancel();clock=null;super.onDetachedFromWindow()}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat();val H=h.toFloat()
        fun x(f:Float):Float{return d*12+(W-d*40)*f}
        fun y(f:Float):Float{return d*70+(H-d*84)*f}
        road.reset();road.moveTo(x(0f),y(.95f))
        road.cubicTo(x(.2f),y(1f),x(.2f),y(.56f),x(.41f),y(.56f))
        road.cubicTo(x(.62f),y(.56f),x(.64f),y(.92f),x(.82f),y(.7f))
        if(big)road.cubicTo(x(1f),y(.55f),x(1f),y(.36f),x(1f),y(.3f)) else road.cubicTo(x(1f),y(.48f),x(1f),y(.2f),x(1f),y(.14f))
        pm.setPath(road,false);length=pm.length;requestLayout()}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){if(length<=0f)return
        if(big){val q=at(books.size.toFloat()/slots);val x=max(q[0],d*46);val w=d*40;val h=d*60
            covers.forEachIndexed{i,c->c?:return@forEachIndexed;c.layout((x-w/2).roundToInt(),(q[1]-d*9-h).roundToInt(),(x+w/2).roundToInt(),(q[1]-d*9).roundToInt());c.pivotX=c.width/2f;c.pivotY=c.height.toFloat();place(i,c,false)}
            badge?.let{bg->val bw=bg.measuredWidth;val bh=bg.measuredHeight;val top=q[1]-d*9-h*SEL-d*8;val left=x+w*SEL/2-bw/2f
                bg.layout(left.roundToInt(),(top-bh/2f).roundToInt(),(left+bw).roundToInt(),(top+bh/2f).roundToInt())}
            return}
        covers.forEachIndexed{i,c->c?:return@forEachIndexed;val q=at(slot(i));val w=d*40;val h=d*60
            c.layout((q[0]-w/2).roundToInt(),(q[1]-d*9-h).roundToInt(),(q[0]+w/2).roundToInt(),(q[1]-d*9).roundToInt());c.pivotX=c.width/2f;c.pivotY=c.height.toFloat()
            c.translationZ=if(i==selected)d*6 else d*2
            if(walk>=1f||!motion){val lift=if(i==selected)-d*8 else 0f;val k=if(i==selected)SEL else BASE;c.translationY=lift;c.scaleX=k;c.scaleY=k}}}
    /** Show book [i]: its cover springs up, grows and lights up softly (no frame); the previous one settles back. */
    fun select(i:Int){if(i !in books.indices||i==selected)return;val dir=if(i>selected)1 else -1;val old=selected;selected=i
        if(big){covers.forEachIndexed{k,c->c?:return@forEachIndexed;place(k,c,true)};invalidate();onPick(i,dir);return}
        listOf(old,i).forEach{k->val c=covers[k]?:return@forEach;c.translationZ=if(k==i)d*6 else d*2
            val lift=if(k==i)-d*8 else 0f;val s=if(k==i)SEL else BASE
            if(motion){c.animate().setUpdateListener{invalidate()}.translationY(lift).scaleX(s).scaleY(s).setStartDelay(0).setDuration(460).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()} else {c.translationY=lift;c.scaleX=s;c.scaleY=s}}
        invalidate();onPick(i,dir)}
    // earlier books shown as milestones (very large goals) are picked by a tap near them
    override fun onTouchEvent(e:MotionEvent):Boolean{if(coverFrom==0)return false
        if(e.actionMasked==MotionEvent.ACTION_DOWN)return true
        if(e.actionMasked==MotionEvent.ACTION_UP){var best=-1;var bd=d*18
            for(i in 0 until coverFrom){val q=at(slot(i));val dist=hypot(e.x-q[0],e.y-q[1]);if(dist<bd){bd=dist;best=i}}
            if(best>=0)select(best);return true}
        return true}
    override fun onDraw(c:Canvas){val pt0=StatsProbe.t();try{draw0(c)}finally{StatsProbe.add("Дорога цели",pt0)}}
    private fun draw0(c:Canvas){if(length<=0f)return
        val green=if(a.light)0xff1f9d63.toInt() else 0xff2fd38a.toInt();val green2=if(a.light)0xff52c68f.toInt() else 0xff9df5c8.toInt()
        p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x0f000000 else 0x12ffffff;p.strokeWidth=d*16;c.drawPath(road,p)
        p.strokeCap=Paint.Cap.BUTT;p.strokeWidth=d*1.4f;p.color=if(a.light)0x38000000 else 0x38ffffff;p.pathEffect=DashPathEffect(floatArrayOf(d*5,d*7),0f);c.drawPath(road,p);p.pathEffect=null
        // the way walked so far
        val done=(books.size.toFloat()/slots).coerceIn(0f,1f)*walk
        if(done>0f){val part=Path();pm.getSegment(0f,length*done,part,true);p.strokeCap=Paint.Cap.ROUND
            if(!a.light)glowFor(part)?.let{glowPaint.alpha=(0x8c*glowFade).toInt();c.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),height.toFloat()),glowPaint)}
            p.shader=LinearGradient(0f,0f,width.toFloat(),0f,green,green2,Shader.TileMode.CLAMP);p.strokeWidth=d*5;c.drawPath(part,p);p.shader=null}
        p.style=Paint.Style.FILL
        if(big){tp.textSize=10*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(500);tp.textAlign=Paint.Align.CENTER
            var m=tickStep;while(m<slots){val f=m.toFloat()/slots;val q=at(f);val reached=f<=done+.001f
                p.color=if(reached)(if(a.light)0xff1f9d63.toInt() else Color.WHITE) else if(a.light)0x59000000 else 0x66ffffff;c.drawCircle(q[0],q[1],d*3.2f,p)
                tp.color=if(reached)a.fg else a.muted;c.drawText("$m",q[0],q[1]+d*20,tp);m+=tickStep}
            tp.textAlign=Paint.Align.LEFT
            if(books.isNotEmpty()){val q=at(books.size.toFloat()/slots);val x=max(q[0],d*46);p.color=if(a.light)0x59000000 else 0x80ffffff.toInt();p.strokeWidth=d*1.5f;c.drawLine(x,q[1]-d*9,q[0],q[1]-d*3,p)
                p.color=if(a.light)0xff1f9d63.toInt() else Color.WHITE;c.drawCircle(q[0],q[1],d*4f,p)}}
        // milestones: one per book of the goal
        if(!big)for(k in 1 until slots){val q=at(k.toFloat()/slots);val reached=k<=books.size&&k.toFloat()/slots<=done+.001f
            p.color=if(reached)(if(a.light)0xff1f9d63.toInt() else Color.WHITE) else if(a.light)0x40000000 else 0x4dffffff
            c.drawCircle(q[0],q[1],d*(if(reached)(if(k-1<coverFrom&&k-1==selected)4.6f else 3.4f) else 2.4f),p)}
        // stems under the covers
        p.color=if(a.light)0x59000000 else 0x80ffffff.toInt();p.strokeWidth=d*1.5f
        if(!big)for(i in coverFrom until books.size){val q=at(slot(i));c.drawLine(q[0],q[1]-d*9,q[0],q[1]-d*3,p)}
        // the next milestone breathes
        if(!big&&books.size<slots&&walk>=1f){val q=at((books.size+1f)/slots);p.style=Paint.Style.STROKE;p.strokeWidth=d*2;p.color=green;p.alpha=(255*(1-pulse)).toInt();c.drawCircle(q[0],q[1],d*(5+6*pulse),p);p.alpha=255;p.style=Paint.Style.FILL}
        // start ring and the flag with the goal
        val s0=at(0f);p.color=if(a.light)Color.WHITE else 0xff1c1c1f.toInt();c.drawCircle(s0[0],s0[1],d*5.5f,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=d*3;p.color=green;c.drawCircle(s0[0],s0[1],d*5.5f,p);p.style=Paint.Style.FILL
        val e=at(1f);p.color=a.fg;c.drawRect(e[0]-d,e[1]-d*30,e[0]+d,e[1],p)
        c.drawPath(Path().apply{moveTo(e[0]+d,e[1]-d*30);lineTo(e[0]+d*18,e[1]-d*25);lineTo(e[0]+d,e[1]-d*19);close()},p)
        if(goal>0){tp.textSize=11*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.sans(600);tp.color=a.fg;c.drawText("$goal",e[0]+d*21,e[1]-d*20,tp)}
        // a soft green light behind the chosen cover; it grows and fades with the cover's own lift
        covers.forEach{cv->cv?:return@forEach;val k=((cv.scaleX-BASE)/(SEL-BASE)).coerceIn(0f,1f);if(k<=.01f||cv.width==0)return@forEach
            val g=haloFor(cv.width,cv.height)?:return@forEach;val w=cv.width*cv.scaleX;val h=cv.height*cv.scaleY
            val l=cv.left+cv.pivotX*(1-cv.scaleX);val t=cv.top+cv.translationY+cv.pivotY*(1-cv.scaleY);val pad=haloPad*cv.scaleX
            haloPaint.alpha=(k*(if(a.light)150 else 235)).toInt();c.drawBitmap(g,null,RectF(l-pad,t-pad,l+w+pad,t+h+pad),haloPaint)}}
    private val haloPad=d*14;private var halo:Bitmap?=null;private val haloPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    /** Blurred off the main thread at a third of the size (measured: done in the frame it cost ~140 ms). */
    private var haloPending=false
    private fun haloFor(w:Int,h:Int):Bitmap?{halo?.let{return it};val pad=haloPad
        halo=Glow.blurred((w+pad*2).toInt(),(h+pad*2).toInt(),3,d*5){c,p->p.color=if(a.light)0xff2fd38a.toInt() else 0xff9df5c8.toInt();c.drawRoundRect(pad,pad,pad+w,pad+h,d*3,d*3,p)}
        return halo}
    // soft glow under the walked part: drawn thick at a third of the size and blurred once
    private var glowBmp:Bitmap?=null;private var glowKey=-1f
    private val glowPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{alpha=0x8c}
    /** The glow is blurred off the main thread (a blur mid-animation dropped frames) and fades in once ready. */
    private var glowPending=false;private var glowFade=0f
    private fun glowFor(part:Path):Bitmap?{if(walk<1f)return null;val key=width*10000f+height;if(glowKey==key)return glowBmp
        glowBmp=Glow.blurred(width,height,3,d*3.2f){c,p->p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=d*9;p.color=0xff2fd38a.toInt();c.drawPath(part,p)}
        glowKey=key;glowFade=1f;return glowBmp}
    companion object{const val SEL=1.12f;const val BASE=.8f;val MONTHS_SHORT=listOf("янв","фев","мар","апр","мая","июн","июл","авг","сен","окт","ноя","дек")}
}

/** An empty, dashed star: the book has no rating yet. */
internal class EmptyStar(private val a:MainActivity):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(36),a.d(36))
    override fun onDraw(c:Canvas){val s=min(width,height)/100f;c.save();c.translate((width-100*s)/2,(height-100*s)/2);c.scale(s,s)
        val star=Path();for(i in 0 until 10){val r=if(i%2==1)21f else 46f;val t=Math.toRadians(-90.0+36*i);val x=(50+r*cos(t)).toFloat();val y=(53+r*sin(t)).toFloat();if(i==0)star.moveTo(x,y) else star.lineTo(x,y)};star.close()
        p.strokeWidth=3f;p.color=a.muted;p.pathEffect=DashPathEffect(floatArrayOf(7f,6f),0f);p.strokeJoin=Paint.Join.ROUND;c.drawPath(star,p);c.restore()}
}
