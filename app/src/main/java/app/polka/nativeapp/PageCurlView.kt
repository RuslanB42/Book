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
    private fun gray(g:Float):Int{val v=(g.coerceIn(0f,1f)*255).toInt();return Color.argb(255,v,v,v)}
    private companion object{const val ARC=40;const val MAXS=ARC+8;const val FILTERS=64}
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
    /** Within ~30% of a corner the grab point is pulled into that corner (classic diagonal curl); elsewhere it stays where the finger landed. */
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
    // ---- Fold-aligned strips ------------------------------------------------------------------
    // The sheet is cut into thin strips parallel to the crease. Each strip is the page bitmap drawn through
    // ONE affine map (a scale along the fold normal only), so ink is never sheared; the bend is a smooth
    // 1-D profile of the distance d from the crease. Plain clipPath + drawBitmap, nothing exotic.
    private val ds=FloatArray(MAXS);private val quad=Path();private val sv=FloatArray(9);private val stripMatrix=Matrix()
    private val stripPaint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val filters=arrayOfNulls<ColorFilter>(FILTERS+1)
    private fun filterFor(g:Float):ColorFilter?{val q=(g.coerceIn(0f,1f)*FILTERS).roundToInt();if(q>=FILTERS)return null;return filters[q]?:PorterDuffColorFilter(gray(q/FILTERS.toFloat()),PorterDuff.Mode.MULTIPLY).also{filters[q]=it}}
    private fun sideFront(d:Float,r:Float,half:Float,apex:Float)=if(d<=-half)d else if(d<=0f)-half+r*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else apex
    private fun shadeFront(d:Float,r:Float,half:Float)=if(d<=-half)1f else if(d<=0f)1f-.30f*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else .70f
    private fun sideBack(d:Float,r:Float,half:Float,apex:Float)=if(d<0f)apex else if(d<=half)-half+r*sin(((d+half)/r).coerceIn(0f,PI.toFloat())) else -d
    private fun shadeBack(d:Float,r:Float,half:Float)=if(d<0f).70f else if(d<=half).70f+.30f*(-cos(((d+half)/r).coerceIn(0f,PI.toFloat()))) else 1f
    /** Sorted distinct distances in [lo,hi]: both ends, the cylinder foot points and a fine arc across the bend. */
    private fun samples(lo:Float,hi:Float,half:Float,r:Float):Int{
        var n=0
        fun add(d:Float){if(d<lo||d>hi||n>=MAXS-1)return;if(n==0||d>ds[n-1]+.05f)ds[n++]=d}
        add(lo);add(-half)
        for(i in 1..ARC)add(-half+r*PI.toFloat()*i/ARC) // ARC is even: the crest (d=0) and far foot (d=half) are exact samples
        add(hi);if(n>0&&ds[n-1]<hi-.05f&&n<MAXS)ds[n++]=hi
        return n
    }
    private fun drawStrips(c:Canvas,sheet:Bitmap,w:Float,h:Float,r:Float,half:Float,apex:Float){
        var dmin=Float.MAX_VALUE;var dmax=-Float.MAX_VALUE;var smin=Float.MAX_VALUE;var smax=-Float.MAX_VALUE
        for(i in 0..3){val x=if(i==1||i==2)w else 0f;val y=if(i>=2)h else 0f;val d=(x-mx)*nx+(y-my)*ny;val s=-(x-mx)*ny+(y-my)*nx
            dmin=min(dmin,d);dmax=max(dmax,d);smin=min(smin,s);smax=max(smax,s)}
        smin-=4f;smax+=4f
        fun run(lo:Float,hi:Float,front:Boolean){
            if(hi-lo<.1f)return;val n=samples(lo,hi,half,r);if(n<2)return
            val ext=if(front)1.2f else -1.2f // each strip slightly overlaps the next one, which is painted over it: no seams
            for(i in 0 until n-1){val a=ds[i];val b=ds[i+1]
                val pa=if(front)sideFront(a,r,half,apex) else sideBack(a,r,half,apex);val pb=if(front)sideFront(b,r,half,apex) else sideBack(b,r,half,apex)
                val k=(pb-pa)/(b-a);val g=if(front)shadeFront((a+b)/2,r,half) else shadeBack((a+b)/2,r,half)
                val a00=k*nx*nx+ny*ny;val a01=(k-1f)*nx*ny;val a11=k*ny*ny+nx*nx;val off=pa-k*a
                sv[0]=a00;sv[1]=a01;sv[2]=mx-(a00*mx+a01*my)+nx*off;sv[3]=a01;sv[4]=a11;sv[5]=my-(a01*mx+a11*my)+ny*off;sv[6]=0f;sv[7]=0f;sv[8]=1f;stripMatrix.setValues(sv)
                var q0=min(pa,pb);var q1=max(pa,pb);if(ext>0)q1+=ext else q0+=ext
                quad.rewind();quad.moveTo(mx+nx*q0-ny*smin,my+ny*q0+nx*smin);quad.lineTo(mx+nx*q1-ny*smin,my+ny*q1+nx*smin);quad.lineTo(mx+nx*q1-ny*smax,my+ny*q1+nx*smax);quad.lineTo(mx+nx*q0-ny*smax,my+ny*q0+nx*smax);quad.close()
                c.save();c.clipPath(quad)
                if(!front){ // paper colour first, then the faint mirrored ink
                    stripPaint.colorFilter=null;stripPaint.style=Paint.Style.FILL;stripPaint.color=shaded(paper,g);c.drawPath(quad,stripPaint)
                    c.concat(stripMatrix);stripPaint.color=Color.WHITE;stripPaint.alpha=34;stripPaint.colorFilter=filterFor(g);c.drawBitmap(sheet,0f,0f,stripPaint)
                } else {c.concat(stripMatrix);stripPaint.color=Color.WHITE;stripPaint.alpha=255;stripPaint.colorFilter=filterFor(g);c.drawBitmap(sheet,0f,0f,stripPaint)}
                c.restore()
            }
        }
        // Front: the sheet still attached (d <= 0): flat, then wrapping over the cylinder up to the crest.
        c.save();c.clipPath(frontPath);run(dmin,min(0f,dmax),true);c.restore()
        // Back: the turned sheet (d >= 0): down the far side of the cylinder, then flat and reversed.
        run(max(0f,dmin),dmax,false)
        stripPaint.colorFilter=null;stripPaint.alpha=255
    }
    private fun shaded(color:Int,g:Float)=Color.rgb((Color.red(color)*g).toInt(),(Color.green(color)*g).toInt(),(Color.blue(color)*g).toInt())
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
        // True cylinder curl. Along the fold normal d the paper stays flat up to -half, wraps a half
        // cylinder of radius r (arc length pi*r), then lies flat again, reversed. Foreshortening
        // is a pure function of d, so ink compresses smoothly and stationary text stays pixel-exact.
        val r=w*(.012f+.06f*sin(Math.PI*fold).toFloat());val half=(PI*r/2).toFloat();val apex=-half+r
        drawStrips(c,sheet,w,h,r,half,apex)
        val lightPaper=Color.luminance(paper)>.4
        paint.color=if(lightPaper)0x3fffffff else 0x25ffffff;paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density*.65f;c.drawPath(backPath,paint);paint.style=Paint.Style.FILL
        c.restore()
    }
}
