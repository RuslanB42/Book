package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.view.View
import org.json.JSONObject

/** Same cover renderer is used in the library, shelves, recommendations and continue card. */
@android.annotation.SuppressLint("WrongConstant")
class CoverView(context: Context, private val book: JSONObject, private val image: Bitmap? = null) : View(context) {
    val bookId get()=book.str("id")
    var softShadow=true
    var stackShadow=false
    /** Large views (book card, cover editor, recommendation): swap to the original-resolution cover once loaded. */
    var hiRes=false
    private var full:Bitmap?=null
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(hiRes&&full==null){val expected=book.optJSONObject("cover")?.toString().orEmpty();(context as? MainActivity)?.requestFullCover(book){b->if(b!=null&&expected==book.optJSONObject("cover")?.toString().orEmpty()){full=b;invalidate();artworkChanged?.invoke()}}}}
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
        if(book.optBoolean("comic"))ComicCover.shadow(canvas,bounds,activity?.light==false) else BookCoverVolume.shadow(canvas,bounds,activity?.light==false,softShadow,stackShadow,shadowFade)
        // A book with a picture whose bitmap is still decoding: a quiet blank cover, never the typeset placeholder
        // (that flashed a different cover for a moment); the picture then fades in.
        if(bitmap==null&&book.optJSONObject("cover")!=null&&!missing){waiting=true;blank(canvas,bounds);return}
        if(waiting){waiting=false;fadeFrom=android.os.SystemClock.uptimeMillis()}
        val f=if(fadeFrom>0L)((android.os.SystemClock.uptimeMillis()-fadeFrom)/180f).coerceIn(0f,1f) else 1f
        if(f<1f){blank(canvas,bounds);val l=canvas.saveLayerAlpha(bounds,(f*255).toInt());drawCover(canvas,bounds,book,bitmap);canvas.restoreToCount(l);postInvalidateOnAnimation();return}
        fadeFrom=0L
        drawCover(canvas,bounds,book,bitmap)
    }
    internal var artworkChanged:(()->Unit)?=null
    private var missing=false;private var fadeFrom=0L
    /** True while the cover's picture is still on its way (the view shows a blank cover). */
    internal var waiting=false;private set
    private val blankPaint=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun blank(c:Canvas,b:RectF){val dark=(context as? MainActivity)?.light==false;blankPaint.color=if(dark)0xff2a2a2d.toInt() else 0xffe6e4df.toInt();val path=Path();BookCoverVolume.outline(path,b);c.drawPath(path,blankPaint)}
    private fun currentArtwork():Bitmap? {
        val ref=book.optJSONObject("cover")
        full?.let{f->if(!f.isRecycled)return f}
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
