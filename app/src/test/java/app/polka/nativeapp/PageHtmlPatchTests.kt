package app.polka.nativeapp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.jsoup.Jsoup

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class PageHtmlPatchTests {
 private val a=Robolectric.buildActivity(MainActivity::class.java).get()
 private fun render(html:String)=ReadingText(a,19f,312,600,emptyMap()).render(html).text
 @Test fun partialPagePreservesOutsideTextLinksIdsAndFormatting(){
  val html="<h2 id='title'>Заглава</h2><p id='a'>До <strong>важного</strong> фрагмента. "+"Длинный текст. ".repeat(20)+" После <a href='#note'>сноска</a>.</p><p id='note'>Примечание</p>";val rich=render(html);val from=rich.indexOf("Длинный");val to=from+80;val page=rich.substring(from,to);val result=PageHtmlPatch.apply(html,rich,from,to,page.replaceFirst("Длинный","Новый"));val doc=Jsoup.parse(result)
  assertEquals("важного",doc.selectFirst("strong")!!.text());assertEquals("#note",doc.selectFirst("a")!!.attr("href"));assertEquals("Примечание",doc.getElementById("note")!!.text());assertEquals(rich.toString().replaceRange(from,to,page.replaceFirst("Длинный","Новый")),render(result).toString());assertTrue(doc.getElementById("a")!!.text().endsWith("После сноска."))
 }
 @Test fun repeatedTextUsesSourceLocationNotFirstMatch(){val html="<p id='one'>Одинаково</p><p id='two'>Одинаково</p>";val rich=render(html);val at=rich.lastIndexOf("Одинаково");val result=PageHtmlPatch.apply(html,rich,at,at+9,"Исправлено");val doc=Jsoup.parse(result);assertEquals("Одинаково",doc.getElementById("one")!!.text());assertEquals("Исправлено",doc.getElementById("two")!!.text())}
 @Test fun separatedEditsDoNotFlattenUntouchedInlineMarkup(){val html="<p id='p'>Первый <strong>жирный</strong> и <em>курсивный</em> последний.</p>";val rich=render(html);val result=PageHtmlPatch.apply(html,rich,0,rich.length,rich.toString().replace("Первый","Начальный").replace("последний","финальный"));val doc=Jsoup.parse(result);assertEquals("жирный",doc.selectFirst("strong")!!.text());assertEquals("курсивный",doc.selectFirst("em")!!.text());assertEquals(rich.toString().replace("Первый","Начальный").replace("последний","финальный"),render(result).toString())}
 @Test fun rawWhitespaceUnicodeEntitiesAndInsertedNewlineRoundtrip(){val html="<p id='x'>  Раз  \n  два &amp; три 😀 четыре. </p>";val rich=render(html);val next=rich.toString().replace("два","ДВА\nНОВОЕ").replace("😀","🌊");val result=PageHtmlPatch.apply(html,rich,0,rich.length,next);assertEquals(next,render(result).toString());assertEquals("x",Jsoup.parse(result).selectFirst("p")!!.id());assertTrue(result.contains("&amp;"))}
 @Test fun deletingParagraphBoundaryMergesWithoutLosingTailOrId(){val html="<p id='a'>Первая</p><p id='b'>Вторая</p><p id='c'>Третья</p>";val rich=render(html);val next=rich.toString().replace("Первая\nВторая","Первая Вторая");val result=PageHtmlPatch.apply(html,rich,0,rich.length,next);assertEquals(next,render(result).toString());assertNotNull(Jsoup.parse(result).getElementById("b"));assertEquals("Третья",Jsoup.parse(result).getElementById("c")!!.text())}
 @Test fun inlineFormattingPreservesOtherNodesAndAnchors(){val html="<p id='p'>Начало важная мысль конец.</p><p id='other'>Без правок</p>";val rich=render(html);val at=rich.indexOf("важная");val result=PageHtmlPatch.format(html,rich,listOf(PageHtmlPatch.Format(at,at+6,"bold")));val doc=Jsoup.parse(result);assertEquals("важная",doc.selectFirst("strong")!!.text());assertEquals("Без правок",doc.getElementById("other")!!.text());assertEquals(rich.toString(),render(result).toString())}
 @Test fun partialNativeTableCannotBeSilentlyDestroyed(){val html="<p>До</p><table><tr><td>Ячейка</td></tr></table><p>После</p>";val rich=render(html);val at=rich.indexOf("Ячейка");try{PageHtmlPatch.apply(html,rich,at,at+1,"Х");fail("must preserve unsupported table geometry")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("Таблица"))}}
 @Test fun diffReconstructsRandomTextEditsIncludingEmptyStrings(){val random=java.util.Random(63);repeat(160){val old=(0 until random.nextInt(80)).map{"абвгд \n"[random.nextInt(7)]}.joinToString("");var next=old;repeat(5){val from=random.nextInt(next.length+1);val to=from+random.nextInt(next.length-from+1);next=next.replaceRange(from,to,listOf("","текст","\n","😀")[random.nextInt(4)])};var actual=old;PageHtmlPatch.changes(old,next).asReversed().forEach{actual=actual.replaceRange(it.start,it.end,it.text)};assertEquals(next,actual)}}
}
