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

    /** Takes a picture of the screen as it is (the last page) first, so the page can turn into the cover. */
    fun show(){
        if(!motion){build(null);return}
        val v=a.window.decorView
        if(v.width<=0||v.height<=0){build(null);return}
        val bmp=try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888)}catch(_:Throwable){build(null);return}
        try{android.view.PixelCopy.request(a.window,bmp,{res->if(a.isFinishing||a.isDestroyed)return@request
                if(res==android.view.PixelCopy.SUCCESS)build(bmp) else{bmp.recycle();build(null)}},android.os.Handler(android.os.Looper.getMainLooper()))}
        catch(_:Throwable){bmp.recycle();build(null)}
    }
    private fun build(shot:Bitmap?){
        val ground=if(a.light)0xffe9e7e3.toInt() else 0xff0e0e10.toInt()
        val root=FrameLayout(a).apply{setBackgroundColor(if(shot!=null)Color.BLACK else ground)}
        // approved «Страница в обложку»: the screen as it was is the first layer; it closes in on the cover's place
        val shrink=shot?.let{Shrink(it).also{v->root.addView(v,FrameLayout.LayoutParams(-1,-1))}}
        val dark=View(a).apply{setBackgroundColor(ground)};root.addView(dark,FrameLayout.LayoutParams(-1,-1))
        // the cover's colours over the whole screen: darkened (lightened in the light theme) more toward the foot
        val light=FrameLayout(a)
        val glow=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP
            colorFilter=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(1.4f);if(!a.light)postConcat(ColorMatrix().apply{setScale(.75f,.75f,.75f,1f)})})}
        light.addView(glow,FrameLayout.LayoutParams(-1,-1))
        root.addView(light,FrameLayout.LayoutParams(-1,-1))
        light.addView(View(a).apply{background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            if(a.light)intArrayOf(0x1affffff,0x66ffffff,0xa6ffffff.toInt()) else intArrayOf(0x0d000000,0x59000000,0x99000000.toInt()))},FrameLayout.LayoutParams(-1,-1))
        val scroll=ScrollView(a).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false}
        val page=a.col().apply{clipChildren=false;clipToPadding=false}
        scroll.addView(page,FrameLayout.LayoutParams(-1,-2))
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val close=FrameLayout(a).apply{background=a.shape(if(a.light)0x14000000 else 0x40000000,20);contentDescription="Закрыть";isClickable=true;isFocusable=true
            addView(IconView(a,"x",a.fg),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER));setOnClickListener{finish(false,false)}}
        root.addView(close,FrameLayout.LayoutParams(a.d(40),a.d(40),Gravity.TOP or Gravity.END).apply{rightMargin=a.d(18)})

        // the cover, as large as the room above the panel allows
        val cover=CoverView(a,book,a.cover(book)).apply{softShadow=true}
        val coverBox=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
        coverBox.addView(cover,FrameLayout.LayoutParams(a.d(200),a.d(300),Gravity.CENTER_HORIZONTAL))
        page.addView(coverBox,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(52)})
        // the light never comes at once: when the cover's colours are ready late, they fade in too
        cover.prepareArtwork{glow.setImageDrawable(BitmapDrawable(a.resources,blurred(cover.glowArtwork())).apply{isFilterBitmap=true})
            if(motion){glow.alpha=0f;glow.animate().alpha(1f).setDuration(700).setInterpolator(MotionSpec.enter).start()}}
        page.addView(View(a),LinearLayout.LayoutParams(1,0,1f))

        // the glass panel: how long it took, the title, the rating and the review, the two ways out
        val panel=a.col().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(20),a.d(22),a.d(20),a.d(8))
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0xd1f7f7f5.toInt() else 0xb81c1c1e.toInt());cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x14000000 else 0x14ffffff)}}
        val kicker=a.text("ПРОЧИТАНО",11.5f,if(a.light)0xff6e6e73.toInt() else 0xffa9a9ae.toInt(),true).apply{letterSpacing=.14f;gravity=Gravity.CENTER}
        panel.addView(kicker,LinearLayout.LayoutParams(-1,-2))
        panel.addView(a.text(book.str("title"),26f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=3},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        MainActivity.authors(book).takeIf{it.isNotBlank()}?.let{panel.addView(a.text(it,14f,a.muted).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})}
        val facts=a.text("",13f,a.muted).apply{gravity=Gravity.CENTER;visibility=View.GONE}
        panel.addView(facts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        // the rating lies on the glass itself, no card of its own
        val review=ReviewBlock(a,book).apply{background=null;elevation=0f;clipToOutline=false;setPadding(0,a.d(6),0,0)}
        panel.addView(review,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        panel.addView(a.button("Готово",true){finish(true,true)}.apply{background=a.shape(a.fg,25);minHeight=a.d(50)},LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=a.d(18)})
        panel.addView(a.text("Без оценки",15f,a.muted).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener{finish(false,true)}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(2)})
        page.addView(panel,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(24)})

        // the cover takes the room left above the panel (300 dp at most, 150 at least) and glides to a new size
        var sizing:android.animation.ValueAnimator?=null
        fun fit(animate:Boolean){val lp=cover.layoutParams as FrameLayout.LayoutParams
            val free=scroll.height-page.paddingTop-page.paddingBottom-a.d(52)-a.d(24)-panel.height
            val to=free.coerceIn(a.d(150),a.d(300));if(scroll.height==0||kotlin.math.abs(to-lp.height)<3)return
            sizing?.cancel()
            fun set(h:Int){lp.height=h;lp.width=(h/1.5f).toInt();cover.layoutParams=lp}
            if(!animate||a.prefs.str("motion")=="reduced"){set(to);return}
            sizing=android.animation.ValueAnimator.ofInt(lp.height,to).apply{duration=MotionSpec.SETTLE;interpolator=MotionSpec.move;addUpdateListener{set(it.animatedValue as Int)};start()}}
        var seen=-1;var first=true
        page.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->val key=panel.height*31+scroll.height+page.paddingBottom
            if(key!=seen){seen=key;val anim=!first;first=false
                page.post{fit(anim);if(!anim&&shrink!=null)startOnceLaidOut(root){entrance(shrink,dark,light,glow,cover,panel,close)}}}}

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
        this.root=root;this.cover=coverBox;this.panel=panel
        // until the move starts only the screen as it was shows, so opening the window changes nothing on it
        if(shrink!=null){dark.alpha=0f;light.alpha=0f;cover.alpha=0f;close.alpha=0f;panel.alpha=0f;for(i in 0 until panel.childCount)panel.getChildAt(i).alpha=0f}
        dialog.show()
    }
    private var root:View?=null

    /** Runs [go] on the next frame drawn after the layout that is pending now (the cover at its final size). */
    private fun startOnceLaidOut(v:View,go:()->Unit){v.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{
        override fun onPreDraw():Boolean{v.viewTreeObserver.removeOnPreDrawListener(this);go();return true}});v.invalidate()}

    /** The last page, drawn inside a rounded window that closes in on the cover's place, a little smaller and darker
     *  as it goes ([t] 0…1). */
    private inner class Shrink(val shot:Bitmap):View(a){
        var t=0f;set(v){field=v;invalidate()}
        val target=RectF()
        private val p=Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG);private val clip=Path();private val dst=RectF()
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val k=t
            clip.rewind();val rad=a.d(5)*k
            clip.addRoundRect(target.left*k,target.top*k,w+(target.right-w)*k,h+(target.bottom-h)*k,rad,rad,Path.Direction.CW)
            c.save();c.clipPath(clip);val s=1f-.38f*k;c.scale(s,s,w/2,h/2)
            val g=(255*(1f-.2f*k)).toInt();p.colorFilter=LightingColorFilter(Color.rgb(g,g,g),0)
            dst.set(0f,0f,w,h);c.drawBitmap(shot,null,dst,p);c.restore()}
        override fun onDetachedFromWindow(){super.onDetachedFromWindow();shot.recycle()}
    }

    /** As approved in the prototype («Страница в обложку»): the last page closes in on the cover's place while the
     *  dark comes over it and the cover shows through, the cover's light comes up behind, the glass panel rises from
     *  below and its lines come one after another. */
    private fun entrance(shrink:Shrink,dark:View,light:View,glow:View,cover:View,panel:LinearLayout,close:View){
        val at=IntArray(2);cover.getLocationInWindow(at);val o=IntArray(2);shrink.getLocationInWindow(o)
        shrink.target.set((at[0]-o[0]).toFloat(),(at[1]-o[1]).toFloat(),(at[0]-o[0]+cover.width).toFloat(),(at[1]-o[1]+cover.height).toFloat())
        fun clock(ms:Long,delay:Long=0,curve:android.animation.TimeInterpolator=MotionSpec.enter,step:(Float)->Unit)=
            android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=ms;startDelay=delay;interpolator=curve;addUpdateListener{step(it.animatedValue as Float)};start()}
        // the page: 760 ms into the cover's place; it is let go of once the cover has come through it
        clock(760,0,rise){shrink.t=it}
        clock(820,0,android.view.animation.LinearInterpolator()){shrink.alpha=if(it<.7f)1f else 1f-(it-.7f)/.3f;if(it>=1f)shrink.visibility=View.GONE}
        dark.animate().alpha(1f).setDuration(420).setInterpolator(MotionSpec.enter).start()
        // the cover shows through from a quarter of the way
        cover.animate().alpha(1f).setStartDelay(155).setDuration(465).setInterpolator(MotionSpec.move).start()
        // the light: up over 1.1 s, settling from a little larger
        light.animate().alpha(1f).setStartDelay(260).setDuration(1100).setInterpolator(MotionSpec.enter).start()
        glow.scaleX=1.18f;glow.scaleY=1.18f;glow.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(1700).setInterpolator(rise).start()
        // the panel from below, then its lines in turn, the cross last
        panel.alpha=0f;panel.translationY=a.d(70).toFloat()
        panel.animate().alpha(1f).translationY(0f).setStartDelay(420).setDuration(760).setInterpolator(rise).start()
        for(i in 0 until panel.childCount){val c=panel.getChildAt(i);c.translationY=a.d(10).toFloat()
            c.animate().alpha(1f).translationY(0f).setStartDelay(560L+i*60).setDuration(520).setInterpolator(MotionSpec.enter).start()}
        close.animate().alpha(1f).setStartDelay(920).setDuration(400).setInterpolator(MotionSpec.enter).start()
    }

    private fun finish(keep:Boolean,closeBook:Boolean){
        if(closed)return;closed=true
        if(!keep){before.forEach{(k,v)->if(v==null)book.remove(k) else book.put(k,v)};a.saveBook(book){}}
        a.currentFocus?.let{f->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)}
        dialog.setOnCancelListener(null)
        val r=root;if(r==null||!motion){dialog.dismiss();done(closeBook);return}
        // the panel drops away and the cover sinks back into the dark as it closes
        panel?.let{p->p.animate().cancel();p.animate().translationY(p.height*.35f).alpha(0f).setStartDelay(0).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).start()}
        cover?.let{c->c.animate().cancel();c.animate().scaleX(.94f).scaleY(.94f).alpha(0f).setStartDelay(0).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).start()}
        r.animate().alpha(0f).setStartDelay(80).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).withEndAction{dialog.dismiss();done(closeBook)}.start()
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
