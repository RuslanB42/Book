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

/** Selects a phrase across justified lines and saves a picture, to see that the highlight and handles sit on the words. */
@RunWith(AndroidJUnit4::class)
class QuoteGeometryCheck {
    private val prose="В этом он весь. Обронит фразочку мимоходом – словно кинжал в живот всадит. Парень просто ответил на мой же вопрос. Не потребовал ничего взамен, никаких уверений в любви. А на душе отчего-то гадко – словно я использовала человека самым грубым и отвратительным образом. "
    @Test fun selectionSitsOnWords(){
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        Thread.sleep(4000)
        for((name,ws) in listOf("plain" to 0.0,"wide" to .3)){
            var reader:NativeReader?=null
            scenario.onActivity{a->
                listOf("ls","ws","font","justify","hyph","size","margin","trimEmpty","lhv").forEach{a.prefs.remove(it)};a.prefs.put("mode","paged").put("size",19.0).put("seenReader",true).put("ws",ws)
                a.reader?.let{it.dispose();a.reader=null}
                val content=JSONObject().put("chapters",JSONArray().put(JSONObject().put("title","Глава").put("html",(1..6).joinToString(""){"<p>$prose</p>"}).put("len",prose.length*6)))
                val book=JSONObject().put("id","geo-$name").put("title","Проверка").put("authors",JSONArray().put("Тест")).put("progress",JSONObject().put("ch",0).put("nativeOffset",0))
                val r=NativeReader(a,book,content,emptyMap());a.reader=r;a.root.removeAllViews();a.root.addView(r.view,FrameLayout.LayoutParams(-1,-1));r.start();reader=r
            }
            var ready=false;var waited=0
            while(!ready&&waited<15000){Thread.sleep(250);waited+=250;scenario.onActivity{ready=reader?.openingReady()==true&&reader?.body?.layout!=null}}
            Thread.sleep(800)
            // Ground truth: where words are actually drawn, from the ink of the rendered lines.
            scenario.onActivity{a->val r=reader!!;val l=r.body.layout;val bw=l.width;val bh=l.height;val full=Bitmap.createBitmap(r.body.width,r.body.height,Bitmap.Config.ARGB_8888);r.body.draw(Canvas(full));val bmp=Bitmap.createBitmap(full,r.body.paddingLeft,r.body.paddingTop,bw,minOf(bh,r.body.height-r.body.paddingTop));full.recycle();val c=Canvas(bmp);val sb=StringBuilder("$name api=${android.os.Build.VERSION.SDK_INT}\n")
                val sp=r.body.paint.measureText(" ")
                for(line in 0 until minOf(6,l.lineCount)){val fm=r.body.paint.fontMetrics;val base=l.getLineBaseline(line);val y0=(base+fm.ascent*.7f).toInt().coerceAtLeast(0);val y1=(base+fm.descent*.3f).toInt().coerceAtMost(bmp.height-1)
                    val ink=BooleanArray(bw){x->(y0..y1).any{y->android.graphics.Color.alpha(bmp.getPixel(x,y))>40}}
                    val starts=mutableListOf<Int>();var gap=999;for(x in 0 until bw){if(ink[x]){if(gap>sp*.55f)starts.add(x);gap=0}else gap++}
                    val t=l.text;val ls=l.getLineStart(line);val le=l.getLineEnd(line);val wordStarts=(ls until le).filter{i->!t[i].isWhitespace()&&(i==ls||t[i-1]==' ')}
                    sb.append("L$line justified=${le<t.length&&t[le-1]!='\n'} ink=${starts.take(8)} nat=${wordStarts.take(8).map{l.getPrimaryHorizontal(it).toInt()}} mine=${wordStarts.take(8).map{JustifiedGeometry.x(r.body,l,line,it).toInt()}} text=«${t.subSequence(ls,le).toString().map{if(it=='\u00a0')'⍽' else if(it=='\n')'¶' else it}.joinToString("")}» codes=${t.subSequence(ls,le).filter{!it.isLetterOrDigit()}.map{Integer.toHexString(it.code)}} pr=${l.getParagraphRight(line)} lr=${l.getLineRight(line).toInt()} lmax=${l.getLineMax(line).toInt()} w=${l.width} desired=${android.text.Layout.getDesiredWidth(t,ls,le,r.body.paint).toInt()}\n")}
                android.util.Log.i("POLKA_QA",sb.toString());bmp.recycle()}
            scenario.onActivity{val r=reader!!;val t=r.selectionFullText().toString();val s=t.indexOf("Обронит");val e=t.indexOf("всадит.")+7;r.quoteSelection.select(s until e)}
            Thread.sleep(900)
            scenario.onActivity{a->val r=reader!!;val dir=File(a.filesDir,"qa").apply{mkdirs()};val b=Bitmap.createBitmap(r.view.width,r.view.height,Bitmap.Config.ARGB_8888);r.view.draw(Canvas(b));File(dir,"quote-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            scenario.onActivity{a->a.reader?.dispose();a.reader=null}
        }
    }
}
