package app.polka.nativeapp

import android.app.Dialog
import android.graphics.*
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.sin

/**
 * How a book was read, from the statistics: from the first day of its reading to [end] (the last stretch of it: a
 * book put aside for over two months starts again where it was taken up again) and the time it took. A start set by
 * hand (the status) counts when it is earlier and close to the reading.
 */
private val RU=java.util.Locale("ru")

internal class BookJourney(val start:LocalDate?,val end:LocalDate,val ms:Long){
    val days get()=start?.let{ChronoUnit.DAYS.between(it,end).toInt()+1}
    /** «3 — 14 октября», «28 сентября — 4 октября», «14 октября». */
    fun range():String{val s=start?:return dayMonth(end)
        if(s==end)return dayMonth(end)
        val year=s.year!=end.year||end.year!=LocalDate.now().year
        val left=if(s.year!=end.year)s.format(DateTimeFormatter.ofPattern("d MMMM yyyy",RU)) else if(s.month==end.month)"${s.dayOfMonth}" else dayMonth(s)
        val right=end.format(DateTimeFormatter.ofPattern(if(year)"d MMMM yyyy" else "d MMMM",RU))
        return "$left — $right"}
    companion object{
        fun of(data:JSONObject?,book:JSONObject,end:LocalDate,zone:ZoneId=ZoneId.systemDefault()):BookJourney{
            val id=book.str("id");val days=data?.optJSONObject("days")
            val read=ArrayList<Pair<LocalDate,Long>>()
            days?.keys()?.forEach{k->val d=runCatching{LocalDate.parse(k)}.getOrNull()?:return@forEach
                val ms=days.optJSONObject(k)?.optJSONObject("books")?.optLong(id)?:0L;if(ms>0&&!d.isAfter(end))read.add(d to ms)}
            read.sortByDescending{it.first}
            val stretch=ArrayList<Pair<LocalDate,Long>>()
            for(p in read){if(stretch.isNotEmpty()&&ChronoUnit.DAYS.between(p.first,stretch.last().first)>60)break;stretch.add(p)}
            var start=stretch.lastOrNull()?.first
            val byHand=book.optLong("startedAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
            if(byHand!=null&&!byHand.isAfter(end)&&(start==null||(byHand.isBefore(start)&&ChronoUnit.DAYS.between(byHand,start)<=60)))start=byHand
            return BookJourney(start,end,stretch.sumOf{it.second})}
    }
}

/**
 * The end of a book as an event (approved «Финал Б: вспышка»): the page gives way, the cover pops up with a spring in
 * the light of «О книге», a short burst in its own colours flies out from behind it (with a vibration), «Прочитано»
 * comes under it, and below: the title, the author and how it was read, the rating (half stars, then the marks and
 * the review), «Готово» and «Без оценки». A comic's next issue comes out from behind the cover once it has landed, and
 * «Читать #08» under the title opens it. «Готово» keeps what was given, «Без оценки» leaves the book without what was
 * put in here. The window is its own, over the reader or the library.
 */
internal class BookFinish(private val a:MainActivity,private val book:JSONObject,private val openNext:((JSONObject)->Unit)?=null,private val done:(closeBook:Boolean)->Unit){
    /** A comic's next issue in the library (approved «Конец выпуска А»): it peeks out from behind the cover and
     *  «Читать #08» under the title opens it. */
    private val nextBook=nextIssue(a,book)
    /** The reader's backdrop, which the screen turns into on the way to the next issue. */
    private var readerBg=Color.BLACK
    private val dialog=Dialog(a,R.style.PolkaBlack)
    private val before=listOf("rating","reviewTags","review","reviewAt").associateWith{book.opt(it)}
    private var closed=false
    private val motion get()=!a.reducedMotion
    /** The cover's pop: past its size and back, once. */
    private val pop=Motion.expand.interpolator
    private var root:View?=null

    /** [pageBg]: the reader's backdrop (a comic's), which the screen turns into on the way to the next issue. */
    fun show(pageBg:Int=0){
        if(pageBg!=0)readerBg=pageBg
        // the cover is ready before anything moves (it never pops up blank)
        var started=false;fun go(){if(started||a.isFinishing||a.isDestroyed)return;started=true;build()}
        if(a.cover(book)!=null||book.optJSONObject("cover")?.str("__polkaBlob").isNullOrBlank())go()
        else{a.requestCover(book){go()};a.root.postDelayed({go()},500)}
    }

