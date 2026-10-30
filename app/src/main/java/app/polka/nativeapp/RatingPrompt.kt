package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import kotlin.math.min

/**
 * Shown once, right after the last page: a quiet animated card with the cover and five stars.
 * Rating only, no text review. Tapping a star saves it at once and the card closes itself.
 */
internal class RatingPrompt(private val a:MainActivity,private val book:JSONObject,private val rated:(Int)->Unit):FrameLayout(a) {
    private val shade=View(a).apply{setBackgroundColor(0x8c000000.toInt())}
    private val card=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;clipChildren=false;clipToPadding=false}
    private val cover=CoverView(a,book,a.cover(book))
    private val heading=a.text("Книга дочитана",22f,bold=true,serif=true).apply{gravity=Gravity.CENTER}
    private val subtitle=a.text(book.str("title"),14f,a.muted).apply{gravity=Gravity.CENTER;maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val hint=a.text("Как вам книга?",16f).apply{gravity=Gravity.CENTER}
    private val stars=NativeRating(a,0,false){n->choose(n)}
    private val later=a.button("Не сейчас"){dismiss()}
    private var closing=false
    private val closeTask=Runnable{dismiss()}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    val isShowing get()=parent!=null&&!closing

    init {
        isClickable=true;isFocusable=true;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Оцените книгу"
        addView(shade,LayoutParams(-1,-1))
        shade.setOnClickListener{dismiss()}
        card.background=a.shape(if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt(),26)
        card.setPadding(a.d(24),a.d(28),a.d(24),a.d(16))
        card.addView(cover,LinearLayout.LayoutParams(a.d(84),a.d(126)));a.space(card,20)
        card.addView(heading,LinearLayout.LayoutParams(-1,-2));a.space(card,4)
        card.addView(subtitle,LinearLayout.LayoutParams(-1,-2));a.space(card,22)
        card.addView(hint,LinearLayout.LayoutParams(-1,-2));a.space(card,6)
        card.addView(stars,LinearLayout.LayoutParams(-1,a.d(56)));a.space(card,8)
        card.addView(later,LinearLayout.LayoutParams(-1,-2))
        val width=min(a.d(340),(a.resources.displayMetrics.widthPixels-a.d(48)).coerceAtLeast(a.d(240)))
        addView(card,LayoutParams(width,-2,Gravity.CENTER))
        card.isClickable=true // taps inside the card must not reach the dismiss layer
    }

    fun show() {
        a.root.addView(this,LayoutParams(-1,-1));requestFocus()
        if(reduced)return
        val settle=PathInterpolator(.22f,1f,.36f,1f)
        shade.alpha=0f;shade.animate().alpha(1f).setDuration(260).start()
        card.alpha=0f;card.translationY=a.d(40).toFloat();card.scaleX=.94f;card.scaleY=.94f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(settle).start()
        // The cover lands first, then the title, then the stars pop in one by one.
        cover.alpha=0f;cover.scaleX=.82f;cover.scaleY=.82f;cover.rotation=-4f
        cover.animate().alpha(1f).scaleX(1f).scaleY(1f).rotation(0f).setStartDelay(120).setDuration(560).setInterpolator(OvershootInterpolator(1.4f)).start()
        listOf(heading,subtitle,hint).forEachIndexed{i,v->v.alpha=0f;v.translationY=a.d(10).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(220L+i*70).setDuration(420).setInterpolator(settle).start()}
        for(i in 0 until stars.childCount){val s=stars.getChildAt(i);s.alpha=0f;s.scaleX=.3f;s.scaleY=.3f
            s.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(420L+i*70).setDuration(420).setInterpolator(OvershootInterpolator(2.2f)).start()}
        later.alpha=0f;later.animate().alpha(1f).setStartDelay(800).setDuration(360).start()
    }

    private fun choose(n:Int) {
        rated(n);removeCallbacks(closeTask)
        hint.text=when{n>=5->"Великолепно!";n==4->"Здорово, спасибо!";n==3->"Спасибо за оценку";else->"Спасибо, учтём"}
        if(!reduced)for(i in 0 until n){val s=stars.getChildAt(i)
            s.animate().cancel();s.animate().scaleX(1.28f).scaleY(1.28f).setStartDelay(i*70L).setDuration(140).withEndAction{s.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220).start()}.start()}
        postDelayed(closeTask,if(reduced)350L else 1000L)
    }

    fun dismiss() {
        if(closing)return;closing=true;removeCallbacks(closeTask)
        fun remove(){(parent as? ViewGroup)?.removeView(this);if(a.ratingPrompt===this)a.ratingPrompt=null}
        if(reduced||!isAttachedToWindow){remove();return}
        shade.animate().alpha(0f).setDuration(220).start()
        card.animate().alpha(0f).translationY(a.d(24).toFloat()).scaleX(.97f).scaleY(.97f).setDuration(220).withEndAction{remove()}.start()
    }

    override fun onDetachedFromWindow(){removeCallbacks(closeTask);if(a.ratingPrompt===this)a.ratingPrompt=null;super.onDetachedFromWindow()}
}
