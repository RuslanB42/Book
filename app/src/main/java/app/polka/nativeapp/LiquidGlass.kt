package app.polka.nativeapp

import android.graphics.*
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Liquid-glass lens for the shelf pane (Android 13+, AGSL), applied as a RenderEffect to the live layer of covers:
 * the GPU bends the row as it is drawn, every frame — no snapshots, so nothing lags, flickers or goes stale.
 * Outside the pane the covers pass through untouched. Inside, the middle magnifies a touch; toward the rim the
 * surface curves, the picture bends outward with a faint colour split, light catches the upper rim and the lower
 * rim falls into shade.
 */
internal object LiquidGlass {
    private const val SRC = """
uniform shader content;
uniform float4 rect;       // pane in layer coordinates: left, top, right, bottom
uniform float radius;
uniform float bevel;
uniform float strength;
uniform float zoom;
uniform float lightMode;

float sdBox(float2 p, float2 b, float r){ float2 q=abs(p)-b+r; return length(max(q,0.0))+min(max(q.x,q.y),0.0)-r; }

half4 main(float2 p){
    float2 size=rect.zw-rect.xy; float2 c=size*0.5; float2 rel=(p-rect.xy)-c;
    if(sdBox(rel,c,radius)>0.0) return content.eval(p);           // outside the pane: untouched
    // Bend measured against a slab with a corner radius of at least the bevel, so the inner contours stay round
    // all the way in and the bend turns smoothly around the corners (no diagonal creases).
    float rs=min(max(radius,bevel*1.15),min(c.x,c.y));
    float d=sdBox(rel,c,rs);
    float t=clamp(-d/bevel,0.0,1.0);
    float bend=smoothstep(0.0,1.0,1.0-t); bend*=bend;
    float e=0.75;
    float2 n=float2(sdBox(rel+float2(e,0),c,rs)-sdBox(rel-float2(e,0),c,rs),
                    sdBox(rel+float2(0,e),c,rs)-sdBox(rel-float2(0,e),c,rs));
    n=n/max(length(n),1e-4);
    float2 base=rect.xy+c+rel*zoom;
    float2 off=n*bend*strength;
    half4 g=content.eval(base+off*1.06);
    half r=content.eval(base+off*1.00).r;
    half b=content.eval(base+off*1.12).b;
    half4 col=half4(r,g.g,b,g.a);
    float lit=max(dot(n,normalize(float2(-0.35,-1.0))),0.0);
    float shade=max(dot(n,float2(0.0,1.0)),0.0);
    half h=half(bend*bend*lit*(lightMode>0.5?0.50:0.34));
    col=half4(col.rgb*half(1.0-bend*shade*(lightMode>0.5?0.10:0.22))+h, max(col.a,h));
    return col;
}
"""
    @Volatile var failed=false

    /** Sets (or clears) the lens on [host]; [pane] is the glass rect in [host]'s coordinates. */
    fun apply(host:View,pane:RectF?,radius:Float,light:Boolean,density:Float){
        if(android.os.Build.VERSION.SDK_INT<33||failed)return
        try{apply33(host,pane,radius,light,density)}catch(e:Throwable){failed=true;try{host.setRenderEffect(null)}catch(_:Throwable){}}
    }
    @RequiresApi(33) private fun apply33(host:View,pane:RectF?,radius:Float,light:Boolean,density:Float){
        if(pane==null||pane.width()<2||pane.height()<2){host.setRenderEffect(null);return}
        val s=RuntimeShader(SRC)
        s.setFloatUniform("rect",pane.left,pane.top,pane.right,pane.bottom)
        s.setFloatUniform("radius",radius)
        s.setFloatUniform("bevel",16f*density)
        s.setFloatUniform("strength",9f*density)
        s.setFloatUniform("zoom",.955f)
        s.setFloatUniform("lightMode",if(light)1f else 0f)
        host.setRenderEffect(RenderEffect.createRuntimeShaderEffect(s,"content"))
    }
    val available get()=android.os.Build.VERSION.SDK_INT>=33&&!failed
}
