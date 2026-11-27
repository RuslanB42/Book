package app.polka.nativeapp

import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.os.Build
import android.provider.MediaStore
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import kotlin.math.*

/** Ratings shown as tiers: S is five stars, D is one. Dropping a book into another row changes its rating. */
internal object Tiers {
    val letters=listOf("S","A","B","C","D")
    val names=listOf("Любимые","Понравились","Неплохо","Так себе","Не понравились")
    fun of(book:JSONObject)=Rating.halves(book).let{h->if(h>0)5-(h+1)/2 else -1}
    /** Light and deep tone of each tier's letter. Approved «Приглушённая»: the classic tier-list order (S red, A orange,
     *  B amber, C yellow, D green), quieter and denser so it sits on the app's graphite; deeper on a light page. */
    fun tone(a:MainActivity,k:Int):Pair<Int,Int> =if(a.light)when(k){0->0xffd9625c.toInt() to 0xffb8433d.toInt();1->0xffd98a4a.toInt() to 0xffb0662a.toInt();2->0xffcfa548.toInt() to 0xffa37c22.toInt();3->0xffb5a83e.toInt() to 0xff8a7f22.toInt();else->0xff7fa650.toInt() to 0xff5c8233.toInt()}
        else when(k){0->0xfff2a3a0.toInt() to 0xffd9625c.toInt();1->0xfff2c49b.toInt() to 0xffd98a4a.toInt();2->0xfff0d79a.toInt() to 0xffcfa548.toInt();3->0xffe8e2a0.toInt() to 0xffbdb45a.toInt();else->0xffc9e3a2.toInt() to 0xff8fb862.toInt()}
    /** Finished books of [year] (all of them when null), own order inside a tier first, then the latest finished. */
    fun books(model:ReadingStatistics,year:Int?)=model.finishedAll.filter{year==null||it.date?.year==year}.map{it.book}
    fun sorted(list:List<JSONObject>)=list.sortedWith(compareBy<JSONObject>{it.optDouble("tierPos",Double.MAX_VALUE)}.thenByDescending{it.optLong("completedAt")})
    fun years(model:ReadingStatistics)=model.finishedAll.mapNotNull{it.date?.year}.distinct().sortedDescending()

    // ── a series of comics is one place in the list (approved «Веер»): its read issues fanned behind the first ──
    /** The series a comic belongs to (null for books, PDFs and comics without one). */
    fun seriesKey(b:JSONObject):String?=if(isComic(b))b.str("series").trim().lowercase().takeIf{it.isNotBlank()} else null
    /** The issues behind each series place, by the place's id. */
    val members=HashMap<String,List<JSONObject>>()
    fun isSeries(o:JSONObject)=o.str("id").startsWith("series:")
    private fun issueOrder(list:List<JSONObject>)=list.sortedWith(compareBy<JSONObject>{it.optDouble("seriesNo",Double.MAX_VALUE)}.thenBy{it.optLong("completedAt")})
    /** [list] with the comics of one series (two read issues or more) gathered into one place each. */
    fun group(a:MainActivity,list:List<JSONObject>):List<JSONObject>{
        val by=list.groupBy{seriesKey(it)};val made=HashSet<String>();val out=ArrayList<JSONObject>()
        list.forEach{b->val k=seriesKey(b);val g=k?.let{by[it]}
            if(k==null||g==null||g.size<2)out.add(b)
            else if(made.add(k)){val issues=issueOrder(g);val id="series:$k";members[id]=issues
                val store=stored(a,k)
                out.add(JSONObject().put("id",id).put("series",k).put("title",issues.first().str("series").trim()).put("completedAt",issues.maxOf{it.optLong("completedAt")})
                    .apply{store?.optDouble("pos")?.takeIf{!it.isNaN()}?.let{put("tierPos",it)}})}}
        return out}
    private fun stored(a:MainActivity,key:String)=a.prefs.optJSONObject("seriesTiers")?.optJSONObject(key)
    /** The tier of a book, or of a series: the one it was put in by hand, else the mean of its issues' stars. */
    fun tierOf(a:MainActivity,o:JSONObject):Int{val issues=members[o.str("id")]?:return of(o)
        stored(a,o.str("series"))?.takeIf{it.has("k")}?.let{return it.optInt("k",-1)}
        val rated=issues.map{Rating.halves(it)}.filter{it>0};if(rated.isEmpty())return -1
        val h=Math.round(rated.average()).toInt().coerceIn(1,10);return 5-(h+1)/2}
    /** Puts series [key] into tier [k] (-1: not rated) at place [pos] of its row. */
    fun place(a:MainActivity,key:String,k:Int?,pos:Double){val all=a.prefs.optJSONObject("seriesTiers")?:JSONObject().also{a.prefs.put("seriesTiers",it)}
        val e=all.optJSONObject(key)?:JSONObject().also{all.put(key,it)};if(k!=null)e.put("k",k);e.put("pos",pos)}
    /** «8 книг, 3 серии комиксов». */
    fun count(list:List<JSONObject>):String{val series=list.count{isSeries(it)};val books=list.size-series
        val sw=if(series%100 in 11..14)"серий" else when(series%10){1->"серия";2,3,4->"серии";else->"серий"}
        return when{series==0->MainActivity.countBooks(books);books==0->"$series $sw комиксов";else->"${MainActivity.countBooks(books)}, $series $sw комиксов"}}
}

/** A series in the tier list: the first read issue in front, the next two fanned behind it, quieter, and how many
 *  issues were read in a small dark tag at its foot. */
