package app.polka.nativeapp

import android.graphics.RectF
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Lets a cover flying between a shelf and the book card pass BEHIND its shelf's glass: over the pane, the glass
 * draws the flying cover's frosted copy, and the card leaves that rectangle out of its own drawing.
 */
internal object ShelfGlassLink {
    interface Pane { val home:View; fun paneRectOnScreen():RectF?; fun ghostMoved() }
    private val panes=Collections.newSetFromMap(WeakHashMap<Pane,Boolean>())
    fun register(p:Pane){panes.add(p)}
    fun unregister(p:Pane){panes.remove(p);if(pane===p){pane=null;ghost=null}}
    var pane:Pane?=null;private set
    var ghost:CoverView?=null;private set
    val ghostRect=RectF()
    /** 0 — the flying cover is still in its slot, behind the glass; 1 — lifted off the shelf, in front of everything. */
    var front=0f;private set
    fun begin(cover:View,flying:CoverView){end();val p=panes.firstOrNull{isInside(cover,it.home)}?:return;pane=p;ghost=flying}
    fun move(left:Float,top:Float,scale:Float,lift:Float){val g=ghost?:return;front=lift;ghostRect.set(left,top,left+g.width*scale,top+g.height*scale);pane?.ghostMoved()}
    fun end(){val p=pane;pane=null;ghost=null;p?.ghostMoved()}
    private fun isInside(v:View,parent:View):Boolean{var p=v.parent;while(p!=null){if(p===parent)return true;p=p.parent};return false}
}
