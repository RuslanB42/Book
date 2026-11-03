package app.polka.nativeapp

import android.graphics.*
import androidx.annotation.RequiresApi

/**
 * Liquid-glass lens for the shelf pane (Android 13+, AGSL). The pane is a rounded slab: in the middle it magnifies
 * the books a touch; toward the rim the surface curves, so the picture bends outward and compresses into the edge,
 * with a faint colour split on the curve; light catches the upper rim and the lower rim falls into shade.
 * The input is a snapshot of the row behind the pane; transparent parts show the page background.
 */
internal object LiquidGlass {
    private const val SRC = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float zoom;
layout(color) uniform half4 bg;
uniform float lightMode;

float sdBox(float2 p, float2 b, float r){ float2 q=abs(p)-b+r; return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r; }

half4 pick(float2 q){ half4 c=content.eval(q); return half4(c.rgb + bg.rgb*(1.0-c.a), 1.0); }

half4 main(float2 p){
    float2 c=size*0.5; float2 rel=p-c;
    // The bend is measured against a slab with a corner radius of at least the bevel: its inner contours stay
    // round all the way in, so the bend direction turns smoothly around the corners (no diagonal creases).
    float rs=min(max(radius,bevel*1.15),min(c.x,c.y));
    float d=sdBox(rel,c,rs);                           // <0 inside, 0 on the rim
    float t=clamp(-d/bevel,0.0,1.0);                   // 0 at rim → 1 past the bevel
    float bend=smoothstep(0.0,1.0,1.0-t); bend*=bend;   // curvature of the slab's edge, eased at both ends
    float e=0.75;
    float2 n=float2(sdBox(rel+float2(e,0),c,rs)-sdBox(rel-float2(e,0),c,rs),
                    sdBox(rel+float2(0,e),c,rs)-sdBox(rel-float2(0,e),c,rs));
    n=n/max(length(n),1e-4);                           // outward normal of the nearest rim
    float2 base=c+rel*zoom;                            // gentle lens magnification
    float2 off=n*bend*strength;                        // the rim pulls the picture outward
    half r=pick(base+off*1.00).r;
    half g=pick(base+off*1.06).g;
    half b=pick(base+off*1.12).b;                      // slight dispersion on the curve
    half3 col=half3(r,g,b);
    // light on the rim: bright where the curve faces up-left, shade where it faces down
    float lit=max(dot(n,normalize(float2(-0.35,-1.0))),0.0);
    float shade=max(dot(n,float2(0.0,1.0)),0.0);
    col+= half3(1.0)*half(bend*bend*lit*(lightMode>0.5?0.55:0.38));
    col*= half(1.0-bend*shade*(lightMode>0.5?0.10:0.22));
    return half4(col,1.0);
}
"""
    @RequiresApi(33) private fun make()=RuntimeShader(SRC)
    fun shader():Any?=if(android.os.Build.VERSION.SDK_INT>=33)try{make()}catch(e:Throwable){null} else null

    /** Points the lens at [snapshot]: its pixel (0,0) sits at ([ox],[oy]) in pane coordinates. */
    fun bind(lens:Any,paint:Paint,snapshot:Bitmap,ox:Float,oy:Float,w:Float,h:Float,radius:Float,bg:Int,light:Boolean,density:Float){
        if(android.os.Build.VERSION.SDK_INT<33)return
        try{bind33(lens as RuntimeShader,paint,snapshot,ox,oy,w,h,radius,bg,light,density)}catch(e:Throwable){paint.shader=null;failed=true}
    }
    /** Set if the device refused the shader; the shelf then falls back to the plain glass. */
    @Volatile var failed=false
    @RequiresApi(33) private fun bind33(s:RuntimeShader,paint:Paint,snapshot:Bitmap,ox:Float,oy:Float,w:Float,h:Float,radius:Float,bg:Int,light:Boolean,density:Float){
        // linear filtering: the lens magnifies, and nearest sampling made letters uneven
        val content=BitmapShader(snapshot,Shader.TileMode.DECAL,Shader.TileMode.DECAL).apply{filterMode=BitmapShader.FILTER_MODE_LINEAR;setLocalMatrix(Matrix().apply{setTranslate(ox,oy)})}
        s.setInputShader("content",content)
        s.setFloatUniform("size",w,h)
        s.setFloatUniform("radius",radius)
        s.setFloatUniform("bevel",16f*density)
        s.setFloatUniform("strength",9f*density)
        s.setFloatUniform("zoom",.955f)
        s.setColorUniform("bg",bg)
        s.setFloatUniform("lightMode",if(light)1f else 0f)
        paint.shader=s
    }
}