internal class SeriesFan(a:MainActivity,issues:List<JSONObject>,small:Boolean):FrameLayout(a){
    init{clipChildren=false;clipToPadding=false
        val k=if(small)40f/48f else 1f;fun dp(v:Float)=(a.d(1)*v*k).toInt()
        val w=dp(48f);val h=dp(72f);val show=issues.take(3)
        for(i in show.indices.reversed()){val b=show[i];val cv=CoverView(a,b,a.cover(b))
            if(i>0){cv.pivotX=w/2f;cv.pivotY=h.toFloat();cv.rotation=if(i==1)4.5f else 9f
                val g=if(i==1)191 else 140;cv.setLayerType(View.LAYER_TYPE_HARDWARE,Paint().apply{colorFilter=LightingColorFilter(Color.rgb(g,g,g),0)})}
            addView(cv,LayoutParams(w,h).apply{leftMargin=if(i==0)dp(2f) else dp(6f);topMargin=dp(3f)})}
        addView(TextView(a).apply{text="${issues.size} вып.";textSize=maxOf(Type.CAPTION,10.5f*k);typeface=PolkaFonts.sans(600);setTextColor(0xfff3f2ef.toInt());gravity=Gravity.CENTER
            includeFontPadding=false;setPadding(dp(7f),0,dp(7f),0)
            background=GradientDrawable().apply{cornerRadius=dp(10f).toFloat();setColor(0xe0121214.toInt());setStroke(1,0x24ffffff)};elevation=a.d(3).toFloat()},
            LayoutParams(-2,dp(20f),Gravity.BOTTOM or Gravity.START).apply{leftMargin=dp(2f)})
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES}
    companion object{fun size(a:MainActivity,small:Boolean)=if(small)Pair(a.d(50),a.d(68)) else Pair(a.d(60),a.d(82))}
}

/** A tier letter: big serif, filled top to bottom with the tier's two tones and a soft glow of the same colour. */
internal class TierLetter(private val a:MainActivity,private val k:Int,size:Float,private val glow:Boolean=true):TextView(a){
    init{text=Tiers.letters[k];textSize=size;typeface=PolkaFonts.serif(a,true);includeFontPadding=false;gravity=Gravity.CENTER
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;setLayerType(LAYER_TYPE_SOFTWARE,null)
        // the gradient is drawn through the text colour's alpha: the theme's default text colour is see-through and faded the letters
        setTextColor(Color.WHITE)}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){super.onSizeChanged(w,h,ow,oh);val (c1,c2)=Tiers.tone(a,k)
        paint.shader=LinearGradient(0f,0f,0f,h.toFloat(),c1,c2,Shader.TileMode.CLAMP)
        if(glow&&!a.light)setShadowLayer(a.d(14).toFloat(),0f,0f,(c2 and 0xffffff) or (0x66 shl 24)) else setShadowLayer(0f,0f,0f,0)}
}

/** The tier screen's backdrop: the page colour with a warm golden light from the top. Baked once, like the goal road's. */
internal class TierBackdrop(private val a:MainActivity):android.graphics.drawable.Drawable(){
    private val p=Paint(Paint.FILTER_BITMAP_FLAG)
    override fun draw(c:Canvas){val b=bounds;c.drawColor(a.bg);val t=bake(a,b.width())?:return;c.drawBitmap(t,b.left.toFloat(),b.top.toFloat(),p)}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
    companion object{
        private var tex:Bitmap?=null;private var key=""
        fun prepare(a:MainActivity){val w=a.resources.displayMetrics.widthPixels;val k="$w${a.light}";if(k==key)return;a.work({bake(a,w)})}
        @Synchronized fun bake(a:MainActivity,w:Int):Bitmap?{if(w<=0)return null;val k="$w${a.light}";if(k==key&&tex!=null)return tex
            val rx=w*1.05f;val h=(rx*1.1f).toInt()
            val out=Glow.face(w,h,a.bg,if(a.light)0xfff5b52a.toInt() else 0xffe0a12a.toInt(),w*.5f,0f,rx*1.5f,if(a.light).12f else .16f,2.6f*1.7f*1.7f)?:return null;tex=out;key=k;return out}
    }
}

/** One tier on the full screen: the letter with its name on the left, the covers on the right, a hairline above. */
internal class TierRow(private val a:MainActivity,val k:Int,val covers:FlowRow):LinearLayout(a){
    private val hair=Paint().apply{color=Ui.fill(a.light)}
    private val band:InsetDrawable
    var lit=false;private set
    init{orientation=HORIZONTAL;clipChildren=false;clipToPadding=false;setWillNotDraw(false)
        setPadding(a.d(8),a.d(11),a.d(18),a.d(11));minimumHeight=a.d(100)
        val label=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(0,a.d(4),0,0)}
        if(k<5){label.addView(TierLetter(a,k,40f,false),LinearLayout.LayoutParams(-2,a.d(50)))
            label.addView(a.text(Tiers.names[k],Type.CAPTION,a.muted).apply{maxLines=1;gravity=Gravity.CENTER;setPadding(0,a.d(4),0,0)})}
        addView(label,LayoutParams(a.d(84),-2))
        addView(covers,LayoutParams(0,-2,1f).apply{marginStart=a.d(4);topMargin=a.d(2)})
        val (_,c2)=if(k<5)Tiers.tone(a,k) else (0xff8a8a90.toInt() to 0xff8a8a90.toInt())
        val g=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf((c2 and 0xffffff) or (0x26 shl 24),(c2 and 0xffffff) or (0x0a shl 24),(c2 and 0xffffff) or (0x05 shl 24))).apply{cornerRadius=a.d(18).toFloat();setStroke(a.d(1),(c2 and 0xffffff) or (0x40 shl 24))}
        band=InsetDrawable(g,a.d(8),a.d(2),a.d(8),a.d(2)).apply{alpha=0};background=band}
    fun light(on:Boolean,motion:Boolean){if(on==lit)return;lit=on;invalidate()
        if(!motion){band.alpha=if(on)255 else 0;return}
        android.animation.ValueAnimator.ofInt(band.alpha,if(on)255 else 0).apply{duration=if(on)200 else 260;addUpdateListener{band.alpha=it.animatedValue as Int}}.start()}
    override fun onDraw(c:Canvas){super.onDraw(c);if(k in 1..4&&!lit)c.drawRect(a.d(100).toFloat(),0f,(width-a.d(18)).toFloat(),a.d(1).toFloat(),hair)}
}

