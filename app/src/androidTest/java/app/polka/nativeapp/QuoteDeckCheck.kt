package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Swipes a quote deck on a real Android and saves pictures before, mid-drag and after. */
@RunWith(AndroidJUnit4::class)
class QuoteDeckCheck {
    private fun find(v:View):View?{if(v.javaClass.simpleName=="Deck")return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    @Test fun swipeDeck(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        var dir:File?=null
        fun shot(name:String){scenario.onActivity{a->val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));File(dir,"deck-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
        scenario.onActivity{a->dir=File(a.filesDir,"qa").apply{mkdirs()}
            val q=JSONArray();listOf("Если мы горим, вы сгорите вместе с нами.","Пусть удача всегда будет на вашей стороне.","Надежда — единственное, что сильнее страха. Немного надежды — это эффективно. Много надежды — опасно.").forEachIndexed{i,t->q.put(JSONObject().put("id","q$i").put("ch",i).put("start",i*10).put("text",t).put("at",1000L+i))}
            a.books=listOf(JSONObject().put("id","deck-book").put("title","Голодные игры").put("authors",JSONArray().put("Сьюзен Коллинз")).put("quotes",q))
            a.tab="quotes";a.render()}
        Thread.sleep(1500);shot("0-rest")
        var x=0f;var y=0f;var w=0
        scenario.onActivity{a->val d=find(a.root)!!;val at=IntArray(2);d.getLocationOnScreen(at);w=d.width;x=at[0]+d.width*.75f;y=at[1]+d.height*.5f}
        val t0=SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(t0,t0,MotionEvent.ACTION_DOWN,x,y,0))
        for(k in 1..12){inst.sendPointerSync(MotionEvent.obtain(t0,t0+k*16L,MotionEvent.ACTION_MOVE,x-k*w*.035f,y+k*1f,0))}
        Thread.sleep(100);shot("1-drag")
        inst.sendPointerSync(MotionEvent.obtain(t0,t0+260,MotionEvent.ACTION_UP,x-12*w*.035f,y+12f,0))
        Thread.sleep(180);shot("2-flying")
        Thread.sleep(900);shot("3-next")
    }
}
