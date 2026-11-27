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
 * Approved notices «А. Капсула-остров»: a fully rounded dark capsule at the top. On the left a round badge (the book's
 * cover in a circle with a small coloured sign on its edge — trash, check, «!» — or a spinner while working), then the
 * title and a quiet second line (or a progress line), and where there is an obvious next step one capsule button.
 * Notices without a book are a compact capsule: a tinted round icon and the words.
 *
 * Motion, like the island: it is born as a small pill at the top edge, grows to its full size with a smooth settle,
 * and its contents fade in once there is room for them. A new state melts the old words into the new ones in place.
 * It leaves the same way back: the contents fade, the capsule shrinks into a pill and slips up. Flick it up to dismiss.
 * The capsule's shape (and so its shadow) is the animated outline: nothing is scaled, nothing is squeezed.
 */
internal class Notice private constructor(internal val a:MainActivity,private val compact:Boolean){
    enum class Kind(val color:Int,val glyph:String){
        LOADING(0xfff0a742.toInt(),""),OK(0xff34b866.toInt(),"check"),PARTIAL(0xfff0a742.toInt(),"bang"),ERROR(0xffef5b4c.toInt(),"x"),
        KNOWN(0xff6e6e74.toInt(),"book"),ATTACHED(0xff4cb0e5.toInt(),"clip"),DELETED(0xffef5b4c.toInt(),"trash"),
        COPIED(0xfff0a742.toInt(),"copy"),INFO(0xff8e8e94.toInt(),"info"),SHELF(0xff8e8e94.toInt(),"shelf"),FOUND(0xff5b8def.toInt(),"folder")}
    class State(val kind:Kind,val title:String,val sub:String="",val books:List<JSONObject> =emptyList(),val blank:Boolean=false,
                val action:String?=null,val onAction:(()->Unit)?=null,val progress:Float?=null,val faded:Boolean=false)

    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val smooth=PathInterpolator(.2f,0f,0f,1f)
    private val host=a.window.decorView as FrameLayout
    /** Room round the capsule for its shadow. */
    private val pad=a.d(24)
    private val surface=if(a.light)Color.WHITE else 0xff2c2c2f.toInt()
    private val frame=FrameLayout(a).apply{clipChildren=false;clipToPadding=false;setPadding(pad,pad,pad,pad)}
    /** How far the capsule has grown out of its pill (0 the pill, 1 full size). */
    private var grow=1f
    private val card=FrameLayout(a).apply{
        background=GradientDrawable().apply{setColor(surface);cornerRadius=a.d(40).toFloat();setStroke(maxOf(1,a.d(1)/2),Ui.fill(a.light))}
        elevation=a.d(10).toFloat();clipToOutline=true
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
            val w=v.width.toFloat();val h=v.height.toFloat();if(w<=0f||h<=0f){o.setRoundRect(0,0,0,0,0f);return}
            val pw=minOf(w,a.d(if(compact)72 else 104).toFloat());val ph=minOf(h,a.d(28).toFloat())
            val cw=pw+(w-pw)*grow;val ch=ph+(h-ph)*grow;val l=(w-cw)/2f
            o.setRoundRect(l.roundToInt(),0,(l+cw).roundToInt(),ch.roundToInt(),ch/2f)}}}
    private val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false
        if(compact)setPadding(a.d(8),0,a.d(18),0) else setPadding(a.d(8),0,a.d(8),0);minimumHeight=a.d(if(compact)48 else 60)}
    private val badge=Badge(a,surface)
    private val icon=FrameLayout(a)
    private val iconGlyph=Dot(a,true)
    private val title=a.text("",Type.TEXT,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val sub=a.text("",Type.SECONDARY,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val texts=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;clipChildren=false;clipToPadding=false}
    private val button=a.text("",Type.SECONDARY,a.bg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(15),0,a.d(15),0);maxLines=1;a.press(this)}
    private val bar=Bar(a)
    private var state:State?=null
    private val hide=Runnable{dismiss()}
    private var leaving=false
    private var motion:ValueAnimator?=null
    var onGone:(()->Unit)?=null

    init{
        if(compact){
            icon.background=GradientDrawable().apply{shape=GradientDrawable.OVAL}
            icon.addView(iconGlyph,FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))
            row.addView(icon,LinearLayout.LayoutParams(a.d(32),a.d(32)).apply{marginEnd=a.d(10)})
            row.addView(title,LinearLayout.LayoutParams(-2,-2))
        }else{
            row.addView(badge,LinearLayout.LayoutParams(a.d(44),a.d(44)).apply{marginEnd=a.d(12)})
            texts.addView(title,LinearLayout.LayoutParams(-1,-2))
            texts.addView(sub,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(2)})
            texts.addView(bar,LinearLayout.LayoutParams(-1,a.d(4)).apply{topMargin=a.d(8)})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(button,LinearLayout.LayoutParams(-2,a.d(36)).apply{marginStart=a.d(12)})
        }
        card.addView(row,FrameLayout.LayoutParams(if(compact)-2 else -1,-2))
        card.isClickable=true
        // A book notice keeps the full width all its life: only its height may change, so the words never get squeezed.
        frame.addView(card,FrameLayout.LayoutParams(if(compact)-2 else -1,-2,Gravity.CENTER_HORIZONTAL))
        swipe()
    }

    private fun put(s:State,first:Boolean){
        state=s;title.text=s.title
        if(compact){(icon.background as GradientDrawable).setColor((s.kind.color and 0xffffff) or 0x2e000000)
            iconGlyph.set(s.kind,if(s.kind.color==0xff8e8e94.toInt())(Ui.ink2(a.light)) else s.kind.color,!first&&!reduced);return}
        val busy=s.progress!=null
        sub.text=s.sub;sub.visibility=if(s.sub.isBlank()||busy)View.GONE else View.VISIBLE
        bar.visibility=if(busy)View.VISIBLE else View.GONE
        button.text=s.action.orEmpty();button.visibility=if(s.action==null)View.GONE else View.VISIBLE
        val quiet=s.kind==Kind.ERROR||s.kind==Kind.PARTIAL
        button.setTextColor(if(quiet)a.fg else a.bg)
        button.background=GradientDrawable().apply{setColor(if(quiet)(Ui.press(a.light)) else a.fg);cornerRadius=a.d(Radius.R20).toFloat()}
        row.setPadding(a.d(8),0,if(s.action==null)a.d(18) else a.d(8),0)
        button.setOnClickListener{s.onAction?.invoke();dismiss()}
        badge.set(s,!first&&!reduced)
        bar.to(s.progress,!first&&!reduced)
    }

    /** The capsule's shape and its contents for a moment of the island motion. */
    private fun pose(g:Float,content:Float){grow=g;card.invalidateOutline();row.alpha=content;row.translationY=a.d(3)*(1f-content)}

    /** First appearance: slides down from beyond the top edge of the display. */
    private fun show(s:State){
        Notice.current?.let{if(it!==this){if(it.a===a)it.dismiss(true) else it.dismissNow()}};Notice.current=this
        put(s,true)
        val top=a.appInsets.top+a.d(6)-pad
        host.addView(frame,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{topMargin=top;leftMargin=a.d(14)-pad;rightMargin=a.d(14)-pad})
        if(reduced){pose(1f,1f);schedule();return}
        // Like a banner on iOS: whole from the start, it slides in from above the screen's real top edge (not from
        // under the status bar or the camera cutout) and settles with a soft spring.
        // The whole frame moves (not the card inside it), so nothing clips it short of the display's edge.
        pose(1f,1f);card.alpha=1f;frame.translationY=-a.resources.displayMetrics.heightPixels.toFloat()
        card.addOnLayoutChangeListener(object:View.OnLayoutChangeListener{override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){
            if(r-l<=0||b-t<=0)return;v.removeOnLayoutChangeListener(this);if(leaving)return
            val from=offscreen();frame.translationY=from
            motion=ValueAnimator.ofFloat(from,0f).apply{duration=Motion.LONG;interpolator=Spring(.86f);addUpdateListener{frame.translationY=it.animatedValue as Float};start()}}})
        schedule()
    }

    /** Later states: the old words dissolve while the new ones rise in their place, the badge changes its sign,
     *  and the capsule eases to its new height. */
    fun update(s:State){if(leaving)return
        val old=state;if(old==null){show(s);return}
        if(reduced){put(s,false);schedule();if(s.kind==Kind.ERROR)shake();return}
        val sameText=old.title==s.title&&old.sub==s.sub&&old.action==s.action&&(old.progress==null)==(s.progress==null)
        if(sameText||card.width==0){put(s,false);schedule();return}
        val ghosts=listOf<View>(texts,button).filter{it.visibility==View.VISIBLE&&it.width>0&&it.height>0}.map{v->
            val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
            val loc=IntArray(2);val base=IntArray(2);v.getLocationInWindow(loc);card.getLocationInWindow(base)
            android.graphics.drawable.BitmapDrawable(a.resources,b).apply{setBounds(loc[0]-base[0],loc[1]-base[1],loc[0]-base[0]+v.width,loc[1]-base[1]+v.height)}}
        ghosts.forEach{card.overlay.add(it)};val tops=ghosts.map{it.bounds.top}
        val h0=card.height
        put(s,false)
        row.measure(View.MeasureSpec.makeMeasureSpec(card.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val h1=row.measuredHeight
        listOf<View>(texts,button).forEachIndexed{i,v->v.animate().cancel();v.alpha=0f;v.translationY=a.d(5).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(90L+i*40L).setDuration(Motion.STANDARD).setInterpolator(smooth).start()}
        ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.FAST;interpolator=smooth
            addUpdateListener{val t=it.animatedValue as Float;ghosts.forEachIndexed{k,g->g.alpha=((1f-t)*255).roundToInt();val b=g.bounds;val top=tops[k]-(a.d(5)*t).roundToInt();g.setBounds(b.left,top,b.right,top+b.height())}}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){ghosts.forEach{card.overlay.remove(it);(it as android.graphics.drawable.BitmapDrawable).bitmap.recycle()}}});start()}
        if(h1!=h0&&h0>0){card.layoutParams=card.layoutParams.apply{height=h0}
            ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.EMPHASIS;interpolator=PathInterpolator(.3f,0f,.1f,1f)
                addUpdateListener{val t=it.animatedValue as Float;card.layoutParams=card.layoutParams.apply{height=(h0+(h1-h0)*t).roundToInt()};card.invalidateOutline()}
                addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){card.layoutParams=card.layoutParams.apply{height=FrameLayout.LayoutParams.WRAP_CONTENT}}});start()}}
        if(s.kind==Kind.ERROR||s.kind==Kind.PARTIAL)card.postDelayed({shake()},260)
        schedule()
    }
    fun setProgress(text:String,subtitle:String,fraction:Float?){val s=state?:return
        if(s.title!=text)update(State(s.kind,text,subtitle,s.books,s.blank,s.action,s.onAction,fraction)) else{state=State(s.kind,text,subtitle,s.books,s.blank,s.action,s.onAction,fraction);bar.to(fraction,!reduced)}}

    private fun stay(k:Kind)=when(k){Kind.LOADING->0L;Kind.ERROR,Kind.PARTIAL,Kind.KNOWN->4500L;Kind.DELETED->5000L;Kind.FOUND->7000L;else->if(compact)1900L else 2600L}
    private fun schedule(){card.removeCallbacks(hide);val ms=stay(state?.kind?:return);if(ms>0)card.postDelayed(hide,ms)}

    /** One short «no» of the head. */
    private fun shake(){if(reduced)return
        ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.EMPHASIS;addUpdateListener{val t=it.animatedFraction;card.translationX=a.d(6)*sin(t*PI.toFloat()*4f)*(1f-t)};start()}}

    /** Gone at once, no motion (its screen is closing); its pending action (an undoable delete) still completes. */
    fun dismissNow(){if(leaving)return;leaving=true;card.removeCallbacks(hide);if(Notice.current===this)Notice.current=null
        motion?.cancel();card.animate().cancel();(frame.parent as? ViewGroup)?.removeView(frame);badge.stop();onGone?.invoke()}
    /** How far up the capsule must go to be fully past the top edge of the display, shadow included. */
    private fun offscreen():Float=-(frame.top+pad+card.height+card.translationY+a.d(20))
    /** A critically-near damped spring (as iOS banners move): [damping] below 1 gives a barely visible settle. */
    private class Spring(private val damping:Float):android.animation.TimeInterpolator{
        override fun getInterpolation(t:Float):Float{if(t>=1f)return 1f;val w=11f;val z=damping;val wd=w*sqrt(1f-z*z)
            return 1f-exp(-z*w*t)*(cos(wd*t)+z*w/wd*sin(wd*t))}}
    /** Leaves the way it came: up past the top edge of the display. */
    fun dismiss(fast:Boolean=false){if(a.isFinishing||a.isDestroyed){dismissNow();return};if(leaving)return;leaving=true;card.removeCallbacks(hide);if(Notice.current===this)Notice.current=null
        val gone:()->Unit={(frame.parent as? ViewGroup)?.removeView(frame);badge.stop();onGone?.invoke()}
        if(reduced){gone();return}
        motion?.cancel();card.animate().cancel();frame.animate().cancel()
        // back up past the top edge of the display, the way it came
        val y0=frame.translationY;val to=offscreen()
        motion=ValueAnimator.ofFloat(0f,1f).apply{duration=if(fast)260 else 360;interpolator=PathInterpolator(.4f,0f,.9f,.6f)
            addUpdateListener{frame.translationY=y0+(to-y0)*(it.animatedValue as Float)}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){gone()}});start()}}

    /** Flick it up to dismiss; a finger on it holds it; dragged down it resists and eases back. */
    private fun swipe(){var y0=0f;var dragging=false;var t0=0L;var base=0f
        card.setOnTouchListener{_,e->when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{y0=e.rawY;dragging=false;t0=e.eventTime;card.removeCallbacks(hide);if(!leaving){motion?.cancel();frame.animate().cancel()};base=frame.translationY;false}
            MotionEvent.ACTION_MOVE->{val dy=e.rawY-y0;if(!dragging&&abs(dy)>a.d(6)){dragging=true;card.parent.requestDisallowInterceptTouchEvent(true)}
                if(dragging)frame.translationY=base+if(dy<0)dy else dy*.18f;dragging}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val dy=e.rawY-y0;val fast=dy<0&&abs(dy)/max(1L,e.eventTime-t0)>.5f
                if(dragging&&(dy< -a.d(28)||fast))dismiss(true)
                else{if(dragging||frame.translationY!=0f)frame.animate().translationY(0f).setDuration(Motion.STANDARD).setInterpolator(smooth).start();schedule()}
                dragging}
            else->false}}
    }

    companion object{
        var current:Notice?=null
        fun card(a:MainActivity,s:State)=Notice(a,false).also{it.show(s)}
        fun short(a:MainActivity,kind:Kind,text:String)=Notice(a,true).also{it.show(State(kind,text))}
    }

    /** The round badge: the book's cover in a circle (grey and faded for a deleted book) with the status sign on its
     *  edge; a spinner on a plain disc while working; the sign alone on a tinted disc when there is no book. */
    private class Badge(private val a:MainActivity,private val surface:Int):FrameLayout(a){
        private val disc=View(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Ui.press(a.light))}}
        private val covers=FrameLayout(a).apply{outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setOval(0,0,v.width,v.height)}};clipToOutline=true}
        private val spinner=Dot(a,true)
        private val sign=Dot(a,false)
        private val plain=Dot(a,true)
        private val ring=View(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(surface)}}
        private var bookId:String?=null
        init{clipChildren=false;clipToPadding=false
            addView(disc,LayoutParams(-1,-1));addView(covers,LayoutParams(-1,-1))
            addView(spinner,LayoutParams(a.d(24),a.d(24),Gravity.CENTER))
            addView(plain,LayoutParams(a.d(18),a.d(18),Gravity.CENTER))
            addView(ring,LayoutParams(a.d(26),a.d(26),Gravity.END or Gravity.BOTTOM).apply{rightMargin=-a.d(5);bottomMargin=-a.d(5)})
            // the sign's box leaves 5dp round its 18dp disc for the pop
            addView(sign,LayoutParams(a.d(30),a.d(30),Gravity.END or Gravity.BOTTOM).apply{rightMargin=-a.d(7);bottomMargin=-a.d(7)})}
        fun set(s:State,animate:Boolean){
            val book=s.books.firstOrNull()?.takeIf{!s.blank}
            val loading=s.kind==Kind.LOADING
            spinner.visibility=if(loading&&book==null)View.VISIBLE else View.GONE
            if(loading&&book==null)spinner.set(Kind.LOADING,a.fg,animate)
            val id=book?.str("id")
            if(id!=bookId){bookId=id;covers.removeAllViews()
                if(book!=null){val v=CoverView(a,book,a.cover(book)).apply{shadowFade=0f
                        if(s.faded)setLayerType(View.LAYER_TYPE_HARDWARE,Paint().apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(0f)})})}
                    // the cover fills the circle (cropped to its middle)
                    covers.addView(v,LayoutParams(a.d(44),a.d(66),Gravity.CENTER))
                    if(animate){covers.alpha=0f;covers.animate().alpha(if(s.faded).6f else 1f).setDuration(Motion.STANDARD).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()} else covers.alpha=if(s.faded).6f else 1f}}
            else if(book!=null)covers.alpha=if(s.faded).6f else 1f
            val hasBook=book!=null
            // with a book: the sign sits on the edge; without one: the sign alone, large, on a tinted disc
            val alone=!hasBook&&!loading
            (disc.background as GradientDrawable).setColor(if(alone)(s.kind.color and 0xffffff) or 0x26000000 else Ui.press(a.light))
            plain.visibility=if(alone)View.VISIBLE else View.GONE
            if(alone)plain.set(s.kind,s.kind.color,animate)
            val onEdge=hasBook&&!loading
            ring.visibility=if(onEdge)View.VISIBLE else View.GONE
            sign.visibility=if(onEdge)View.VISIBLE else View.GONE
            if(onEdge)sign.set(s.kind,s.kind.color,animate)
        }
        fun stop(){}
    }
    /** The status dot: a spinner while working; on the result a coloured disc pops and its sign draws itself.
     *  [bare]: the sign alone (the compact capsule's icon, drawn on a tinted circle). */
    private class Dot(private val a:MainActivity,private val bare:Boolean):View(a){
        /** Room round the drawing: 5dp for the status dot, so its 18dp disc may grow by half while it pops. */
        private val inset=if(bare)0f else a.d(5).toFloat()
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
            if(!bare)ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.EMPHASIS;interpolator=OvershootInterpolator(2.4f);addUpdateListener{pop=it.animatedValue as Float;invalidate()};start()}
            ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.STANDARD;startDelay=if(bare)60 else 150;interpolator=DecelerateInterpolator(1.4f);addUpdateListener{ink=it.animatedValue as Float;invalidate()};start()}}
        private fun startSpin(){if(spinner!=null)return;if(a.prefs.str("motion")=="reduced"){invalidate();return}
            spinner=ValueAnimator.ofFloat(0f,360f).apply{duration=900;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{spin=it.animatedValue as Float;invalidate()};start()}}
        override fun onDetachedFromWindow(){spinner?.cancel();spinner=null;super.onDetachedFromWindow()}
        override fun onDraw(c:Canvas){val k=kind?:return;val s=(width-2*inset)/24f;c.save();c.translate(inset,inset);c.scale(s,s)
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
            "folder"->{p.moveTo(5f,16.8f);p.lineTo(5f,7.6f);p.quadTo(5f,6.2f,6.4f,6.2f);p.lineTo(9.6f,6.2f);p.lineTo(11.2f,8f);p.lineTo(17.6f,8f);p.quadTo(19f,8f,19f,9.4f);p.lineTo(19f,16.8f);p.quadTo(19f,18.2f,17.6f,18.2f);p.lineTo(6.4f,18.2f);p.quadTo(5f,18.2f,5f,16.8f)}
            "shelf"->{p.moveTo(4f,19f);p.lineTo(20f,19f);p.moveTo(7f,19f);p.lineTo(7f,8f);p.moveTo(11f,19f);p.lineTo(11f,5f);p.moveTo(15.5f,19f);p.lineTo(13f,8f);p.lineTo(16f,7.3f);p.lineTo(18.5f,18.3f)}}}
    }
    /** A thin progress line along the card's foot, clipped by its rounded corners. */
    private class Bar(private val a:MainActivity):View(a){
        private var value=-1f;private var shown=0f;private var anim:ValueAnimator?=null
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        fun to(v:Float?,animate:Boolean){val target=v?:-1f;if(target==value)return;val from=if(value<0f)0f else value;value=target
            anim?.cancel();if(!animate||target<0f){shown=maxOf(0f,target);invalidate();return}
            anim=ValueAnimator.ofFloat(from,target).apply{duration=Motion.STANDARD;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
        override fun onDraw(c:Canvas){if(value<0f)return;val w=width.toFloat();val h=height.toFloat()
            p.color=Ui.fill(a.light);c.drawRoundRect(0f,0f,w,h,h/2,h/2,p);p.color=a.fg;if(shown>0f)c.drawRoundRect(0f,0f,maxOf(h,w*shown),h,h/2,h/2,p)}
    }
}
