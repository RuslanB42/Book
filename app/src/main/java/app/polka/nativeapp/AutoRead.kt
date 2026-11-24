package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.*
import android.widget.*
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Автолистание (approved, speed «А: шкала в паузе», a hairline to the next turn). Pages turn by themselves after the
 * time their own letters take at the reader's speed (pictures add a little); in the scroll the text glides up at that
 * speed. The speed starts from the reader's own (the statistics), is kept for each book, and follows the reader: a
 * page turned early by hand makes it faster, a page turned back makes it slower, a little at a time. A tap pauses and
 * shows the control (the speed on a dial, «Подстраиваться под меня»); another tap goes on. After long without a touch
 * it asks «Ещё читаете?»; with no answer it stops and offers the way back to the last page touched.
 */
internal class AutoRead(private val a:MainActivity,private val r:NativeReader){
    companion object{
        const val MIN=80;const val MAX=900;const val STEP=5
        private const val ASK_FOR=20_000L
        /** How long without a touch before «Ещё читаете?» (minutes; 0 never asks), chosen in the control. */
        private val ASK_CHOICES=listOf(10,20,30,45,60,0)
        private const val PICTURE_MS=3000L
        private fun perMs(wpm:Int)=wpm*ReadingTracker.CHARS_PER_WORD/60000.0
    }
    var on=false;private set
    private var running=false
    private var asking=false
    private var adapt=a.prefs.optBoolean("autoAdapt",true)
    private var wpm=(r.book.optInt("autoWpm").takeIf{it>0}?:r.readerWpm()).let{(it/STEP.toFloat()).roundToInt()*STEP}.coerceIn(MIN,MAX)
    private val now get()=SystemClock.uptimeMillis()
    private val ink get()=r.inkColor()
    private val page get()=r.backgroundColor
    private val surface get()=androidx.core.graphics.ColorUtils.blendARGB(page,ink,.08f)
    private val muted get()=(ink and 0xffffff) or (0x8c shl 24)

    // ── the page now (page mode) ──
    private var key="";private var lastPos=-1;private var furthest=-1;private var lastChars=0
    private var spent=0L;private var from=0L;private var due=1L
    private var selfTurn=false;private var turnSince=0L
    private fun spentNow()=spent+(if(running&&!asking)now-from else 0L)
    private fun dueFor(chars:Int,pictures:Int)=((chars/perMs(wpm)).toLong()+pictures*PICTURE_MS).coerceAtLeast(if(chars>0)1200L else PICTURE_MS)

    // ── the scroll ──
    private var vel=0f;private var acc=0f;private var lastFrame=0L;private var measured=0L;private var target=0f;private var holdUntil=0L;private var endSince=0L

    // ── touches ──
    private var lastTouch=0L;private var touchPoint:JSONObject?=null
    private var downX=0f;private var downY=0f;private var downAt=0L;private var tap=false;private var through=false;private var finger=false
    private val slop=ViewConfiguration.get(a).scaledTouchSlop

    private val tick=object:Choreographer.FrameCallback{override fun doFrame(t:Long){if(!on)return;step();Choreographer.getInstance().postFrameCallback(this)}}

    fun start(){if(on)return;on=true
        r.calmForAuto();line.visibility=if(r.paged())View.VISIBLE else View.GONE
        r.view.addView(line,FrameLayout.LayoutParams(-1,a.d(3),Gravity.BOTTOM))
        lastTouch=now;touchPoint=r.herePointNow();key="";lastPos=-1;furthest=-1
        resume();Choreographer.getInstance().postFrameCallback(tick)}
    fun stop(){if(!on)return;on=false;running=false;asking=false;keep(false)
        Choreographer.getInstance().removeFrameCallback(tick)
        listOfNotNull(line,panel,card).forEach{r.view.removeView(it)};panel=null;card=null;countdown?.cancel();countdown=null
        r.autoEnded()}
    private fun resume(){if(!on||running)return;hidePanel();running=true;from=now;vel=0f;acc=0f;lastFrame=0L;measured=0L;holdUntil=0L;keep(true)}
    private fun pause(show:Boolean=true){if(!running)return;spent=spentNow();running=false;keep(false);if(show)showPanel();line.invalidate()}
    /** The app went to the background: the reading waits, paused, with its control open. */
    fun appPaused(){pause()}
    /** A long press (to quote): the page holds still, quietly; a tap after the quote goes on. */
    fun held(){pause(false)}
    private fun keep(on:Boolean){if(on)a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}

