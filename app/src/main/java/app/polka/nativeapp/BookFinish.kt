package app.polka.nativeapp

import android.app.Dialog
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
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
 * The end of a book as an event: the cover in its own light, how many days and hours it took, and right under it the
 * rating (half stars), the marks and the review. «Готово» keeps what was given, «Без оценки» leaves the book without
 * what was put in here. The window is its own, over the reader or the library.
 */
internal class BookFinish(private val a:MainActivity,private val book:JSONObject,private val done:(closeBook:Boolean)->Unit){
    private val dialog=Dialog(a,android.R.style.Theme_Black_NoTitleBar)
    private val before=listOf("rating","reviewTags","review","reviewAt").associateWith{book.opt(it)}
    private var closed=false

    fun show(){
        val root=FrameLayout(a).apply{setBackgroundColor(a.bg)}
        // the cover's own colours, blurred, behind the top of the page and fading into it
        val glow=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP;alpha=if(a.light).4f else .55f}
        root.addView(glow,FrameLayout.LayoutParams(-1,a.d(520)))
        root.addView(View(a).apply{background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(a.bg and 0x00ffffff,a.bg and 0x00ffffff,a.bg))},FrameLayout.LayoutParams(-1,a.d(560)))
        val scroll=ScrollView(a).apply{isFillViewport=true;overScrollMode=View.OVER_SCROLL_NEVER;isVerticalScrollBarEnabled=false}
        val page=a.col().apply{clipChildren=false;clipToPadding=false}
        scroll.addView(page,FrameLayout.LayoutParams(-1,-2))
        root.addView(scroll,FrameLayout.LayoutParams(-1,-1))

        val top=FrameLayout(a)
        val close=FrameLayout(a).apply{background=a.shape(if(a.light)0x14000000 else 0x33000000,20);contentDescription="Закрыть";isClickable=true;isFocusable=true
            addView(IconView(a,"x",a.fg),FrameLayout.LayoutParams(a.d(18),a.d(18),Gravity.CENTER));setOnClickListener{finish(false,false)}}
        top.addView(close,FrameLayout.LayoutParams(a.d(40),a.d(40),Gravity.END))
        page.addView(top,LinearLayout.LayoutParams(-1,-2))

        val cover=CoverView(a,book,a.cover(book)).apply{softShadow=true}
        page.addView(cover,LinearLayout.LayoutParams(a.d(124),a.d(186)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=a.d(4)})
        cover.prepareArtwork{glow.setImageDrawable(BitmapDrawable(a.resources,blurred(cover.glowArtwork())).apply{isFilterBitmap=true})}
        page.addView(a.text(book.str("title"),28f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER;maxLines=3},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(22)})
        MainActivity.authors(book).takeIf{it.isNotBlank()}?.let{page.addView(a.text(it,14f,if(a.light)0xff5a5650.toInt() else 0xffc9c2b8.toInt()).apply{gravity=Gravity.CENTER;maxLines=2},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})}

        val tiles=a.row().apply{gravity=Gravity.TOP}
        page.addView(tiles,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(22)})
        val review=ReviewBlock(a,book)
        page.addView(review,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        page.addView(View(a),LinearLayout.LayoutParams(1,0,1f))
        page.addView(a.button("Готово",true){finish(true,true)}.apply{background=a.shape(a.fg,25);minHeight=a.d(50)},LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=a.d(24)})
        page.addView(a.text("Без оценки",15f,a.muted).apply{gravity=Gravity.CENTER;isClickable=true;isFocusable=true;setOnClickListener{finish(false,true)}},LinearLayout.LayoutParams(-1,a.d(44)).apply{topMargin=a.d(2)})

        // the facts: how many days (from — to) and how long it was read
        var journey:BookJourney?=null
        a.work({val data=a.library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")}
            val end=book.optLong("completedAt").takeIf{it>0}?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()}?:LocalDate.now()
            journey=BookJourney.of(data,book,end)}){val j=journey?:return@work
            fun tile(value:String,caption:String){val t=a.col().apply{background=a.shape(if(a.light)0x0d000000 else 0x12ffffff,16);setPadding(a.d(12),a.d(12),a.d(12),a.d(11))}
                t.addView(a.text(value,21f,a.fg,true,serif=true).apply{maxLines=1})
                t.addView(a.text(caption,12f,a.muted).apply{maxLines=1;setPadding(0,a.d(4),0,0)})
                tiles.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{if(tiles.childCount>0)leftMargin=a.d(8)})}
            j.days?.let{n->tile("$n ${daysWordRu(n)}",j.range())}?:tile(dayMonth(j.end),"прочитана")
            if(j.ms>=60_000L)tile(duration(j.ms),"за чтением")}

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
            page.setPadding(a.d(20)+bars.left,bars.top+a.d(10),a.d(20)+bars.right,maxOf(bars.bottom,keyboard)+a.d(12));ins}
        ImeKeeper(scroll,a.d(20)){keyboard}.attach()
        // the end of the book comes out of the dark, like the book itself (see MotionSpec)
        val motion=a.prefs.str("motion")!="reduced"
        dialog.window?.setWindowAnimations(0)
        dialog.show()
        if(motion){root.alpha=0f;root.animate().alpha(1f).setDuration(MotionSpec.DARK_OUT).setInterpolator(MotionSpec.enter).start()}
        this.root=root
    }
    private var root:View?=null

    private fun finish(keep:Boolean,closeBook:Boolean){
        if(closed)return;closed=true
        if(!keep){before.forEach{(k,v)->if(v==null)book.remove(k) else book.put(k,v)};a.saveBook(book){}}
        a.currentFocus?.let{f->(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(f.windowToken,0)}
        dialog.setOnCancelListener(null)
        val r=root;if(r==null||a.prefs.str("motion")=="reduced"){dialog.dismiss();done(closeBook);return}
        r.animate().alpha(0f).setDuration(MotionSpec.DARK_IN).setInterpolator(MotionSpec.exit).withEndAction{dialog.dismiss();done(closeBook)}.start()
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
