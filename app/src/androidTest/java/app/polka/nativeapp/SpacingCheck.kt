package app.polka.nativeapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Letter spacing + justification: does the live page, the page picture (snapshot) and the next-page picture
 *  all reach the right text edge? Reports, per picture, how many full lines end within 4px of the edge. */
@RunWith(AndroidJUnit4::class)
class SpacingCheck {
    private val prose="Вечером над морем зажёгся маяк, и его свет был виден далеко от берега. Тихие вечера, запах старых книг и неторопливые разговоры о том, что ещё впереди, казались бесконечными и тёплыми."
    private fun content():JSONObject{val html=(1..24).joinToString(""){"<p>$prose</p>"};return JSONObject().put("chapters",JSONArray().put(JSONObject().put("title","Глава").put("html",html)))}
    @Test fun spacedLinesReachTheEdge(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        val ui=InstrumentationRegistry.getInstrumentation().uiAutomation
        val report=StringBuilder();var dir:File?=null;var reader:NativeReader?=null
        scenario.onActivity{a->
            dir=File(a.filesDir,"qa").apply{mkdirs()}
            listOf("ls","ws","font","justify","hyph","size","margin","trimEmpty","lhv").forEach{a.prefs.remove(it)};a.prefs.put("mode","paged").put("size",19.0).put("ls",.06).put("motion","full")
            a.reader?.let{it.dispose();a.reader=null}
            val book=JSONObject().put("id","spacing-check").put("title","Проверка").put("authors",JSONArray().put("Тест")).put("progress",JSONObject().put("ch",0).put("nativeOffset",0))
            val r=NativeReader(a,book,content(),emptyMap());a.reader=r;a.root.removeAllViews();a.root.addView(r.view,FrameLayout.LayoutParams(-1,-1));r.start();reader=r
        }
        var ready=false;var waited=0
        while(!ready&&waited<15000){Thread.sleep(250);waited+=250;scenario.onActivity{ready=reader?.openingReady()==true&&reader?.body?.layout!=null}}
        Thread.sleep(1500)
        fun edges(name:String,b:Bitmap,r:NativeReader,screen:Boolean=false){
            val l=r.body.layout;val loc=IntArray(2);val vloc=IntArray(2);if(screen)r.body.getLocationOnScreen(loc) else {r.body.getLocationInWindow(loc);r.view.getLocationInWindow(vloc)}
            val ox=loc[0]-vloc[0];val oy=loc[1]-vloc[1]
            val right=ox+r.body.width-r.body.paddingRight;var full=0;var near=0;val gaps=mutableListOf<Int>()
            for(i in 0 until l.lineCount){val e=l.getLineEnd(i);val t=l.text;if(e>=t.length||t[e-1]=='\n')continue
                full++;val y0=oy+r.body.paddingTop+l.getLineTop(i)+2;val y1=oy+r.body.paddingTop+l.getLineBottom(i)-2;var ink=-1
                for(x in (right+40).coerceAtMost(b.width-1) downTo (right-120).coerceAtLeast(0)){var hit=false;for(y in y0.coerceAtLeast(0)..y1.coerceAtMost(b.height-1)){val p=b.getPixel(x,y);if(android.graphics.Color.red(p)>120){hit=true;break}};if(hit){ink=x;break}}
                val gap=right-ink;gaps.add(gap);if(kotlin.math.abs(gap)<=4)near++}
            report.append("$name: fullLines=$full nearEdge=$near gaps=${gaps.take(14)}\n")
            File(dir,"spacing-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        scenario.onActivity{a->val r=reader!!
            val spans=(r.body.text as? android.text.Spanned)?.getSpans(0,r.body.text.length,LineStretch::class.java)?.size
            report.append("previewProbe=${run{val tv=android.widget.TextView(a);tv.setText("а б в",android.widget.TextView.BufferType.SPANNABLE);tv.measure(0,0);tv.layout(0,0,tv.measuredWidth,tv.measuredHeight);"text=${tv.text.javaClass.simpleName} layoutText=${tv.layout.text.javaClass.simpleName} same=${tv.text===tv.layout.text}"}}\n");report.append("justify=${r.body.justificationMode} ls=${r.body.letterSpacing} stretchSpans=$spans layout=${r.body.layout.javaClass.simpleName} sdk=${android.os.Build.VERSION.SDK_INT}\n")
            val sw=Bitmap.createBitmap(r.view.width,r.view.height,Bitmap.Config.ARGB_8888);r.view.draw(Canvas(sw));edges("view-draw",sw,r)
            val m=NativeReader::class.java.declaredMethods.first{it.name=="snapshot"&&it.parameterTypes.size==3};m.isAccessible=true
            edges("snapshot-current",m.invoke(r,null,null,false) as Bitmap,r)
            val pt=NativeReader::class.java.declaredMethods.first{it.name=="pageText"&&it.parameterTypes.size==3};pt.isAccessible=true
            val rich=NativeReader::class.java.getDeclaredField("chapterRich").apply{isAccessible=true}.get(r) as CharSequence
            val starts=NativeReader::class.java.getDeclaredField("pageStarts").apply{isAccessible=true}.get(r) as List<*>
            run{val page=pt.invoke(r,rich,starts,0) as CharSequence;val b=r.body;val pv=android.widget.TextView(a);pv.paint.set(b.paint);pv.typeface=b.typeface;pv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,b.textSize);pv.includeFontPadding=b.includeFontPadding;pv.setLineSpacing(0f,1f);pv.breakStrategy=b.breakStrategy;pv.hyphenationFrequency=b.hyphenationFrequency;pv.justificationMode=b.justificationMode;pv.setPadding(b.paddingLeft,b.paddingTop,b.paddingRight,b.paddingBottom);pv.setTextIsSelectable(true);pv.setText(page,android.widget.TextView.BufferType.SPANNABLE)
                pv.measure(android.view.View.MeasureSpec.makeMeasureSpec(r.scroll.width,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED));pv.layout(0,0,r.scroll.width,pv.measuredHeight)
                SpacedJustify.apply(pv);val sp=(pv.layout.text as android.text.Spanned).getSpans(0,pv.layout.text.length,LineStretch::class.java)
                report.append("manualPreview: lines=${pv.layout.lineCount} spans=${sp.size} first=${sp.firstOrNull()?.extra} err=${SpacedJustify.lastError} lineRight0=${pv.layout.getLineRight(0)} parRight0=${pv.layout.getParagraphRight(0)} width=${pv.layout.width} ws=${pv.paint.wordSpacing} ls=${pv.paint.letterSpacing} bodyLineRight0=${b.layout.getLineRight(0)} bodyLineMax0=${b.layout.getLineMax(0)} pvLineMax0=${pv.layout.getLineMax(0)}\n")
                val bb=Bitmap.createBitmap(r.view.width,r.view.height,Bitmap.Config.ARGB_8888);val c=Canvas(bb);c.translate(r.scroll.left.toFloat(),r.scroll.top.toFloat());pv.draw(c);edges("manual-preview",bb,r)}
            edges("snapshot-text-same-page",m.invoke(r,pt.invoke(r,rich,starts,0),null,false) as Bitmap,r)
        }
        val scr=ui.takeScreenshot();scenario.onActivity{a->edges("screen",scr,reader!!,true)}
        scenario.onActivity{a->a.reader?.dispose();a.reader=null}
        File(dir,"report.txt").writeText(report.toString());report.lines().forEach{android.util.Log.i("POLKA_QA",it)}
    }
}