    private fun step(){
        val askAfter=a.prefs.optInt("autoAsk",10)*60_000L
        if(running&&!asking&&askAfter>0&&now-lastTouch>askAfter){ask();return}
        if(!running||asking)return
        if(r.paged())turnStep() else scrollStep()}
    private fun turnStep(){if(key.isEmpty())pageShown();line.invalidate()
        if(spentNow()<due||r.autoBusy())return
        if(selfTurn&&now-turnSince<3000)return
        // the last page read through: on to the end of the book, as a turn by hand would
        if(r.atEnd()){stop();r.turn(1);return}
        selfTurn=true;turnSince=now;r.turn(1)}
    private fun scrollStep(){val t=now;val dt=(if(lastFrame==0L)16L else t-lastFrame).coerceIn(0L,50L);lastFrame=t
        if(finger||t<holdUntil||r.autoBusy()){acc=0f;vel=0f;return}
        if(t-measured>500){measured=t;val h=r.scroll.height.coerceAtLeast(1);val cpp=r.scrollCharsPerPx()
            // never faster than a screen in six seconds (pictures, empty lines)
            target=min(if(cpp>0f)(perMs(wpm)/cpp).toFloat() else Float.MAX_VALUE,h/6000f)}
        vel+=(target-vel)*min(1f,dt/400f);acc+=vel*dt
        val n=acc.toInt();if(n<=0)return;acc-=n
        if(r.scroll.canScrollVertically(1)){r.scroll.scrollBy(0,n);endSince=0L;return}
        // the end of the chapter: a moment on its last lines, then the next one (or the end of the book)
        if(endSince==0L)endSince=t else if(t-endSince>1500){endSince=0L;if(r.atEnd()){stop();r.turn(1)}else r.nextChapterAuto()}}

    /** A page came on the screen (page mode): its time starts; one turned by hand teaches the speed. */
    fun pageShown(){if(!on||!r.paged())return;val k=r.pageKey();if(k==key)return;key=k
        val pos=r.position();val t=spentNow()
        if(lastPos>=0&&!selfTurn&&running){if(pos>lastPos&&lastPos>=furthest)learnForward(t) else if(pos<lastPos)learnBack()}
        furthest=max(furthest,pos);selfTurn=false
        lastPos=pos;lastChars=r.pageCharsNow();due=dueFor(lastChars,r.pageImagesNow());spent=0L;from=now;line.invalidate()}
    /** Turned early by hand after really reading the page (not a skip, not a slip): a step towards that speed. */
    private fun learnForward(t:Long){if(!adapt||lastChars<200)return
        if(t<1500||t<due/4||t>due*92/100)return
        val seen=(lastChars/ReadingTracker.CHARS_PER_WORD/(t/60000.0)).toFloat()
        setWpm(min(wpm+(seen-wpm)*.35f,wpm*1.15f).roundToInt())}
    private fun learnBack(){if(adapt)setWpm((wpm*.93f).roundToInt())}
    /** The scroll pulled by hand: back is slower, a short step on is faster; a long jump is a skip. */
    private fun learnScroll(dy:Float){if(!adapt)return;val h=r.scroll.height
        if(dy>slop*2)setWpm((wpm*.93f).roundToInt()) else if(dy< -slop*2&&-dy<h*.6f)setWpm((wpm*1.04f).roundToInt())}
    private fun setWpm(v:Int){val next=v.coerceIn(MIN,MAX);if(next==wpm)return
        // the part of the page already read stays read
        if(r.paged()&&due>0){val part=spentNow().toDouble()/due;wpm=next;due=dueFor(lastChars,r.pageImagesNow());spent=(part*due).toLong();from=now}else wpm=next
        measured=0L;r.book.put("autoWpm",wpm);value?.let{showValue(it)}}

