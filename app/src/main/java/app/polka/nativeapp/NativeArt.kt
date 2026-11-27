package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONObject

/** Same cover renderer is used in the library, shelves, recommendations and continue card. */
@android.annotation.SuppressLint("WrongConstant")
class CoverView(context: Context, private val book: JSONObject, private val image: Bitmap? = null) : View(context) {
    val bookId get()=book.str("id")
    /** Large views (book card, cover editor, recommendation): swap to the original-resolution cover once loaded. */
    var hiRes=false
    // the full picture belongs to one cover: when the book's cover changes, the old one is dropped and the new one asked for
    private var full:Bitmap?=null;private var fullKey="";private var fullAsked:String?=null
    private fun coverKey()=book.optJSONObject("cover")?.toString().orEmpty()
    private fun askFull(){val expected=coverKey();if(!hiRes||fullAsked==expected)return;fullAsked=expected
        (context as? MainActivity)?.requestFullCover(book){b->if(b!=null&&expected==coverKey()){full=b;fullKey=expected;invalidate();artworkChanged?.invoke()}}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();askFull()}
    /** 0..1 multiplier for the cast shadow only (cover pixels untouched). Lets two covers hand a shadow over without a pop or a clipped alpha layer. */
    var shadowFade=1f
        set(v){val n=v.coerceIn(0f,1f);if(n!=field){field=n;invalidate()}}
    private var retainedArtwork:Bitmap?=image
    private var retainedKey=book.optJSONObject("cover")?.toString().orEmpty()
    init { elevation=0f; outlineProvider=object:android.view.ViewOutlineProvider() { override fun getOutline(v:View,o:android.graphics.Outline) { o.setRoundRect(0,0,v.width,v.height,BookCoverVolume.radius(v.width.toFloat())) } } }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) { if(layoutParams?.height==android.view.ViewGroup.LayoutParams.WRAP_CONTENT) { val w=MeasureSpec.getSize(widthMeasureSpec); setMeasuredDimension(w,(w*1.5f).toInt()) } else super.onMeasure(widthMeasureSpec,heightMeasureSpec) }
    override fun performClick():Boolean { super.performClick(); return true }
    private val palette=listOf("#2f3e46" to "#ebe5d6","#6b2737" to "#f3e6d6","#1f3a5f" to "#e8edf3","#3d4a2f" to "#ece6d2","#7a4b1e" to "#f6ead7","#2d2a32" to "#e6dccb","#8c3b2b" to "#f4e9dc","#e3d8c3" to "#2a2622","#c9d4cf" to "#1d2a26","#4a3b5c" to "#efe7f5","#0f4c4a" to "#e3f0ec")
    fun colors(b:JSONObject):Pair<Int,Int> { var hash=2166136261L.toInt(); (b.str("title")+MainActivity.authors(b)).forEach { hash=(hash xor it.code)*16777619 }; val pair=palette[((hash.toLong() and 0xffffffffL)%palette.size).toInt()]; return Color.parseColor(pair.first) to Color.parseColor(pair.second) }
    private var requestedId:String?=null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activity=context as? MainActivity
        val bitmap=currentArtwork()
        if(bitmap==null && activity!=null && book.optJSONObject("cover")!=null && requestedId!=book.optJSONObject("cover")?.str("__polkaBlob")) { requestedId=book.optJSONObject("cover")?.str("__polkaBlob"); val expected=book.optJSONObject("cover")?.toString().orEmpty();activity.requestCover(book) { loaded -> if(loaded==null){missing=true;invalidate()} else if(expected==book.optJSONObject("cover")?.toString().orEmpty()) {retainedArtwork=loaded;retainedKey=expected;invalidate();artworkChanged?.invoke()} } }
        val bounds=RectF(0f,0f,width.toFloat(),height.toFloat())
        // a cover lies on the page: the app's shade at the cover's height (see [Lift]), dithered as it lies on the page
        if(activity!=null)FloatShade.draw(canvas,activity,bounds,if(book.optBoolean("comic"))width*.03f else BookCoverVolume.radius(width.toFloat()),activity.light,(255*shadowFade).toInt(),true,Lift.COVER)
        // A book with a picture whose bitmap is still decoding: a quiet blank cover, never the typeset placeholder
        // (that flashed a different cover for a moment); the picture then fades in.
        // the check is there from the first frame, over the blank cover too, not after the picture has come
        // a new picture still on its way: the old cover stays until it comes, and then they swap
        if(bitmap==null&&book.optJSONObject("cover")!=null&&!missing){
            if(shownKey!=null&&shownKey!=coverKey()&&!waiting){drawCover(canvas,bounds,book,shownArt);mark(canvas,activity);return}
            waiting=true;blank(canvas,bounds);mark(canvas,activity);return}
        // the cover changed under a cover already shown (approved «Смена обложки Б»): the old one blurs and sinks away,
        // the new one rises out of a blur
        val key=coverKey()
        if(shownKey!=null&&key!=shownKey&&!waiting&&isShown&&activity?.prefs?.str("motion")!="reduced"){swapFrom=shownArt;swapAt=android.os.SystemClock.uptimeMillis();fadeFrom=0L}
        shownKey=key;shownArt=bitmap
        if(swapAt>0L){val t=(android.os.SystemClock.uptimeMillis()-swapAt)/1000f
            if(t<SWAP){swap(canvas,bounds,t,bitmap);mark(canvas,activity);postInvalidateOnAnimation();return}
            swapAt=0L;swapFrom=null}
        if(waiting){waiting=false;fadeFrom=android.os.SystemClock.uptimeMillis()}
        val f=if(fadeFrom>0L)((android.os.SystemClock.uptimeMillis()-fadeFrom)/180f).coerceIn(0f,1f) else 1f
        if(f<1f){blank(canvas,bounds);val l=canvas.saveLayerAlpha(bounds,(f*255).toInt());drawCover(canvas,bounds,book,bitmap);canvas.restoreToCount(l);mark(canvas,activity);postInvalidateOnAnimation();return}
        fadeFrom=0L
        drawCover(canvas,bounds,book,bitmap)
        mark(canvas,activity)
    }
    /** A read book's cover in a grid carries a round check in its lower right corner (approved «Б: статус открыт»). */
    internal var readMark=false
    /** How much of the check shows (a cover flying from a shelf's fan into its place gets it on the way). */
    internal var markAlpha=1f
        set(v){val n=v.coerceIn(0f,1f);if(n!=field){field=n;if(readMark)invalidate()}}
    private val markPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun mark(c:Canvas,a:MainActivity?){if(!readMark||a==null||markAlpha<=0f)return
        val w=width.toFloat();val s=w*.176f;val m=w*.059f;val cx=w-m-s/2f;val cy=height-m-s/2f;val al=(markAlpha*255).toInt()
        markPaint.color=a.fg;markPaint.alpha=al;c.drawCircle(cx,cy,s/2f,markPaint)
        val k=s*.58f/24f;val ox=cx-12f*k;val oy=cy-12f*k
        markPaint.color=a.bg;markPaint.alpha=al;markPaint.style=Paint.Style.STROKE;markPaint.strokeWidth=2.6f*k;markPaint.strokeCap=Paint.Cap.ROUND;markPaint.strokeJoin=Paint.Join.ROUND
        markPath.rewind();markPath.moveTo(ox+5f*k,oy+12.5f*k);markPath.lineTo(ox+9.5f*k,oy+17f*k);markPath.lineTo(ox+19f*k,oy+7.5f*k);c.drawPath(markPath,markPaint);markPaint.style=Paint.Style.FILL}
    private val markPath=Path()
    internal var artworkChanged:(()->Unit)?=null
    private var missing=false;private var fadeFrom=0L
    // the cover last drawn and, while a new one comes in, the one going away
    private var shownKey:String?=null;private var shownArt:Bitmap?=null;private var swapFrom:Bitmap?=null;private var swapAt=0L
    private var swapLayers:Any?=null
    /** The old cover over 0..0.28 s (fading, to 94 %, into a blur), the new one over 0.12..0.52 s (the same, backwards). */
    private fun swap(c:Canvas,bounds:RectF,t:Float,art:Bitmap?){
        val blur=width*.26f
        val u=SWAP_OUT.getInterpolation((t/.28f).coerceIn(0f,1f))
        val v=SWAP_IN.getInterpolation(((t-.12f)/.4f).coerceIn(0f,1f))
        if(u<1f)layer(c,0,1f-u,1f-.06f*u,blur*u){drawCover(it,bounds,book,swapFrom)}
        if(v>0f)layer(c,1,v,.94f+.06f*v,blur*(1f-v)){drawCover(it,bounds,book,art)}
    }
    private fun layer(c:Canvas,i:Int,alpha:Float,scale:Float,blur:Float,draw:(Canvas)->Unit){
        if(android.os.Build.VERSION.SDK_INT>=31&&c.isHardwareAccelerated){
            val layers=(swapLayers as? BlurLayers)?:BlurLayers().also{swapLayers=it}
            layers.draw(c,i,width,height,alpha,scale,blur,draw);return}
        // without a blur (Android 11 and older): the same fade and scale
        val l=c.saveLayerAlpha(0f,0f,width.toFloat(),height.toFloat(),(alpha*255).toInt());c.scale(scale,scale,width/2f,height/2f);draw(c);c.restoreToCount(l)
    }
    /** True while the cover's picture is still on its way (the view shows a blank cover). */
    internal var waiting=false;private set
    private val blankPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun blank(c:Canvas,b:RectF){val dark=(context as? MainActivity)?.light==false;blankPaint.color=if(dark)0xff2a2a2d.toInt() else 0xffe6e4df.toInt();val path=Path();BookCoverVolume.outline(path,b);c.drawPath(path,blankPaint)}
    private fun currentArtwork():Bitmap? {
        val ref=book.optJSONObject("cover")
        full?.let{f->if(!f.isRecycled&&fullKey==ref?.toString().orEmpty())return f}
        if(hiRes&&isAttachedToWindow)askFull()
        val cached=if(ref!=null)(context as? MainActivity)?.cover(book) else null
        if(cached!=null){retainedArtwork=cached;retainedKey=ref?.toString().orEmpty()}
        return cached?:retainedArtwork?.takeIf{retainedKey==ref?.toString().orEmpty()}
    }
    internal fun prepareArtwork(done:()->Unit){
        val a=context as? MainActivity;val id=book.optJSONObject("cover")?.str("__polkaBlob").orEmpty()
        if(currentArtwork()!=null||id.isBlank()||a==null){done();return}
        val expected=book.optJSONObject("cover")?.toString().orEmpty()
        a.requestCover(book){loaded->if(expected==book.optJSONObject("cover")?.toString().orEmpty()){retainedArtwork=loaded;retainedKey=expected};done()}
    }
    internal fun glowKey():String {val art=currentArtwork();return book.str("title")+MainActivity.authors(book)+book.optJSONObject("cover")?.toString().orEmpty()+System.identityHashCode(art)+":"+(art?.generationId?:0)}
    internal fun glowArtwork():Bitmap = Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).also{drawCover(Canvas(it),RectF(0f,0f,96f,144f),book,currentArtwork())}
    fun drawCover(canvas: Canvas, bounds: RectF, b: JSONObject, bitmap: Bitmap? = null, shelf:Boolean=false) {
        if(b.optBoolean("comic")){ComicCover.draw(canvas,bounds,bitmap,colors(b).first);return}
        val r=BookCoverVolume.boards(canvas,bounds,BookCoverVolume.binding(bitmap,colors(b).first))
        canvas.save(); val path = Path().apply { BookCoverVolume.outline(this,r) }; canvas.clipPath(path)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (bitmap != null) {
            val scale = maxOf(r.width() / bitmap.width, r.height() / bitmap.height); val w = bitmap.width * scale; val h = bitmap.height * scale
            paint.colorFilter=BookCoverVolume.matte; canvas.drawBitmap(bitmap, null, RectF(r.centerX() - w/2, r.centerY() - h/2, r.centerX() + w/2, r.centerY() + h/2), paint); paint.colorFilter=null
        } else {
            val colors=colors(b); paint.color=colors.first; canvas.drawRect(r, paint)
            val prepared=CoverTypeset.get(context,b,!shelf)
            canvas.save();canvas.translate(r.left,r.top);canvas.scale(r.width()/400f,r.width()/400f)
            canvas.save();canvas.translate(prepared.left,prepared.top);prepared.title.draw(canvas);canvas.restore()
            paint.color=(colors.second and 0xffffff) or 0x80000000.toInt();paint.strokeWidth=400f/r.width();val lineY=(prepared.top+prepared.title.height+24f).coerceAtMost(450f)
            canvas.drawLine(prepared.left,lineY,prepared.left+88f,lineY,paint)
            canvas.save();canvas.translate(prepared.left,600f-(if(shelf)40f else 48f)-prepared.author.height);prepared.author.draw(canvas);canvas.restore();canvas.restore()
        }
        paint.color=Color.WHITE;paint.alpha=255
        BookCoverVolume.material(canvas,r)
        canvas.restore()
    }
}
open class IconView(context: Context, private val kind: String, var ink: Int) : View(context) {
    /** Colour behind the icon; when set and the view is selected, the filled variant is drawn with details cut in this colour. */
    var cut:Int?=null
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.save(); val size=(if(kind=="plus") 26f else if(minOf(width,height)<=context.resources.displayMetrics.density*30f) 16f else if(minOf(width,height)<=context.resources.displayMetrics.density*44.5f) 22f else 24f)*context.resources.displayMetrics.density; c.translate((width-size)/2,(height-size)/2); c.scale(size/24,size/24)
        IosIcons.draw(c,kind,ink,isSelected&&cut!=null&&IosIcons.hasFilled(kind),cut?:0)
        c.restore()
    }
}

