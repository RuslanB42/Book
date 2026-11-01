package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Runs the real reader on a real Android (emulator) with every text setting and reports how lines break:
 *  words split mid-way, whether a hyphen is drawn there, and one-letter fragments. Writes a report + pictures. */
@RunWith(AndroidJUnit4::class)
class TextDeviceCheck {
    private val prose="Вечером над морем зажёгся маяк, и его свет был виден далеко от берега. Достопримечательности побережья, неожиданно освещённые прожектором, казались декорациями; высокопревосходительство губернатора путешествовало вдоль набережной. Человеконенавистничество — не про нас: мы любили этот город, его электрификацию, непредсказуемость погоды и разговоры о переустройстве мира. Сквозь распахнутые окна доносились голоса рыбаков, перекликавшихся у причалов, и запах смолы, водорослей и свежевыкрашенных лодок. "
    private fun content():JSONObject{val html="<h1>Первая глава</h1>"+(1..14).joinToString(""){"<p>$prose</p>"};return JSONObject().put("chapters",JSONArray().put(JSONObject().put("title","Первая глава").put("html",html).put("len",prose.length*14)))}
    private val combos=listOf(
        "base" to JSONObject(),
        "ls+5" to JSONObject().put("ls",.05),
        "ls-2" to JSONObject().put("ls",-.02),
        "ws+30" to JSONObject().put("ws",.3),
        "ptserif" to JSONObject().put("font","ptserif"),
        "sans" to JSONObject().put("font","sans"),
        "nojustify" to JSONObject().put("justify",false),
        "nohyph" to JSONObject().put("hyph",false),
        "big" to JSONObject().put("size",26.0).put("margin",40),
        "small" to JSONObject().put("size",15.0).put("margin",10),
        "trim" to JSONObject().put("trimEmpty",true)
    )
    @Test fun lineBreaksUnderEverySetting(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        val report=StringBuilder();var dir:File?=null
        for((name,overrides) in combos){
            var reader:NativeReader?=null
            scenario.onActivity{a->
                dir=a.getExternalFilesDir("qa")
                listOf("ls","ws","font","justify","hyph","size","margin","trimEmpty","lhv").forEach{a.prefs.remove(it)};a.prefs.put("mode","paged").put("size",19.0)
                overrides.keys().forEach{k->a.prefs.put(k,overrides.get(k))}
                a.reader?.let{it.dispose();a.reader=null}
                val book=JSONObject().put("id","device-check-$name").put("title","Проверка").put("authors",JSONArray().put("Тест")).put("progress",JSONObject().put("ch",0).put("nativeOffset",0))
                val r=NativeReader(a,book,content(),emptyMap());a.reader=r;a.root.removeAllViews();a.root.addView(r.view,FrameLayout.LayoutParams(-1,-1));r.start();reader=r
            }
            var ready=false;var waited=0
            while(!ready&&waited<15000){Thread.sleep(250);waited+=250;scenario.onActivity{ready=reader?.openingReady()==true&&reader?.body?.layout!=null}}
            Thread.sleep(800)
            scenario.onActivity{a->
                val r=reader!!;val layout=r.body.layout;val text=r.body.text
                var mid=0;var hyphenDrawn=0;var bare=0;var oneLetter=0;val samples=mutableListOf<String>()
                for(i in 0 until layout.lineCount){
                    val end=layout.getLineEnd(i);if(end<=0||end>=text.length)continue
                    if(!text[end-1].isLetter()||!text[end].isLetter())continue
                    mid++
                    val edit=runCatching{layout.javaClass.getMethod("getEndHyphenEdit",Int::class.javaPrimitiveType).invoke(layout,i) as Int}.getOrDefault(-1)
                    if(edit!=0)hyphenDrawn++ else bare++
                    var l=end;while(l>0&&text[l-1].isLetter())l--;var rr=end;while(rr<text.length&&text[rr].isLetter())rr++
                    if(end-l==1||rr-end==1)oneLetter++
                    if(samples.size<6)samples.add("${text.subSequence(l,end)}|${text.subSequence(end,rr)} edit=$edit")
                }
                report.append("$name: lines=${layout.lineCount} midWord=$mid hyphenDrawn=$hyphenDrawn noHyphen=$bare oneLetter=$oneLetter softHyphensInText=${text.count{it=='­'}} ls=${r.body.letterSpacing} hyphFreq=${r.body.hyphenationFrequency} justify=${r.body.justificationMode}\n   ${samples.joinToString("; ")}\n")
                val b=Bitmap.createBitmap(r.view.width,r.view.height,Bitmap.Config.ARGB_8888);r.view.draw(Canvas(b))
                File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
            }
        }
        scenario.onActivity{a->a.reader?.dispose();a.reader=null}
        File(dir,"report.txt").writeText(report.toString());println(report)
    }
}
