package app.polka.nativeapp
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject
import kotlin.math.*

class SelectedRecommendation(val a:MainActivity,val book:JSONObject,selected:Boolean):FrameLayout(a) {
    private val cover=CoverView(a,book)
    private var selectedNow=selected
    init { clipChildren=false; clipToPadding=false; addView(cover,LayoutParams(-1,-1)); setWillNotDraw(false) }
    override fun onMeasure(w:Int,h:Int) { val width=MeasureSpec.getSize(w); super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((width*1.5f).toInt(),MeasureSpec.EXACTLY)) }
    fun choose(value:Boolean) { selectedNow=value; invalidate(); if(a.prefs.str("motion")!="reduced") { scaleX=.94f; scaleY=.94f; animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(PathInterpolator(.34f,1.5f,.64f,1f)).start() } }
    override fun dispatchDraw(c:Canvas) { super.dispatchDraw(c); if(!selectedNow) return; val p=Paint(3).apply { color=a.accentFill; style=Paint.Style.STROKE; strokeWidth=a.d(2).toFloat() }; c.drawRoundRect(-a.d(3).toFloat(),-a.d(3).toFloat(),width+a.d(3).toFloat(),height+a.d(3).toFloat(),a.d(3).toFloat(),a.d(3).toFloat(),p); p.style=Paint.Style.FILL; p.color=a.bg; val x=width-a.d(5).toFloat(); val y=a.d(5).toFloat(); c.drawCircle(x,y,a.d(15).toFloat(),p); p.color=a.accentFill; c.drawCircle(x,y,a.d(12).toFloat(),p); p.color=0xff161617.toInt(); p.style=Paint.Style.STROKE; p.strokeWidth=a.d(2).toFloat(); p.strokeJoin=Paint.Join.ROUND; p.strokeCap=Paint.Cap.ROUND; c.drawPath(Path().apply { moveTo(x-a.d(4),y); lineTo(x-a.d(1),y+a.d(3)); lineTo(x+a.d(5),y-a.d(3)) },p) }
}
class RecommendationCard(val a:MainActivity,val book:JSONObject):FrameLayout(a) {
    private var direction:String?=null; private var progress=0f; private var depth=0f
    val colorDepth get()=depth
    init { addView(CoverView(a,book).apply{softShadow=true;stackShadow=true},LayoutParams(-1,-1)); clipChildren=false; clipToPadding=false; elevation=0f }
    override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){super.onSizeChanged(w,h,ow,oh);pivotY=h.toFloat()}
    fun stamp(dir:String?,p:Float) { direction=dir; progress=p; invalidate() }
    fun setColorFilterDepth(d:Int)=setColorFilterDepth(d.toFloat())
    fun setColorFilterDepth(d:Float) { depth=d; invalidate() }
    override fun dispatchDraw(c:Canvas) {
        val layer=if(depth>0) c.saveLayer(-width.toFloat(),-height*.6f,width*2f,height*1.74f,Paint().apply { val gain=1-depth*.28f; colorFilter=ColorMatrixColorFilter(ColorMatrix(floatArrayOf(gain,0f,0f,0f,0f,0f,gain,0f,0f,0f,0f,0f,gain,0f,0f,0f,0f,0f,1f,0f))) }) else c.save()
        super.dispatchDraw(c); c.restoreToCount(layer); val dir=direction ?: return; if(progress<=0) return
        val color=when(dir) { "want"->a.accentFill;"read"->0xff8fd3b0.toInt();else->a.fg }; val p=Paint(3); val density=a.resources.displayMetrics.density; val alpha=progress*.9f
        // CSS 90/270/0 degree gradients, including the transparent stop and inset rim.
        val tintColor=when(dir){"want"->0xfff0a742.toInt();"read"->0xff3c8c64.toInt();else->Color.BLACK}
        val tint=(tintColor and 0xffffff) or ((alpha*.45f*255).toInt() shl 24)
        p.shader=when(dir){
            "read"->LinearGradient(0f,height.toFloat(),0f,0f,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,tint),floatArrayOf(0f,.3f,1f),Shader.TileMode.CLAMP)
            "want"->LinearGradient(0f,0f,width.toFloat(),0f,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,tint),floatArrayOf(0f,.25f,1f),Shader.TileMode.CLAMP)
            else->LinearGradient(width.toFloat(),0f,0f,0f,intArrayOf(Color.TRANSPARENT,Color.TRANSPARENT,tint),floatArrayOf(0f,.25f,1f),Shader.TileMode.CLAMP)
        }; c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),2*density,6*density,p);p.shader=null
        p.color=color;p.alpha=(alpha*(if(dir=="nope").7f else .95f)*255).toInt();p.style=Paint.Style.STROKE;p.strokeWidth=3*density;c.drawRoundRect(1.5f*density,1.5f*density,width-1.5f*density,height-1.5f*density,2*density,6*density,p)
        val stampAlpha=((progress-.15f)/.85f).coerceIn(0f,1f); if(stampAlpha<=0) return
        val text=when(dir) { "want"->"ХОЧУ";"read"->"ЧИТАЛ";else->"НЕ ТО" }; p.textSize=22*density; p.typeface=Typeface.create("sans-serif",Typeface.BOLD); val tw=p.measureText(text); val w=tw+24*density; val h=42*density
        val x=when(dir) { "want"->width*.1f;"nope"->width*.92f-w;else->(width-w)/2 }; val y=if(dir=="read") height*.9f-h else height*.09f
        c.save(); c.rotate(if(dir=="want") -14f else if(dir=="nope") 14f else 0f,x+w/2,y+h/2); p.style=Paint.Style.FILL; p.color=0xc7101011.toInt(); p.alpha=(stampAlpha*200).toInt(); c.drawRoundRect(x,y,x+w,y+h,12*density,12*density,p); p.color=color; p.alpha=(stampAlpha*255).toInt(); p.style=Paint.Style.STROKE; c.drawRoundRect(x,y,x+w,y+h,12*density,12*density,p); p.style=Paint.Style.FILL; c.drawText(text,x+12*density,y+h/2-(p.ascent()+p.descent())/2,p); c.restore()
    }
}
/** Same FLIP geometry, grouped stagger and interruption policy as recExpand in the source. */
class RecommendationExpansion(val a:MainActivity,val book:JSONObject,val source:View,val closed:()->Unit) {
 private lateinit var panel:FrameLayout
 private lateinit var content:LinearLayout
 private lateinit var big:CoverView
 private lateinit var backdrop:FrameLayout
 private lateinit var glow:BookGlowView
 private lateinit var back:View
 private val parts=mutableListOf<View>()
 private var clock:android.animation.ValueAnimator?=null
 private var shutting=false;private var finished=false
 private var closePose:FloatArray?=null
 private var closeAlphas=emptyList<Float>()
 private var fromX=0f;private var fromY=0f;private var bgX=0f;private var bgY=0f
 private var coverScale=1f;private var coverScaleY=1f;private var bgScaleX=1f;private var bgScaleY=1f
 private fun reduced()=a.prefs.str("motion")=="reduced"
 private fun sourceCover(v:View):View? {if(v is CoverView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)sourceCover(v.getChildAt(i))?.let{return it};return null}
 val isShowing get()=::panel.isInitialized && panel.parent!=null && !finished
 val contentRoot:View get()=panel
 fun show(){
  if(a.recommendationExpansion?.isShowing==true)return
  a.recommendationExpansion=this
  panel=FrameLayout(a).apply{clipChildren=false;clipToPadding=false;elevation=a.d(40).toFloat();alpha=0f;setBackgroundColor(Color.TRANSPARENT)}
  backdrop=FrameLayout(a).apply{a.controlSurface(this,30f,sheet=true);pivotX=0f;pivotY=0f};panel.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
  content=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;setPadding(a.d(24),a.d(64),a.d(24),a.d(40));clipChildren=false;clipToPadding=false}
  val coverWidth=min(a.d(170),(a.resources.displayMetrics.widthPixels*.44f).toInt());big=CoverView(a,book).apply{softShadow=true;pivotX=0f;pivotY=0f}
  val hero=FrameLayout(a).apply{clipChildren=false;clipToPadding=false};glow=BookGlowView(a,big).apply{alpha=0f};hero.addView(glow,glow.haloLayout(coverWidth,(coverWidth*1.5f).toInt()));hero.addView(big,FrameLayout.LayoutParams(coverWidth,(coverWidth*1.5f).toInt(),Gravity.TOP or Gravity.CENTER_HORIZONTAL));content.addView(hero,LinearLayout.LayoutParams(-1,(coverWidth*1.5f).toInt()));a.space(content,26)
  fun part(v:View){parts.add(v);content.addView(v,LinearLayout.LayoutParams(-1,-2))}
  part(a.text(book.str("title"),27f,bold=true,serif=true).apply{gravity=Gravity.CENTER;cssLeading(1.18f)});a.space(content,5);part(a.text(MainActivity.authors(book),14f,a.muted).apply{gravity=Gravity.CENTER;cssLeading(1.4f)})
  val facts=mutableListOf<Pair<String,String>>();val year=book.optInt("year");if(year!=0)facts.add("Год" to if(year<0)"${-year} до н. э." else "$year");val pages=book.optInt("recPages");if(pages>0)facts.add("Объём" to "$pages стр.");val rating=book.optDouble("recRating",0.0);if(rating>0)facts.add("Оценка" to String.format(java.util.Locale("ru"),"%.1f из 5",rating))
  if(facts.isNotEmpty()){
   a.space(content,24);val row=a.line().apply{setPadding(0,a.d(16),0,a.d(16));background=object:android.graphics.drawable.Drawable(){val p=Paint(3).apply{color=if(a.light)0x17161617 else 0x17ffffff};override fun draw(c:Canvas){c.drawRect(0f,0f,bounds.width().toFloat(),a.d(1).toFloat(),p);c.drawRect(0f,bounds.height()-a.d(1).toFloat(),bounds.width().toFloat(),bounds.height().toFloat(),p)};override fun setAlpha(alpha:Int){};override fun setColorFilter(filter:ColorFilter?){};@Deprecated("Drawable")override fun getOpacity()=PixelFormat.TRANSLUCENT}}
   facts.forEachIndexed{i,(name,value)->if(i>0)row.addView(View(a).apply{setBackgroundColor(if(a.light)0x17161617 else 0x17ffffff)},LinearLayout.LayoutParams(a.d(1),a.d(30)));val cell=a.column().apply{gravity=Gravity.CENTER;setPadding(a.d(6),a.d(2),a.d(6),a.d(2))};cell.addView(a.text(name.uppercase(),11f,a.muted));a.space(cell,5);cell.addView(a.text(value,15f,bold=true));row.addView(cell,LinearLayout.LayoutParams(0,-2,1f))};part(row)
  }
  fun section(title:String,fn:(LinearLayout)->Unit){val sec=a.column().apply{setPadding(0,a.d(30),0,0)};sec.addView(a.text(title.uppercase(),13f,a.muted,true));a.space(sec,12);fn(sec);part(sec)}
  if(book.str("annotation").isNotBlank())section("О книге"){sec->sec.addView(a.text(book.str("annotation"),17f,serif=true).apply{cssLeading(1.65f);alpha=.9f})}
  section("Почему в подборке"){sec->sec.addView(a.text(book.str("reason"),15f));val themes=book.optJSONArray("themes")?.strings().orEmpty();if(themes.isNotEmpty()){a.space(sec,6);sec.addView(a.text("Темы: "+themes.joinToString(", "),14f,a.muted))};if(book.str("series").isNotBlank()){a.space(sec,6);sec.addView(a.text("Цикл «${book.str("series")}»"+if(book.str("seriesNo").isNotBlank()&&book.str("seriesNo")!="null")", книга ${book.str("seriesNo")}" else "",14f,a.muted))}}
  val scroller=ScrollView(a).apply{isFillViewport=false;isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;clipChildren=false;clipToPadding=false};val center=FrameLayout(a).apply{clipChildren=false;clipToPadding=false};center.addView(content,FrameLayout.LayoutParams(if(a.resources.configuration.screenWidthDp>520)a.d(520)else -1,-2,Gravity.TOP or Gravity.CENTER_HORIZONTAL));scroller.addView(center);panel.addView(scroller,FrameLayout.LayoutParams(-1,-1))
  back=a.icon("chevdown","Свернуть описание"){dismiss()};panel.addView(back,FrameLayout.LayoutParams(a.d(44),a.d(44),Gravity.TOP or Gravity.START).apply{leftMargin=a.d(14);topMargin=a.d(12)})
  var down=0f;var pulling=false
  scroller.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->{down=e.y;pulling=scroller.scrollY<=0};MotionEvent.ACTION_MOVE->if(pulling&&scroller.scrollY<=0&&e.y>down){content.translationY=(e.y-down)*.45f;return@setOnTouchListener true};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{val dy=content.translationY/.45f;pulling=false;if(e.actionMasked==MotionEvent.ACTION_UP&&dy>a.d(110))dismiss()else content.animate().translationY(0f).setDuration(if(reduced())0 else 360).setInterpolator(PathInterpolator(.3f,1.3f,.5f,1f)).start()}};false}
  panel.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;panel.isClickable=true;panel.isFocusableInTouchMode=true
  panel.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_BACK&&event.action==KeyEvent.ACTION_UP){dismiss();true}else false}
  panel.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){cancelClock();if(!finished){finished=true;restoreShadows();source.visibility=View.VISIBLE;source.alpha=1f;if(a.recommendationExpansion===this@RecommendationExpansion)a.recommendationExpansion=null;closed();a.flushDeferredRender()}}})
  fun begin(){if(finished||big.width<=0||backdrop.height<=0)return;geometry();source.visibility=View.INVISIBLE;panel.alpha=1f;if(reduced())sampleMotion(1200)else{sampleMotion(0);animateMotion(false)}}
  panel.addOnLayoutChangeListener(object:View.OnLayoutChangeListener{override fun onLayoutChange(v:View,l:Int,t:Int,r:Int,b:Int,ol:Int,ot:Int,or:Int,ob:Int){if(big.width>0&&backdrop.height>0){panel.removeOnLayoutChangeListener(this);glow.prepare{if(!shutting)begin()}}}})
  a.followInsets(panel){i->
   content.setPadding(a.d(24)+i.left,a.d(64)+i.top,a.d(24)+i.right,a.d(40)+i.bottom)
   back.layoutParams=(back.layoutParams as FrameLayout.LayoutParams).apply{leftMargin=a.d(14)+i.left;topMargin=a.d(12)+i.top}
  }
  a.root.addView(panel,FrameLayout.LayoutParams(-1,-1));panel.requestFocus()
 }
 private fun geometry(){val from=IntArray(2);val to=IntArray(2);val origin=IntArray(2);source.getLocationOnScreen(from);big.getLocationOnScreen(to);panel.getLocationOnScreen(origin);bgX=(from[0]-origin[0]).toFloat();bgY=(from[1]-origin[1]).toFloat();bgScaleX=source.width/backdrop.width.toFloat();bgScaleY=source.height/backdrop.height.toFloat();val cover=sourceCover(source)?:source;cover.getLocationOnScreen(from);fromX=from[0]-to[0]+big.translationX;fromY=from[1]-to[1]+big.translationY;coverScale=cover.width/big.width.toFloat();coverScaleY=cover.height/big.height.toFloat()}
 private fun restoreShadows(){(sourceCover(source) as? CoverView)?.shadowFade=1f}
 private fun cancelClock(){clock?.removeAllListeners();clock?.cancel();clock=null}
 private fun animateMotion(closing:Boolean){cancelClock();clock=android.animation.ValueAnimator.ofFloat(0f,1f).apply{duration=if(closing)620 else 150+min(parts.lastIndex,6)*45+560L;interpolator=android.view.animation.LinearInterpolator();addUpdateListener{sampleMotion(currentPlayTime,closing)};if(closing)addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(animation:android.animation.Animator){finish()}});start()}}
 /** Deterministic clock for frame-by-frame comparison; all meaningful text groups share the source stagger. */
 fun sampleMotion(elapsed:Long,closing:Boolean=false){if(!::big.isInitialized)return;fun curve(duration:Long,delay:Long=0,x1:Float=.32f,y1:Float=.72f,x2:Float=0f,y2:Float=1f)=OpeningTimeline.bezier(((elapsed-delay).toFloat()/duration).coerceIn(0f,1f),x1,y1,x2,y2)
  if(closing){val t=curve(620,x1=.25f,y1=.1f,x2=.25f,y2=1f);val pose=closePose?:floatArrayOf(0f,0f,1f,0f,0f,1f,1f,1f,1f,1f)
   fun mix(x:Float,y:Float)=x+(y-x)*t
   big.translationX=mix(pose[0],fromX);big.translationY=mix(pose[1],fromY);big.scaleX=mix(pose[2],coverScale);big.scaleY=mix(pose.getOrElse(10){pose[2]},coverScaleY)
   backdrop.translationX=mix(pose[3],bgX);backdrop.translationY=mix(pose[4],bgY);backdrop.scaleX=mix(pose[5],bgScaleX);backdrop.scaleY=mix(pose[6],bgScaleY)
   fun smooth(x:Float)=x.coerceIn(0f,1f).let{it*it*(3-2*it)}
   backdrop.alpha=pose[7]*(1-smooth((t-.08f)/.92f));glow.alpha=pose[8]*(1-smooth(t/.8f));back.alpha=pose[9]*(1-smooth(t/.65f))
   parts.forEachIndexed{i,v->v.alpha=(closeAlphas.getOrNull(i)?:1f)*(1-smooth(t/.7f))}
   // One physical cover all the way to the destination. Cross-fading two moving
   // silhouettes made both the book and its shadow flicker before the final frame.
   val landed=elapsed>=620L
   // The deck card carries a wider stacked penumbra than the flying cover. Switching them in one
   // frame made that halo flash behind the landed book, so the two shadows now cross-fade over
   // the final stretch (shadow alpha only: no View alpha layer that would clip the penumbra).
   // The card's cover sits exactly under the flying one, so only its shadow becomes visible.
   source.visibility=View.VISIBLE;source.alpha=1f
   (sourceCover(source) as? CoverView)?.shadowFade=smooth((elapsed-260f)/360f)
   big.shadowFade=1-smooth((elapsed-300f)/300f)
   big.alpha=if(landed)0f else 1f
   return
  }
  val t=curve(if(closing)520 else 620);val remaining=if(closing)t else 1-t;big.translationX=fromX*remaining;big.translationY=fromY*remaining;big.scaleX=1+(coverScale-1)*remaining;big.scaleY=1+(coverScaleY-1)*remaining;backdrop.translationX=bgX*remaining;backdrop.translationY=bgY*remaining;backdrop.scaleX=1+(bgScaleX-1)*remaining;backdrop.scaleY=1+(bgScaleY-1)*remaining
  glow.alpha=if(closing)1-curve(300,x1=0f,y1=0f,x2=.58f,y2=1f)else 1f;back.alpha=if(closing)1-(elapsed/140f).coerceIn(0f,1f)else ((elapsed-260)/300f).coerceIn(0f,1f)
  parts.forEachIndexed{i,v->val q=if(closing)1-curve(160,x1=0f,y1=0f,x2=.58f,y2=1f)else curve(560,150+min(i,6)*45L,.22f,1f,.36f,1f);v.alpha=q;v.translationY=if(closing)0f else a.d(22)*(1-q)}
 }
 fun freezeMotion(){cancelClock()}
 fun stopMotion(){if(shutting)finish()else{cancelClock();sampleMotion(1200)}}
 private fun finish(){if(finished)return;finished=true;cancelClock();restoreShadows();source.visibility=View.VISIBLE;source.alpha=1f;(panel.parent as? ViewGroup)?.removeView(panel);if(a.recommendationExpansion===this)a.recommendationExpansion=null;closed();a.flushDeferredRender()}
 fun dismiss(){if(shutting||finished)return;shutting=true;if(!::panel.isInitialized||reduced()||!source.isAttachedToWindow||a.isFinishing){finish();return};cancelClock();content.animate().cancel();closePose=floatArrayOf(big.translationX,big.translationY,big.scaleX,backdrop.translationX,backdrop.translationY,backdrop.scaleX,backdrop.scaleY,backdrop.alpha,glow.alpha,back.alpha,big.scaleY);closeAlphas=parts.map{it.alpha};geometry();sampleMotion(0,true);animateMotion(true)}
}
