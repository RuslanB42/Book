package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.RenderEffect
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.max

/**
 * The reader's bottom panel as the one place for its tools (approved «Читалка А: панель перестраивается через
 * размытие»): text settings, sounds and page editing open inside it instead of in windows of their own. The panel's
 * rows blur away, it takes the new height on a little spring, and the new rows come out of a blur one after another;
 * «‹» (or Back, or a tap on the page) runs it back. Pages stack («Текст» → «Ещё»).
 */
internal class ReaderPanel(private val a:MainActivity,private val surface:LinearLayout){
    /** Every page keeps the page's colours ([MainActivity.readerTint]). [onReturn]: the page is on top again after the
     *  one over it went back. Without a [title] there is no head (the editor's own buttons lead out). */
    class Page(val title:String?,val content:View,val right:View?=null,val onReturn:(()->Unit)?=null)
    /** The reader's own rows (chapter, slider, tools). */
    val main=a.column()
    val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    private val stack=ArrayList<Pair<Page,View>>()
    val depth get()=stack.size
    val view:View get()=surface
    private var clock:ValueAnimator?=null;private var settle:(()->Unit)?=null
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{stage.addView(main,FrameLayout.LayoutParams(-1,-2,Gravity.TOP));surface.clipChildren=false}

    fun push(page:Page){val from=top();val holder=holder(page);stack.add(page to holder);switch(from,holder,false)}
    /** The top page changes for another in its place (the same move). */
    fun replace(page:Page){val old=stack.removeLastOrNull()?:return push(page);val holder=holder(page);stack.add(page to holder);switch(old.second,holder,true)}
    /** The top page built anew in place, at once (a theme change: the reader's crossfade covers it). */
    fun rebuild(page:Page){val old=stack.removeLastOrNull()?:return;stage.removeView(old.second);val holder=holder(page);stack.add(page to holder);holder.visibility=View.VISIBLE}
    fun back(){val old=stack.removeLastOrNull()?:return;switch(old.second,top(),true);stack.lastOrNull()?.first?.onReturn?.invoke()}
    /** Straight back to the reader's own rows, from however deep. */
    fun home(){if(stack.isEmpty())return;val old=stack.removeAt(stack.lastIndex);stack.forEach{(_,v)->stage.removeView(v)};stack.clear();switch(old.second,main,true)}
    private fun top():View=stack.lastOrNull()?.second?:main

    private fun holder(page:Page):LinearLayout{
        val box=a.column().apply{clipChildren=false;clipToPadding=false}
        val ink=a.fg
        // the one window head, as on every sheet: «‹» on the left, the title centred on the whole panel
        if(page.title!=null){(page.right?.parent as? ViewGroup)?.removeView(page.right)
            box.addView(a.windowHead(page.title,false,ink,Ui.fill(a.light),{back()},page.right),LinearLayout.LayoutParams(-1,a.d(Size.HEADER)).apply{leftMargin=-a.d(4);rightMargin=-a.d(4);bottomMargin=a.d(12)})}
        (page.content.parent as? ViewGroup)?.removeView(page.content)
        box.addView(page.content,LinearLayout.LayoutParams(-1,-2))
        stage.addView(box,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        // a page taller than most of the screen scrolls inside the panel, so the book still shows above it
        val width=stage.width.takeIf{it>0}?:(surface.width-surface.paddingLeft-surface.paddingRight)
        box.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val cap=((surface.parent as? View)?.height?:a.root.height)*58/100
        if(box.measuredHeight>cap){val headH=box.measuredHeight-page.content.measuredHeight;box.removeView(page.content)
            val color=a.readerTint?.panel?:Ui.sheet(a.light)
            // it cuts what scrolls past its top and foot, but reaches out to the panel's sides, where rings round chosen tiles sit
            val side=a.d(18)
            val scroll=SheetScroll(a,color,30,18).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;setPadding(side,0,side,0);addView(page.content)}
            box.addView(scroll,LinearLayout.LayoutParams(-1,max(a.d(120),cap-headH)).apply{leftMargin=-side;rightMargin=-side})}
        box.visibility=View.INVISIBLE
        return box
    }

    /** The rows that come and go one after another: the main rows, or a page's head and its content's rows. */
    private fun rows(layer:View):List<View>{
        if(layer===main)return (0 until main.childCount).map{main.getChildAt(it)}.filter{it.visibility==View.VISIBLE}
        val box=layer as ViewGroup;val out=ArrayList<View>()
        for(i in 0 until box.childCount){val c=box.getChildAt(i)
            if(c is LinearLayout&&c.orientation==LinearLayout.VERTICAL&&c.childCount in 2..8)for(j in 0 until c.childCount)c.getChildAt(j).takeIf{it.visibility==View.VISIBLE}?.let{out.add(it)}
            else out.add(c)}
        return out}

    private fun blur(v:View,r:Float){if(android.os.Build.VERSION.SDK_INT<31)return;v.setRenderEffect(if(r<.5f)null else RenderEffect.createBlurEffect(r,r,Shader.TileMode.DECAL))}
    private fun pose(v:View,shown:Float,dy:Float,radius:Float){v.alpha=shown;v.translationY=dy;blur(v,radius)}

    private fun switch(from:View,to:View,drop:Boolean){
        clock?.cancel();settle?.invoke();settle=null
        // the surface takes the page's colours (or the reader's again)
        to.visibility=View.VISIBLE
        val lp=stage.layoutParams
        val startH=stage.height
        val width=stage.width.takeIf{it>0}?:(surface.width-surface.paddingLeft-surface.paddingRight)
        to.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        val target=to.measuredHeight
        val out=rows(from);val come=rows(to)
        fun end(){out.forEach{pose(it,1f,0f,0f)};come.forEach{pose(it,1f,0f,0f)};from.visibility=View.GONE;if(drop)stage.removeView(from)
            lp.height=ViewGroup.LayoutParams.WRAP_CONTENT;stage.layoutParams=lp}
        if(reduced||startH<=0||!surface.isShown){end();return}
        settle={end()}
        come.forEach{pose(it,0f,a.d(8).toFloat(),a.d(8).toFloat())}
        lp.height=startH;stage.layoutParams=lp
        val spring=Spring.of(.22,420.0)
        val total=max(spring.duration,150L+45L*come.size+300L)
        var hidden=false
        clock=ValueAnimator.ofFloat(0f,total.toFloat()).apply{duration=total;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                if(!hidden){val k=MotionSpec.exit.getInterpolation((ms/160f).coerceIn(0f,1f));out.forEach{v->pose(v,1f-k,-a.d(6)*k,a.d(6)*k)}
                    if(ms>=160f){hidden=true;from.visibility=View.GONE}}
                lp.height=(startH+(target-startH)*spring.interpolator.getInterpolation((ms/spring.duration).coerceIn(0f,1f))).toInt().coerceAtLeast(0);stage.layoutParams=lp
                come.forEachIndexed{i,v->val u=MotionSpec.enter.getInterpolation(((ms-150f-45f*i)/300f).coerceIn(0f,1f));pose(v,u,a.d(8)*(1f-u),a.d(8)*(1f-u))}}
            addListener(object:android.animation.AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:android.animation.Animator){cancelled=true}
                override fun onAnimationEnd(x:android.animation.Animator){if(!cancelled){settle=null;end()}}})
            start()}
    }
}
