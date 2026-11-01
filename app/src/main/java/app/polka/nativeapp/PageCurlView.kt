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
    /** Where on the page edge the hand grabbed (overlay y). Near a corner the fold is diagonal; mid-edge it is almost straight. */
    private var anchorY=sourceBitmap.height.toFloat()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val lift=Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp=context.resources.displayMetrics.density
    private val frontPath=Path();private val backPath=Path();private val shadowPath=Path()
    private val reflection=Matrix();private val matrixValues=FloatArray(9)
    private val xs=FloatArray(8);private val ys=FloatArray(8);private var points=0
    private val mesh=FloatArray((COLS+1)*(ROWS+1)*2)
    private val backMesh=FloatArray((COLS+1)*(ROWS+1)*2)
    private val frontColors=IntArray((COLS+1)*(ROWS+1))
    private val backColors=IntArray((COLS+1)*(ROWS+1))
    /** The sheet washed with paper colour: drawn on the back, its ink reads as text seen faintly through the paper. */
    private var inkBack:Bitmap?=null
    private val paper2=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tube=LinearGradient(0f,0f,1f,0f,intArrayOf(0x40000000,0x14000000,0x00000000),floatArrayOf(0f,.35f,1f),Shader.TileMode.CLAMP)
    private fun grey(l:Float):Int{val g=(255*l.coerceIn(0f,1f)).toInt();return Color.rgb(g,g,g)}
    companion object{const val COLS=36;const val ROWS=48;val WHITE=Color.WHITE}
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
    override fun onDetachedFromWindow(){inkBack?.recycle();inkBack=null;super.onDetachedFromWindow()}
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;setWillNotDraw(false) }
    /** The raw grab point is kept and snapped once per pose, so animation frames that reuse it never drift it further into the corner. */
    private var grabY=sourceBitmap.height.toFloat()
    fun pose(p:Float,y:Float,anchor:Float=grabY){progress=p.coerceIn(0f,1f);touchY=y.coerceIn(0f,sourceBitmap.height.toFloat());grabY=anchor;anchorY=snapAnchor(anchor);invalidate()}
    /** Within ~30% of a corner the grab point is pulled into that corner (the classic diagonal curl); elsewhere it stays where the finger landed. */
    private fun snapAnchor(y:Float):Float{val h=sourceBitmap.height.toFloat();if(h<=0f)return y;val v=y.coerceIn(0f,h);val rel=v/h;val k=(1f-min(rel,1f-rel)/.3f).coerceIn(0f,1f);return v+((if(rel<.5f)0f else h)-v)*k*k}
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
    override fun onDraw(c:Canvas){
        super.onDraw(c);val w=sourceBitmap.width.toFloat();val h=sourceBitmap.height.toFloat();if(w<=0||h<=0)return
        val p=progress;paint.reset();paint.isAntiAlias=true;paint.isFilterBitmap=true
        if(p<=.0001f){c.drawBitmap(sourceBitmap,0f,0f,paint);return}
        if(p>=.9999f){c.drawBitmap(targetBitmap,0f,0f,paint);return}
        c.save();c.clipRect(0f,0f,w,h)
        if(reduced){c.drawBitmap(sourceBitmap,0f,0f,paint);c.save();if(direction>0)c.clipRect(w*(1-p),0f,w,h)else c.clipRect(0f,0f,w*p,h);c.drawBitmap(targetBitmap,0f,0f,paint);c.restore();c.restore();return}
        val sheet=if(direction>0)sourceBitmap else targetBitmap;val below=if(direction>0)targetBitmap else sourceBitmap
        val fold=if(direction>0)p else 1-p
        // The finger point T and the crease are as before (T moves twice as far as the crease), so the gesture feels the
        // same. The sheet now rolls over a cylinder of radius R whose flat-lying back meets the crease: like iOS books.
        val cy=anchorY.coerceIn(0f,h);val tx=w-2*w*fold
        val ty=cy+(touchY-cy)*sin(Math.PI*fold).toFloat()*.52f
        mx=(w+tx)*.5f;my=(cy+ty)*.5f
        val len=hypot(w-tx,cy-ty).coerceAtLeast(.01f);nx=(w-tx)/len;ny=(cy-ty)/len
        band=(w*.075f*sin(Math.PI*fold).toFloat()).coerceAtLeast(w*.008f)
        val r=(w*.11f*sin(Math.PI*fold).toFloat().pow(.6f)).coerceAtMost(len*.45f)
        val half=(Math.PI*r/2).toFloat()
        val ax=mx-nx*half;val ay=my-ny*half
        c.drawBitmap(below,0f,0f,paint)
        // Soft shadow of the roll on the page below, just outside the roll's silhouette.
        if(r>1f){paint.shader=shade(tube,r-half,r-half+r*2.4f);c.drawRect(0f,0f,w,h,paint);paint.shader=null}
        polygon(true,backPath,true)
        c.save();c.translate(nx*band*.25f,ny*band*.25f);c.clipPath(backPath);paint.shader=shade(cast,-band*2,band);c.drawRect(0f,0f,w,h,paint);paint.shader=null;c.restore()
        var v=0
        for(row in 0..ROWS)for(col in 0..COLS){val x=w*col/COLS;val y=h*row/ROWS;val d=(x-ax)*nx+(y-ay)*ny
            // proj: where a point at roll-distance dd ends up along n (flat front, around the roll, then lying flat on its back)
            fun place(dd:Float,out:FloatArray){val proj=when{dd<=0f->dd;r<1f->-dd;dd<2*half->r*sin(dd/r);else->2*half-dd};val off=-d+proj;out[v]=x+nx*off;out[v+1]=y+ny*off}
            val dF=min(d,half);place(dF,mesh);val dB=max(d,half);place(dB,backMesh)
            val vi=v/2
            // light: the front darkens as it turns away toward the top of the roll; the back is grey on the roll, bright when flat
            frontColors[vi]=if(d<=0f||r<1f)WHITE else grey(1f-.26f*sin(dF/r).pow(2.2f))
            backColors[vi]=if(r<1f||dB>=2*half)grey(.985f) else grey(.80f+.185f*(-cos(dB/r)).coerceAtLeast(0f).pow(.7f))
            v+=2}
        // front side of the sheet (the flat part and the lower half of the roll)
        c.drawBitmapMesh(sheet,COLS,ROWS,mesh,0,frontColors,0,paint)
        // back side: translucent paper (the page beneath shows faintly through), with the ink seen mirrored through it
        val lightPaper=Color.luminance(paper)>.4
        // (alpha baked into the texture: a paint's alpha is not applied to meshes on every renderer)
        if(inkBack==null)inkBack=Bitmap.createBitmap(sheet.width,sheet.height,Bitmap.Config.ARGB_8888).also{b->val cv=Canvas(b)
            cv.drawColor(paper or 0xff000000.toInt(),PorterDuff.Mode.SRC)
            cv.drawBitmap(sheet,0f,0f,Paint(Paint.FILTER_BITMAP_FLAG).apply{alpha=if(lightPaper)0x1e else 0x2a})}
        c.drawBitmapMesh(inkBack!!,COLS,ROWS,backMesh,0,backColors,0,paint)
        c.restore()
    }
}
