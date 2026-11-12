package app.polka.nativeapp

import android.animation.ValueAnimator
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
import androidx.core.view.ViewCompat
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.*

// ───────────────────────── shared bits ─────────────────────────

internal fun goalGreen(a:MainActivity)=if(a.light)0xff009a5c.toInt() else 0xff37c080.toInt()
internal fun goalGreen2(a:MainActivity)=if(a.light)0xff54b581.toInt() else 0xff7bdca6.toInt()
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
    override fun onDraw(c:Canvas){
        // the same icons as everywhere else in the app
        val z=min(width,height).toFloat();c.save();c.translate((width-z)/2,(height-z)/2);c.scale(z/24f,z/24f)
        val drawn=IosIcons.draw(c,when(kind){"down"->"chevdown";"expand"->"expand";else->"share"},ink,false,0);c.restore();if(drawn)return
        val s=min(width,height)/16f;c.save();c.translate((width-16*s)/2,(height-16*s)/2);c.scale(s,s)
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
        background=GradientDrawable().apply{setColor(if(light)0xffffffff.toInt() else 0xff2a2a2e.toInt());cornerRadius=d(20).toFloat();setStroke(d(1),if(light)0x0f000000 else 0x10ffffff)}}
    val popup=GentlePopup(box,d(236),motion,onClose).apply{elevation=d(22).toFloat();isOutsideTouchable=true;setBackgroundDrawable(ColorDrawable(0));animationStyle=0}
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

/** A popup that folds away (scale and fade towards its anchor) instead of vanishing; a tap outside or on the
 *  anchor again closes it the same gentle way. [onClose] fires as the fold starts, so the chevron turns back with it. */
internal class GentlePopup(private val box:View,width:Int,private val motion:Boolean,private val onClose:()->Unit):PopupWindow(box,width,ViewGroup.LayoutParams.WRAP_CONTENT,true){
    private var folding=false
    override fun dismiss(){if(folding)return;folding=true;onClose()
        if(!motion){super.dismiss();return}
        box.animate().cancel();box.animate().scaleX(.9f).scaleY(.9f).alpha(0f).setDuration(200).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{close()}.start()}
    private fun close(){super.dismiss()}
}

// ───────────────────────── full-screen road ─────────────────────────

/**
 * The goal road opened to the whole screen, as a layer over the app (no new window, so nothing stalls the first
 * frames). It grows out of the card as a container transform: the rounded bounds morph from the card to the screen
 * while the card's picture fades out and the road, scaled with the bounds, fades in; closing runs it backwards.
 * Year tabs and the progress sit on top; below winds the road with big covers and their details.
 */
