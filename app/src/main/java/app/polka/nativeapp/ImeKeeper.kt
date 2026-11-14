package app.polka.nativeapp

import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.ScrollView

/**
 * Keeps the line being typed above the keyboard. It runs right before a frame is drawn (after that frame's layout), so
 * while the keyboard slides the text rides up with it frame by frame instead of being covered and then jumping. It acts
 * only when something about the typing changed (the field, the caret, the text, the keyboard's height), so scrolling
 * away by hand while the keyboard is open is never undone.
 */
internal class ImeKeeper(private val host:View,private val margin:Int,private val ime:()->Int):ViewTreeObserver.OnPreDrawListener {
    private var seen=""
    fun attach():ImeKeeper{host.viewTreeObserver.addOnPreDrawListener(this);return this}
    override fun onPreDraw():Boolean {
        val f=host.findFocus() as? EditText
        val k=ime()
        if(f==null||k<=0||!f.isShown){seen="";return true}
        val now="${System.identityHashCode(f)}:${f.selectionEnd}:${f.length()}:${f.height}:$k"
        if(now==seen)return true
        seen=now
        ImeKeeper.keep(f,k,margin,false)
        return true
    }
    companion object {
        private fun scroller(v:View):ScrollView?{var p=v.parent;while(p is View){if(p is ScrollView)return p;p=p.parent};return null}
        /** Scrolls the nearest scroll view just enough that [f] (or, if it is taller than the room left, its caret line)
         *  sits [margin] above the keyboard. Never scrolls the other way. */
        fun keep(f:View,keyboard:Int,margin:Int,smooth:Boolean,extra:Int=0){
            val s=scroller(f)?:return
            val at=IntArray(2);f.getLocationInWindow(at)
            val box=IntArray(2);s.getLocationInWindow(box)
            val floor=minOf(f.rootView.height-keyboard,box[1]+s.height)
            val room=floor-box[1]-margin
            var bottom=f.height+extra
            if(f is EditText&&bottom>room){val l=f.layout
                if(l!=null){val line=l.getLineForOffset(maxOf(0,f.selectionEnd));bottom=f.totalPaddingTop+l.getLineBottom(line)-f.scrollY+f.totalPaddingBottom}}
            val over=at[1]+bottom+margin-floor
            if(over<=0)return
            if(smooth)s.smoothScrollBy(0,over) else s.scrollBy(0,over)
        }
    }
}

/** Gently brings [v] (and [extra] px under it) into the room above the keyboard, as it is right now. */
internal fun MainActivity.revealAboveKeyboard(v:View,extra:Int){if(v.isAttachedToWindow)ImeKeeper.keep(v,imeNow,d(16),true,extra)}