/**
 * Тир-лист — opened from its card in the statistics. Rows S…D are the ratings 5…1; a long press lifts a cover,
 * the row under the finger lights up in its colour and opens a place, letting go puts the book there and saves the
 * new rating. A short tap opens the book card. The share button makes a picture of the list.
 */
internal class TierListOverlay(private val a:MainActivity,private val model:ReadingStatistics,startYear:Int?,private val source:()->View?,private val onClose:()->Unit):SwipeClosable{
    private val motion get()=a.prefs.str("motion")!="reduced"
    private val years=Tiers.years(model)
    private var year:Int?=startYear?.takeIf{it in years}?:years.firstOrNull()
    private val frame=Frame()
    private val col=a.column()
    private val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;clipToPadding=false;isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(24))}
    private val page=a.column().apply{clipChildren=false;clipToPadding=false}
    // approved (variant А): one list for everything, narrowed by pills above it — the year, books or comics, the genre;
    // a pill opens a short menu under itself, a chosen filter turns the pill white and names what is chosen
    private var kind:String?=null
    private var genre:String?=null
    private val pills=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
    private val pillStrip=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;setPadding(a.d(18),0,a.d(18),0);addView(pills)}
    private var menu:FrameLayout?=null
    private val countText=a.text("",Type.TEXT,a.muted)
    private val rowsBox=a.column().apply{clipChildren=false;clipToPadding=false}
    private val rows=mutableListOf<TierRow>()
    private var closing=false
    private var share:FrameLayout?=null
    private var poster:Bitmap?=null
    private val shown=HashMap<String,Int>()
    // it grows out of its card on the statistics and folds back into it (see CardMorph)
    private val morph=CardMorph(a,frame,col,source,24)

    private inner class Frame:FrameLayout(a){
        var lastX=0f;var lastY=0f;private var cancelled=false
        override fun dispatchTouchEvent(e:MotionEvent):Boolean{lastX=e.x;lastY=e.y
            if(drag!=null){if(!cancelled){cancelled=true;val c=MotionEvent.obtain(e).apply{action=MotionEvent.ACTION_CANCEL};super.dispatchTouchEvent(c);c.recycle()}
                dragEvent(e);return true}
            cancelled=false;return super.dispatchTouchEvent(e)}
    }

    fun show(){
        frame.isClickable=true;frame.setBackgroundColor(a.bg);frame.elevation=a.d(30).toFloat()
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),a.d(8),a.d(16),0)}
        fun round(kind:String,desc:String,click:()->Unit)=FrameLayout(a).apply{isClickable=true;contentDescription=desc;setOnClickListener{click()}
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Ui.fill(a.light))}
            addView(Glyph(a,kind,a.fg),FrameLayout.LayoutParams(a.d(19),a.d(19),Gravity.CENTER));a.press(this)}
        bar.addView(round("back","Назад"){close()},LinearLayout.LayoutParams(a.d(46),a.d(46)))
        bar.addView(a.text("ТИР-ЛИСТ",Type.SECONDARY,a.muted,true).apply{letterSpacing=.06f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(round("share","Поделиться тир-листом"){openShare()},LinearLayout.LayoutParams(a.d(46),a.d(46)))
        col.addView(bar)
        col.addView(pillStrip,LinearLayout.LayoutParams(-1,a.d(36)).apply{topMargin=a.d(18)})
        col.addView(countText.apply{setPadding(a.d(22),a.d(8),a.d(22),a.d(10))})
        page.addView(rowsBox)
        scroll.addView(page,ViewGroup.LayoutParams(-1,-2))
        col.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        frame.addView(col,FrameLayout.LayoutParams(-1,-1))
        a.followInsets(frame){i->col.setPadding(i.left,i.top,i.right,0);scroll.setPadding(0,0,0,i.bottom+a.d(28))}
        a.root.addView(frame,FrameLayout.LayoutParams(-1,-1))
        a.tierList=this
        bind(false)
        if(!motion)return
        morph.open()
    }

    private fun cell(b:JSONObject,small:Boolean=false):View{
        if(Tiers.isSeries(b)){val issues=Tiers.members[b.str("id")].orEmpty()
            return SeriesFan(a,issues,small).apply{tag=b;contentDescription="${b.str("title")}, ${issues.size} вып.";isClickable=true;isLongClickable=true;if(small)alpha=.6f
                setOnClickListener{openSeries(b)};setOnLongClickListener{startDrag(this,b);true};a.press(this)}}
        val cv=CoverView(a,b,a.cover(b)).apply{tag=b;contentDescription=b.str("title");isClickable=true;isLongClickable=true;if(small)alpha=.6f}
        cv.setOnClickListener{a.detail(b,cv)}
        cv.setOnLongClickListener{startDrag(cv,b);true}
        a.press(cv)
        a.observeBook(cv,b.str("id")){fresh->replaceJson(b,fresh);if(drag==null&&shown[b.str("id")]!=Tiers.of(b))frame.removeCallbacks(rebindTask).also{frame.postDelayed(rebindTask,120)}}
        return cv}
    private val rebindTask=Runnable{if(drag==null&&!closing)bind(true)}
    private fun cellSize(small:Boolean)=if(small)Pair(a.d(40),a.d(60)) else Pair(a.d(48),a.d(72))
    private fun sizeOf(b:JSONObject,small:Boolean)=if(Tiers.isSeries(b))SeriesFan.size(a,small) else cellSize(small)
    private fun tierOf(b:JSONObject)=Tiers.tierOf(a,b)
    /** The series' sheet: its read issues with their numbers and stars, how many there are, the series' letter. */
    private fun openSeries(item:JSONObject){val issues=Tiers.members[item.str("id")]?:return
        val total=a.books.count{Tiers.seriesKey(it)==item.str("series")}
        val box=a.column().apply{setPadding(a.d(20),a.d(4),a.d(20),a.d(12))}
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(a.text("${total} ${if(total%100 in 11..14)"выпусков" else when(total%10){1->"выпуск";2,3,4->"выпуска";else->"выпусков"}}, прочитано ${issues.size}",Type.SECONDARY,a.muted),LinearLayout.LayoutParams(0,-2,1f))
        tierOf(item).takeIf{it>=0}?.let{k->head.addView(TierLetter(a,k,34f,false),LinearLayout.LayoutParams(a.d(44),a.d(44)))}
        box.addView(head,LinearLayout.LayoutParams(-1,-2))
        var dialog:android.app.AlertDialog?=null
        issues.chunked(4).forEach{chunk->val rowL=a.line().apply{gravity=Gravity.TOP}
            chunk.forEach{b->val c=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
                val cover=FrameLayout(a)
                cover.addView(CoverView(a,b,a.cover(b)).apply{isClickable=true;contentDescription=b.str("title");setOnClickListener{dialog?.dismiss();a.detail(b,this)};a.press(this)},FrameLayout.LayoutParams(-1,-1))
                b.optDouble("seriesNo").takeIf{!it.isNaN()}?.let{n->cover.addView(a.text("#"+(if(n%1.0==0.0)n.toLong().toString() else n.toString()),Type.CAPTION,0xfff3f2ef.toInt(),true).apply{setPadding(a.d(6),a.d(1),a.d(6),a.d(1))
                    background=GradientDrawable().apply{cornerRadius=a.d(9).toFloat();setColor(0x99000000.toInt())};elevation=a.d(2).toFloat()},FrameLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(5);topMargin=a.d(5)})}
                c.addView(cover,LinearLayout.LayoutParams(a.d(68),a.d(102)))
                val h=Rating.halves(b)
                c.addView(a.text(if(h>0)"★ ${Rating.text(h)}" else "",Type.CAPTION,StarRow.starYellow(a),true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,a.d(20)).apply{topMargin=a.d(4)})
                rowL.addView(c,LinearLayout.LayoutParams(0,-2,1f))}
            repeat(4-chunk.size){rowL.addView(View(a),LinearLayout.LayoutParams(0,1,1f))}
            box.addView(rowL,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})}
        dialog=a.dialog().setTitle(item.str("title")).setView(ScrollView(a).apply{addView(box)}).show()}

    // ───── filters ─────
    private fun genresOf(b:JSONObject)=b.optJSONArray("genres")?.strings().orEmpty().mapNotNull{Genres.name(it)}.distinct()
    /** The finished books the filters let through. */
    private fun filtered()=Tiers.books(model,year).filter{b->(kind==null||(kind=="comic")==isComic(b))&&(genre==null||genre in genresOf(b))}
    private val kinds=listOf<String?>(null,"book","comic")
    private val kindNames=listOf("Книги и комиксы","Книги","Комиксы")
    /** The pills: the year always; books or comics only when there are both; the genre only when the books have any. */
    private fun renderPills(){pills.removeAllViews();val all=Tiers.books(model,null)
        fun add(label:String,on:Boolean,open:(View)->Unit){val ink=if(on)a.bg else a.fg
            val p=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(11),0);isClickable=true;contentDescription=label
                background=GradientDrawable().apply{cornerRadius=a.d(18).toFloat();setColor(if(on)a.fg else Ui.fill(a.light))};a.press(this)}
            p.addView(a.text(label,Type.TEXT,ink,true).apply{maxLines=1})
            p.addView(IconView(a,"chevdown",ink),LinearLayout.LayoutParams(a.d(13),a.d(13)).apply{marginStart=a.d(6)})
            p.setOnClickListener{open(p)}
            pills.addView(p,LinearLayout.LayoutParams(-2,a.d(36)).apply{if(pills.childCount>0)marginStart=a.d(8)})}
        val ys=listOf<Int?>(null)+years
        add(year?.toString()?:"Все годы",year!=null){v->choose(v,ys.map{it?.toString()?:"Все годы"},ys.indexOf(year)){i->year=ys[i];refilter()}}
        if(all.any{isComic(it)}&&all.any{!isComic(it)}||kind!=null)
            add(kindNames[kinds.indexOf(kind)],kind!=null){v->choose(v,kindNames,kinds.indexOf(kind)){i->kind=kinds[i];refilter()}}
        val gs=all.flatMap{genresOf(it)}.distinct().sortedWith(compareBy(java.text.Collator.getInstance(java.util.Locale("ru"))){it})
        if(gs.isNotEmpty()||genre!=null){val opts=listOf<String?>(null)+gs
            add(genre?:"Жанр",genre!=null){v->choose(v,listOf("Все жанры")+gs,opts.indexOf(genre).coerceAtLeast(0)){i->genre=opts[i];refilter()}}}
    }
    private fun refilter(){scroll.scrollTo(0,0);bind(true)}
    /** A short menu under [anchor]: the chosen line marked with a tick; picking one closes it. */
    private fun choose(anchor:View,items:List<String>,chosen:Int,pick:(Int)->Unit){if(menu!=null||drag!=null||closing)return
        val layer=FrameLayout(a).apply{isClickable=true;setOnClickListener{closeMenu()}}
        val card=FrameLayout(a).apply{background=GradientDrawable().apply{setColor(Ui.card(a.light));cornerRadius=a.d(18).toFloat()}
            elevation=a.d(16).toFloat();clipToOutline=true;isClickable=true}
        val list=a.column().apply{setPadding(a.d(6),a.d(6),a.d(6),a.d(6))}
        items.forEachIndexed{i,t->val on=i==chosen
            val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(12),0);isClickable=true;contentDescription=t
                if(on)background=GradientDrawable().apply{setColor(Ui.fill(a.light));cornerRadius=a.d(12).toFloat()}
                setOnClickListener{closeMenu();if(i!=chosen)pick(i)};a.press(this)}
            row.addView(a.text(t,Type.TEXT,a.fg,on).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f))
            if(on)row.addView(IconView(a,"check",a.fg),LinearLayout.LayoutParams(a.d(16),a.d(16)).apply{marginStart=a.d(12)})
            list.addView(row.apply{minimumHeight=a.d(Size.TOUCH)},LinearLayout.LayoutParams(-1,-2))}
        card.addView(ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(list)},FrameLayout.LayoutParams(-1,-1))
        val at=IntArray(2);anchor.getLocationInWindow(at);val f=IntArray(2);frame.getLocationInWindow(f)
        val w=maxOf(anchor.width,a.d(210));val x=(at[0]-f[0]).coerceIn(a.d(16),maxOf(a.d(16),frame.width-a.d(16)-w));val y=at[1]-f[1]+anchor.height+a.d(8)
        val h=minOf(items.size*a.d(44)+a.d(12),frame.height-y-a.d(24))
        layer.addView(card,FrameLayout.LayoutParams(w,h).apply{leftMargin=x;topMargin=y})
        frame.addView(layer,FrameLayout.LayoutParams(-1,-1));menu=layer
        // the menu grows out of its pill (see MotionSpec, asks)
        if(motion){card.pivotX=(at[0]-f[0]-x+anchor.width/2).toFloat();card.pivotY=0f;card.scaleX=.94f;card.scaleY=.94f;card.alpha=0f
            card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(MotionSpec.ASK_IN).setInterpolator(MotionSpec.enter).start()}}
    private fun closeMenu(){val layer=menu?:return;menu=null;val card=layer.getChildAt(0)
        if(motion)card.animate().scaleX(.96f).scaleY(.96f).alpha(0f).setDuration(MotionSpec.ASK_OUT).setInterpolator(MotionSpec.exit).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}

    /** Lays the filtered books out: five rows and the tray of books without a rating. */
    private fun bind(animated:Boolean){
        val list=Tiers.group(a,filtered())
        countText.text=Tiers.count(list)
        renderPills()
        shown.clear();list.forEach{shown[it.str("id")]=tierOf(it)}
        fun build(){fill(rowsBox,list)}
        if(animated&&motion){rowsBox.animate().cancel();rowsBox.animate().alpha(0f).setDuration(Motion.FAST).withEndAction{build();rowsBox.translationY=a.d(8).toFloat()
            rowsBox.animate().alpha(1f).translationY(0f).setDuration(Motion.EMPHASIS).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}.start()} else build()
    }
    /** Lays five rows (and the tray) into [box]. */
    private fun fill(box:LinearLayout,list:List<JSONObject>){box.removeAllViews();rows.clear()
            for(k in 0..5){val small=k==5;val items=Tiers.sorted(list.filter{tierOf(it)==(if(k==5)-1 else k)})
                if(k==5&&items.isEmpty())continue
                val flow=FlowRow(a,a.d(if(small)8 else 7),a.d(8),true){motion}
                items.forEach{b->val (w,h)=sizeOf(b,small);flow.addView(cell(b,small),ViewGroup.LayoutParams(w,h))}
                val row=TierRow(a,k,flow)
                if(k==5){row.minimumHeight=0;row.setPadding(a.d(14),a.d(12),a.d(14),a.d(14));(row.getChildAt(0)).visibility=View.GONE
                    (flow.layoutParams as LinearLayout.LayoutParams).apply{marginStart=0;topMargin=a.d(28)}
                    val tray=FrameLayout(a).apply{clipChildren=false;clipToPadding=false
                        background=GradientDrawable().apply{cornerRadius=a.d(22).toFloat();setStroke(a.d(1)+a.d(1)/2,if(a.light)0x26000000 else 0x22ffffff,a.d(5).toFloat(),a.d(4).toFloat())}}
                    tray.addView(a.text("ЕЩЁ НЕ ОЦЕНЕНЫ",Type.CAPTION,Ui.ink2(a.light),true).apply{letterSpacing=.08f},FrameLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(14);topMargin=a.d(12)})
                    tray.addView(row,FrameLayout.LayoutParams(-1,-2))
                    box.addView(tray,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=a.d(16);rightMargin=a.d(16);topMargin=a.d(14)})}
                else box.addView(row,LinearLayout.LayoutParams(-1,-2))
                rows.add(row)}
    }

    // ───── dragging ─────
    private class Drag(val book:JSONObject,val float:View,val hole:View,val dx:Float,val dy:Float,var row:TierRow,val from:Int)
    private var drag:Drag?=null
    private fun where(v:View):RectF{val l=IntArray(2);v.getLocationInWindow(l);val f=IntArray(2);frame.getLocationInWindow(f)
        val x=(l[0]-f[0]).toFloat();val y=(l[1]-f[1]).toFloat();return RectF(x,y,x+v.width,y+v.height)}
    private fun startDrag(cv:View,b:JSONObject){if(drag!=null||closing||share!=null)return
        val flow=cv.parent as? FlowRow?:return;val row=rows.firstOrNull{it.covers===flow}?:return
        val r=where(cv);val idx=flow.indexOfChild(cv)
        val (w,h)=sizeOf(b,row.k==5)
        val hole=View(a)
        flow.removeView(cv);flow.addView(hole,idx,ViewGroup.LayoutParams(w,h))
        val fl=(if(Tiers.isSeries(b))SeriesFan(a,Tiers.members[b.str("id")].orEmpty(),row.k==5) else CoverView(a,b,a.cover(b))).apply{elevation=a.d(16).toFloat();pivotX=r.width()/2;pivotY=r.height()/2}
        frame.addView(fl,FrameLayout.LayoutParams(r.width().toInt(),r.height().toInt()));fl.translationX=r.left;fl.translationY=r.top
        val sc=if(row.k==5)1.45f else 1.2f
        if(motion)fl.animate().scaleX(sc).scaleY(sc).rotation(-4f).setDuration(Motion.FAST).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start() else {fl.scaleX=sc;fl.scaleY=sc}
        frame.haptic(a,Haptic.LONG)
        drag=Drag(b,fl,hole,frame.lastX-r.left,frame.lastY-r.top,row,row.k)
        if(row.k<5)row.light(true,motion)
        frame.postOnAnimation(edgeScroll)
    }
    private fun dragEvent(e:MotionEvent){val d=drag?:return
        when(e.actionMasked){
            MotionEvent.ACTION_MOVE->{d.float.translationX=e.x-d.dx;d.float.translationY=e.y-d.dy;aim(e.x,e.y)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->drop()}}
    /** Finds the row under the finger and the place in it, and moves the empty place there. */
    private fun aim(x:Float,y:Float){val d=drag?:return
        val target=rows.firstOrNull{val r=where(if(it.k==5)it.parent as View else it);y>=r.top&&y<=r.bottom}?:return
        val flow=target.covers;var idx=0
        for(i in 0 until flow.childCount){val ch=flow.getChildAt(i);if(ch===d.hole)continue;val r=where(ch)
            if(y>r.bottom+a.d(4)||(y>=r.top-a.d(4)&&x>r.centerX()))idx++}
        if(target!==d.row){d.row.light(false,motion);d.row=target;if(target.k<5)target.light(true,motion);frame.haptic(a,Haptic.SELECT)
            val small=target.k==5;d.float.animate().scaleX(if(small)1.45f else 1.2f).scaleY(if(small)1.45f else 1.2f).setDuration(Motion.FAST).start()}
        val cur=d.hole.parent as? FlowRow
        if(cur!==flow||cur.indexOfChild(d.hole)!=idx){cur?.removeView(d.hole);val (w,h)=sizeOf(d.book,target.k==5)
            flow.addView(d.hole,idx.coerceAtMost(flow.childCount),ViewGroup.LayoutParams(w,h))}}
    /** Near the top or bottom edge the list scrolls under the lifted cover. */
    private val edgeScroll=object:Runnable{override fun run(){if(drag==null)return
        val r=where(scroll);val y=frame.lastY;val edge=a.d(90).toFloat();val top=r.top+edge;val bottom=r.bottom-edge
        val v=when{y<top->-((top-y)/edge).coerceIn(0f,1f)*a.d(14);y>bottom->((y-bottom)/edge).coerceIn(0f,1f)*a.d(14);else->0f}
        if(v!=0f){scroll.scrollBy(0,v.toInt());aim(frame.lastX,frame.lastY)}
        frame.postOnAnimation(this)}}
    private fun drop(){val d=drag?:return
        val flow=d.hole.parent as? FlowRow;val target=d.row
        val r=where(d.hole)
        fun settle(){val f=d.hole.parent as? FlowRow;val i=f?.indexOfChild(d.hole)?:-1
            f?.removeView(d.hole);frame.removeView(d.float);d.row.light(false,motion);drag=null
            if(f==null||i<0){bind(false);return}
            val small=target.k==5;val (w,h)=sizeOf(d.book,small);f.addView(cell(d.book,small),i,ViewGroup.LayoutParams(w,h))
            commit(d.book,target,f)}
        if(flow==null){settle();return}
        if(!motion){settle();return}
        val small=target.k==5;val w=d.float.width.coerceAtLeast(1).toFloat();val hgt=d.float.height.coerceAtLeast(1).toFloat();val sw=r.width()/w
        // the scale pivots on the middle, so aim the middle of the cover at the middle of its place
        d.float.animate().translationX(r.left-(w-r.width())/2).translationY(r.top-(hgt-r.height())/2).scaleX(sw).scaleY(sw).rotation(0f).alpha(if(small).6f else 1f)
            .setDuration(Motion.STANDARD).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).withEndAction{settle()}.start()
    }
    /** Saves the new rating and the order inside the row the book landed in. */
    private fun commit(book:JSONObject,row:TierRow,flow:FlowRow){
        val before=tierOf(book);val now=if(row.k==5)-1 else row.k
        // a series is put in its row by hand (its issues keep their own stars); a book gets the row's stars
        if(Tiers.isSeries(book))Tiers.place(a,book.str("series"),now,book.optDouble("tierPos",0.0))
        else if(now!=before){Rating.put(book,if(now<0)0 else (5-now)*2)}
        shown[book.str("id")]=now
        var prefs=Tiers.isSeries(book)
        for(i in 0 until flow.childCount){val b=flow.getChildAt(i).tag as? JSONObject?:continue
            if(Tiers.isSeries(b)){b.put("tierPos",i.toDouble());Tiers.place(a,b.str("series"),if(b===book)now else null,i.toDouble());prefs=true}
            else if(b.optDouble("tierPos",-1.0)!=i.toDouble()||b===book){b.put("tierPos",i.toDouble());a.saveBook(b){}}}
        if(prefs)a.savePreferences()
        if(now!=before)frame.haptic(a,Haptic.SELECT)
    }

    /** The main screen is rebuilt when a book is saved (a rating set here): the list stays open on top of it. */
    fun reattach(){if(closing||frame.parent===a.root)return;(frame.parent as? ViewGroup)?.removeView(frame);a.root.addView(frame,FrameLayout.LayoutParams(-1,-1))}
    fun back(){if(menu!=null)closeMenu() else if(share!=null)closeShare() else close()}
    fun close(){if(closing)return;closing=true;closeShare();closeMenu()
        val d=drag;if(d!=null){frame.removeView(d.float);drag=null}
        fun done(){(frame.parent as? ViewGroup)?.removeView(frame);if(a.tierList===this)a.tierList=null;poster?.recycle();poster=null}
        // the card under the list is brought up to date first, so the list folds back into the new picture
        onClose()
        if(!motion){done();return}
        morph.close{done()}}
    // a swipe back: only over the list itself (no menu, picture or dragged book over it)
    override val swipeView:View get()=frame
    override fun swipeReady()=!closing&&motion&&menu==null&&share==null&&drag==null
    override fun swipeMove(dx:Float)=morph.hold(dx)
    override fun swipeEnd(go:Boolean){if(go)close() else morph.settle()}

    // ───── share picture ─────
    private fun openShare(){if(share!=null||drag!=null)return
        val list=Tiers.group(a,filtered()).filter{tierOf(it)>=0}
        val layer=FrameLayout(a).apply{setBackgroundColor(if(a.light)0xe6f2f2f0.toInt() else 0xe608080a.toInt());isClickable=true;setOnClickListener{closeShare()}}
        val box=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
        val title=if(kind=="comic")(if(year!=null)"Мои комиксы $year" else "Мои комиксы") else if(year!=null)"Мой $year в книгах" else "Мои книги"
        val bmp=TierPoster.render(a,title,genre,list);poster?.recycle();poster=bmp
        val img=ImageView(a).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.FIT_CENTER;elevation=a.d(16).toFloat();clipToOutline=true;isClickable=true;contentDescription="Тир-лист"
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,a.d(22).toFloat())}}}
        val maxW=a.resources.displayMetrics.widthPixels-a.d(48);val maxH=(frame.height*.66f).toInt()
        val k=min(maxW.toFloat()/bmp.width,maxH.toFloat()/bmp.height)
        box.addView(img,LinearLayout.LayoutParams((bmp.width*k).toInt(),(bmp.height*k).toInt()))
        val acts=a.line().apply{gravity=Gravity.CENTER}
        fun act(label:String,primary:Boolean,click:()->Unit)=a.text(label,Type.BODY,if(primary)a.bg else a.fg,true).apply{gravity=Gravity.CENTER;isClickable=true
            background=GradientDrawable().apply{setColor(if(primary)a.fg else Ui.press(a.light));cornerRadius=a.d(24).toFloat()};setOnClickListener{click()};a.press(this)}
        acts.addView(act("Сохранить",false){save()},LinearLayout.LayoutParams(0,a.d(48),1f))
        acts.addView(act("Отправить",true){send()},LinearLayout.LayoutParams(0,a.d(48),1f).apply{marginStart=a.d(10)})
        box.addView(acts,LinearLayout.LayoutParams((bmp.width*k).toInt(),-2).apply{topMargin=a.d(20)})
        layer.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        frame.addView(layer,FrameLayout.LayoutParams(-1,-1));share=layer
        // a picture to keep or send, over the page: it shows and grows a little in place
        if(motion){layer.alpha=0f;layer.animate().alpha(1f).setDuration(MotionSpec.ASK_IN).setInterpolator(MotionSpec.enter).start()
            img.scaleX=.94f;img.scaleY=.94f;img.alpha=0f
            img.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(MotionSpec.ASK_IN).setInterpolator(MotionSpec.enter).start()}
    }
    private fun closeShare(){val layer=share?:return;share=null
        if(motion)layer.animate().alpha(0f).setDuration(MotionSpec.ASK_OUT).setInterpolator(MotionSpec.exit).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}
    private fun fileName()="Glyph-tier-${year?:"all"}${kind?.let{"-$it"}?:""}.png"
    private fun send(){val bmp=poster?:return;val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,fileName())
        a.work({file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}){
            val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Тир-лист",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться"))}}
    private fun save(){val bmp=poster?:return
        if(Build.VERSION.SDK_INT<29){send();return}
        val name=fileName()
        if(Build.VERSION.SDK_INT>=29)a.work({val values=ContentValues().apply{put(MediaStore.Images.Media.DISPLAY_NAME,name);put(MediaStore.Images.Media.MIME_TYPE,"image/png");put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Glyph");put(MediaStore.Images.Media.IS_PENDING,1)}
            val r=a.contentResolver;val uri=r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)?:error("no uri")
            r.openOutputStream(uri)?.use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);r.update(uri,values,null,null)},
            failed={Notice.short(a,Notice.Kind.ERROR,"Не получилось сохранить")}){Notice.short(a,Notice.Kind.OK,"Сохранено в галерею");closeShare()}}
}

