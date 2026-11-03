package app.polka.nativeapp

import android.graphics.RectF
import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Lets a cover flying between a shelf and the book card pass BEHIND its shelf's glass instead of over it:
 * the card tells the glass where the flying cover is; the glass draws it through its lens, and the card leaves
 * that part out of its own drawing.
 */
internal object ShelfGlassLink {
    interface Pane { val home:View; fun paneRectOnScreen():RectF?; fun recapture(); fun ghostMoved() }
    private val panes=Collections.newSetFromMap(WeakHashMap<Pane,Boolean>())
    fun register(p:Pane){panes.add(p)}
    fun unregister(p:Pane){panes.remove(p)}
    /** The flying cover, its screen rect (unscaled view + scale), and the pane it belongs to. */
    var ghost:View?=null;private set
    val ghostRect=RectF();var ghostScale=1f;private set
    var pane:Pane?=null;private set
    /** The cover on the shelf was hidden or shown: every pane holding it re-snaps at once. */
    fun coverToggled(cover:View){panes.filter{isInside(cover,it.home)}.forEach{it.recapture()}}
    fun begin(cover:View,flying:View){pane=panes.firstOrNull{isInside(cover,it.home)};ghost=if(pane!=null)flying else null}
    fun move(left:Float,top:Float,scale:Float){val g=ghost?:return;ghostScale=scale;ghostRect.set(left,top,left+g.width*scale,top+g.height*scale);pane?.ghostMoved()}
    fun end(){val p=pane;ghost=null;pane=null;p?.ghostMoved()}
    private fun isInside(v:View,parent:View):Boolean{var p=v.parent;while(p!=null){if(p===parent)return true;p=p.parent};return false}
}
