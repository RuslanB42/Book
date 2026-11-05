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
    private const val THICK_MARGIN=190
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
        // A hardcover lying on a table, lit from above-left (approved Т2+): a tight dark contact all round, a short cast
        // to the lower right and a soft penumbra beyond it. Built from the approved CSS layers at reference 240×360.
        val m=THICK_MARGIN;val t=Bitmap.createBitmap(240+2*m,360+2*m,Bitmap.Config.ARGB_8888);val tc=Canvas(t);val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
        val k=240f/170f
        fun layer(dx:Float,dy:Float,blur:Float,spread:Float,light:Float,darkA:Float){val sigma=blur/2f*k
            tp.maskFilter=if(sigma<.6f)null else BlurMaskFilter(((sigma-.5f)/.57735f).coerceAtLeast(.5f),BlurMaskFilter.Blur.NORMAL)
            tp.alpha=(255*(if(dark)darkA else light)).toInt();val g=spread*k
            tc.drawRoundRect(m+dx*k-g,m+dy*k-g,m+240f+dx*k+g,m+360f+dy*k+g,4f,4f,tp)}
        layer(7f,11f,16f,-4f,.22f,.85f)
        layer(3f,5f,7f,-1f,.24f,.90f)
        layer(1f,2f,2f,0f,.26f,.95f)
        layer(0f,0f,2f,0f,.12f,.60f)
        layer(0f,0f,0f,.5f,.14f,.70f)
        shadows[index]=t;atlasBuilds++;return t
    }
    @Synchronized fun shadow(c:Canvas,r:RectF,dark:Boolean,soft:Boolean=false,stack:Boolean=false,fade:Float=1f) {
        if(r.width()<=0||r.height()<=0||fade<=0f)return
        val w=r.width();val h=r.height();ink.shader=null;ink.color=Color.WHITE;ink.alpha=(255*fade.coerceAtMost(1f)).toInt()
        if(!soft&&!stack){val mx=THICK_MARGIN*w/240f;val my=THICK_MARGIN*h/360f
            c.drawBitmap(shadow(dark,false,false),null,RectF(r.left-mx,r.top-my,r.right+mx,r.bottom+my),ink);return}
        c.drawBitmap(shadow(dark,soft,stack),null,if(stack)RectF(r.left-w,r.top-h*.6f,r.right+w,r.bottom+h*(264f/360f))else RectF(r.left-w*.5f,r.top-h*(96f/360f),r.right+w*.5f,r.bottom+h*.4f),ink)
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
    private fun blend(a:Int,b:Int,t:Float)=Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
}