internal class GoalRoadOverlay(private val a:MainActivity,private val model:ReadingStatistics,private val data:JSONObject,private var year:Int,
    private val years:List<Int>,private val motion:Boolean,private val source:()->View?,private val onYear:(Int)->Unit){
    private val today=model.today
    private val frame=FrameLayout(a)
    private val col=a.column()
    private val snapView=ImageView(a).apply{scaleType=ImageView.ScaleType.FIT_XY;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private lateinit var tabs:LinearLayout
    private lateinit var progress:LinearLayout
    private lateinit var scroll:ScrollView
    private lateinit var roadHost:FrameLayout
    private var column:RoadColumn?=null
    private var share:FrameLayout?=null
    private var closing=false
    private val insets=android.graphics.Rect()
    private var from:RectF?=null
    private var snap:Bitmap?=null
    private val bounds=RectF();private var radius=0f
    private var anim:ValueAnimator?=null
    fun show(){
        val decor=a.window.decorView as ViewGroup
        frame.isClickable=true;frame.background=GoalBackdrop(a)
        frame.outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){
            if(bounds.isEmpty)o.setRect(0,0,v.width,v.height) else o.setRoundRect(bounds.left.roundToInt(),bounds.top.roundToInt(),bounds.right.roundToInt(),bounds.bottom.roundToInt(),radius)}}
        frame.clipToOutline=true
        ViewCompat.getRootWindowInsets(decor)?.let{val s=SafeArea.content(it,false);insets.set(s.left,s.top,s.right,s.bottom)}
        // header: collapse · caption · share, then the years as big serif tabs and the progress
        val bar=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),a.d(8),a.d(16),0)}
        fun round(kind:String,desc:String,click:()->Unit)=FrameLayout(a).apply{isClickable=true;contentDescription=desc;setOnClickListener{click()}
            background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(if(a.light)0x0d000000 else 0x14ffffff)}
            addView(Glyph(a,kind,a.fg),FrameLayout.LayoutParams(a.d(17),a.d(17),Gravity.CENTER));a.press(this)}
        bar.addView(round("down","Свернуть"){close()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
        bar.addView(a.text("ЦЕЛЬ НА ГОД",12.5f,a.muted,true).apply{letterSpacing=.06f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
        bar.addView(round("share","Поделиться годом в книгах"){openShare()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
        col.addView(bar)
        tabs=a.line().apply{gravity=Gravity.BOTTOM;setPadding(a.d(18),0,a.d(18),0)}
        val page=a.column()
        page.addView(HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(tabs)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
        progress=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(18),0)}
        page.addView(progress,LinearLayout.LayoutParams(-1,a.d(30)).apply{topMargin=a.d(12)})
        roadHost=FrameLayout(a);page.addView(roadHost,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        // the year and the progress are part of the page and scroll away with it; content fades softly under the buttons
        scroll=object:ScrollView(a){override fun getBottomFadingEdgeStrength()=0f}.apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;clipToPadding=false;setPadding(0,0,0,insets.bottom+a.d(24))
            isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(28))}
        scroll.addView(page,ViewGroup.LayoutParams(-1,-2))
        scroll.setOnScrollChangeListener{_,_,y,_,_->column?.viewport(y-roadHost.top,scroll.height)}
        col.addView(scroll,LinearLayout.LayoutParams(-1,0,1f).apply{topMargin=a.d(4)})
        col.setPadding(insets.left,insets.top,insets.right,0)
        frame.addView(col,FrameLayout.LayoutParams(-1,-1))
        decor.addView(frame,FrameLayout.LayoutParams(-1,-1))
        a.goalRoad=this
        bindYear(false)
        if(!motion)return
        capture();apply(0f);col.setLayerType(View.LAYER_TYPE_HARDWARE,null)
        // start on the first drawn frame, so no step of the morph is skipped
        frame.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{frame.viewTreeObserver.removeOnPreDrawListener(this)
            run(0f,1f,520,PathInterpolator(.2f,0f,0f,1f)){col.setLayerType(View.LAYER_TYPE_NONE,null);frame.removeView(snapView);bounds.setEmpty();frame.invalidateOutline()};return true}})
    }
    private fun run(f:Float,t:Float,ms:Long,i:android.animation.TimeInterpolator,end:()->Unit){anim?.cancel()
        anim=ValueAnimator.ofFloat(f,t).apply{duration=ms;interpolator=i;addUpdateListener{apply(it.animatedValue as Float)}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(x:android.animation.Animator){if(!cancelled)end()}});start()}}
    private var k=1f
    /** One step of the morph: [p] 0 is the card, 1 the whole screen. */
    private fun apply(p:Float){k=p;val W=frame.width.takeIf{it>0}?.toFloat()?:(a.window.decorView.width.toFloat());val H=frame.height.takeIf{it>0}?.toFloat()?:(a.window.decorView.height.toFloat())
        val f=from?:RectF(0f,0f,W,H)
        bounds.set(f.left*(1-p),f.top*(1-p),f.right+(W-f.right)*p,f.bottom+(H-f.bottom)*p);radius=a.d(28)*(1-p);frame.invalidateOutline()
        val sc=bounds.width()/W;col.pivotX=0f;col.pivotY=0f;col.scaleX=sc;col.scaleY=sc;col.translationX=bounds.left;col.translationY=bounds.top
        col.alpha=((p-.12f)/.42f).coerceIn(0f,1f)
        val s=snap;if(s!=null){val ss=bounds.width()/s.width;snapView.pivotX=0f;snapView.pivotY=0f;snapView.scaleX=ss;snapView.scaleY=ss;snapView.translationX=bounds.left;snapView.translationY=bounds.top
            snapView.alpha=(1f-p/.32f).coerceIn(0f,1f)}}
    /** Where the card is now and a picture of it, for the morph. */
    private fun capture(){val v=source();frame.removeView(snapView);snapView.setImageDrawable(null);snap?.recycle();snap=null
        if(v==null||!v.isAttachedToWindow||v.width==0){from=null;return}
        val l=IntArray(2);v.getLocationOnScreen(l);val o=IntArray(2);a.window.decorView.getLocationOnScreen(o)
        from=RectF((l[0]-o[0]).toFloat(),(l[1]-o[1]).toFloat(),(l[0]-o[0]+v.width).toFloat(),(l[1]-o[1]+v.height).toFloat())
        snap=try{Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}}catch(e:Throwable){null}
        snap?.let{snapView.setImageBitmap(it);frame.addView(snapView,FrameLayout.LayoutParams(it.width,it.height,Gravity.TOP or Gravity.START))}}
    private fun bindYear(animated:Boolean){
        tabs.removeAllViews()
        years.forEach{y->val on=y==year
            tabs.addView(a.text("$y",28f,if(on)a.fg else if(a.light)0xffbdbdc1.toInt() else 0xff4b4b52.toInt(),true,serif=true).apply{isClickable=true;contentDescription="$y год"
                setOnClickListener{if(y!=year){year=y;onYear(y);bindYear(true)}}},LinearLayout.LayoutParams(-2,-2).apply{marginEnd=a.d(20)})}
        val list=yearBooks(model,data,year);val goal=model.target(year)
        progress.removeAllViews()
        progress.addView(a.text(if(goal>0)"${list.size} / $goal" else MainActivity.countBooks(list.size),22f,a.fg,true,serif=true))
        progress.addView(GoalTrack(a,if(goal>0)list.size.toFloat()/goal else 1f,motion),LinearLayout.LayoutParams(0,a.d(6),1f).apply{marginStart=a.d(14)})
        val old=column;val nw=RoadColumn(a,list,goal,year,today,motion)
        roadHost.addView(nw,FrameLayout.LayoutParams(-1,-2));column=nw
        scroll.scrollTo(0,0);nw.post{nw.viewport(scroll.scrollY-roadHost.top,scroll.height)}
        if(old!=null){if(animated&&motion){old.animate().alpha(0f).setDuration(180).withEndAction{roadHost.removeView(old)}.start()
            nw.alpha=0f;nw.animate().alpha(1f).setStartDelay(120).setDuration(380).setInterpolator(Ease.outCubic).start()} else roadHost.removeView(old)}
    }
    fun back(){if(share!=null)closeShare() else close()}
    fun close(){if(closing)return;closing=true
        closeShare()
        if(!motion){finish();return}
        capture();bounds.set(0f,0f,frame.width.toFloat(),frame.height.toFloat());col.setLayerType(View.LAYER_TYPE_HARDWARE,null);apply(k)
        run(k,0f,420,PathInterpolator(.3f,0f,.1f,1f)){finish()}}
    private fun finish(){(frame.parent as? ViewGroup)?.removeView(frame);if(a.goalRoad===this)a.goalRoad=null;snapView.setImageDrawable(null);snap?.recycle();snap=null;poster?.recycle();poster=null}

    // ───── share picture ─────
    private var poster:Bitmap?=null
    private fun openShare(){if(share!=null)return
        val list=yearBooks(model,data,year);val goal=model.target(year);val y=year
        val layer=FrameLayout(a).apply{setBackgroundColor(if(a.light)0xe6f2f2f0.toInt() else 0xe608080a.toInt());isClickable=true;setOnClickListener{closeShare()}}
        val box=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
        val img=ImageView(a).apply{scaleType=ImageView.ScaleType.FIT_CENTER;elevation=a.d(16).toFloat();clipToOutline=true;contentDescription="Мой год в книгах — $y"
            outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,a.d(22).toFloat())}};isClickable=true}
        val sh=frame.height-insets.top-insets.bottom;val ph=min((sh*.68f).toInt(),a.d(560));val pw=ph*9/16
        box.addView(img,LinearLayout.LayoutParams(pw,ph))
        val acts=a.line().apply{gravity=Gravity.CENTER}
        fun act(label:String,primary:Boolean,click:()->Unit)=a.text(label,16f,if(primary)a.bg else a.fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(22),0,a.d(22),0);isClickable=true
            background=GradientDrawable().apply{setColor(if(primary)a.fg else if(a.light)0x14000000 else 0x1affffff);cornerRadius=a.d(24).toFloat()};setOnClickListener{click()};a.press(this)}
        acts.addView(act("Закрыть",false){closeShare()},LinearLayout.LayoutParams(-2,a.d(48)))
        acts.addView(act("Отправить",true){send(y)},LinearLayout.LayoutParams(-2,a.d(48)).apply{marginStart=a.d(10)})
        box.addView(acts,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18)})
        layer.addView(box,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        frame.addView(layer,FrameLayout.LayoutParams(-1,-1));share=layer
        if(motion){layer.alpha=0f;layer.animate().alpha(1f).setDuration(220).start()
            img.translationY=a.d(24).toFloat();img.alpha=0f
            img.animate().translationY(0f).alpha(1f).setStartDelay(60).setDuration(520).setInterpolator(PathInterpolator(.16f,1f,.3f,1f)).start()}
        GoalPoster.render(a,list,goal,y){bmp->if(share!==layer){bmp.recycle();return@render};poster?.recycle();poster=bmp;img.setImageBitmap(bmp)}
    }
    private fun closeShare(){val layer=share?:return;share=null
        if(motion)layer.animate().alpha(0f).setDuration(180).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}
    private fun send(y:Int){val bmp=poster?:return;val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,"Polka-goal-$y.png")
        a.work({file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}){
            val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Мой год в книгах",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться"))}}
}

