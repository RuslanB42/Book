package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.*
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

/**
 * Approved notices «А»: a floating card at the top (radius 20), on the left the book's cover framed evenly
 * (32×48, 10 from the top, bottom and left), then a status dot right before the title, a quiet second line and,
 * where there is an obvious next step, one button. The dot always tells the truth: a spinner while working,
 * a check only on success, «!» when only part worked, a cross on failure. Notices without a book are compact
 * capsules with a tinted icon.
 *
 * Motion: drops in from above with a soft spring (a touch stretched, settling); while working a light sweeps the
 * blank cover and the dot spins; on the result the cover fades in, the dot pops and its sign draws itself, the text
 * melts into the new one while the card eases to its new size; a failure shakes the card once. It leaves upwards
 * and can be flicked away.
 */
internal class Notice private constructor(private val a:MainActivity,private val compact:Boolean){
    enum class Kind(val color:Int,val glyph:String){
        LOADING(0xfff0a742.toInt(),""),OK(0xff34b866.toInt(),"check"),PARTIAL(0xfff0a742.toInt(),"bang"),ERROR(0xffef5b4c.toInt(),"x"),
        KNOWN(0xff6e6e74.toInt(),"book"),ATTACHED(0xff4cb0e5.toInt(),"clip"),DELETED(0xffef5b4c.toInt(),"trash"),
        COPIED(0xfff0a742.toInt(),"copy"),INFO(0xff8e8e94.toInt(),"info"),SHELF(0xff8e8e94.toInt(),"shelf")}
    class State(val kind:Kind,val title:String,val sub:String="",val books:List<JSONObject> =emptyList(),val blank:Boolean=false,
                val action:String?=null,val onAction:(()->Unit)?=null,val progress:Float?=null,val faded:Boolean=false)

    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val smooth=PathInterpolator(.2f,0f,0f,1f)
    private val host=a.window.decorView as FrameLayout
    /** The card's floating shade reaches beyond it: the frame leaves room for it so nothing is cut. */
    private val pad=a.d(36)
    private val frame=FrameLayout(a).apply{clipChildren=false;clipToPadding=false;setPadding(pad,pad,pad,pad)}
    private val radius=if(compact)a.d(23).toFloat() else a.d(20).toFloat()
    private val fill=GradientDrawable().apply{setColor(if(a.light)Color.WHITE else 0xff242426.toInt());cornerRadius=radius;setStroke(maxOf(1,a.d(1)/2),if(a.light)0x0a000000 else 0x0dffffff)}
    private val card=FrameLayout(a).apply{background=Floating(a,fill,radius);clipChildren=false
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,radius)}}}
    private val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        if(compact)setPadding(a.d(8),0,a.d(18),0) else setPadding(a.d(10),a.d(10),a.d(14),a.d(10));minimumHeight=a.d(if(compact)46 else 68)}
    private val covers=CoverSlot(a)
    private val icon=FrameLayout(a)
    private val iconGlyph=Dot(a,true)
    private val dot=Dot(a,false)
    private val title=a.text("",15f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val sub=a.text("",12.5f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val texts=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
    private val titleRow=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private val button=a.text("",13f,a.fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(14),0,a.d(14),0);maxLines=1
        background=GradientDrawable().apply{setColor(if(a.light)0x0f000000 else 0x17ffffff);cornerRadius=a.d(17).toFloat()};a.press(this)}
    private val bar=Bar(a)
    private var state:State?=null
    private val hide=Runnable{dismiss()}
    private var leaving=false
    var onGone:(()->Unit)?=null

    init{
        if(compact){
            icon.background=GradientDrawable().apply{shape=GradientDrawable.OVAL}
            icon.addView(iconGlyph,FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))
            row.addView(icon,LinearLayout.LayoutParams(a.d(30),a.d(30)).apply{marginEnd=a.d(10)})
            row.addView(title,LinearLayout.LayoutParams(-2,-2))
        }else{
            row.addView(covers,LinearLayout.LayoutParams(-2,a.d(48)).apply{marginEnd=a.d(12)})
            titleRow.addView(dot,LinearLayout.LayoutParams(a.d(18),a.d(18)).apply{marginEnd=a.d(7)})
            titleRow.addView(title,LinearLayout.LayoutParams(0,-2,1f))
            texts.addView(titleRow,LinearLayout.LayoutParams(-1,-2));texts.addView(sub,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(3)})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button,LinearLayout.LayoutParams(-2,a.d(34)).apply{marginStart=a.d(12)})
        }
        card.addView(row,FrameLayout.LayoutParams(if(compact)-2 else -1,-2))
        card.addView(bar,FrameLayout.LayoutParams(-1,a.d(3),Gravity.BOTTOM).apply{leftMargin=a.d(18);rightMargin=a.d(18);bottomMargin=a.d(5)})
        card.isClickable=true
        // A book notice keeps the full width all its life: only its height may change, so the words never get squeezed.
        frame.addView(card,FrameLayout.LayoutParams(if(compact)-2 else -1,-2,Gravity.CENTER_HORIZONTAL))
        swipe()
    }

    private fun put(s:State,first:Boolean){
        state=s;title.text=s.title
        if(compact){(icon.background as GradientDrawable).setColor((s.kind.color and 0xffffff) or 0x2e000000)
            iconGlyph.set(s.kind,if(s.kind.color==0xff8e8e94.toInt())(if(a.light)0xff6e6e74.toInt() else 0xffc8c8cc.toInt()) else s.kind.color,!first&&!reduced);return}
        sub.text=s.sub;sub.visibility=if(s.sub.isBlank())View.GONE else View.VISIBLE
        button.text=s.action.orEmpty();button.visibility=if(s.action==null)View.GONE else View.VISIBLE
        button.setOnClickListener{s.onAction?.invoke();dismiss()}
        covers.set(s.books,s.blank,s.faded,!first&&!reduced)
        dot.set(s.kind,s.kind.color,!reduced)
        bar.to(s.progress,!first&&!reduced)
    }

    /** First appearance: drops in from above and settles with a soft spring. */
    private fun show(s:State){
        Notice.current?.let{if(it!==this)it.dismiss(true)};Notice.current=this
        put(s,true)
        val top=a.appInsets.top+a.d(8)-pad
        host.addView(frame,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{topMargin=top;leftMargin=a.d(16)-pad;rightMargin=a.d(16)-pad})
        if(reduced){schedule();return}
        card.pivotY=0f;card.alpha=0f;card.translationY=-a.d(64).toFloat();card.scaleY=1.05f;card.scaleX=.97f
        ValueAnimator.ofFloat(0f,1f).apply{duration=620
            addUpdateListener{val t=it.animatedFraction;val sp=1f-exp(-6.2f*t)*cos(7.4f*t)        // a damped spring: one soft settle
                card.translationY=-a.d(64)*(1f-sp);card.alpha=(t*3.2f).coerceAtMost(1f);card.scaleY=1f+.05f*(1f-sp);card.scaleX=.97f+.03f*min(1f,sp)}
            start()}
        schedule()
    }

    /** Later states: the old words dissolve while the new ones rise in their place (no empty card in between), the cover
     *  fades in over the blank one, the dot pops into its sign and the card eases to its new height. */
    fun update(s:State){if(leaving)return
        val old=state;if(old==null){show(s);return}
        if(reduced){put(s,false);schedule();if(s.kind==Kind.ERROR)shake();return}
        val sameText=old.title==s.title&&old.sub==s.sub&&old.action==s.action
        if(sameText||card.width==0){put(s,false);schedule();return}
        // a picture of the old words stays on top and dissolves upward
        val ghosts=listOf<View>(texts,button).filter{it.visibility==View.VISIBLE&&it.width>0&&it.height>0}.map{v->
            val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
            val loc=IntArray(2);val base=IntArray(2);v.getLocationInWindow(loc);card.getLocationInWindow(base)
            android.graphics.drawable.BitmapDrawable(a.resources,b).apply{setBounds(loc[0]-base[0],loc[1]-base[1],loc[0]-base[0]+v.width,loc[1]-base[1]+v.height)}}
        ghosts.forEach{card.overlay.add(it)};val tops=ghosts.map{it.bounds.top}
        val h0=card.height
        put(s,false)
        row.measure(View.MeasureSpec.makeMeasureSpec(card.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val h1=row.measuredHeight
        listOf<View>(texts,button).forEachIndexed{i,v->v.animate().cancel();v.alpha=0f;v.translationY=a.d(6).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(60L+i*50L).setDuration(300).setInterpolator(smooth).start()}
        ValueAnimator.ofFloat(0f,1f).apply{duration=220;interpolator=smooth
            addUpdateListener{val t=it.animatedValue as Float;ghosts.forEachIndexed{k,g->g.alpha=((1f-t)*255).roundToInt();val b=g.bounds;val top=tops[k]-(a.d(6)*t).roundToInt();g.setBounds(b.left,top,b.right,top+b.height())}}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){ghosts.forEach{card.overlay.remove(it);(it as android.graphics.drawable.BitmapDrawable).bitmap.recycle()}}});start()}
        if(h1!=h0&&h0>0){card.layoutParams=card.layoutParams.apply{height=h0}
            ValueAnimator.ofFloat(0f,1f).apply{duration=340;interpolator=PathInterpolator(.3f,0f,.1f,1f)
                addUpdateListener{val t=it.animatedValue as Float;card.layoutParams=card.layoutParams.apply{height=(h0+(h1-h0)*t).roundToInt()}}
                addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){card.layoutParams=card.layoutParams.apply{height=FrameLayout.LayoutParams.WRAP_CONTENT}}});start()}}
        if(s.kind==Kind.ERROR||s.kind==Kind.PARTIAL)card.postDelayed({shake()},260)
        schedule()
    }
    fun setProgress(text:String,subtitle:String,fraction:Float?){val s=state?:return
        if(s.title!=text||s.sub!=subtitle)update(State(s.kind,text,subtitle,s.books,s.blank,s.action,s.onAction,fraction)) else{state=State(s.kind,text,subtitle,s.books,s.blank,s.action,s.onAction,fraction);bar.to(fraction,!reduced)}}

    private fun stay(k:Kind)=when(k){Kind.LOADING->0L;Kind.ERROR,Kind.PARTIAL,Kind.KNOWN->4500L;Kind.DELETED->5000L;else->if(compact)1900L else 2600L}
    private fun schedule(){card.removeCallbacks(hide);val ms=stay(state?.kind?:return);if(ms>0)card.postDelayed(hide,ms)}

    /** One short «no» of the head. */
    private fun shake(){if(reduced)return
        ValueAnimator.ofFloat(0f,1f).apply{duration=420;addUpdateListener{val t=it.animatedFraction;card.translationX=a.d(7)*sin(t*PI.toFloat()*4f)*(1f-t)};start()}}

    fun dismiss(fast:Boolean=false){if(leaving)return;leaving=true;card.removeCallbacks(hide);if(Notice.current===this)Notice.current=null
        val gone:()->Unit={(frame.parent as? ViewGroup)?.removeView(frame);covers.stop();onGone?.invoke()}
        if(reduced){gone();return}
        card.animate().cancel()
        // Leaves the way it came: slides up past the top edge, fading only near the end, so it never sits half-seen over the page.
        val from=card.translationY;val to=-(frame.top+pad+card.height+a.d(12)).toFloat()
        ValueAnimator.ofFloat(0f,1f).apply{duration=if(fast)220 else 320;interpolator=PathInterpolator(.5f,0f,.9f,.6f)
            addUpdateListener{val t=it.animatedValue as Float;card.translationY=from+(to-from)*t;card.alpha=(1f-((t-.55f)/.45f).coerceIn(0f,1f))}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){gone()}});start()}}

    /** Flick it up to dismiss; a finger on it holds it; dragged down it resists and springs back. */
    private fun swipe(){var y0=0f;var dragging=false;var t0=0L
        card.setOnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{y0=e.rawY;dragging=false;t0=e.eventTime;card.removeCallbacks(hide);false}
            MotionEvent.ACTION_MOVE->{val dy=e.rawY-y0;if(!dragging&&abs(dy)>a.d(6)){dragging=true;card.parent.requestDisallowInterceptTouchEvent(true)}
                if(dragging)card.translationY=if(dy<0)dy else dy*.18f;dragging}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val dy=e.rawY-y0;val fast=dy<0&&abs(dy)/max(1L,e.eventTime-t0)>.5f
                if(dragging&&(dy< -a.d(28)||fast))dismiss(true)
                else{if(dragging)card.animate().translationY(0f).setDuration(260).setInterpolator(OvershootInterpolator(1.2f)).start();schedule()}
                dragging}
            else->false}}
    }

    companion object{
        var current:Notice?=null
        fun card(a:MainActivity,s:State)=Notice(a,false).also{it.show(s)}
        fun short(a:MainActivity,kind:Kind,text:String)=Notice(a,true).also{it.show(State(kind,text))}
    }

    /** The cover (or two or three fanned to the right), a blank one with a sweeping light while the book is read. */
    private class CoverSlot(private val a:MainActivity):FrameLayout(a){
        private var sweep:ValueAnimator?=null
        private val blank=Blank(a)
        init{clipChildren=false}
        fun set(books:List<JSONObject>,showBlank:Boolean,faded:Boolean,animate:Boolean){
            val had=childCount>0&&getChildAt(childCount-1) !is Blank
            removeAllViews()
            val list=books.take(3)
            val w=a.d(32)+if(list.size>1)a.d(6)*(list.size-1) else 0
            layoutParams=layoutParams?.apply{width=w}
            if(list.isEmpty()||showBlank){addView(blank,LayoutParams(a.d(32),a.d(48)));blank.faded=faded;if(!showBlank||faded)blank.stop() else blank.start();return}
            blank.stop()
            val under=animate&&!had&&blank.parent==null&&!faded
            list.indices.reversed().forEach{i->val b=list[i]
                val v=CoverView(a,b,a.cover(b)).apply{softShadow=false;shadowFade=0f
                    outlineProvider=object:ViewOutlineProvider(){override fun getOutline(view:View,o:Outline){o.setRoundRect(0,0,view.width,view.height,a.d(4).toFloat())}};clipToOutline=true
                    elevation=a.d(1).toFloat()
                    if(faded)setLayerType(View.LAYER_TYPE_HARDWARE,Paint().apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})})
                    alpha=if(faded).55f else 1f-i*.12f}
                addView(v,LayoutParams(a.d(32),a.d(48)).apply{leftMargin=a.d(6)*i})
                if(animate&&!had){v.alpha=0f;v.scaleX=.94f;v.scaleY=.94f;v.animate().alpha(if(faded).55f else 1f-i*.12f).scaleX(1f).scaleY(1f).setStartDelay(i*60L).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
            // the blank stays underneath while the cover fades in over it, so the slot is never empty
            if(under){addView(blank,0,LayoutParams(a.d(32),a.d(48)));postDelayed({if(blank.parent===this)removeView(blank)},420)}
        }
        fun stop(){blank.stop()}
    }
    /** A blank cover: two quiet lines for a title, a soft light sweeping across while the file is being read. */
    private class Blank(private val a:MainActivity):View(a){
        var faded=false
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var t=-1f;private var loop:ValueAnimator?=null
        fun start(){if(loop!=null||a.prefs.str("motion")=="reduced")return;loop=ValueAnimator.ofFloat(0f,1f).apply{duration=1300;repeatCount=ValueAnimator.INFINITE;interpolator=PathInterpolator(.45f,0f,.55f,1f);addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
        fun stop(){loop?.cancel();loop=null;t=-1f;invalidate()}
        override fun onDetachedFromWindow(){stop();super.onDetachedFromWindow()}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=a.d(4).toFloat();val light=a.light
            p.shader=null;p.color=if(light)0xffe6e5e1.toInt() else 0xff323236.toInt();if(faded)p.alpha=150;c.drawRoundRect(0f,0f,w,h,r,r,p)
            p.color=if(light)0x1a000000 else 0x1fffffff;val lh=a.d(3).toFloat()
            c.drawRoundRect(w*.22f,h*.24f,w*.78f,h*.24f+lh,lh/2,lh/2,p);c.drawRoundRect(w*.22f,h*.34f,w*.6f,h*.34f+lh,lh/2,lh/2,p)
            if(t>=0f){val x=-w+t*w*3f;p.shader=LinearGradient(x,0f,x+w,h*.3f,intArrayOf(0,if(light)0x66ffffff else 0x24ffffff,0),floatArrayOf(0f,.5f,1f),Shader.TileMode.CLAMP)
                c.save();val path=Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)};c.clipPath(path);c.drawRect(0f,0f,w,h,p);c.restore();p.shader=null}}
    }
    /** The status dot: a spinner while working; on the result a coloured disc pops and its sign draws itself.
     *  [bare]: the sign alone (the compact capsule's icon, drawn on a tinted circle). */
    private class Dot(private val a:MainActivity,private val bare:Boolean):View(a){
        private var kind:Kind?=null;private var color=0
        private var pop=1f;private var ink=1f;private var spin=0f;private var spinner:ValueAnimator?=null
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
        private val path=Path();private val part=Path();private val measure=PathMeasure()
        fun set(k:Kind,c:Int,animate:Boolean){if(k==kind&&c==color)return;val was=kind;kind=k;color=c
            if(k==Kind.LOADING){pop=1f;ink=1f;startSpin();return}
            spinner?.cancel();spinner=null
            path.reset();glyph(k.glyph,path)
            if(!animate||(was==null&&bare)){pop=1f;ink=1f;invalidate();if(!animate)return}
            pop=if(bare)1f else 0f;ink=0f
            if(!bare)ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=OvershootInterpolator(2.4f);addUpdateListener{pop=it.animatedValue as Float;invalidate()};start()}
            ValueAnimator.ofFloat(0f,1f).apply{duration=320;startDelay=if(bare)60 else 150;interpolator=DecelerateInterpolator(1.4f);addUpdateListener{ink=it.animatedValue as Float;invalidate()};start()}}
        private fun startSpin(){if(spinner!=null)return;if(a.prefs.str("motion")=="reduced"){invalidate();return}
            spinner=ValueAnimator.ofFloat(0f,360f).apply{duration=900;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{spin=it.animatedValue as Float;invalidate()};start()}}
        override fun onDetachedFromWindow(){spinner?.cancel();spinner=null;super.onDetachedFromWindow()}
        override fun onDraw(c:Canvas){val k=kind?:return;val s=width/24f;c.save();c.scale(s,s)
            if(k==Kind.LOADING){p.style=Paint.Style.STROKE;p.strokeWidth=2.6f;p.color=(color and 0xffffff) or 0x40000000;c.drawCircle(12f,12f,8.5f,p)
                p.color=color;c.drawArc(3.5f,3.5f,20.5f,20.5f,spin-90f,100f,false,p);c.restore();return}
            if(!bare){p.style=Paint.Style.FILL;p.color=color;c.drawCircle(12f,12f,12f*pop,p)}
            if(ink>0f){p.style=Paint.Style.STROKE;p.strokeWidth=if(bare)2.1f else 3f;p.color=if(bare)color else Color.WHITE
                c.save();if(!bare){c.translate(12f,12f);c.scale(.62f,.62f);c.translate(-12f,-12f)}
                measure.setPath(path,false);part.reset();var total=0f;do{total+=measure.length}while(measure.nextContour())
                measure.setPath(path,false);var left=total*ink.coerceIn(0f,1f)
                do{val l=measure.length;if(left<=0f)break;measure.getSegment(0f,min(l,left),part,true);left-=l}while(measure.nextContour())
                c.drawPath(part,p);c.restore()}
            c.restore()}
        private fun glyph(n:String,p:Path){when(n){
            "check"->{p.moveTo(5.5f,12.5f);p.lineTo(9.7f,16.7f);p.lineTo(18.5f,8f)}
            "x"->{p.moveTo(7.5f,7.5f);p.lineTo(16.5f,16.5f);p.moveTo(16.5f,7.5f);p.lineTo(7.5f,16.5f)}
            "bang"->{p.moveTo(12f,6f);p.lineTo(12f,13.5f);p.moveTo(12f,17.6f);p.lineTo(12f,17.7f)}
            "book"->{p.moveTo(5f,5.5f);p.lineTo(10.5f,5.5f);p.quadTo(12.5f,5.5f,12.5f,7.5f);p.lineTo(12.5f,19f);p.quadTo(12.5f,17.4f,10.9f,17.4f);p.lineTo(5f,17.4f);p.close()
                p.moveTo(19f,5.5f);p.lineTo(13.5f,5.5f);p.quadTo(11.5f,5.5f,11.5f,7.5f);p.moveTo(19f,5.5f);p.lineTo(19f,17.4f);p.lineTo(13.1f,17.4f);p.quadTo(11.5f,17.4f,11.5f,19f)}
            "clip"->{p.moveTo(15.5f,7f);p.lineTo(8.7f,13.8f);p.quadTo(7.6f,15.4f,9.3f,16.6f);p.quadTo(10.6f,17.4f,11.9f,16.4f);p.lineTo(18.6f,9.7f);p.quadTo(20.3f,6.8f,17.8f,4.6f);p.quadTo(15.3f,2.7f,12.9f,4f);p.lineTo(5.5f,11.4f);p.quadTo(2.6f,15.6f,6.2f,19f);p.quadTo(9.9f,21.9f,13.5f,19.4f);p.lineTo(19.5f,13.4f)}
            "trash"->{p.moveTo(5f,7f);p.lineTo(19f,7f);p.moveTo(10f,7f);p.lineTo(10f,5.5f);p.lineTo(14f,5.5f);p.lineTo(14f,7f);p.moveTo(7f,7f);p.lineTo(7.8f,19f);p.lineTo(16.2f,19f);p.lineTo(17f,7f)}
            "copy"->{p.addRoundRect(9f,9f,19f,20f,2.4f,2.4f,Path.Direction.CW);p.moveTo(15f,9f);p.lineTo(15f,6.5f);p.quadTo(15f,4.5f,13f,4.5f);p.lineTo(6.5f,4.5f);p.quadTo(4.5f,4.5f,4.5f,6.5f);p.lineTo(4.5f,14f);p.quadTo(4.5f,16f,6.5f,16f);p.lineTo(9f,16f)}
            "info"->{p.moveTo(12f,11f);p.lineTo(12f,17f);p.moveTo(12f,7.2f);p.lineTo(12f,7.3f)}
            "shelf"->{p.moveTo(4f,19f);p.lineTo(20f,19f);p.moveTo(7f,19f);p.lineTo(7f,8f);p.moveTo(11f,19f);p.lineTo(11f,5f);p.moveTo(15.5f,19f);p.lineTo(13f,8f);p.lineTo(16f,7.3f);p.lineTo(18.5f,18.3f)}}}
    }
    /** A thin progress line along the card's foot, clipped by its rounded corners. */
    private class Bar(private val a:MainActivity):View(a){
        private var value=-1f;private var shown=0f;private var anim:ValueAnimator?=null
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun to(v:Float?,animate:Boolean){val target=v?:-1f;if(target==value)return;val from=if(value<0f)0f else value;value=target
            anim?.cancel();if(!animate||target<0f){shown=maxOf(0f,target);invalidate();return}
            anim=ValueAnimator.ofFloat(from,target).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
        override fun onDraw(c:Canvas){if(value<0f)return;val w=width.toFloat();val h=height.toFloat()
            p.color=if(a.light)0x0f000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,h/2,h/2,p);p.color=0xfff0a742.toInt();if(shown>0f)c.drawRoundRect(0f,0f,maxOf(h,w*shown),h,h/2,h/2,p)}
    }
}
