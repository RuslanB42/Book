package app.polka.nativeapp

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.*

// ───────────────────────── shared bits ─────────────────────────

internal fun goalGreen(a:MainActivity)=if(a.light)0xff1f9d63.toInt() else 0xff2fd38a.toInt()
internal fun goalGreen2(a:MainActivity)=if(a.light)0xff52c68f.toInt() else 0xff9df5c8.toInt()
internal val medalNames=listOf("бронза","серебро","золото","платина","бриллиант")

/** «ещё 5 — примерно 3 в месяц», «цель достигнута», «не хватило 2 книг». */
internal fun goalPace(year:Int,today:LocalDate,done:Int,target:Int):String{val left=target-done
    return when{target<=0->if(year>=today.year)"цель не задана · Настройки → «Чтение»" else ""
        left<=0->"цель достигнута"
        year==today.year->{val per=left.toDouble()/(13-today.monthValue);"ещё $left — "+(if(per<1)"меньше книги в месяц" else "примерно ${per.roundToInt()} в месяц")}
        year<today.year->"не хватило ${MainActivity.countBooks(left)}"
        else->"ещё $left"}}

/** «из 12 книг», «из 21 книги». */
internal fun ofBooks(n:Int)="$n "+if(n%10==1&&n%100!=11)"книги" else "книг"

internal fun daysWord(n:Int)="$n "+if(n%100 in 11..14)"дней" else when(n%10){1->"день";2,3,4->"дня";else->"дней"}

/** Small line glyphs drawn on a 16-unit grid: chevron down, expand, share. */
internal class Glyph(private val a:MainActivity,private val kind:String,var ink:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path().apply{when(kind){
        "down"->{moveTo(3.5f,6f);lineTo(8f,10.5f);lineTo(12.5f,6f)}
        "expand"->{moveTo(9.5f,2.5f);lineTo(13.5f,2.5f);lineTo(13.5f,6.5f);moveTo(6.5f,13.5f);lineTo(2.5f,13.5f);lineTo(2.5f,9.5f);moveTo(13.5f,2.5f);lineTo(9f,7f);moveTo(2.5f,13.5f);lineTo(7f,9f)}
        else->{moveTo(8f,10f);lineTo(8f,2.5f);moveTo(5f,5.5f);lineTo(8f,2.5f);lineTo(11f,5.5f);moveTo(3f,9f);lineTo(3f,12.5f);lineTo(13f,12.5f);lineTo(13f,9f)}}}
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val s=min(width,height)/16f;c.save();c.translate((width-16*s)/2,(height-16*s)/2);c.scale(s,s)
        p.color=ink;p.strokeWidth=1.7f;c.drawPath(path,p);c.restore()}
}

/** Thin rounded progress track with a green gradient fill that grows when shown. */
internal class GoalTrack(private val a:MainActivity,fraction:Float,motion:Boolean):View(a){
    private val target=fraction.coerceIn(0f,1f);private var shown=if(motion)0f else target
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
        if(motion&&target>0f)addOnAttachStateChangeListener(object:OnAttachStateChangeListener{var done=false
            override fun onViewAttachedToWindow(v:View){if(done)return;done=true;ValueAnimator.ofFloat(0f,target).apply{startDelay=180;duration=800;interpolator=PathInterpolator(.2f,.8f,.2f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}
            override fun onViewDetachedFromWindow(v:View){}})}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2
        p.shader=null;p.color=if(a.light)0x14000000 else 0x14ffffff;c.drawRoundRect(0f,0f,width.toFloat(),h,r,r,p)
        if(shown>0f){val w=max(h,width*shown);p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,w,0f,goalGreen(a),goalGreen2(a),Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,w,h,r,r,p);p.shader=null}}
}

// ───────────────────────── year menu on the card ─────────────────────────

