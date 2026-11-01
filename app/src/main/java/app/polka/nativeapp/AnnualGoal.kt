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

/**
 * Thick glowing progress bar: a warm gradient fill with a soft light along its top, a glowing head, an outer glow,
 * a small "pace" mark where you would be today at an even pace, and the scale underneath. Fills once with a soft spring.
 */
internal class GoalGlowBar(a:MainActivity,animate:Boolean,private val fraction:Float,private val pace:Float?,private val left:String,private val right:String,description:String):StatsAnimatedView(a,animate){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=12.5f*a.resources.displayMetrics.scaledDensity;fontFeatureSettings="tnum"}
    private val barH get()=a.d(30).toFloat()
    init{contentDescription=description;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),(a.d(14)+barH+a.d(10)+label.textSize+a.d(4)).toInt())
    override fun onDraw(c:Canvas){
        val top=a.d(7).toFloat();val w=width.toFloat();val r=barH/2;val light=a.light
        p.shader=null;p.clearShadowLayer();p.color=if(light)0xffececef.toInt() else 0xff2f2f33.toInt();c.drawRoundRect(0f,top,w,top+barH,r,r,p)
        val f=(fraction*phase).coerceIn(0f,1f)
        if(f>0f){val right=max(barH,w*f);val c1=0xffff6a3d.toInt();val c2=0xffffb340.toInt()
            // outer glow
            p.shader=null;p.color=c2;p.setShadowLayer(a.d(12).toFloat(),0f,a.d(2).toFloat(),(c2 and 0xffffff) or (if(light)0x66 shl 24 else 0xaa shl 24))
            c.drawRoundRect(0f,top,right,top+barH,r,r,p);p.clearShadowLayer()
            p.shader=LinearGradient(0f,0f,right,0f,intArrayOf(c1,0xffff8a3a.toInt(),c2),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(0f,top,right,top+barH,r,r,p)
            // soft light along the top
            p.shader=LinearGradient(0f,top,0f,top+barH,intArrayOf(0x66ffffff,0x10ffffff,0x00ffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(a.d(3).toFloat(),top+a.d(2),right-a.d(3),top+barH*.55f,r*.6f,r*.6f,p)
            // glowing head
            val hx=right-r;p.shader=RadialGradient(hx,top+r,r*1.6f,intArrayOf(0xccfff4d6.toInt(),0x55ffd27a,0x00ffd27a),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawCircle(hx,top+r,r*1.6f,p)
            p.shader=null}
        // pace mark
        if(pace!=null&&pace in .02f..0.98f){val x=w*pace;p.shader=null;p.color=if(x<w*f)0xccffffff.toInt() else (if(light)0xff9a9aa0.toInt() else 0xff8a8a90.toInt());c.drawCircle(x,top+r,a.d(3).toFloat(),p)}
        label.color=if(light)0xff86868b.toInt() else 0xff8a8a8e.toInt();val base=top+barH+a.d(10)-label.ascent()
        label.textAlign=Paint.Align.LEFT;c.drawText(left,0f,base,label);label.textAlign=Paint.Align.RIGHT;c.drawText(right,w,base,label)
        if(pace!=null&&pace in .12f..0.88f){label.textAlign=Paint.Align.CENTER;c.drawText("темп",w*pace,base,label)}
    }
}

/**
 * Books finished in the goal's year. Collapsed: a fanned row of small covers (newest first) with "+N".
 * Expanded (tap the card): every book as a cover with its title, your stars and the date — opening with a smooth height
 * reveal and the covers rising one after another.
 */
internal class YearBooks(private val a:MainActivity,private val books:List<Pair<JSONObject,LocalDate?>>,private val motion:Boolean):LinearLayout(a){
    private val row=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    private val grid=LinearLayout(a).apply{orientation=VERTICAL;visibility=GONE;clipChildren=false;clipToPadding=false}
    private var open=false
    private var anim:ValueAnimator?=null
    init{orientation=VERTICAL;clipChildren=false;clipToPadding=false
        if(books.isEmpty()){addView(a.text("Здесь появятся книги, прочитанные за этот год",13.5f,a.muted));return@init}
        addView(row,LayoutParams(-1,a.d(66)));buildRow();addView(grid,LayoutParams(-1,-2))}
    private fun cover(book:JSONObject):View{val box=FrameLayout(a).apply{clipChildren=false}
        fun put(b:Bitmap?){box.removeAllViews();box.addView(CoverView(a,book,b).apply{softShadow=true},FrameLayout.LayoutParams(-1,-1))}
        put(a.cover(book));if(a.cover(book)==null&&book.optJSONObject("cover")!=null)a.requestCover(book){b->if(b!=null)put(b)};return box}
    private fun buildRow(){
        val show=books.take(7);val cw=a.d(40);val ch=a.d(60);val step=a.d(30)
        show.forEachIndexed{i,(b,_)->val v=cover(b);v.rotation=listOf(-4f,2f,-1f,3f,-3f,1f,-2f)[i];row.addView(v,0,FrameLayout.LayoutParams(cw,ch).apply{leftMargin=i*step;topMargin=a.d(3)})
            if(motion){v.alpha=0f;v.translationY=a.d(10).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(240L+i*55L).setDuration(420).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
        if(books.size>show.size){val more=a.text("+${books.size-show.size}",13f,a.fg,true).apply{gravity=Gravity.CENTER;background=a.shape(if(a.light)0xffececef.toInt() else 0xff2f2f33.toInt(),20)}
            row.addView(more,FrameLayout.LayoutParams(a.d(40),a.d(40)).apply{leftMargin=show.size*step+a.d(12);topMargin=a.d(13)})}
        val hint=IconView(a,"chevdown",a.muted).apply{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO};row.addView(hint,FrameLayout.LayoutParams(a.d(22),a.d(22),Gravity.END or Gravity.CENTER_VERTICAL));chevron=hint
    }
    private var chevron:View?=null
    private val ru=Locale("ru")
    private fun stars(n:Int)=object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);val path=androidx.core.graphics.PathParser.createPathFromPathData("M12 2.8l2.85 5.77 6.37 .93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z")!!
        override fun onDraw(c:Canvas){val s=a.d(11).toFloat();for(i in 0 until 5){c.save();c.translate(i*(s+a.d(2)),0f);c.scale(s/24,s/24);p.style=if(i<n)Paint.Style.FILL else Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(i<n)a.accentFill else (a.muted and 0xffffff) or 0x80000000.toInt();c.drawPath(path,p);c.restore()}}}
    private fun buildGrid(){grid.removeAllViews();val cols=3;var line:LinearLayout?=null
        books.forEachIndexed{i,(b,date)->if(i%cols==0){line=a.line().apply{gravity=Gravity.TOP;clipChildren=false};grid.addView(line,LayoutParams(-1,-2).apply{topMargin=a.d(if(i==0)16 else 18)})}
            val cell=a.column().apply{clipChildren=false;isClickable=true;setOnClickListener{a.detail(b)};a.press(this)}
            cell.addView(AspectBox(a).apply{addView(cover(b),FrameLayout.LayoutParams(-1,-1))},LayoutParams(-1,-2))
            line!!.addView(cell,LayoutParams(0,-2,1f).apply{if(i%cols!=0)leftMargin=a.d(14)})
            a.space(cell,8);cell.addView(a.text(b.str("title"),13f,a.fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
            val rating=b.optInt("rating");if(rating>0){a.space(cell,4);cell.addView(stars(rating),LayoutParams(-1,a.d(12)))}
            date?.let{a.space(cell,3);cell.addView(a.text(it.format(DateTimeFormatter.ofPattern("d MMMM",ru)),11.5f,a.muted))}
            if(motion){cell.alpha=0f;cell.translationY=a.d(14).toFloat();cell.scaleX=.94f;cell.scaleY=.94f;cell.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setStartDelay(140L+min(i,8)*45L).setDuration(460).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
        if(books.size%cols!=0)repeat(cols-books.size%cols){line!!.addView(View(a),LayoutParams(0,1,1f).apply{leftMargin=a.d(14)})}
    }
    fun toggle(){if(books.isEmpty())return;open=!open;anim?.cancel()
        chevron?.animate()?.rotation(if(open)180f else 0f)?.setDuration(320)?.setInterpolator(PathInterpolator(.2f,0f,0f,1f))?.start()
        if(open&&grid.childCount==0)buildGrid()
        if(!motion){grid.visibility=if(open)VISIBLE else GONE;return}
        val w=width;grid.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))
        val full=grid.measuredHeight
        val from=if(grid.visibility==VISIBLE)grid.height else 0;val to=if(open)full else 0
        grid.visibility=VISIBLE;grid.layoutParams=grid.layoutParams.apply{height=from}
        anim=ValueAnimator.ofInt(from,to).apply{duration=if(open)520 else 360;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{grid.layoutParams=grid.layoutParams.apply{height=it.animatedValue as Int};grid.alpha=if(open)1f else (it.animatedValue as Int).toFloat()/max(1,full)}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(open)grid.layoutParams=grid.layoutParams.apply{height=LayoutParams.WRAP_CONTENT} else {grid.visibility=GONE;grid.alpha=1f}}});start()}
    }
}

/** Keeps a book's 2:3 shape at whatever width it is given. */
internal class AspectBox(a:MainActivity):FrameLayout(a){
    init{clipChildren=false}
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val h=(w*1.5f).toInt();super.onMeasure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
}
