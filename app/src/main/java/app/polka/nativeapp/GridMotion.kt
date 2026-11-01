package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*

/**
 * Library grid changes (a book added, a book deleted) without a jump: before the page is rebuilt every visible cell's
 * position is recorded; after the rebuild the same books glide from where they were to where they are now, new books
 * grow in, and a deleted book fades and shrinks away in its old place.
 */
internal class GridSnapshot(val cells:Map<String,RectF>,val ghosts:List<Pair<RectF,Bitmap>>,val scrollY:Int)

private fun collectCells(v:View,out:MutableMap<String,View>){val t=v.tag;if(t is String&&t.startsWith("grid:")&&v.isShown)out[t.removePrefix("grid:")]=v;if(v is ViewGroup)for(i in 0 until v.childCount)collectCells(v.getChildAt(i),out)}
private fun MainActivity.screenRect(v:View):RectF{val at=IntArray(2);val r=IntArray(2);v.getLocationInWindow(at);root.getLocationInWindow(r);val x=(at[0]-r[0]).toFloat()+0f;val y=(at[1]-r[1]).toFloat();return RectF(x,y,x+v.width,y+v.height)}

/** Called by render() right before the page is torn down. */
internal fun MainActivity.captureGrid(keep:Set<String>):GridSnapshot{
    val found=HashMap<String,View>();collectCells(root,found)
    val cells=HashMap<String,RectF>();val ghosts=mutableListOf<Pair<RectF,Bitmap>>()
    val visible=Rect(0,0,root.width,root.height)
    found.forEach{(id,v)->val r=screenRect(v);if(!RectF(visible).let{RectF.intersects(it,r)})return@forEach;cells[id]=r
        if(id !in keep&&v.width>0&&v.height>0)runCatching{val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));ghosts.add(r to b)}}
    return GridSnapshot(cells,ghosts,if(::scroll.isInitialized)scroll.scrollY else 0)
}

/** Called by render() after the new page is in place: animate from the snapshot on the first frame. */
internal fun MainActivity.playGrid(snap:GridSnapshot,restoreY:Int?){
    val reduced=prefs.str("motion")=="reduced"
    root.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
        if(root.viewTreeObserver.isAlive)root.viewTreeObserver.removeOnPreDrawListener(this)
        if(restoreY!=null&&::scroll.isInitialized)scroll.scrollTo(0,restoreY)
        val now=HashMap<String,View>();collectCells(root,now)
        val settle=PathInterpolator(.2f,0f,0f,1f)
        var fresh=0
        now.forEach{(id,v)->val old=snap.cells[id]
            if(old!=null){if(reduced)return@forEach;val r=screenRect(v);val dx=old.left-r.left;val dy=old.top-r.top;if(kotlin.math.abs(dx)<1f&&kotlin.math.abs(dy)<1f)return@forEach
                v.translationX=dx;v.translationY=dy;v.animate().translationX(0f).translationY(0f).setStartDelay(if(snap.ghosts.isEmpty())0L else 90L).setDuration(520).setInterpolator(settle).start()}
            else if(freshBookIds.remove(id)&&!reduced){val k=fresh++;v.alpha=0f;v.scaleX=.86f;v.scaleY=.86f;v.pivotY=v.height*.4f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(220L+k*70L).setDuration(520).setInterpolator(PathInterpolator(.2f,1.2f,.36f,1f)).start()}
        }
        snap.ghosts.forEach{(r,b)->val g=ImageView(this@playGrid).apply{setImageBitmap(b);elevation=d(2).toFloat()}
            root.addView(g,FrameLayout.LayoutParams(r.width().toInt(),r.height().toInt()).apply{leftMargin=r.left.toInt();topMargin=r.top.toInt()})
            if(reduced){root.removeView(g);b.recycle();return@forEach}
            g.pivotX=r.width()/2;g.pivotY=r.height()*.4f
            g.animate().alpha(0f).scaleX(.82f).scaleY(.82f).setDuration(300).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{root.removeView(g);b.recycle()}.start()}
        return true
    }})
}
