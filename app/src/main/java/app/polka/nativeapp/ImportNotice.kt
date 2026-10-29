package app.polka.nativeapp
import android.view.Gravity
import android.view.View
import android.widget.*
class ImportNotice(val a:MainActivity,label:String,loading:Boolean=true) {
    val row=a.line().apply { setPadding(a.d(16),a.d(12),a.d(16),a.d(12)); background=a.controlSurface(this,18f,sheet=true); elevation=a.d(12).toFloat() }
    val caption=a.text(label,14f,a.fg)
    init { if(loading) row.addView(ProgressBar(a).apply { indeterminateTintList=android.content.res.ColorStateList.valueOf(a.fg) },LinearLayout.LayoutParams(a.d(20),a.d(20)).apply { marginEnd=a.d(10) }); row.addView(caption); a.root.addView(row,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin=a.d(90) }); row.alpha=0f; row.animate().alpha(1f).setDuration(180).start() }
    fun setMessage(value:String) { caption.text=value }
    fun dismiss() { if(a.prefs.str("motion")=="reduced")(row.parent as? android.view.ViewGroup)?.removeView(row)else row.animate().alpha(0f).translationY(a.d(8).toFloat()).setDuration(180).withEndAction{(row.parent as? android.view.ViewGroup)?.removeView(row)}.start() }
}
