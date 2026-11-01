package app.polka.nativeapp

import android.graphics.*

/** One light source, above-left. Shadows are cached alpha atlases, never hardware blur or layers. */
internal object BookCoverVolume {
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadows=arrayOfNulls<Bitmap>(6)
    internal var atlasBuilds=0;private set
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
    private val spine=LinearGradient(0f,0f,42f,0f,
        intArrayOf(0x72000000,0x1c000000,0x24ffffff,0x0cffffff,0x29000000,0x42000000,0x12ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.10f,.26f,.43f,.57f,.64f,.79f,1f),Shader.TileMode.CLAMP)
    private val face=LinearGradient(0f,0f,400f,600f,intArrayOf(0x10ffffff,0x03ffffff,Color.TRANSPARENT,0x13000000),floatArrayOf(0f,.28f,.65f,1f),Shader.TileMode.CLAMP)
    private val fore=LinearGradient(385f,0f,400f,0f,intArrayOf(Color.TRANSPARENT,0x0a000000,0x23000000,0x0cffffff,0x40000000),floatArrayOf(0f,.52f,.80f,.91f,1f),Shader.TileMode.CLAMP)
    private val ends=LinearGradient(0f,0f,0f,600f,intArrayOf(0x14ffffff,Color.TRANSPARENT,Color.TRANSPARENT,0x26000000),floatArrayOf(0f,.008f,.982f,1f),Shader.TileMode.CLAMP)
    @Synchronized private fun shadow(dark:Boolean,soft:Boolean,stack:Boolean=false):Bitmap {
        val index=(if(dark)1 else 0)+(if(stack)4 else if(soft)2 else 0);shadows[index]?.let{return it}
        val b=Bitmap.createBitmap(if(stack)720 else if(soft)480 else 360,if(stack)840 else if(soft)600 else 480,Bitmap.Config.ARGB_8888);val c=Canvas(b);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
        val r=if(stack)RectF(240f,216f,480f,576f)else if(soft)RectF(120f,96f,360f,456f)else RectF(60f,48f,300f,408f)
        if(stack){
            // Only broad low-opacity lobes: stacked books must not accumulate contact rings.
            p.setShadowLayer(48f,0f,20f,if(dark)0x20000000 else 0x16000000);c.drawRoundRect(r,3f,3f,p)
            p.setShadowLayer(30f,0f,12f,if(dark)0x16000000 else 0x10000000);c.drawRoundRect(r,3f,3f,p)
            shadows[index]=b;atlasBuilds++;return b
        }
        if(soft){
            // Floating recommendation cards: a broad feathered penumbra, no hard contact ring.
            p.setShadowLayer(28f,3f,18f,if(dark)0x38000000 else 0x1f000000);c.drawRoundRect(r,3f,3f,p)
            p.setShadowLayer(14f,2f,7f,if(dark)0x29000000 else 0x19000000);c.drawRoundRect(r,3f,3f,p)
            shadows[index]=b;atlasBuilds++;return b
        }
        // Broad ambient penumbra, directional lift, tight contact. No coloured glow.
        p.setShadowLayer(18f,3f,13f,if(dark)0x59000000 else 0x26000000);c.drawRoundRect(r,3f,3f,p)
        p.setShadowLayer(6f,2.5f,5f,if(dark)0x70000000 else 0x33000000);c.drawRoundRect(r,3f,3f,p)
        p.setShadowLayer(1.6f,.6f,1.5f,if(dark)0x99000000.toInt() else 0x4d000000);c.drawRoundRect(r,3f,3f,p)
        shadows[index]=b;atlasBuilds++;return b
    }
    @Synchronized fun shadow(c:Canvas,r:RectF,dark:Boolean,soft:Boolean=false,stack:Boolean=false,fade:Float=1f) {
        if(r.width()<=0||r.height()<=0||fade<=0f)return
        val w=r.width();val h=r.height();ink.shader=null;ink.color=Color.WHITE;ink.alpha=(255*fade.coerceAtMost(1f)).toInt()
        c.drawBitmap(shadow(dark,soft,stack),null,if(stack)RectF(r.left-w,r.top-h*.6f,r.right+w,r.bottom+h*(264f/360f))else if(soft)RectF(r.left-w*.5f,r.top-h*(96f/360f),r.right+w*.5f,r.bottom+h*.4f)else RectF(r.left-w*.25f,r.top-h*(48f/360f),r.right+w*.25f,r.bottom+h*.20f),ink)
    }
    /** Apple Books style: a flat cover, no board thickness or page block — the face is the whole bounds. */
    @Synchronized fun boards(c:Canvas,r:RectF,color:Int):RectF = RectF(r)
    /** The corner radius of a cover of this width. */
    fun radius(w:Float)=w*.022f
    /** A soft binding crease near the left edge and a hairline rim, as on Apple Books covers. */
    @Synchronized fun material(c:Canvas,r:RectF) {
        c.save();c.translate(r.left,r.top);c.scale(r.width()/400f,r.height()/600f)
        ink.color=Color.WHITE;ink.alpha=255
        ink.shader=crease;c.drawRect(0f,0f,30f,600f,ink)
        ink.shader=null;c.restore()
        val rr=radius(r.width());val hair=(r.width()*.004f).coerceAtLeast(.75f)
        ink.style=Paint.Style.STROKE;ink.strokeWidth=hair;ink.color=0x26000000
        c.drawRoundRect(r.left+hair/2,r.top+hair/2,r.right-hair/2,r.bottom-hair/2,rr,rr,ink);ink.style=Paint.Style.FILL
    }
    private val crease=LinearGradient(0f,0f,30f,0f,
        intArrayOf(0x33000000,0x0a000000,0x2effffff,0x14000000,0x24000000,0x08ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.12f,.24f,.40f,.52f,.70f,1f),Shader.TileMode.CLAMP)
    private fun blend(a:Int,b:Int,t:Float)=Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
}
