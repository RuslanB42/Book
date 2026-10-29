package app.polka.nativeapp

import kotlin.math.*
/** Coordinates are CSS px / Android dp. Mirrors recFlyOut, not Android fling physics. */
internal class RecommendationFlight(w:Float,h:Float,action:String,private val dx:Float=0f,private val dy:Float=0f,vx:Float=0f,vy:Float=0f,side:Float=1f,private val drag:Boolean=false) {
 data class Pose(val x:Float,val y:Float,val rotation:Float)
 val end=Pose(if(action=="read")dx+if(drag)vx*120 else 0f else (w+160)*(if(action=="want")1 else -1),if(action=="read")-h*.9f-80 else dy+if(drag)(vy*260).coerceIn(-300f,300f)else 50f,if(action=="read")dx*.06f*side else (if(action=="want")28 else -28)*side)
 private val start=Pose(dx,dy,dx*.06f*side)
 private val anticipation=Pose(if(action=="read")0f else if(action=="want")18f else -18f,if(action=="read")-16f else 0f,if(action=="read")0f else if(action=="want")3f else -3f)
 val duration=if(drag)(hypot(end.x-dx,end.y-dy)/maxOf(1.1f,hypot(vx,vy))).toLong().coerceIn(200L,420L)else 460L
 /** Input is eased overall fraction; WAAPI distributes the middle key at offset .25. */
 fun sample(f:Float):Pose {val t=f.coerceIn(0f,1f);val a:Pose;val b:Pose;val p:Float;if(drag){a=start;b=end;p=t}else if(t<=.25f){a=Pose(0f,0f,0f);b=anticipation;p=t/.25f}else{a=anticipation;b=end;p=(t-.25f)/.75f};return Pose(a.x+(b.x-a.x)*p,a.y+(b.y-a.y)*p,a.rotation+(b.rotation-a.rotation)*p)}
}
