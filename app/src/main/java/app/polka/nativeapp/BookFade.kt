package app.polka.nativeapp

import android.graphics.Color
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.FrameLayout

/** Opening/closing a book is a soft fade through the app background. All heavy work (building the reader, rebuilding the
 *  library) happens only while the screen is fully dark, so neither fade ever has to share a frame with it. */
internal fun MainActivity.fadeVeil():View=View(this).apply{setBackgroundColor(bg);alpha=0f;isClickable=true;elevation=d(60).toFloat();importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
internal val fadeIn=PathInterpolator(.4f,0f,.2f,1f)
internal val fadeOut=PathInterpolator(.3f,0f,.2f,1f)

internal fun MainActivity.closeWithFade(r:NativeReader){
    val veil=fadeVeil();bookClosing=true;root.addView(veil,FrameLayout.LayoutParams(-1,-1))
    veil.animate().alpha(1f).setDuration(220).setInterpolator(fadeIn).withEndAction{
        (r.view.parent as? ViewGroup)?.removeView(r.view);bookClosing=false
        render(true,false)
        // Back to where the book was opened from: the book card reappears in place, already settled.
        val back=returnDetailId?.takeIf{it==r.book.str("id")};returnDetailId=null
        back?.let{id->books.firstOrNull{it.str("id")==id}?.let{b->BookDetailSheet(this,b,null).apply{instant=true}.show()}}
        (veil.parent as? ViewGroup)?.removeView(veil);root.addView(veil,FrameLayout.LayoutParams(-1,-1));finishBookReturn()
        // Reveal only after the rebuilt library has been laid out once.
        val observer=veil.viewTreeObserver
        observer.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
            if(observer.isAlive)observer.removeOnPreDrawListener(this)
            veil.postOnAnimation{veil.animate().alpha(0f).setDuration(320).setInterpolator(fadeOut).withEndAction{(veil.parent as? ViewGroup)?.removeView(veil)}.start()}
            return true}})
    }.start()
}

/** The last page a book was left on, kept as a picture so reopening can show it at once while the live text is
 *  prepared. Used only when the screen size, position and every text setting are exactly the same. */
internal object PageImages {
    private fun file(a:MainActivity,id:String)=java.io.File(java.io.File(a.cacheDir,"pages"),id.replace(Regex("[^A-Za-z0-9_-]"),"_")+".webp")
    private fun meta(a:MainActivity,id:String)=java.io.File(java.io.File(a.cacheDir,"pages"),id.replace(Regex("[^A-Za-z0-9_-]"),"_")+".key")
    fun signature(a:MainActivity,w:Int,h:Int,ch:Int,offset:Int):String{val p=a.prefs
        return listOf(w,h,ch,offset,p.str("font"),p.optDouble("size",19.0),p.optDouble("lhv",-1.0),p.str("lh"),p.optDouble("ls",0.0),p.optDouble("ws",0.0),p.optInt("margin",24),p.optBoolean("hyph",true),p.optBoolean("justify",true),p.optBoolean("trimEmpty",false),p.str("rt"),p.str("ui"),p.str("mode","paged"),a.light).joinToString("|")}
    fun save(a:MainActivity,id:String,key:String,image:android.graphics.Bitmap){
        val f=file(a,id);val m=meta(a,id)
        a.work({f.parentFile?.mkdirs();val tmp=java.io.File(f.path+".tmp");tmp.outputStream().use{image.compress(if(android.os.Build.VERSION.SDK_INT>=30)android.graphics.Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") android.graphics.Bitmap.CompressFormat.WEBP,92,it)};tmp.renameTo(f);m.writeText(key);image.recycle()})
    }
    fun load(a:MainActivity,id:String,key:String):android.graphics.Bitmap?=runCatching{if(meta(a,id).readText()!=key)null else android.graphics.BitmapFactory.decodeFile(file(a,id).path)}.getOrNull()
    fun drop(a:MainActivity,id:String){runCatching{file(a,id).delete();meta(a,id).delete()}}
}