/** The road screen's backdrop: the page colour with a soft green light from the top right. The light is baked once
 *  into an opaque picture with a gaussian falloff and fine per-channel noise, so it shows no rings or layers. */
internal class GoalBackdrop(private val a:MainActivity):android.graphics.drawable.Drawable(){
    private val p=Paint(Paint.FILTER_BITMAP_FLAG)
    override fun draw(c:Canvas){val b=bounds;c.drawColor(a.bg);val t=bake(a,b.width())?:return;c.drawBitmap(t,b.left.toFloat(),b.top.toFloat(),p)}
    override fun setAlpha(alpha:Int){}
    override fun setColorFilter(cf:ColorFilter?){}
    @Deprecated("Deprecated in Java") override fun getOpacity()=PixelFormat.OPAQUE
    companion object{
        private var tex:Bitmap?=null;private var key=""
        /** Bake ahead of time (the card calls this while the statistics are shown) so opening never waits. */
        fun prepare(a:MainActivity){val w=a.resources.displayMetrics.widthPixels;val k="$w${a.light}";if(k==key)return
            a.work({bake(a,w)})}
        @Synchronized fun bake(a:MainActivity,w:Int):Bitmap?{if(w<=0)return null;val k="$w${a.light}";if(k==key&&tex!=null)return tex
            val rx=w*1.05f;val h=(rx*1.25f).toInt()
            val out=Glow.face(w,h,a.bg,0xff37c080.toInt(),w*.82f,0f,rx*1.7f,if(a.light).09f else .15f,2.6f*1.7f*1.7f)?:return null;tex=out;key=k;return out}
    }
}