/** A frosted menu under «ЦЕЛЬ НА 2026 ⌄»: each year with its own small progress bar and «N из M». */
internal fun MainActivity.goalYearMenu(anchor:View,years:List<Int>,current:Int,motion:Boolean,stats:(Int)->Pair<Int,Int>,onClose:()->Unit,pick:(Int)->Unit){
    val a=this
    val box=column().apply{setPadding(d(6),d(6),d(6),d(6))
        background=GradientDrawable().apply{setColor(if(light)0xf7ffffff.toInt() else 0xf52a2a2e.toInt());cornerRadius=d(20).toFloat();setStroke(d(1),if(light)0x0f000000 else 0x10ffffff)}}
    val popup=PopupWindow(box,d(236),ViewGroup.LayoutParams.WRAP_CONTENT,true).apply{elevation=d(22).toFloat();isOutsideTouchable=true;setBackgroundDrawable(ColorDrawable(0));animationStyle=0;setOnDismissListener{onClose()}}
    years.forEachIndexed{i,y->val (done,goal)=stats(y)
        val row=line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(12),d(10),d(12),d(10));isClickable=true;contentDescription="$y: $done"+if(goal>0)" из $goal" else ""
            background=GradientDrawable().apply{setColor(if(y==current)(if(light)0x0d000000 else 0x14ffffff) else 0);cornerRadius=d(14).toFloat()}
            setOnClickListener{popup.dismiss();if(y!=current)pick(y)};press(this)}
        row.addView(text("$y",18f,fg,true,serif=true))
        row.addView(GoalTrack(a,if(goal>0)done.toFloat()/goal else 0f,false),LinearLayout.LayoutParams(0,d(4),1f).apply{marginStart=d(12);marginEnd=d(10)})
        row.addView(text(if(goal>0)"$done из $goal"+(if(done>=goal)" ✓" else "") else MainActivity.countBooks(done),12f,muted))
        box.addView(row,LinearLayout.LayoutParams(-1,-2))
        if(motion){row.alpha=0f;row.translationY=-d(6).toFloat();row.animate().alpha(1f).translationY(0f).setStartDelay(60L+i*40L).setDuration(300).setInterpolator(Ease.outCubic).start()}}
    popup.showAsDropDown(anchor,-d(4),d(8))
    if(motion){box.pivotX=d(24).toFloat();box.pivotY=0f;box.scaleX=.86f;box.scaleY=.86f;box.alpha=0f;val sp=Spring.of(.3,420.0)
        box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
}

// ───────────────────────── full-screen road ─────────────────────────

/**
 * The goal road opened to the whole screen: it grows out of the card, the year tabs sit on top, and below winds a
 * long road with big covers that alternate sides — under each the number, title, author, dates, days and rating.
 * Empty places up to the goal wait as dashed slots before the finish flag. The walked part draws itself while you
 * scroll and the books rise in one by one. «Поделиться» makes a story-sized picture of the year.
 */
