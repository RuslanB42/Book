package app.polka.nativeapp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Lets a cover flying between a shelf and the book card pass BEHIND its shelf's glass: over the pane, the flying
 * cover is drawn inside the shelf's live lens layer (so the glass bends it like the books on the shelf), and the
 * card leaves that rectangle out of its own drawing.
 */
internal object ShelfGlassLink {
    interface Pane { val home:View; val layer:View; fun paneRectOnScreen():RectF? }
    private val panes=Collections.newSetFromMap(WeakHashMap<Pane,Boolean>())
    fun register(p:Pane){panes.add(p)}
    fun unregister(p:Pane){panes.remove(p)}
    var pane:Pane?=null;private set
    private var ghost:View?=null
    private val ghostRect=RectF();private var ghostScale=1f
    private val drawable=object:Drawable(){
        override fun draw(c:Canvas){val g=ghost?:return;val p=pane?:return;val pr=p.paneRectOnScreen()?:return
            val at=IntArray(2);p.layer.getLocationOnScreen(at)
            val save=c.save();c.clipRect(pr.left-at[0],pr.top-at[1],pr.right-at[0],pr.bottom-at[1])
            c.translate(ghostRect.left-at[0],ghostRect.top-at[1]);c.scale(ghostScale,ghostScale);g.draw(c);c.restoreToCount(save)}
        override fun setAlpha(alpha:Int){}
        override fun setColorFilter(cf:ColorFilter?){}
        @Deprecated("Drawable") override fun getOpacity()=PixelFormat.TRANSLUCENT
    }
    fun begin(cover:View,flying:View){end();val p=panes.firstOrNull{isInside(cover,it.home)}?:return;pane=p;ghost=flying
        drawable.setBounds(0,0,p.layer.width,p.layer.height);p.layer.overlay.add(drawable)}
    fun move(left:Float,top:Float,scale:Float){val g=ghost?:return;ghostScale=scale;ghostRect.set(left,top,left+g.width*scale,top+g.height*scale);pane?.layer?.invalidate()}
    fun end(){val p=pane;pane=null;ghost=null;p?.layer?.let{it.overlay.remove(drawable);it.invalidate()}}
    private fun isInside(v:View,parent:View):Boolean{var p=v.parent;while(p!=null){if(p===parent)return true;p=p.parent};return false}
}
