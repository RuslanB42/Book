package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.*
import android.widget.*

/**
 * The day's goal is met while reading: a glass pill drops in (the flame pops, the number turns), then grows into a
 * full-screen celebration. The grey flame squashes into an ember, stretches into a droplet, bursts with a ring and
 * falling sparks and stands up as a living flame; the count turns over, the week capsule reaches today.
 */
internal class StreakCelebration(private val a:MainActivity,private val host:FrameLayout,private val streak:Int,private val week:BooleanArray,private val today:Int){
    private val reduced=a.prefs.str("motion")=="reduced"
    private val amber=a.accentFill
    private val overlay=FrameLayout(a)
    private val glow=BreathGlow(a,0xffff6e1e.toInt(),if(a.light).22f else .3f,false).apply{level=0f}
    private val burst=IgnitionBurst(a)
    private val sparks=a.lottie("streak_burst",-1)
    private val embers=a.lottie("streak_embers",-1).apply{repeatCount=com.airbnb.lottie.LottieDrawable.INFINITE}
    private val flame=FlameArt(a,0f)
    private val number=Odometer(a,"${streak-1}",84f,amber,true,true)
    private val caption=a.text(days(streak)+" подряд",17f,amber,true).apply{gravity=Gravity.CENTER}
    private val capsule=WeekCapsule(a,week,today)
    private val weekBox=a.column()
    private val message=a.text(message(),14.5f,a.muted).apply{gravity=Gravity.CENTER;cssLeading(1.35f)}
    private val go=a.text("Продолжить чтение",16f,if(a.light)Color.WHITE else 0xff161617.toInt(),true).apply{gravity=Gravity.CENTER;background=a.shape(amber,28);isClickable=true;isFocusable=true}
    private var pill:View?=null
    private var closed=false
    private fun days(n:Int)=if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}
    private fun message():String{
        val streakWeek=(0..today).all{week[it]}
        return when{streakWeek&&today==6->"Вся неделя без пропусков"
            streakWeek->{val left=6-today;"Ещё $left ${days(left)} — и неделя будет без пропусков"}
            else->"Загляните в книгу завтра, чтобы огонёк не погас"}
    }
    private fun later(ms:Long,f:()->Unit){overlay.postDelayed({if(!closed&&overlay.isAttachedToWindow)f()},ms)}
    private fun haptic(){if(a.prefs.optBoolean("haptics",true))overlay.performHapticFeedback(if(android.os.Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)}

    fun show(){
        build()
        if(reduced){flame.lit=1f;glow.level=1f;number.set("$streak");listOf(caption,weekBox,message).forEach{it.alpha=1f};go.translationY=0f
            overlay.alpha=0f;host.addView(overlay,FrameLayout.LayoutParams(-1,-1));overlay.animate().alpha(1f).setDuration(200).start();a.streakCelebration=this;haptic();return}
        dropPill()
    }
    private fun build(){
        overlay.setBackgroundColor(a.bg);overlay.isClickable=true;overlay.elevation=a.d(40).toFloat();overlay.clipChildren=false
        overlay.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;overlay.contentDescription="Серия продлена: $streak ${days(streak)} подряд"
        val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;clipChildren=false;clipToPadding=false}
        val flameBox=FrameLayout(a).apply{clipChildren=false}
        flameBox.addView(glow,FrameLayout.LayoutParams(a.d(440),a.d(440),Gravity.CENTER))
        // embers rising around the flame (Lottie, looped) and the ignition burst (Lottie, once)
        flameBox.addView(embers,FrameLayout.LayoutParams(a.d(220),a.d(308),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=-a.d(40)})
        flameBox.addView(sparks,FrameLayout.LayoutParams(a.d(400),a.d(400),Gravity.CENTER).apply{topMargin=-a.d(28)})
        embers.alpha=0f
        flameBox.addView(flame,FrameLayout.LayoutParams(a.d(112),a.d(140),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        burst.originY=.66f
        col.addView(flameBox,LinearLayout.LayoutParams(a.d(160),a.d(170)))
        a.space(col,10);col.addView(number,LinearLayout.LayoutParams(-2,-2));col.addView(caption,LinearLayout.LayoutParams(-2,-2))
        val names=a.line();listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEachIndexed{i,s->names.addView(a.text(s,11.5f,if(i==today)amber else a.muted,i==today).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))}
        weekBox.addView(names);a.space(weekBox,8);weekBox.addView(capsule,LinearLayout.LayoutParams(-1,-2))
        a.space(col,34);col.addView(weekBox,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=a.d(28);rightMargin=a.d(28)})
        a.space(col,20);col.addView(message,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=a.d(36);rightMargin=a.d(36)})
        val body=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
        body.addView(col,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply{bottomMargin=a.d(60)})
        body.addView(go,FrameLayout.LayoutParams(-1,a.d(52),Gravity.BOTTOM).apply{leftMargin=a.d(22);rightMargin=a.d(22);bottomMargin=a.d(28)})
        overlay.addView(body,FrameLayout.LayoutParams(-1,-1))
        a.followInsets(body){i->body.setPadding(i.left,i.top,i.right,i.bottom)}
        go.setOnClickListener{dismiss()};a.press(go)
        listOf(caption,weekBox,message).forEach{it.alpha=0f}
        go.translationY=a.d(140).toFloat()
        if(!reduced)capsule.prepareToday()
    }
    /** Small glass pill at the top of the page, then it opens into the whole screen. */
    private fun dropPill(){
        val card=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        a.floatCard(card,24,if(a.light)Color.WHITE else 0xff242426.toInt(),if(a.light)12 else 14)
        card.setPadding(a.d(10),a.d(8),a.d(20),a.d(8))
        val small=FlameArt(a,1f);card.addView(small,LinearLayout.LayoutParams(a.d(34),a.d(42)).apply{rightMargin=a.d(10)})
        val texts=a.column();texts.addView(a.text("Серия продлена",15f,a.fg,true))
        val line=a.line().apply{gravity=Gravity.CENTER_VERTICAL};val mini=Odometer(a,"${streak-1}",13f,amber,true);line.addView(mini);line.addView(a.text(" ${days(streak)} подряд",13f,a.muted));texts.addView(line)
        card.addView(texts)
        val holder=FrameLayout(a).apply{clipChildren=false;elevation=a.d(30).toFloat()}
        holder.addView(card,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL))
        host.addView(holder,FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{topMargin=a.d(10)});pill=holder
        card.alpha=0f;card.translationY=-a.d(72).toFloat();card.scaleX=.9f;card.scaleY=.9f
        card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(460).setInterpolator(OvershootInterpolator(1.1f)).start()
        small.scaleX=.3f;small.scaleY=.3f;small.animate().scaleX(1f).scaleY(1f).setStartDelay(160).setDuration(520).setInterpolator(OvershootInterpolator(2.6f)).start()
        mini.set("$streak","${streak-1}",delay=260)
        haptic();a.streakCelebration=this
        card.setOnClickListener{expand(card)}
        holder.postDelayed({if(!closed&&overlay.parent==null)expand(card)},1250)
    }
    /** The pill's rounded rectangle grows to the whole screen (a container transform), the scene plays inside. */
    private fun expand(card:View){
        if(overlay.parent!=null||closed)return
        host.addView(overlay,FrameLayout.LayoutParams(-1,-1))
        val hostAt=IntArray(2);val at=IntArray(2);host.getLocationInWindow(hostAt);card.getLocationInWindow(at)
        val from=RectF((at[0]-hostAt[0]).toFloat(),(at[1]-hostAt[1]).toFloat(),(at[0]-hostAt[0]+card.width).toFloat(),(at[1]-hostAt[1]+card.height).toFloat())
        var f=0f;val r0=card.height/2f
        overlay.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){val w=v.width.toFloat();val h=v.height.toFloat()
            o.setRoundRect((from.left*(1-f)).toInt(),(from.top*(1-f)).toInt(),(from.right+(w-from.right)*f).toInt(),(from.bottom+(h-from.bottom)*f).toInt(),r0+(a.d(28)-r0)*f)}}
        overlay.clipToOutline=true
        val body=overlay.getChildAt(0);body.alpha=0f
        ValueAnimator.ofFloat(0f,1f).apply{duration=560;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{f=it.animatedValue as Float;overlay.invalidateOutline();body.alpha=((f-.35f)/.65f).coerceIn(0f,1f)}
            addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){overlay.clipToOutline=false;overlay.outlineProvider=ViewOutlineProvider.BACKGROUND;pill?.let{host.removeView(it)};pill=null}});start()}
        later(760){ignite()}
    }
    private fun ignite(){
        // squash into an ember
        flame.animate().scaleX(1.3f).scaleY(.42f).setDuration(300).setInterpolator(PathInterpolator(.5f,0f,.75f,.3f)).withEndAction{
            flame.lit=1f
            // stretch into a droplet
            flame.animate().scaleX(.42f).scaleY(1.3f).setDuration(190).setInterpolator(PathInterpolator(.2f,0f,.2f,1f)).withEndAction{
                sparks.playAnimation();embers.playAnimation();embers.animate().alpha(1f).setStartDelay(250).setDuration(600).start();haptic()
                ValueAnimator.ofFloat(0f,1f).apply{duration=700;interpolator=DecelerateInterpolator();addUpdateListener{glow.level=it.animatedValue as Float};start()}
                flame.animate().scaleX(1f).scaleY(1f).setDuration(680).setInterpolator(OvershootInterpolator(2.4f)).withEndAction{if(!closed)flame.startFlicker()}.start()
                settle()
            }.start()
        }.start()
    }
    private fun settle(){
        number.set("$streak","${streak-1}",delay=60)
        later(320){rise(caption)}
        later(540){rise(weekBox)}
        later(760){capsule.growToday()}
        later(1080){message.animate().alpha(1f).setDuration(400).start()}
        later(1240){go.animate().translationY(0f).setDuration(560).setInterpolator(PathInterpolator(.2f,1.15f,.36f,1f)).start()}
    }
    private fun rise(v:View){v.translationY=a.d(10).toFloat();v.animate().alpha(1f).translationY(0f).setDuration(380).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
    fun dismiss(){
        if(closed)return;closed=true;a.streakCelebration=null;flame.stopFlicker()
        fun remove(){pill?.let{host.removeView(it)};host.removeView(overlay)}
        if(reduced){remove();return}
        pill?.animate()?.alpha(0f)?.setDuration(200)?.start()
        if(overlay.parent==null){overlay.postDelayed({remove()},220);return}
        overlay.animate().alpha(0f).setDuration(280).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{remove()}.start()
    }
}
