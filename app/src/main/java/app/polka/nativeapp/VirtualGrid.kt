package app.polka.nativeapp

import android.content.Context
import android.graphics.Rect
import android.view.*

/** One scroll owner. Offscreen rows remain geometry, including during a reorder. */
class VirtualGrid(context:Context,private val count:Int,private val columns:Int,private val gap:Int,private val rowGap:Int,private val rowHeight:(Int,Int)->Int,private val factory:(Int)->View):ViewGroup(context) {
    private val live=sortedMapOf<Int,View>()
    private var offsets=IntArray(0);private var sizes=IntArray(0);private var cellWidth=0;private var measuredWidthKey=-1
    internal var pinned:View?=null
    internal var reorder:ShelfReorder?=null
    val materializedCount get()=live.size
    private val scrollListener=ViewTreeObserver.OnScrollChangedListener { sync();invalidate() }
    init { clipChildren=false;clipToPadding=false }
    override fun onAttachedToWindow(){super.onAttachedToWindow();viewTreeObserver.addOnScrollChangedListener(scrollListener);post { sync() }}
    override fun onDetachedFromWindow(){reorder?.cancel();if(viewTreeObserver.isAlive)viewTreeObserver.removeOnScrollChangedListener(scrollListener);super.onDetachedFromWindow()}
    override fun dispatchTouchEvent(e:MotionEvent):Boolean {
        // A lift already cancelled the child click. Own the FIRST move/up too;
        // ViewGroup interception alone sends that first event only as child CANCEL.
        val drag=reorder
        if(drag?.active==true){drag.touch(e);return true}
        return super.dispatchTouchEvent(e)
    }
    internal fun cancelChildTouch(){val now=android.os.SystemClock.uptimeMillis();val e=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,0f,0f,0);super.dispatchTouchEvent(e);e.recycle()}
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean=reorder?.intercept(e)?:false
    override fun onTouchEvent(e:MotionEvent):Boolean=if(reorder?.touch(e)==true)true else super.onTouchEvent(e)
    private fun geometry(width:Int){measuredWidthKey=width;cellWidth=((width-gap*(columns-1))/columns).coerceAtLeast(1);val rows=(count+columns-1)/columns;sizes=IntArray(rows){rowHeight(it,cellWidth)};offsets=IntArray(rows);for(i in 1 until rows)offsets[i]=offsets[i-1]+sizes[i-1]+rowGap}
    private fun measureCells(){live.forEach{(i,v)->v.measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(sizes[i/columns],MeasureSpec.AT_MOST))}}
    override fun onMeasure(ws:Int,hs:Int){val width=MeasureSpec.getSize(ws);if(measuredWidthKey!=width)geometry(width);setMeasuredDimension(width,if(sizes.isEmpty())0 else offsets.last()+sizes.last());if(live.isEmpty()&&count>0)materialize(0,minOf(sizes.lastIndex,2));measureCells()}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){sync();place()}
    private fun materialize(first:Int,last:Int){if(first>last)return;for(row in first..last)for(col in 0 until columns){val i=row*columns+col;if(i>=count||live.containsKey(i))continue;val child=factory(i);live[i]=child;addViewInLayout(child,-1,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT),true);child.measure(MeasureSpec.makeMeasureSpec(cellWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(sizes[row],MeasureSpec.AT_MOST))}}
    fun sync(){if(offsets.isEmpty())return;val at=IntArray(2);getLocationOnScreen(at)
        // Clip against the actual scroll viewport, not just physical display metrics.
        val host=generateSequence(parent){it.parent}.filterIsInstance<android.widget.ScrollView>().firstOrNull()
        val bounds=Rect();if(host!=null)host.getGlobalVisibleRect(bounds)else bounds.set(0,0,resources.displayMetrics.widthPixels,resources.displayMetrics.heightPixels)
        val extra=bounds.height()/2;val top=(bounds.top-at[1]-extra).coerceAtLeast(0);val bottom=bounds.bottom-at[1]+extra
        if(bottom<0||top>height){live.filterValues{it!==pinned}.keys.toList().forEach{removeViewInLayout(live.remove(it))};return}
        var first=offsets.binarySearch(top);if(first<0)first=(-first-2).coerceAtLeast(0);var last=offsets.binarySearch(bottom);if(last<0)last=(-last-1).coerceAtMost(offsets.lastIndex)
        live.keys.toList().filter{it/columns !in first..last&&live[it]!==pinned}.forEach{removeViewInLayout(live.remove(it))};materialize(first,last);place()
    }
    private fun place(){live.forEach{(i,v)->val x=(i%columns)*(cellWidth+gap);val y=offsets[i/columns];v.layout(x,y,x+cellWidth,y+v.measuredHeight)}}
    internal fun cellAt(x:Float,y:Float):Pair<Int,View>?=live.entries.firstOrNull{(_,v)->x>=v.left&&x<v.right&&y>=v.top&&y<v.bottom}?.let{it.key to it.value}
    internal fun slot(index:Int)=Rect((index%columns)*(cellWidth+gap),offsets[index/columns],(index%columns)*(cellWidth+gap)+cellWidth,offsets[index/columns]+sizes[index/columns])
    internal fun viewAt(index:Int)=live[index]
    internal fun nearest(cx:Float,cy:Float,current:Int):Int {if(count==0)return current;fun distance(i:Int):Float{val r=slot(i);val dx=cx-r.exactCenterX();val dy=cy-(r.top+cellWidth*.75f);return kotlin.math.sqrt(dx*dx+dy*dy)};val best=(0 until count).minByOrNull(::distance)?:current;return if(distance(best)<distance(current)-cellWidth*.18f)best else current}
    /** FLIP neighbours from their PRESENTATION position, never from the previous target. */
    internal fun move(from:Int,to:Int,mutate:()->Unit,animate:Boolean){if(from==to)return
        val before=live.values.associateWith{floatArrayOf(it.left+it.translationX,it.top+it.translationY)}
        val remap=sortedMapOf<Int,View>();live.forEach{(i,v)->val next=when{ i==from->to;from<to&&i in from+1..to->i-1;to<from&&i in to until from->i+1;else->i };remap[next]=v};live.clear();live.putAll(remap)
        mutate();geometry(width);measureCells();place();requestLayout()
        live.values.forEach{v->val old=before[v]?:return@forEach;v.animate().cancel();v.translationX=old[0]-v.left;v.translationY=old[1]-v.top;if(animate&&v!==pinned)v.animate().translationX(0f).translationY(0f).setDuration(260).setInterpolator(MotionSpec.settle).start()else{v.translationX=0f;v.translationY=0f}}
    }
}
