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
    private val dp=context.resources.displayMetrics.density
    private val mesh=FloatArray((COLS+1)*(ROWS+1)*2)
    private val backMesh=FloatArray((COLS+1)*(ROWS+1)*2)
    private val frontColors=IntArray((COLS+1)*(ROWS+1))
    private val backColors=IntArray((COLS+1)*(ROWS+1))
    /** The sheet washed with paper colour: drawn on the back, its ink reads as text seen faintly through the paper. */
    private var inkBack:Bitmap?=null
    private val tube=LinearGradient(0f,0f,1f,0f,intArrayOf(0x38000000,0x16000000,0x05000000,0x00000000),floatArrayOf(0f,.25f,.6f,1f),Shader.TileMode.CLAMP)
    private fun grey(l:Float):Int{val g=(255*l.coerceIn(0f,1f)).toInt();return Color.rgb(g,g,g)}
    companion object{const val COLS=36;const val ROWS=48;val WHITE=Color.WHITE}
    private val shaderMatrix=Matrix()
    private val shaderValues=FloatArray(9)
    private fun shade(shader:Shader,from:Float,to:Float):Shader {
        val length=to-from
        shaderValues[0]=nx*length;shaderValues[1]=-ny;shaderValues[2]=mx+nx*from
        shaderValues[3]=ny*length;shaderValues[4]=nx;shaderValues[5]=my+ny*from
        shaderValues[6]=0f;shaderValues[7]=0f;shaderValues[8]=1f
        shaderMatrix.setValues(shaderValues);shader.setLocalMatrix(shaderMatrix);return shader
    }
    /** How the page turns: «curl» (the folding sheet), «slide» (both pages move together), «cover» (the new page slides
     *  in over the old one), «none» (the new page simply replaces the old). */
    var style="curl"
    private val edge=Paint(Paint.ANTI_ALIAS_FLAG)
    private fun flat(c:Canvas,p:Float,w:Float,h:Float){val dir=if(direction>0)1f else -1f
        when(style){
            // whole pixels and a paper-coloured ground, so no sliver of the real page underneath shows at the seam
            "slide"->{c.drawColor(paper);val sx=Math.round(-dir*w*p).toFloat();c.drawBitmap(sourceBitmap,sx,0f,paint);c.drawBitmap(targetBitmap,sx+dir*w,0f,paint)}
            "cover"->{c.drawBitmap(sourceBitmap,0f,0f,paint)
                // the old page darkens a little under the arriving one, which casts a soft shadow on it
                edge.shader=null;edge.color=Color.argb((70*p).toInt(),0,0,0);c.drawRect(0f,0f,w,h,edge)
                val x=Math.round(dir*w*(1-p)).toFloat();val sw=24*dp;val sx=if(dir>0)x else x+w
                edge.shader=LinearGradient(sx,0f,sx-dir*sw,0f,0x40000000,0x00000000,Shader.TileMode.CLAMP);c.drawRect(minOf(sx,sx-dir*sw),0f,maxOf(sx,sx-dir*sw),h,edge);edge.shader=null
                c.drawBitmap(targetBitmap,x,0f,paint)}
            else->c.drawBitmap(if(p<.5f)sourceBitmap else targetBitmap,0f,0f,paint)}}
    private var mx=0f;private var my=0f;private var nx=1f;private var ny=0f
    override fun onDetachedFromWindow(){inkBack?.recycle();inkBack=null;super.onDetachedFromWindow()}
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;setWillNotDraw(false) }
    /** The raw grab point is kept and snapped once per pose, so animation frames that reuse it never drift it further into the corner. */
    private var grabY=sourceBitmap.height.toFloat()
    fun pose(p:Float,y:Float,anchor:Float=grabY){progress=p.coerceIn(0f,1f);touchY=y.coerceIn(0f,sourceBitmap.height.toFloat());grabY=anchor;anchorY=snapAnchor(anchor);invalidate()}
    /** Within ~30% of a corner the grab point is pulled into that corner (the classic diagonal curl); elsewhere it stays where the finger landed. */
    private fun snapAnchor(y:Float):Float{val h=sourceBitmap.height.toFloat();if(h<=0f)return y;val v=y.coerceIn(0f,h);val rel=v/h;val k=(1f-min(rel,1f-rel)/.3f).coerceIn(0f,1f);return v+((if(rel<.5f)0f else h)-v)*k*k}
    override fun onDraw(c:Canvas){
        super.onDraw(c);val w=sourceBitmap.width.toFloat();val h=sourceBitmap.height.toFloat();if(w<=0||h<=0)return
        val p=progress;paint.reset();paint.isAntiAlias=true;paint.isFilterBitmap=true
        if(p<=.0001f){c.drawBitmap(sourceBitmap,0f,0f,paint);return}
        if(p>=.9999f){c.drawBitmap(targetBitmap,0f,0f,paint);return}
        c.save();c.clipRect(0f,0f,w,h)
        if(style!="curl"&&!reduced){flat(c,p,w,h);c.restore();return}
        if(reduced){c.drawBitmap(sourceBitmap,0f,0f,paint);c.save();if(direction>0)c.clipRect(w*(1-p),0f,w,h)else c.clipRect(0f,0f,w*p,h);c.drawBitmap(targetBitmap,0f,0f,paint);c.restore();c.restore();return}
        val sheet=if(direction>0)sourceBitmap else targetBitmap;val below=if(direction>0)targetBitmap else sourceBitmap
        val fold=if(direction>0)p else 1-p
        // The finger point T and the crease are as before (T moves twice as far as the crease), so the gesture feels the
        // same. The sheet now rolls over a cylinder of radius R whose flat-lying back meets the crease: like iOS books.
        val cy=anchorY.coerceIn(0f,h);val tx=w-2*w*fold
        val ty=cy+(touchY-cy)*sin(Math.PI*fold).toFloat()*.52f
        mx=(w+tx)*.5f;my=(cy+ty)*.5f
        val len=hypot(w-tx,cy-ty).coerceAtLeast(.01f);nx=(w-tx)/len;ny=(cy-ty)/len
        val r=(w*.11f*sin(Math.PI*fold).toFloat().pow(.6f)).coerceAtMost(len*.45f)
        val half=(Math.PI*r/2).toFloat()
        val ax=mx-nx*half;val ay=my-ny*half
        c.drawBitmap(below,0f,0f,paint)
        // Soft shadow of the roll on the page below, just outside the roll's silhouette.
        if(r>1f){paint.shader=shade(tube,r-half,r-half+r*2.4f);c.drawRect(0f,0f,w,h,paint);paint.shader=null}
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
