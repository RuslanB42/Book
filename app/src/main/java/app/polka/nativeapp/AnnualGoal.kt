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

/** Slim, bright glowing progress bar. The view reaches [glow] beyond its column on each side, so the glow fades out
 *  softly instead of being cut; the empty part of the track is a recessed groove with an inner shadow. */
internal class GoalGlowBar(a:MainActivity,animate:Boolean,private val fraction:Float,description:String):StatsAnimatedView(a,animate){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    val glow get()=a.d(14)
    private val barH get()=a.d(14).toFloat()
    init{contentDescription=description;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),(barH+glow*2).toInt())
    override fun onDraw(c:Canvas){
        val g=glow.toFloat();val x0=g;val x1=width-g;val w=x1-x0;val top=g;val bottom=top+barH;val r=barH/2;val light=a.light
        p.reset();p.isAntiAlias=true
        // groove: base, then an inner shadow along the top edge and a faint light along the bottom
        p.color=if(light)0xffe6e6ea.toInt() else 0xff2a2a2e.toInt();c.drawRoundRect(x0,top,x1,bottom,r,r,p)
        c.save();val groove=Path().apply{addRoundRect(x0,top,x1,bottom,r,r,Path.Direction.CW)};c.clipPath(groove)
        p.color=if(light)0x40000000 else 0xb0000000.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=a.d(4).toFloat();p.maskFilter=BlurMaskFilter(a.d(3).toFloat(),BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(x0-a.d(1),top+a.d(1),x1+a.d(1),bottom+a.d(3),r,r,p)
        p.color=if(light)0x30ffffff else 0x14ffffff;p.strokeWidth=a.d(1).toFloat();p.maskFilter=null;c.drawLine(x0+r,bottom-a.d(1)*.5f,x1-r,bottom-a.d(1)*.5f,p)
        p.style=Paint.Style.FILL;c.restore()
        val f=(fraction*phase).coerceIn(0f,1f);if(f<=0f)return
        val right=x0+(w*f).coerceAtLeast(barH*1.4f).coerceAtMost(w)
        val c1=0xffff5a2c.toInt();val c2=0xffffb22e.toInt();val c3=0xffffd76a.toInt()
        // bright glow around the fill, fading out well inside the view
        p.color=(0xff8a2a and 0xffffff) or ((if(light)0x88 else 0xcc) shl 24);p.maskFilter=BlurMaskFilter(a.d(9).toFloat(),BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(x0+a.d(2),top,right-a.d(1),bottom+a.d(2),r,r,p);p.maskFilter=null
        p.shader=LinearGradient(x0,0f,right,0f,intArrayOf(c1,0xffff7f2a.toInt(),c2,c3),floatArrayOf(0f,.4f,.85f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x0,top,right,bottom,r,r,p)
        // glossy top light and a hot spot at the head
        p.shader=LinearGradient(0f,top,0f,bottom,intArrayOf(0x88ffffff.toInt(),0x22ffffff,0x00ffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x0+a.d(2),top+a.d(1.5f),right-a.d(2),top+barH*.58f,r*.7f,r*.7f,p)
        p.shader=RadialGradient(right-r,top+r,r*1.8f,intArrayOf(0xb0fff6dc.toInt(),0x00fff6dc),null,Shader.TileMode.CLAMP);c.drawCircle(right-r,top+r,r*1.8f,p)
        p.shader=null
    }
}

/**
 * Books finished in the goal's year, shown as books: cover, title and your stars. One book is shown large with its
 * details; several — a row of three, the third turning into "+N" when there are more; tapping the card opens the rest
 * below with a smooth reveal, the covers rising one after another.
 */
internal class YearBooks(private val a:MainActivity,private val books:List<Pair<JSONObject,LocalDate?>>,private val motion:Boolean):LinearLayout(a){
    private val grid=LinearLayout(a).apply{orientation=VERTICAL;visibility=GONE;clipChildren=false;clipToPadding=false}
    private var open=false
    private var anim:ValueAnimator?=null
    private val ru=Locale("ru")
    private val cols=3
    private var chevron:View?=null
    init{run apply@{orientation=VERTICAL;clipChildren=false;clipToPadding=false
        if(books.isEmpty()){addView(a.text("Здесь появятся книги, прочитанные за этот год",13.5f,a.muted));return@apply}
        // Collapsed: only a quiet line; the books themselves appear when the card is opened.
        val hint=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(36)}
        hint.addView(a.text("Прочитанные книги",14f,a.fg,true));hint.addView(a.text("  ${books.size}",14f,a.muted),LayoutParams(0,-2,1f))
        val chev=IconView(a,"chevdown",a.muted);hint.addView(chev,LayoutParams(a.d(20),a.d(20)));chevron=chev
        addView(hint,LayoutParams(-1,-2));addView(grid,LayoutParams(-1,-2))}}
    private fun cover(book:JSONObject):View{val box=AspectBox(a)
        fun put(b:Bitmap?){box.removeAllViews();box.addView(CoverView(a,book,b).apply{softShadow=true},FrameLayout.LayoutParams(-1,-1))}
        put(a.cover(book));if(a.cover(book)==null&&book.optJSONObject("cover")!=null)a.requestCover(book){b->if(b!=null)put(b)};return box}
    private fun stars(n:Int,size:Int=11)=object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);val path=androidx.core.graphics.PathParser.createPathFromPathData("M12 2.8l2.85 5.77 6.37 .93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z")!!
        override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(size*5+8),a.d(size+1))
        override fun onDraw(c:Canvas){val s=a.d(size).toFloat();for(i in 0 until 5){c.save();c.translate(i*(s+a.d(2)),0f);c.scale(s/24,s/24);p.style=if(i<n)Paint.Style.FILL else Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(i<n)a.accentFill else (a.muted and 0xffffff) or 0x80000000.toInt();c.drawPath(path,p);c.restore()}}}
    private fun date(d:LocalDate?)=d?.format(DateTimeFormatter.ofPattern("d MMMM",ru)).orEmpty()
    private fun single(b:JSONObject,d:LocalDate?):View{val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;isClickable=true;setOnClickListener{a.detail(b)}}
        r.addView(cover(b),LayoutParams(a.d(64),-2))
        val m=a.column().apply{setPadding(a.d(16),0,0,0)};m.addView(a.text(b.str("title"),16f,a.fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        m.addView(a.text(MainActivity.authors(b),13f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        if(b.optInt("rating")>0){a.space(m,8);m.addView(stars(b.optInt("rating"),13))};if(d!=null){a.space(m,6);m.addView(a.text("Прочитана ${date(d)}",12.5f,a.muted))}
        r.addView(m,LayoutParams(0,-2,1f));return r}
    private fun cell(b:JSONObject,d:LocalDate?,i:Int):View{val cell=a.column().apply{clipChildren=false;isClickable=true;setOnClickListener{a.detail(b)};a.press(this)}
        cell.addView(cover(b),LayoutParams(-1,-2));a.space(cell,8)
        cell.addView(a.text(b.str("title"),12.5f,a.fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        val rating=b.optInt("rating");if(rating>0){a.space(cell,4);cell.addView(stars(rating))}
        if(motion){cell.alpha=0f;cell.translationY=a.d(12).toFloat();cell.animate().alpha(1f).translationY(0f).setStartDelay(160L+min(i,8)*50L).setDuration(460).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
        return cell}
    private fun row(items:List<Pair<JSONObject,LocalDate?>>,more:Int,offset:Int=0):LinearLayout{val line=a.line().apply{gravity=Gravity.TOP;clipChildren=false}
        items.forEachIndexed{k,(b,d)->line.addView(cell(b,d,offset+k),LayoutParams(0,-2,1f).apply{if(k>0)leftMargin=a.d(14)})}
        if(more>0){val tile=AspectBox(a).apply{background=a.shape(if(a.light)0xffefeff1.toInt() else 0xff2c2c30.toInt(),6)}
            val t=a.text("+$more",20f,a.fg,true).apply{gravity=Gravity.CENTER};tile.addView(t,FrameLayout.LayoutParams(-1,-1))
            val holder=a.column().apply{addView(tile,LayoutParams(-1,-2));a.space(this,8);addView(a.text("Все книги",12.5f,a.muted))}
            line.addView(holder,LayoutParams(0,-2,1f).apply{leftMargin=a.d(14)});moreTile=t;moreLabel=holder.getChildAt(2) as TextView}
        val filled=items.size+(if(more>0)1 else 0);repeat(cols-filled){line.addView(View(a),LayoutParams(0,1,1f).apply{leftMargin=a.d(14)})}
        return line}
    private var moreTile:TextView?=null;private var moreLabel:TextView?=null
    private fun buildGrid(){grid.removeAllViews()
        if(books.size==1){grid.addView(single(books[0].first,books[0].second),LayoutParams(-1,-2).apply{topMargin=a.d(12)});return}
        books.chunked(cols).forEachIndexed{r,items->grid.addView(row(items,0,r*cols),LayoutParams(-1,-2).apply{topMargin=a.d(if(r==0)12 else 18)})}}
    fun toggle(){if(books.isEmpty())return;open=!open;anim?.cancel()
        chevron?.animate()?.rotation(if(open)180f else 0f)?.setDuration(360)?.setInterpolator(PathInterpolator(.2f,0f,0f,1f))?.start()
        if(open&&grid.childCount==0)buildGrid()
        if(!motion){grid.visibility=if(open)VISIBLE else GONE;return}
        grid.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));val full=grid.measuredHeight
        val from=if(grid.visibility==VISIBLE)grid.height else 0;val to=if(open)full else 0
        grid.visibility=VISIBLE;grid.layoutParams=grid.layoutParams.apply{height=from}
        anim=ValueAnimator.ofInt(from,to).apply{duration=if(open)520 else 380;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{val h=it.animatedValue as Int;grid.layoutParams=grid.layoutParams.apply{height=h};if(!open)grid.alpha=h.toFloat()/max(1,full)}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(open)grid.layoutParams=grid.layoutParams.apply{height=LayoutParams.WRAP_CONTENT} else {grid.visibility=GONE;grid.alpha=1f}}});start()}
    }
}

/** Keeps a book's 2:3 shape at whatever width it is given. */
internal class AspectBox(a:MainActivity):FrameLayout(a){
    init{clipChildren=false}
    override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val h=(w*1.5f).toInt();super.onMeasure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
}
