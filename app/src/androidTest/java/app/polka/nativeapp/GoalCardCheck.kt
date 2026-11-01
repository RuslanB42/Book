package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The statistics screen with a year goal and books read this year: pictures of the goal card closed and opened. */
@RunWith(AndroidJUnit4::class)
class GoalCardCheck {
    private fun find(v:View):View?{if(v.javaClass.simpleName=="YearBooks")return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    @Test fun goalCard(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        fun shot(name:String){scenario.onActivity{a->val sv=a.profileOverlay as ScrollView;val card=find(sv)!!.parent.parent as View;val top=IntArray(2);card.getLocationInWindow(top);val r=IntArray(2);sv.getLocationInWindow(r);sv.scrollTo(0,top[1]-r[1]+sv.scrollY-40)}
            Thread.sleep(400);scenario.onActivity{a->val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));File(dir,"goal-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            val now=System.currentTimeMillis();val day=86400000L
            val titles=listOf("Дюна" to 5,"Мастер и Маргарита" to 5,"Пикник на обочине" to 4,"Норвежский лес" to 3,"Тайная история" to 4,"Сто лет одиночества" to 5,"Голодные игры" to 4,"Шантарам" to 0,"Финансист" to 3)
            a.books=titles.mapIndexed{i,(t,r)->JSONObject().put("id","g$i").put("title",t).put("authors",JSONArray().put("Автор")).put("rating",r).put("progress",JSONObject().put("pct",1.0)).put("completedAt",now-i*9*day)}
            val data=JSONObject().put("annualGoals",JSONObject().put("${java.time.LocalDate.now().year}",24))
            StatisticsScreen(a,data,0,true).show()}
        Thread.sleep(2500);shot("closed")
        scenario.onActivity{a->(find(a.profileOverlay!!) as YearBooks).toggle()}
        Thread.sleep(1500);shot("open")
    }
}