internal class GoalRoadDialog(private val a:MainActivity,private val model:ReadingStatistics,private val data:JSONObject,private var year:Int,
    private val years:List<Int>,private val motion:Boolean,private val source:()->View?,private val onYear:(Int)->Unit):Dialog(a,R.style.PolkaSheetTheme){
    private val today=model.today
    private lateinit var frame:RevealFrame
    private lateinit var tabs:LinearLayout
    private lateinit var progress:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var roadHost:FrameLayout
    private var column:RoadColumn?=null
    private var share:FrameLayout?=null
    private var closing=false
    private var insets=android.graphics.Rect()
    override fun onCreate(state:Bundle?){super.onCreate(state)
        frame=RevealFrame(a);setContentView(frame)
        val w=window!!;ReaderWindow.configure(w,false,a.bg);w.setBackgroundDrawable(ColorDrawable(0));w.setLayout(-1,-1);w.setGravity(Gravity.FILL)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setWindowAnimations(0)
        if(android.os.Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}
        val col=a.column()
        // header: collapse, the caption, then the years as big serif tabs and the progress
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),a.d(10),a.d(18),0)}
        val close=FrameLayout(a).apply{isClickable=true;contentDescription="Свернуть";setOnClickListener{close()}
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(a.light)0x0d000000 else 0x14ffffff)}
            addView(Glyph(a,"down",a.fg),FrameLayout.LayoutParams(a.d(17),a.d(17),Gravity.CENTER));a.press(this)}
        bar.addView(close,LinearLayout.LayoutParams(a.d(40),a.d(40)))
        bar.addView(a.text("ЦЕЛЬ НА ГОД",12.5f,a.muted,true).apply{letterSpacing=.06f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(View(a),LinearLayout.LayoutParams(a.d(40),a.d(40)))
        col.addView(bar)
        tabs=a.line().apply{gravity=Gravity.BOTTOM;setPadding(a.d(18),0,a.d(18),0)}
        col.addView(HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(tabs)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        progress=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(18),0)}
        col.addView(progress,LinearLayout.LayoutParams(-1,a.d(30)).apply{topMargin=a.d(12)})
        scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;clipToPadding=false}
        roadHost=FrameLayout(a);scroll.addView(roadHost,ViewGroup.LayoutParams(-1,-2))
        scroll.setOnScrollChangeListener{_,_,y,_,_->column?.viewport(y,scroll.height)}
        val body=FrameLayout(a);body.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        col.addView(body,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=a.d(6)})
        frame.addView(col,FrameLayout.LayoutParams(-1,-1))
        val shareBtn=a.line().apply{gravity=Gravity.CENTER;setPadding(a.d(22),0,a.d(22),0);isClickable=true;contentDescription="Поделиться годом в книгах"
            background=GradientDrawable().apply{setColor(a.fg);cornerRadius=a.d(24).toFloat()};elevation=a.d(10).toFloat()
            addView(Glyph(a,"share",a.bg),LinearLayout.LayoutParams(a.d(16),a.d(16)).apply{marginEnd=a.d(8)})
            addView(a.text("Поделиться",14f,a.bg,true));setOnClickListener{openShare()};a.press(this)}
        val scrim=View(a).apply{background=GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(a.bg,a.bg and 0x00ffffff or 0xd9000000.toInt(),a.bg and 0x00ffffff))}
        val scrimLp=FrameLayout.LayoutParams(-1,a.d(130),Gravity.BOTTOM);frame.addView(scrim,scrimLp)
        val shareLp=FrameLayout.LayoutParams(-2,a.d(48),Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        frame.addView(shareBtn,shareLp)
        SafeArea.bind(frame){i->val s=SafeArea.content(i,false);insets.set(s.left,s.top,s.right,s.bottom)
            col.setPadding(s.left,s.top,s.right,0);scroll.setPadding(0,0,0,s.bottom+a.d(96));shareLp.bottomMargin=s.bottom+a.d(20);shareBtn.layoutParams=shareLp;scrimLp.height=s.bottom+a.d(130);scrim.layoutParams=scrimLp}
        bindYear(false)
        if(motion){capture();frame.t=0f
            // container transform: the card's picture fades out while the frame grows to the screen and the road fades in
            ValueAnimator.ofFloat(0f,1f).apply{duration=560;interpolator=PathInterpolator(.3f,0f,0f,1f);addUpdateListener{frame.t=it.animatedValue as Float};start()}
            roadHost.translationY=a.d(28).toFloat();roadHost.animate().translationY(0f).setStartDelay(120).setDuration(700).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()
            shareBtn.alpha=0f;shareBtn.translationY=a.d(20).toFloat();shareBtn.animate().alpha(1f).translationY(0f).setStartDelay(360).setDuration(520).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}
    }
    /** Where the card is now and how it looks, for the grow / fold transition. */
    private fun capture(){val v=source();if(v==null||!v.isAttachedToWindow||v.width==0){frame.from=null;frame.setSnapshot(null);return}
        val l=IntArray(2);v.getLocationOnScreen(l);frame.from=RectF(l[0].toFloat(),l[1].toFloat(),(l[0]+v.width).toFloat(),(l[1]+v.height).toFloat())
        frame.setSnapshot(try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}catch(e:Throwable){null})}
    private fun bindYear(animated:Boolean){
        tabs.removeAllViews()
        years.forEach{y->val on=y==year
            tabs.addView(a.text("$y",28f,if(on)a.fg else if(a.light)0xffbdbdc1.toInt() else 0xff4b4b52.toInt(),true,serif=true).apply{isClickable=true;contentDescription="$y год"
                pivotX=0f;post{pivotY=height.toFloat()};scaleX=if(on)1.08f else 1f;scaleY=scaleX
                setOnClickListener{if(y!=year){year=y;onYear(y);bindYear(true)}}},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=a.d(20)})}
        val list=yearBooks(model,data,year);val goal=model.target(year)
        progress.removeAllViews()
        progress.addView(a.text(if(goal>0)"${list.size} / $goal" else MainActivity.countBooks(list.size),22f,a.fg,true,serif=true))
        progress.addView(GoalTrack(a,if(goal>0)list.size.toFloat()/goal else 1f,motion),LinearLayout.LayoutParams(0,a.d(6),1f).apply{marginStart=a.d(14)})
        val old=column;val nw=RoadColumn(a,list,goal,year,today,motion)
        roadHost.addView(nw,FrameLayout.LayoutParams(-1,-2));column=nw
        scroll.scrollTo(0,0);nw.post{nw.viewport(0,scroll.height)}
        if(old!=null){if(animated&&motion){old.animate().alpha(0f).setDuration(160).withEndAction{roadHost.removeView(old)}.start()
            nw.alpha=0f;nw.translationY=a.d(24).toFloat();nw.animate().alpha(1f).translationY(0f).setStartDelay(120).setDuration(420).setInterpolator(Ease.outCubic).start()} else roadHost.removeView(old)}
    }
    private fun close(){if(closing)return;closing=true
        if(share!=null){closeShare()}
        if(!motion){dismiss();return}
        capture()
        ValueAnimator.ofFloat(frame.t,0f).apply{duration=440;interpolator=PathInterpolator(.3f,0f,.1f,1f);addUpdateListener{frame.t=it.animatedValue as Float}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){dismiss()}});start()}}
    @Deprecated("Back dispatcher") override fun onBackPressed(){if(share!=null)closeShare() else close()}

    // ───── share picture ─────
    private var poster:Bitmap?=null
    private fun openShare(){if(share!=null)return
        val list=yearBooks(model,data,year);val goal=model.target(year);val y=year
        val layer=FrameLayout(a).apply{setBackgroundColor(if(a.light)0xd9f2f2f0.toInt() else 0xdb08080a.toInt());isClickable=true;setOnClickListener{closeShare()}}
        val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
        val img=ImageView(a).apply{scaleType=ImageView.ScaleType.FIT_CENTER;elevation=a.d(16).toFloat();clipToOutline=true;contentDescription="Мой год в книгах — $y"
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,a.d(22).toFloat())}};isClickable=true}
        val sh=frame.height-insets.top-insets.bottom;val ph=min((sh*.68f).toInt(),a.d(560));val pw=ph*9/16
        col.addView(img,LinearLayout.LayoutParams(pw,ph))
        val acts=a.line().apply{gravity=Gravity.CENTER}
        fun act(label:String,primary:Boolean,click:()->Unit)=a.text(label,14f,if(primary)a.bg else a.fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(20),0,a.d(20),0);isClickable=true
            background=GradientDrawable().apply{setColor(if(primary)a.fg else if(a.light)0x14000000 else 0x1affffff);cornerRadius=a.d(22).toFloat()};setOnClickListener{click()};a.press(this)}
        acts.addView(act("Закрыть",false){closeShare()},LinearLayout.LayoutParams(-2,a.d(44)))
        acts.addView(act("Отправить",true){send(y)},LinearLayout.LayoutParams(-2,a.d(44)).apply{marginStart=a.d(10)})
        col.addView(acts,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18)})
        layer.addView(col,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        frame.addView(layer,FrameLayout.LayoutParams(-1,-1));share=layer
        if(motion){layer.alpha=0f;layer.animate().alpha(1f).setDuration(200).start()
            img.translationY=a.d(30).toFloat();img.scaleX=.92f;img.scaleY=.92f;img.alpha=0f;val sp=Spring.of(.25,520.0)
            img.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60).setDuration(sp.duration).setInterpolator(sp.interpolator).start()}
        GoalPoster.render(a,list,goal,y){bmp->if(share!==layer){bmp.recycle();return@render};poster?.recycle();poster=bmp;img.setImageBitmap(bmp)}
    }
    private fun closeShare(){val layer=share?:return;share=null
        if(motion)layer.animate().alpha(0f).setDuration(180).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}
    private fun send(y:Int){val bmp=poster?:return;val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,"Polka-goal-$y.png")
        a.work({file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}){
            val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Мой год в книгах",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться"))}}
    override fun dismiss(){super.dismiss();poster?.recycle();poster=null;frame.setSnapshot(null)}
}

