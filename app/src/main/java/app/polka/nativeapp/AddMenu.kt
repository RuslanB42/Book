package app.polka.nativeapp

import android.view.*
import android.view.animation.*
import android.widget.*

/**
 * The «＋» button unfolds into a choice: add a book file or create a shelf. The plus turns into a cross, the page
 * dims, and the two pills rise out of the button one after another; tapping outside, the cross or «Назад» folds
 * them back into it.
 */
internal class AddMenu(private val a:MainActivity){
    private val overlay=FrameLayout(a)
    private val scrim=View(a)
    private val pills=ArrayList<View>()
    private var closing=false
    private val out=PathInterpolator(.2f,0f,0f,1f)
    /** Ease-out with a whisper of settle — smooth, no wobble. */
    private val rise=PathInterpolator(.22f,1.18f,.36f,1f)

    fun open(){
        val fab=a.addButton?:return;a.addMenu=this
        scrim.setBackgroundColor(if(a.light)0x66f2f2f0 else 0x99000000.toInt());scrim.alpha=0f;scrim.isClickable=true;scrim.setOnClickListener{close()}
        overlay.addView(scrim,FrameLayout.LayoutParams(-1,-1))
        val flp=fab.layoutParams as FrameLayout.LayoutParams
        val column=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.END;clipChildren=false;clipToPadding=false}
        overlay.addView(column,FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.END).apply{rightMargin=flp.rightMargin;bottomMargin=flp.bottomMargin+a.d(58)+a.d(14)})
        // nearest to the button last in the column, so it rises first
        listOf(Triple("shelves","Новая полка"){a.createShelf()},Triple("download","Добавить книгу"){a.pickBooks()}).forEach{(icon,label,act)->
            val pill=a.row().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(20),0);isClickable=true;isFocusable=true;contentDescription=label
                background=a.controlSurface(this,26f);elevation=a.d(12).toFloat()
                addView(IconView(a,icon,a.fg),LinearLayout.LayoutParams(a.d(24),a.d(24)))
                addView(a.text(label,16f,a.fg,true),LinearLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(10)})
                setOnClickListener{if(!closing){close();postDelayed({act()},170)}}}
            a.press(pill);pills.add(pill);pill.alpha=0f
            column.addView(pill,LinearLayout.LayoutParams(-2,a.d(52)).apply{topMargin=a.d(10)})
        }
        a.root.addView(overlay,a.root.indexOfChild(fab),FrameLayout.LayoutParams(-1,-1))
        val reduced=a.prefs.str("motion")=="reduced"
        scrim.animate().alpha(1f).setDuration(if(reduced)0 else 240).setInterpolator(out).start()
        fab.animate().rotation(45f).setDuration(if(reduced)0 else 320).setInterpolator(out).start()
        // Start poses are set after layout but BEFORE the first frame (setting them in a post showed the pills
        // at rest for a frame and then snapped them back — the flash).
        overlay.viewTreeObserver.addOnPreDrawListener(object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
            overlay.viewTreeObserver.removeOnPreDrawListener(this)
            pills.asReversed().forEachIndexed{i,p->
                // each pill grows out of the button: from its right edge, a little below, small and clear
                p.pivotX=p.width.toFloat();p.pivotY=p.height/2f
                p.translationY=(column.height-p.top-p.height/2f)*.35f+a.d(12);p.scaleX=.82f;p.scaleY=.82f;p.alpha=0f
                p.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(if(reduced)0 else 30L+i*45L).setDuration(if(reduced)0 else 340).setInterpolator(rise).start()}
            return true}})
    }

    fun close(){if(closing)return;closing=true
        val fab=a.addButton;val reduced=a.prefs.str("motion")=="reduced"
        fab?.animate()?.rotation(0f)?.setDuration(if(reduced)0 else 260)?.setInterpolator(out)?.start()
        pills.forEachIndexed{i,p->p.animate().cancel();p.animate().translationY(a.d(14).toFloat()).scaleX(.86f).scaleY(.86f).alpha(0f).setStartDelay(if(reduced)0 else i*30L).setDuration(if(reduced)0 else 180).setInterpolator(out).start()}
        scrim.animate().alpha(0f).setStartDelay(if(reduced)0 else 40).setDuration(if(reduced)0 else 220).withEndAction{(overlay.parent as? ViewGroup)?.removeView(overlay);if(a.addMenu===this)a.addMenu=null}.start()
        if(a.addMenu===this)a.addMenu=null
    }
}
