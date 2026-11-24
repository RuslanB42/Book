package app.polka.nativeapp

import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
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
 * The end of a book as an event (approved «Конец книги В»): the cover's own colours, blurred, over the whole screen,
 * the cover large in the middle of them, and at the foot one glass panel — how long it took, the title, the rating
 * (half stars), the marks and the review, «Готово» and «Без оценки». The cover gives way as the panel grows (the
 * marks open up, the keyboard comes). «Готово» keeps what was given, «Без оценки» leaves the book without what was
 * put in here. The window is its own, over the reader or the library.
 */
internal class BookFinish(private val a:MainActivity,private val book:JSONObject,private val done:(closeBook:Boolean)->Unit){
    private val dialog=Dialog(a,android.R.style.Theme_Black_NoTitleBar)
    private val before=listOf("rating","reviewTags","review","reviewAt").associateWith{book.opt(it)}
    private var closed=false

    private val motion get()=a.prefs.str("motion")!="reduced"
    /** Out of the page: like out of a deep place, slow at the end. */
    private val rise=android.view.animation.PathInterpolator(.16f,1f,.3f,1f)
    private var cover:View?=null;private var panel:View?=null

    /** Opened on the last page ([fromPage]): a picture of the screen is taken first, so the page can turn into the
     *  cover — of the reader's own [window] (comics are read in a window of their own, over the book's page). Opened
     *  later (the book closed as read), the page comes out of the dark instead. */
    /** [page]: where the page itself is in that window, when it does not fill it (a comic's page on its backdrop
     *  [pageBg]): then only the page turns into the cover, and the backdrop gives way to the light. */
    fun show(fromPage:Boolean=false,window:Window?=null,page:RectF?=null,pageBg:Int=0){
        // the cover is ready before anything moves: it shows through the page at the end of the move, never late
        var shot:Bitmap?=null;var waiting=2;var built=false
        fun ready(){if(--waiting>0||built||a.isFinishing||a.isDestroyed)return;built=true;build(shot,page,pageBg)}
        var coverDone=false;fun coverReady(){if(coverDone)return;coverDone=true;ready()}
        if(a.cover(book)!=null||book.optJSONObject("cover")?.str("__polkaBlob").isNullOrBlank())coverReady()
        else{a.requestCover(book){coverReady()};a.root.postDelayed({coverReady()},500)}
        if(!motion||!fromPage){ready();return}
        val w=window?:a.window;val v=w.decorView
        if(v.width<=0||v.height<=0){ready();return}
        val bmp=try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)}catch(_:Throwable){ready();return}
        try{android.view.PixelCopy.request(w,bmp,{res->if(res==android.view.PixelCopy.SUCCESS)shot=bmp else bmp.recycle();ready()},android.os.Handler(android.os.Looper.getMainLooper()))}
        catch(_:Throwable){bmp.recycle();ready()}
    }
    private fun build(shot:Bitmap?,from:RectF?=null,fromBg:Int=0){
        val ground=if(a.light)0xffe9e7e3.toInt() else 0xff0e0e10.toInt()
        val root=FrameLayout(a).apply{setBackgroundColor(ground)}
        // the cover's colours over the whole screen: darkened (lightened in the light theme) more toward the foot.
        // Two separate layers, each fading on its own (a fading group would be drawn twice a frame)
        val glow=object:ImageView(a){override fun hasOverlappingRendering()=false}.apply{scaleType=ImageView.ScaleType.CENTER_CROP
            colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.4f);if(!a.light)postConcat(ColorMatrix().apply{setScale(.75f,.75f,.75f,1f)})})}
        root.addView(glow,FrameLayout.LayoutParams(-1,-1))
        val scrim=object:View(a){override fun hasOverlappingRendering()=false}.apply{background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if(a.light)intArrayOf(0x1affffff,0x66ffffff,0xa6ffffff.toInt()) else intArrayOf(0x0d000000,0x59000000,0x99000000.toInt()))}
        root.addView(scrim,FrameLayout.LayoutParams(-1,-1))
        // approved «Страница в обложку»: the last page as it was on the screen, a card over the light that closes in on
        // the cover's place (the cover then shows through it)
        // a comic's backdrop round its page: the reader's colour, giving way to the light as the page flies
        val backdrop=if(shot!=null&&from!=null)View(a).apply{setBackgroundColor(fromBg)}.also{root.addView(it,FrameLayout.LayoutParams(-1,-1))} else null
        val card=shot?.let{PageCard(it,from).also{v->root.addView(v,FrameLayout.LayoutParams(-1,-1))}}
        val scroll=ScrollView(a).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false}
        val page=a.col().apply{clipChildren=false;clipToPadding=false}
        scroll.addView(page,FrameLayout.LayoutParams(-1,-2))
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val close=FrameLayout(a).apply{background=a.shape(if(a.light)0x14000000 else 0x40000000,20);contentDescription="Закрыть";isClickable=true;isFocusable=true
            addView(IconView(a,"x",a.fg),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER));setOnClickListener{finish(false,false)}}
        root.addView(close,FrameLayout.LayoutParams(a.d(40),a.d(40),Gravity.TOP or Gravity.END).apply{rightMargin=a.d(18)})

        // the cover, as large as the room above the panel allows
        val cover=CoverView(a,book,a.cover(book)).apply{softShadow=true}
        // its place takes all the room the panel leaves; the cover's size comes from that room in the same layout
        // pass, so as the panel grows or shrinks (a rating, the marks, the keyboard) the cover follows it frame by
        // frame, on the panel's own curve — never a move of its own after it
        val coverBox=object:FrameLayout(a){
            // asked without a limit (the scroll's first look at the page), it is as small as it may be: the page then
            // fits the screen and is measured again with the room it really has
            override fun onMeasure(ws:Int,hs:Int){val free=MeasureSpec.getMode(hs)==MeasureSpec.UNSPECIFIED
                val room=if(free)a.d(150) else MeasureSpec.getSize(hs);val h=room.coerceIn(a.d(150),a.d(300));val w=(h/1.5f).toInt()
                cover.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
                setMeasuredDimension(MeasureSpec.getSize(ws),maxOf(room,a.d(150)))}
            override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val x=(width-cover.measuredWidth)/2;cover.layout(x,0,x+cover.measuredWidth,cover.measuredHeight)}
        }.apply{clipChildren=false;clipToPadding=false;minimumHeight=a.d(150)}
        coverBox.addView(cover)
        page.addView(coverBox,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=a.d(52)})
        // the light never comes at once: when the cover's colours are ready late, they fade in too
        cover.prepareArtwork{val late=entered
            glow.setImageDrawable(BitmapDrawable(a.resources,blurred(cover.glowArtwork())).apply{isFilterBitmap=true})
            if(motion&&late&&!closed){glow.alpha=0f;glow.animate().alpha(1f).setDuration(700).setInterpolator(MotionSpec.enter).start()}}

        // the glass panel: how long it took, the title, the rating and the review, the two ways out
        val panel=a.col().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(20),a.d(22),a.d(20),a.d(8))
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0xd1f7f7f5.toInt() else 0xb81c1c1e.toInt());cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x14000000 else 0x14ffffff)}}
        val kicker=a.text("ПРОЧИТАНО",11.5f,if(a.light)0xff6e6e73.toInt() else 0xffa9a9ae.toInt(),true).apply{letterSpacing=.14f;gravity=Gravity.CENTER}
        panel.addView(kicker,LinearLayout.LayoutParams(-1,-2))
        panel.addView(a.text(book.str("title"),26f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=3},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        MainActivity.authors(book).takeIf{it.isNotBlank()}?.let{panel.addView(a.text(it,14f,a.muted).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})}
        // its line is kept from the start (the facts come a moment later): the panel never grows under the move
        val facts=a.text(" ",13f,a.muted).apply{gravity=Gravity.CENTER;visibility=View.INVISIBLE;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        panel.addView(facts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        // the rating lies on the glass itself, no card of its own
        val review=ReviewBlock(a,book).apply{background=null;elevation=0f;clipToOutline=false;setPadding(0,a.d(6),0,0)}
        panel.addView(review,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        panel.addView(a.button("Готово",true){finish(true,true)}.apply{background=a.shape(a.fg,25);minHeight=a.d(50)},LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=a.d(18)})
        panel.addView(a.text("Без оценки",15f,a.muted).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener{finish(false,true)}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(2)})
        page.addView(panel,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(24)})

        // the move starts once the page is laid out at its final size
        var first=true
        page.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->if(first&&scroll.height>0){first=false
                if(motion)startOnceLaidOut(root){afterFirstFrames{entrance(card,glow,scrim,cover,panel,close)}}}}

        // the facts: how many days (from — to) and how long it was read
        var journey:BookJourney?=null
        a.work({val data=a.library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")}
            val end=book.optLong("completedAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}?:LocalDate.now()
            journey=BookJourney.of(data,book,end)}){val j=journey?:return@work
            j.days?.let{n->kicker.text="ПРОЧИТАНО ЗА $n ${daysWordRu(n).uppercase()}"}
            facts.text=listOfNotNull(j.range(),if(j.ms>=60_000L)"${duration(j.ms)} за чтением" else null).joinToString(", ");facts.visibility=View.VISIBLE
            if(motion&&facts.isAttachedToWindow){facts.alpha=0f;facts.animate().alpha(1f).setDuration(280).setInterpolator(MotionSpec.enter).start()}}

        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setOnCancelListener{finish(false,false)}
        dialog.window?.let{w->w.setLayout(-1,-1);w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) // the page's own colour fades with it
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            WindowCompat.setDecorFitsSystemWindows(w,false)
            w.statusBarColor=Color.TRANSPARENT;w.navigationBarColor=Color.TRANSPARENT
            WindowCompat.getInsetsController(w,w.decorView).apply{isAppearanceLightStatusBars=a.light;isAppearanceLightNavigationBars=a.light}}
        // the page keeps clear of the bars and of the keyboard; the line being typed stays above it
        var keyboard=0
        ViewCompat.setOnApplyWindowInsetsListener(root){_,ins->val bars=ins.getInsets(WindowInsetsCompat.Type.systemBars()or WindowInsetsCompat.Type.displayCutout());keyboard=ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
            page.setPadding(a.d(14)+bars.left,bars.top,a.d(14)+bars.right,maxOf(bars.bottom,keyboard)+a.d(14))
            (close.layoutParams as FrameLayout.LayoutParams).topMargin=bars.top+a.d(8);close.requestLayout();ins}
        ImeKeeper(scroll,a.d(20)){keyboard}.attach()
        dialog.window?.setWindowAnimations(0)
        this.root=root;this.cover=cover;this.panel=panel;this.card=card;this.glow=glow;this.scrim=scrim;this.close=close;this.backdrop=backdrop
        // until the move starts only the page shows (or nothing, out of the dark), so opening the window changes nothing
        if(motion){if(card==null)root.alpha=0f;glow.alpha=0f;scrim.alpha=0f;cover.alpha=0f;close.alpha=0f
            panel.visibility=View.INVISIBLE;for(i in 0 until panel.childCount)panel.getChildAt(i).alpha=0f}
        dialog.setOnDismissListener{card?.release()}
        dialog.show()
    }
    private var root:View?=null

    /** Runs [go] once the window's first frames (the heaviest: a new window, the whole page) are drawn; they show the
     *  page as it was, so the wait is not seen and the move loses none of its steps to them. */
    private fun afterFirstFrames(go:()->Unit){val ch=Choreographer.getInstance();ch.postFrameCallback{ch.postFrameCallback{if(!closed)go()}}}
    /** Runs [go] on the next frame drawn after the layout that is pending now (the cover at its final size). */
    private fun startOnceLaidOut(v:View,go:()->Unit){v.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{
        override fun onPreDraw():Boolean{v.viewTreeObserver.removeOnPreDrawListener(this);go();return true}});v.invalidate()}

    /**
     * The last page as a card: a rounded window over the picture of the screen. [t] 0 is the whole screen, 1 the
     * cover's place; the picture fills the window as it narrows (cut evenly from both sides, never squeezed), the
     * card's shadow grows as it becomes a book and its face darkens a little. Everything is moved by the view's own
     * properties (the picture is drawn once), so each frame is cheap.
     */
    private inner class PageCard(shot:Bitmap,from:RectF?):FrameLayout(a){
        /** The cover as the real one draws it (volume, gloss): what the page becomes. Laid out at the cover's size. */
        private val face=CoverView(a,book,a.cover(book)).apply{softShadow=false;pivotX=0f;pivotY=0f;alpha=0f}
        /** How far along the flight is in time (its place, [t], is eased; the cover's coming is spread over the time). */
        var time=0f
        /** The cover comes up through the page all along the flight (it is the page turning into it, not a swap at
         *  the end): from 15 % to 75 % of its time, eased at both ends. */
        private fun faceAlpha():Float{val x=((time-.15f)/.6f).coerceIn(0f,1f);return x*x*(3-2*x)}
        /** The cover's size, once known (the real cover's place). */
        fun aim(r:RectF){target.set(r);face.layoutParams=LayoutParams(r.width().toInt(),r.height().toInt());pose()}
        /** The part of the picture that is the page (all of it, for a book). */
        private val src=from?:RectF(0f,0f,shot.width.toFloat(),shot.height.toFloat())
        private val shotW=shot.width.toFloat();private val shotH=shot.height.toFloat()
        private var bmp:Bitmap?=shot
        private val pic=ImageView(a).apply{setImageBitmap(shot);scaleType=ImageView.ScaleType.FIT_XY;pivotX=0f;pivotY=0f}
        private val shade=object:View(a){override fun hasOverlappingRendering()=false}.apply{setBackgroundColor(Color.BLACK);alpha=0f}
        val target=RectF();private val now=RectF()
        init{addView(pic,LayoutParams(-1,-1));addView(shade,LayoutParams(-1,-1));addView(face,LayoutParams(0,0))
            // rounded to the cover's own corners by the end of the way
            clipToOutline=true;outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
                o.setRoundRect(now.left.toInt(),now.top.toInt(),now.right.toInt(),now.bottom.toInt(),BookCoverVolume.radius(target.width())*t.coerceIn(0f,1f));o.alpha=1f}}}
        var t=0f;set(v){field=v;pose()}
        override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){super.onSizeChanged(w,h,ow,oh);pose()}
        fun pose(){val w=width.toFloat();val h=height.toFloat();if(w<=0f||h<=0f)return;val k=t
            // the window narrows from the page's place to the cover's; the page fills it, cut evenly, never squeezed
            val ux=w/shotW;val uy=h/shotH;val from=RectF(src.left*ux,src.top*uy,src.right*ux,src.bottom*uy)
            now.set(from.left+(target.left-from.left)*k,from.top+(target.top-from.top)*k,from.right+(target.right-from.right)*k,from.bottom+(target.bottom-from.bottom)*k)
            val s=maxOf(now.width()/from.width(),now.height()/from.height())
            pic.scaleX=s;pic.scaleY=s;pic.translationX=now.centerX()-from.centerX()*s;pic.translationY=now.centerY()-from.centerY()*s
            // its shadow lifts with it and lies down again as it lands (the cover's own shadow takes over from there)
            shade.alpha=.22f*k.coerceIn(0f,1f);elevation=a.d(18)*Math.sin(Math.PI*k.coerceIn(0f,1f)).toFloat();invalidateOutline()
            // the cover fills the window as the real one fills its place (cut evenly), coming up over the page
            val fw=target.width();val fh=target.height()
            if(fw>0f&&fh>0f){val fs=maxOf(now.width()/fw,now.height()/fh);face.scaleX=fs;face.scaleY=fs;face.translationX=now.centerX()-fw*fs/2;face.translationY=now.centerY()-fh*fs/2;face.alpha=faceAlpha()}}
        fun release(){pic.setImageDrawable(null);bmp?.recycle();bmp=null}
    }
    private var card:PageCard?=null;private var backdrop:View?=null;private var glow:View?=null;private var scrim:View?=null;private var close:View?=null
    /** The move has started (the cover's light coming later fades in on its own). */
    private var entered=false

    private fun clock(ms:Long,delay:Long,curve:android.animation.TimeInterpolator,step:(Float)->Unit,end:()->Unit={})=
        android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=ms;startDelay=delay;interpolator=curve
            addUpdateListener{step(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){end()}});start()}
    /** The page's own glide: it lifts off gently (no jolt in its first frames) and settles long — like setting a
     *  book down. */
    private val glide=android.view.animation.PathInterpolator(.42f,0f,.12f,1f)

    /**
     * The end of the book as one movement (approved «Страница в обложку», worked through for smoothness):
     *  - the last page lifts off as a card and glides into the cover's place, cut to the cover's shape on the way, a
     *    shadow growing under it — the page is seen the whole way, nothing covers it;
     *  - behind it the cover's light comes up and settles;
     *  - near the end the cover shows through the card, which is then let go;
     *  - the glass panel slides up from beyond the screen's edge and its lines come one after another, the cross last.
     * Opened away from the page, the window comes out of the dark and the cover rises into its place instead.
     */
    private fun entrance(card:PageCard?,glow:View,scrim:View,cover:View,panel:LinearLayout,close:View){entered=true
        val p=root?:return
        if(card!=null){val at=IntArray(2);cover.getLocationInWindow(at);val o=IntArray(2);card.getLocationInWindow(o)
            card.aim(RectF((at[0]-o[0]).toFloat(),(at[1]-o[1]).toFloat(),(at[0]-o[0]+cover.width).toFloat(),(at[1]-o[1]+cover.height).toFloat()))
            // the page flies and turns into the cover on the way; the real cover only takes over once the card has
            // become it (same picture, same place), so nothing changes there
            // landed, the card is the cover (same picture, same place, no shadow): the real one takes its place, and
            // its shadow comes up under it slowly
            (cover as? CoverView)?.shadowFade=0f
            clock(820,0,android.view.animation.LinearInterpolator(),{f->card.time=f;card.t=glide.getInterpolation(f)}){cover.alpha=1f;card.visibility=View.GONE
                (cover as? CoverView)?.let{cv->clock(700,0,MotionSpec.enter,{cv.shadowFade=it})}}
            backdrop?.animate()?.alpha(0f)?.setDuration(520)?.setInterpolator(MotionSpec.move)?.start()}
        else{p.animate().alpha(1f).setDuration(MotionSpec.DARK_OUT).setInterpolator(MotionSpec.enter).start()
            cover.scaleX=.9f;cover.scaleY=.9f;cover.translationY=a.d(24).toFloat()
            cover.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f).setStartDelay(80).setDuration(680).setInterpolator(rise).withLayer().start()}
        scrim.animate().alpha(1f).setDuration(420).setInterpolator(MotionSpec.enter).start()
        glow.scaleX=1.15f;glow.scaleY=1.15f
        glow.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(1100).setInterpolator(rise).start()
        // the panel slides in whole from beyond the edge (no fading glass), its lines then come in turn
        panel.translationY=(panel.height+a.d(40)).toFloat();panel.visibility=View.VISIBLE
        panel.animate().translationY(0f).setStartDelay(320).setDuration(640).setInterpolator(rise).start()
        for(i in 0 until panel.childCount){val c=panel.getChildAt(i);c.translationY=a.d(12).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(440L+i*45).setDuration(400).setInterpolator(MotionSpec.enter).withLayer().start()}
        close.animate().alpha(1f).setStartDelay(780).setDuration(300).setInterpolator(MotionSpec.enter).start()
    }

    private fun finish(keep:Boolean,closeBook:Boolean){
        if(closed)return;closed=true
        if(!keep){before.forEach{(k,v)->if(v==null)book.remove(k) else book.put(k,v)};a.saveBook(book){}}
        a.currentFocus?.let{f->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)}
        dialog.setOnCancelListener(null)
        val r=root;if(r==null||!motion){dialog.dismiss();done(closeBook);return}
        listOfNotNull(panel,cover,glow,scrim,close).forEach{it.animate().cancel()}
        val p=panel;val c=cover;val pc=card
        // the panel goes back down beyond the edge
        p?.animate()?.translationY((p.height+a.d(40)).toFloat())?.setStartDelay(0)?.setDuration(300)?.setInterpolator(MotionSpec.exit)?.start()
        close?.animate()?.alpha(0f)?.setStartDelay(0)?.setDuration(150)?.start()
        if(!closeBook&&pc!=null&&c!=null&&c.width>0){
            // back to the page: the cover becomes the page again and opens out to the whole screen, which is the
            // reader as it was under this window
            pc.visibility=View.VISIBLE;pc.time=1f;pc.t=1f
            backdrop?.animate()?.alpha(1f)?.setStartDelay(120)?.setDuration(340)?.setInterpolator(MotionSpec.move)?.start()
            c.animate().cancel();c.alpha=0f
            glow?.animate()?.alpha(0f)?.setStartDelay(0)?.setDuration(300)?.setInterpolator(MotionSpec.exit)?.start()
            scrim?.animate()?.alpha(0f)?.setStartDelay(0)?.setDuration(300)?.start()
            clock(560,0,android.view.animation.LinearInterpolator(),{f->pc.time=1f-f;pc.t=1f-glide.getInterpolation(f)}){dialog.dismiss();done(false)}
            return}
        // leaving the book: the reader closes under this window at once, so it fades straight onto the library
        done(closeBook)
        c?.animate()?.scaleX(.94f)?.scaleY(.94f)?.alpha(0f)?.setStartDelay(0)?.setDuration(260)?.setInterpolator(MotionSpec.exit)?.withLayer()?.start()
        r.animate().alpha(0f).setStartDelay(90).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).withLayer().withEndAction{dialog.dismiss()}.start()
    }

    /** A soft, saturated wash of the cover: shrunk to a few pixels and blurred, then drawn large and smooth. */
    private fun blurred(src:Bitmap):Bitmap{
        // halving again and again and growing back with filtering is a cheap, even blur
        val tiny=Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src,24,36,true),12,18,true),6,9,true)
        val out=Bitmap.createBitmap(48,72,Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(Bitmap.createScaledBitmap(tiny,12,18,true),null,Rect(0,0,48,72),Paint(Paint.FILTER_BITMAP_FLAG).apply{colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.3f)})})
        return out
    }
}