/** Five plain stars, filled yellow up to the rating. */
internal class StarRow(private val a:MainActivity,var rating:Int,private val sizeDp:Float,private val gapDp:Float=2f):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    init{contentDescription=if(rating in 1..5)"Оценка $rating из 5" else "Без оценки"}
    override fun onMeasure(w:Int,h:Int)=setMeasuredDimension((a.d(1)*(sizeDp*5+gapDp*4)).roundToInt(),(a.d(1)*sizeDp).roundToInt())
    override fun onDraw(c:Canvas){val s=a.d(1)*sizeDp;val g=a.d(1)*gapDp
        for(i in 0 until 5){p.color=if(i<rating)starYellow(a) else starEmpty(a);drawStar(c,i*(s+g),0f,s,p)}}
    companion object{
        fun starYellow(a:MainActivity)=if(a.light)0xfff5a524.toInt() else 0xffffc53d.toInt()
        fun starEmpty(a:MainActivity)=if(a.light)0x24000000 else 0x2effffff
        /** A rounded five-point star filling the [size] square at x, y. */
        fun drawStar(c:Canvas,x:Float,y:Float,size:Float,p:Paint){val path=Path();val cx=x+size/2;val cy=y+size*.53f;val R=size*.5f;val r=R*.48f
            for(i in 0 until 10){val rr=if(i%2==0)R else r;val t=Math.toRadians(-90.0+36*i);val px=(cx+rr*cos(t)).toFloat();val py=(cy+rr*sin(t)).toFloat();if(i==0)path.moveTo(px,py) else path.lineTo(px,py)};path.close()
            p.style=Paint.Style.FILL;p.pathEffect=CornerPathEffect(size*.08f);c.drawPath(path,p);p.pathEffect=null}}
}

/** One big star of the rating picker: yellow when on, a quiet outline otherwise. */
internal class StarGlyph(private val a:MainActivity,on:Boolean):View(a){
    var on=on;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val s=min(width,height).toFloat();val x=(width-s)/2;val y=(height-s)/2
        if(on){p.color=StarRow.starYellow(a);StarRow.drawStar(c,x,y,s,p)}
        else{p.color=if(a.light)0x14000000 else 0x1fffffff;StarRow.drawStar(c,x,y,s,p)}}
}

/** A small cover for the card's road. The picture is scaled down in halves to its exact pixel size first (one big
 *  jump from a high-resolution cover leaves jagged, shimmering detail), then drawn with rounded corners. */
