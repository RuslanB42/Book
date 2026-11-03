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

/** Lottie empty states: an empty shelf and no quotes. */
@RunWith(AndroidJUnit4::class)
class LottieCheck {
    @Test fun empty(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shot(name:String){val b:Bitmap=ui.takeScreenshot();File(dir,"lottie-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            a.books=listOf(JSONObject().put("id","x").put("title","Дюна").put("authors",JSONArray()))
            a.tab="shelves";a.shelf=null;a.auto="done";a.render(false,false)}
        Thread.sleep(3000);shot("shelf")
        scenario.onActivity{a->a.tab="quotes";a.auto=null;a.render(false,false)}
        Thread.sleep(3000);shot("quotes")
    }
}
