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
class ReaderCurlTests {
 private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
 private lateinit var a:MainActivity
 private lateinit var r:NativeReader
 private var time=0L
 private var down=0L
 private fun advance(ms:Long){repeat(((ms+15)/16).toInt()){Shadows.shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)}}
 private fun drain(){repeat(12){Thread.sleep(30);advance(80)}}
 private fun layout(){repeat(2){a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));if(::r.isInitialized)r.view.viewTreeObserver.dispatchOnPreDraw();advance(16)}}
 @Before fun open(){val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("polka.sqlite");val db=Room.databaseBuilder(c,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build();val lib=Library(db);lib.initialize();lib.savePrefs(lib.prefs().put("motion","reduced").put("rt","paper").put("seenReader",true))
  val words=(0..100).joinToString(""){i->"<p>Вечер $i. Над морем зажёгся маяк. Тихие волны касались берега, а над далёкой водой постепенно появлялись звёзды. Мы читали дальше, сохраняя в памяти этот свет.</p>"}
  val xml="<FictionBook><description><title-info><book-title>Тихий берег</book-title><author><first-name>Полка</first-name></author></title-info></description><body><section><title><p>У моря</p></title>$words</section><section><title><p>Новая глава</p></title>$words</section></body></FictionBook>"
  lib.importBooks(listOf(BookParser.parse(xml.toByteArray(),"curl.fb2")));db.close();controller=Robolectric.buildActivity(MainActivity::class.java).setup();a=controller.get();drain();layout();a.openBook(a.books.first());for(attempt in 0..11){drain();if(a.reader!=null)r=a.reader!!;layout();if(::r.isInitialized&&r.openingReady())break};assertTrue(r.openingReady());r.showChrome(false);layout();a.prefs.put("motion","normal");org.robolectric.shadows.ShadowChoreographer.setPaused(true)
 }
 @After fun close(){if(::r.isInitialized)r.dispose();controller.pause().stop().destroy();org.robolectric.shadows.ShadowChoreographer.setPaused(false)}
 private fun touch(target:View,action:Int,x:Float,y:Float,dt:Long=100){if(action==MotionEvent.ACTION_DOWN){down=SystemClock.uptimeMillis();time=down}else time+=dt;val e=MotionEvent.obtain(down,time,action,x,y,0);target.dispatchTouchEvent(e);e.recycle()}
 private fun body(action:Int,x:Float,y:Float=.72f,dt:Long=100)=touch(r.body,action,r.scroll.width*x,r.scroll.height*y,dt)
 private fun shot(name:String):Bitmap{val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.19").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};return b}
 private fun complete(){layout();advance(800);layout();assertNull(r.outgoingPage)}
 @Test fun fingerOwnsFoldAndCanReverseWithoutChangingPage(){val base=r.pageBase();val text=r.body.text.toString();val builds=r.layoutBuilds
  body(MotionEvent.ACTION_DOWN,.92f);body(MotionEvent.ACTION_MOVE,.56f);val curl=r.outgoingPage!!;assertEquals(.36f,curl.progress,.015f);assertEquals(base,r.pageBase());assertFalse(r.pageMotionRunning);shot("paper-drag-36").recycle()
  advance(1200);assertEquals("holding finger must hold the page",.36f,curl.progress,.015f)
  body(MotionEvent.ACTION_MOVE,.76f);assertEquals(.16f,curl.progress,.015f);shot("paper-reverse-16").recycle();body(MotionEvent.ACTION_CANCEL,.76f);complete();assertEquals(base,r.pageBase());assertEquals(text,r.body.text.toString());assertEquals(builds,r.layoutBuilds);assertEquals(0,r.scroll.scrollY);shot("paper-cancelled").recycle()
 }
 @Test fun slowReleaseCommitsExactlyOnePageAndHandoffMatchesPreview(){val base=r.pageBase();body(MotionEvent.ACTION_DOWN,.95f,.85f);body(MotionEvent.ACTION_MOVE,.40f,.67f,1500);val curl=r.outgoingPage!!;val preview=curl.targetBitmap;shot("paper-drag-55-diagonal").recycle();body(MotionEvent.ACTION_UP,.40f,.67f,1500);assertTrue(r.pageBase()>base);layout();for(ms in listOf(0L,100L,240L)){r.samplePageMotion(ms);shot("paper-settle-$ms").recycle()};r.samplePageMotion(620);complete()
  val live=Bitmap.createBitmap(r.scroll.width,r.scroll.height,Bitmap.Config.ARGB_8888);Canvas(live).also{it.drawColor(r.backgroundColor);r.scroll.draw(it)};var differing=0;val pixels=IntArray(live.width*live.height);val expected=IntArray(pixels.size);live.getPixels(pixels,0,live.width,0,0,live.width,live.height);preview.getPixels(expected,0,live.width,r.scroll.left,r.scroll.top,live.width,live.height);for(i in pixels.indices)if(pixels[i]!=expected[i])differing++
  assertTrue("snapshot must exactly match live page (diff=$differing)",differing<pixels.size*.003);live.recycle();shot("paper-committed").recycle();val next=r.pageBase();advance(1000);assertEquals(next,r.pageBase())
 }
 @Test fun backwardsCurlReturnsAndFirstPageCannotUnderflow(){body(MotionEvent.ACTION_DOWN,.20f);body(MotionEvent.ACTION_MOVE,.65f,dt=1500);assertNull(r.outgoingPage);body(MotionEvent.ACTION_UP,.65f,dt=1500);assertEquals(0,r.pageBase())
  r.turn(1);complete();val next=r.pageBase();body(MotionEvent.ACTION_DOWN,.12f,.45f);body(MotionEvent.ACTION_MOVE,.68f,.51f,1500);assertEquals(-1,r.outgoingPage!!.direction);assertEquals(next,r.pageBase());shot("paper-backward").recycle();body(MotionEvent.ACTION_UP,.68f,.51f,1500);complete();assertEquals(0,r.pageBase())
 }
 @Test fun opposingReleaseVelocityCancelsEvenPastThreshold(){val base=r.pageBase();body(MotionEvent.ACTION_DOWN,.95f);body(MotionEvent.ACTION_MOVE,.30f,dt=1000);body(MotionEvent.ACTION_MOVE,.43f,dt=30);body(MotionEvent.ACTION_UP,.58f,dt=30);complete();assertEquals(base,r.pageBase())}
 @Test fun realLongPressStartsChapterSelectionRatherThanTurning(){val base=r.pageBase();body(MotionEvent.ACTION_DOWN,.4f,.24f);advance(700);assertTrue(r.quoteSelection.active);assertTrue(r.quoteSelection.range.end>r.quoteSelection.range.start);body(MotionEvent.ACTION_UP,.4f,.24f);assertEquals(base,r.pageBase());assertNull(r.outgoingPage);shot("quote-long-press").recycle()}
 @Test fun quoteHandleCrossesPageAndSavesExactChapterRange(){val oldBase=r.pageBase();val start=r.pageLimit()-30;val q=r.quoteSelection;q.begin(start);layout();shot("quote-before-edge").recycle();val p=q.handlePoint(false);touch(q,MotionEvent.ACTION_DOWN,p.x,p.y);touch(q,MotionEvent.ACTION_MOVE,q.width-r.body.paddingRight+a.d(15).toFloat(),p.y);advance(360);layout();advance(750);layout();assertTrue(r.pageBase()>oldBase);assertTrue(q.active);assertTrue(q.range.start<r.pageBase());assertTrue(q.range.end>r.pageBase())
  touch(q,MotionEvent.ACTION_MOVE,a.d(170).toFloat(),a.d(180).toFloat());touch(q,MotionEvent.ACTION_UP,a.d(170).toFloat(),a.d(180).toFloat());layout();shot("quote-across-pages").recycle();val from=q.range.start;val end=q.range.end;val expected=r.selectionFullText().subSequence(from,end).toString();assertTrue(expected.length>30)
  descendants(q).filterIsInstance<TextView>().first{it.text.toString()=="Цитата"}.performClick();drain();layout();val saved=r.book.getJSONArray("quotes").getJSONObject(0);assertEquals(from,saved.getInt("start"));assertEquals(end,saved.getInt("end"));assertEquals(expected,saved.getString("rawText"));assertFalse(q.active)
 }
 @Test fun startHandleCanExtendOntoPreviousPageWithoutLosingEnd(){r.turn(1);complete();val base=r.pageBase();val q=r.quoteSelection;q.begin(base+18);layout();shot("quote-backward-before").recycle();val end=q.range.end;val p=q.handlePoint(true);touch(q,MotionEvent.ACTION_DOWN,p.x,p.y);assertTrue("start handle must capture drag at $p; active=${q.active}",q.dragging);touch(q,MotionEvent.ACTION_MOVE,a.d(4).toFloat(),p.y);advance(360);layout();advance(750);layout();assertTrue("previous page expected: base=$base now=${r.pageBase()} active=${q.active} dragging=${q.dragging}",r.pageBase()<base);assertEquals(end,q.range.end);assertTrue("start must precede old page",q.range.start<base);touch(q,MotionEvent.ACTION_UP,a.d(40).toFloat(),a.d(350).toFloat());shot("quote-backward").recycle()}
 @Test fun selectionEdgeDwellCanBeCancelledAndCannotCrossChapter(){val q=r.quoteSelection;q.begin(r.pageLimit()-30);layout();val p=q.handlePoint(false);touch(q,MotionEvent.ACTION_DOWN,p.x,p.y);touch(q,MotionEvent.ACTION_MOVE,q.width-a.d(4).toFloat(),p.y);advance(150);touch(q,MotionEvent.ACTION_CANCEL,q.width-a.d(4).toFloat(),p.y);advance(800);assertEquals(0,r.pageBase());assertNull(r.outgoingPage)
  var result=true;r.jumpOffset(r.selectionFullText().length);layout();val chapter=r.chapter;r.turnWithSelection(1){result=it};assertFalse(result);assertEquals(chapter,r.chapter);assertNull(r.outgoingPage)
 }
 @Test fun chapterBoundaryCurlUsesWarmPreviewAndReturnsToLastPage(){r.jumpOffset(r.selectionFullText().length);drain();layout();val last=r.pageBase();val ch=r.chapter;body(MotionEvent.ACTION_DOWN,.95f);body(MotionEvent.ACTION_MOVE,.4f,dt=1500);assertNotNull(r.outgoingPage);assertEquals(ch,r.chapter);body(MotionEvent.ACTION_UP,.4f,dt=1500);complete();assertEquals(ch+1,r.chapter);assertEquals(0,r.pageBase());drain();r.turn(-1);complete();assertEquals(ch,r.chapter);assertEquals(last,r.pageBase())}
 @Test fun reducedMotionAndNightPaperHaveNoSlideOrBlankFrame(){a.prefs.put("rt","night");r.applyTypography();drain();layout();body(MotionEvent.ACTION_DOWN,.9f,.2f);body(MotionEvent.ACTION_MOVE,.42f,.3f,1500);shot("night-curl").recycle();body(MotionEvent.ACTION_CANCEL,.42f,.3f);complete();a.prefs.put("motion","reduced");r.turn(1);layout();assertNull(r.outgoingPage);assertFalse(r.pageMotionRunning);assertTrue(r.pageBase()>0);assertEquals(0f,r.scroll.translationX,0f)}
 @Test fun foldedPaperUsesPhysicalScreenBoundsWithoutMovingTextMargins(){
  r.safeArea(a.d(8),a.d(32),a.d(8),a.d(24));drain();layout();val padding=r.body.paddingTop
  body(MotionEvent.ACTION_DOWN,.94f,.25f);body(MotionEvent.ACTION_MOVE,.42f,.35f,1500);val curl=r.outgoingPage!!;layout()
  assertEquals(r.view.width,curl.sourceBitmap.width);assertEquals(r.view.height,curl.sourceBitmap.height)
  assertEquals(0,curl.left);assertEquals(0,curl.top);assertEquals(r.view.width,curl.right);assertEquals(r.view.height,curl.bottom);assertEquals(padding,r.body.paddingTop)
  assertEquals(r.backgroundColor,curl.sourceBitmap.getPixel(a.d(100),0));shot("paper-fullscreen-safe-insets").recycle()
  body(MotionEvent.ACTION_CANCEL,.42f,.35f);complete()
 }
 private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
}

class ChapterSelectionTests {
 @Test fun rangesContainEveryCharacterAcrossPageBoundaries(){val text="Первая страница. Вторая страница. Третья страница.";val q=ChapterSelection();assertTrue(q.begin(text,8));assertFalse(q.move(text,25,false));assertEquals(text.substring(q.start,q.end),q.text(text));assertEquals("страница. Вторая страница",q.text(text))}
 @Test fun handlesCanCrossAndUnicodeWordsDoNotSplit(){val text="Море 🌊 и маяк. Берег.";val q=ChapterSelection();q.begin(text,text.indexOf("маяк"));assertEquals("маяк",q.text(text));assertFalse(q.move(text,text.indexOf("Берег"),true));assertTrue(q.start<q.end);assertTrue(q.move(text,0,false));assertTrue(q.start<q.end);assertTrue(q.text(text).startsWith("Море"))}
 @Test fun blankAndEdgeOffsetsAreSafe(){val q=ChapterSelection();assertFalse(q.begin("",10));assertFalse(q.begin("  ",-1));assertTrue(q.begin("слово",999));assertEquals("слово",q.text("слово"))}
}