/** Full-screen layer for a container transform: its rounded bounds morph from the card to the screen while the card's
 *  picture fades out and the full content fades in (and the reverse on close). */
internal class RevealFrame(private val a:MainActivity):FrameLayout(a){
    var from:RectF?=null
    var t=1f;set(v){field=v;invalidate()}
    private var snap:Bitmap?=null
    fun setSnapshot(b:Bitmap?){snap?.recycle();snap=b}
    private val clip=Path();private val r=RectF();private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val sp=Paint(Paint.FILTER_BITMAP_FLAG)
    init{setWillNotDraw(false)}
    override fun dispatchDraw(c:Canvas){val f=from;val W=width.toFloat();val H=height.toFloat();val k=if(f==null)1f else t
        if(f!=null&&k<1f)r.set(f.left*(1-k),f.top*(1-k),f.right+(W-f.right)*k,f.bottom+(H-f.bottom)*k) else r.set(0f,0f,W,H)
        val rad=a.d(28)*(1-k);c.save();if(k<1f){clip.reset();clip.addRoundRect(r,rad,rad,Path.Direction.CW);c.clipPath(clip)}
        c.drawColor(a.bg)
        p.shader=RadialGradient(W*.82f,0f,W*1.05f,intArrayOf(if(a.light)0x1f2fd38a else 0x292fd38a,0x002fd38a),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,W,H*.7f,p);p.shader=null
        if(k<1f){val fade=((k-.18f)/.5f).coerceIn(0f,1f)
            if(fade>0f){val l=c.saveLayerAlpha(r,(255*fade).toInt());super.dispatchDraw(c);c.restoreToCount(l)}
            val s=snap;val out=(1f-k/.38f).coerceIn(0f,1f)
            if(s!=null&&!s.isRecycled&&out>0f){val sc=r.width()/s.width;sp.alpha=(255*out).toInt();c.save();c.translate(r.left,r.top);c.scale(sc,sc);c.drawBitmap(s,0f,0f,sp);c.restore()}}
        else super.dispatchDraw(c)
        c.restore()}
}

