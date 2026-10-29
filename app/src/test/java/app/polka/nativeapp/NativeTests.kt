package app.polka.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.*
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], manifest=Config.NONE)
class NativeTests {
    private lateinit var db:LibraryDb
    private lateinit var lib:Library
    private val xml="""<?xml version="1.0" encoding="UTF-8"?><FictionBook xmlns:l="http://www.w3.org/1999/xlink"><description><title-info><book-title>Смотритель маяка</book-title><author><first-name>Иван</first-name><last-name>Тестов</last-name></author><genre>prose</genre></title-info></description><body><section><title><p>Глава первая</p></title><p>Вечером над морем зажёгся маяк. Книга для проверки чтения.</p></section><section><title><p>Глава вторая</p></title><p>Ночь продолжалась.</p></section></body></FictionBook>"""
    @Before fun before() { db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),LibraryDb::class.java).allowMainThreadQueries().build(); lib=Library(db); lib.initialize() }
    @After fun after() { db.close() }
    private fun importOne()=BookParser.parse(xml.toByteArray(),"test.fb2").also { lib.importBooks(listOf(it)) }
    @Test fun fb2ParsesRussianChapters() { val b=importOne(); assertEquals("Смотритель маяка",b.book.getString("title")); assertEquals("Иван Тестов",MainActivity.authors(b.book)); assertEquals(2,b.content.getJSONArray("chapters").length()); assertTrue(b.content.toString().contains("Ночь продолжалась")) }
    @Test fun zipAndEpubParsers() {
        fun zip(entries:Map<String,String>):ByteArray { val out=ByteArrayOutputStream(); ZipOutputStream(out).use { z -> entries.forEach { (path,text) -> z.putNextEntry(ZipEntry(path)); z.write(text.toByteArray()); z.closeEntry() } }; return out.toByteArray() }
        assertEquals("Смотритель маяка",BookParser.parse(zip(mapOf("test.fb2" to xml)),"test.fb2.zip").book.str("title"))
        val epub=zip(mapOf("META-INF/container.xml" to "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>","OPS/book.opf" to "<package><metadata><dc:title xmlns:dc='x'>Моя EPUB</dc:title><dc:creator xmlns:dc='x'>Автор</dc:creator></metadata><manifest><item id='a' href='a.xhtml' media-type='application/xhtml+xml'/></manifest><spine><itemref idref='a'/></spine></package>","OPS/a.xhtml" to "<html><body><h1>Глава</h1><p>Текст EPUB</p></body></html>"))
        val p=BookParser.parse(epub,"test.epub"); assertEquals("Моя EPUB",p.book.str("title")); assertTrue(p.content.toString().contains("Текст EPUB"))
    }
    @Test fun largeDatabaseValuesAreChunkedAndRestored() { val item=importOne(); val big="абвгд📚".repeat(600000); val bytes=ByteArray(4*1024*1024) { (it%253).toByte() }; val id=uid(); db.runInTransaction { lib.dao.putAsset(AssetRow(id,"application/octet-stream",bytes)); val content=item.content.copyJson().put("extra",big); lib.dao.putContent(ContentRow(item.book.str("id"),content.toString())) }; assertArrayEquals(bytes,lib.dao.asset(id)!!.bytes); assertEquals(big,JSONObject(lib.dao.content(item.book.str("id"))!!.json).getString("extra")); assertTrue(lib.dao.assetChunks(id).size>1) }
    @Test fun atomicRollbackKeepsLibraryUntouched() { importOne(); val before=lib.snapshot(); try { db.runInTransaction { lib.dao.clearBooks(); lib.dao.clearContent(); error("simulated failure") } } catch(_:IllegalStateException) {}; assertEquals(before.books,lib.snapshot().books); assertEquals(before.content,lib.snapshot().content) }
    @Test fun backupRoundTripAndCorruptionRejected() { importOne(); val out=ByteArrayOutputStream(); Backup.write(lib.snapshot(),out); val bytes=out.toByteArray(); val restored=Backup.read(ByteArrayInputStream(bytes)); assertEquals(1,restored.books.size); assertTrue(JSONObject(restored.books[0].json).has("title")); lib.replace(restored); assertEquals(1,lib.allBooks().size); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 16).toByte(); try { Backup.read(ByteArrayInputStream(bytes)); fail("corrupted file accepted") } catch(_:IllegalArgumentException) {}; assertEquals(1,lib.allBooks().size) }
    @Test fun dataSurvivesClosingAndReopeningDatabase() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="persist-${uid()}.sqlite"
        fun open()=Room.databaseBuilder(context,LibraryDb::class.java,name).allowMainThreadQueries().build()
        val first=open(); val initial=Library(first); initial.initialize(); val item=BookParser.parse(xml.toByteArray(),"persist.fb2"); initial.importBooks(listOf(item)); first.close()
        val second=open(); try { val reloaded=Library(second); reloaded.initialize(); assertEquals(item.book.str("title"),reloaded.allBooks()[0].str("title")); assertTrue(JSONObject(reloaded.dao.content(item.book.str("id"))!!.json).getJSONArray("chapters").length()>0) } finally { second.close(); context.deleteDatabase(name) }
    }
    @Test fun filterUsesOnlyCurrentShelfMembership() { val a=JSONObject().put("id","a").put("origin","recommendation").put("shelf","Проза"); val b=JSONObject().put("id","b").put("shelf",REC_SHELF); assertEquals(listOf(a),Library.home(listOf(a,b),false)); assertEquals(2,Library.home(listOf(a,b),true).size); a.put("shelf",REC_SHELF); b.put("shelf","Проза"); assertEquals(listOf(b),Library.home(listOf(a,b),false)) }
    @Test fun recommendationsUseEmbeddedRealCatalogue() { val cat=JSONObject(ApplicationProvider.getApplicationContext<Context>().assets.open("recs.json").bufferedReader().readText()); assertEquals(1333,cat.getJSONArray("b").length()); val ranked=RecommendationEngine.rank(cat,emptyList(),emptySet()); assertEquals(40,ranked.size); val known=ranked[0]; val next=RecommendationEngine.rank(cat,listOf(known),emptySet()); assertFalse(next.any { it.str("rec")==known.str("rec") }) }
    @Test fun streamingArchiveRoundTripAndAtomicFailure() {
        val original=importOne(); val folder=File(ApplicationProvider.getApplicationContext<Context>().cacheDir,"stream-test").apply { mkdirs() }; val archive=File(folder,"library.polka")
        ArchiveIO.write(lib,archive)
        val context=ApplicationProvider.getApplicationContext<Context>()
        fun restore(target:Library) { val executor=java.util.concurrent.Executors.newSingleThreadExecutor(); try { executor.submit { ArchiveIO.restore(context,target,archive) {} }.get() } catch(e:java.util.concurrent.ExecutionException) { throw e.cause!! } finally { executor.shutdown() } }
        val targetDb=Room.databaseBuilder(context,LibraryDb::class.java,"target-${uid()}.sqlite").allowMainThreadQueries().build()
        try {
            val target=Library(targetDb); target.initialize(); restore(target); assertEquals(1,target.allBooks().size)
            val b=target.allBooks()[0]; val c=JSONObject(target.dao.content(b.str("id"))!!.json); val source=c.getJSONObject("source").getString("__polkaBlob"); assertArrayEquals(xml.toByteArray(),target.dao.asset(source)!!.bytes)
            val native=File(folder,"native-export.polka"); ArchiveIO.write(target,native); assertEquals(1,Backup.read(native.inputStream()).books.size)
            val bytes=archive.readBytes(); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte(); archive.writeBytes(bytes)
            try { restore(target); fail("corrupt archive accepted") } catch(_:IllegalArgumentException) {}
            assertEquals(original.book.str("title"),target.allBooks()[0].str("title"))
        } finally { targetDb.close() }
    }
    @Test fun originalWebBackupImports() { val path=System.getProperty("webBackupFixture") ?: return; val snap=FileInputStream(path).use(Backup::read); assertTrue(snap.books.size>=2); lib.replace(snap); assertTrue(lib.allBooks().isNotEmpty()); val archive=File(path+".stream.polka"); ArchiveIO.write(lib,archive); val out=ByteArrayOutputStream(); Backup.write(lib.snapshot(),out); val file=File(path+".native.polka"); file.writeBytes(out.toByteArray()); assertEquals(snap.books.size,Backup.read(file.inputStream()).books.size) }
    @Test fun anchorsBridgeParagraphWhitespaceAndRepeatedText() { val text="Один\n\nдва. Один два."; val range=TextAnchors.locate(text,"Один два",14); assertEquals(11,range!!.first); val bridged=TextAnchors.locate("Один\n\nдва","Один два"); assertEquals(0,bridged!!.first); assertEquals(8,bridged.last) }
    @Test fun exportsUseUpdatedMetadataAndReadableChapters() {
        val item=importOne(); val updated=item.book.copyJson().put("title","Новое название").put("authors",JSONArray(listOf("Новый Автор"))).put("series","Новая серия").put("seriesNo",2).put("lang","ru"); lib.save(updated)
        for(format in listOf("fb2","epub")) { val bytes=BookExport.build(lib,updated,format); val parsed=BookParser.parse(bytes,"export.$format"); assertEquals("Новое название",parsed.book.str("title")); assertEquals("Новый Автор",MainActivity.authors(parsed.book)); assertTrue(parsed.content.toString().contains("Ночь продолжалась")); assertTrue(bytes.isNotEmpty()) }
    }
    @Test fun exportedBooksIncludeCurrentChapterEdits() {
        val item=importOne(); val ch=item.content.getJSONArray("chapters").getJSONObject(0); ch.put("html","<p>Сохранённая правка <strong>жирным</strong>.</p>").put("len",32).put("edited",true); lib.dao.putContent(ContentRow(item.book.str("id"),item.content.toString()))
        for(format in listOf("fb2","epub")) { val parsed=BookParser.parse(BookExport.build(lib,item.book,format),"edited.$format"); assertTrue(parsed.content.toString().contains("Сохранённая правка")); assertTrue(parsed.content.toString().contains("strong")) }
    }
    @Test fun recommendationUndoCanRemoveOnlyNewWishWithoutFile() {
        val cat=JSONObject(ApplicationProvider.getApplicationContext<Context>().assets.open("recs.json").bufferedReader().readText()); val b=RecommendationEngine.rank(cat,emptyList(),emptySet()).first(); lib.save(b.put("shelf",REC_SHELF).put("noFile",true)); assertEquals(1,lib.allBooks().size); lib.dao.deleteBook(b.str("id")); assertTrue(lib.allBooks().isEmpty())
    }

}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualTests {
    @Test fun renderNativeScreens() {
        val app=ApplicationProvider.getApplicationContext<Context>(); app.deleteDatabase("polka.sqlite")
        val db=Room.databaseBuilder(app,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build(); val lib=Library(db); lib.initialize()
        val fb2="""<FictionBook><description><title-info><book-title>Смотритель маяка</book-title><author><first-name>Полка</first-name></author></title-info></description><body><section><title><p>Первая глава</p></title><p>${"Вечером над морем зажёгся маяк. Его свет был виден далеко от берега. ".repeat(80)}</p></section></body></FictionBook>"""
        val one=BookParser.parse(fb2.toByteArray(),"one.fb2"); one.book.put("shelf","Проза").put("openedAt",System.currentTimeMillis()).put("progress",JSONObject().put("ch",0).put("pct",.3))
        val two=BookParser.parse(fb2.replace("Смотритель маяка","Как устроена Полка").toByteArray(),"two.fb2"); two.book.put("shelf","Руководство")
        lib.importBooks(listOf(one,two)); val statsDays=JSONObject(); val minutes=listOf(12,15,4,12,18,14,6); minutes.forEachIndexed { i,m -> statsDays.put(java.time.LocalDate.now().minusDays((6-i).toLong()).toString(),JSONObject().put("ms",m*60000L).put("chars",m*900).put("met",m>=10)) }; lib.dao.putMeta(MetaRow("stats",JSONObject().put("id","stats").put("data",JSONObject().put("goal",10).put("days",statsDays).put("books",JSONObject().put(one.book.str("id"),JSONObject().put("ms",54*60000L).put("chars",48600).put("sessions",6).put("total",100000)).put(two.book.str("id"),JSONObject().put("ms",27*60000L).put("chars",24300).put("sessions",3).put("total",70000)))).toString())); lib.savePrefs(lib.prefs().put("shelves",JSONArray(listOf("Проза","Руководство","Очень длинное название полки на несколько строк")))); db.close()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup(); val activity=controller.get()
        repeat(30) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        val view=activity.window.decorView
        fun captureLayoutOnly(target:View){target.measure(View.MeasureSpec.makeMeasureSpec(activity.d(390),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(activity.d(844),View.MeasureSpec.EXACTLY));target.layout(0,0,activity.d(390),activity.d(844))}
        fun capture(name:String, target:View=view) { val view=target; val density=activity.resources.displayMetrics.density; val w=(390*density).toInt(); val h=(844*density).toInt(); view.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); view.layout(0,0,w,h); shadowOf(Looper.getMainLooper()).idleFor(700,java.util.concurrent.TimeUnit.MILLISECONDS); val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap)); File(System.getProperty("qaOutputDir", "build/qa")).mkdirs(); FileOutputStream(File(System.getProperty("qaOutputDir", "build/qa"),"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
        capture("home")
        fun captureDialog(name:String) { activity.bookDetailSheet?.let { sheet->capture(name);sheet.viewTreeObserver.dispatchOnPreDraw();sheet.freezeMotion();sheet.sampleMotion(460);capture(name);sheet.finish();return }; val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing); val popup=dialog.window!!.decorView; val density=activity.resources.displayMetrics.density; val w=(390*density).toInt(); val h=(844*density).toInt(); popup.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h-activity.d(48),View.MeasureSpec.AT_MOST)); val ph=popup.measuredHeight; popup.layout(0,0,w,ph); shadowOf(Looper.getMainLooper()).idle(); val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); val canvas=Canvas(bitmap); view.draw(canvas); canvas.drawColor(0x88000000.toInt()); canvas.save(); canvas.translate(0f,(h-ph).toFloat()); popup.draw(canvas); canvas.restore(); FileOutputStream(File(System.getProperty("qaOutputDir", "build/qa"),"$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; dialog.dismiss(); shadowOf(Looper.getMainLooper()).idleFor(650,java.util.concurrent.TimeUnit.MILLISECONDS) }
        fun clickDescription(label:String) { fun walk(v:View):Boolean { if(v.contentDescription==label) { v.performClick(); return true }; if(v is android.view.ViewGroup) for(i in 0 until v.childCount) if(walk(v.getChildAt(i))) return true; return false }; assertTrue(walk(view)); repeat(10) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() } }
        clickDescription("Фильтры и сортировка"); captureDialog("filters")
        activity.settings(); captureDialog("settings")
        activity.detail(activity.books.first()); captureDialog("book-detail")
        activity.metadataEditor(activity.books.first()); captureDialog("metadata-editor")
        // Regression: selecting a cover must survive the metadata form's Save action.
        val editedBook=activity.books.first(); activity.metadataEditor(editedBook); val metadataDialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        val coverFile=File(activity.cacheDir,"test-cover.png"); val coverBitmap=Bitmap.createBitmap(8,12,Bitmap.Config.ARGB_8888); coverBitmap.eraseColor(0xff4c6a72.toInt()); coverFile.outputStream().use { coverBitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; activity.coverTarget=editedBook.str("id"); MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,android.content.Intent::class.java).apply { isAccessible=true }.invoke(activity,15,android.app.Activity.RESULT_OK,android.content.Intent().setData(android.net.Uri.fromFile(coverFile))); repeat(15) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        val selectedCover=editedBook.getJSONObject("cover").str("__polkaBlob"); assertTrue(selectedCover.isNotBlank())
        fun clickSave(v:View):Boolean { if(v is android.widget.TextView && v.text.toString()=="Сохранить") { v.performClick(); return true }; if(v is android.view.ViewGroup) for(i in 0 until v.childCount) if(clickSave(v.getChildAt(i))) return true; return false }
        assertTrue(clickSave(metadataDialog.window!!.decorView)); repeat(15) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }; val savedBook=activity.books.first { it.str("id")==editedBook.str("id") }; assertEquals(selectedCover,savedBook.getJSONObject("cover").str("__polkaBlob")); val reset=savedBook.copyJson(); reset.remove("cover"); activity.saveBook(reset); repeat(15) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        activity.profile(); repeat(15) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }; capture("profile")
        val profileScroll=activity.profileOverlay as android.widget.ScrollView
        for((name,y) in listOf("profile-activity" to 650,"profile-books" to 1240,"profile-library" to 1900,"profile-goal" to 2500)) { profileScroll.scrollTo(0,activity.d(y)); capture(name) }
        profileScroll.scrollTo(0,0); activity.onBackPressed()
        clickDescription("Полки"); capture("shelves")
        activity.prefs.put("ui","light"); activity.render(); capture("shelves-light")
        activity.settings(); captureDialog("settings-light")
        activity.prefs.put("ui","dark"); activity.render()
        clickDescription("Для вас"); capture("recommendations-onboarding"); activity.work({ activity.library.dao.putMeta(MetaRow("recs",JSONObject().put("id","recs").put("data",JSONObject().put("onboarded",true).put("coached",true)).toString())) }) { activity.render() }; repeat(20) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }; capture("recommendations")
        clickDescription("Мои книги")
        activity.openBook(activity.books.first()); repeat(20) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle();captureLayoutOnly(view);view.viewTreeObserver.dispatchOnPreDraw() }; capture("reader"); val firstOffset=activity.reader!!.pageBase(); val firstProgress=activity.reader!!.progress(); activity.reader!!.turn(1); shadowOf(Looper.getMainLooper()).idle(); assertTrue("Page offset must advance",activity.reader!!.pageBase()>firstOffset); assertTrue("Book progress must advance",activity.reader!!.progress()>firstProgress); activity.reader!!.save(); activity.reader!!.settings(); captureDialog("reader-settings"); activity.reader!!.editChapter(); capture("page-editor-inline"); activity.reader!!.pageEditor!!.cancel()
        activity.closeReader(); repeat(15) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle() }
        clickDescription("Цитаты"); capture("quotes-empty")
        controller.pause().stop().destroy()
    }
}
