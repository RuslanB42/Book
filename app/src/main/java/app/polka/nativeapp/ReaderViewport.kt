package app.polka.nativeapp

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView

/** Pages fold under the finger; focus/selection must never scroll their vertical viewport. */
class ReaderViewport(context:Context):ScrollView(context) {
 var lockPage:(()->Boolean)?=null
 private fun locked()=lockPage?.invoke()==true
 override fun scrollTo(x:Int,y:Int){if(locked())super.scrollTo(0,0)else super.scrollTo(x,y)}
 override fun onOverScrolled(scrollX:Int,scrollY:Int,clampedX:Boolean,clampedY:Boolean){if(locked())super.onOverScrolled(0,0,true,true)else super.onOverScrolled(scrollX,scrollY,clampedX,clampedY)}
 override fun requestChildRectangleOnScreen(child:View,rectangle:Rect,immediate:Boolean):Boolean=if(locked())false else super.requestChildRectangleOnScreen(child,rectangle,immediate)
 override fun onInterceptTouchEvent(event:MotionEvent):Boolean=if(locked())false else super.onInterceptTouchEvent(event)
 override fun onTouchEvent(event:MotionEvent):Boolean=if(locked())false else super.onTouchEvent(event)
 override fun fling(velocityY:Int){if(!locked())super.fling(velocityY)}
}