    private fun build(){
        val root=FrameLayout(a).apply{setBackgroundColor(a.bg)}
        val scroll=ScrollView(a).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false;clipChildren=false}
        val page=a.col().apply{clipChildren=false;clipToPadding=false;gravity=Gravity.CENTER_HORIZONTAL}
        scroll.addView(page,FrameLayout.LayoutParams(-1,-2))
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val close=FrameLayout(a).apply{background=a.shape(Ui.press(a.light),Radius.R24);contentDescription="Закрыть";isClickable=true;isFocusable=true
            addView(IconView(a,"x",a.fg),FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.CENTER));setOnClickListener{finish(false,false)};a.press(this)}
        root.addView(close,FrameLayout.LayoutParams(a.d(46),a.d(46),Gravity.TOP or Gravity.END).apply{rightMargin=a.d(16)})

        val cover=CoverView(a,book,a.cover(book))
        // the light of «О книге»: the cover's own colours, lifted and blurred wide round it
        val light=BookGlowView(a,cover)
        // the next issue waits behind it, to its right: smaller and quieter (it comes out once the cover has landed)
        val peek=nextBook?.let{n->CoverView(a,n,a.cover(n)).apply{importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}}
        val burst=Burst(cover)
        // the cover takes the room the rest leaves (150–300dp tall); as the rest grows (the marks, the keyboard) the
        // cover follows it in the same layout pass; the light is laid round it at the size «О книге» gives it
        val coverBox=object:FrameLayout(a){
            override fun onMeasure(ws:Int,hs:Int){val free=MeasureSpec.getMode(hs)==MeasureSpec.UNSPECIFIED
                val room=if(free)a.d(150) else MeasureSpec.getSize(hs);val h=room.coerceIn(a.d(150),a.d(300));val w=(h/1.5f).toInt()
                cover.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
                peek?.measure(MeasureSpec.makeMeasureSpec((w*.73f).toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((h*.73f).toInt(),MeasureSpec.EXACTLY))
                val (lw,lh)=lightSize(w);light.measure(MeasureSpec.makeMeasureSpec(lw,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(lh,MeasureSpec.EXACTLY))
                burst.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(ws),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(maxOf(room,a.d(150)),MeasureSpec.EXACTLY))
                setMeasuredDimension(MeasureSpec.getSize(ws),maxOf(room,a.d(150)))}
            override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val x=(width-cover.measuredWidth)/2;val y=(height-cover.measuredHeight)/2
                cover.layout(x,y,x+cover.measuredWidth,y+cover.measuredHeight)
                val cx=x+cover.measuredWidth/2;val cy=y+cover.measuredHeight/2
                light.layout(cx-light.measuredWidth/2,cy-light.measuredHeight/2,cx+light.measuredWidth-light.measuredWidth/2,cy+light.measuredHeight-light.measuredHeight/2)
                burst.layout(0,0,width,height)
                peek?.let{p->val px=x+cover.measuredWidth-(p.measuredWidth*.4f).toInt();val py=y+(cover.measuredHeight*.14f).toInt();p.layout(px,py,px+p.measuredWidth,py+p.measuredHeight)}}
        }.apply{clipChildren=false;clipToPadding=false;minimumHeight=a.d(150)}
        coverBox.addView(light);peek?.let{coverBox.addView(it)};coverBox.addView(burst);coverBox.addView(cover)
        page.addView(coverBox,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=a.d(56)})
        // «Прочитано» as the app shows a status: its quiet tag with the read mark the covers carry
        val chip=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(8),0,a.d(13),0);background=a.shape(Ui.fill(a.light),Radius.R16)
            addView(ReadMark(),LinearLayout.LayoutParams(a.d(18),a.d(18)))
            addView(a.text("Прочитано",Type.SECONDARY,a.fg).apply{typeface=PolkaFonts.sans(500)},LinearLayout.LayoutParams(-2,-2).apply{marginStart=a.d(7)})}
        page.addView(chip,LinearLayout.LayoutParams(-2,a.d(32)).apply{topMargin=a.d(14)})

        // below, on the page itself: the title, the author and how it was read, the way on, the rating, the two ways out
        val rest=a.col().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(10),0,a.d(10),0)}
        val title=a.text(book.str("title"),Type.H1,a.fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=3}
        val facts=a.text(MainActivity.authors(book),Type.TEXT,a.muted).apply{gravity=Gravity.CENTER;maxLines=2}
        val head=a.col().apply{gravity=Gravity.CENTER_HORIZONTAL;addView(title,LinearLayout.LayoutParams(-1,-2));addView(facts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})}
        nextBook?.let{n->
            val goOn=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(14),0);background=a.shape(Ui.press(a.light),Radius.R20)
                isClickable=true;isFocusable=true;contentDescription="Читать ${issueLabel(n)}";setOnClickListener{goNext(n)};a.press(this)}
            goOn.addView(a.text("Читать ${issueLabel(n)}",Type.TEXT,a.fg,true))
            goOn.addView(IconView(a,"next",a.fg),LinearLayout.LayoutParams(a.d(17),a.d(17)).apply{marginStart=a.d(6)})
            head.addView(goOn,LinearLayout.LayoutParams(-2,a.d(40)).apply{topMargin=a.d(16)})}
        rest.addView(head,LinearLayout.LayoutParams(-1,-2))
        val review=ReviewBlock(a,book).apply{background=null;elevation=0f;clipToOutline=false;setPadding(0,a.d(6),0,0)}
        rest.addView(review,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16)})
        val ways=a.col().apply{
            addView(a.button("Готово",true){finish(true,true)}.apply{background=a.shape(a.fg,Radius.R28);minHeight=a.d(52)},LinearLayout.LayoutParams(-1,a.d(52)))
            addView(a.text("Без оценки",Type.TEXT,a.muted).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener{finish(false,true)}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(4)})}
        rest.addView(ways,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})
        page.addView(rest,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(28)})

        // how it was read: how many days and how long, after the author
        var journey:BookJourney?=null
        a.work({val data=a.library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")}
            val end=book.optLong("completedAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}?:LocalDate.now()
            journey=BookJourney.of(data,book,end)}){val j=journey?:return@work
            facts.text=listOfNotNull(MainActivity.authors(book).takeIf{it.isNotBlank()},j.days?.let{n->"$n ${daysWordRu(n)}"},if(j.ms>=60_000L)duration(j.ms) else null).joinToString(", ")}

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setOnCancelListener{finish(false,false)}
        dialog.window?.let{w->w.setLayout(-1,-1);w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(w,false)
            // the page runs under the bars (and the camera cutout); its content keeps clear of them by its padding
            w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
            w.attributes=w.attributes.apply{if(android.os.Build.VERSION.SDK_INT>=28)layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                if(android.os.Build.VERSION.SDK_INT>=30){setFitInsetsTypes(0);setFitInsetsSides(0)}}
            w.statusBarColor=Color.TRANSPARENT;w.navigationBarColor=Color.TRANSPARENT
            WindowCompat.getInsetsController(w,w.decorView).apply{isAppearanceLightStatusBars=a.light;isAppearanceLightNavigationBars=a.light}}
        // the page keeps clear of the bars and of the keyboard; the line being typed stays above it
        var keyboard=0
        ViewCompat.setOnApplyWindowInsetsListener(root){_,ins->val bars=ins.getInsets(WindowInsetsCompat.Type.systemBars()or WindowInsetsCompat.Type.displayCutout());keyboard=ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
            page.setPadding(a.d(14)+bars.left,bars.top,a.d(14)+bars.right,maxOf(bars.bottom,keyboard)+a.d(14))
            (close.layoutParams as FrameLayout.LayoutParams).topMargin=bars.top+a.d(8);close.requestLayout();ins}
        ImeKeeper(scroll,a.d(20)){keyboard}.attach()
        dialog.window?.setWindowAnimations(0)
        this.root=root
        if(motion){root.alpha=0f;cover.alpha=0f;light.alpha=0f;chip.alpha=0f;close.alpha=0f;peek?.alpha=0f;listOf(head,review,ways).forEach{it.alpha=0f}
            var first=true
            page.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(first&&scroll.height>0){first=false
                afterFirstFrames{entrance(cover,light,burst,chip,peek,listOf(head,review,ways),close)}}}}
        else peek?.alpha=PEEK
        dialog.show()
    }

    /** Runs [go] once the window's first frames (the heaviest: a new window, the whole page) are drawn, so the move
     *  loses none of its steps to them. */
    private fun afterFirstFrames(go:()->Unit){val ch=Choreographer.getInstance();ch.postFrameCallback{ch.postFrameCallback{if(!closed)go()}}}

    /**
     * The finish in about a second and a half (approved «Финал Б»): the page gives way to this one; the cover pops up
     * past its size and back, its light comes up; as it lands, the burst flies out from behind it and the phone gives
     * one firm tick; «Прочитано» comes up under it (a comic's next issue slides out from behind); then the rest rises
     * in turn, the cross last.
     */
    private fun entrance(cover:View,light:View,burst:Burst,chip:View,peek:View?,rest:List<View>,close:View){
        val r=root?:return
        r.animate().alpha(1f).setDuration(Motion.STANDARD).setInterpolator(MotionSpec.enter).start()
        cover.scaleX=.6f;cover.scaleY=.6f
        // its size on the pop's curve, its showing quick and plain (one animator each: a view has one property animator)
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{startDelay=280;duration=Motion.FAST;interpolator=MotionSpec.enter;addUpdateListener{cover.alpha=it.animatedValue as Float};start()}
        cover.animate().scaleX(1f).scaleY(1f).setStartDelay(280).setDuration(Motion.EMPHASIS).setInterpolator(pop).withLayer().start()
        light.animate().alpha(1f).setStartDelay(330).setDuration(Motion.LONG).setInterpolator(MotionSpec.enter).start()
        r.postDelayed({if(!closed){burst.go();tick()}},560)
        chip.scaleX=.7f;chip.scaleY=.7f
        chip.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(800).setDuration(Motion.STANDARD).setInterpolator(pop).start()
        peek?.let{pk->pk.translationX=-a.d(46).toFloat();pk.scaleX=.94f;pk.scaleY=.94f
            pk.animate().alpha(PEEK).translationX(0f).scaleX(1f).scaleY(1f).setStartDelay(800).setDuration(Motion.EMPHASIS).setInterpolator(MotionSpec.enter).start()}
        rest.forEachIndexed{i,v->v.translationY=a.d(14).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(950L+i*80).setDuration(Motion.EMPHASIS).setInterpolator(MotionSpec.enter).withLayer().start()}
        close.animate().alpha(1f).setStartDelay(1100).setDuration(Motion.STANDARD).setInterpolator(MotionSpec.enter).start()
    }
    /** The finish's one vibration: a firm, short «done». */
    private fun tick(){root?.haptic(a,Haptic.CONFIRM)}
    /** How large the light of «О книге» is round a cover [w] wide (BookGlowRaster's picture, at its spread). */
    private fun lightSize(w:Int):Pair<Int,Int>{val s=w/96f*BookGlowRaster.SPREAD;return ((96+BookGlowRaster.PADDING*2)*s).toInt()+4 to ((144+BookGlowRaster.PADDING*2)*s).toInt()+4}

    /** The read mark the covers carry: a light disc with the check cut in it. */
    private inner class ReadMark:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path()
        override fun onDraw(c:Canvas){val s=minOf(width,height).toFloat();p.style=Paint.Style.FILL;p.color=a.fg;c.drawCircle(width/2f,height/2f,s/2f,p)
            val k=s*.6f/24f;val ox=width/2f-12f*k;val oy=height/2f-12f*k
            p.style=Paint.Style.STROKE;p.strokeWidth=3f*k;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=a.bg
            path.rewind();path.moveTo(ox+5f*k,oy+12.5f*k);path.lineTo(ox+9.5f*k,oy+17f*k);path.lineTo(ox+19f*k,oy+7.5f*k);c.drawPath(path,p)}
    }

    /**
     * The burst out from behind the cover: two dozen bits of confetti in the cover's own colours (and the page's light
     * ink), flung out round it, slowing, turning, falling a little and fading. Drawn from the cover's centre; once.
     */
    private inner class Burst(private val cover:CoverView):View(a){
        private inner class Bit(val angle:Float,val dist:Float,val w:Float,val h:Float,val spin:Float,val color:Int)
        private val bits=ArrayList<Bit>();private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var t=-1f
        init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        fun go(){if(!motion)return
            val rnd=java.util.Random(book.str("id").hashCode().toLong());val colors=palette()
            bits.clear();repeat(24){val tall=rnd.nextBoolean()
                bits.add(Bit((rnd.nextFloat()*2*Math.PI).toFloat(),a.d(120)+rnd.nextFloat()*a.d(70),a.d(if(tall)6 else 8).toFloat(),a.d(if(tall)14 else 8).toFloat(),(rnd.nextFloat()*2-1)*260f,colors[rnd.nextInt(colors.size)]))}
            android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.CYCLE;interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener{t=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){t=-1f;invalidate()}});start()}}
        /** The cover's own colours, lifted to show on the page, with the page's ink. */
        private fun palette():List<Int>{val out=ArrayList<Int>()
            runCatching{val art=cover.glowArtwork();val small=Bitmap.createScaledBitmap(art,3,4,true)
                for(y in 0 until 4)for(x in 0 until 3){val c=small.getPixel(x,y);val hsv=FloatArray(3);Color.colorToHSV(c,hsv);hsv[1]=(hsv[1]*1.2f).coerceAtMost(1f);hsv[2]=hsv[2].coerceAtLeast(.55f);out.add(Color.HSVToColor(hsv))}
                if(small!==art)small.recycle();art.recycle()}
            out.add(a.fg);return out}
        override fun onDraw(c:Canvas){if(t<0f)return;val cx=cover.left+cover.width/2f;val cy=cover.top+cover.height/2f
            // out fast and slowing (60 % of the time), then drifting down a little as they fade
            val fly=1f-(1f-(t/.6f).coerceIn(0f,1f)).let{it*it*it};val fade=((t-.6f)/.4f).coerceIn(0f,1f)
            bits.forEach{b->val d=b.dist*(fly+.08f*fade);val x=cx+cos(b.angle)*d;val y=cy+sin(b.angle)*d*.9f+a.d(30)*fade*fade
                p.color=b.color;p.alpha=(255*(1f-fade)*(t/.06f).coerceAtMost(1f)).toInt()
                c.save();c.translate(x,y);c.rotate(b.spin*(t+.3f*fade));c.drawRoundRect(-b.w/2,-b.h/2,b.w/2,b.h/2,minOf(b.w,b.h)/2,minOf(b.w,b.h)/2,p);c.restore()}}
    }

    /**
     * On to the next issue: a plain fade (asked for instead of the cover growing into the page, which blinked where the
     * drawn cover gave way to the real page). The screen fades to the reader's backdrop, then the next issue's reader
     * opens on that same colour and its first page fades in.
     */
    private fun goNext(n:JSONObject){
        if(closed)return;closed=true
        a.currentFocus?.let{f->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)}
        dialog.setOnCancelListener(null)
        val open=openNext?:{x:JSONObject->openIssue(a,x)}
        val r=root as? FrameLayout
        if(r==null||!motion){open(n);r?.postDelayed({dialog.dismiss()},MotionSpec.DARK_OUT+120L)?:dialog.dismiss();return}
        val veil=View(a).apply{setBackgroundColor(readerBg);alpha=0f};r.addView(veil,FrameLayout.LayoutParams(-1,-1))
        veil.animate().alpha(1f).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.move)
            .withEndAction{open(n);r.postDelayed({dialog.dismiss()},MotionSpec.DARK_OUT+120L)}.start()
    }

    /** Leaving: the page fades away — back onto the reader as it was ([closeBook] false), or onto the library, the
     *  reader closing under it at once. */
    private fun finish(keep:Boolean,closeBook:Boolean){
        if(closed)return;closed=true
        if(!keep){before.forEach{(k,v)->if(v==null)book.remove(k) else book.put(k,v)};a.saveBook(book){}}
        a.currentFocus?.let{f->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)}
        dialog.setOnCancelListener(null)
        val r=root;if(r==null||!motion){dialog.dismiss();done(closeBook);return}
        if(closeBook)done(true)
        r.animate().cancel()
        r.animate().alpha(0f).setStartDelay(0).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).withLayer().withEndAction{dialog.dismiss();if(!closeBook)done(false)}.start()
    }
}