/** The long winding road of the year: one milestone per book of the goal, big covers alternating sides. */
internal class RoadColumn(private val a:MainActivity,private val books:List<YearBook>,private val goal:Int,private val year:Int,private val today:LocalDate,private val motion:Boolean):FrameLayout(a){
    private val d=a.d(1).toFloat()
    private val slots=if(goal>0)max(goal,books.size) else books.size
    private val top=d*70
    private val pts=ArrayList<PointF>()
    private val road=Path();private val walked=Path();private var walkedEnd=0f
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG)
    private val items=ArrayList<View>();private val slotViews=ArrayList<View>()
    private val startLabel=a.text("Старт · 1 января",12f,a.muted).apply{gravity=Gravity.CENTER}
    private val finish=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    private var reach=0f;private var intro=if(motion)0f else 1f
    private var glow:Bitmap?=null
    private val revealed=HashSet<View>()
    init{setWillNotDraw(false);clipChildren=false
        books.forEachIndexed{i,b->val v=item(i,b);items.add(v);addView(v);if(motion){v.alpha=0f;v.translationY=d*18;v.translationX=if(i%2==0)d*28 else -d*28} else revealed.add(v)}
        for(k in books.size+1..slots){val s=EmptySlot(a,k);slotViews.add(s);addView(s);if(motion){s.alpha=0f} else revealed.add(s)}
        addView(startLabel)
        if(goal>0){finish.addView(a.text("Финиш — ${MainActivity.countBooks(goal)}",26f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER})
            val pace=goalPace(year,today,books.size,goal);if(pace.isNotEmpty()){a.space(finish,6);finish.addView(a.text(pace,12.5f,a.muted).apply{gravity=Gravity.CENTER})}}
        else if(books.isEmpty())finish.addView(a.text("Пока ни одной книги за $year — дочитанные появятся здесь.",13f,a.muted).apply{gravity=Gravity.CENTER})
        addView(finish)
        if(motion)ValueAnimator.ofFloat(0f,1f).apply{startDelay=240;duration=1100;interpolator=PathInterpolator(.45f,0f,.15f,1f);addUpdateListener{intro=it.animatedValue as Float;invalidate()};start()}}
    /** One book: the cover stands on its milestone at the edge, the details sit beside it towards the middle. */
    private fun item(i:Int,yb:YearBook):View{val right=i%2==0
        val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        val cover=CoverView(a,yb.book,a.cover(yb.book)).apply{softShadow=true;hiRes=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        val g=if(right)Gravity.END else Gravity.START
        val t=a.column().apply{gravity=g}
        fun add(v:TextView,top:Int=0){v.gravity=g;t.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(top)})}
        add(a.text("№ ${i+1}",11f,goalGreen2(a),true).apply{letterSpacing=.08f})
        add(a.text(yb.book.str("title"),19f,a.fg,true,serif=true).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.END},4)
        val author=MainActivity.authors(yb.book);if(author.isNotBlank())add(a.text(author,12.5f,a.muted).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END},4)
        fun f(dt:LocalDate)="${dt.dayOfMonth} ${YearRoad.MONTHS_SHORT[dt.monthValue-1]}"+if(dt.year!=year)" ${dt.year}" else ""
        add(a.text(if(yb.start==yb.end)f(yb.end) else "${f(yb.start)} — ${f(yb.end)}",12.5f,a.fg),12)
        add(a.text("за "+daysWord((java.time.temporal.ChronoUnit.DAYS.between(yb.start,yb.end)+1).toInt().coerceAtLeast(1)),12f,if(a.light)0xff9a9aa0.toInt() else 0xff74747a.toInt()),3)
        val r=yb.book.optInt("rating")
        val rate=a.line().apply{gravity=Gravity.CENTER_VERTICAL or g}
        // the medal views measure themselves at 36dp: shown here scaled down to 22dp
        val medal=FrameLayout(a);medal.addView((if(r in 1..5)RatingMedal(a,r) else EmptyStar(a)).apply{scaleX=22f/36;scaleY=22f/36},FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        val word=a.text(if(r in 1..5)medalNames[r-1] else "без оценки",12f,a.muted)
        if(right){rate.addView(word);rate.addView(medal,LinearLayout.LayoutParams(a.d(22),a.d(22)).apply{leftMargin=a.d(6)})}
        else{rate.addView(medal,LinearLayout.LayoutParams(a.d(22),a.d(22)));rate.addView(word,LinearLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(6)})}
        t.addView(rate,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
        val coverLp=LinearLayout.LayoutParams(a.d(96),a.d(144))
        if(right){row.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=a.d(16)});row.addView(cover,coverLp)}
        else{row.addView(cover,coverLp);row.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=a.d(16)})}
        row.tag=right
        row.contentDescription="${i+1}-я книга: ${yb.book.str("title")}, ${f(yb.start)} — ${f(yb.end)}"+if(r in 1..5)", ${medalNames[r-1]}" else ""
        row.isClickable=true;row.setOnClickListener{a.detail(yb.book)}
        return row}
    private var step=d*230;private var first=d*170
    override fun onMeasure(w:Int,h:Int){val W=MeasureSpec.getSize(w)
        val side=MeasureSpec.makeMeasureSpec(W-a.d(32),MeasureSpec.EXACTLY);val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        items.forEach{it.measure(side,free)};slotViews.forEach{it.measure(MeasureSpec.makeMeasureSpec(a.d(64),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(a.d(96),MeasureSpec.EXACTLY))}
        startLabel.measure(MeasureSpec.makeMeasureSpec(W,MeasureSpec.EXACTLY),free);finish.measure(side,free)
        // every book gets enough room so neighbours never touch, however long the title is
        val tallest=(items.maxOfOrNull{it.measuredHeight}?:a.d(144)).toFloat()
        step=max(d*230,tallest+d*64);first=max(d*150,tallest/2+d*86)
        setMeasuredDimension(W,(top+first+max(0,slots-1)*step+d*230).toInt())}
    private fun milestoneY(k:Int)=top+first+(k-1)*step
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat()
        pts.clear();pts.add(PointF(W/2,top))
        // milestones sit under the middle of the covers, which stand at the screen edges
        for(k in 1..slots)pts.add(PointF(if(k%2==1)W-d*64 else d*64,milestoneY(k)))
        pts.add(PointF(W/2,(if(slots>0)milestoneY(slots) else top)+d*120))
        road.reset();road.moveTo(pts[0].x,pts[0].y)
        val lens=FloatArray(pts.size);var total=0f;val pm=PathMeasure()
        for(k in 1 until pts.size){val a0=pts[k-1];val b=pts[k];val seg=Path();seg.moveTo(a0.x,a0.y);val bend=(b.y-a0.y)*.55f;seg.cubicTo(a0.x,a0.y+bend,b.x,b.y-bend,b.x,b.y)
            road.cubicTo(a0.x,a0.y+bend,b.x,b.y-bend,b.x,b.y);pm.setPath(seg,false);total+=pm.length;lens[k]=total}
        walked.reset();pm.setPath(road,false);val n=min(books.size,slots);if(n>0)pm.getSegment(0f,lens[n],walked,true)
        walkedEnd=if(n>0)pts[n].y else top
        glow?.recycle();glow=null
        if(!a.light&&n>0)try{val k=4;val small=Bitmap.createBitmap(max(1,w/k),max(1,h/k),Bitmap.Config.ARGB_8888);val cv=Canvas(small);cv.scale(1f/k,1f/k)
            cv.drawPath(walked,Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeWidth=d*16;color=0xff2fd38a.toInt()})
            glow=ShelfFrost.blur(small,d*6f/k);small.recycle()}catch(e:Throwable){glow=null}}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){if(pts.isEmpty())return;val W=r-l
        items.forEachIndexed{i,v->val y=pts[i+1].y;val x=a.d(16);val tp=(y-v.measuredHeight/2f).roundToInt();v.layout(x,tp,x+v.measuredWidth,tp+v.measuredHeight)}
        slotViews.forEachIndexed{j,v->val k=books.size+1+j;val x=(pts[k].x-d*32).roundToInt();val y=(pts[k].y-d*48).roundToInt();v.layout(x,y,x+v.measuredWidth,y+v.measuredHeight)}
        val s=pts[0];startLabel.layout(0,(s.y-d*34).roundToInt(),W,(s.y-d*34).roundToInt()+startLabel.measuredHeight)
        val e=pts.last();val fy=(e.y+d*18).roundToInt();finish.layout(a.d(16),fy,a.d(16)+finish.measuredWidth,fy+finish.measuredHeight)}
    /** Scroll position changed: draw the walked road down to three quarters of the screen and bring in what is visible. */
    fun viewport(scrollY:Int,height:Int){reach=scrollY+height*.78f;invalidate()
        val limit=scrollY+height-d*40;var n=0
        (items+slotViews).forEach{v->if(v in revealed||v.top>limit)return@forEach;revealed.add(v);val delay=60L+n++*110L
            if(v in items){v.animate().alpha(1f).translationX(0f).translationY(0f).setStartDelay(delay).setDuration(700).setInterpolator(glide).start()}
            else v.animate().alpha(1f).setStartDelay(delay).setDuration(600).setInterpolator(glide).start()}}
    private val glide=PathInterpolator(.16f,1f,.3f,1f)
    override fun onDraw(c:Canvas){if(pts.isEmpty())return;val W=width.toFloat()
        val green=goalGreen(a);val green2=goalGreen2(a)
        p.reset();p.isAntiAlias=true;p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
        p.color=if(a.light)0x0d000000 else 0x0fffffff;p.strokeWidth=d*26;c.drawPath(road,p)
        p.strokeCap=Paint.Cap.BUTT;p.strokeWidth=d*1.6f;p.color=if(a.light)0x38000000 else 0x38ffffff;p.pathEffect=DashPathEffect(floatArrayOf(d*6,d*9),0f);c.drawPath(road,p);p.pathEffect=null
        // the walked part, revealed down to where the reader has scrolled (the road only ever goes down)
        val shown=top+(max(reach,top+d*60)-top)*intro
        if(!walked.isEmpty){c.save();c.clipRect(0f,0f,W,min(shown,walkedEnd+d*20))
            glow?.let{g->c.drawBitmap(g,null,RectF(0f,0f,W,height.toFloat()),Paint(Paint.FILTER_BITMAP_FLAG).apply{alpha=0x99})}
            p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=d*7;p.color=Color.WHITE;p.shader=LinearGradient(0f,top,0f,walkedEnd,green,green2,Shader.TileMode.CLAMP);c.drawPath(walked,p);p.shader=null;c.restore()}
        p.style=Paint.Style.FILL
        for(k in 1..slots){val q=pts[k];val done=k<=books.size;p.color=if(done)(if(a.light)green else Color.WHITE) else if(a.light)0x40000000 else 0x4dffffff;c.drawCircle(q.x,q.y,d*(if(done)5f else 3.5f),p)}
        val s=pts[0];p.color=a.bg;c.drawCircle(s.x,s.y,d*9,p);p.style=Paint.Style.STROKE;p.strokeWidth=d*4;p.color=green;c.drawCircle(s.x,s.y,d*9,p);p.style=Paint.Style.FILL
        if(goal>0){val e=pts.last();p.color=a.fg;c.drawRect(e.x-d*1.5f,e.y-d*44,e.x+d*1.5f,e.y,p)
            c.drawPath(Path().apply{moveTo(e.x+d*1.5f,e.y-d*44);lineTo(e.x+d*27,e.y-d*36);lineTo(e.x+d*1.5f,e.y-d*27);close()},p)}}
    override fun onDetachedFromWindow(){super.onDetachedFromWindow();glow?.recycle();glow=null}
}

