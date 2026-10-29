package app.polka.nativeapp

import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Insets are pixels from Android, never fixed dp guesses; bars/cutout/IME overlap, not add. */
internal object SafeArea {
    fun content(i:WindowInsetsCompat,keyboard:Boolean=true):Insets {
        val bars=i.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val cut=i.displayCutout
        val gestures=i.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
        val ime=if(keyboard)i.getInsets(WindowInsetsCompat.Type.ime())else Insets.NONE
        return Insets.of(maxOf(bars.left,cut?.safeInsetLeft?:0,gestures.left),maxOf(bars.top,cut?.safeInsetTop?:0,gestures.top),maxOf(bars.right,cut?.safeInsetRight?:0,gestures.right),maxOf(bars.bottom,cut?.safeInsetBottom?:0,gestures.bottom,ime.bottom))
    }
    fun bind(view:View,apply:(WindowInsetsCompat)->Unit){
        ViewCompat.setOnApplyWindowInsetsListener(view){_,i->apply(i);WindowInsetsCompat.CONSUMED}
        view.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){ViewCompat.requestApplyInsets(v)};override fun onViewDetachedFromWindow(v:View){}})
        if(view.isAttachedToWindow)ViewCompat.requestApplyInsets(view)
    }
}