    /** Every touch on the reader comes here first. A tap pauses or goes on (and nothing under it is tapped); the rest
     *  goes on to the page — a swipe still turns it (and teaches the speed). */
    fun intercept(e:MotionEvent,cancelUnder:()->Unit):Boolean{if(!on)return false
        // a quote being chosen keeps its own taps
        if(!running&&r.quoteActive())return false
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{through=listOfNotNull(panel,card,chip).any{over(it,e)};lastTouch=now
                chip?.let{if(!over(it,e))hideChip()}
                downX=e.rawX;downY=e.rawY;downAt=now;tap=!through;finger=!through&&!r.paged()&&running}
            MotionEvent.ACTION_MOVE->if(tap&&(abs(e.rawX-downX)>slop||abs(e.rawY-downY)>slop))tap=false
            MotionEvent.ACTION_POINTER_DOWN->tap=false
            MotionEvent.ACTION_UP->{finger=false;if(through)return false
                if(tap&&now-downAt<ViewConfiguration.getLongPressTimeout()){cancelUnder();touchPoint=r.herePointNow()
                    if(asking)answer() else if(running)pause() else resume();return true}
                if(!r.paged()&&running){learnScroll(e.rawY-downY);holdUntil=now+900}
                if(running)touchPoint=r.herePointNow()}
            MotionEvent.ACTION_CANCEL->finger=false}
        return false}
    private fun over(v:View,e:MotionEvent):Boolean{if(v.visibility!=View.VISIBLE||v.parent==null)return false;val rc=Rect();if(!v.getGlobalVisibleRect(rc))return false;return rc.contains(e.rawX.toInt(),e.rawY.toInt())}

    // ── the hairline to the next turn ──
    private val line=object:View(a){
        private val p=Paint()
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        override fun onDraw(c:Canvas){translationY=(parent as? View)?.paddingBottom?.toFloat()?:0f
            val w=width.toFloat();val h=height.toFloat();p.color=(ink and 0xffffff) or (0x14 shl 24);c.drawRect(0f,0f,w,h,p)
            val k=(spentNow().toFloat()/due.coerceAtLeast(1)).coerceIn(0f,1f);p.color=(ink and 0xffffff) or (0x8c shl 24);c.drawRect(0f,0f,w*k,h,p)}}

    // ── the control (paused) ──
    private var panel:LinearLayout?=null;private var value:TextView?=null
    private fun showValue(t:TextView){val s=android.text.SpannableStringBuilder("$wpm  слов в минуту")
        val n="$wpm".length;s.setSpan(android.text.style.AbsoluteSizeSpan(26,true),0,n,0);s.setSpan(android.text.style.StyleSpan(Typeface.BOLD),0,n,0)
        s.setSpan(android.text.style.ForegroundColorSpan(muted),n,s.length,0);s.setSpan(android.text.style.AbsoluteSizeSpan(13,true),n,s.length,0);t.text=s}
    private fun showPanel(){val p=panel?:build().also{panel=it}
        p.animate().cancel();p.visibility=View.VISIBLE
        if(a.prefs.str("motion")=="reduced"){p.alpha=1f;p.translationY=0f;return}
        p.alpha=0f;p.translationY=a.d(24).toFloat();p.animate().alpha(1f).translationY(0f).setDuration(MotionSpec.SHEET_IN).setInterpolator(MotionSpec.enter).start()}
    private fun hidePanel(){val p=panel?:return;if(p.visibility!=View.VISIBLE)return
        if(a.prefs.str("motion")=="reduced"){p.visibility=View.GONE;return}
        p.animate().cancel();p.animate().alpha(0f).translationY(a.d(16).toFloat()).setDuration(MotionSpec.SHEET_OUT).setInterpolator(MotionSpec.exit).withEndAction{if(!running)return@withEndAction;p.visibility=View.GONE}.start()}
    private fun build():LinearLayout{
        val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(0,a.d(16),0,a.d(14));isClickable=true;elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(surface);cornerRadius=a.d(28).toFloat()}}
        val top=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(8),0)}
        val play=FrameLayout(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(ink)};isClickable=true;contentDescription="Продолжить";setOnClickListener{resume()};a.press(this)
            addView(IconView(a,"play",page),FrameLayout.LayoutParams(a.d(24),a.d(24),Gravity.CENTER))}
        top.addView(play,LinearLayout.LayoutParams(a.d(52),a.d(52)))
        val v=TextView(a).apply{setTextColor(ink);typeface=PolkaFonts.sans(600);includeFontPadding=false;setPadding(a.d(14),0,0,0)};value=v;showValue(v)
        top.addView(v,LinearLayout.LayoutParams(0,-2,1f))
        top.addView(IconView(a,"x",muted).apply{isClickable=true;contentDescription="Выключить автолистание";setOnClickListener{stop()};a.press(this)},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        box.addView(top)
        box.addView(Dial(),LinearLayout.LayoutParams(-1,a.d(56)).apply{topMargin=a.d(14)})
        box.addView(View(a).apply{setBackgroundColor((ink and 0xffffff) or (0x12 shl 24))},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14);leftMargin=a.d(16);rightMargin=a.d(16)})
        box.addView(LayoutToggle(a,"Подстраиваться под меня",adapt){on->adapt=on;a.prefs.put("autoAdapt",on);a.savePreferences()},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(12);leftMargin=a.d(12);rightMargin=a.d(12)})
        box.addView(AskRow(),LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(8);leftMargin=a.d(12);rightMargin=a.d(12)})
        r.view.addView(box,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=a.d(14);rightMargin=a.d(14);bottomMargin=a.d(26)})
        return box}

    private fun askLabel(m:Int)=if(m==0)"не спрашивать" else "через $m мин"
    /** «Ещё читаете?» and when: a capsule like the switch above it, its choice on the right; a tap lists the choices. */
    private inner class AskRow:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG)
        init{isClickable=true;a.press(this);contentDescription="Спрашивать «Ещё читаете?»";setOnClickListener{choose()}}
        private fun choose(){val now=a.prefs.optInt("autoAsk",10)
            a.dialog().setTitle("Спрашивать «Ещё читаете?»").setItemIcons(*ASK_CHOICES.map{if(it==now)"check" else ""}.toTypedArray())
                .setItems(ASK_CHOICES.map{askLabel(it).replaceFirstChar{c->c.uppercase()}}.toTypedArray()){_,i->a.prefs.put("autoAsk",ASK_CHOICES[i]);a.savePreferences();lastTouch=SystemClock.uptimeMillis();invalidate()}.show()}
        override fun onDraw(c:Canvas){val h=height.toFloat();val w=width.toFloat();val d=a.resources.displayMetrics.density
            p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,h/2,h/2,p)
            t.typeface=PolkaFonts.sans(400);t.textSize=13f*a.resources.displayMetrics.scaledDensity;val mid=h/2f-(t.ascent()+t.descent())/2f
            t.color=if(a.light)0xff3a3a3e.toInt() else 0xffcfcfd4.toInt();t.textAlign=Paint.Align.LEFT;c.drawText("«Ещё читаете?»",14*d,mid,t)
            t.color=if(a.light)0xff8a8a8e.toInt() else 0xff8e8e93.toInt();t.textAlign=Paint.Align.RIGHT;c.drawText(askLabel(a.prefs.optInt("autoAsk",10)),w-30*d,mid,t)
            c.save();c.translate(w-26*d,(h-16*d)/2);c.scale(16*d/24f,16*d/24f);IosIcons.draw(c,"next",t.color);c.restore()}
    }

    /** The speed on a ruler: dragged or flung under the mark, it stops on a step of five words with a soft tick. */
    private inner class Dial:View(a){
        private val gap=a.d(12).toFloat()
        private var at=wpm.toFloat()
        private var mark=wpm/STEP
        private val fling=OverScroller(a,android.view.animation.DecelerateInterpolator(1.6f))
        private var vt:VelocityTracker?=null;private var lastX=0f
        private var settle:ValueAnimator?=null
        private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
        private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;textSize=11f*a.resources.displayMetrics.scaledDensity;typeface=PolkaFonts.sans(400)}
        private val far get()=(MAX-MIN)/STEP*gap
        init{contentDescription="Скорость"}
        private fun px()=(at-MIN)/STEP*gap
        private fun moveTo(x:Float){at=(MIN+x.coerceIn(0f,far)/gap*STEP);val m=(at/STEP).roundToInt()
            if(m!=mark){mark=m;if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);setWpm(m*STEP)};invalidate()}
        private fun snap(){val to=(at/STEP).roundToInt()*STEP.toFloat();settle?.cancel()
            settle=ValueAnimator.ofFloat(at,to).apply{duration=180;interpolator=MotionSpec.enter;addUpdateListener{at=it.animatedValue as Float;invalidate()};start()}}
        override fun onTouchEvent(e:MotionEvent):Boolean{
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{fling.forceFinished(true);settle?.cancel();parent?.requestDisallowInterceptTouchEvent(true);lastX=e.x;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e)}
                MotionEvent.ACTION_MOVE->{vt?.addMovement(e);moveTo(px()-(e.x-lastX));lastX=e.x}
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{vt?.addMovement(e);vt?.computeCurrentVelocity(1000);val vx=vt?.xVelocity?:0f;vt?.recycle();vt=null
                    if(abs(vx)>ViewConfiguration.get(a).scaledMinimumFlingVelocity*2){fling.fling(px().roundToInt(),0,-vx.roundToInt(),0,0,far.roundToInt(),0,0);postInvalidateOnAnimation()}else snap()}}
            return true}
        override fun computeScroll(){if(fling.computeScrollOffset()){moveTo(fling.currX.toFloat());postInvalidateOnAnimation();if(fling.isFinished)snap()}}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val cx=w/2f;val base=h;val p0=px()
            val first=((p0-cx)/gap).toInt()-1;val last=((p0+cx)/gap).toInt()+1
            for(k in max(0,first)..min((MAX-MIN)/STEP,last)){val x=cx+k*gap-p0;val v=MIN+k*STEP;val major=v%20==0
                val fade=(1f-((x-cx)/cx).let{it*it}).coerceIn(0f,1f)
                p.strokeWidth=a.d(2).toFloat();p.color=(ink and 0xffffff) or ((255*(if(major).55f else .22f)*fade).toInt() shl 24)
                c.drawLine(x,base-a.d(if(major)22 else 12),x,base-a.d(1),p)
                if(major&&abs(x-cx)>gap*1.5f){t.color=(muted and 0xffffff) or ((255*.8f*fade).toInt() shl 24);c.drawText("$v",x,a.d(12).toFloat(),t)}}
            p.strokeWidth=a.d(3).toFloat();p.color=ink;c.drawLine(cx,base-a.d(34),cx,base-a.d(1),p)}
        override fun onDetachedFromWindow(){fling.forceFinished(true);settle?.cancel();super.onDetachedFromWindow()}
    }

    // ── «Ещё читаете?» ──
    private var card:FrameLayout?=null;private var countdown:ValueAnimator?=null
    private fun ask(){spent=spentNow();asking=true;line.invalidate()
        val scrim=FrameLayout(a).apply{setBackgroundColor(0x73000000);isClickable=true;setOnClickListener{answer()}}
        val box=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(22),a.d(24),a.d(22),a.d(18));isClickable=true;elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(surface);cornerRadius=a.d(26).toFloat()}}
        val ring=object:View(a){var k=1f;var secs=20
            private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND}
            private val t=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER;textSize=17f*a.resources.displayMetrics.scaledDensity;typeface=PolkaFonts.sans(600)}
            override fun onDraw(c:Canvas){val s=a.d(4).toFloat();val rr=RectF(s,s,width-s,height-s);p.strokeWidth=s
                p.color=(ink and 0xffffff) or (0x1a shl 24);c.drawOval(rr,p);p.color=ink;c.drawArc(rr,-90f,360f*k,false,p)
                t.color=ink;c.drawText("$secs",width/2f,height/2f-(t.ascent()+t.descent())/2f,t)}}
        box.addView(ring,LinearLayout.LayoutParams(a.d(56),a.d(56)))
        box.addView(TextView(a).apply{text="Ещё читаете?";textSize=20f;typeface=PolkaFonts.sans(600);setTextColor(ink);gravity=Gravity.CENTER},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(10)})
        box.addView(TextView(a).apply{text="Давно не было касаний. Если не ответить, листание остановится";textSize=14f;setTextColor(muted);gravity=Gravity.CENTER;typeface=PolkaFonts.sans(400)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(6);leftMargin=a.d(10);rightMargin=a.d(10)})
        box.addView(TextView(a).apply{text="Читаю";textSize=15f;typeface=PolkaFonts.sans(600);setTextColor(page);gravity=Gravity.CENTER;isClickable=true
            background=GradientDrawable().apply{setColor(ink);cornerRadius=a.d(24).toFloat()};setOnClickListener{answer()};a.press(this)},LinearLayout.LayoutParams(-1,a.d(48)).apply{topMargin=a.d(18)})
        scrim.addView(box,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply{leftMargin=a.d(22);rightMargin=a.d(22)})
        r.view.addView(scrim,FrameLayout.LayoutParams(-1,-1));card=scrim
        if(a.prefs.str("motion")!="reduced"){scrim.alpha=0f;scrim.animate().alpha(1f).setDuration(MotionSpec.ASK_IN).setInterpolator(MotionSpec.enter).start()}
        val back=touchPoint
        countdown=ValueAnimator.ofFloat(1f,0f).apply{duration=ASK_FOR;interpolator=android.view.animation.LinearInterpolator()
            addUpdateListener{val k=it.animatedValue as Float;ring.k=k;ring.secs=(k*ASK_FOR/1000f).let{s->kotlin.math.ceil(s).toInt()};ring.invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(x:android.animation.Animator){if(cancelled||!asking)return
                    // asleep: stop, and offer the page last touched
                    stop();back?.let{offerBack(it)}}})
            start()}}
    private fun answer(){if(!asking)return;asking=false;countdown?.cancel();countdown=null;lastTouch=now;from=now;touchPoint=r.herePointNow()
        val c=card?:return;card=null
        if(a.prefs.str("motion")=="reduced"){r.view.removeView(c);return}
        c.animate().alpha(0f).setDuration(MotionSpec.ASK_OUT).setInterpolator(MotionSpec.exit).withEndAction{r.view.removeView(c)}.start()}

    // ── «Вернуться к стр. …» ──
    private var chip:LinearLayout?=null
    private fun offerBack(point:JSONObject){val label=r.pageLabel(point)?:return
        if(point.optInt("ch")==r.herePointNow().optInt("ch")&&point.optInt("off")==r.herePointNow().optInt("off"))return
        val c=LinearLayout(a).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(12),0,a.d(16),0);isClickable=true;elevation=a.d(10).toFloat();contentDescription="Вернуться к $label"
            background=GradientDrawable().apply{setColor(surface);cornerRadius=a.d(20).toFloat()}
            setOnClickListener{hideChip();r.autoReturn(point)};a.press(this)}
        c.addView(IconView(a,"back",ink),LinearLayout.LayoutParams(a.d(18),a.d(18)))
        c.addView(TextView(a).apply{text="Вернуться к $label";textSize=14f;typeface=PolkaFonts.sans(500);setTextColor(ink);setPadding(a.d(8),0,0,0)})
        r.view.addView(c,FrameLayout.LayoutParams(-2,a.d(40),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply{bottomMargin=a.d(44)});chip=c
        c.postDelayed({hideChip()},10_000)}
    private fun hideChip(){val c=chip?:return;chip=null;c.animate().alpha(0f).setDuration(MotionSpec.ASK_OUT).withEndAction{(c.parent as? ViewGroup)?.removeView(c)}.start()}
}
