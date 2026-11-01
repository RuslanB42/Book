package app.polka.nativeapp

import android.os.SystemClock
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens the page editor on a real Android and taps into words like a person would: does the caret land where tapped,
 *  and does one Backspace delete the letter before that caret? Logs every miss. */
@RunWith(AndroidJUnit4::class)
class EditorDeviceCheck {
    private val prose="Вечером над морем зажёгся маяк, и его свет был виден далеко от берега. Достопримечательности побережья, неожиданно освещённые прожектором, казались декорациями; высокопревосходительство губернатора путешествовало вдоль набережной. Сквозь распахнутые окна доносились голоса рыбаков, перекликавшихся у причалов. "
    private fun content():JSONObject{val html="<h1>Первая глава</h1>"+(1..10).joinToString(""){"<p>$prose</p>"};return JSONObject().put("chapters",JSONArray().put(JSONObject().put("title","Первая глава").put("html",html).put("len",prose.length*10)))}
    private fun find(v:View):EditText?{if(v is EditText)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    @Test fun caretFollowsTaps(){
        val inst=InstrumentationRegistry.getInstrumentation()
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        val report=StringBuilder()
        for((name,overrides) in listOf("justify" to JSONObject(),"nojustify" to JSONObject().put("justify",false),"ws+30" to JSONObject().put("ws",.3),"ls+5" to JSONObject().put("ls",.05))){
            var reader:NativeReader?=null
            scenario.onActivity{a->
                listOf("ls","ws","font","justify","hyph","size","margin","trimEmpty","lhv").forEach{a.prefs.remove(it)};a.prefs.put("mode","paged").put("size",19.0).put("seenReader",true)
                overrides.keys().forEach{k->a.prefs.put(k,overrides.get(k))}
                a.reader?.let{it.dispose();a.reader=null}
                val book=JSONObject().put("id","editor-check-$name").put("title","Проверка").put("authors",JSONArray().put("Тест")).put("progress",JSONObject().put("ch",0).put("nativeOffset",0))
                val r=NativeReader(a,book,content(),emptyMap());a.reader=r;a.root.removeAllViews();a.root.addView(r.view,FrameLayout.LayoutParams(-1,-1));r.start();reader=r
            }
            var ready=false;var waited=0
            while(!ready&&waited<15000){Thread.sleep(250);waited+=250;scenario.onActivity{ready=reader?.openingReady()==true&&reader?.body?.layout!=null}}
            Thread.sleep(600)
            scenario.onActivity{reader!!.editChapter()}
            Thread.sleep(1200)
            var editor:EditText?=null;scenario.onActivity{editor=find(reader!!.view)}
            val e=editor;if(e==null){report.append("$name: editor not found\n");continue}
            var misses=0;var delMisses=0;var checks=0;val notes=mutableListOf<String>()
            // Pick word-internal offsets spread over the page.
            val targets=mutableListOf<Int>();scenario.onActivity{val t=e.text;var i=40;while(i<t.length-5&&targets.size<10){if(t[i].isLetter()&&t[i-1].isLetter())targets.add(i);i+=t.length/11}}
            for(off in targets){
                var x=0f;var y=0f;var expectChar=' ';var before=""
                scenario.onActivity{val l=e.layout;val line=l.getLineForOffset(off);val at=IntArray(2);e.getLocationOnScreen(at)
                    x=at[0]+e.totalPaddingLeft+l.getPrimaryHorizontal(off);y=at[1]+e.totalPaddingTop+(l.getLineTop(line)+l.getLineBottom(line))/2f-e.scrollY;expectChar=e.text[off-1];before=e.text.toString()}
                val t0=SystemClock.uptimeMillis()
                inst.sendPointerSync(MotionEvent.obtain(t0,t0,MotionEvent.ACTION_DOWN,x,y,0));inst.sendPointerSync(MotionEvent.obtain(t0,t0+60,MotionEvent.ACTION_UP,x,y,0))
                Thread.sleep(700)
                var sel=-1;var hitTest=-1;scenario.onActivity{sel=e.selectionStart;val l=e.layout;hitTest=l.getOffsetForHorizontal(l.getLineForOffset(off),l.getPrimaryHorizontal(off))}
                checks++
                if(sel!=off){misses++;if(notes.size<8)notes.add("tap@$off→caret $sel (hitTest $hitTest) «${before.substring(maxOf(0,off-6),minOf(before.length,off+6))}»")}
                if(sel<=0){var info="";scenario.onActivity{val root=e.rootView;fun top(v:View,px:Float,py:Float):String{val r=android.graphics.Rect();if(!v.isShown||!v.getGlobalVisibleRect(r)||!r.contains(px.toInt(),py.toInt()))return "";if(v is ViewGroup)for(i in v.childCount-1 downTo 0){val t=top(v.getChildAt(i),px,py);if(t.isNotEmpty())return t};return v.javaClass.simpleName+"#"+v.hashCode()};info="under=${top(root,x,y)} focused=${e.isFocused} hasWindowFocus=${e.hasWindowFocus()} enabled=${e.isEnabled} editorAt=${IntArray(2).also{e.getLocationOnScreen(it)}.toList()} x=$x y=$y"};notes.add("tap@$off no caret: $info");continue}
                inst.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL);Thread.sleep(300)
                var after="";scenario.onActivity{after=e.text.toString()}
                val expected=before.removeRange(sel-1,sel)
                if(sel>0&&after!=expected){delMisses++;if(notes.size<12)notes.add("del@$sel removed wrong: «${before.substring(maxOf(0,sel-6),minOf(before.length,sel+6))}» → «${after.substring(maxOf(0,sel-7),minOf(after.length,sel+5))}» expected char «${before[sel-1]}» (tapped «$expectChar»)")}
                scenario.onActivity{e.setText(before)}
                Thread.sleep(200)
            }
            var info="";scenario.onActivity{info="justify=${e.justificationMode} ws=${if(android.os.Build.VERSION.SDK_INT>=29)e.paint.wordSpacing else 0f} ls=${e.letterSpacing} hyph=${e.hyphenationFrequency} spans=${e.text.getSpans(0,e.length(),Any::class.java).map{it.javaClass.simpleName}.groupingBy{it}.eachCount()}"}
            report.append("$name: taps=$checks caretMisses=$misses deleteMisses=$delMisses $info\n   ${notes.joinToString("\n   ")}\n")
            scenario.onActivity{a->a.reader?.dispose();a.reader=null}
        }
        scenario.onActivity{a->val dir=File(a.filesDir,"qa").apply{mkdirs()};File(dir,"editor.txt").writeText(report.toString())}
        report.lines().forEach{android.util.Log.i("POLKA_QA",it)}
    }
}