/** The picture to share: the title, the count and every non-empty tier with its covers, on the same golden backdrop. */
internal object TierPoster{
    fun render(a:MainActivity,title:String,genre:String?,books:List<JSONObject>):Bitmap{
        val W=1080;val pad=64f;val labelW=150f;val cw=118f;val ch=177f;val gap=16f
        val perLine=max(1,((W-pad*2-labelW+gap)/(cw+gap)).toInt())
        val groups=(0..4).map{k->Tiers.sorted(books.filter{Tiers.tierOf(a,it)==k})}.withIndex().filter{it.value.isNotEmpty()}
        val heights=groups.map{g->val lines=(g.value.size+perLine-1)/perLine;max(150f,lines*ch+(lines-1)*gap)+48f}
        val top=330f;val H=(top+heights.sum()+pad+40f).toInt().coerceAtLeast(720)
        val bmp=Bitmap.createBitmap(W,H,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
        c.drawColor(a.bg)
        Glow.face(W,H,a.bg,if(a.light)0xfff5b52a.toInt() else 0xffe0a12a.toInt(),W*.5f,0f,W*1.6f,if(a.light).12f else .16f,2.6f*1.7f*1.7f)?.let{c.drawBitmap(it,0f,0f,null);it.recycle()}
        val tp=Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface=PolkaFonts.sans(600);tp.textSize=30f;tp.letterSpacing=.08f;tp.color=a.muted;c.drawText("ТИР-ЛИСТ",pad,130f,tp);tp.letterSpacing=0f
        tp.typeface=PolkaFonts.serif(a,true);tp.textSize=74f;tp.color=a.fg;c.drawText(title,pad,222f,tp)
        tp.typeface=PolkaFonts.sans(400);tp.textSize=34f;tp.color=a.muted;c.drawText(Tiers.count(books)+(genre?.let{", ${it.lowercase()}"}?:""),pad,278f,tp)
        tp.typeface=PolkaFonts.display(600);tp.textSize=30f;tp.color=a.muted;tp.textAlign=Paint.Align.RIGHT;c.drawText("Glyph",W-pad,130f,tp);tp.textAlign=Paint.Align.LEFT
        val hair=Paint().apply{color=Ui.fill(a.light)}
        var y=top
        groups.forEachIndexed{gi,g->val k=g.index;val h=heights[gi]
            if(gi>0)c.drawRect(pad+labelW,y,W-pad,y+2f,hair)
            val (c1,c2)=Tiers.tone(a,k)
            tp.typeface=PolkaFonts.serif(a,true);tp.textSize=104f;tp.textAlign=Paint.Align.CENTER
            tp.shader=LinearGradient(0f,y+40f,0f,y+150f,c1,c2,Shader.TileMode.CLAMP);c.drawText(Tiers.letters[k],pad+labelW/2-14f,y+24f+104f*.78f+20f,tp);tp.shader=null;tp.textAlign=Paint.Align.LEFT
            g.value.forEachIndexed{i,b->val line=i/perLine;val col=i%perLine
                val x=pad+labelW+col*(cw+gap);val cy=y+24f+line*(ch+gap)
                // a series: its next two issues fanned behind the first, quieter, as in the list
                val fan=Tiers.members[b.str("id")]?.take(3)?:listOf(b)
                for(fi in fan.indices.reversed()){val ib=fan[fi]
                    val cv=CoverView(a,ib,a.cover(ib));cv.measure(View.MeasureSpec.makeMeasureSpec(cw.toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(ch.toInt(),View.MeasureSpec.EXACTLY))
                    cv.layout(0,0,cw.toInt(),ch.toInt());c.save();c.translate(x+(if(fi>0)10f else 0f),cy)
                    if(fi>0){c.rotate(if(fi==1)4.5f else 9f,cw/2,ch);val g=if(fi==1)191 else 140;c.saveLayer(-40f,-40f,cw+40f,ch+40f,Paint().apply{colorFilter=LightingColorFilter(Color.rgb(g,g,g),0)})}
                    cv.draw(c);if(fi>0)c.restore();c.restore()}}
            y+=h}
        return bmp
    }
}
