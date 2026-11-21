package app.polka.nativeapp
import android.graphics.*
import android.view.*
import android.widget.*
import org.json.*
import kotlin.math.*

fun readingSpeed(chars:Long,ms:Long)=if(ms>=60000 && chars>300) (chars/6.4/(ms/60000.0)).roundToInt() else 0
fun duration(ms:Long):String { val m=(ms/60000.0).roundToLong(); return when { ms>0 && m<1 -> "<1 мин"; m<60 -> "$m мин"; m%60==0L -> "${m/60} ч"; else -> "${m/60} ч ${m%60} мин" } }
fun MainActivity.profile() { var stats=JSONObject(); work({ stats=library.dao.meta("stats")?.let { JSONObject(it.json).optJSONObject("data") } ?: JSONObject() }) { showProfile(stats,profileRange) } }
fun MainActivity.showProfile(stats:JSONObject,range:Int,animate:Boolean=true) {
    profileStats=stats; profileRange=range
    profileOverlay?.let { root.removeView(it) }
    StatisticsScreen(this,stats,range,animate).show()
}

/** Same presentation-to-target transition for toolbar and system Back. */
fun MainActivity.dismissProfile(){val panel=profileOverlay?:return;profileOverlay=null;profileStats=null
 Depth(this,panel).leave{root.removeView(panel)}
}
