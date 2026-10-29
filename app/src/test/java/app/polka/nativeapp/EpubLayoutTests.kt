package app.polka.nativeapp

import android.graphics.*
import android.os.Looper
import android.text.*
import android.text.style.*
import android.view.View
import android.widget.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubLayoutTests {
    private fun epub(ncx:Boolean=false):ByteArray {
        val entries=linkedMapOf(
            "META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
            "OPS/book.opf" to """<package><metadata><dc:title xmlns:dc='x'>Проверка EPUB</dc:title><meta property='belongs-to-collection' id='series'>Серия</meta><meta refines='#series' property='group-position'>3</meta></metadata><manifest><item id='a' href='Text/one.xhtml' media-type='application/xhtml+xml'/><item id='b' href='Text/two.xhtml' media-type='application/xhtml+xml'/><item id='toc' href='nav.xhtml' media-type='application/xhtml+xml' properties='${if(ncx) "" else "nav"}'/><item id='ncx' href='toc.ncx' media-type='application/x-dtbncx+xml'/><item id='image' href='Images/diagram.svg' media-type='image/svg+xml' properties='cover-image'/></manifest><spine toc='ncx'><itemref idref='a'/><itemref idref='b'/></spine></package>""",
            "OPS/nav.xhtml" to """<html><body><nav epub:type='toc'><ol><li><a href='Text/one.xhtml#part'>Часть первая</a><ol><li><a href='Text/one.xhtml#nested'>Вложенная глава</a></li></ol></li><li><a href='Text/two.xhtml'>Часть вторая</a></li></ol></nav></body></html>""",
            "OPS/toc.ncx" to """<ncx><navMap><navPoint><navLabel><text>Часть первая</text></navLabel><content src='Text/one.xhtml#part'/><navPoint><navLabel><text>Вложенная глава</text></navLabel><content src='Text/one.xhtml#nested'/></navPoint></navPoint><navPoint><navLabel><text>Часть вторая</text></navLabel><content src='Text/two.xhtml'/></navPoint></navMap></ncx>""",
            "OPS/Text/one.xhtml" to """<html><head><style>p{color:red}</style></head><body><h1 id='part'>Часть первая</h1><p>Абзац с <strong>жирным</strong> и <em>курсивом</em>.</p><h2 id='nested'>Вложенная глава</h2><p><a href='#nested'>В эту главу</a> <a href='two.xhtml#note'>Сноска</a></p><svg xmlns='http://www.w3.org/2000/svg'><image xlink:href='../Images/diagram.svg'/></svg><script>bad()</script></body></html>""",
            "OPS/Text/two.xhtml" to """<html><body><h1>Часть вторая</h1><aside id='note' epub:type='footnote'><p>Текст сноски</p></aside></body></html>""",
            "OPS/Images/diagram.svg" to """<svg xmlns='http://www.w3.org/2000/svg' width='120' height='60'><rect x='0' y='0' width='120' height='60' fill='#a64a36'/><circle cx='30' cy='30' r='15' fill='#ffffff'/></svg>"""
        )
        val out=ByteArrayOutputStream(); ZipOutputStream(out).use { z -> entries.forEach { (path,text) -> z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() } }; return out.toByteArray()
    }
    @Test fun generatedCoverTypefaceMatchesReferenceWidth() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get(); val paint=TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply { typeface=PolkaFonts.serif(a,true); textSize=17.545f }
        // Browser reference: 120.78 px at 600 / 17.545px Literata. Rounded font rasterizers may differ slightly.
        assertEquals(120.78f,paint.measureText("Как устроена"),2f)
    }
    @Test fun epub3NestedNavigationAndSameFileAnchors() {
        val b=BookParser.parse(epub(),"layout.epub"); val toc=b.content.getJSONArray("toc")
        assertEquals(3,toc.length()); assertEquals(1,toc.getJSONObject(1).getInt("depth")); assertEquals(0,toc.getJSONObject(1).getInt("ch")); assertEquals("nested",toc.getJSONObject(1).str("anchor"))
        assertEquals("Серия",b.book.str("series")); assertEquals("3",b.book.str("seriesNo"))
        val html=b.content.getJSONArray("chapters").getJSONObject(0).str("html")
        assertTrue(html.contains("OPS/Text/one.xhtml#nested")); assertTrue(html.contains("OPS/Text/two.xhtml#note")); assertTrue(html.contains("OPS/Images/diagram.svg")); assertFalse(html.contains("bad()")); assertFalse(html.contains("color:red"))
    }
    @Test fun epubExportRetainsNavigationAndRewritesInternalLinks() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db=androidx.room.Room.inMemoryDatabaseBuilder(context,LibraryDb::class.java).allowMainThreadQueries().build()
        try { val lib=Library(db); lib.initialize(); val original=BookParser.parse(epub(),"layout.epub"); lib.importBooks(listOf(original)); val bytes=BookExport.build(lib,original.book,"epub"); val roundtrip=BookParser.parse(bytes,"roundtrip.epub")
            val toc=roundtrip.content.getJSONArray("toc"); assertEquals(3,toc.length()); assertEquals(1,toc.getJSONObject(1).optInt("depth")); assertEquals("nested",toc.getJSONObject(1).str("anchor")); assertEquals("Серия",roundtrip.book.str("series"))
            val html=roundtrip.content.getJSONArray("chapters").getJSONObject(0).str("html"); assertTrue(html.contains("OEBPS/ch0.xhtml#nested")); assertTrue(html.contains("OEBPS/ch1.xhtml#note"))
        } finally { db.close() }
    }
    @Test fun epub2NcxHasTheSameHierarchy() {
        val toc=BookParser.parse(epub(true),"legacy.epub").content.getJSONArray("toc"); assertEquals(3,toc.length()); assertEquals("Вложенная глава",toc.getJSONObject(1).str("title")); assertEquals(1,toc.getJSONObject(1).optInt("depth")); assertEquals("part",toc.getJSONObject(0).str("anchor"))
    }
    @Test fun svgBookResourceDecodesNatively() {
        val bitmap=BookImages.decode("<svg xmlns='http://www.w3.org/2000/svg' width='100' height='50'><rect width='100' height='50' fill='#ff0000'/></svg>".toByteArray())
        assertNotNull(bitmap); assertEquals(100,bitmap!!.width); assertEquals(50,bitmap.height); assertEquals(Color.RED,bitmap.getPixel(20,20))
    }
    @Test fun nativeRendererPreservesListsTablesAndAnchors() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        val result=ReadingText(a,19f,342,500,emptyMap()).render("<h1 id='top'>Глава</h1><p>Первый абзац.</p><p>Второй абзац с <sup>2</sup>.</p><ol start='3'><li>Первый пункт</li><li>Второй пункт</li></ol><pre>  a\n  b</pre><table><tr><th>Год</th><th>Книг</th></tr><tr><td>2026</td><td>42</td></tr></table>")
        val text=result.text; assertEquals(0,result.anchors["top"]); assertTrue(text.contains("3. Первый пункт")); assertTrue(text.contains("4. Второй пункт")); assertTrue(text.contains("  a\n  b")); assertTrue(text.contains("2026\t42")); assertEquals(2,text.getSpans(0,text.length,TableRowSpan::class.java).size); assertTrue(text.getSpans(0,text.length,LeadingMarginSpan::class.java).isNotEmpty()); assertEquals(1,text.getSpans(0,text.length,SuperscriptSpan::class.java).size)
    }
    @Test fun tableRowsSplitInsteadOfLosingLongCellText() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get(); val value="Длинная ячейка ".repeat(300)
        val result=ReadingText(a,19f,320,200,emptyMap()).render("<table><tr><td>$value</td><td>Конец</td></tr></table>")
        assertTrue(result.text.toString().contains(value.trim())); assertTrue(result.text.getSpans(0,result.text.length,TableRowSpan::class.java).size>1)
    }
    @Test fun paragraphStylesDoNotLeakIntoFollowingBlocks() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        val text=ReadingText(a,19f,342,500,emptyMap()).render("<blockquote><p>Цитата</p></blockquote><p>Обычный текст</p><table><tr><td>Ячейка</td></tr></table>").text
        val table=text.indexOf("Ячейка"); val margins=text.getSpans(table,table+1,LeadingMarginSpan::class.java); assertEquals("Quote/list indents must not reach the table",0,margins.size)
        assertTrue(text.getSpans(0,text.length,LeadingMarginSpan::class.java).all { text.getSpanEnd(it)<=table })
    }
    @Test fun renderStructuredReadingPreview() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get(); val html="""<h2>Первая глава</h2><p>Абзац после заголовка начинается без красной строки.</p><p>Следующий абзац — с отступом. Здесь <strong>жирный текст</strong>, <em>курсив</em> и сноска<sup><a href='#n'>1</a></sup>.</p><ul><li>Первый пункт списка</li><li>Второй пункт списка</li></ul><blockquote><p>Небольшая цитата внутри главы.</p></blockquote><table><tr><th>Год</th><th>Прочитано книг</th></tr><tr><td>2025</td><td>18</td></tr><tr><td>2026</td><td>24</td></tr></table><pre>строка кода
  отступ сохранён</pre>"""
        val view=TextView(a).apply { typeface=PolkaFonts.reader(a,"literata"); textSize=19f; setTextColor(a.fg); setBackgroundColor(a.bg); includeFontPadding=false; setPadding(24,20,24,20); setLineSpacing(0f,1f); text=ReadingText(a,19f,342,600,emptyMap()).render(html).text }
        view.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY)); view.layout(0,0,390,844); org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); val bitmap=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap)); FileOutputStream(File(System.getProperty("qaOutputDir"),"epub-structured.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
}
