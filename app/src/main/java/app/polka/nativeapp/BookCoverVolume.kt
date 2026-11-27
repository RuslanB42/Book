package app.polka.nativeapp

import android.graphics.*

/** One light source, above-left (the cover's cast shade is the app's own, see [FloatShade] and [Lift.COVER]). */
internal object BookCoverVolume {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bindings=java.util.WeakHashMap<Bitmap,Int>()
    @Synchronized fun binding(bitmap:Bitmap?,fallback:Int):Int {
        if(bitmap==null||bitmap.isRecycled||bitmap.config==Bitmap.Config.HARDWARE)return fallback
        bindings[bitmap]?.let{return it}
        // Dominant real pigment around the cover's perimeter, away from the paper block,
        // spine highlight and central illustration/title. Quantized voting avoids averaging
        // complementary colours into an unrelated muddy hue; alpha weights exclude corners.
        val weights=IntArray(512);val reds=LongArray(512);val greens=LongArray(512);val blues=LongArray(512)
        for(y in 0 until 16)for(x in 0 until 12){
            if(x in 3..8 && y in 3..12)continue
            val px=(bitmap.width*(.12f+x*.068f)).toInt().coerceIn(0,bitmap.width-1)
            val py=(bitmap.height*(.08f+y*.056f)).toInt().coerceIn(0,bitmap.height-1)
            val pixel=bitmap.getPixel(px,py);val weight=Color.alpha(pixel);if(weight<128)continue
            val r=Color.red(pixel);val g=Color.green(pixel);val b=Color.blue(pixel);val bin=((r shr 5) shl 6) or ((g shr 5) shl 3) or (b shr 5)
            weights[bin]+=weight;reds[bin]+=r.toLong()*weight;greens[bin]+=g.toLong()*weight;blues[bin]+=b.toLong()*weight
        }
        val bin=weights.indices.maxByOrNull{weights[it]}?:0;val weight=weights[bin]
        return (if(weight==0)fallback else Color.rgb((reds[bin]/weight).toInt(),(greens[bin]/weight).toInt(),(blues[bin]/weight).toInt())).also{bindings[bitmap]=it}
    }
    /** Apple Books style: a flat cover, no board thickness or page block — the face is the whole bounds. */
    @Synchronized fun boards(c:Canvas,r:RectF,color:Int):RectF = RectF(r)
    /** Spine-side corners a touch rounder than the fore-edge ones, as on a real hardcover. */
    fun radius(w:Float)=w*.0147f
    fun outline(path:Path,r:RectF){val sp=r.width()*.0147f;val fe=r.width()*.0088f;path.addRoundRect(r,floatArrayOf(sp,sp,fe,fe,fe,fe,sp,sp),Path.Direction.CW)}
    /** A slightly softened print, as on a matte laminated board (a touch less contrast and colour). */
    val matte=ColorMatrixColorFilter(ColorMatrix().apply{setSaturation(.93f);postConcat(ColorMatrix(floatArrayOf(.94f,0f,0f,0f,10f,0f,.94f,0f,0f,10f,0f,0f,.94f,0f,10f,0f,0f,0f,1f,0f)))})
    /** Approved Т2+: one light from above-left. A rounded spine with a soft sheen, then a gentle hinge hollow; the
     *  board a little brighter at the top left and falling into shade toward the lower right; edges rolling off
     *  (a thin light along the top, shade along the foot and fore-edge); fine matte paper grain. */
    @Synchronized fun material(c:Canvas,r:RectF) {
        c.save();c.translate(r.left,r.top);c.scale(r.width()/400f,r.height()/600f)
        ink.color=Color.WHITE;ink.alpha=255
        ink.shader=spineT;c.drawRect(0f,0f,56f,600f,ink)
        ink.shader=lightT;c.drawRect(0f,0f,400f,600f,ink)
        ink.shader=topT;c.drawRect(0f,0f,400f,9f,ink)
        ink.shader=footT;c.drawRect(0f,585f,400f,600f,ink)
        ink.shader=foreT;c.drawRect(388f,0f,400f,600f,ink)
        ink.shader=null;c.restore()
        ink.shader=grain();c.drawRect(r,ink);ink.shader=null
        val hair=(r.width()/170f).coerceAtLeast(.6f)
        ink.color=0x29ffffff;c.drawRect(r.left,r.top,r.right,r.top+hair,ink)
        ink.color=0x4d000000;c.drawRect(r.left,r.bottom-hair,r.right,r.bottom,ink)
        ink.color=0x38000000;c.drawRect(r.right-hair,r.top,r.right,r.bottom,ink)
    }
    private val spineT=LinearGradient(0f,0f,56f,0f,
        intArrayOf(0x38000000,0x0f000000,0x1affffff,0x08ffffff,0x0f000000,0x1f000000,0x0f000000,0x17ffffff,0x08ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.071f,.171f,.286f,.371f,.457f,.543f,.643f,.786f,1f),Shader.TileMode.CLAMP)
    private val lightT=LinearGradient(0f,0f,400f,600f,intArrayOf(0x12ffffff,0x00ffffff,0x00000000,0x1f000000),floatArrayOf(0f,.4f,.6f,1f),Shader.TileMode.CLAMP)
    private val topT=LinearGradient(0f,0f,0f,9f,0x1affffff,0x00ffffff,Shader.TileMode.CLAMP)
    private val footT=LinearGradient(0f,585f,0f,600f,0x00000000,0x2e000000,Shader.TileMode.CLAMP)
    private val foreT=LinearGradient(388f,0f,400f,0f,0x00000000,0x29000000,Shader.TileMode.CLAMP)
    private var grainShader:BitmapShader?=null
    private fun grain():BitmapShader=grainShader?:run{val n=64;val px=IntArray(n*n);val rnd=java.util.Random(7)
        for(k in px.indices){val v=rnd.nextInt(256);px[k]=if(v<128)Color.argb(rnd.nextInt(16),0,0,0) else Color.argb(rnd.nextInt(16),255,255,255)}
        BitmapShader(Bitmap.createBitmap(px,n,n,Bitmap.Config.ARGB_8888),Shader.TileMode.REPEAT,Shader.TileMode.REPEAT).also{grainShader=it}}
}
