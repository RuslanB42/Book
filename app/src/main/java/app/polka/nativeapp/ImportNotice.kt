package app.polka.nativeapp
import android.view.Gravity
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.*

/**
 * Import progress. While loading, five spines rise on a little shelf; on an empty library it appears right where the
 * floating covers were (they part to make room). When the books are in, the spines gather into one, the caption turns
 * into the result and the notice leaves while the new covers settle into the grid.
 */
class ImportNotice(val a:MainActivity,label:String,loading:Boolean=true) {
    private val covers=a.floatingCovers?.takeIf{loading&&it.isAttachedToWindow&&it.isShown}
    private val centered=covers!=null
    val row=(if(centered)a.column().apply{gravity=Gravity.CENTER_HORIZONTAL} else a.line().apply{gravity=Gravity.CENTER_VERTICAL}).apply{
        if(!centered){setPadding(a.d(16),a.d(12),a.d(18),a.d(12));background=a.controlSurface(this,18f,sheet=true);elevation=a.d(12).toFloat()}}
    private val spines=if(loading)SpineLoader(a) else null
    val caption=a.text(label,if(centered)15f else 14f,a.fg,centered)
    private val detail=a.text("",13f,a.muted).apply{visibility=View.GONE}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    /** Lives above the page (in the window frame), so rebuilding the library underneath never cuts it off. */
    private val host=a.window.decorView as FrameLayout
    init {
        spines?.let{if(centered){row.addView(it);a.space(row,18)}else{it.scaleX=.42f;it.scaleY=.42f;row.addView(FrameLayout(a).apply{clipChildren=false;addView(it,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))},LinearLayout.LayoutParams(a.d(56),a.d(34)).apply{marginEnd=a.d(8)})}}
        val texts=if(centered)row else a.column().also{row.addView(it)}
        texts.addView(caption);if(centered){caption.gravity=Gravity.CENTER;a.space(texts,4)};texts.addView(detail)
        if(centered){val c=covers!!;val at=IntArray(2);val rootAt=IntArray(2);c.getLocationInWindow(at);host.getLocationInWindow(rootAt)
            host.addView(row,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{topMargin=at[1]-rootAt[1]+c.height/2-a.d(60)})
            if(reduced)c.scatter=1f else android.animation.ValueAnimator.ofFloat(c.scatter,1f).apply{duration=560;interpolator=PathInterpolator(.3f,0f,.2f,1f);addUpdateListener{c.scatter=it.animatedValue as Float};start()}}
        // At the top, clear of the status bar and well away from the bottom navigation.
        else host.addView(row,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=a.appInsets.top+a.d(10) })
        row.elevation=a.d(30).toFloat()
        row.alpha=0f;if(!centered)row.translationY=-a.d(24).toFloat();row.animate().alpha(1f).translationY(0f).setStartDelay(if(centered)180L else 0L).setDuration(if(centered)360L else 260L).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
    }
    fun setMessage(value:String) { caption.text=value }
    fun setDetail(value:String) { detail.text=value; detail.visibility=if(value.isBlank())View.GONE else View.VISIBLE }
    /** Spines gather into one book, then [then] runs (the library renders under the notice) and the notice leaves with [result]. */
    fun finish(result:String,then:()->Unit) {
        val s=spines ?: run{then();dismiss();return}
        s.merge {
            then()
            caption.text=result;detail.visibility=View.GONE
            if(centered){row.animate().alpha(0f).translationY(-a.d(14).toFloat()).setStartDelay(260).setDuration(320).withEndAction{(row.parent as? android.view.ViewGroup)?.removeView(row)}.start()}
            else row.postDelayed({dismiss()},1700)
        }
    }
    fun dismiss() { covers?.let{c->if(c.scatter>0f&&c.isAttachedToWindow)android.animation.ValueAnimator.ofFloat(c.scatter,0f).apply{duration=420;addUpdateListener{c.scatter=it.animatedValue as Float};start()}}
        if(reduced)(row.parent as? android.view.ViewGroup)?.removeView(row)else row.animate().alpha(0f).translationY(if(centered)a.d(8).toFloat() else -a.d(20).toFloat()).setDuration(220).withEndAction{(row.parent as? android.view.ViewGroup)?.removeView(row)}.start() }
}
