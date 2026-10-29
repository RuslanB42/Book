package app.polka.nativeapp
import android.view.*
import android.os.Looper
import org.robolectric.Shadows
import java.util.concurrent.TimeUnit
/** Wait for real background raster work, not for an arbitrary simulated animation delay. */
internal fun View.awaitGlowRaster(){
 fun all(v:View):List<BookGlowView> = (if(v is BookGlowView)listOf(v)else emptyList())+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 val limit=System.currentTimeMillis()+8000
 while(all(this).any{!it.ready}&&System.currentTimeMillis()<limit){Thread.sleep(10);Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MILLISECONDS)}
}
