package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Continue-reading carousel with three books: pictures at rest, half-way between cards, and on the second card. */
@RunWith(AndroidJUnit4::class)
class CarouselCheck {
    private fun find(v:View):HorizontalScrollView?{if(v is HorizontalScrollView&&v.parent is ContinueReading)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    @Test fun carousel(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        fun shot(name:String){scenario.onActivity{a->val b=Bitmap.createBitmap(a.root.width,a.root.height/2,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));File(dir,"car-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            val now=System.currentTimeMillis()
            a.books=listOf("Дюна" to .3,"Мастер и Маргарита" to .62,"Пикник на обочине" to .81).mapIndexed{i,(t,p)->JSONObject().put("id","c$i").put("title",t).put("authors",JSONArray().put("Автор")).put("openedAt",now-i*1000L).put("chars",400000).put("progress",JSONObject().put("pct",p))}
            a.tab="home";a.render()}
        Thread.sleep(1500);shot("0-rest")
        var stride=0;scenario.onActivity{a->val h=find(a.root)!!;stride=(h.getChildAt(0) as ViewGroup).getChildAt(1).left-(h.getChildAt(0) as ViewGroup).getChildAt(0).left;h.scrollTo(stride/2,0)}
        Thread.sleep(300);shot("1-half")
        scenario.onActivity{a->find(a.root)!!.scrollTo(stride,0)}
        Thread.sleep(300);shot("2-second")
    }
}
