package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * A reader's colours (approved «Панель того же цвета, что и страница»): every theme of the book and every backdrop of
 * the comics has its own five — the page, the bottom panel, the cards in it, the ink and the secondary ink (4.5:1 or
 * more on the panel and on the cards). While a reader is open the app's colours are these (see
 * [MainActivity.readerTint]), so its panel, pages, sheets and questions all stay in the page's colours.
 */
internal class ReaderTint(val page:Int,val ink:Int,val panel:Int,val card:Int,val ink2:Int,val light:Boolean){
    companion object{
        private fun c(v:Long)=v.toInt()
        /** A book's theme («rt»); any other key: the app's own colours. */
        fun book(key:String,appLight:Boolean)=when(key){
            "night"->ReaderTint(c(0xff18181a),c(0xffdedcd6),c(0xff222224),c(0xff2c2c2f),c(0xff9a9893),false)
            "black"->ReaderTint(c(0xff000000),c(0xffc9c7c1),c(0xff121212),c(0xff1e1e1e),c(0xff8e8c88),false)
            "sepia"->ReaderTint(c(0xfff3ead8),c(0xff3a2e22),c(0xffebe0c8),c(0xfff8f1e3),c(0xff6b5843),true)
            "paper"->ReaderTint(c(0xfffbfaf7),c(0xff1e1d1b),c(0xfff1efe9),c(0xffffffff),c(0xff6a6862),true)
            else->app(appLight)}
        /** The comics' backdrop («comicBg»). */
        fun comic(key:String)=when(key){
            "graphite"->ReaderTint(c(0xff1c1c1e),c(0xfff3f2ef),c(0xff262628),c(0xff303033),c(0xff9a9893),false)
            "white"->ReaderTint(c(0xfff3f2ef),c(0xff1e1d1b),c(0xffe9e8e4),c(0xffffffff),c(0xff5f5f63),true)
            else->ReaderTint(c(0xff000000),c(0xfff3f2ef),c(0xff121212),c(0xff1e1e1e),c(0xff8e8c88),false)}
        fun app(light:Boolean)=ReaderTint(Ui.bg(light),Ui.ink(light),Ui.sheet(light),Ui.card(light),Ui.ink2(light),light)
    }
}

/** A reader's colours change in one move (approved: 400 ms, «Перемещение»): the page, the panel and everything in it
 *  are recoloured at once under a picture of how they were, which fades away; with «Меньше движения» in 150 ms. */
internal fun FrameLayout.crossfadeColours(a:MainActivity,change:()->Unit){
    if(width<=0||height<=0){change();return}
    val pic=runCatching{Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{draw(Canvas(it))}}.getOrNull()
    if(pic==null){change();return}
    val cover=ImageView(a).apply{setImageBitmap(pic);scaleType=ImageView.ScaleType.FIT_XY;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;elevation=a.d(40).toFloat()}
    addView(cover,FrameLayout.LayoutParams(width,height).apply{leftMargin=-paddingLeft;topMargin=-paddingTop;rightMargin=-paddingRight;bottomMargin=-paddingBottom})
    change()
    val reduced=a.prefs.str("motion")=="reduced"
    cover.animate().alpha(0f).setDuration(if(reduced)Motion.FAST else Motion.EMPHASIS).setInterpolator(MotionSpec.move).withEndAction{removeView(cover);cover.setImageDrawable(null);pic.recycle()}.start()}
