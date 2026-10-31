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
    var onPose:((Float)->Unit)?=null
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val frontPath=Path();private val backPath=Path();private val shadowPath=Path()
    private val reflection=Matrix();private val matrixValues=FloatArray(9)
    private val xs=FloatArray(8);private val ys=FloatArray(8);private var points=0
    private val mesh=FloatArray((24+1)*(32+1)*2)
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
    fun pose(p:Float,y:Float,anchor:Float=anchorY){progress=p.coerceIn(0f,1f);touchY=y.coerceIn(0f,sourceBitmap.height.toFloat());anchorY=snapAnchor(anchor);invalidate();onPose?.invoke(progress)}
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
        // T moves twice as far as the crease. Vertical bending follows the hand,
        // but vanishes at both endpoints, making exact bitmap-to-live handoff.
        val cy=anchorY.coerceIn(0f,h);val tx=w-2*w*fold
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
        c.save();c.clipPath(frontPath)
        // A small cylindrical strip compresses ink into the bend. The rest of the
        // mesh is identity: stationary text stays pixel-stable and doesn't swim.
        var vertex=0
        for(row in 0..32)for(col in 0..24){val x=w*col/24f;val y=h*row/32f;val d=(x-mx)*nx+(y-my)*ny
            val bow=if(d<0&&d> -band)band*.3f*sin(Math.PI*(d+band)/band).toFloat()else 0f
            mesh[vertex++]=x+nx*bow;mesh[vertex++]=y+ny*bow
        }
        c.drawBitmapMesh(sheet,24,32,mesh,0,null,0,paint)
        // Narrow cylindrical bend on the front surface, no full-screen grey fill.
        paint.shader=shade(frontShade,-band,0f);c.drawRect(0f,0f,w,h,paint);paint.shader=null;c.restore()
        c.save();c.clipPath(backPath)
        paint.color=paper;c.drawPath(backPath,paint)
        // The reverse carries faint mirrored ink through the paper, not a second
        // readable front face. Reflection has determinant -1, so type reverses.
        matrixValues[0]=1-2*nx*nx;matrixValues[1]=-2*nx*ny;matrixValues[2]=2*nx*(nx*mx+ny*my)
        matrixValues[3]=-2*nx*ny;matrixValues[4]=1-2*ny*ny;matrixValues[5]=2*ny*(nx*mx+ny*my)
        matrixValues[6]=0f;matrixValues[7]=0f;matrixValues[8]=1f;reflection.setValues(matrixValues)
        paint.alpha=32;c.drawBitmap(sheet,reflection,paint);paint.alpha=255
        val lightPaper=Color.luminance(paper)>.4
        paint.shader=shade(reverseShade,-band*2,0f)
        c.drawRect(0f,0f,w,h,paint);paint.shader=null;c.restore()
        paint.color=if(lightPaper)0x3fffffff else 0x25ffffff;paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density*.65f;c.drawPath(backPath,paint);paint.style=Paint.Style.FILL
        c.restore()
    }
}
