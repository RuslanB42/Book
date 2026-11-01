package app.polka.nativeapp
import android.animation.*
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.animation.*
import android.widget.*

/**
 * Notices for adding, attaching and deleting books. A floating pill at the top: a coloured round badge (spines rising
 * while loading, a drawn check when done, a bin when deleted), a bold title and a quiet second line, a hairline edge
 * and a wide soft shadow. On an empty library the loader appears right where the floating covers were instead.
 */
class ImportNotice(val a:MainActivity,label:String,loading:Boolean=true,kind:String=if(loading)"loading" else "done",subtitle:String="") {
    private val covers=a.floatingCovers?.takeIf{loading&&it.isAttachedToWindow&&it.isShown}
    private val centered=covers!=null
    private val green=0xff34b866.toInt(); private val coral=0xffef5b4c.toInt(); private val amber=a.accentFill
    val row=(if(centered)a.column().apply{gravity=Gravity.CENTER_HORIZONTAL} else a.line().apply{gravity=Gravity.CENTER_VERTICAL}).apply{ if(!centered) a.noticeSurface(this) }
    private val spines=if(loading)SpineLoader(a) else null
    private val badge=FrameLayout(a).apply{clipChildren=false}
    private val disc=GradientDrawableCompat.circle(when(kind){"loading"->(amber and 0xffffff) or 0x2e000000;"deleted"->coral;else->green})
    private val check=CheckMark(a)
    val caption=a.text(label,if(centered)15f else 15f,a.fg,true)
    private val detail=a.text(subtitle,13f,a.muted).apply{visibility=if(subtitle.isBlank())View.GONE else View.VISIBLE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    /** Lives above the page (in the window frame), so rebuilding the library underneath never cuts it off. */
    private val host=a.window.decorView as FrameLayout
    init {
        if(centered){spines?.let{row.addView(it);a.space(row,18)};row.addView(caption);caption.gravity=Gravity.CENTER;a.space(row,4);row.addView(detail)
            val c=covers!!;val at=IntArray(2);val rootAt=IntArray(2);c.getLocationInWindow(at);host.getLocationInWindow(rootAt)
            host.addView(row,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{topMargin=at[1]-rootAt[1]+c.height/2-a.d(60)})
            if(reduced)c.scatter=1f else ValueAnimator.ofFloat(c.scatter,1f).apply{duration=560;interpolator=PathInterpolator(.3f,0f,.2f,1f);addUpdateListener{c.scatter=it.animatedValue as Float};start()}
            row.alpha=0f;row.animate().alpha(1f).setStartDelay(180L).setDuration(360L).start()
        } else {
            badge.background=disc;badge.elevation=a.d(3).toFloat()
            when(kind){
                "loading"->{val s=spines!!;s.scaleX=.34f;s.scaleY=.34f;badge.addView(s,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))}
                "deleted"->badge.addView(IconView(a,"trash",Color.WHITE),FrameLayout.LayoutParams(a.d(22),a.d(22),Gravity.CENTER))
                else->{badge.addView(check,FrameLayout.LayoutParams(a.d(22),a.d(22),Gravity.CENTER));check.progress=1f}
            }
            badge.addView(check.takeIf{kind=="loading"}?.apply{progress=0f}?:View(a),FrameLayout.LayoutParams(a.d(22),a.d(22),Gravity.CENTER))
            row.addView(badge,LinearLayout.LayoutParams(a.d(42),a.d(42)).apply{marginEnd=a.d(12)})
            val texts=a.column();texts.addView(caption);texts.addView(detail);row.addView(texts,LinearLayout.LayoutParams(-2,-2))
            host.addView(row,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=a.appInsets.top+a.d(12);leftMargin=a.d(20);rightMargin=a.d(20)})
            if(reduced){row.alpha=1f}else{
                row.alpha=0f;row.translationY=-a.d(36).toFloat();row.scaleX=.92f;row.scaleY=.92f
                row.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(OvershootInterpolator(1.15f)).start()
                badge.scaleX=.4f;badge.scaleY=.4f;badge.animate().scaleX(1f).scaleY(1f).setStartDelay(120).setDuration(480).setInterpolator(OvershootInterpolator(2.4f)).start()
                if(kind!="loading"){check.progress=0f;check.draw(220)}
            }
        }
    }
    fun setMessage(value:String) { caption.text=value }
    fun setDetail(value:String) { detail.text=value; detail.visibility=if(value.isBlank())View.GONE else View.VISIBLE }
    /** Spines gather into one book, the badge turns green and draws a check, [then] runs (the library renders under the notice), and the notice leaves. */
    fun finish(result:String,subtitle:String="",then:()->Unit) {
        val s=spines ?: run{then();dismiss();return}
        s.merge {
            then()
            caption.text=result;setDetail(subtitle)
            if(centered){row.animate().alpha(0f).translationY(-a.d(14).toFloat()).setStartDelay(260).setDuration(320).withEndAction{(row.parent as? android.view.ViewGroup)?.removeView(row)}.start();return@merge}
            if(reduced){s.visibility=View.INVISIBLE;disc.setColor(green);check.progress=1f}
            else{s.animate().alpha(0f).scaleX(.2f).scaleY(.2f).setDuration(180).start()
                ValueAnimator.ofObject(ArgbEvaluator(),(amber and 0xffffff) or 0x2e000000,green).apply{duration=260;addUpdateListener{disc.setColor(it.animatedValue as Int)};start()}
                badge.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction{badge.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(2f)).start()}.start()
                check.draw(320,120)}
            row.postDelayed({dismiss()},1900)
        }
    }
    fun dismiss() { covers?.let{c->if(c.scatter>0f&&c.isAttachedToWindow)ValueAnimator.ofFloat(c.scatter,0f).apply{duration=420;addUpdateListener{c.scatter=it.animatedValue as Float};start()}}
        if(reduced)(row.parent as? android.view.ViewGroup)?.removeView(row)else row.animate().alpha(0f).translationY(if(centered)a.d(8).toFloat() else -a.d(24).toFloat()).scaleX(.96f).scaleY(.96f).setDuration(240).setInterpolator(AccelerateInterpolator(1.3f)).withEndAction{(row.parent as? android.view.ViewGroup)?.removeView(row)}.start() }
}

internal object GradientDrawableCompat { fun circle(color:Int)=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(color)} }

/** White check that draws itself on. */
internal class CheckMark(private val a:MainActivity):View(a){
    var progress=1f; set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND;color=Color.WHITE}
    private val path=Path();private val part=Path();private val measure=PathMeasure()
    fun draw(ms:Long,delay:Long=0){ValueAnimator.ofFloat(0f,1f).apply{duration=ms;startDelay=delay;interpolator=DecelerateInterpolator();addUpdateListener{progress=it.animatedValue as Float};start()}}
    override fun onDraw(c:Canvas){if(progress<=0f)return;val w=width.toFloat();val h=height.toFloat();p.strokeWidth=a.d(2.6f)
        path.reset();path.moveTo(w*.2f,h*.53f);path.lineTo(w*.42f,h*.74f);path.lineTo(w*.82f,h*.3f)
        measure.setPath(path,false);part.reset();measure.getSegment(0f,measure.length*progress.coerceIn(0f,1f),part,true);c.drawPath(part,p)}
}
