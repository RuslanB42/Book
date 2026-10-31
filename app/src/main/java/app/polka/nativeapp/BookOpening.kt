package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.*

class BookOpeningSnapshot(val scene:Bitmap,val cover:Bitmap,val rect:RectF,val binding:Int=0xff29262d.toInt(),val background:Int=0xff151515.toInt(),val hasTarget:Boolean=true,val sourceView:View?=null,val litScene:Bitmap?=null) {
 fun release(){litScene?.takeUnless{it.isRecycled}?.recycle();if(!scene.isRecycled)scene.recycle();if(!cover.isRecycled)cover.recycle()}
}
fun MainActivity.openingSnapshot(id:String,explicit:View?=null,captureScene:Boolean=true):BookOpeningSnapshot? {
 if(prefs.str("motion")=="reduced" || root.width<=0 || root.height<=0)return null
 fun find(v:View):View? {if(v is CoverView && v.bookId==id && v.isShown && v.getGlobalVisibleRect(Rect()))return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
 val source=explicit?:find(root)?:return null;if(source.width<=0 || source.height<=0)return null
 val at=IntArray(2);val origin=IntArray(2);source.getLocationOnScreen(at);root.getLocationOnScreen(origin)
 val x=(at[0]-origin[0]-root.paddingLeft).toFloat();val y=(at[1]-origin[1]-root.paddingTop).toFloat();val rect=RectF(x,y,x+source.width,y+source.height)
 if(rect.bottom<0 || rect.top>root.height)return null
 // Capture once, without the moving cover. Keep the library visible while pagination prepares.
 val sceneScale=minOf(1f,720f/root.width)
 val scene=Bitmap.createBitmap((if(captureScene)root.width*sceneScale else 1f).roundToInt().coerceAtLeast(1),(if(captureScene)root.height*sceneScale else 1f).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
 val was=source.visibility
 // Capture the clean scene separately; detail light is retained only for the initial fade-out.
 fun lights(v:View):List<BookGlowView> = (if(v is BookGlowView)listOf(v)else emptyList())+if(v is ViewGroup)(0 until v.childCount).flatMap{lights(v.getChildAt(it))}else emptyList()
 val hidden=if(captureScene)lights(root).map{it to it.visibility}else emptyList()
 try{hidden.forEach{it.first.visibility=View.INVISIBLE};if(captureScene){source.visibility=View.INVISIBLE;val sceneCanvas=Canvas(scene);sceneCanvas.scale(sceneScale,sceneScale);root.draw(sceneCanvas)}}finally{source.visibility=was;hidden.forEach{it.first.visibility=it.second}}
 // Preserve detail light separately: the clean scene remains glow-free, while
 // the identical lit scene dissolves during the first approach, never disappearing at tap.
 val lit=if(captureScene&&hidden.any{it.second==View.VISIBLE&&it.first.alpha>0f})Bitmap.createBitmap(scene.width,scene.height,Bitmap.Config.ARGB_8888).also{image->try{source.visibility=View.INVISIBLE;val canvas=Canvas(image);canvas.scale(sceneScale,sceneScale);root.draw(canvas)}finally{source.visibility=was}}else null
 val cover=openingCoverTexture(source,root.width,root.height)
 val inside=BookCoverVolume.binding(cover,bg)
 return BookOpeningSnapshot(scene,cover,rect,inside,bg,true,source,lit)
}

/** A frozen source scene covers async reader preparation, so no background-only frame can escape. */
internal class BookOpeningHold(a:MainActivity,private val snapshot:BookOpeningSnapshot):View(a){
 private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 private val dark=!a.light
 init{isClickable=true;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;contentDescription="Открываю книгу"}
 override fun onDraw(c:Canvas){if(snapshot.scene.isRecycled)return;c.drawBitmap(snapshot.scene,null,RectF(0f,0f,width.toFloat(),height.toFloat()),p);snapshot.litScene?.takeUnless{it.isRecycled}?.let{c.drawBitmap(it,null,RectF(0f,0f,width.toFloat(),height.toFloat()),p)};BookCoverVolume.shadow(c,snapshot.rect,dark,true);c.drawBitmap(snapshot.cover,null,snapshot.rect,p)}
}

/** Hosts the actual, already laid-out native reader. No reader screenshots or last-frame substitution. */
class BookOpeningStage(private val a:MainActivity,private val snapshot:BookOpeningSnapshot,private val live:View,private val restoreLive:Boolean=true,private val ownedBitmap:Bitmap?=null,internal val returning:Boolean=false,private val motionReader:NativeReader?=null,private val completed:(()->Unit)?=null):ViewGroup(a) {
 constructor(a:MainActivity,snapshot:BookOpeningSnapshot,readerBitmap:Bitmap):this(a,snapshot,ImageView(a).apply{setImageBitmap(readerBitmap);scaleType=ImageView.ScaleType.FIT_XY},false,readerBitmap)
 private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
 private val textureMatrix=Matrix();private val src=FloatArray(8);private val dst=FloatArray(8)
 private var boardShadow:Bitmap?=null
 private var animator:ValueAnimator?=null;private var released=false;private var finishing=false
 private val reader=motionReader?:a.reader?.takeIf{it.view===live}
 private val paper=(live.background as? android.graphics.drawable.ColorDrawable)?.color?:a.bg
 private val area=reader?.openingArea()?:RectF(0f,0f,live.width.toFloat(),live.height.toFloat())
 var progress=if(returning)1f else 0f;private set
 init{isClickable=true;elevation=a.d(80).toFloat();if(returning)snapshot.sourceView?.visibility=View.INVISIBLE;clipChildren=false;clipToPadding=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;contentDescription=if(returning)"Закрытие книги" else "Открытие книги";(live.parent as? ViewGroup)?.removeView(live);addView(live);if(!returning)reader?.openingState(true);setWillNotDraw(false)}
 override fun onMeasure(ws:Int,hs:Int){val w=MeasureSpec.getSize(ws);val h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);if(boardShadow==null && w>0 && h>0){val dp=resources.displayMetrics.density;val bw=minOf(264f,w/dp*.4f,h/dp*.285f);val bh=bw*1.5f;boardShadow=Bitmap.createBitmap(ceil(bw*2).toInt(),ceil(bh*1.667f).toInt(),Bitmap.Config.ARGB_8888).also{val canvas=Canvas(it);BookCoverVolume.shadow(canvas,RectF(bw*.5f,bh*.267f,bw*1.5f,bh*1.267f),!a.light,true)}};live.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))}
 override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){live.layout(0,0,width,height);if(changed && progress>0f && (r-l!=measuredWidth || b-t!=measuredHeight))post{finish()}}
 override fun onSizeChanged(w:Int,h:Int,oldw:Int,oldh:Int){super.onSizeChanged(w,h,oldw,oldh);if(oldw>0&&oldh>0&&(w!=oldw||h!=oldh))post{finish()}}
 override fun onInterceptTouchEvent(e:MotionEvent)=true
 override fun onTouchEvent(e:MotionEvent)=true
 fun frame(value:Float){progress=value.coerceIn(0f,1f);reader?.openingChrome(OpeningTimeline.chrome(progress));alpha=if(returning&&!snapshot.hasTarget)(progress/.16f).coerceIn(0f,1f) else 1f;invalidate()}
 internal fun freezeMotion(){animator?.removeAllListeners();animator?.cancel();animator=null}
 fun start(){animator=ValueAnimator.ofFloat(if(returning)1f else 0f,if(returning)0f else 1f).apply{duration=if(returning)OpeningTimeline.CLOSE_DURATION else OpeningTimeline.DURATION;interpolator=android.view.animation.LinearInterpolator();addUpdateListener{frame(it.animatedValue as Float)};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(animation:Animator){finish()}});start()}}
 fun finish(){if(finishing||released)return;finishing=true;animator?.removeAllListeners();animator?.cancel();animator=null;removeView(live);if(returning)snapshot.sourceView?.visibility=View.VISIBLE;val host=parent as? ViewGroup;host?.removeView(this);reader?.openingChrome(1f);if(!returning)reader?.openingState(false);if(restoreLive && a.reader?.view===live && !a.isDestroyed && live.parent==null)a.root.addView(live,0,FrameLayout.LayoutParams(-1,-1));release();completed?.invoke()}
 private fun release(){if(released)return;released=true;boardShadow?.takeUnless{it.isRecycled}?.recycle();boardShadow=null;snapshot.release();ownedBitmap?.takeUnless{it.isRecycled}?.recycle()}
 override fun onDetachedFromWindow(){if(returning)snapshot.sourceView?.visibility=View.VISIBLE;animator?.removeAllListeners();animator?.cancel();animator=null;if(!finishing){reader?.openingChrome(1f);if(!returning)reader?.openingState(false);completed?.invoke()};release();super.onDetachedFromWindow()}
 private data class V(val x:Float,val y:Float,val z:Float)
 private data class Face(val points:List<V>,val kind:Int,val color:Int,val opacity:Float=1f)
 override fun dispatchDraw(c:Canvas){
  if(released || width<=0 || height<=0)return
  val W=width.toFloat();val H=height.toFloat();val dp=resources.displayMetrics.density;val w=minOf(264*dp,W*.4f,H*.285f);val h=w*1.5f
  val f=OpeningTimeline.sample(progress,W,H,w,h,OpeningTimeline.Pose(snapshot.rect.left,snapshot.rect.top,snapshot.rect.width()/w,snapshot.rect.height()/h,0f,0f),dp)
  val dissolve=((progress-.10f)/.70f).coerceIn(0f,1f).let{it*it*(3-2*it)}
  paint.shader=null
  if(returning){
   // The full-resolution destination is already alive beneath this overlay. Never cross-fade
   // a downscaled library screenshot to crisp text at the last frame.
   paint.color=paper;paint.alpha=(dissolve*255).toInt();c.drawRect(0f,0f,W,H,paint)
  }else{
   c.drawColor(paper);paint.color=Color.WHITE;paint.alpha=((1-dissolve)*255).toInt()
   c.drawBitmap(snapshot.scene,null,RectF(0f,0f,W,H),paint)
   snapshot.litScene?.takeUnless{it.isRecycled}?.let{paint.alpha=(255*(1-dissolve)*OpeningTimeline.light(progress)).toInt();c.drawBitmap(it,null,RectF(0f,0f,W,H),paint)}
  };paint.alpha=255
  fun rotate(v:V,x:Float,y:Float):V {val ax=Math.toRadians(x.toDouble());val ay=Math.toRadians(y.toDouble());val xx=v.x*cos(ay)+v.z*sin(ay);val zz=-v.x*sin(ay)+v.z*cos(ay);return V(xx.toFloat(),(v.y*cos(ax)-zz*sin(ax)).toFloat(),(v.y*sin(ax)+zz*cos(ax)).toFloat())}
  fun world(v:V,hinged:Boolean=false):V {val q=if(hinged)rotate(v,0f,f.swing).let{V(it.x,it.y,it.z+2*dp)}else v;val p=f.pose;val rotated=rotate(V((q.x-w/2)*p.sx,(q.y-h/2)*p.sy,q.z),p.rx,p.ry);return V(p.x+w*p.sx/2+rotated.x,p.y+h*p.sy/2+rotated.y,rotated.z)}
  fun rect(z:Float,hinged:Boolean=false,back:Boolean=false):List<V> {val points=if(back)listOf(V(w,0f,z),V(0f,0f,z),V(0f,h,z),V(w,h,z))else listOf(V(0f,0f,z),V(w,0f,z),V(w,h,z),V(0f,h,z));return points.map{world(it,hinged)}}
  val faces=ArrayList<Face>(12)
  faces.add(Face(rect(-15*dp),6,snapshot.binding,f.volume))
  // Actual recessed page-block sides, never an offset decorative paper rectangle.
  val fore=w-dp;val head=dp;val tail=h-dp;val left=2*dp;val z1=-dp;val z2=-15*dp
  fun side(points:List<V>,color:Int,hinged:Boolean=false,kind:Int=0){faces.add(Face(points.map{world(it,hinged)},kind,color,f.volume))}
  side(listOf(V(fore,head,z1),V(fore,head,z2),V(fore,tail,z2),V(fore,tail,z1)),0xffd3cebd.toInt(),kind=4)
  side(listOf(V(left,head,z2),V(fore,head,z2),V(fore,head,z1),V(left,head,z1)),0xffe2ddcd.toInt(),kind=5)
  side(listOf(V(left,tail,z1),V(fore,tail,z1),V(fore,tail,z2),V(left,tail,z2)),0xffb4af9f.toInt(),kind=5)
  side(listOf(V(0f,dp,2*dp),V(0f,dp,-15*dp),V(0f,h-dp,-15*dp),V(0f,h-dp,2*dp)),snapshot.binding)
  faces.add(Face(rect(0f),1,paper))
  faces.add(Face(rect(2.5f*dp,true),2,snapshot.binding,f.volume))
  faces.add(Face(rect(-2.5f*dp,true,true),3,snapshot.binding,f.volume))
  val thick=2.5f*dp
  side(listOf(V(w,0f,thick),V(w,0f,-thick),V(w,h,-thick),V(w,h,thick)),snapshot.binding,true)
  side(listOf(V(0f,0f,-thick),V(w,0f,-thick),V(w,0f,thick),V(0f,0f,thick)),snapshot.binding,true)
  side(listOf(V(0f,h,thick),V(w,h,thick),V(w,h,-thick),V(0f,h,-thick)),snapshot.binding,true)
  val perspective=1700*dp;val ox=W*.5f;val oy=H*.48f
  for(face in faces.sortedBy{it.points.sumOf{p->p.z.toDouble()}}){
   if(face.opacity<=0)continue
   for(i in 0..3){val v=face.points[i];val scale=perspective/(perspective-v.z);dst[i*2]=ox+(v.x-ox)*scale;dst[i*2+1]=oy+(v.y-oy)*scale}
   val winding=(dst[2]-dst[0])*(dst[5]-dst[1])-(dst[3]-dst[1])*(dst[4]-dst[0]);if(winding<=.001f)continue
   src[0]=0f;src[1]=0f;src[2]=w;src[3]=0f;src[4]=w;src[5]=h;src[6]=0f;src[7]=h
   textureMatrix.reset();if(!textureMatrix.setPolyToPoly(src,0,dst,0,4))continue
   c.save();c.concat(textureMatrix);if(face.kind==6)c.clipRect(-w*.5f,-h*.267f,w*1.5f,h*1.4f)else c.clipRect(0f,0f,w,h);paint.shader=null;paint.alpha=(face.opacity*255).toInt();paint.color=face.color;paint.alpha=(face.opacity*255).toInt()
   when(face.kind){
    1->{paint.alpha=255;c.drawRect(0f,0f,w,h,paint);val initial=w*.86f/area.width().coerceAtLeast(1f);val tx=w*.07f-area.left*initial;val ty=h*.08f-maxOf(0f,area.top)*initial;val total=initial+(1-initial)*f.zoom
      c.save();c.translate(tx*(1-f.zoom)/f.pose.sx,ty*(1-f.zoom)/f.pose.sy);c.scale(total/f.pose.sx,total/f.pose.sy);drawChild(c,live,drawingTime);c.restore()
      paint.shader=LinearGradient(0f,0f,w*.11f,0f,intArrayOf(0x99000000.toInt(),Color.TRANSPARENT),null,Shader.TileMode.CLAMP);paint.alpha=(f.gutter*255).toInt();c.drawRect(0f,0f,w*.11f,h,paint);paint.shader=null}
    2->{c.drawBitmap(snapshot.cover,null,RectF(0f,0f,w,h),paint);paint.color=Color.BLACK;paint.alpha=(f.shade*f.volume*255).toInt();c.drawRect(0f,0f,w,h,paint)}
    3->{c.drawRect(0f,0f,w,h,paint);paint.shader=LinearGradient(0f,0f,w,0f,intArrayOf(0x33000000,Color.TRANSPARENT,0x05ffffff,0x11000000),floatArrayOf(0f,.05f,.5f,1f),Shader.TileMode.CLAMP);paint.alpha=(f.volume*255).toInt();c.drawRect(0f,0f,w,h,paint);paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=.6f*dp;paint.color=0x19ffffff;c.drawRect(2*dp,2*dp,w-2*dp,h-2*dp,paint);paint.style=Paint.Style.FILL;paint.shader=LinearGradient(0f,0f,w,h*.27f,intArrayOf(0x18ffffff,Color.TRANSPARENT,0x33000000),floatArrayOf(0f,.4f,1f),Shader.TileMode.CLAMP);paint.alpha=(f.innerLight*255).toInt();c.drawRect(0f,0f,w,h,paint);paint.shader=null}
    6->{boardShadow?.let{c.drawBitmap(it,null,RectF(-w*.5f,-h*.267f,w*1.5f,h*1.4f),paint)}}
    4,5->{paint.shader=LinearGradient(0f,0f,if(face.kind==4)w/14*1.8f else 0f,if(face.kind==5)h/14*1.9f else 0f,intArrayOf(0xffc4bbaa.toInt(),0xffe6dfd0.toInt(),0xffc4bbaa.toInt(),0xffeee7d9.toInt()),floatArrayOf(0f,.36f,.67f,1f),Shader.TileMode.REPEAT);c.drawRect(0f,0f,w,h,paint);paint.shader=null}
    else->c.drawRect(0f,0f,w,h,paint)
   };c.restore()
  };paint.shader=null;paint.alpha=255
 }
}
fun MainActivity.revealNativeBook(snapshot:BookOpeningSnapshot?,readerView:View){if(snapshot==null)return;if(isFinishing||isDestroyed||prefs.str("motion")=="reduced"||readerView.width<=0||readerView.height<=0||reader?.view!==readerView){snapshot.release();return};val stage=BookOpeningStage(this,snapshot,readerView);root.addView(stage,FrameLayout.LayoutParams(-1,-1));stage.measure(View.MeasureSpec.makeMeasureSpec(readerView.width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(readerView.height,View.MeasureSpec.EXACTLY));stage.layout(root.paddingLeft,root.paddingTop,root.paddingLeft+readerView.width,root.paddingTop+readerView.height);stage.start()}
fun ViewGroup.childrenOpening():BookOpeningStage?=(0 until childCount).map{getChildAt(it)}.filterIsInstance<BookOpeningStage>().firstOrNull()

/** Build the destination once under the retained live page, then run the exact geometry backwards. */
internal fun MainActivity.returnNativeBook(r:NativeReader) {
 val live=r.view
 val width=live.width;val height=live.height
 (live.parent as? ViewGroup)?.removeView(live)
 bookClosing=true
 render(true,false,true)
 val hold=object:FrameLayout(this){override fun onInterceptTouchEvent(e:MotionEvent)=true;override fun onTouchEvent(e:MotionEvent)=true}.apply {
  setBackgroundColor((live.background as? android.graphics.drawable.ColorDrawable)?.color?:bg)
  isClickable=true;elevation=d(60).toFloat();addView(live,FrameLayout.LayoutParams(-1,-1))
  contentDescription="Закрываю книгу"
 }
 root.addView(hold,FrameLayout.LayoutParams(-1,-1))
 hold.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));hold.layout(0,0,width,height)
 var handedOff=false;var frames=0;var signature="";var stable=0
 val observer=root.viewTreeObserver
 val listener=object:ViewTreeObserver.OnPreDrawListener {
  override fun onPreDraw():Boolean {
   if(handedOff)return true
   if(isFinishing||isDestroyed){handedOff=true;if(observer.isAlive)observer.removeOnPreDrawListener(this);root.removeView(hold);finishBookReturn();return true}
   val next="${root.width}:${root.height}:${page.width}:${page.height}:${page.childCount}"
   if(next!=signature){signature=next;stable=0}else stable++
   frames++
   if(stable<2 || (waitingForReturnDeck()&&frames<30)){hold.postInvalidateOnAnimation();return true}
   hold.visibility=View.INVISIBLE
   val snapshot=try {openingSnapshot(r.book.str("id"),captureScene=false)?:returnFallbackSnapshot(r.book,width,height)} finally {hold.visibility=View.VISIBLE}
   handedOff=true;if(observer.isAlive)observer.removeOnPreDrawListener(this)
   // No event-loop gap between the last live-reader frame and reverse frame 1.
   val stage=BookOpeningStage(this@returnNativeBook,snapshot,live,false,returning=true,motionReader=r,completed={finishBookReturn()})
   root.removeView(hold);root.addView(stage,FrameLayout.LayoutParams(-1,-1))
   stage.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));stage.layout(0,0,width,height);stage.frame(1f);stage.start()
   return true
  }
 }
 hold.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener {
  override fun onViewAttachedToWindow(v:View){}
  override fun onViewDetachedFromWindow(v:View){if(!handedOff){handedOff=true;if(observer.isAlive)observer.removeOnPreDrawListener(listener);finishBookReturn()}}
 })
 observer.addOnPreDrawListener(listener);hold.postInvalidateOnAnimation()
}

/** If a filtered/virtualized library has no visible matching cover, dissolve only the final return. */
private fun MainActivity.returnFallbackSnapshot(book:org.json.JSONObject,width:Int,height:Int):BookOpeningSnapshot {
 val scene=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888)
 val w=minOf(d(144),width/2);val h=w*3/2
 val source=CoverView(this,book);source.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));source.layout(0,0,w,h)
 val cover=openingCoverTexture(source,width,height)
 val x=(width-w)*.5f;val y=(height-h)*.46f
 return BookOpeningSnapshot(scene,cover,RectF(x,y,x+w,y+h),BookCoverVolume.binding(cover,bg),bg,false)
}

/** Rasterize once at the largest projected cover size, not at a tiny carousel thumbnail size. */
private fun openingCoverTexture(source:View,windowWidth:Int,windowHeight:Int):Bitmap {
 val width=maxOf(source.width,minOf(windowWidth*.82f,windowHeight*.48f,1024f).roundToInt())
 val height=(source.height.toFloat()*width/source.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
 return Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also {
  val canvas=Canvas(it);canvas.scale(width.toFloat()/source.width,height.toFloat()/source.height);source.draw(canvas)
 }
}
