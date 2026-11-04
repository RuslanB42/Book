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
        // «Тень толщины» (approved T1): the book's own silhouette, firm and offset down-right by its thickness, then two
        // ever softer copies further out. Light from above-left. Drawn once at a reference size (240×360) into an atlas.
        val m=THICK_MARGIN;val t=Bitmap.createBitmap(240+2*m,360+2*m,Bitmap.Config.ARGB_8888);val tc=Canvas(t);val tp=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK}
        fun layer(dx:Float,dy:Float,sigma:Float,alpha:Int,grow:Float=1f){tp.alpha=alpha;tp.maskFilter=BlurMaskFilter(((sigma-.5f)/.57735f).coerceAtLeast(.5f),BlurMaskFilter.Blur.NORMAL)
            val w=240f*grow;val h=360f*grow;val l=m+dx-(w-240f)/2;val tt=m+dy-(h-360f)/2;tc.drawRoundRect(l,tt,l+w,tt+h,6f,14f,tp)}
        layer(33f,53f,46f,if(dark)77 else 31,1.04f)
        layer(18f,28f,20f,if(dark)107 else 46)
        layer(8f,12f,5f,if(dark)158 else 77)
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
    /** The corner radius of a cover of this width (the rounded fore-edge; the spine side is nearly square). */
    fun radius(w:Float)=w*.05f
    /** A hardcover's outline: spine corners almost square, fore-edge corners rounded. */
    fun outline(path:Path,r:RectF){val s=r.width()*.017f;val f=r.width()*.058f;path.addRoundRect(r,floatArrayOf(s,s,f,f,f,f,s,s),Path.Direction.CW)}
    /** «Твёрдый переплёт» (approved Б2): a bright spine with a groove and a fine crease line that also reads on dark
     *  covers, gloss from above fading to a darker foot, a fore-edge falloff, and a thin board rim. */
    @Synchronized fun material(c:Canvas,r:RectF) {
        c.save();c.translate(r.left,r.top);c.scale(r.width()/400f,r.height()/600f)
        ink.color=Color.WHITE;ink.alpha=255
        ink.shader=spineB;c.drawRect(0f,0f,44f,600f,ink)
        ink.shader=foreB;c.drawRect(360f,0f,400f,600f,ink)
        ink.shader=glossB;c.drawRect(0f,0f,400f,600f,ink)
        ink.shader=null;c.restore()
        val d=r.width()/118f
        // the crease line along the spine
        ink.color=0x4dffffff;c.drawRect(r.left+r.width()*.042f,r.top,r.left+r.width()*.042f+(1.2f*d).coerceAtLeast(1f),r.bottom,ink)
        // board rim: light along the top and fore-edge, dark along the foot
        val hair=(d).coerceAtLeast(.75f)
        ink.color=0x38ffffff;c.drawRect(r.left,r.top,r.right,r.top+hair,ink)
        ink.color=0x1affffff;c.drawRect(r.right-hair,r.top,r.right,r.bottom,ink)
        ink.color=0x59000000;c.drawRect(r.left,r.bottom-hair*1.5f,r.right,r.bottom,ink)
    }
    private val spineB=LinearGradient(0f,0f,44f,0f,
        intArrayOf(0x4d000000,0x14000000,0x57ffffff,0x1fffffff,0x33000000,0x0f000000,0x12ffffff,Color.TRANSPARENT),
        floatArrayOf(0f,.109f,.218f,.364f,.509f,.636f,.773f,1f),Shader.TileMode.CLAMP)
    private val foreB=LinearGradient(360f,0f,400f,0f,intArrayOf(Color.TRANSPARENT,0x14ffffff,0x29000000,0x57000000),floatArrayOf(0f,.4f,.75f,1f),Shader.TileMode.CLAMP)
    private val glossB=LinearGradient(0f,0f,0f,600f,intArrayOf(0x3dffffff,0x00ffffff,0x00000000,0x2e000000),floatArrayOf(0f,.22f,.72f,1f),Shader.TileMode.CLAMP)
    private fun blend(a:Int,b:Int,t:Float)=Color.rgb((Color.red(a)*(1-t)+Color.red(b)*t).toInt(),(Color.green(a)*(1-t)+Color.green(b)*t).toInt(),(Color.blue(a)*(1-t)+Color.blue(b)*t).toInt())
}