internal class MiniCover(private val a:MainActivity,private val book:JSONObject):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private var small:Bitmap?=null;private var source:Bitmap?=null;private var asked=false
    private val art by lazy{CoverView(a,JSONObject())};private val clip=Path();private val r=RectF()
    init{outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,2.5f*a.resources.displayMetrics.density)}}}
    override fun onDraw(c:Canvas){val pt0=StatsProbe.t();try{draw0(c)}finally{StatsProbe.add("Обложки цели",pt0)}}
    private fun draw0(c:Canvas){if(width==0)return;r.set(0f,0f,width.toFloat(),height.toFloat());val rad=2.5f*a.resources.displayMetrics.density
        c.save();clip.reset();clip.addRoundRect(r,rad,rad,Path.Direction.CW);c.clipPath(clip)
        val bmp=a.cover(book)
        if(bmp==null&&book.optJSONObject("cover")!=null){if(!asked){asked=true;a.requestCover(book){if(it!=null)invalidate()}};c.drawColor(if(a.light)0xffe6e4df.toInt() else 0xff2a2a2d.toInt())}
        else if(bmp==null)art.drawCover(c,r,book,null)
        else{if(source!==bmp||small==null||small!!.width!=width){source=bmp;small=try{fit(bmp,width,height)}catch(e:Throwable){null}}
            val s=small;if(s!=null)c.drawBitmap(s,null,r,p) else art.drawCover(c,r,book,bmp)}
        c.restore()}
    private fun fit(src:Bitmap,w:Int,h:Int):Bitmap{
        // crop to the cover's shape, then halve until close, then one last filtered step
        val sc=max(w.toFloat()/src.width,h.toFloat()/src.height);val cw=(w/sc).roundToInt().coerceAtMost(src.width);val ch=(h/sc).roundToInt().coerceAtMost(src.height)
        var cur=Bitmap.createBitmap(src,(src.width-cw)/2,(src.height-ch)/2,cw,ch)
        while(cur.width>w*2&&cur.height>h*2){val nx=Bitmap.createScaledBitmap(cur,cur.width/2,cur.height/2,true);if(cur!==src)cur.recycle();cur=nx}
        val out=Bitmap.createScaledBitmap(cur,w,h,true);if(cur!==src&&cur!==out)cur.recycle();return out}
    override fun onDetachedFromWindow(){super.onDetachedFromWindow();small?.recycle();small=null}
}

/** A flat book cover for the road: the picture with soft rounded corners and a soft painted shadow, no gloss.
 *  The shadow is drawn inside the view (it is [PAD] larger than the cover on every side), never as an elevation
 *  shadow, so the cover fades cleanly under the header like any other content. */
internal class FlatCover(private val a:MainActivity,private val book:JSONObject):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG);private var asked=false;private val art by lazy{CoverView(a,JSONObject())}
    private val pad=a.d(PAD).toFloat();private val clip=Path();private val r=RectF()
    override fun onDraw(c:Canvas){r.set(pad,pad,width-pad,height-pad);val rad=a.d(4).toFloat()
        shadow(r.width().roundToInt(),r.height().roundToInt())?.let{c.drawBitmap(it,null,RectF(0f,pad*.5f,width.toFloat(),height+pad*.5f),p)}
        c.save();clip.reset();clip.addRoundRect(r,rad,rad,Path.Direction.CW);c.clipPath(clip)
        val bmp=a.cover(book)
        if(bmp==null&&book.optJSONObject("cover")!=null){if(!asked){asked=true;a.requestCover(book){if(it!=null)invalidate()}};c.drawColor(if(a.light)0xffe6e4df.toInt() else 0xff2a2a2d.toInt())}
        else if(bmp==null)art.drawCover(c,r,book,null)
        else{val sc=max(r.width()/bmp.width,r.height()/bmp.height);val w=bmp.width*sc;val h=bmp.height*sc
            c.drawBitmap(bmp,null,RectF(r.centerX()-w/2,r.centerY()-h/2,r.centerX()+w/2,r.centerY()+h/2),p)}
        c.restore()}
    private fun shadow(w:Int,h:Int):Bitmap?{val key=w*10000+h;cache[key]?.let{return it}
        return try{val k=2;val pd=pad/k;val b=Bitmap.createBitmap(((w+pad*2)/k).roundToInt(),((h+pad*2)/k).roundToInt(),Bitmap.Config.ARGB_8888)
            Canvas(b).drawRoundRect(pd,pd,b.width-pd,b.height-pd,a.d(4)/k.toFloat(),a.d(4)/k.toFloat(),Paint(Paint.ANTI_ALIAS_FLAG).apply{color=if(a.light)0x40000000 else 0x99000000.toInt()})
            val out=ShelfFrost.blur(b,a.d(6)/k.toFloat());b.recycle();cache[key]=out;out}catch(e:Throwable){null}}
    companion object{const val PAD=14;private val cache=HashMap<Int,Bitmap>()}
}

