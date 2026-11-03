package app.polka.nativeapp

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** The glass statistics with sample data: top of the page and scrolled to the bottom. */
@RunWith(AndroidJUnit4::class)
class StatsCheck {
    @Test fun stats(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shot(name:String){Thread.sleep(900);val b:Bitmap=ui.takeScreenshot();File(dir,"stats-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            val today=LocalDate.now();val days=JSONObject()
            for(i in 0 until 40){val d=today.minusDays(i.toLong());val m=listOf(42,28,55,18,37,64,48,33,25,51)[i%10];days.put(d.toString(),JSONObject().put("ms",m*60000L).put("chars",m*1000L*6).put("met",i<12))}
            val data=JSONObject().put("days",days).put("goal",30).put("annualGoals",JSONObject().put(today.year.toString(),30))
            val titles=listOf("Дюна","Солярис","Пикник на обочине","Марсианин","Гиперион","Основание","Нейромант")
            a.books=titles.mapIndexed{i,t->JSONObject().put("id","b$i").put("title",t).put("authors",JSONArray().put("Автор")).put("progress",JSONObject().put("pct",1.0))
                .put("completedAt",today.minusDays((i*9).toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())}
            a.tab="home";a.render();a.window.decorView.tag=data}
        Thread.sleep(2500)
        scenario.onActivity{a->StatisticsScreen(a,a.window.decorView.tag as JSONObject,0,false).show()}
        scenario.onActivity{a->(a.profileOverlay as? android.widget.ScrollView)?.scrollTo(0,0)}
        shot("0-top")
        scenario.onActivity{a->(a.profileOverlay as? android.widget.ScrollView)?.let{it.scrollTo(0,it.getChildAt(0).height/3)}}
        shot("0-mid")
        scenario.onActivity{a->(a.profileOverlay as? android.widget.ScrollView)?.fullScroll(android.view.View.FOCUS_DOWN)}
        shot("1-bottom")
        scenario.onActivity{a->(a.profileOverlay as? android.widget.ScrollView)?.scrollTo(0,0)}
        Thread.sleep(2500);shot("2-top")
        scenario.onActivity{a->(a.profileOverlay as? android.widget.ScrollView)?.let{sv->val card=((sv.getChildAt(0) as android.view.ViewGroup).getChildAt(2));card.performClick()}}
        Thread.sleep(2500);shot("3-month")
        scenario.onActivity{a->a.readBooksPage(ReadingStatistics(a.window.decorView.tag as JSONObject,a.books),java.time.LocalDate.now().year)}
        Thread.sleep(2500);shot("4-books")
    }
}
