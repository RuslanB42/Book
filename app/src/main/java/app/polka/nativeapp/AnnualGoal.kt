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
internal class GoalGlowBar(a:MainActivity,animate:Boolean,private val fraction:Float,private val tint:Int,description:String):StatsAnimatedView(a,animate){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    val glow get()=a.d(14)
    private val barH get()=a.d(14).toFloat()
    init{contentDescription=description;setLayerType(LAYER_TYPE_SOFTWARE,null)}
    private val vpad get()=a.d(12).toFloat()
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(MeasureSpec.getSize(w),(barH+vpad*2).toInt())
    override fun onDraw(c:Canvas){
        val g=glow.toFloat();val x0=g;val x1=width-g;val w=x1-x0;val top=vpad;val bottom=top+barH;val r=barH/2;val light=a.light
        p.reset();p.isAntiAlias=true
        // groove: base, then an inner shadow along the top edge and a faint light along the bottom
        p.color=if(light)0xffe6e6ea.toInt() else 0xff2a2a2e.toInt();c.drawRoundRect(x0,top,x1,bottom,r,r,p)
        c.save();val groove=Path().apply{addRoundRect(x0,top,x1,bottom,r,r,Path.Direction.CW)};c.clipPath(groove)
        p.color=if(light)0x40000000 else 0xb0000000.toInt();p.style=Paint.Style.STROKE;p.strokeWidth=a.d(4).toFloat();p.maskFilter=BlurMaskFilter(a.d(3).toFloat(),BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(x0-a.d(1),top+a.d(1),x1+a.d(1),bottom+a.d(3),r,r,p)
        p.maskFilter=null
        p.style=Paint.Style.FILL;c.restore()
        val f=(fraction*phase).coerceIn(0f,1f);if(f<=0f)return
        val right=x0+(w*f).coerceAtLeast(barH*1.4f).coerceAtMost(w)
        // exactly the icon's colour, brightening toward the head — vivid, not dimmed
        val head=androidx.core.graphics.ColorUtils.blendARGB(tint,0xffffd0a0.toInt(),.35f)
        p.color=(tint and 0xffffff) or ((if(light)0x60 else 0x8c) shl 24);p.maskFilter=BlurMaskFilter(a.d(8).toFloat(),BlurMaskFilter.Blur.NORMAL)
        c.drawRoundRect(x0+a.d(4),top+a.d(1),right-a.d(2),bottom+a.d(1),r,r,p);p.maskFilter=null
        p.shader=LinearGradient(x0,0f,right,0f,intArrayOf(tint,tint,head),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x0,top,right,bottom,r,r,p)
        // glossy top light and a hot spot at the head
        p.shader=LinearGradient(0f,top,0f,bottom,intArrayOf(0x2affffff,0x0affffff,0x00ffffff),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x0+a.d(2),top+a.d(1.5f),right-a.d(2),top+barH*.58f,r*.7f,r*.7f,p)
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
        if(books.isEmpty())return@apply
        // Collapsed: nothing here; the books appear only when the card is opened.
        addView(grid,LayoutParams(-1,-2))}}
    private fun cover(book:JSONObject):View{val box=AspectBox(a);val rating=book.optInt("rating")
        fun put(b:Bitmap?){box.removeAllViews();box.addView(CoverView(a,book,b).apply{softShadow=true},FrameLayout.LayoutParams(-1,-1))
            // your rating as a small medal pinned to the cover's corner
            if(rating>0)box.addView(RatingMedal(a,rating),FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.END).apply{rightMargin=a.d(3);topMargin=a.d(3)})}
        put(a.cover(book));if(a.cover(book)==null&&book.optJSONObject("cover")!=null)a.requestCover(book){b->if(b!=null)put(b)};return box}
    private fun stars(n:Int,size:Int=11)=object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);val path=androidx.core.graphics.PathParser.createPathFromPathData("M12 2.8l2.85 5.77 6.37 .93-4.61 4.49 1.09 6.34L12 17.34l-5.7 2.99 1.09-6.34-4.61-4.49 6.37-.93Z")!!
        override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(size*5+8),a.d(size+1))
        override fun onDraw(c:Canvas){val s=a.d(size).toFloat();for(i in 0 until 5){c.save();c.translate(i*(s+a.d(2)),0f);c.scale(s/24,s/24);p.style=if(i<n)Paint.Style.FILL else Paint.Style.STROKE;p.strokeWidth=2f;p.color=if(i<n)a.accentFill else (a.muted and 0xffffff) or 0x80000000.toInt();c.drawPath(path,p);c.restore()}}}
    private fun date(d:LocalDate?)=d?.format(DateTimeFormatter.ofPattern("d MMMM",ru)).orEmpty()
    private fun single(b:JSONObject,d:LocalDate?):View{val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;isClickable=true;setOnClickListener{a.detail(b)}}
        r.addView(cover(b),LayoutParams(a.d(64),-2))
        val m=a.column().apply{setPadding(a.d(16),0,0,0)};m.addView(a.text(b.str("title"),16f,a.fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        m.addView(a.text(MainActivity.authors(b),13f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
if(d!=null){a.space(m,6);m.addView(a.text("Прочитана ${date(d)}",12.5f,a.muted))}
        r.addView(m,LayoutParams(0,-2,1f));return r}
    private fun cell(b:JSONObject,d:LocalDate?,i:Int):View{val cell=a.column().apply{clipChildren=false;isClickable=true;setOnClickListener{a.detail(b)};a.press(this)}
        cell.addView(cover(b),LayoutParams(-1,-2));a.space(cell,8)
        cell.addView(a.text(b.str("title"),12.5f,a.fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
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

/** Your rating as a small chip in the cover's top-right corner: dark glass, a gold star and the number (like ratings on posters). */
/** A cloisonné enamel award, in the spirit of activity-app badges: a polished gold rim, two or three enamel fields
 *  divided by thin gold wires, a glassy highlight, and the rating as a raised gold numeral. Shape and colours grow
 *  with the rating — 1–2 a round coin, 3 a shield, 4–5 a hexagon. */
internal class RatingMedal(private val a:MainActivity,private val rating:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private class Look(val base:Int,val band:Int,val third:Int?)
    private val look=when(rating.coerceIn(1,5)){
        1->Look(0xff33415f.toInt(),0xff7d9fd6.toInt(),null)
        2->Look(0xff15806f.toInt(),0xff9be15d.toInt(),null)
        3->Look(0xfff2862c.toInt(),0xffffd84a.toInt(),null)
        4->Look(0xff2463d8.toInt(),0xff3fd3e8.toInt(),0xffb8f53a.toInt())
        else->Look(0xffff3d7f.toInt(),0xffb8f53a.toInt(),0xffff9b2b.toInt())}
    init{contentDescription="Оценка $rating из 5";setLayerType(LAYER_TYPE_SOFTWARE,null)}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension(a.d(38),a.d(40))
    /** The outline in a 100×100 box. */
    private fun shape():Path=Path().apply{when(rating.coerceIn(1,5)){
        1,2->addCircle(50f,50f,45f,Path.Direction.CW)
        3->{moveTo(14f,8f);lineTo(86f,8f);quadTo(92f,8f,92f,14f);lineTo(92f,48f);cubicTo(92f,74f,72f,88f,50f,97f);cubicTo(28f,88f,8f,74f,8f,48f);lineTo(8f,14f);quadTo(8f,8f,14f,8f);close()}
        else->{val pts=(0 until 6).map{i->val t=Math.toRadians(-90.0+60*i);(50+47*Math.cos(t)).toFloat() to (51+47*Math.sin(t)).toFloat()}
            // rounded hexagon: cut each corner with a quadratic through the vertex
            for(i in 0 until 6){val (x,y)=pts[i];val (px,py)=pts[(i+5)%6];val (nx,ny)=pts[(i+1)%6];val k=.16f
                val ax=x+(px-x)*k;val ay=y+(py-y)*k;val bx=x+(nx-x)*k;val by=y+(ny-y)*k
                if(i==0)moveTo(ax,ay) else lineTo(ax,ay);quadTo(x,y,bx,by)};close()}}}
    private val gold=intArrayOf(0xfffff4c2.toInt(),0xffe8b84e.toInt(),0xff9c6b1d.toInt(),0xfff6d37c.toInt(),0xffb8862a.toInt(),0xfffff0b0.toInt())
    override fun onDraw(c:Canvas){val s=minOf(width,height)/100f;c.save();c.translate((width-100*s)/2,(height-100*s)/2);c.scale(s,s)
        val outline=shape();val dp=a.d(1)/s
        // drop shadow under the whole badge
        p.reset();p.isAntiAlias=true;p.color=Color.BLACK;p.setShadowLayer(4f*dp,0f,2.2f*dp,0x80000000.toInt());c.drawPath(outline,p);p.clearShadowLayer()
        // enamel fields, clipped to the inside of the rim
        c.save();c.clipPath(outline)
        p.shader=RadialGradient(42f,38f,70f,lighten(look.base,.22f),look.base,Shader.TileMode.CLAMP);c.drawRect(0f,0f,100f,100f,p);p.shader=null
        p.style=Paint.Style.FILL;p.color=look.band;p.shader=RadialGradient(10f,100f,70f,lighten(look.band,.2f),look.band,Shader.TileMode.CLAMP);c.drawCircle(10f,100f,58f,p);p.shader=null
        wire(c){it.addCircle(10f,100f,58f,Path.Direction.CW)}
        look.third?.let{t->p.style=Paint.Style.FILL;p.shader=RadialGradient(90f,6f,36f,lighten(t,.2f),t,Shader.TileMode.CLAMP);c.drawCircle(90f,6f,34f,p);p.shader=null
            wire(c){it.addCircle(90f,6f,34f,Path.Direction.CW)}}
        // glassy enamel highlight, upper-left
        p.style=Paint.Style.FILL;p.shader=RadialGradient(30f,22f,46f,0x55ffffff,0x00ffffff,Shader.TileMode.CLAMP);c.drawRect(0f,0f,100f,100f,p);p.shader=null
        c.restore()
        // polished gold rim: thick sweep, thin dark inner bevel, bright outer lip
        p.style=Paint.Style.STROKE;p.strokeJoin=Paint.Join.ROUND
        p.strokeWidth=9f;p.shader=SweepGradient(50f,50f,gold,null).apply{setLocalMatrix(Matrix().apply{setRotate(-120f,50f,50f)})};c.drawPath(outline,p);p.shader=null
        p.strokeWidth=1.4f;p.color=0x66402a08;c.save();c.scale(.905f,.905f,50f,51f);c.drawPath(outline,p);c.restore()
        p.strokeWidth=1.1f;p.color=0x99fff6d8.toInt();c.drawPath(outline,p)
        // raised gold numeral
        p.style=Paint.Style.FILL;p.typeface=Typeface.create(Typeface.SERIF,Typeface.BOLD);p.textSize=58f;p.textAlign=Paint.Align.CENTER
        val ty=56f-(p.ascent()+p.descent())/2
        p.style=Paint.Style.STROKE;p.strokeWidth=7f;p.color=0xcc3a2405.toInt();c.drawText("$rating",50f,ty,p)
        p.style=Paint.Style.FILL;p.shader=LinearGradient(0f,ty-44f,0f,ty+4f,intArrayOf(0xfffff6cf.toInt(),0xfff0c35a.toInt(),0xffa8731f.toInt(),0xfff3d07a.toInt()),floatArrayOf(0f,.4f,.75f,1f),Shader.TileMode.CLAMP);c.drawText("$rating",50f,ty,p);p.shader=null
        p.style=Paint.Style.STROKE;p.strokeWidth=1.2f;p.color=0x88fff8e0.toInt();c.drawText("$rating",50f,ty,p);p.style=Paint.Style.FILL
        c.restore()}
    /** A thin gold cloisonné wire along a path. */
    private fun wire(c:Canvas,build:(Path)->Unit){val path=Path().also(build);p.style=Paint.Style.STROKE;p.shader=null
        p.strokeWidth=3.4f;p.color=0xff8a5d17.toInt();c.drawPath(path,p);p.strokeWidth=2.2f;p.color=0xfff0c860.toInt();c.drawPath(path,p);p.style=Paint.Style.FILL}
    private fun lighten(c:Int,t:Float)=Color.rgb((Color.red(c)+(255-Color.red(c))*t).toInt(),(Color.green(c)+(255-Color.green(c))*t).toInt(),(Color.blue(c)+(255-Color.blue(c))*t).toInt())
}
