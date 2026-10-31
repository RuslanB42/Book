package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** A paper fold, not a translated screenshot. The crease bisects the corner and
 * its dragged position; the turned sheet reflects around it. Geometry is reused
 * while scrubbing: no layout, bitmap capture or text shaping in onDraw/MOVE. */
internal class PageCurlView(context:Context,val sourceBitmap:Bitmap,val targetBitmap:Bitmap,
    val direction:Int,private val paper:Int,private val reduced:Boolean=false):View(context) {
    var progress=0f;private set
    var touchY=sourceBitmap.height*.8f;private set
    private var bottomCorner=true
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frontPath=Path();private val backPath=Path();private val shadowPath=Path()
    private val reflection=Matrix();private val matrixValues=FloatArray(9)
    private val xs=FloatArray(8);private val ys=FloatArray(8);private var points=0
    private val frontMesh=FloatArray((COLS+1)*(ROWS+1)*2);private val backMesh=FloatArray((COLS+1)*(ROWS+1)*2)
    private val frontColors=IntArray((COLS+1)*(ROWS+1));private val backColors=IntArray((COLS+1)*(ROWS+1))
    private fun gray(g:Float):Int{val v=(g.coerceIn(0f,1f)*255).toInt();return Color.argb(255,v,v,v)}
    private val paperTile:Bitmap=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888).also{it.setPixel(0,0,paper or 0xff000000.toInt())}
    private companion object{const val COLS=36;const val ROWS=48;const val USE_STRIPS=false;const val ARC=40;const val MAXS=ARC+8}
    private val shaderMatrix=Matrix()
    private val shaderValues=FloatArray(9)
    private val cast=LinearGradient(0f,0f,1f,0f,intArrayOf(0x00000000,0x3b000000,0x65000000),floatArrayOf(0f,.72f,1f),Shader.TileMode.CLAMP)
    private val frontShade=LinearGradient(0f,0f,1f,0f,intArrayOf(0x00000000,0x13000000,0x40000000),floatArrayOf(0f,.72f,1f),Shader.TileMode.CLAMP)
    private val reverseShade=LinearGradient(0f,0f,1f,0f,
        if(Color.luminance(paper)>.4)intArrayOf(0x00000000,0x08000000,0x28000000,0x35ffffff,0x62000000)else intArrayOf(0x00000000,0x12ffffff,0x22ffffff,0x30ffffff,0x65000000),floatArrayOf(0f,.35f,.7f,.86f,1f),Shader.TileMode.CLAMP)
    private fun shade(shader:Shader,from:Float,to:Float):Shader {
        val length=to-from
        shaderValues[0]=nx*length;shaderValues[1]=-ny;shaderValues[2]=mx+nx*from
        shaderValues[3]=ny*length;shaderValues[4]=nx;shaderValues[5]=my+ny*from
        shaderValues[6]=0f;shaderValues[7]=0f;shaderValues[8]=1f
        shaderMatrix.setValues(shaderValues);shader.setLocalMatrix(shaderMatrix);return shader
    }
    private var mx=0f;private var my=0f;private var nx=1f;private var ny=0f
    private var band=1f
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;setWillNotDraw(false) }
    fun pose(p:Float,y:Float,bottom:Boolean=bottomCorner){progress=p.coerceIn(0f,1f);touchY=y.coerceIn(0f,sourceBitmap.height.toFloat());bottomCorner=bottom;invalidate()}
    private fun polygon(turned:Boolean,path:Path,reflect:Boolean=false){
        // Sutherland-Hodgman clipping against the fold's half plane.
        points=0;val w=sourceBitmap.width.toFloat();val h=sourceBitmap.height.toFloat()
        var ax=0f;var ay=h;var da=(ax-mx)*nx+(ay-my)*ny
        for(i in 0..3){val bx=if(i==1||i==2)w else 0f;val by=if(i>=2)h else 0f;val db=(bx-mx)*nx+(by-my)*ny
            val insideA=if(turned)da>=0 else da<=0;val insideB=if(turned)db>=0 else db<=0
            if(insideA!=insideB){val t=da/(da-db);xs[points]=ax+(bx-ax)*t;ys[points++]=ay+(by-ay)*t}
            if(insideB){xs[points]=bx;ys[points++]=by};ax=bx;ay=by;da=db
        }
        path.rewind();for(i in 0 until points){var x=xs[i];var y=ys[i];if(reflect){val d=(x-mx)*nx+(y-my)*ny;x-=2*d*nx;y-=2*d*ny};if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};path.close()
    }
    // ---- Fold-aligned strips ------------------------------------------------------------------
    // Every vertex row lies on a line parallel to the crease, so the bend is a pure 1-D function of the
    // distance d from the crease. Text inside one strip is only ever scaled along the normal, never sheared,
    // and the flat part (d <= -half) is a single identity strip, so stationary text is pixel exact.
    private val stripPos=FloatArray(2*MAXS*2);private val stripTex=FloatArray(2*MAXS*2);private val stripFlat=FloatArray(2*MAXS*2)
    private val stripCol=IntArray(2*MAXS);private val stripIdx=ShortArray(MAXS*6);private val ds=FloatArray(MAXS)
    private val vertexPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaders=HashMap<Bitmap,BitmapShader>()
    private fun shaderFor(b:Bitmap)=shaders.getOrPut(b){BitmapShader(b,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
    private val paperShader by lazy{BitmapShader(paperTile,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
    private fun sideFront(d:Float,r:Float,half:Float,apex:Float)=if(d<=-half)d else if(d<=0f)-half+r*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else apex
    private fun shadeFront(d:Float,r:Float,half:Float)=if(d<=-half)1f else if(d<=0f)1f-.30f*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else .70f
    private fun sideBack(d:Float,r:Float,half:Float,apex:Float)=if(d<0f)apex else if(d<=half)-half+r*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else -d
    private fun shadeBack(d:Float,r:Float,half:Float)=if(d<0f).70f else if(d<=half).70f+.30f*(-cos(((d+half)/r).coerceIn(0f,PI.toFloat()))) else 1f
    /** Sorted distinct sample distances in [lo,hi]: the ends, the cylinder breakpoints and a fine arc inside the bend. */
    private fun samples(lo:Float,hi:Float,half:Float,r:Float):Int{
        var n=0
        fun add(d:Float){if(d<lo||d>hi||n>=MAXS-1)return;if(n==0||d>ds[n-1]+.05f)ds[n++]=d}
        add(lo);add(-half)
        // ARC is even, so the crest (d = 0) and the far foot (d = half) are exact samples.
        for(i in 1..ARC){add(-half+r*PI.toFloat()*i/ARC)}
        add(hi)
        if(n>0&&ds[n-1]<hi-.05f&&n<MAXS)ds[n++]=hi
        return n
    }
    private fun drawStrips(c:Canvas,sheet:Bitmap,w:Float,h:Float,r:Float,half:Float,apex:Float){
        var dmin=Float.MAX_VALUE;var dmax=-Float.MAX_VALUE;var smin=Float.MAX_VALUE;var smax=-Float.MAX_VALUE
        for(i in 0..3){val x=if(i==1||i==2)w else 0f;val y=if(i>=2)h else 0f;val d=(x-mx)*nx+(y-my)*ny;val s=-(x-mx)*ny+(y-my)*nx
            dmin=min(dmin,d);dmax=max(dmax,d);smin=min(smin,s);smax=max(smax,s)}
        val pad=2f;smin-=pad;smax+=pad
        fun build(lo:Float,hi:Float,front:Boolean):Int{
            if(hi-lo<.1f)return 0
            val n=samples(lo,hi,half,r);if(n<2)return 0
            for(i in 0 until n){val d=ds[i]
                val pos=if(front)sideFront(d,r,half,apex) else sideBack(d,r,half,apex)
                val col=gray(if(front)shadeFront(d,r,half) else shadeBack(d,r,half))
                for(j in 0..1){val s=if(j==0)smin else smax;val o=(i*2+j)*2
                    stripPos[o]=mx+nx*pos-ny*s;stripPos[o+1]=my+ny*pos+nx*s
                    stripTex[o]=mx+nx*d-ny*s;stripTex[o+1]=my+ny*d+nx*s
                    stripFlat[o]=.5f;stripFlat[o+1]=.5f;stripCol[i*2+j]=col}}
            var q=0;for(i in 0 until n-1){val a=(i*2).toShort();stripIdx[q++]=a;stripIdx[q++]=(a+1).toShort();stripIdx[q++]=(a+2).toShort();stripIdx[q++]=(a+1).toShort();stripIdx[q++]=(a+3).toShort();stripIdx[q++]=(a+2).toShort()}
            return n
        }
        // Front: the sheet still attached (d <= 0): flat, then wrapping over the cylinder up to the crest.
        val fn=build(dmin,min(0f,dmax),true)
        if(fn>=2){c.save();c.clipPath(frontPath);vertexPaint.shader=shaderFor(sheet);vertexPaint.alpha=255
            c.drawVertices(Canvas.VertexMode.TRIANGLES,fn*2,stripPos,0,stripTex,0,stripCol,0,stripIdx,0,(fn-1)*6,vertexPaint);c.restore()}
        // Back: the turned sheet (d >= 0): down the far side of the cylinder, then flat and reversed.
        val bn=build(max(0f,dmin),dmax,false)
        if(bn>=2){
            vertexPaint.shader=paperShader;vertexPaint.alpha=255
            c.drawVertices(Canvas.VertexMode.TRIANGLES,bn*2,stripPos,0,stripFlat,0,stripCol,0,stripIdx,0,(bn-1)*6,vertexPaint)
            // Faint mirrored ink shows through the paper (the strips already reverse it).
            vertexPaint.shader=shaderFor(sheet);vertexPaint.alpha=34
            c.drawVertices(Canvas.VertexMode.TRIANGLES,bn*2,stripPos,0,stripTex,0,stripCol,0,stripIdx,0,(bn-1)*6,vertexPaint)
            vertexPaint.shader=null;vertexPaint.alpha=255}
    }
    /** Fallback for old Android: axis-aligned bitmap mesh. */
    private fun drawMesh(c:Canvas,sheet:Bitmap,r:Float,half:Float,apex:Float){
        val w=sourceBitmap.width.toFloat();val h=sourceBitmap.height.toFloat()
        var v=0;var k=0
        for(row in 0..ROWS)for(col in 0..COLS){val x=w*col/COLS;val y=h*row/ROWS;val d=(x-mx)*nx+(y-my)*ny
            // front face: flat, then foreshortened as it bends away; everything past the crest collapses onto it
            val theta=((d+half)/r).coerceIn(0f,PI.toFloat())
            val df=if(d<=-half)d else if(d<=0f)-half+r*sin(theta) else apex
            val gf=if(d<=-half)1f else if(d<=0f)1f-.30f*sin(theta) else .70f
            frontMesh[v]=x+(df-d)*nx;frontMesh[v+1]=y+(df-d)*ny;frontColors[k]=gray(gf)
            // back face: from the crest round to flat; the part before the crest collapses onto it
            val db=if(d<0f)apex else if(d<=half)-half+r*sin(theta) else -d
            val gb=if(d<0f).70f else if(d<=half).70f+.30f*(-cos(theta)) else 1f
            backMesh[v]=x+(db-d)*nx;backMesh[v+1]=y+(db-d)*ny;backColors[k]=gray(gb)
            v+=2;k++
        }
        c.save();c.clipPath(frontPath);paint.shader=null;paint.colorFilter=null
        c.drawBitmapMesh(sheet,COLS,ROWS,frontMesh,0,frontColors,0,paint);c.restore()
        c.save()
        c.drawBitmapMesh(paperTile,COLS,ROWS,backMesh,0,backColors,0,paint)
        // Faint mirrored ink shows through the paper (the mesh already reverses it).
        paint.alpha=34;c.drawBitmapMesh(sheet,COLS,ROWS,backMesh,0,backColors,0,paint);paint.alpha=255
        c.restore()
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c);val w=sourceBitmap.width.toFloat();val h=sourceBitmap.height.toFloat();if(w<=0||h<=0)return
        val p=progress;paint.reset();paint.isAntiAlias=true;paint.isFilterBitmap=true
        if(p<=.0001f){c.drawBitmap(sourceBitmap,0f,0f,paint);return}
        if(p>=.9999f){c.drawBitmap(targetBitmap,0f,0f,paint);return}
        c.save();c.clipRect(0f,0f,w,h)
        if(reduced){c.drawBitmap(sourceBitmap,0f,0f,paint);c.save();if(direction>0)c.clipRect(w*(1-p),0f,w,h)else c.clipRect(0f,0f,w*p,h);c.drawBitmap(targetBitmap,0f,0f,paint);c.restore();c.restore();return}
        val sheet=if(direction>0)sourceBitmap else targetBitmap;val below=if(direction>0)targetBitmap else sourceBitmap
        val fold=if(direction>0)p else 1-p
        // T moves twice as far as the crease. Vertical bending follows the hand,
        // but vanishes at both endpoints, making exact bitmap-to-live handoff.
        val cy=if(bottomCorner)h else 0f;val tx=w-2*w*fold
        val ty=cy+(touchY-cy)*sin(Math.PI*fold).toFloat()*.52f
        mx=(w+tx)*.5f;my=(cy+ty)*.5f
        val len=hypot(w-tx,cy-ty).coerceAtLeast(.01f);nx=(w-tx)/len;ny=(cy-ty)/len
        band=(w*.075f*sin(Math.PI*fold).toFloat()).coerceAtLeast(w*.008f)
        c.drawBitmap(below,0f,0f,paint)
        polygon(false,frontPath);polygon(true,backPath,true)
        // Soft cast shadow on the exposed page; clipped to the folded silhouette.
        c.save();c.translate(nx*band*.3f,ny*band*.3f);c.clipPath(backPath)
        paint.shader=shade(cast,-band*2,band)
        c.drawRect(0f,0f,w,h,paint);paint.shader=null;c.restore()
        // True cylinder curl. Along the fold normal d the paper stays flat up to -half, wraps a half
        // cylinder of radius r (arc length pi*r), then lies flat again, reversed. Foreshortening
        // is a pure function of d, so ink compresses smoothly and stationary text stays pixel-exact.
        val r=w*(.012f+.06f*sin(Math.PI*fold).toFloat());val half=(PI*r/2).toFloat();val apex=-half+r
        if(USE_STRIPS&&android.os.Build.VERSION.SDK_INT>=29)drawStrips(c,sheet,w,h,r,half,apex) else drawMesh(c,sheet,r,half,apex)
        val lightPaper=Color.luminance(paper)>.4
        paint.color=if(lightPaper)0x3fffffff else 0x25ffffff;paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density*.65f;c.drawPath(backPath,paint);paint.style=Paint.Style.FILL
        c.restore()
    }
}
