package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.*

/** «Серия продлена»: drops in from the top while reading, the flame pops with a soft flare, then it lifts away. */
internal class StreakToast(private val a:MainActivity,streak:Int):FrameLayout(a){
    private val flare=object:View(a){
        val p=Paint(Paint.ANTI_ALIAS_FLAG);var t=0f
        override fun onDraw(c:Canvas){if(t<=0f||t>=1f)return;val r=width/2f*(.45f+.75f*t);p.shader=RadialGradient(width/2f,height/2f,r.coerceAtLeast(1f),intArrayOf(0x66ff8a1f,0x00ff8a1f),null,Shader.TileMode.CLAMP);p.alpha=(255*(1f-t)).toInt();c.drawCircle(width/2f,height/2f,r,p)}
    }
    private val flame=FlameView(a,streak,true,false,false)
    private val card=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private var gone=false
    init{
        clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription="Серия продлена: $streak ${days(streak)} подряд"
        a.floatCard(card,26,if(a.light)Color.WHITE else 0xff2a2a2d.toInt(),if(a.light)12 else 14)
        card.setPadding(a.d(10),a.d(8),a.d(20),a.d(8))
        val flameBox=FrameLayout(a).apply{clipChildren=false}
        flameBox.addView(flare,LayoutParams(a.d(64),a.d(64),Gravity.CENTER))
        flameBox.addView(flame,LayoutParams(a.d(34),a.d(42),Gravity.CENTER))
        card.addView(flameBox,LinearLayout.LayoutParams(a.d(44),a.d(44)))
        val texts=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(a.d(8),0,0,0)}
        texts.addView(a.text("Серия продлена",15f,a.fg,true))
        texts.addView(a.text("$streak ${days(streak)} подряд",13f,a.muted))
        card.addView(texts)
        addView(card,LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL))
        card.setOnClickListener{leave()}
    }
    private fun days(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    fun show(){
        if(a.prefs.optBoolean("haptics",true))performHapticFeedback(if(android.os.Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
        if(a.prefs.str("motion")=="reduced"){card.alpha=0f;card.animate().alpha(1f).setDuration(180).start();postDelayed({leave()},2600);return}
        card.alpha=0f;card.translationY=-a.d(72).toFloat();card.scaleX=.9f;card.scaleY=.9f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(OvershootInterpolator(1.1f)).start()
        flame.scaleX=.3f;flame.scaleY=.3f;flame.pivotY=a.d(38).toFloat()
        flame.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(520).setInterpolator(OvershootInterpolator(2.6f)).start()
        ValueAnimator.ofFloat(0f,1f).apply{startDelay=200;duration=700;interpolator=DecelerateInterpolator();addUpdateListener{flare.t=it.animatedValue as Float;flare.invalidate()};start()}
        postDelayed({leave()},2800)
    }
    fun leave(){
        if(gone)return;gone=true
        if(a.prefs.str("motion")=="reduced"){(parent as? ViewGroup)?.removeView(this);return}
        card.animate().cancel();card.animate().alpha(0f).translationY(-a.d(40).toFloat()).scaleX(.96f).scaleY(.96f).setDuration(260).setInterpolator(AccelerateInterpolator(1.4f)).withEndAction{(parent as? ViewGroup)?.removeView(this)}.start()
    }
}