/** How a peeking next issue shows behind the read one. */
private const val PEEK=.72f
/** A comic's next issue in the library: the same series, the nearest larger number (or, without numbers, the next
 *  title in order); null when there is none, or it has no file to read. */
internal fun nextIssue(a:MainActivity,book:JSONObject):JSONObject?{
    if(!isComic(book))return null
    val key=book.str("series").trim().lowercase().takeIf{it.isNotBlank()}?:return null
    fun natural(t:String)=t.lowercase().replace(Regex("\\d+")){it.value.padStart(8,'0')}
    val list=a.books.filter{isComic(it)&&it.str("series").trim().lowercase()==key}
        .sortedWith(compareBy<JSONObject>{it.optDouble("seriesNo",0.0).takeIf{n->n>0}?:Double.MAX_VALUE}.thenBy{natural(it.str("title"))})
    val i=list.indexOfFirst{it.str("id")==book.str("id")};if(i<0)return null
    return list.getOrNull(i+1)
}
/** «#08»: as the issue's own title writes its number, or from its number in the series. */
internal fun issueLabel(n:JSONObject):String=Regex("#\\s*\\d+").find(n.str("title"))?.value?.replace(" ","")
    ?:n.optDouble("seriesNo",0.0).takeIf{it>0}?.let{"#"+(if(it%1.0==0.0)it.toInt().toString().padStart(2,'0') else it.toString())}?:"дальше"
/** Opens a comic's issue straight in its reader, with the bars hidden (the cover it grew from is its first page). */
internal fun openIssue(a:MainActivity,next:JSONObject){val fresh=a.books.firstOrNull{it.str("id")==next.str("id")}?:next
    if(PageDocs.isDoc(fresh)&&PageDocs.dir(a,fresh).exists())try{PageReader(a,fresh.copyJson()).apply{quiet=true}.show();return}catch(_:Exception){}
    a.openBook(fresh)}
