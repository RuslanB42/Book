package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import kotlin.math.*
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
class SurfaceInteractionTests {
 private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
 private lateinit var a:MainActivity
 private fun advance(ms:Long){repeat(((ms+15)/16).toInt()){Shadows.shadowOf(Looper.getMainLooper()).idleFor(16,TimeUnit.MILLISECONDS)}}
 private fun drain(){repeat(12){Thread.sleep(35);advance(80)}}
 private fun layout(){repeat(3){a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster();advance(16)}}
 private fun save(name:String):Bitmap{val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.26").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};return b}
 @Before fun setup(){val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("polka.sqlite");val db=Room.databaseBuilder(c,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build();val lib=Library(db);lib.initialize();lib.savePrefs(lib.prefs().put("motion","reduced").put("seenReader",true));val text=(0..70).joinToString(""){"<p>Вечер $it. Над морем зажёгся маяк. Тихие волны касались берега. Мы читали дальше, сохраняя в памяти этот свет.</p>"};lib.importBooks(listOf(BookParser.parse("<FictionBook><description><title-info><book-title>Смотритель маяка</book-title><author><first-name>Полка</first-name></author></title-info></description><body><section><title><p>У моря</p></title>$text</section></body></FictionBook>".toByteArray(),"glass.fb2")));db.close();controller=Robolectric.buildActivity(MainActivity::class.java).setup();a=controller.get();drain();layout();org.robolectric.shadows.ShadowChoreographer.setPaused(true)}
 @After fun close(){a.root.childrenOpening()?.finish();a.reader?.dispose();controller.pause().stop().destroy();org.robolectric.shadows.ShadowChoreographer.setPaused(false)}

 @Test fun carouselRestoresSelectionBeforeFirstPaintAndReusesViewsOnTabReturn(){
  val original=a.books.first();val queue=(0..2).map{i->original.copyJson().put("id","reading-$i").put("openedAt",10L-i).put("progress",JSONObject().put("pct",.2))}
  a.books=queue;a.continueBookId="reading-2";a.render(false,false)
  a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780))
  val carousel=descendants(a.page).filterIsInstance<ContinueReading>().single();val scroll=descendants(carousel).filterIsInstance<HorizontalScrollView>().single();val strip=scroll.getChildAt(0) as LinearLayout
  assertEquals("selection restored before drawing or draining posted callbacks",strip.getChildAt(2).left-strip.paddingLeft,scroll.scrollX);assertEquals("reading-2",a.continueBookId)
  val covers=descendants(carousel).filterIsInstance<CoverView>();val tab=MainActivity::class.java.getDeclaredField("tab").apply{isAccessible=true};tab.set(a,"quotes");a.render(false,false);layout();tab.set(a,"home");a.render(false,false);layout()
  assertSame(carousel,descendants(a.page).filterIsInstance<ContinueReading>().single());assertEquals(covers,descendants(carousel).filterIsInstance<CoverView>());assertEquals(strip.getChildAt(2).left-strip.paddingLeft,scroll.scrollX)
 }
 @Test fun cachedCarouselCoverKeepsLoadedPixelsAfterGlobalCacheEviction(){
  val b=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","cover-retention"))
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.MAGENTA)};a.covers.put("cover-retention",art)
  val cover=CoverView(a,b);cover.layout(0,0,96,144)
  fun capture()=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).also{cover.draw(Canvas(it))}
  val before=capture();a.covers.evictAll();val after=capture();assertTrue("loaded cover must not revert to generated fallback",before.sameAs(after));before.recycle();after.recycle()
 }
 @Test fun rootSectionsDoNotRenderOversizedPageTitles(){
  val tab=MainActivity::class.java.getDeclaredField("tab").apply{isAccessible=true}
  for(name in listOf("home","shelves","quotes","foryou")){tab.set(a,name);a.render(false,false);layout();assertFalse(descendants(a.root).filterIsInstance<TextView>().any{it.text.toString() in listOf("Мои книги","Полки","Цитаты","Для вас")&&it.textSize/a.resources.displayMetrics.scaledDensity>30})}
 }
 @Test fun detailQuotesUseTheSameNativeComponentIncludingActionsAndNotes(){
  val q=JSONObject().put("id","q").put("text","Тихие волны касались берега.").put("at",123456789L)
  val b=a.books.first().copyJson().put("quotes",JSONArray().put(q)).put("notes",JSONArray().put(JSONObject().put("id","n").put("quoteId","q").put("text","Моя мысль о море")))
  val standalone=a.quoteCardNative(b,q);a.detail(b);layout();descendants(a.bookDetailSheet!!).first{it.contentDescription=="Показать цитаты"}.performClick();layout()
  val embedded=descendants(a.bookDetailSheet!!).first{it.contentDescription?.startsWith("Открыть цитату:")==true}
  fun labels(v:View)=descendants(v).filterIsInstance<TextView>().map{it.text.toString()}
  assertEquals(labels(standalone),labels(embedded));assertEquals(descendants(standalone).mapNotNull{it.contentDescription?.toString()},descendants(embedded).drop(1).mapNotNull{it.contentDescription?.toString()})
  descendants(embedded).first{it.contentDescription=="Копировать цитату"}.performClick();assertTrue((a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip!!.getItemAt(0).text.contains(q.str("text")))
  a.bookDetailSheet!!.finish()
 }
 @Test fun glowIsPreparedBeforePresentationAndRepeatedOpenUsesSameRaster(){
  a.prefs.put("motion","normal");a.detail(a.books.first());layout();val detail=a.bookDetailSheet!!;detail.freezeMotion();val glow=descendants(detail).filterIsInstance<BookGlowView>().single();assertTrue(glow.ready);detail.sampleMotion(460);val first=save("glow-first-complete");drain();val later=save("glow-after-wait");val bounds=Rect();glow.getGlobalVisibleRect(bounds);val rootAt=IntArray(2);a.root.getLocationOnScreen(rootAt);bounds.offset(-rootAt[0],-rootAt[1]);bounds.intersect(0,0,first.width,first.height);val firstLight=Bitmap.createBitmap(first,bounds.left,bounds.top,bounds.width(),bounds.height());val laterLight=Bitmap.createBitmap(later,bounds.left,bounds.top,bounds.width(),bounds.height());assertTrue("no independent delayed glow fade (exclude async chapter/stats below halo)",firstLight.sameAs(laterLight));firstLight.recycle();laterLight.recycle();first.recycle();later.recycle();detail.finish()
  a.detail(a.books.first());layout();val next=descendants(a.bookDetailSheet!!).filterIsInstance<BookGlowView>().single();assertTrue(next.ready);assertEquals("cached raster, no reblur",0,next.rasterBuilds);a.bookDetailSheet!!.finish()
 }
 @Test fun collapsedDetailQuotesAreTheSharedStackAndExpandOnTap(){
  val b=a.books.first().copyJson();val qs=(0..4).map{JSONObject().put("id","stack-$it").put("text","Вечер $it. Тихие волны касались берега.").put("at",it)};b.put("quotes",JSONArray(qs));a.detail(b);layout();val sheet=a.bookDetailSheet!!
  val stack=descendants(sheet).filterIsInstance<QuoteStackLayer>().single();val reference=a.quoteStack(b,qs){}
  fun labels(v:View)=descendants(v).filterIsInstance<TextView>().map{it.text.toString()}
  assertEquals(labels(reference),labels(stack));assertEquals(0,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});val scroller=descendants(sheet).filterIsInstance<ScrollView>().single();val region=Rect();stack.getDrawingRect(region);(scroller.getChildAt(0) as ViewGroup).offsetDescendantRectToMyCoords(stack,region);scroller.scrollTo(0,(region.top-a.d(90)).coerceAtLeast(0));layout();save("quotes-collapsed-stack").recycle()
  stack.performClick();layout();assertEquals(0,descendants(sheet).filterIsInstance<QuoteStackLayer>().size);assertEquals(3,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
  descendants(sheet).first{it.contentDescription=="Свернуть цитаты"}.performClick();assertEquals(1,descendants(sheet).filterIsInstance<QuoteStackLayer>().size);sheet.finish()
 }
 @Test fun preparedHaloAppearsContinuouslyWithTheFinalCoverLanding(){
  a.prefs.put("motion","normal");a.detail(a.books.first());layout();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();val glow=descendants(sheet).filterIsInstance<BookGlowView>().single();assertTrue(glow.ready)
  for(ms in listOf(0L,48L,120L,230L,360L,460L)){sheet.sampleMotion(ms);assertEquals("halo follows cover position, not an asynchronous reveal at $ms",sheet.landingLight(sheet.motionProgress),glow.alpha,.0001f);save("halo-entry-$ms").recycle()};sheet.finish()
 }
 @Test fun carouselViewportIsFullBleedAndHasNoFadingEdgeMasks(){
  val b=a.books.first();a.books=(0..2).map{b.copyJson().put("id","edge-$it").put("openedAt",10-it).put("progress",JSONObject().put("pct",.3))};a.render(false,false);layout()
  val carousel=descendants(a.page).filterIsInstance<ContinueReading>().single();val scroll=descendants(carousel).filterIsInstance<HorizontalScrollView>().single();assertFalse(scroll.isHorizontalFadingEdgeEnabled);assertFalse(scroll.isVerticalFadingEdgeEnabled);val at=IntArray(2);scroll.getLocationOnScreen(at);val origin=IntArray(2);a.root.getLocationOnScreen(origin);assertEquals(origin[0],at[0]);assertEquals(a.root.width,scroll.width)
  for(i in 0..2){val strip=scroll.getChildAt(0) as LinearLayout;scroll.scrollTo(strip.getChildAt(i).left-strip.paddingLeft,0);save("carousel-edge-$i").recycle()}
 }
 @Test fun actualTabReturnDoesNotCreateAnAlphaLayerAtCarouselSideEdges(){
  val b=a.books.first();a.books=(0..2).map{b.copyJson().put("id","tab-edge-$it").put("openedAt",10-it).put("progress",JSONObject().put("pct",.3))};a.render(false,false);layout();a.prefs.put("motion","normal")
  fun select(label:String){val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();descendants(nav).first{it.contentDescription==label}.performClick()}
  select("Цитаты");layout();a.screenMotion.stop();select("Мои книги");assertEquals("incoming overflow must not be clipped into a translucent layer",1f,a.page.alpha,0f)
  val ghost=ScreenMotion::class.java.getDeclaredField("ghost").apply{isAccessible=true}.get(a.screenMotion) as View;assertEquals("outgoing capture includes overflow beyond content margins",a.root.width,ghost.layoutParams.width)
  layout();save("tab-return-edge-first").recycle();advance(100);save("tab-return-edge-100").recycle();advance(400);layout();save("tab-return-edge-final").recycle();assertEquals(1f,a.page.alpha,0f)
 }
 @Test fun tabsPushAsAdjacentPagesAndRapidRetargetKeepsPresentation(){
  a.prefs.put("motion","normal")
  fun select(label:String){val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();descendants(nav).first{it.contentDescription==label}.performClick()}
  fun ghost()=ScreenMotion::class.java.getDeclaredField("ghost").apply{isAccessible=true}.get(a.screenMotion) as? View
  select("Полки");layout();a.screenMotion.freezeTab();a.screenMotion.sampleTab(0);val outgoing=ghost()!!;val width=a.root.width.toFloat();assertEquals(width,a.page.translationX,.1f);assertEquals(0f,outgoing.translationX,.1f)
  for(ms in listOf(0L,60L,140L,240L)){a.screenMotion.sampleTab(ms);assertEquals("pages stay exactly adjacent",width,a.page.translationX-outgoing.translationX,.1f);assertEquals(1f,a.page.alpha,0f);assertEquals(1f,outgoing.alpha,0f);assertEquals(0f,a.page.translationY,0f);save("tab-push-$ms").recycle()}
  a.screenMotion.sampleTab(140);val before=save("tab-interrupt-before");select("Цитаты");a.screenMotion.freezeTab();layout();a.screenMotion.sampleTab(0);val after=save("tab-interrupt-after");val top=a.d(100);var diff=0L;var n=0;for(y in top until a.root.height-a.d(90) step 4)for(x in 0 until a.root.width step 4){val b=before.getPixel(x,y);val c=after.getPixel(x,y);diff+=abs(Color.red(b)-Color.red(c))+abs(Color.green(b)-Color.green(c))+abs(Color.blue(b)-Color.blue(c));n++};assertTrue("retarget preserves composited content, not previous resting pose: ${diff.toFloat()/n}",diff.toFloat()/n<3f);before.recycle();after.recycle()
  a.screenMotion.sampleTab(300);assertNull(ghost());assertEquals(0f,a.page.translationX,0f);select("Мои книги");a.screenMotion.freezeTab();assertTrue("return tab enters from left",a.page.translationX<0);a.screenMotion.sampleTab(300);assertEquals(0f,a.page.translationX,0f);assertNull(ghost())
 }
 @Test fun reducedMotionTabsHaveNoTransientSnapshotOrTranslation(){
  a.prefs.put("motion","reduced");val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();descendants(nav).first{it.contentDescription=="Цитаты"}.performClick();layout();assertEquals(0f,a.page.translationX,0f);assertEquals(1f,a.page.alpha,0f);assertNull(ScreenMotion::class.java.getDeclaredField("ghost").apply{isAccessible=true}.get(a.screenMotion))
 }
 @Test fun readerPanelBackgroundsReachScreenEdgesButButtonsKeepSafeInsets(){
  a.openBook(a.books.first());repeat(8){drain();layout()};val r=a.reader!!;r.showChrome(true);r.safeArea(a.d(5),a.d(28),a.d(7),a.d(24));layout()
  val top=NativeReader::class.java.getDeclaredField("topBar").apply{isAccessible=true}.get(r) as LinearLayout;val bottom=NativeReader::class.java.getDeclaredField("bottom").apply{isAccessible=true}.get(r) as LinearLayout
  assertFalse("edge backgrounds must not be clipped by safe-area padding",r.view.clipToPadding);assertEquals(0,top.top);assertEquals(r.view.height,bottom.bottom);assertEquals(0,top.left);assertEquals(r.view.width,top.right);assertTrue(top.paddingTop>=a.d(28));assertTrue(bottom.paddingBottom>=a.d(24));assertTrue((top.background as OpaqueSurface).flushTop);assertTrue((bottom.background as OpaqueSurface).flushBottom);save("reader-edge-panels").recycle()
 }
 private fun scrollTo(v:View){val sheet=a.bookDetailSheet!!;val sc=descendants(sheet).filterIsInstance<ScrollView>().single();val box=Rect();v.getDrawingRect(box);(sc.getChildAt(0) as ViewGroup).offsetDescendantRectToMyCoords(v,box);sc.scrollTo(0,(box.top-a.d(100)).coerceAtLeast(0));layout()}
 @Test fun descriptionMorphsHeightAndCanReverseWithoutJump(){
  val book=a.books.first().copyJson().put("annotation",(1..24).joinToString(" "){"Тихие волны касались берега, сохраняя вечерний свет."});a.detail(book);layout();val sheet=a.bookDetailSheet!!;val reveal=descendants(sheet).filterIsInstance<DetailReveal>().first();scrollTo(reveal);val short=reveal.height;a.prefs.put("motion","normal")
  descendants(sheet).first{it.contentDescription=="Развернуть описание"}.performClick();reveal.freeze();reveal.sample(0f);layout();assertEquals(short,reveal.height);reveal.sample(.5f);layout();val middle=reveal.height;assertTrue(middle>short);save("description-opening-middle").recycle()
  descendants(sheet).first{it.contentDescription=="Свернуть описание"}.performClick();reveal.freeze();reveal.sample(0f);layout();assertEquals("reverse starts from displayed height",middle,reveal.height);reveal.sample(.5f);layout();assertTrue(reveal.height in (short+1) until middle);save("description-reverse-middle").recycle();reveal.sample(1f);layout();assertEquals(short,reveal.height);sheet.finish()
 }
 @Test fun quotesStackMorphsIntoCardsAndBackWithIntermediateHeights(){
  val book=a.books.first().copyJson().put("quotes",JSONArray((0..4).map{JSONObject().put("id","smooth-$it").put("text","Цитата $it. Мы читали дальше, сохраняя в памяти этот свет.")}));a.detail(book);layout();val sheet=a.bookDetailSheet!!;val reveal=descendants(sheet).filterIsInstance<DetailReveal>().last();scrollTo(reveal);val short=reveal.height;a.prefs.put("motion","normal")
  descendants(sheet).first{it.contentDescription=="Показать цитаты"}.performClick();reveal.freeze();reveal.sample(0f);layout();assertEquals(short,reveal.height);reveal.sample(.5f);layout();val middle=reveal.height;save("quotes-opening-middle").recycle();reveal.sample(1f);layout();val full=reveal.height;assertTrue("continuous expansion",short<middle&&middle<full);assertEquals(3,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
  descendants(sheet).first{it.contentDescription=="Свернуть цитаты"}.performClick();reveal.freeze();reveal.sample(0f);layout();assertEquals(full,reveal.height);reveal.sample(.5f);layout();assertTrue(reveal.height in (short+1) until full);save("quotes-closing-middle").recycle();reveal.sample(1f);layout();assertEquals(short,reveal.height);assertEquals(1,descendants(sheet).filterIsInstance<QuoteStackLayer>().size);sheet.finish()
 }
 @Test fun reducedMotionRevealChangesImmediatelyWithoutTransparentInk(){
  val reveal=DetailReveal(a);a.page.addView(reveal);reveal.body.addView(a.text("Короткий текст",16f));layout();reveal.change(true){reveal.body.addView(a.text("Продолжение",16f))};layout();assertEquals(1f,reveal.progress,0f);assertEquals(1f,reveal.body.alpha,0f);assertEquals(reveal.body.height,reveal.height)
 }
 @Test fun swipeFlightFollowsFingerAndReleaseDoesNotResetThePose(){
  val origin=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(a.books.first(),origin);layout();val sheet=a.bookDetailSheet!!;a.prefs.put("motion","normal");sheet.beginDrag();sheet.dragTo(a.d(80).toFloat());assertEquals(a.d(80).toFloat(),sheet.panelView.translationY,.1f);val cover=descendants(sheet).filterIsInstance<CoverView>().single{it.visibility==View.VISIBLE};val x=cover.translationX;val y=cover.translationY;val scale=cover.scaleX;val q=sheet.motionProgress;save("drag-return-80").recycle()
  sheet.endDrag(true);sheet.freezeMotion();assertEquals(q,sheet.motionProgress,.0001f);assertEquals(x,cover.translationX,.1f);assertEquals(y,cover.translationY,.1f);assertEquals(scale,cover.scaleX,.0001f);assertEquals(a.d(80).toFloat(),sheet.panelView.translationY,.1f);sheet.sampleMotion(460,true);sheet.finish();assertEquals(View.VISIBLE,origin.visibility)
 }
 @Test fun realTouchDragAndCancelRestoresCoverWithoutJump(){
  val origin=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(a.books.first(),origin);layout();val sheet=a.bookDetailSheet!!;a.prefs.put("motion","normal");val top=sheet.panelView.top.toFloat()+a.d(9);val x=a.d(180).toFloat();val now=SystemClock.uptimeMillis()
  fun touch(action:Int,y:Float,t:Long){val e=MotionEvent.obtain(now,now+t,action,x,y,0);sheet.dispatchTouchEvent(e);e.recycle()}
  touch(MotionEvent.ACTION_DOWN,top,0);touch(MotionEvent.ACTION_MOVE,top+a.d(60),80);assertEquals(a.d(60).toFloat(),sheet.panelView.translationY,.5f);val before=sheet.motionProgress;touch(MotionEvent.ACTION_CANCEL,top+a.d(60),100);sheet.freezeMotion();assertEquals(before,sheet.motionProgress,.0001f);save("drag-cancel-start").recycle();sheet.sampleMotion(460,false);sheet.finish();assertEquals(View.VISIBLE,origin.visibility)
 }
 @Test fun openingLightDissolvesInsteadOfDisappearingAtSnapshotHandoff(){
  assertEquals(1f,OpeningTimeline.light(0f),0f);assertTrue(OpeningTimeline.light(.04f)>.9f);assertTrue(OpeningTimeline.light(.14f) in .49f.. .51f);assertEquals(0f,OpeningTimeline.light(.28f),0f)
  a.prefs.put("motion","normal");val w=a.root.width;val h=a.root.height;val clean=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLACK)};val lit=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)};val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.WHITE)};val snap=BookOpeningSnapshot(clean,art,RectF(w*.4f,h*.3f,w*.6f,h*.45f),background=Color.BLACK,litScene=lit);val live=View(a).apply{setBackgroundColor(Color.BLACK)};val stage=BookOpeningStage(a,snap,live,false);a.root.addView(stage,FrameLayout.LayoutParams(-1,-1));layout()
  fun pixel(t:Float):Int{stage.frame(t);val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);stage.draw(Canvas(b));val red=Color.red(b.getPixel(1,h/2));b.recycle();return red}
  val first=pixel(0f);val mid=pixel(.14f);val last=pixel(.28f);assertTrue(first>mid&&mid>last);assertEquals(0,last);stage.finish();assertTrue(lit.isRecycled)
 }
 @Test fun downwardContentSwipeCancelsChildClickAndCommitsFromFingerPosition(){
  val origin=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(a.books.first(),origin);layout();val sheet=a.bookDetailSheet!!;a.prefs.put("motion","normal");val top=sheet.panelView.top.toFloat()+a.d(320);val x=a.d(180).toFloat();val now=SystemClock.uptimeMillis()
  fun touch(action:Int,y:Float,t:Long){val e=MotionEvent.obtain(now,now+t,action,x,y,0);sheet.dispatchTouchEvent(e);e.recycle()}
  touch(MotionEvent.ACTION_DOWN,top,0);touch(MotionEvent.ACTION_MOVE,top+a.d(45),90);touch(MotionEvent.ACTION_MOVE,top+a.d(160),180);assertEquals(a.d(160).toFloat(),sheet.panelView.translationY,.5f);val before=sheet.motionProgress;touch(MotionEvent.ACTION_UP,top+a.d(160),200);sheet.freezeMotion();assertEquals(before,sheet.motionProgress,.0001f);assertNull("drag must not activate the reading button",a.reader);save("content-drag-release").recycle();sheet.sampleMotion(460,true);sheet.finish();assertEquals(View.VISIBLE,origin.visibility)
 }
 @Test fun actualDetailOpeningRetainsHaloWhilePreparingThenFadesDuringApproach(){
  a.detail(a.books.first());layout();val sheet=a.bookDetailSheet!!;val hero=descendants(sheet).filterIsInstance<CoverView>().single{it.visibility==View.VISIBLE};a.prefs.put("motion","normal");val snap=a.openingSnapshot(a.books.first().str("id"),hero)!!;assertNotNull(snap.litScene);val hold=BookOpeningHold(a,snap);a.root.addView(hold,FrameLayout.LayoutParams(-1,-1));layout();save("opening-light-hold").recycle();a.root.removeView(hold)
  val stage=BookOpeningStage(a,snap,View(a).apply{setBackgroundColor(a.bg)},false);a.root.addView(stage,FrameLayout.LayoutParams(-1,-1));layout();for(t in listOf(0f,.07f,.14f,.28f)){stage.frame(t);save("opening-light-${(t*100).toInt()}").recycle()};stage.finish();sheet.finish()
 }
 @Test fun landingHaloHasContinuousReversiblePositionBasedEnvelope(){
  a.detail(a.books.first());layout();val sheet=a.bookDetailSheet!!;assertEquals(0f,sheet.landingLight(.60f),.0001f);assertEquals(.5f,sheet.landingLight(.8828427f),.0001f);assertEquals(1f,sheet.landingLight(1f),.0001f);var previous=0f;for(i in 0..100){val alpha=sheet.landingLight(i/100f);assertTrue(alpha>=previous);assertTrue(alpha-previous<.065f);previous=alpha};sheet.finish()
 }
 @Test fun landingLightIsGentleAtDisplayCadenceAndAttachedToMovingCover(){
  a.prefs.put("motion","normal");val source=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(a.books.first(),source);layout();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();val glow=descendants(sheet).filterIsInstance<BookGlowView>().single();var previous=0f
  for(ms in 0L..464L step 16){sheet.sampleMotion(ms);val alpha=glow.alpha;assertTrue("no brightness burst at $ms ms: ${alpha-previous}",alpha-previous<.15f);assertTrue(alpha>=previous);previous=alpha
   val cover=descendants(sheet).filterIsInstance<CoverView>().single{it.visibility==View.VISIBLE};val cp=IntArray(2);val gp=IntArray(2);cover.getLocationOnScreen(cp);glow.getLocationOnScreen(gp)
   // Map the raster's centre through the glow view's transform; no glow left at its destination.
   val centre=glow.rasterBounds();val gx=gp[0]+centre.centerX()*glow.scaleX;val gy=gp[1]+centre.centerY()*glow.scaleY
   assertEquals("light centre follows moving cover x",cp[0]+cover.width*cover.scaleX*.5f,gx,3f);assertEquals("light centre follows moving cover y",cp[1]+cover.height*cover.scaleY*.5f,gy,3f)
  };sheet.finish()
 }
 @Test fun softHaloHasZeroEdgesAndGradualPenumbra(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(226,28,48))};val b=BookGlowRaster.build(art);val p=BookGlowRaster.PADDING
  for(x in 0 until b.width){assertEquals(0,Color.alpha(b.getPixel(x,0)));assertEquals(0,Color.alpha(b.getPixel(x,b.height-1)))}
  for(y in 0 until b.height){assertEquals(0,Color.alpha(b.getPixel(0,y)));assertEquals(0,Color.alpha(b.getPixel(b.width-1,y)))}
  var previous=0;for(x in 0..p){val alpha=Color.alpha(b.getPixel(x,p+72));assertTrue(alpha>=previous);assertTrue("soft spatial alpha gradient",alpha-previous<=6);previous=alpha};assertTrue(Color.alpha(b.getPixel(p-25,p+72))>25);b.recycle();art.recycle()
 }
 @Test fun referenceStyleRedCoverHaloRendersInBothThemes(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(222,23,42))};a.covers.put("red-soft-reference",art);val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","red-soft-reference"))
  for(theme in listOf("light","dark")){a.prefs.put("ui",theme);a.render(false,false);layout();val source=descendants(a.root).filterIsInstance<CoverView>().first();a.prefs.put("motion","normal");a.detail(book,source);layout();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();for(ms in listOf(80L,120L,160L,200L,280L,460L)){sheet.sampleMotion(ms);save("$theme-soft-red-$ms").recycle()};sheet.finish()}
 }
 @Test fun sheetEdgeFadeHasSmoothEndpointsAndNoToolbarBoundary(){
  assertEquals(1f,DetailTopFade.opacity(0f),0f);assertEquals(0f,DetailTopFade.opacity(1f),0f);assertTrue(DetailTopFade.opacity(.01f)>.999f);assertTrue(DetailTopFade.opacity(.99f)<.001f);var prev=1f;for(i in 1..100){val q=DetailTopFade.opacity(i/100f);assertTrue(q<=prev);assertTrue(prev-q<.016f);prev=q};assertTrue(DetailTopFade.opacity(68f/96f) in .1f.. .3f)
 }
 @Test fun haloHasNaturalTopPenumbraNotAnOpaqueHeaderMask(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(244,219,175))};val b=BookGlowRaster.build(art);val pad=BookGlowRaster.PADDING
  val top=Color.alpha(b.getPixel(pad+48,pad-10));val bottom=Color.alpha(b.getPixel(pad+48,pad+144+9));assertTrue("light exists above book rather than stopping below toolbar",top>60);assertEquals("Gaussian top/bottom symmetry, no baked toolbar mask",top.toFloat(),bottom.toFloat(),4f);art.recycle();b.recycle()
 }
 @Test fun detailContentPassesBehindFloatingControlsToTheRealSheetEdge(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(238,28,46))};a.covers.put("header-boundary-red",art);val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","header-boundary-red"))
  for(theme in listOf("light","dark")){
   a.prefs.put("ui",theme);a.prefs.put("motion","reduced");a.render(false,false);layout();a.detail(book);layout();val sheet=a.bookDetailSheet!!;val panel=sheet.panelView;val scroller=descendants(sheet).filterIsInstance<ScrollView>().single();assertTrue(panel.clipToOutline);assertEquals(0,scroller.top);assertEquals(panel.height,scroller.height)
   val close=descendants(sheet).first{it.contentDescription=="Закрыть"};val toolbar=close.parent as View;assertNull("no opaque full-width internal toolbar",toolbar.background)
   scroller.scrollTo(0,a.d(80));layout();val frame=save("$theme-detail-through-header");val pos=IntArray(2);panel.getLocationOnScreen(pos);val rootAt=IntArray(2);a.root.getLocationOnScreen(rootAt);val x=a.d(126)+pos[0]-rootAt[0];val y=a.d(27)+pos[1]-rootAt[1];val c=frame.getPixel(x,y);assertEquals(1,descendants(sheet).filterIsInstance<DetailTopFade>().size)
   val bg=if(theme=="light")0xfff7f7f5.toInt()else 0xff1c1c1e.toInt()
   fun contrast(yDp:Int):Int{val pixel=frame.getPixel(x,a.d(yDp)+pos[1]-rootAt[1]);return abs(Color.red(pixel)-Color.red(bg))+abs(Color.green(pixel)-Color.green(bg))+abs(Color.blue(pixel)-Color.blue(bg))}
   assertTrue("content fades smoothly rather than clipping at the toolbar",contrast(20)<contrast(45)&&contrast(45)<contrast(75));assertTrue("content remains visible within former header",contrast(45)>40);assertTrue("full cover below fade",contrast(90)>200);frame.recycle()
   for(offset in listOf(24,48,100,160)){scroller.scrollTo(0,a.d(offset));layout();save("$theme-detail-header-scroll-$offset").recycle()}
   scroller.scrollTo(0,0);sheet.finish()
   a.prefs.put("motion","normal");val source=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(book,source);layout();val moving=a.bookDetailSheet!!;moving.freezeMotion();moving.sampleMotion(160);save("$theme-detail-header-flight").recycle();moving.sampleMotion(460);moving.beginDrag();moving.dragTo(a.d(55).toFloat());layout();save("$theme-detail-header-drag").recycle();moving.endDrag(false);moving.freezeMotion();moving.sampleMotion(460);moving.finish()
  }
 }
 @Test fun glowPaintOwnsOpacityAndPaddedBoundsContainTheEntirePenumbra(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(246,224,183))};a.covers.put("bright-halo-bounds",art);val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","bright-halo-bounds"));a.detail(book);layout();val sheet=a.bookDetailSheet!!;val glow=descendants(sheet).filterIsInstance<BookGlowView>().single();assertTrue(glow.ready)
  val r=glow.rasterBounds();assertTrue("penumbra has real transparent padding inside its own view: $r / ${glow.width}x${glow.height}",r.left>=0&&r.top>=0&&r.right<=glow.width&&r.bottom<=glow.height)
  for(alpha in listOf(0f,.1f,.5f,.99f,1f)){glow.alpha=alpha;assertTrue("opacity belongs to bitmap paint, not a cropped GPU alpha layer",kotlin.math.abs((alpha*255).toInt()-glow.inkOpacity)<=1)};sheet.finish()
 }
 @Test fun brightHaloHasNoBottomStepWhenPartialAlphaBecomesFullAlpha(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(255,235,196))};a.covers.put("bright-halo-edge",art);val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","bright-halo-edge"));a.detail(book);layout();val sheet=a.bookDetailSheet!!;val glow=descendants(sheet).filterIsInstance<BookGlowView>().single();assertTrue(glow.ready);val pad=a.d(80)
  fun capture(opacity:Float,bounded:Boolean):Bitmap{glow.alpha=opacity;val b=Bitmap.createBitmap(glow.width+pad*2,glow.height+pad*2,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.translate(pad.toFloat(),pad.toFloat());val save=if(bounded)c.saveLayer(0f,0f,glow.width.toFloat(),glow.height.toFloat(),null)else c.save();glow.draw(c);c.restoreToCount(save);return b}
  val bounded=capture(.5f,true);val unbounded=capture(.5f,false);assertTrue("the GPU's finite layer bounds cannot crop any glow pixels",bounded.sameAs(unbounded));bounded.recycle();unbounded.recycle()
  val before=capture(.99f,true);val after=capture(1f,false);val p=IntArray(before.width*before.height);val q=IntArray(p.size);before.getPixels(p,0,before.width,0,0,before.width,before.height);after.getPixels(q,0,after.width,0,0,after.width,after.height);var maxJump=0;var bottomLit=0;val oldBottom=a.d(234)-glow.top+pad
  for(i in p.indices){maxJump=maxOf(maxJump,kotlin.math.abs(Color.alpha(p[i])-Color.alpha(q[i])));if(i/after.width>oldBottom&&Color.alpha(q[i])>10)bottomLit++};assertTrue("there really is light below the former 234dp cutoff",bottomLit>100);assertTrue("no new band appears when full alpha drops implicit GPU layer: $maxJump",maxJump<=4);before.recycle();after.recycle();sheet.finish()
 }
 @Test fun brightCoverLandingAndSwipeKeepTheSameUncroppedLightShape(){
  val art=android.graphics.BitmapFactory.decodeStream(javaClass.getResourceAsStream("/halo-cream-reference.jpg"))!!;a.covers.put("warm-landing",art)
  val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","warm-landing"));val source=descendants(a.root).filterIsInstance<CoverView>().first();a.prefs.put("motion","normal");a.detail(book,source);layout();val sheet=a.bookDetailSheet!!;sheet.freezeMotion()
  for(ms in listOf(230L,280L,340L,410L,459L,460L)){sheet.sampleMotion(ms);layout();save("warm-landing-$ms").recycle()}
  sheet.sampleMotion(460);sheet.beginDrag();for(dy in listOf(12,28,50)){sheet.dragTo(a.d(dy).toFloat());layout();save("warm-drag-$dy").recycle()};sheet.endDrag(false);sheet.freezeMotion();sheet.sampleMotion(460);sheet.finish()
 }
 private class MovingPage(a:MainActivity):View(a){val p=Paint();var ink=Color.RED;var phase=0f;override fun onDraw(c:Canvas){c.drawColor(ink);p.color=Color.YELLOW;for(i in 0..12)c.drawRect(i*100f+phase,0f,i*100f+phase+24,height.toFloat(),p)}}
 @Test fun bookDetailFlightHasShadowOutsideCoverFromItsFirstMovingFrames(){a.prefs.put("motion","normal");val origin=descendants(a.root).filterIsInstance<CoverView>().first();a.detail(a.books.first(),origin);layout();val detail=a.bookDetailSheet!!;detail.freezeMotion();assertFalse(detail.clipChildren);detail.sampleMotion(230);layout();val flight=descendants(detail).filterIsInstance<CoverView>().first{it.visibility==View.VISIBLE};val rect=android.graphics.Rect();flight.getGlobalVisibleRect(rect);val visible=save("detail-flight-shadow");flight.visibility=View.INVISIBLE;val hidden=save("detail-flight-hidden-probe");flight.visibility=View.VISIBLE;var different=0;val left=(flight.x+flight.width*flight.scaleX).toInt();val top=(flight.y+flight.height*flight.scaleY*.2f).toInt();val bottom=(flight.y+flight.height*flight.scaleY*.8f).toInt();for(y in top.coerceAtLeast(0) until bottom.coerceAtMost(visible.height))for(x in (left+1).coerceAtLeast(0)..(left+a.d(8)).coerceAtMost(visible.width-1)){if(visible.getPixel(x,y)!=hidden.getPixel(x,y))different++};assertTrue("shadow must be drawn beyond the moving cover bounds, not appear at handoff",different>20);visible.recycle();hidden.recycle();for(ms in listOf(0L,90L,230L,460L)){detail.sampleMotion(ms);save("detail-$ms-sampling").recycle();drain();save("detail-$ms").recycle()};detail.finish()}
 @Test fun recommendationCollapseStartsFromPresentationAndEndsWithIdenticalDeck(){a.prefs.put("motion","reduced");val tab=MainActivity::class.java.getDeclaredField("tab").apply{isAccessible=true};tab.set(a,"foryou");a.render(false,false);layout();drain();a.page.removeAllViews();val host=a.column();a.page.addView(host);RecommendationDeck(a,host,JSONObject(a.assets.open("recs.json").bufferedReader().readText()),JSONObject().put("onboarded",true).put("coached",true)).start();layout();val source=descendants(host).filterIsInstance<RecommendationCard>().last();a.prefs.put("motion","normal");val expansion=RecommendationExpansion(a,source.book,source){};expansion.show();layout();expansion.freezeMotion();expansion.sampleMotion(180);val cover=descendants(expansion.contentRoot).filterIsInstance<CoverView>().single();val y=cover.translationY;val scale=cover.scaleX;expansion.dismiss();expansion.freezeMotion();assertEquals(y,cover.translationY,.01f);assertEquals(scale,cover.scaleX,.001f);for(ms in listOf(0L,100L,260L,440L,600L,620L)){expansion.sampleMotion(ms,true);save("recommendation-close-$ms").recycle()};val last=save("recommendation-last-before-removal");expansion.stopMotion();val after=save("recommendation-after-removal");val p=IntArray(last.width*last.height);val q=IntArray(p.size);last.getPixels(p,0,last.width,0,0,last.width,last.height);after.getPixels(q,0,after.width,0,0,after.width,after.height);var error=0L;for(i in p.indices)error+=abs(Color.red(p[i])-Color.red(q[i]))+abs(Color.green(p[i])-Color.green(q[i]))+abs(Color.blue(p[i])-Color.blue(q[i]));assertTrue("removing the final transparent layer must not change the scene: ${error.toDouble()/p.size}",error.toDouble()/p.size<1.0);assertEquals(1f,source.alpha,0f);last.recycle();after.recycle()}
 @Test fun deferredRefreshAfterDescriptionDoesNotRecreateOrRerankTheDeck(){
  a.prefs.put("motion","reduced");MainActivity::class.java.getDeclaredField("tab").apply{isAccessible=true}.set(a,"foryou");a.render(false,false);layout();drain()
  a.page.removeAllViews();val host=a.column();a.page.addView(host);val deck=RecommendationDeck(a,host,JSONObject(a.assets.open("recs.json").bufferedReader().readText()),JSONObject().put("onboarded",true).put("coached",true));deck.start();layout()
  val source=descendants(host).filterIsInstance<RecommendationCard>().last();val order=deck.list.map{it.str("rec")};val originalPage=a.page;val originalScroll=a.scroll
  a.prefs.put("motion","normal");deck.expanded(source.book,source);layout();val expansion=a.recommendationExpansion!!;expansion.freezeMotion();expansion.sampleMotion(1200)
  // A reload/save arriving while the description is open used to tear down the whole
  // screen immediately after the final closing frame and asynchronously rank a new deck.
  a.render(true,false);assertSame(originalPage,a.page)
  expansion.dismiss();expansion.freezeMotion();expansion.sampleMotion(580,true)
  val big=descendants(expansion.contentRoot).filterIsInstance<CoverView>().single();assertEquals("no double-cover crossfade",1f,big.alpha,0f)
  // Shadows hand over smoothly (no one-frame halo pop at landing); cover pixels never cross-fade.
  val srcCover=descendants(source).filterIsInstance<CoverView>().single()
  assertTrue("card shadow already easing in before landing",srcCover.shadowFade>0f&&srcCover.shadowFade<1f);assertTrue("flying shadow easing out",big.shadowFade<1f)
  var last=-1f;for(ms in 240L..620L step 20L){expansion.sampleMotion(ms,true);assertTrue("card shadow monotonic at $ms",srcCover.shadowFade>=last);assertTrue("card shadow step <=0.2 at $ms",last<0||srcCover.shadowFade-last<=.2f);last=srcCover.shadowFade}
  assertEquals(1f,srcCover.shadowFade,0f);assertEquals(0f,big.shadowFade,0f);expansion.sampleMotion(580,true)
  expansion.sampleMotion(620,true);val before=save("deck-deferred-before-removal");expansion.stopMotion();layout();drain()
  assertSame(originalPage,a.page);assertSame(originalScroll,a.scroll);assertSame(deck,a.recommendationDeck);assertSame(source,descendants(host).filterIsInstance<RecommendationCard>().last());assertEquals(order,deck.list.map{it.str("rec")});assertFalse(deck.busy)
  val after=save("deck-deferred-after-removal");var delta=0L;for(y in 0 until before.height step 3)for(x in 0 until before.width step 3){val p=before.getPixel(x,y);val q=after.getPixel(x,y);delta+=abs(Color.red(p)-Color.red(q))+abs(Color.green(p)-Color.green(q))+abs(Color.blue(p)-Color.blue(q))};assertTrue("closing must not trigger a new blank/loading scene: $delta",delta<before.width*before.height/9);before.recycle();after.recycle()
 }
 @Test fun readingQueueIncludesEveryInProgressBookAndCarouselKeepsNextCardVisible(){
  fun book(id:String,opened:Long,pct:Double,noFile:Boolean=false)=JSONObject().put("id",id).put("title","Книга $id").put("authors",JSONArray().put("Автор")).put("openedAt",opened).put("progress",JSONObject().put("pct",pct)).put("noFile",noFile)
  val books=listOf(book("older",1,.3),book("latest",9,.1),book("done",10,1.0),book("want",0,0.0),book("missing",20,.4,true),book("progress-only",0,.2))
  assertEquals(listOf("latest","older","progress-only"),ReadingQueue.books(books).map{it.str("id")})
  a.page.removeAllViews();val carousel=ContinueReading(a,books);a.page.addView(carousel);layout();val scroll=descendants(carousel).filterIsInstance<HorizontalScrollView>().single();val strip=scroll.getChildAt(0) as LinearLayout
  assertEquals(3,strip.childCount);assertTrue(strip.getChildAt(0).width<scroll.width);assertEquals(3,descendants(carousel).filterIsInstance<CoverView>().size);save("continue-carousel").recycle()
  scroll.scrollTo(strip.getChildAt(1).left,0);assertEquals("older",a.continueBookId)
  a.page.removeAllViews();val single=ContinueReading(a,listOf(books.first()));a.page.addView(single);layout();val one=descendants(single).filterIsInstance<HorizontalScrollView>().single();assertEquals(one.width-a.d(40),(one.getChildAt(0) as LinearLayout).getChildAt(0).width)
 }
 @Test fun bookDetailsHaveLiveQuotesExpandableDescriptionAndWrappingMetadata(){
  val book=a.books.first().copyJson().put("annotation",("История о море и людях, которые берегут свет. ").repeat(20)).put("shelf","Очень длинное название полки, которое должно переноситься").put("series","Большая морская библиотека")
  book.put("quotes",JSONArray((0..4).map{JSONObject().put("id","quote-$it").put("ch",0).put("start",it*20).put("text","Сохранённая цитата $it. Мы читали дальше, сохраняя в памяти этот свет.")}))
  for(theme in listOf("light","dark")){a.prefs.put("ui",theme);a.render(false,false);layout();a.detail(book.copyJson());layout();drain();val sheet=a.bookDetailSheet!!;sheet.freezeMotion();sheet.sampleMotion(460);layout()
   assertTrue(descendants(sheet).filterIsInstance<TextView>().any{it.text.toString()=="Ваши цитаты · 5"});assertEquals(0,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
   val labels=descendants(sheet).filterIsInstance<TextView>().map{it.text.toString()};assertTrue(labels.indexOf("Описание")<labels.indexOf("Ваши цитаты · 5"));assertTrue(labels.indexOf("Ваши цитаты · 5")<labels.indexOf("Впечатления"))
   save("$theme-detail-redesign").recycle();descendants(sheet).first{it.contentDescription=="Показать цитаты"}.performClick();assertEquals(3,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
   descendants(sheet).first{it.contentDescription=="Следующие цитаты"}.performClick();assertEquals(2,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
   descendants(sheet).first{it.contentDescription=="Свернуть цитаты"}.performClick();assertEquals(0,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true})
   descendants(sheet).first{it.contentDescription=="Показать цитаты"}.performClick();assertEquals(3,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});layout();val scroller=descendants(sheet).filterIsInstance<ScrollView>().single();val heading=descendants(sheet).filterIsInstance<TextView>().first{it.text.toString()=="Ваши цитаты · 5"};val region=Rect();heading.getDrawingRect(region);(scroller.getChildAt(0) as ViewGroup).offsetDescendantRectToMyCoords(heading,region);scroller.scrollTo(0,(region.top-a.d(90)).coerceAtLeast(0));layout();save("$theme-detail-quotes-expanded").recycle()
   val more=descendants(sheet).filterIsInstance<TextView>().first{it.text.toString()=="Читать полностью"};more.performClick();assertEquals("Свернуть",more.text.toString());more.performClick();assertEquals("Читать полностью",more.text.toString())
   a.publishBook(book.copyJson().put("quotes",JSONArray()));assertTrue(descendants(sheet).filterIsInstance<TextView>().any{it.text.toString()=="Ваши цитаты"});assertFalse(descendants(sheet).any{it.contentDescription?.startsWith("Открыть цитату:")==true});sheet.finish()
  }
 }
 @Test fun navigationCanBeScrubbedWithFinger(){a.prefs.put("motion","normal");val nav=descendants(a.root).filterIsInstance<PolkaNavigation>().single();var selected="";nav.bind("home"){selected=it};val now=SystemClock.uptimeMillis();for((action,x,dt)in listOf(Triple(MotionEvent.ACTION_DOWN,26f,0L),Triple(MotionEvent.ACTION_MOVE,96f,70L),Triple(MotionEvent.ACTION_UP,156f,120L))){val e=MotionEvent.obtain(now,now+dt,action,a.d(x.toInt()).toFloat(),a.d(26).toFloat(),0);nav.dispatchTouchEvent(e);e.recycle()};assertEquals("quotes",selected);nav.sampleIndicator(620);assertEquals(a.d(112).toFloat(),nav.indicatorOffset,.01f)}
 @Test fun sharedSurfacesCoverSheetsReaderSelectionAndEditorWithoutObscuringText(){a.prefs.put("motion","reduced");val sheet=a.dialog().setTitle("Непрозрачная панель").setView(a.column().apply{addView(a.edit("","Название"));addView(a.button("Проверить" ){})}).show();val frame=descendants(sheet.window!!.decorView).filterIsInstance<PolkaSheetFrame>().single();assertTrue(frame.panel.background is OpaqueSurface);assertTrue((frame.panel.background as OpaqueSurface).flushBottom);sheet.dismiss();a.openBook(a.books.first());repeat(8){drain();layout()};val r=a.reader!!;r.showChrome(true);layout();assertTrue(descendants(r.view).count{it.background is OpaqueSurface}>=2);save("reader-chrome-cold").recycle();drain();save("reader-chrome").recycle();r.editChapter();layout();assertTrue(descendants(r.view).count{it.background is OpaqueSurface}>=2);save("editor-controls-sampling").recycle();drain();save("editor-controls").recycle()}
 @Test fun bottomSheetNeverShowsRestingPoseOrFullScrimBeforeEntry(){a.prefs.put("motion","normal");val sheet=a.dialog().setTitle("Настройки полки").setView(a.edit("","Название")).show();val frame=descendants(sheet.window!!.decorView).filterIsInstance<PolkaSheetFrame>().single();assertEquals("panel hidden synchronously, not in posted task",0f,frame.panel.alpha,0f);assertEquals("no one-frame dim flash",0f,frame.shade.alpha,0f);frame.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));frame.layout(0,0,a.d(360),a.d(780));frame.viewTreeObserver.dispatchOnPreDraw();assertEquals(frame.panel.height.toFloat(),frame.panel.translationY,.1f);assertEquals(0f,frame.shade.alpha,.001f);var previous=frame.panel.translationY;for(ms in listOf(16L,48L,96L,160L,260L,440L,620L)){frame.sampleEntry?.invoke(ms);assertTrue("entry moves continuously toward rest",frame.panel.translationY<=previous+a.d(1));previous=frame.panel.translationY;val b=Bitmap.createBitmap(frame.width,frame.height,Bitmap.Config.ARGB_8888);b.eraseColor(a.bg);frame.draw(Canvas(b));val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.26").apply{mkdirs()};File(dir,"sheet-entry-$ms.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()};assertEquals(0f,frame.panel.translationY,.1f);sheet.dismiss();frame.sampleEntry?.invoke(620)}
 @Test fun glowRasterPreservesSpatialCoverColoursAndSmoothFalloff(){
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888);val c=Canvas(art);val p=Paint();p.color=Color.RED;c.drawRect(0f,0f,48f,144f,p);p.color=Color.BLUE;c.drawRect(48f,0f,96f,144f,p)
  val glow=BookGlowRaster.build(art);val pad=BookGlowRaster.PADDING;val left=glow.getPixel(pad+8,pad+72);val right=glow.getPixel(pad+88,pad+72)
  assertTrue(Color.red(left)>Color.blue(left)*3);assertTrue(Color.blue(right)>Color.red(right)*3)
  assertTrue(Color.alpha(glow.getPixel(pad-5,pad+72))>Color.alpha(glow.getPixel(pad-25,pad+72)));assertTrue(Color.alpha(glow.getPixel(pad-25,pad+72))>Color.alpha(glow.getPixel(pad-50,pad+72)));assertEquals(0,Color.alpha(glow.getPixel(0,0)))
  art.eraseColor(Color.rgb(72,122,174));assertEquals(Color.rgb(72,122,174),BookCoverVolume.binding(art,Color.MAGENTA));art.recycle();glow.recycle()
 }
 @Test fun realArtworkGlowExistsOnlyInDetailsCachesAndStaysOutOfReadingSnapshots(){
  assertFalse(descendants(a.root).any{it is BookGlowView})
  val art=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888);val c=Canvas(art);val p=Paint();p.color=Color.rgb(230,36,50);c.drawRect(0f,0f,48f,144f,p);p.color=Color.rgb(30,80,240);c.drawRect(48f,0f,96f,144f,p);a.covers.put("colour-probe",art)
  val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","colour-probe"))
  for(theme in listOf("light","dark")){
   a.prefs.put("ui",theme);a.render(false,false);layout();a.detail(book.copyJson());layout();val detail=a.bookDetailSheet!!;detail.freezeMotion();detail.sampleMotion(460);layout();val glow=descendants(detail).filterIsInstance<BookGlowView>().single()
   val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(!glow.ready&&System.nanoTime()<deadline){val b=save("$theme-glow-preparing");b.recycle();Thread.sleep(30);advance(40)}
   assertTrue("real artwork halo ready",glow.ready);glow.finishReveal();val count=glow.rasterBuilds;repeat(4){save("$theme-actual-cover-glow").recycle()};assertEquals(count,glow.rasterBuilds)
   val cover=descendants(detail).filterIsInstance<CoverView>().single{it.visibility==View.VISIBLE};a.prefs.put("motion","normal");val snapshot=a.openingSnapshot(book.str("id"),cover)!!
   assertNotNull("detail light is preserved separately for fade-out",snapshot.litScene);assertFalse("lit and clean scenes differ",snapshot.litScene!!.sameAs(snapshot.scene));assertEquals(View.VISIBLE,glow.visibility);assertTrue("binding comes from the actual red/blue cover, not title palette",Color.red(snapshot.binding)>180||Color.blue(snapshot.binding)>180);val withLight=snapshot.scene;glow.visibility=View.INVISIBLE;val without=a.openingSnapshot(book.str("id"),cover)!!;assertTrue("no halo baked into reader opening scene",withLight.sameAs(without.scene));glow.visibility=View.VISIBLE;snapshot.release();without.release();a.prefs.put("motion","reduced")
   detail.finish();assertFalse(glow.ready);assertFalse(descendants(a.root).any{it is BookGlowView})
  }
 }
 @Test fun aThousandQuotesNeverMountMoreThanThreeCards(){
  val book=a.books.first().copyJson().put("quotes",JSONArray((0 until 1000).map{JSONObject().put("id","q$it").put("ch",0).put("start",it*10).put("text","Цитата $it")}))
  a.detail(book);layout();val sheet=a.bookDetailSheet!!;assertEquals(0,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});descendants(sheet).first{it.contentDescription=="Показать цитаты"}.performClick()
  repeat(15){assertEquals(3,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});descendants(sheet).first{it.contentDescription=="Следующие цитаты"}.performClick()}
  a.publishBook(book.copyJson().put("quotes",JSONArray().put(JSONObject().put("id","only").put("text","Единственная цитата"))));assertEquals(1,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});descendants(sheet).first{it.contentDescription=="Свернуть цитаты"}.performClick();assertEquals(0,descendants(sheet).count{it.contentDescription?.startsWith("Открыть цитату:")==true});sheet.finish()
 }
 @Test fun actionMenusUseRealVectorsAndPreserveActionCallbacks(){
  for(theme in listOf("light","dark")){
   a.prefs.put("ui",theme);a.render(false,false);layout();a.bookActions(a.books.first());val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val decor=dialog.window!!.decorView
   decor.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));decor.layout(0,0,a.d(360),a.d(780));decor.viewTreeObserver.dispatchOnPreDraw()
   val labels=listOf("О книге","Изменить данные","Переместить на полку","Оценить","Мысли о книге","Прикрепить файл","Отметить прочитанной","Скачать FB2","Скачать EPUB","Удалить книгу")
   val rows=labels.map{label->descendants(decor).filterIsInstance<TextView>().first{it.text.toString()==label}}
   rows.forEach{row->val icon=row.compoundDrawablesRelative[0];assertTrue("missing vector on ${row.text}",icon is ActionIconDrawable);assertTrue(row.height>=a.d(48));assertTrue(row.compoundDrawablePadding>=a.d(12));val raster=Bitmap.createBitmap(a.d(22),a.d(22),Bitmap.Config.ARGB_8888);icon.draw(Canvas(raster));val pixels=IntArray(raster.width*raster.height);raster.getPixels(pixels,0,raster.width,0,0,raster.width,raster.height);assertTrue("empty icon for ${row.text}",pixels.count{Color.alpha(it)>0}>20);raster.recycle()}
   val image=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888);decor.draw(Canvas(image));val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.26").apply{mkdirs()};File(dir,"$theme-book-action-icons.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)};image.recycle()
   assertEquals(0xffd9504e.toInt(),rows.last().currentTextColor);rows[1].performClick();assertTrue(descendants(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<TextView>().any{it.text.toString()=="Данные книги"});org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss()
  }
  a.shelfActions("Полка");val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();val rows=descendants(dialog.window!!.decorView).filterIsInstance<TextView>().filter{it.text.toString() in listOf("Переименовать","Добавить книги на полку","Удалить полку")};assertEquals(3,rows.size);assertTrue(rows.all{it.compoundDrawablesRelative[0] is ActionIconDrawable});dialog.dismiss()
 }
 @Test fun labelledBackUsesSameChevronAndTouchSizeAsOtherBackControls(){
  var pressed=0;val labelled=a.backControl("Полки","Назад к полкам"){pressed++};a.page.removeAllViews();a.page.addView(labelled);layout();val glyph=descendants(labelled).filterIsInstance<IconView>().single();assertEquals(a.d(44),glyph.width);assertEquals(a.d(44),glyph.height)
  val standard=a.icon("back","Назад"){};standard.measure(View.MeasureSpec.makeMeasureSpec(a.d(44),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(44),View.MeasureSpec.EXACTLY));standard.layout(0,0,a.d(44),a.d(44));val one=Bitmap.createBitmap(a.d(44),a.d(44),Bitmap.Config.ARGB_8888);val two=one.copy(Bitmap.Config.ARGB_8888,true);glyph.draw(Canvas(one));standard.draw(Canvas(two));assertTrue(one.sameAs(two));labelled.performClick();assertEquals(1,pressed);one.recycle();two.recycle()
 }
 private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
 @Test fun closingCoverKeepsItsBottomAndHandsOffWithoutAFlash(){
  val artwork=Bitmap.createBitmap(96,144,Bitmap.Config.ARGB_8888).apply{eraseColor(0xff184fd4.toInt());Canvas(this).drawRect(0f,0f,96f,48f,Paint().apply{color=0xffdf2438.toInt()})}
  a.covers.put("return-probe",artwork)
  val book=a.books.first().copyJson().put("cover",JSONObject().put("__polkaBlob","return-probe")).put("openedAt",100).put("progress",JSONObject().put("pct",.3))
  a.books=listOf(book);a.render(false,false);layout()
  for(kind in listOf("carousel","grid")){
   val covers=descendants(a.page).filterIsInstance<CoverView>();val origin=if(kind=="carousel")covers.first()else covers.last()
   a.prefs.put("motion","reduced");a.detail(book,origin);layout();val sheet=a.bookDetailSheet!!
   assertFalse("no visible detail heading",descendants(sheet).filterIsInstance<TextView>().any{it.text=="О книге"})
   a.prefs.put("motion","normal");sheet.dismiss();sheet.freezeMotion();assertEquals(View.INVISIBLE,origin.visibility)
   for(ms in listOf(0L,70L,160L,260L,390L,459L,460L)){
    sheet.sampleMotion(ms,true);layout();val flight=descendants(sheet).filterIsInstance<CoverView>().single{it.visibility==View.VISIBLE}
    val frame=save("$kind-close-$ms");val x=(flight.x+flight.width*flight.scaleX*.5f).toInt();val y=(flight.y+flight.height*flight.scaleY*.92f).toInt()
    assertTrue("bottom of cover remains visible at $kind/$ms",Color.blue(frame.getPixel(x,y))>120&&Color.red(frame.getPixel(x,y))<100);frame.recycle()
   }
   val last=save("$kind-close-before-handoff");sheet.finish();layout();val restored=save("$kind-close-after-handoff")
   val pos=IntArray(2);origin.getLocationOnScreen(pos);val root=IntArray(2);a.root.getLocationOnScreen(root);val x=pos[0]-root[0]+origin.width/2;val y=pos[1]-root[1]+origin.height*9/10
   assertEquals("same artwork on the handoff frame",last.getPixel(x,y),restored.getPixel(x,y))
   var total=0L;var samples=0;for(dy in origin.height/10 until origin.height*9/10 step 4)for(dx in origin.width/10 until origin.width*9/10 step 4){val px=pos[0]-root[0]+dx;val py=pos[1]-root[1]+dy;val before=last.getPixel(px,py);val after=restored.getPixel(px,py);total+=abs(Color.red(before)-Color.red(after))+abs(Color.green(before)-Color.green(after))+abs(Color.blue(before)-Color.blue(after));samples++}
   assertTrue("no cover-wide flash at handoff: $kind / ${total.toFloat()/samples}",total.toFloat()/samples<2f);assertEquals(View.VISIBLE,origin.visibility);assertNull(a.bookDetailSheet);last.recycle();restored.recycle()
  }
 }

}
