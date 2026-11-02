package app.polka.nativeapp

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** «Полки» with real GPU screenshots: at rest, after sliding the first row, and in edit mode. */
@RunWith(AndroidJUnit4::class)
class ShelvesCheck {
    private fun rows(v:View,out:MutableList<HorizontalScrollView>){if(v is HorizontalScrollView&&v.parent?.parent is ShelfRow)out.add(v);if(v is ViewGroup)for(i in 0 until v.childCount)rows(v.getChildAt(i),out)}
    @Test fun shelves(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shot(name:String){Thread.sleep(700);val b:Bitmap=ui.takeScreenshot();File(dir,"shelf-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            val titles=listOf("Дюна","Мастер и Маргарита","Пикник на обочине","Солярис","Трудно быть богом","Улитка на склоне","Понедельник начинается в субботу","Град обреченный","Гиперион","Основание","Нейромант","Марсианин")
            a.books=titles.mapIndexed{i,t->JSONObject().put("id","s$i").put("title",t).put("authors",JSONArray().put(if(i%2==0)"Фрэнк Герберт" else "Стругацкие")).put("shelf",if(i<8)"Фантастика" else "").put("addedAt",1000L+i)
                .put("progress",JSONObject().put("pct",if(i%3==0)0.4 else 0.0))}
            a.tab="shelves";a.shelf=null;a.auto=null;a.render()}
        shot("0-rest")
        scenario.onActivity{a->val l=ArrayList<HorizontalScrollView>();rows(a.root,l);l.firstOrNull()?.scrollTo(a.d(150),0)}
        shot("1-slid")
        scenario.onActivity{a->a.shelvesTopBarLabel?.performClick()}
        shot("2-edit")
    }
}
