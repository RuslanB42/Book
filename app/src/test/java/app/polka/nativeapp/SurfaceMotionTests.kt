package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.os.*
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
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SurfaceMotionTests {
 private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
 private lateinit var a:MainActivity
 private fun advance(ms:Long){repeat(((ms+15)/16).toInt()){Shadows.shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)}}
 private fun drain(){repeat(12){Thread.sleep(35);advance(80)}}
 private fun layout(){repeat(3){a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster();advance(16)}}
 private fun save(name:String):Bitmap{val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.18/motion").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};return b}
 @Before fun setup(){val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("polka.sqlite");val db=Room.databaseBuilder(c,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build();val lib=Library(db);lib.initialize();lib.savePrefs(lib.prefs().put("motion","reduced").put("seenReader",true));val text=(0..70).joinToString(""){"<p>Вечер $it. Над морем зажёгся маяк. Тихие волны касались берега. Мы читали дальше, сохраняя в памяти этот свет.</p>"};lib.importBooks(listOf(BookParser.parse("<FictionBook><description><title-info><book-title>Смотритель маяка</book-title><author><first-name>Полка</first-name></author></title-info></description><body><section><title><p>У моря</p></title>$text</section></body></FictionBook>".toByteArray(),"glass.fb2")));db.close();controller=Robolectric.buildActivity(MainActivity::class.java).setup();a=controller.get();drain();layout();org.robolectric.shadows.ShadowChoreographer.setPaused(true)}
 @After fun close(){a.root.childrenOpening()?.finish();a.reader?.dispose();controller.pause().stop().destroy();org.robolectric.shadows.ShadowChoreographer.setPaused(false)}
 @Test fun retargetKeepsPositionAndVelocityInsteadOfRestarting(){val spring=LiquidSpring{_,_->};spring.snap(0f);spring.to(100f);spring.sample(90);val x=spring.value;val speed=spring.velocity;assertTrue(x>0&&x<100);spring.to(-20f);assertEquals(x,spring.value,.001f);assertEquals(speed,spring.velocity,.01f);spring.sample(620);assertEquals(-20f,spring.value,0f);assertEquals(0f,spring.velocity,0f);spring.stop()}
 @Test fun navigationKeepsInstanceAndRetargetsRapidChanges(){a.prefs.put("motion","normal");val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();nav.bind("foryou"){};nav.sampleIndicator(90);val mid=nav.indicatorOffset;nav.bind("shelves"){};assertEquals(mid,nav.indicatorOffset,.01f);nav.sampleIndicator(620);assertEquals(a.d(56).toFloat(),nav.indicatorOffset,.01f);assertEquals(1,descendants(nav).count{it.isSelected});a.root.removeView(nav);assertTrue(nav.indicatorOffset.isFinite())}
 @Test fun lightAndDarkSolidScreensKeepTextAndTouchGeometry(){for(theme in listOf("light","dark")){a.prefs.put("ui",theme).put("motion","reduced");a.render(false,false);layout();drain();save("$theme-library").recycle();val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();assertEquals(a.d(230),nav.width);assertEquals(a.d(58),nav.height);assertTrue(nav.background is OpaqueSurface);a.search();advance(400);layout();save("$theme-search").recycle();assertTrue(a.librarySearch!!.input.hasFocus());a.librarySearch!!.close()}}
 @Test @Config(sdk=[28]) fun olderAndroidUsesSolidSurfaces(){a.prefs.put("motion","reduced");a.render(false,false);layout();drain();save("api28-solid").recycle();assertTrue(descendants(a.root).filterIsInstance<PolkaNavigation>().single().background is OpaqueSurface)}
 @Test fun openingKeepsLibraryDuringPreparationAndNoSolidVeilAtStart(){a.prefs.put("ui","light").put("motion","reduced");a.render(false,false);layout();a.prefs.put("motion","normal");val before=save("opening-before");a.openBook(a.books.first());for(i in 0..12){drain();layout();if(a.reader!=null)break};val r=a.reader!!;assertTrue("preparing reader must be masked by its source scene",a.root.childrenOpening()!=null||descendants(a.root).any{it is BookOpeningHold});var stage=a.root.childrenOpening();for(i in 0..12){layout();drain();stage=a.root.childrenOpening();if(stage!=null)break};assertNotNull(stage);stage!!.frame(0f);val first=save("opening-000");val sampleY=a.d(120);assertTrue("header area cannot turn into a solid black veil",Color.red(first.getPixel(a.d(20),sampleY))>120);for(t in listOf(.12f,.28f,.48f,.72f,.90f,1f)){stage.frame(t);save("opening-${(t*1000).toInt()}").recycle()};stage.finish();assertSame(r.view,a.root.getChildAt(0));assertEquals(1f,r.view.alpha,0f);assertNull(a.root.childrenOpening());first.recycle();before.recycle()}
 @Test fun openingFromDetailKeepsSourceBeforeAsyncLoad(){a.prefs.put("ui","light").put("motion","reduced");a.render(false,false);layout();a.detail(a.books.first());layout();val detail=a.bookDetailSheet!!;detail.freezeMotion();detail.sampleMotion(460);layout();val source=descendants(detail).filterIsInstance<CoverView>().first();a.prefs.put("motion","normal");a.openBook(a.books.first(),source);assertTrue("hold must exist synchronously, before detail disappears",descendants(a.root).any{it is BookOpeningHold});detail.finish();layout();save("opening-detail-hold").recycle();assertTrue(descendants(a.root).any{it is BookOpeningHold}||a.root.childrenOpening()!=null);repeat(8){drain();layout()};a.root.childrenOpening()?.finish();assertNotNull(a.reader)}
 @Test fun reducedMotionOpensWithoutSceneOrTransitionOverlay(){a.prefs.put("motion","reduced");a.openBook(a.books.first());repeat(10){drain();layout()};assertNotNull(a.reader);assertNull(a.root.childrenOpening());assertFalse(descendants(a.root).any{it is BookOpeningHold});assertEquals(1f,a.reader!!.view.alpha,0f)}
 private fun readyReader():NativeReader {repeat(20){drain();layout();if(a.reader?.openingReady()==true)return a.reader!!};error("reader not ready")}
 private fun difference(one:Bitmap,two:Bitmap):Float {var different=0;var count=0;for(y in 0 until one.height step 3)for(x in 0 until one.width step 3){count++;if(one.getPixel(x,y)!=two.getPixel(x,y))different++};return different.toFloat()/count}
 @Test fun closeReversesLivePageWithoutReloadAndSavesPosition(){
  a.prefs.put("motion","reduced");a.openBook(a.books.first());val r=readyReader();r.turn(1);r.stopPageMotion();layout();val offset=r.pageBase();assertTrue(offset>0)
  a.updateAppInsets(androidx.core.graphics.Insets.of(0,a.d(24),0,a.d(24)))
  val before=save("return-before");a.prefs.put("motion","normal");a.closeReader();assertTrue(a.bookClosing);assertNull(a.reader)
  val libraryInsets=a.appInsets;ReaderWindow.apply(a,WindowInsets.Builder().setInsets(WindowInsets.Type.systemBars(),android.graphics.Insets.NONE).build());assertEquals("insets must not jump during reverse flight",libraryInsets,a.appInsets)
  repeat(6){layout()};val stage=a.root.childrenOpening()!!;assertTrue(stage.returning);stage.freezeMotion();stage.frame(1f)
  val first=save("return-1000");assertTrue("first reverse frame must be the live reader: ${difference(before,first)}",difference(before,first)<.012f)
  val library=a.root.getChildAt(0);a.closeReader();a.onBackPressed();assertSame(stage,a.root.childrenOpening())
  a.render(true,false);assertSame(stage,a.root.childrenOpening())
  for(t in listOf(.9f,.72f,.63f,.48f,.30f,.12f,0f)){stage.frame(t);save("return-${(t*1000).toInt()}").recycle()}
  val last=save("return-last");stage.finish();assertFalse(a.bookClosing);assertNull(a.root.childrenOpening());assertSame(library,a.root.getChildAt(0))
  val final=save("return-library");assertTrue("return cover must meet its library destination: ${difference(last,final)}",difference(last,final)<.025f)
  drain();assertSame("no post-transition reload",library,a.root.getChildAt(0));assertEquals(offset,a.books.first().getJSONObject("progress").getInt("nativeOffset"))
  a.prefs.put("motion","reduced");a.openBook(a.books.first());val reopened=readyReader();assertEquals(offset,reopened.pageBase())
  before.recycle();first.recycle();last.recycle();final.recycle()
 }
 @Test fun closeWithNoVisibleDestinationFadesOnlyAtTheEndAndReleasesStage(){
  a.openBook(a.books.first());readyReader();a.query="нет такой книги";a.prefs.put("motion","normal");a.closeReader();repeat(6){layout()}
  val stage=a.root.childrenOpening()!!;stage.freezeMotion();stage.frame(.4f);assertEquals(1f,stage.alpha,0f);stage.frame(.08f);assertEquals(.5f,stage.alpha,.001f);stage.frame(0f);assertEquals(0f,stage.alpha,0f)
  stage.finish();stage.finish();assertFalse(a.bookClosing);assertNull(a.root.childrenOpening());assertNull(a.reader)
 }
 @Test fun reducedMotionClosesImmediatelyAndKeepsSavedReadingPosition(){
  a.openBook(a.books.first());val r=readyReader();r.turn(1);r.stopPageMotion();layout();val offset=r.pageBase();a.closeReader();assertFalse(a.bookClosing);assertNull(a.reader);assertNull(a.root.childrenOpening());assertTrue(descendants(a.root).any{it is PolkaNavigation});assertEquals(offset,a.books.first().getJSONObject("progress").getInt("nativeOffset"))
 }
 @Test fun lightReturnKeepsChromeAndCancellationRestoresTheSourceCover(){
  a.prefs.put("ui","light").put("motion","reduced");a.render(false,false);layout();a.openBook(a.books.first());val r=readyReader();r.showChrome(true);layout()
  val before=save("light-return-before");a.prefs.put("motion","normal");a.closeReader();repeat(6){layout()};val stage=a.root.childrenOpening()!!;stage.freezeMotion();stage.frame(1f)
  val first=save("light-return-1000");assertTrue(difference(before,first)<.012f)
  for(t in listOf(.72f,.48f,.30f,.12f)){stage.frame(t);save("light-return-${(t*1000).toInt()}").recycle()}
  val hidden=descendants(a.root).filterIsInstance<CoverView>().firstOrNull{it.visibility==View.INVISIBLE};assertNotNull(hidden)
  a.root.removeView(stage);assertFalse(a.bookClosing);assertEquals(View.VISIBLE,hidden!!.visibility);assertNull(a.reader);before.recycle();first.recycle()
 }
 @Test fun tinyCoverSourceUsesFullProjectedTextureResolution(){
  a.prefs.put("motion","normal");val cover=CoverView(a,a.books.first());a.root.addView(cover,FrameLayout.LayoutParams(a.d(64),a.d(96)));layout()
  val snapshot=a.openingSnapshot(a.books.first().str("id"),cover,captureScene=false)!!
  assertTrue(snapshot.cover.width>=minOf(a.root.width*.82f,a.root.height*.48f,1024f).toInt());assertEquals(1,snapshot.scene.width)
  assertEquals(View.VISIBLE,cover.visibility);snapshot.release();assertTrue(snapshot.cover.isRecycled);assertTrue(snapshot.scene.isRecycled)
 }
 private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
}
