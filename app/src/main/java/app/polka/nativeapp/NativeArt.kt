package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import org.json.JSONObject

/** Same cover renderer is used in the library, shelves, recommendations and continue card. */
@android.annotation.SuppressLint("WrongConstant")
class CoverView(context: Context, private val book: JSONObject, private val image: Bitmap? = null) : View(context) {
    val bookId get()=book.str("id")
    var softShadow=true
    var stackShadow=false
    /** 0..1 multiplier for the cast shadow only (cover pixels untouched). Lets two covers hand a shadow over without a pop or a clipped alpha layer. */
    var shadowFade=1f
        set(v){val n=v.coerceIn(0f,1f);if(n!=field){field=n;invalidate()}}
    private var retainedArtwork:Bitmap?=image
    private var retainedKey=book.optJSONObject("cover")?.toString().orEmpty()
    init { elevation=0f; outlineProvider=object:android.view.ViewOutlineProvider() { override fun getOutline(v:View,o:android.graphics.Outline) { o.setRoundRect(0,0,v.width,v.height,v.width*.012f) } } }
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int) { if(layoutParams?.height==android.view.ViewGroup.LayoutParams.WRAP_CONTENT) { val w=MeasureSpec.getSize(widthMeasureSpec); setMeasuredDimension(w,(w*1.5f).toInt()) } else super.onMeasure(widthMeasureSpec,heightMeasureSpec) }
    override fun performClick():Boolean { super.performClick(); return true }
    private val palette=listOf("#2f3e46" to "#ebe5d6","#6b2737" to "#f3e6d6","#1f3a5f" to "#e8edf3","#3d4a2f" to "#ece6d2","#7a4b1e" to "#f6ead7","#2d2a32" to "#e6dccb","#8c3b2b" to "#f4e9dc","#e3d8c3" to "#2a2622","#c9d4cf" to "#1d2a26","#4a3b5c" to "#efe7f5","#0f4c4a" to "#e3f0ec")
    fun colors(b:JSONObject):Pair<Int,Int> { var hash=2166136261L.toInt(); (b.str("title")+MainActivity.authors(b)).forEach { hash=(hash xor it.code)*16777619 }; val pair=palette[((hash.toLong() and 0xffffffffL)%palette.size).toInt()]; return Color.parseColor(pair.first) to Color.parseColor(pair.second) }
    private var requestedId:String?=null
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activity=context as? MainActivity
        val bitmap=currentArtwork()
        if(bitmap==null && activity!=null && book.optJSONObject("cover")!=null && requestedId!=book.optJSONObject("cover")?.str("__polkaBlob")) { requestedId=book.optJSONObject("cover")?.str("__polkaBlob"); val expected=book.optJSONObject("cover")?.toString().orEmpty();activity.requestCover(book) { loaded -> if(loaded!=null&&expected==book.optJSONObject("cover")?.toString().orEmpty()) {retainedArtwork=loaded;retainedKey=expected;invalidate();artworkChanged?.invoke()} } }
        val bounds=RectF(0f,0f,width.toFloat(),height.toFloat())
        BookCoverVolume.shadow(canvas,bounds,activity?.light==false,softShadow,stackShadow,shadowFade)
        drawCover(canvas,bounds,book,bitmap)
    }
    internal var artworkChanged:(()->Unit)?=null
    private fun currentArtwork():Bitmap? {
        val ref=book.optJSONObject("cover")
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
        val r=BookCoverVolume.boards(canvas,bounds,BookCoverVolume.binding(bitmap,colors(b).first))
        canvas.save(); val path = Path().apply { if(shelf){val d=resources.displayMetrics.density;addRoundRect(r,floatArrayOf(2*d,2*d,4*d,4*d,4*d,4*d,2*d,2*d),Path.Direction.CW)}else addRoundRect(r,r.width()*.012f,r.width()*.012f,Path.Direction.CW) }; canvas.clipPath(path)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        if (bitmap != null) {
            val scale = maxOf(r.width() / bitmap.width, r.height() / bitmap.height); val w = bitmap.width * scale; val h = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF(r.centerX() - w/2, r.centerY() - h/2, r.centerX() + w/2, r.centerY() + h/2), paint)
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
    private fun TextPaint.letterSpacingCompat() { /* tracking follows native font metrics */ }
}
class ShelfArt(context: Context, val previews: List<Pair<JSONObject, Bitmap?>>, val light: Boolean, val add: Boolean = false) : View(context) {
    override fun onMeasure(ws:Int,hs:Int) { if(layoutParams?.height==android.view.ViewGroup.LayoutParams.WRAP_CONTENT) { val w=MeasureSpec.getSize(ws); setMeasuredDimension(w,(w*.94f).toInt()) } else super.onMeasure(ws,hs) }
    private val retained=mutableMapOf<String,Bitmap>()
    private val requested=mutableSetOf<String>(); private var frost:Bitmap?=null;private var frostKey="";private var pendingKey=""
    private val renderer=CoverView(context,org.json.JSONObject())
    private fun left(index:Int,count:Int,w:Float)=w*(.27f-(count-1)*.08f+index*.16f)
    override fun onDraw(c:Canvas) {
        super.onDraw(c); if(width<1 || height<1) return
        val a=context as? MainActivity; val density=resources.displayMetrics.density; val w=width.toFloat(); val h=height.toFloat(); val top=h*.46f; val radius=w*.045f; val p=Paint(Paint.ANTI_ALIAS_FLAG)
        val glass=RectF(0f,top,w,h); val background=if(light) 0xfff2f2f0.toInt() else 0xff161617.toInt()
        val books=previews.take(3).map { (b,bitmap) -> b to (a?.cover(b) ?: retained[b.str("id")] ?: bitmap) }
        books.forEach { (book,bitmap) -> if(bitmap==null && book.optJSONObject("cover")!=null && requested.add(book.str("id"))) a?.requestCover(book) { loaded -> if(loaded!=null)retained[book.str("id")]=loaded;requested.remove(book.str("id")); invalidate() } }
        // The glass has its own shadow and opaque backing, rather than tinting sharp covers twice.
        p.color=background; p.setShadowLayer(if(light) 25*density else 34*density,0f,if(light) 14*density else 18*density,if(light) 0x22000000 else 0x73000000); c.drawRoundRect(glass,radius,radius,p); p.clearShadowLayer()
        if(!add && books.isEmpty()) { p.color=if(light) 0x55808080 else 0x44777777; p.style=Paint.Style.STROKE; p.strokeWidth=density; p.pathEffect=DashPathEffect(floatArrayOf(5*density,4*density),0f); c.drawRoundRect(w*.27f,h*.90f-w*.69f,w*.73f,h*.90f,3*density,3*density,p); p.pathEffect=null; p.style=Paint.Style.FILL }
        
        fun covers(canvas:Canvas) { books.indices.reversed().forEach { i -> val (book,bitmap)=books[i]; val x=left(i,books.size,w);val r=RectF(x,h*.90f-w*.69f,x+w*.46f,h*.90f)
            BookCoverVolume.shadow(canvas,r,!light);renderer.drawCover(canvas,r,book,bitmap,true) } }
        covers(c)
        val key="$width:$height:$light:"+books.joinToString { it.first.str("id")+it.first.str("title")+it.first.str("authors")+System.identityHashCode(it.second) }
        if(key!=frostKey) {
            val targetWidth=(width/density).toInt().coerceIn(1,160);val targetHeight=(targetWidth*.94f).toInt().coerceAtLeast(1)
            // Commit the sharp covers and matching frosted front in one draw. The bounded
            // cached raster is never injected into an already visible blank shelf later.
            frost=ShelfFrost.prepared(key){
                val bitmap=Bitmap.createBitmap(targetWidth,targetHeight,Bitmap.Config.ARGB_8888);val layer=Canvas(bitmap);layer.scale(targetWidth/w,targetHeight/h);covers(layer)
                try{ShelfFrost.blur(bitmap,3*density*targetWidth/w)}finally{bitmap.recycle()}
            };frostKey=key
        }
        // CSS paints an opaque background under the border. The filtered children are
        // clipped to the padding box, not composited underneath the border itself.
        val outer=Path().apply { addRoundRect(glass,radius,radius,Path.Direction.CW) }
        c.save();c.clipPath(outer);p.color=background;c.drawRect(glass,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=density
        p.color=if(light)0xccffffff.toInt() else 0x21ffffff
        val edgeRadius=(radius-density*.5f).coerceAtLeast(0f)
        c.drawRoundRect(RectF(density*.5f,top+density*.5f,w-density*.5f,h-density*.5f),edgeRadius,edgeRadius,p)
        p.style=Paint.Style.FILL;p.color=Color.WHITE
        val inner=RectF(density,top+density,w-density,h-density)
        val innerRadius=(radius-density).coerceAtLeast(0f)
        c.clipPath(Path().apply{addRoundRect(inner,innerRadius,innerRadius,Path.Direction.CW)})
        frost?.let { bitmap -> val matrix=ColorMatrix().apply { setSaturation(1.12f) }; val brightness=if(light) 1.05f else .82f; matrix.postConcat(ColorMatrix(floatArrayOf(brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,brightness,0f,0f,0f,0f,0f,1f,0f))); p.colorFilter=ColorMatrixColorFilter(matrix); c.drawBitmap(bitmap,null,RectF(0f,0f,w,h),p); p.colorFilter=null }
        p.color=if(light) 0x66ffffff else 0x0effffff; c.drawRect(glass,p); c.restore()
        val size=(w*.047f).coerceIn(8*density,11*density);val sr=size/2;val inset=density+w*.045f+sr
        listOf(inset to top+inset,w-inset to top+inset,inset to h-inset,w-inset to h-inset).forEachIndexed{index,(x,y)->ShelfScrew.draw(c,x,y,size,density,ShelfScrew.turns[index])}
        if(add) { p.color=if(light) 0xff777777.toInt() else 0xffbbbbbb.toInt(); p.strokeWidth=density*1.6f; val y=(top+h)/2; c.drawLine(w*.44f,y,w*.56f,y,p); c.drawLine(w*.5f,y-w*.06f,w*.5f,y+w*.06f,p) }
    }
    override fun onDetachedFromWindow() { pendingKey="";frost=null;frostKey="";super.onDetachedFromWindow() }
}
class IconView(context: Context, private val kind: String, var ink: Int) : View(context) {
    override fun onDraw(c: Canvas) {
        super.onDraw(c); c.save(); val size=(if(kind=="plus") 26f else if(minOf(width,height)<=context.resources.displayMetrics.density*30f) 16f else if(minOf(width,height)<=context.resources.displayMetrics.density*44.5f) 22f else 24f)*context.resources.displayMetrics.density; c.translate((width-size)/2,(height-size)/2); c.scale(size/24,size/24)
        if(OriginalIcons.draw(context,c,kind,ink)) { c.restore(); return }
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=ink; style=Paint.Style.STROKE; strokeWidth=1.6f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND }
        fun line(vararg a:Float) { val path=Path(); path.moveTo(a[0],a[1]); for(i in 2 until a.size step 2) path.lineTo(a[i],a[i+1]); c.drawPath(path,p) }
        when(kind) {
            "expand" -> { line(4f,10f,4f,4f,10f,4f);line(14f,4f,20f,4f,20f,10f);line(20f,14f,20f,20f,14f,20f);line(10f,20f,4f,20f,4f,14f) }
            "share" -> { line(12f,15f,12f,3f);line(7f,8f,12f,3f,17f,8f);line(5f,13f,5f,21f,19f,21f,19f,13f) }
            "download" -> { line(12f,3f,12f,16f);line(7f,11f,12f,16f,17f,11f);line(4f,18f,4f,21f,20f,21f,20f,18f) }
            "home" -> { line(3f,10f,12f,3f,21f,10f,21f,21f,15f,21f,15f,14f,9f,14f,9f,21f,3f,21f,3f,10f) }
            "shelves" -> { line(4f,20f,4f,5f); line(8f,20f,8f,3f); line(12f,20f,12f,7f); line(17f,5f,21f,20f); line(2f,21f,22f,21f) }
            "quotes" -> { c.drawRect(4f,12f,9f,20f,p); c.drawRect(15f,12f,20f,20f,p); line(4f,12f,4f,8f,8f,4f); line(15f,12f,15f,8f,19f,4f) }
            "foryou" -> { c.drawRoundRect(6f,4f,18f,20f,2f,2f,p); line(2f,7f,2f,17f); line(22f,7f,22f,17f) }
            "search" -> { c.drawCircle(10f,10f,6f,p); line(15f,15f,21f,21f) }
            "filter" -> { line(4f,6f,20f,6f); line(7f,12f,17f,12f); line(10f,18f,14f,18f) }
            "settings" -> { c.drawCircle(12f,7f,4f,p); c.drawArc(4f,13f,20f,26f,180f,180f,false,p) }
            "plus" -> { line(12f,4f,12f,20f); line(4f,12f,20f,12f) }
            "back" -> line(15f,5f,8f,12f,15f,19f)
            "more" -> { p.style=Paint.Style.FILL; for(x in listOf(5f,12f,19f)) c.drawCircle(x,12f,1.2f,p) }
            "bookmark" -> line(6f,3f,18f,3f,18f,21f,12f,17f,6f,21f,6f,3f)
            "toc" -> { line(4f,6f,20f,6f);line(4f,12f,20f,12f);line(4f,18f,20f,18f) }
        }; c.restore()
    }
}

class ThinProgress(context:Context,val fraction:Double,val foreground:Int,val track:Int):View(context) {
    override fun onDraw(c:Canvas) { val p=Paint(Paint.ANTI_ALIAS_FLAG); val radius=height/2f; p.color=track; c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),radius,radius,p); p.color=foreground; c.drawRoundRect(0f,0f,(width*fraction).toFloat(),height.toFloat(),radius,radius,p) }
}
