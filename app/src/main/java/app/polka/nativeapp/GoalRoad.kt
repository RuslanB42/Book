package app.polka.nativeapp

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import org.json.JSONObject
import java.io.File
import kotlin.math.*

// ───────────────────────── shared bits ─────────────────────────

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

// ───────────────────────── year menu on the card ─────────────────────────

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
    private lateinit var page:LinearLayout
    private var share:FrameLayout?=null
    private var closing=false
    private val insets=android.graphics.Rect()
    private var from:RectF?=null
    private var snap:Bitmap?=null
    private val bounds=RectF();private var radius=0f
    private var anim:ValueAnimator?=null
    fun show(){
        val decor=a.window.decorView as ViewGroup
        frame.isClickable=true;frame.setBackgroundColor(a.bg)
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
        // approved «Мой год в книгах»: the years small in the middle of the bar (this one bright), the page below
        tabs=a.line().apply{gravity=Gravity.CENTER}
        bar.addView(HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(tabs)},LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(10);marginEnd=a.d(10)})
        bar.addView(round("share","Поделиться годом в книгах"){openShare()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
        col.addView(bar)
        val page=a.column();this.page=page
        progress=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(18),0)}
        page.addView(progress,LinearLayout.LayoutParams(-1,a.d(30)).apply{topMargin=a.d(12)})
        roadHost=FrameLayout(a);page.addView(roadHost,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        // the year and the progress are part of the page and scroll away with it; content fades softly under the buttons
        scroll=object:ScrollView(a){override fun getBottomFadingEdgeStrength()=0f}.apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_IF_CONTENT_SCROLLS;clipToPadding=false;setPadding(0,0,0,insets.bottom+a.d(24))
            isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(28))}
        scroll.addView(page,ViewGroup.LayoutParams(-1,-2))
        scroll.setOnScrollChangeListener{_,_,_,_,_->(roadHost.getChildAt(roadHost.childCount-1)?.tag as? YearRails)?.stick(scroll)}
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
            tabs.addView(a.text("$y",15f,if(on)a.fg else a.muted,on).apply{if(!on)typeface=PolkaFonts.sans(500);isClickable=true;contentDescription=if(on)"$y год, выбран" else "$y год";setPadding(a.d(8),a.d(8),a.d(8),a.d(8))
                setOnClickListener{if(y!=year){year=y;onYear(y);bindYear(true)}}},LinearLayout.LayoutParams(-2,-2))}
        val list=yearBooks(model,data,year);val goal=model.target(year)
        // approved «Кольцо»: the big ring, the words, the year's results and the books by month
        progress.removeAllViews();progress.visibility=View.GONE
        val old=roadHost.getChildAt(0);val nw=a.goalYearPage(model,list,goal,year,today,motion)
        roadHost.addView(nw,FrameLayout.LayoutParams(-1,-2))
        scroll.scrollTo(0,0);nw.post{(nw.tag as? YearRails)?.stick(scroll)}
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
        // the year page itself, whole, as one picture (the month names back in their places)
        (roadHost.getChildAt(roadHost.childCount-1)?.tag as? YearRails)?.reset()
        val bmp=try{val w=page.width;val h=min(page.height,w*6);Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).also{val c=Canvas(it);c.drawColor(a.bg);page.draw(c)}}catch(e:Throwable){null}
        (roadHost.getChildAt(roadHost.childCount-1)?.tag as? YearRails)?.stick(scroll)
        if(bmp!=null){poster?.recycle();poster=bmp;img.setImageBitmap(bmp)
            val r=bmp.height.toFloat()/bmp.width;val iw=if(ph/r>pw)pw else (ph/r).toInt();img.layoutParams=img.layoutParams.apply{width=if(ph/r>pw)pw else iw;height=if(ph/r>pw)(pw*r).toInt() else ph}}
    }
    private fun closeShare(){val layer=share?:return;share=null
        if(motion)layer.animate().alpha(0f).setDuration(180).withEndAction{frame.removeView(layer)}.start() else frame.removeView(layer)}
    private fun send(y:Int){val bmp=poster?:return;val dir=File(a.cacheDir,"calendar-share").apply{mkdirs()};val file=File(dir,"Polka-goal-$y.png")
        a.work({file.outputStream().use{bmp.compress(Bitmap.CompressFormat.PNG,100,it)}}){
            val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",file)
            a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="image/png";putExtra(Intent.EXTRA_STREAM,uri);clipData=ClipData.newUri(a.contentResolver,"Мой год в книгах",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Поделиться"))}}
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

// ───────────────────────── the share picture ─────────────────────────