/** A place on the road still waiting for its book: a dashed outline with the book's number. */
internal class EmptySlot(private val a:MainActivity,private val n:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{textAlign=Paint.Align.CENTER}
    init{contentDescription="Место для $n-й книги"}
    override fun onDraw(c:Canvas){val d=a.d(1).toFloat()
        p.style=Paint.Style.STROKE;p.strokeWidth=d*1.5f;p.color=if(a.light)0x2e000000 else 0x2effffff;p.pathEffect=DashPathEffect(floatArrayOf(d*5,d*4),0f)
        c.drawRoundRect(d,d,width-d,height-d,d*6,d*6,p)
        tp.textSize=20*a.resources.displayMetrics.scaledDensity;tp.typeface=PolkaFonts.display(600);tp.color=if(a.light)0xffb0b0b5.toInt() else 0xff55555b.toInt()
        c.drawText("$n",width/2f,height/2f-(tp.ascent()+tp.descent())/2,tp)}
}

// ───────────────────────── the share picture ─────────────────────────

/** A 1080×1920 story picture: «Мой год в книгах», the year, «N из M книг», the covers with their medals, «Полка». */
internal object GoalPoster{
    fun render(a:MainActivity,list:List<YearBook>,goal:Int,year:Int,done:(Bitmap)->Unit){
        // wait (briefly) for covers that are not decoded yet
        val missing=list.filter{it.book.optJSONObject("cover")!=null&&a.cover(it.book)==null}
        if(missing.isEmpty()){done(draw(a,list,goal,year));return}
        var left=missing.size;var finished=false
        val h=android.os.Handler(android.os.Looper.getMainLooper())
        fun go(){if(finished)return;finished=true;done(draw(a,list,goal,year))}
        missing.forEach{yb->a.requestCover(yb.book){left--;if(left<=0)h.post{go()}}}
        h.postDelayed({go()},1500)}
    private fun draw(a:MainActivity,list:List<YearBook>,goal:Int,year:Int):Bitmap{
        val W=1080f;val H=1920f;val bmp=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);val c=Canvas(bmp)
        val p=Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(0xff141417.toInt())
        p.shader=RadialGradient(W*.82f,0f,W*.95f,intArrayOf(0x592fd38a,0x002fd38a),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,W,H,p)
        p.shader=RadialGradient(0f,H,W*.9f,intArrayOf(0x407c5cff,0x007c5cff),null,Shader.TileMode.CLAMP);c.drawRect(0f,0f,W,H,p);p.shader=null
        val tp=Paint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface=PolkaFonts.sans(600);tp.textSize=40f;tp.letterSpacing=.1f;tp.color=0xff9df5c8.toInt();c.drawText("МОЙ ГОД В КНИГАХ",88f,180f,tp)
        tp.letterSpacing=0f;tp.typeface=PolkaFonts.display(700);tp.textSize=220f;tp.color=0xfff3f3f5.toInt();c.drawText("$year",80f,400f,tp)
        tp.typeface=PolkaFonts.sans(400);tp.textSize=52f;tp.color=0xffcfcfd4.toInt()
        c.drawText((if(goal>0)"${list.size} из ${ofBooks(goal)}" else MainActivity.countBooks(list.size))+if(goal>0&&list.size>=goal)" · цель достигнута" else "",88f,490f,tp)
        // the covers: as large as fit between the heading and the footer
        val gx=88f;val gw=W-2*gx;val gy=580f;val gh=1660f-gy;val gap=30f
        var cols=3;var cell=0f;var rows=0
        while(true){cell=(gw-(cols-1)*gap)/cols;rows=ceil(list.size/cols.toFloat()).toInt().coerceAtLeast(1);val rowH=cell*1.5f+cell*.3f+gap*1.6f;if(rows*rowH<=gh||cols>=7)break;cols++}
        val rowH=cell*1.5f+cell*.3f+gap*1.6f;val fit=max(1,(gh/rowH).toInt())*cols
        val shown=if(list.size>fit)list.take(fit-1) else list
        val art=CoverView(a,JSONObject())
        shown.forEachIndexed{i,yb->val x=gx+(i%cols)*(cell+gap);val y=gy+(i/cols)*rowH;val r=RectF(x,y,x+cell,y+cell*1.5f)
            p.color=0x66000000;p.maskFilter=null;c.drawRoundRect(r.left+6,r.top+14,r.right-6,r.bottom+16,12f,12f,p)
            art.drawCover(c,r,yb.book,a.cover(yb.book))
            val m=cell*.3f;val rating=yb.book.optInt("rating")
            if(rating in 1..5){val v=RatingMedal(a,rating);v.layout(0,0,m.toInt(),m.toInt());c.save();c.translate(x+(cell-m)/2,r.bottom+gap*.4f);v.draw(c);c.restore()}}
        if(list.size>fit){val i=fit-1;val x=gx+(i%cols)*(cell+gap);val y=gy+(i/cols)*rowH;val r=RectF(x,y,x+cell,y+cell*1.5f)
            p.color=0x1affffff;c.drawRoundRect(r,14f,14f,p);tp.typeface=PolkaFonts.display(700);tp.textSize=cell*.32f;tp.color=0xfff3f3f5.toInt();tp.textAlign=Paint.Align.CENTER
            c.drawText("+${list.size-fit+1}",r.centerX(),r.centerY()-(tp.ascent()+tp.descent())/2,tp);tp.textAlign=Paint.Align.LEFT}
        // footer: the favourite book and the app's name
        val fav=list.firstOrNull{it.book.optInt("rating")==5}
        tp.typeface=PolkaFonts.display(700);tp.textSize=56f;tp.color=0xfff3f3f5.toInt();tp.textAlign=Paint.Align.RIGHT;c.drawText("Полка",W-88f,1820f,tp);tp.textAlign=Paint.Align.LEFT
        if(fav!=null){tp.typeface=PolkaFonts.sans(400);tp.textSize=34f;tp.color=0xff9b9ba1.toInt()
            val label=TextUtils.ellipsize("любимая: ${fav.book.str("title")}",android.text.TextPaint(tp),W-88f*2-260f,TextUtils.TruncateAt.END).toString();c.drawText(label,88f,1818f,tp)}
        return bmp}
}