/** The long winding road of the year: one milestone per book of the goal, big covers alternating sides. */
internal class RoadColumn(private val a:MainActivity,private val books:List<YearBook>,private val goal:Int,private val year:Int,private val today:LocalDate,private val motion:Boolean):FrameLayout(a){
    private val d=a.d(1).toFloat()
    private val slots=if(goal>0)max(goal,books.size) else books.size
    /** A big goal (over 20 books) keeps only the books already read on the road; the way still ahead is one long
     *  stretch to the flag instead of dozens of empty places. */
    private val geo=if(slots>20)books.size else slots
    private val top=d*70
    private val pts=ArrayList<PointF>()
    private val road=Path();private val walked=Path();private var walkedEnd=0f
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val tp=Paint(Paint.ANTI_ALIAS_FLAG)
    private val items=ArrayList<View>();private val slotViews=ArrayList<View>()
    private val startLabel=a.text("Старт · 1 января",12f,a.muted).apply{gravity=Gravity.CENTER}
    private val finish=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL}
    private var reach=0f;private var intro=if(motion)0f else 1f
    private var glow:Bitmap?=null;private var glowAlpha=0f;private var glowGen=0
    private val part=Path();private val walkMeasure=PathMeasure();private var walkLen=0f;private val glowPaint=Paint(Paint.FILTER_BITMAP_FLAG)
    private val revealed=HashSet<View>()
    init{setWillNotDraw(false);clipChildren=false
        books.forEachIndexed{i,b->val v=item(i,b);items.add(v);addView(v);if(motion){v.alpha=0f;v.translationY=d*18;v.translationX=if(i%2==0)d*28 else -d*28} else revealed.add(v)}
        
        addView(startLabel)
        if(goal>0){finish.addView(a.text("Финиш — ${MainActivity.countBooks(goal)}",26f,a.fg,true,serif=true).apply{gravity=Gravity.CENTER})
            val pace=goalPace(year,today,books.size,goal);if(pace.isNotEmpty()){a.space(finish,6);finish.addView(a.text(pace,12.5f,a.muted).apply{gravity=Gravity.CENTER})}}
        else if(books.isEmpty())finish.addView(a.text("Пока ни одной книги за $year — дочитанные появятся здесь.",13f,a.muted).apply{gravity=Gravity.CENTER})
        addView(finish)
        if(motion)ValueAnimator.ofFloat(0f,1f).apply{startDelay=320;duration=900L+min(books.size,8)*140L;interpolator=PathInterpolator(.45f,0f,.2f,1f);addUpdateListener{intro=it.animatedValue as Float;invalidate()};start()}}
    /** One book: the cover stands on its milestone at the edge, the details sit beside it towards the middle. */
    private fun item(i:Int,yb:YearBook):View{val right=i%2==0
        val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        val cover=CoverView(a,yb.book,a.cover(yb.book)).apply{softShadow=true;hiRes=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        val g=if(right)Gravity.END else Gravity.START
        val t=a.column().apply{gravity=g}
        fun add(v:TextView,top:Int=0){v.gravity=g;t.addView(v,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(top)})}
        add(a.text(yb.book.str("title"),19f,a.fg,true,serif=true).apply{maxLines=3;ellipsize=TextUtils.TruncateAt.END})
        val author=MainActivity.authors(yb.book);if(author.isNotBlank())add(a.text(author,12.5f,a.muted).apply{maxLines=2;ellipsize=TextUtils.TruncateAt.END},4)
        fun f(dt:LocalDate)="${dt.dayOfMonth} ${YearRoad.MONTHS_SHORT[dt.monthValue-1]}"+if(dt.year!=year)" ${dt.year}" else ""
        add(a.text(if(yb.start==yb.end)f(yb.end) else "${f(yb.start)} — ${f(yb.end)}",12.5f,a.fg),12)
        add(a.text("за "+daysWord((java.time.temporal.ChronoUnit.DAYS.between(yb.start,yb.end)+1).toInt().coerceAtLeast(1)),12f,if(a.light)0xff9a9aa0.toInt() else 0xff74747a.toInt()),3)
        val r=yb.book.optInt("rating")
        val rate=StarRow(a,r,15f,3f)
        t.addView(rate,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(10);gravity=g})
        val pad=0
        val coverLp=LinearLayout.LayoutParams(a.d(96),a.d(144))
        if(right){row.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=a.d(16)+pad});row.addView(cover,coverLp)}
        else{row.addView(cover,coverLp);row.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=a.d(16)+pad})}
        row.tag=right
        row.contentDescription="${i+1}-я книга: ${yb.book.str("title")}, ${f(yb.start)} — ${f(yb.end)}"+if(r in 1..5)", оценка $r из 5" else ""
        return row}
    private var step=d*230;private var first=d*170;private var tallestH=d*144
    override fun onMeasure(w:Int,h:Int){val W=MeasureSpec.getSize(w)
        val side=MeasureSpec.makeMeasureSpec(W-a.d(32),MeasureSpec.EXACTLY);val free=MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED)
        items.forEach{it.measure(side,free)};slotViews.forEach{it.measure(MeasureSpec.makeMeasureSpec(a.d(64),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(a.d(96),MeasureSpec.EXACTLY))}
        startLabel.measure(MeasureSpec.makeMeasureSpec(W,MeasureSpec.EXACTLY),free);finish.measure(side,free)
        // every book gets enough room so neighbours never touch, however long the title is
        val tallest=(items.maxOfOrNull{it.measuredHeight}?:a.d(144)).toFloat()
        tallestH=tallest;step=max(d*260,tallest+d*140);first=max(d*170,tallest/2+d*120)
        setMeasuredDimension(W,(finishY()+d*120).toInt())}
    private fun finishY()=(if(geo>0)milestoneY(geo) else top)+(if(geo in 1..books.size)tallestH/2+(if(geo<slots)d*320 else d*140) else if(geo==0&&slots>0)d*320 else d*120)
    /** Read books get room for their cover and details; the places still ahead come closer together. */
    private fun milestoneY(k:Int):Float{val n=books.size;return if(k<=n||n==0)top+first+(k-1)*step else top+first+(n-1)*step+tallestH/2+(k-n)*d*130}
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){val W=w.toFloat()
        pts.clear();pts.add(PointF(W/2,top))
        // milestones sit under the middle of the covers, which stand at the screen edges
        for(k in 1..geo)pts.add(PointF(if(k%2==1)W-d*64 else d*64,milestoneY(k)))
        pts.add(PointF(W/2,finishY()))
        road.reset()
        val lens=FloatArray(pts.size);var total=0f;val pm=PathMeasure()
        // the road runs straight down under each cover and only swings across in the free space between two books,
        // so it never passes under a title, the dates or the stars
        fun half(k:Int)=if(k in 1..books.size)items[k-1].measuredHeight/2f+d*12 else 0f
        for(k in 1 until pts.size){val a0=pts[k-1];val b=pts[k];val seg=Path();seg.moveTo(a0.x,a0.y)
            val y0=a0.y+half(k-1);val y1=b.y-half(k)
            if(k==1)road.moveTo(a0.x,a0.y)
            fun line(x:Float,y:Float){seg.lineTo(x,y);road.lineTo(x,y)}
            fun curve(x1:Float,y1c:Float,x2:Float,y2c:Float,x:Float,y:Float){seg.cubicTo(x1,y1c,x2,y2c,x,y);road.cubicTo(x1,y1c,x2,y2c,x,y)}
            if(y1-y0>d*40){if(y0>a0.y)line(a0.x,y0);val bend=(y1-y0)*.5f;curve(a0.x,y0+bend,b.x,y1-bend,b.x,y1);if(b.y>y1)line(b.x,b.y)}
            else{val bend=(b.y-a0.y)*.55f;curve(a0.x,a0.y+bend,b.x,b.y-bend,b.x,b.y)}
            pm.setPath(seg,false);total+=pm.length;lens[k]=total}
        walked.reset();pm.setPath(road,false);val n=min(books.size,geo);if(n>0)pm.getSegment(0f,lens[n],walked,true)
        walkedEnd=if(n>0)pts[n].y else top
        walkMeasure.setPath(walked,false);walkLen=walkMeasure.length
        // the soft glow under the walked road is blurred off the main thread and fades in when ready
        glow?.recycle();glow=null;glowAlpha=0f;val gen=++glowGen
        if(!a.light&&n>0){val path=Path(walked);var out:Bitmap?=null
            a.work({out=Glow.blurred(w,h,4,d*6f){c,p->p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=d*16;p.color=0xff37c080.toInt();c.drawPath(path,p)}}){val g=out
                if(g==null)return@work
                if(gen!=glowGen||!isAttachedToWindow){g.recycle();return@work}
                glow=g;if(!motion){glowAlpha=1f;invalidate()} else ValueAnimator.ofFloat(0f,1f).apply{duration=500;addUpdateListener{glowAlpha=it.animatedValue as Float;invalidate()};start()}}}}
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
        // the walked part draws itself along its length once (a round head, no hard edges); then its glow fades in
        if(!walked.isEmpty){if(intro>=1f)part.set(walked) else{part.reset();walkMeasure.getSegment(0f,walkLen*intro,part,true)}
            val ga=glowAlpha*((intro-.8f)/.2f).coerceIn(0f,1f)
            if(ga>0f)glow?.let{g->glowPaint.alpha=(0x70*ga).toInt();c.drawBitmap(g,null,RectF(0f,0f,W,height.toFloat()),glowPaint)}
            p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=d*7;p.color=Color.WHITE;p.shader=LinearGradient(0f,top,0f,walkedEnd,green,green2,Shader.TileMode.CLAMP);c.drawPath(part,p);p.shader=null}
        p.style=Paint.Style.FILL
        for(k in 1..geo){val q=pts[k];val done=k<=books.size;p.color=if(done)(if(a.light)green else Color.WHITE) else if(a.light)0x40000000 else 0x4dffffff;c.drawCircle(q.x,q.y,d*(if(done)5f else 3.5f),p)}
        val s=pts[0];p.color=a.bg;c.drawCircle(s.x,s.y,d*9,p);p.style=Paint.Style.STROKE;p.strokeWidth=d*4;p.color=green;c.drawCircle(s.x,s.y,d*9,p);p.style=Paint.Style.FILL
        if(goal>0){val e=pts.last();p.color=a.fg;c.drawRect(e.x-d*1.5f,e.y-d*44,e.x+d*1.5f,e.y,p)
            c.drawPath(Path().apply{moveTo(e.x+d*1.5f,e.y-d*44);lineTo(e.x+d*27,e.y-d*36);lineTo(e.x+d*1.5f,e.y-d*27);close()},p)}}
    override fun onDetachedFromWindow(){super.onDetachedFromWindow();glowGen++;glow?.recycle();glow=null}
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
        tp.typeface=PolkaFonts.sans(600);tp.textSize=40f;tp.letterSpacing=.1f;tp.color=0xff7bdca6.toInt();c.drawText("МОЙ ГОД В КНИГАХ",88f,180f,tp)
        tp.letterSpacing=0f;tp.typeface=PolkaFonts.display(700);tp.textSize=220f;tp.color=0xfff3f3f5.toInt();c.drawText("$year",80f,400f,tp)
        tp.typeface=PolkaFonts.sans(400);tp.textSize=52f;tp.color=0xffcfcfd4.toInt()
        c.drawText((if(goal>0)"${list.size} из ${ofBooks(goal)}" else MainActivity.countBooks(list.size))+if(goal>0&&list.size>=goal)" · цель достигнута" else "",88f,490f,tp)
        // the covers: as large as fit between the heading and the footer
        val gx=88f;val gw=W-2*gx;val gy=580f;val gh=1660f-gy;val gap=30f
        var cols=3;var cell=0f;var rows=0
        while(true){cell=(gw-(cols-1)*gap)/cols;rows=ceil(list.size/cols.toFloat()).toInt().coerceAtLeast(1);val rowH=cell*1.5f+cell*.15f+gap*1.6f;if(rows*rowH<=gh||cols>=7)break;cols++}
        val rowH=cell*1.5f+cell*.15f+gap*1.6f;val fit=max(1,(gh/rowH).toInt())*cols
        val shown=if(list.size>fit)list.take(fit-1) else list
        val art=CoverView(a,JSONObject())
        shown.forEachIndexed{i,yb->val x=gx+(i%cols)*(cell+gap);val y=gy+(i/cols)*rowH;val r=RectF(x,y,x+cell,y+cell*1.5f)
            p.color=0x66000000;p.maskFilter=null;c.drawRoundRect(r.left+6,r.top+14,r.right-6,r.bottom+16,12f,12f,p)
            art.drawCover(c,r,yb.book,a.cover(yb.book))
            val st=cell*.15f;val gp=st*.2f;val rating=yb.book.optInt("rating");val sx=x+(cell-(st*5+gp*4))/2
            for(k in 0 until 5){p.color=if(k<rating)0xffffc53d.toInt() else 0x2effffff;StarRow.drawStar(c,sx+k*(st+gp),r.bottom+gap*.5f,st,p)}}
        if(list.size>fit){val i=fit-1;val x=gx+(i%cols)*(cell+gap);val y=gy+(i/cols)*rowH;val r=RectF(x,y,x+cell,y+cell*1.5f)
            p.color=0x1affffff;c.drawRoundRect(r,14f,14f,p);tp.typeface=PolkaFonts.display(700);tp.textSize=cell*.32f;tp.color=0xfff3f3f5.toInt();tp.textAlign=Paint.Align.CENTER
            c.drawText("+${list.size-fit+1}",r.centerX(),r.centerY()-(tp.ascent()+tp.descent())/2,tp);tp.textAlign=Paint.Align.LEFT}
        // footer: the favourite book and the app's name
        val fav=list.firstOrNull{it.book.optInt("rating")==5}
        tp.typeface=PolkaFonts.display(700);tp.textSize=56f;tp.color=0xfff3f3f5.toInt();tp.textAlign=Paint.Align.RIGHT;c.drawText("Glyph",W-88f,1820f,tp);tp.textAlign=Paint.Align.LEFT
        if(fav!=null){tp.typeface=PolkaFonts.sans(400);tp.textSize=34f;tp.color=0xff9b9ba1.toInt()
            val label=TextUtils.ellipsize("любимая: ${fav.book.str("title")}",android.text.TextPaint(tp),W-88f*2-260f,TextUtils.TruncateAt.END).toString();c.drawText(label,88f,1818f,tp)}
        return bmp}
}
