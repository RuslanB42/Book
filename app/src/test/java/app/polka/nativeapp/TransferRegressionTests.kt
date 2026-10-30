package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.os.Looper
import android.text.Spanned
import android.text.style.ImageSpan
import android.view.*
import android.widget.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.*
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TransferRegressionTests {
    private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var a:MainActivity
    private fun drain() { repeat(12) { Thread.sleep(50); Shadows.shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS) } }
    @Before fun open() {
        val context=ApplicationProvider.getApplicationContext<Context>(); context.deleteDatabase("polka.sqlite")
        val db=Room.databaseBuilder(context,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build(); val lib=Library(db); lib.initialize(); lib.savePrefs(lib.prefs().put("motion","reduced").put("shelves",JSONArray(listOf("Проза","Руководство","Очень длинное название полки на несколько строк"))));
        val xml="<FictionBook><description><title-info><book-title>Смотритель маяка</book-title><author><first-name>Полка</first-name></author></title-info></description><body><section><title><p>Первая глава</p></title><p>${"Вечером над морем зажёгся маяк. Его свет был виден далеко от берега. ".repeat(80)}</p></section></body></FictionBook>";
        val one=BookParser.parse(xml.toByteArray(),"one.fb2"); one.book.put("shelf","Проза").put("sample",true).put("openedAt",System.currentTimeMillis()).put("progress",JSONObject().put("ch",0).put("pct",.3)); val two=BookParser.parse(xml.replace("Смотритель маяка","Как устроена Полка").toByteArray(),"two.fb2");two.book.put("shelf","Руководство").put("sample",true);lib.importBooks(listOf(one,two)); db.close()
        controller=Robolectric.buildActivity(MainActivity::class.java).setup(); a=controller.get(); drain()
    }
    @After fun close() { controller.pause().stop().destroy();org.robolectric.shadows.ShadowChoreographer.setPaused(false) }
    private fun catalogue()=JSONObject(a.assets.open("recs.json").bufferedReader().readText())
    private fun descendants(view:View):List<View> = listOf(view)+(if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList())
    private fun click(label:String) { val v=descendants(a.root).firstOrNull { (it is TextView && it.text.toString()==label) || it.contentDescription==label }; assertNotNull(label,v); v!!.performClick(); drain() }
    private fun layout() { val w=a.d(360); val h=a.d(780); a.root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); a.root.layout(0,0,w,h);a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster(); Shadows.shadowOf(Looper.getMainLooper()).idleFor(650,TimeUnit.MILLISECONDS); a.root.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); a.root.layout(0,0,w,h) }
    private fun screenshot(name:String,target:View=a.root) { layout(); val bitmap=Bitmap.createBitmap(a.d(360),a.d(780),Bitmap.Config.ARGB_8888); target.draw(Canvas(bitmap)); drain(); target.draw(Canvas(bitmap)); File(System.getProperty("qaOutputDir"),"s22").mkdirs(); File(System.getProperty("qaOutputDir"),"s22/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
    @Test fun onboardingPagesMatchAllOriginalJavaScriptFixtures() {
        val fixture=JSONObject(File(System.getProperty("recReferenceFixture")).readText()); val cat=catalogue()
        fixture.getJSONArray("cases").objects().forEachIndexed { i,test -> val pages=RecommendationEngine.startPages(cat,test.getJSONArray("books").objects(),test.getJSONObject("state")).map { p -> p.map { it.str("rec") } }; val expected=test.getJSONArray("startPages"); assertEquals("page count $i",expected.length(),pages.size); pages.forEachIndexed { j,p -> assertEquals("case $i page $j",expected.getJSONArray(j).strings(),p); assertEquals(12,p.size) } }
    }
    @Test fun onboardingSelectionSurvivesPageChangeAndCommitsOnlyOnGo() {
        val host=a.column(); a.page.addView(host); val state=JSONObject(); val deck=RecommendationDeck(a,host,catalogue(),state); deck.start(); layout()
        val first=descendants(host).filterIsInstance<SelectedRecommendation>().first(); (first.parent as View).performClick(); assertFalse(state.has("picks")); deck.starterPage++; deck.onboarding(); assertFalse(state.has("picks")); deck.starterPage=0; deck.onboarding(); assertTrue((descendants(host).filterIsInstance<SelectedRecommendation>().first().parent as View).isSelected)
        click("Подобрать книги · 1"); assertEquals(1,state.getJSONArray("picks").length()); assertTrue(state.optBoolean("onboarded")); assertEquals(1,RecommendationEngine.positive(catalogue(),a.books,state))
    }
    @Test fun skippingStarterDoesNotCommitTemporarySelection() {
        val host=a.column(); a.page.addView(host); val state=JSONObject(); val deck=RecommendationDeck(a,host,catalogue(),state); deck.start(); (descendants(host).filterIsInstance<SelectedRecommendation>().first().parent as View).performClick(); click("Пропустить"); assertTrue(state.optBoolean("onboarded")); assertEquals(0,state.optJSONArray("picks")?.length() ?: 0)
    }
    @Test fun wishIsSavedToShelfAndUndoAfterMovingShelfRemovesOnlyItsNewCard() {
        val host=a.column(); a.page.addView(host); val state=JSONObject().put("onboarded",true).put("coached",true); val deck=RecommendationDeck(a,host,catalogue(),state); deck.start(); val first=deck.list.first(); deck.vote("want",0); drain(); assertFalse(deck.busy)
        val saved=a.books.single { it.str("rec")==first.str("rec") }; assertEquals(REC_SHELF,saved.str("shelf")); assertTrue(saved.optBoolean("noFile")); a.saveBook(saved.copyJson().put("shelf","Проза")); drain(); deck.undo(); drain(); assertFalse(deck.busy); assertTrue(a.books.none { it.str("id")==saved.str("id") }); assertEquals(first.str("rec"),deck.list.first().str("rec"))
    }
    @Test fun mergedTableRetainsImagesFormattingAndClickableLinks() {
        val bitmap=Bitmap.createBitmap(30,20,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }; val bytes=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray(); bitmap.recycle()
        val result=ReadingText(a,19f,312,190,mapOf("pic.png" to bytes)).render("<table><tr><td rowspan='2'><strong>Объединено</strong><br><a href='#note'>Ссылка</a><img src='pic.png'/></td><th colspan='2'>Шапка</th></tr><tr><td>Лево</td><td>Право</td></tr></table>")
        val spans=result.text.getSpans(0,result.text.length,TableRowSpan::class.java); assertTrue(spans.isNotEmpty()); val model=spans.first().model; assertEquals(3,model.columns); assertEquals(4,model.cells.size); val merged=model.cells.first(); assertEquals(2,merged.rowspan); assertTrue((merged.text as Spanned).getSpans(0,merged.text.length,ImageSpan::class.java).isNotEmpty()); assertEquals(model.heights.sum().toFloat(),model.bounds(merged).bottom,0f)
        val off=merged.text.indexOf("Ссылка"); val line=merged.layout.getLineForOffset(off); val x=model.bounds(merged).left+model.pad*2+merged.layout.getPrimaryHorizontal(off+1); val y=model.bounds(merged).top+model.pad+merged.layout.getLineTop(line)+2; assertEquals("#note",model.linkAt(x,y)); assertTrue(result.text.contains("Лево\tПраво"))
    }
    @Test fun nativeBookOpeningUsesLiveReaderAndMatchesOriginalTimeline() {
        a.root.setPadding(0,a.d(24),0,a.d(24));layout();val book=a.books.first{it.str("title")=="Смотритель маяка"};a.prefs.put("motion","normal");val snapshot=a.openingSnapshot(book.str("id"));assertNotNull(snapshot);a.prefs.put("motion","reduced");a.openBook(book.copyJson().put("progress",book.optJSONObject("progress")!!.copyJson().put("nativeOffset",0)));drain();layout();val reader=a.reader!!;val live=reader.view;val before=reader.pageBase();val stage=BookOpeningStage(a,snapshot!!,live);a.root.addView(stage,FrameLayout.LayoutParams(-1,-1));layout()
        assertSame(live,stage.getChildAt(0));assertTrue("Keep a bounded source scene, not a solid veil",snapshot.scene.width in 2..720)
        val dir=File(System.getProperty("qaOutputDir"),"motion/opening-native").apply{mkdirs()};val bitmap=Bitmap.createBitmap(360,732,Bitmap.Config.ARGB_8888)
        for(i in 0..105){stage.frame(i/105f);val canvas=Canvas(bitmap);canvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);stage.draw(canvas);File(dir,"frame-%03d.png".format(i)).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        stage.finish();assertSame(live,a.root.getChildAt(0));assertSame(live,a.reader!!.view);assertEquals(before,reader.pageBase());assertEquals(1f,live.getChildAt(0).alpha,0f);assertTrue(snapshot.cover.isRecycled);assertNull(stage.parent);stage.finish();bitmap.recycle()
    }
    @Test fun normalOpeningWaitsForStableLayoutThenRestoresSameReader(){
        a.root.setPadding(0,a.d(24),0,a.d(24));layout();a.prefs.put("motion","normal");a.openBook(a.books.first());drain();assertNotNull(a.reader);val live=a.reader!!.view
        repeat(8){layout();a.root.viewTreeObserver.dispatchOnPreDraw();Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS)}
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(2400,TimeUnit.MILLISECONDS);layout();assertSame(live,a.root.getChildAt(0));assertEquals(1f,live.alpha,0f);assertTrue(live.width>0);assertTrue(a.reader!!.body.text.isNotBlank());assertFalse(descendants(a.root).any{it is BookOpeningStage})
    }
    @Test fun openingKeyframesAndCancellationAreDeterministic(){
        val source=OpeningTimeline.Pose(20f,348f,1f,1f,0f,0f);fun frame(t:Float)=OpeningTimeline.sample(t,360f,732f,144f,216f,source)
        assertEquals(source,frame(0f).pose);assertEquals(0f,frame(.30f).swing,0f)
        assertTrue(frame(.30f).pose.sx>source.sx);assertEquals(0f,frame(.63f).zoom,0f)
        assertEquals(-166f,frame(.63f).swing,.0001f);assertEquals(1f,frame(1f).chrome,0f)
        assertEquals(0f,frame(1f).pose.x,0f);assertEquals(0f,frame(1f).pose.y,0f)
        assertEquals(2.5f,frame(1f).pose.sx,.0001f);assertEquals(732f/216,frame(1f).pose.sy,.0001f);assertEquals(0f,frame(1f).volume,0f)
        var commits=0;val v=View(a);val motion=NativeMotion(v,MotionPose(),MotionPose(alpha=0f),200,complete={commits++});motion.start();motion.sample(80);assertTrue(v.alpha in 0f..1f);motion.cancel();assertEquals(0,commits);assertEquals(0f,v.alpha,0f)
    }
    @Test fun openingPhasesAreContinuousMonotonicAndExactlyReversible(){
        val source=OpeningTimeline.Pose(20f,348f,.5f,.5f,0f,0f)
        fun sample(t:Float)=OpeningTimeline.sample(t,360f,780f,144f,216f,source)
        var previous=sample(0f)
        for(i in 1..1000){val t=i/1000f;val f=sample(t)
            assertTrue(f.pose.x.isFinite()&&f.pose.y.isFinite());assertTrue(f.zoom>=previous.zoom);assertTrue(f.swing<=previous.swing+.0001f)
            assertTrue("no discontinuous position",kotlin.math.abs(f.pose.x-previous.pose.x)<3f&&kotlin.math.abs(f.pose.y-previous.pose.y)<4f)
            assertTrue("no scale jump",kotlin.math.abs(f.pose.sx-previous.pose.sx)<.04f)
            if(t<=.30f){assertEquals(0f,f.swing,0f);assertEquals(0f,f.zoom,0f)}
            if(t<=.63f)assertEquals(0f,f.zoom,0f)
            // Reverse playback samples the same absolute geometry, independent of frame rate.
            assertEquals(f.pose.x,sample(1f-(1f-t)).pose.x,.0002f);assertEquals(f.swing,sample(1f-(1f-t)).swing,.0002f)
            previous=f
        }
        for(t in listOf(.30f,.63f)){val left=sample(t-.0001f);val right=sample(t+.0001f);assertEquals(left.pose.x,right.pose.x,.002f);assertEquals(left.swing,right.swing,.002f)}
    }
    @Test fun gridKeepsThousandBooksBoundedToViewport(){
        var created=0;val grid=VirtualGrid(a,1000,2,a.d(24),a.d(30),{_,w->w*2}){created++;TextView(a).apply{text="Книга $it";minHeight=a.d(200)}};a.page.removeAllViews();a.page.addView(grid);layout();assertTrue("bounded views: ${grid.materializedCount}",grid.materializedCount<30);assertTrue(created<30);descendants(a.root).filterIsInstance<ScrollView>().first().scrollTo(0,a.d(60000));grid.sync();assertTrue(grid.materializedCount<30);assertTrue(grid.childCount<30)
    }
    @Test fun recommendationExpansionStaggerAndReturnAreDeterministic(){
        click("Для вас");a.page.removeAllViews();val host=a.column();a.page.addView(host);val state=JSONObject().put("onboarded",true).put("coached",true);val deck=RecommendationDeck(a,host,catalogue(),state);deck.start();layout();val source=descendants(host).filterIsInstance<RecommendationCard>().last();a.prefs.put("motion","normal");var closed=0;val dialog=RecommendationExpansion(a,source.book,source){closed++};dialog.show();val decor=dialog.contentRoot
        fun measure(){decor.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));decor.layout(0,0,a.d(360),a.d(780))};measure();Shadows.shadowOf(Looper.getMainLooper()).idle();measure();decor.awaitGlowRaster();dialog.freezeMotion();val dir=File(System.getProperty("qaOutputDir"),"motion/recommendation-native").apply{mkdirs()};val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888)
        for(i in 0..60){dialog.sampleMotion(i*16L);val canvas=Canvas(bitmap);canvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);decor.draw(canvas);File(dir,"frame-%03d.png".format(i)).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        val big=descendants(decor).filterIsInstance<CoverView>().single();assertEquals(a.resources.displayMetrics.widthPixels*.44f,big.width.toFloat(),a.d(1).toFloat());dialog.sampleMotion(0);assertTrue(big.scaleX>1f);dialog.sampleMotion(620);assertEquals(1f,big.scaleX,.0001f);assertEquals(0f,big.translationY,.0001f);assertEquals(View.INVISIBLE,source.visibility);val sameRoot=a.root.getChildAt(0);val closeDir=File(System.getProperty("qaOutputDir"),"motion/recommendation-close-native").apply{mkdirs()};dialog.dismiss();dialog.freezeMotion();for(i in 0..32){dialog.sampleMotion(i*16L,true);val canvas=Canvas(bitmap);canvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);a.root.draw(canvas);File(closeDir,"frame-%03d.png".format(i)).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}};dialog.sampleMotion(260,true);assertTrue(big.scaleX>1f);dialog.stopMotion();assertSame(sameRoot,a.root.getChildAt(0));assertEquals(View.VISIBLE,source.visibility);assertEquals(1,closed);assertFalse(dialog.isShowing);val finalCanvas=Canvas(bitmap);finalCanvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);a.root.draw(finalCanvas);File(closeDir,"frame-033.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun quotesAreGroupedAndReturnWithoutLosingBooks(){
        a.books=a.books.mapIndexed{i,b->b.copyJson().put("quotes",JSONArray((0 until if(i==0)3 else 1).map{j->JSONObject().put("id","q-$i-$j").put("ch",0).put("start",j*10).put("at",1000+j).put("text","Цитата $j. Вечером над морем зажёгся маяк, и его свет был виден далеко от берега.")}))};a.render(false,false);layout();click("Цитаты");layout();screenshot("quotes-groups");val stack=descendants(a.root).first{it.contentDescription?.toString()?.endsWith(": 3 цитаты")==true};stack.performClick();layout();assertNotNull(a.quoteBookId);assertEquals(3,descendants(a.root).count{it.contentDescription=="Копировать цитату"});screenshot("quotes-book");a.openQuoteGroup(null);layout();assertNull(a.quoteBookId);assertEquals(2,a.books.size)
    }
    @Test fun readGestureWaitsForRatingAndSkipCreatesUnratedSignal() {
        val host=a.column(); a.page.removeAllViews(); a.page.addView(host); val state=JSONObject().put("onboarded",true).put("coached",true); val deck=RecommendationDeck(a,host,catalogue(),state); deck.start(); layout(); val book=deck.list.first(); val card=descendants(host).filterIsInstance<RecommendationCard>().last(); deck.fly(card,"read"); assertTrue(deck.busy); assertNull(state.optJSONObject("sw")); val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); assertTrue(dialog.isShowing); val go=descendants(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString()=="Готово" }; assertFalse(go.isEnabled); descendants(dialog.window!!.decorView).filterIsInstance<TextView>().first { it.text.toString()=="Без оценки" }.performClick(); drain(); assertFalse(deck.busy); assertEquals("read",state.getJSONObject("sw").getJSONObject(book.str("rec")).str("a")); assertEquals(0,state.getJSONObject("sw").getJSONObject(book.str("rec")).optInt("r"))
    }
    @Test @Config(sdk=[35]) fun s22InsetsNavigationAndKeyboardStayInsideSafeArea() {
        fun insets(nav:Int,keyboard:Int=0)=WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(),Insets.of(0,a.d(24),0,0)).setInsets(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,a.d(nav))).setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,a.d(keyboard))).build()
        for(nav in listOf(24,48)) { a.root.dispatchApplyWindowInsets(insets(nav)); layout(); assertEquals(a.d(24),a.appInsets.top); assertEquals(a.d(nav),a.appInsets.bottom); val add=descendants(a.root).first { it.contentDescription=="Добавить книги" }; val r=Rect(); add.getDrawingRect(r); a.root.offsetDescendantRectToMyCoords(add,r); assertTrue(r.bottom<=a.root.height-a.d(nav)); assertTrue(r.left>=0); val navigation=descendants(a.root).filterIsInstance<PolkaNavigation>().single(); assertTrue(navigation.right<=add.left); screenshot("home-${nav}dp-bars") }
        a.root.dispatchApplyWindowInsets(insets(24,300)); layout(); assertEquals(a.d(300),a.appInsets.bottom); a.root.dispatchApplyWindowInsets(insets(24)); layout(); assertEquals(a.d(24),a.appInsets.bottom)
        click("Полки"); screenshot("shelves"); a.prefs.put("ui","light"); a.render(); screenshot("shelves-light"); a.prefs.put("ui","dark"); a.render(); click("Для вас"); layout(); screenshot("recommendations-starter")
        a.work({ a.library.dao.putMeta(MetaRow("recs",JSONObject().put("id","recs").put("data",JSONObject().put("onboarded",true).put("coached",true)).toString())) }) { a.render() }; drain(); layout(); screenshot("recommendations-deck")
        val card=descendants(a.root).filterIsInstance<RecommendationCard>().last(); card.performClick(); drain(); val expanded=a.recommendationExpansion!!; val popup=expanded.contentRoot; popup.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(732),View.MeasureSpec.EXACTLY)); popup.layout(0,0,a.d(360),a.d(732)); Shadows.shadowOf(Looper.getMainLooper()).idle(); val picture=Bitmap.createBitmap(a.d(360),a.d(732),Bitmap.Config.ARGB_8888); popup.draw(Canvas(picture)); File(System.getProperty("qaOutputDir"),"s22/recommendations-expanded.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG,100,it) }; picture.recycle(); expanded.dismiss(); drain()
        a.showProfile(JSONObject().put("goal",10),7,false); screenshot("profile"); a.profileOverlay?.let { a.root.removeView(it) }; a.profileOverlay=null
        a.settings(); val sheet=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog(); val settings=sheet.window!!.decorView; settings.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(684),View.MeasureSpec.AT_MOST)); settings.layout(0,0,a.d(360),settings.measuredHeight); val shot=Bitmap.createBitmap(a.d(360),settings.measuredHeight,Bitmap.Config.ARGB_8888); settings.draw(Canvas(shot)); File(System.getProperty("qaOutputDir"),"s22/settings.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG,100,it) }; shot.recycle(); sheet.dismiss(); drain()
    }

    @Test fun pageTurnWaitsForNewLayoutAndKeepsOutgoingBitmapAlive(){
        a.openBook(a.books.first());val r=readyReader();a.prefs.put("motion","normal");r.showChrome(false);layout();val builds=r.layoutBuilds;val original=r.pageBase()
        r.turn(1);val base=r.pageBase();assertNotNull("motion=${a.prefs.str("motion")} pending=${r.pageMotionPending} running=${r.pageMotionRunning}",r.outgoingPage);val ghost=r.outgoingPage!!;val bitmap=ghost.sourceBitmap
        assertTrue(r.pageMotionPending);assertFalse(r.pageMotionRunning);assertEquals("live text must never slide away",0f,r.scroll.translationX,0f);assertEquals(0f,ghost.translationX,0f)
        r.turn(1);assertEquals("rapid tap must not tear down the in-flight page",base,r.pageBase());assertSame(ghost,r.outgoingPage)
        val dir=File(System.getProperty("qaOutputDir"),"reader-0.6.2").apply{mkdirs()}
        a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));r.view.viewTreeObserver.dispatchOnPreDraw();assertTrue(r.pageMotionRunning)
        for(ms in listOf(0L,16L,100L,260L,500L)){r.samplePageMotion(ms);val shot=Bitmap.createBitmap(a.d(360),a.d(780),Bitmap.Config.ARGB_8888);a.root.draw(Canvas(shot));File(dir,"turn-$ms.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle();assertEquals(1f,r.body.alpha,0f);assertFalse(bitmap.isRecycled)}
        r.stopPageMotion();assertNull(r.outgoingPage);assertFalse(bitmap.isRecycled);assertEquals(0f,r.scroll.translationX,0f);assertEquals(builds,r.layoutBuilds)
        r.turn(-1);assertEquals(original,r.pageBase());r.stopPageMotion()
    }
    private fun storedContent(id:String):ContentRow? { val job=java.util.concurrent.FutureTask<ContentRow?>{a.library.dao.content(id)};Thread(job).start();return job.get() }
    private fun rawFrame(name:String):Bitmap { val shot=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(shot));val dir=File(System.getProperty("qaOutputDir"),"reader-0.6.3").apply{mkdirs()};File(dir,"$name.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};return shot }
    @Test @Config(sdk=[28,35]) fun actualTouchCannotFocusTintPageOrOutgoingSnapshot(){
        a.openBook(a.books.first().copyJson().put("progress",JSONObject().put("ch",0).put("nativeOffset",0)));val r=readyReader();r.showChrome(false);layout();a.prefs.put("motion","normal");val base=r.pageBase();val ink=r.backgroundColor
        org.robolectric.shadows.ShadowChoreographer.setPaused(true)
        assertFalse(r.body.defaultFocusHighlightEnabled);assertFalse(r.scroll.defaultFocusHighlightEnabled)
        r.body.requestFocus();r.scroll.scrollTo(0,a.d(200));assertEquals("A page must not scroll to a focused TextView",0,r.scroll.scrollY);assertFalse(r.scroll.requestChildRectangleOnScreen(r.body,Rect(0,a.d(600),a.d(100),a.d(900)),true));val before=rawFrame("touch-before-api${android.os.Build.VERSION.SDK_INT}");assertEquals(ink,before.getPixel(a.d(2),a.d(100)))
        val time=android.os.SystemClock.uptimeMillis();val x=r.body.width*.9f;val y=a.d(180).toFloat()
        val down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0);r.body.dispatchTouchEvent(down);down.recycle();val pressed=rawFrame("touch-down-api${android.os.Build.VERSION.SDK_INT}");assertEquals(ink,pressed.getPixel(a.d(2),a.d(100)))
        val up=MotionEvent.obtain(time,time+40,MotionEvent.ACTION_UP,x,y,0);r.body.dispatchTouchEvent(up);up.recycle();repeat(360){if(r.pageBase()==base)Shadows.shadowOf(Looper.getMainLooper()).idleFor(1,TimeUnit.MILLISECONDS)}
        assertTrue("Must exercise the GestureDetector, not call turn directly",r.pageBase()>base);assertNotNull("motion=${a.prefs.str("motion")} pending=${r.pageMotionPending} running=${r.pageMotionRunning}",r.outgoingPage);val ghost=r.outgoingPage!!;val bitmap=ghost.sourceBitmap;assertEquals(ink,bitmap.getPixel(a.d(2),a.d(100)));assertEquals("Outgoing page needs bounds before the next layout",r.view.width,ghost.width);assertEquals(r.view.height,ghost.height);val pending=rawFrame("touch-pending-api${android.os.Build.VERSION.SDK_INT}");assertEquals(ink,pending.getPixel(a.d(2),a.d(100)));val pixels=IntArray(pending.width*a.d(500));pending.getPixels(pixels,0,pending.width,0,0,pending.width,a.d(500));assertTrue("Pending transition must retain text, not a blank background",pixels.count{it!=ink}>1000)
        a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));r.view.viewTreeObserver.dispatchOnPreDraw()
        for(ms in listOf(0L,8L,16L,32L,100L,260L,500L)){r.samplePageMotion(ms);val frame=rawFrame("touch-turn-${android.os.Build.VERSION.SDK_INT}-$ms");if(ms==0L)assertEquals("No initial full-page flash",ink,frame.getPixel(a.d(2),a.d(4)));assertEquals("No vertical jump at $ms",0,r.scroll.scrollY);frame.recycle()}
        r.stopPageMotion();before.recycle();pressed.recycle();pending.recycle()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(400,TimeUnit.MILLISECONDS);val swipeBase=r.pageBase();val now=android.os.SystemClock.uptimeMillis()
        for((action,dx,dt) in listOf(Triple(MotionEvent.ACTION_DOWN,.9f,0L),Triple(MotionEvent.ACTION_MOVE,.5f,60L),Triple(MotionEvent.ACTION_UP,.1f,120L))){val event=MotionEvent.obtain(now,now+dt,action,r.body.width*dx,a.d(180).toFloat(),0);r.body.dispatchTouchEvent(event);event.recycle();assertEquals(0,r.scroll.scrollY)}
        assertTrue("Real fling must advance one page",r.pageBase()>swipeBase);assertNotNull(r.outgoingPage);rawFrame("swipe-pending-api${android.os.Build.VERSION.SDK_INT}").recycle();r.stopPageMotion();org.robolectric.shadows.ShadowChoreographer.setPaused(false)
    }
    @Test fun inlineEditorStartsWithOnlyTheVisiblePageAndKeepsSameReader(){
        a.openBook(a.books.first());val r=readyReader();r.turn(1);layout();val base=r.pageBase();val visible=r.body.text.toString();assertTrue(base>0);val root=a.root;val scroll=r.scroll;val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
        r.editChapter();layout();val edit=r.pageEditor!!;for(line in 0 until edit.editor.lineCount)assertTrue("Editable glyphs must fit line $line: ${edit.editor.layout.getLineMax(line)} > ${edit.editor.layout.width}",edit.editor.layout.getLineMax(line)<=edit.editor.layout.width+1);assertEquals("Opening a page editor must not skip its first line",0,r.scroll.scrollY);assertEquals(0,edit.editor.scrollY);assertEquals("Focus must not move caret to page end",0,edit.editor.selectionStart);assertEquals(visible,edit.editor.text.toString());assertSame(r,a.reader);assertSame(root,a.root);assertSame(scroll,edit.editor.parent);assertSame(dialog,org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog());assertTrue(edit.editor.length()<2500);assertFalse(descendants(a.root).filterIsInstance<TextView>().any{it.text.toString()=="Сохранить главу"});val old=r.pageBase();r.turn(1);assertEquals(old,r.pageBase());rawFrame("edit-current-page").recycle()
        r.safeArea(0,a.d(28),0,a.d(300));layout();assertEquals(visible,edit.editor.text.toString());assertSame(edit,r.pageEditor);assertEquals("IME must not move caret",0,edit.editor.selectionStart);rawFrame("edit-with-ime-inset").recycle();r.safeArea(0,0,0,0);edit.cancel();layout();assertNull(r.pageEditor);assertEquals(base,r.pageBase());assertEquals(visible,r.body.text.toString())
    }
    @Test fun inlineEditorCommitsOnlyPageThenReopensWithSameText(){
        a.openBook(a.books.first());val r=readyReader();r.turn(1);layout();val base=r.pageBase();val fullBefore=storedContent(r.book.str("id"))!!.json;val old=JSONObject(fullBefore).getJSONArray("chapters").getJSONObject(r.chapter).str("html")
        r.editChapter();val edit=r.pageEditor!!;edit.editor.text.insert(5," ИСПРАВЛЕНО ");edit.undo();assertFalse(edit.dirty());edit.redo();assertTrue(edit.dirty());edit.save();drain();layout();drain();assertNull(r.pageEditor);assertSame(r,a.reader);val stored=JSONObject(storedContent(r.book.str("id"))!!.json).getJSONArray("chapters").getJSONObject(r.chapter).str("html");assertTrue(stored.contains("ИСПРАВЛЕНО"));assertTrue(stored.endsWith(old.takeLast(100)));assertTrue(r.body.text.contains("ИСПРАВЛЕНО"));rawFrame("edit-saved").recycle();r.editChapter();assertTrue(r.pageEditor!!.editor.text.contains("ИСПРАВЛЕНО"));r.pageEditor!!.cancel();a.closeReader();drain();a.openBook(a.books.first{it.str("id")==r.book.str("id")});val reopened=readyReader();assertTrue(reopened.body.text.contains("ИСПРАВЛЕНО"));assertTrue(reopened.pageBase()<=base)
    }
    private fun awaitCondition(label:String,ready:()->Boolean){
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        while(!ready()&&System.nanoTime()<deadline){Thread.sleep(20);Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS)}
        assertTrue(label,ready())
    }
    @Test fun inlineEditorFailureRollsBackAndLeavesDraftForRetry(){
        a.openBook(a.books.first());val r=readyReader()
        val stored=storedContent(r.book.str("id"))!!.json;val beforeEdits=r.book.optInt("edits")
        r.editChapter();val edit=r.pageEditor!!;val persist=edit.commit
        edit.editor.text.insert(10," НЕ ТЕРЯТЬ ")
        edit.commit={updated,_->a.library.db.runInTransaction{a.library.dao.putContent(ContentRow(r.book.str("id"),updated.toString()));throw java.io.IOException("тестовый отказ записи")}}
        edit.save();awaitCondition("failure callback must release the editor"){!edit.saving}
        assertSame(edit,r.pageEditor);assertTrue("draft remains enabled",edit.editor.isEnabled)
        assertTrue("draft text remains after rollback",edit.editor.text.contains("НЕ ТЕРЯТЬ"))
        assertEquals(stored,storedContent(r.book.str("id"))!!.json);assertEquals(beforeEdits,r.book.optInt("edits"))
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.dismiss();rawFrame("edit-save-error").recycle()
        edit.commit=persist;edit.save()
        awaitCondition("retry must finish and repaint the live reader"){r.pageEditor==null&&r.body.text.contains("НЕ ТЕРЯТЬ")}
        layout();assertNull(r.pageEditor);assertTrue(r.body.text.contains("НЕ ТЕРЯТЬ"))
    }
    @Test fun backDoesNotDiscardDirtyInlinePage(){
        a.openBook(a.books.first());val r=readyReader();val visible=r.body.text.toString();r.editChapter();r.pageEditor!!.editor.text.append(" ЧЕРНОВИК");a.onBackPressed();assertSame(r,a.reader);assertNotNull(r.pageEditor);val confirm=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertTrue(confirm.isShowing)
        descendants(confirm.window!!.decorView).filterIsInstance<TextView>().first{it.text.toString()=="Отмена"}.performClick();assertTrue(r.pageEditor!!.editor.text.contains("ЧЕРНОВИК"));r.pageEditor!!.cancel();val cancel=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();descendants(cancel.window!!.decorView).filterIsInstance<TextView>().first{it.text.toString()=="Продолжить"}.performClick();assertNull(r.pageEditor);assertSame(r,a.reader);assertEquals(visible,r.body.text.toString())
    }
    @Test @Config(sdk=[35]) fun readerUsesCutoutSafeAreaWithoutSystemBarBandsAndRestoresLibrary(){
        a.openBook(a.books.first());val r=readyReader()
        val cutout=DisplayCutout(Rect(0,a.d(28),0,0),listOf(Rect(a.d(168),0,a.d(192),a.d(28))))
        fun inset(ime:Int=0)=WindowInsets.Builder().setDisplayCutout(cutout).setInsets(WindowInsets.Type.statusBars(),Insets.of(0,a.d(28),0,0)).setInsets(WindowInsets.Type.navigationBars(),Insets.of(0,0,0,a.d(24))).setInsets(WindowInsets.Type.systemGestures(),Insets.of(0,0,0,a.d(16))).setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,a.d(ime))).build()
        a.root.dispatchApplyWindowInsets(inset());layout();assertEquals(0,a.root.paddingTop);assertEquals(0,a.root.paddingBottom);assertEquals(a.d(28),r.view.paddingTop);assertEquals(a.d(16),r.view.paddingBottom);assertEquals(android.graphics.Color.TRANSPARENT,a.window.statusBarColor);assertEquals(android.graphics.Color.TRANSPARENT,a.window.navigationBarColor);assertFalse(a.window.isNavigationBarContrastEnforced)
        r.showChrome(false);layout();screenshot("reader-cutout-immersive");a.root.dispatchApplyWindowInsets(inset(300));assertEquals(a.d(300),r.view.paddingBottom);a.root.dispatchApplyWindowInsets(inset());assertEquals(a.d(16),r.view.paddingBottom)
        r.showChrome(true);layout();val before=r.pageBase();screenshot("reader-cutout-chrome");r.showChrome(false);layout();assertEquals(before,r.pageBase());a.closeReader();drain();a.root.dispatchApplyWindowInsets(inset());assertEquals(a.d(28),a.appInsets.top);assertEquals(a.d(24),a.appInsets.bottom)
    }
    @Test @Config(sdk=[28]) fun samsungPreEdgeToEdgeReaderAlsoHidesSystemBars(){
        a.openBook(a.books.first());val r=readyReader();val flags=a.window.decorView.systemUiVisibility
        assertTrue(flags and View.SYSTEM_UI_FLAG_FULLSCREEN!=0);assertTrue(flags and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION!=0);assertTrue(flags and View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY!=0)
        assertEquals(WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,a.window.attributes.layoutInDisplayCutoutMode)
        r.pause();r.resume();assertTrue(a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN!=0);a.closeReader();drain();assertEquals(0,a.window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
    }
    @Test fun readerNotebookListsFiltersCopiesAndOpensQuoteInSameReader(){
        val b=a.books.first().copyJson().put("quotes",JSONArray().put(JSONObject().put("id","reader-q").put("ch",0).put("start",2000).put("text","Сохранённая цитата о маяке").put("at",1000))).put("notes",JSONArray().put(JSONObject().put("id","reader-n").put("ch",0).put("text","Мысль о маяке").put("quoteId","reader-q").put("at",2000))).put("bookmarks",JSONArray().put(JSONObject().put("id","reader-b").put("ch",0).put("nativeOffset",100).put("text","Первая глава").put("at",3000)))
        a.openBook(b);val r=readyReader();r.notebook();val panel=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val decor=panel.window!!.decorView
        fun all()=descendants(decor)
        assertTrue(all().any{it is TextView&&it.text.toString().startsWith("Цитаты 1")});assertTrue(all().any{it is TextView&&it.text.toString()=="Мысль о маяке"})
        all().filterIsInstance<TextView>().first{it.text.toString()=="Цитаты 1"}.performClick();assertEquals(1,all().count{it.contentDescription?.toString()?.startsWith("Цитата: ")==true});assertFalse(all().any{it.contentDescription?.toString()?.startsWith("Закладка: ")==true})
        val input=all().filterIsInstance<EditText>().single();input.setText("не существует");assertTrue(all().any{it is TextView&&it.text.toString()=="Ничего не найдено"});input.setText("")
        all().first{it.contentDescription=="Копировать запись"}.performClick();val clip=a.getSystemService(Context.CLIPBOARD_SERVICE)as android.content.ClipboardManager;assertTrue(clip.primaryClip!!.getItemAt(0).text.contains("Сохранённая цитата"))
        decor.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(732),View.MeasureSpec.AT_MOST));decor.layout(0,0,a.d(360),decor.measuredHeight);val shot=Bitmap.createBitmap(a.d(360),decor.measuredHeight,Bitmap.Config.ARGB_8888);decor.draw(Canvas(shot));val dir=File(System.getProperty("qaOutputDir"),"reader-0.6.2").apply{mkdirs()};File(dir,"notebook-quotes.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()
        all().filterIsInstance<TextView>().first{it.text.toString()=="К фрагменту"}.performClick();drain();assertSame(r,a.reader);assertTrue(r.pageBase() in 1..2000);assertFalse(panel.isShowing)
    }
    @Test fun readerNavigationRestoresThreeTabsAndSearchWithoutLeavingBook(){
        a.openBook(a.books.first());val r=readyReader();r.navigation();val panel=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val decor=panel.window!!.decorView
        for(label in listOf("Оглавление","Блокнот","Поиск"))assertTrue(descendants(decor).any{it is TextView&&it.text.toString()==label})
        descendants(decor).filterIsInstance<TextView>().first{it.text.toString()=="Поиск"}.performClick();val input=descendants(decor).filterIsInstance<EditText>().single();input.setText("маяк");drain();drain();assertTrue(descendants(decor).any{it is TextView&&it.text.toString().startsWith("Найдено: ")});assertSame(r,a.reader)
    }
    private fun readyReader():NativeReader {repeat(8){drain();layout();a.root.viewTreeObserver.dispatchOnPreDraw();if(a.reader?.openingReady()==true)return a.reader!!};error("Reader did not finish background layout")}
    @Test fun readerPreparesOffMainAndTurningReusesPagination(){
        a.openBook(a.books.first().copyJson().put("progress",JSONObject().put("ch",0).put("nativeOffset",0)));val reader=readyReader();assertFalse(reader.lastLayoutWorker.isBlank());assertNotEquals(Thread.currentThread().name,reader.lastLayoutWorker);val builds=reader.layoutBuilds;a.prefs.put("motion","normal");assertTrue(reader.body.text.length<reader.chapters.getJSONObject(0).optInt("len"));val start=reader.pageBase();reader.turn(1);assertTrue(reader.pageBase()>start);assertEquals(builds,reader.layoutBuilds);assertEquals(1f,reader.body.alpha,0f);reader.stopPageMotion();screenshot("reader-background-layout");reader.toggleChrome();layout();drain();assertEquals("Chrome must not repaginate the chapter",builds,reader.layoutBuilds);a.prefs.put("size",23);reader.applyTypography();drain();layout();drain();assertTrue("Changing typography must repaginate",reader.layoutBuilds>builds);assertFalse(reader.isPreparing);reader.dispose();assertFalse(reader.isPreparing)
    }
    @Test fun quotesSwitchWithoutReplacingActivityChromeOrNavigation(){
        a.books=a.books.map{it.copyJson().put("quotes",JSONArray().put(JSONObject().put("id","q").put("text","Текст цитаты").put("at",1)))};a.render(false,false);click("Цитаты");layout();a.prefs.put("motion","normal");val contentRoot=a.root.getChildAt(0);val navigation=descendants(a.root).filterIsInstance<PolkaNavigation>().single();repeat(3){a.openQuoteGroup(a.books.first().str("id"));assertSame(contentRoot,a.root.getChildAt(0));assertSame(navigation,descendants(a.root).filterIsInstance<PolkaNavigation>().single());assertEquals(0f,a.page.getChildAt(0).alpha,0f);a.openQuoteGroup(null)};layout();assertSame(navigation,descendants(a.root).filterIsInstance<PolkaNavigation>().single());assertNull(a.quoteBookId)
    }
    @Test fun redesignedDetailKeepsUnderlyingScreenAndSeparatesReadingQuotesAndMetadata(){
        layout();val original=a.root.getChildAt(0);a.detail(a.books.first{Library.status(it)=="reading"});layout();a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();sheet.sampleMotion(460);val views=descendants(sheet);val labels=views.filterIsInstance<TextView>().map{it.text.toString()};for(name in listOf("Сейчас читаете","Описание","Оценка","Сведения"))assertTrue("missing section: $name",name in labels);assertFalse("detail heading intentionally removed",labels.contains("О книге"));assertTrue(labels.any{it.startsWith("Ваши цитаты")});val cover=views.filterIsInstance<CoverView>().single();assertEquals(a.d(132),cover.width);assertEquals(a.d(198),cover.height);assertTrue(cover.softShadow);assertSame(original,a.root.getChildAt(0));screenshot("book-detail-v4");sheet.finish();assertSame(original,a.root.getChildAt(0))
    }
    @Test fun detailCoverFlightAndCloseDoNotReplaceWindow(){
        org.robolectric.shadows.ShadowChoreographer.setPaused(true);layout();val source=descendants(a.root).filterIsInstance<CoverView>().first{it.width>0&&it.height>0&&it.getGlobalVisibleRect(android.graphics.Rect())};val book=a.books.first{it.str("id")==source.bookId};val decor=a.window.decorView;a.prefs.put("motion","normal");a.detail(book,source);a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();val dir=File(System.getProperty("qaOutputDir"),"motion/detail-native").apply{mkdirs()};val bitmap=Bitmap.createBitmap(360,780,Bitmap.Config.ARGB_8888)
        for(i in 0..29){sheet.sampleMotion(i*16L);val canvas=Canvas(bitmap);canvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);a.root.draw(canvas);File(dir,"frame-%03d.png".format(i)).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        assertEquals(View.INVISIBLE,source.visibility);assertSame(decor,a.window.decorView);sheet.dismiss();sheet.freezeMotion();for(i in 0..29){sheet.sampleMotion(i*16L,true);val canvas=Canvas(bitmap);canvas.scale(1/a.resources.displayMetrics.density,1/a.resources.displayMetrics.density);a.root.draw(canvas);File(dir,"frame-%03d.png".format(i+30)).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}};sheet.finish();assertEquals(View.VISIBLE,source.visibility);assertSame(decor,a.window.decorView);assertNull(a.bookDetailSheet);bitmap.recycle()
    }

}
