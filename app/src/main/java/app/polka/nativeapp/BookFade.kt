package app.polka.nativeapp

import android.graphics.Color
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Opening/closing a book is a soft fade through the app background. All heavy work (building the reader, rebuilding the
 *  library) happens only while the screen is fully dark, so neither fade ever has to share a frame with it. */
internal fun MainActivity.fadeVeil():View=View(this).apply{setBackgroundColor(bg);alpha=0f;isClickable=true;elevation=d(60).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
internal val fadeIn=PathInterpolator(.4f,0f,.2f,1f)
internal val fadeOut=PathInterpolator(.3f,0f,.2f,1f)

internal fun MainActivity.closeWithFade(r:NativeReader){
    val veil=fadeVeil();bookClosing=true;root.addView(veil,FrameLayout.LayoutParams(-1,-1))
    veil.animate().alpha(1f).setDuration(220).setInterpolator(fadeIn).withEndAction{
        (r.view.parent as? ViewGroup)?.removeView(r.view);bookClosing=false
        render(true,false)
        // Back to where the book was opened from: the book card reappears in place, already settled.
        val back=returnDetailId?.takeIf{it==r.book.str("id")};returnDetailId=null
        back?.let{id->books.firstOrNull{it.str("id")==id}?.let{b->BookDetailSheet(this,b,null).apply{instant=true}.show()}}
        (veil.parent as? ViewGroup)?.removeView(veil);root.addView(veil,FrameLayout.LayoutParams(-1,-1));finishBookReturn()
        // Reveal only after the rebuilt library has been laid out once.
        val observer=veil.viewTreeObserver
        observer.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
            if(observer.isAlive)observer.removeOnPreDrawListener(this)
            veil.postOnAnimation{veil.animate().alpha(0f).setDuration(320).setInterpolator(fadeOut).withEndAction{(veil.parent as? ViewGroup)?.removeView(veil)}.start()}
            return true}})
    }.start()
}