class ThinProgress(context:Context,val fraction:Double,val foreground:Int,val track:Int):View(context) {
    override fun onDraw(c:Canvas) { val p=Paint(Paint.ANTI_ALIAS_FLAG); val radius=height/2f; p.color=track; c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,p); if(fraction<.01) return; p.color=foreground; c.drawRoundRect(0f,0f,(width*fraction).toFloat(),height.toFloat(),radius,radius,p) }
}

private const val SWAP=.52f
private val SWAP_OUT=android.view.animation.PathInterpolator(.3f,0f,1f,1f)
private val SWAP_IN=android.view.animation.PathInterpolator(0f,0f,.2f,1f)
/** Two drawings, each with its own fade, scale and blur, for a cover changing into another. */
@androidx.annotation.RequiresApi(31)
private class BlurLayers{
    private val nodes=arrayOf(RenderNode("coverOut"),RenderNode("coverIn"))
    fun draw(c:Canvas,i:Int,w:Int,h:Int,alpha:Float,scale:Float,blur:Float,content:(Canvas)->Unit){
        val n=nodes[i];n.setPosition(0,0,w,h);n.setClipToBounds(false);n.setAlpha(alpha);n.setPivotX(w/2f);n.setPivotY(h/2f);n.setScaleX(scale);n.setScaleY(scale)
        n.setRenderEffect(if(blur>.5f)RenderEffect.createBlurEffect(blur,blur,Shader.TileMode.DECAL) else null)
        val rc=n.beginRecording(w,h);try{content(rc)}finally{n.endRecording()};c.drawRenderNode(n)
    }
}
