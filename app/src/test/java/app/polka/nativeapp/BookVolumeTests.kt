package app.polka.nativeapp

import android.content.Context
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
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
class BookVolumeTests {
 private val context get()=ApplicationProvider.getApplicationContext<Context>()
 private fun book(i:Int)=JSONObject().put("id","volume-$i").put("title",listOf("Смотритель маяка","Тихий берег","Дом у моря","По ту сторону ночи")[i%4]).put("authors",JSONArray().put("Полка"))
 private fun save(b:Bitmap,name:String){val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.25/volume").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 @Test fun shadowHasSoftPenumbraTightContactAndNoRectangularCutoff(){for(dark in listOf(false,true)){val b=Bitmap.createBitmap(440,580,Bitmap.Config.ARGB_8888);val c=Canvas(b);val r=RectF(100f,80f,340f,440f);BookCoverVolume.shadow(c,r,dark)
  val close=Color.alpha(b.getPixel(341,260));val far=Color.alpha(b.getPixel(357,260));assertTrue("contact=$close penumbra=$far",close>far&&far>0);assertEquals(0,Color.alpha(b.getPixel(20,20)));assertTrue("light comes from above-left",Color.alpha(b.getPixel(220,448))>Color.alpha(b.getPixel(220,70)));save(b,if(dark)"shadow-dark-alpha" else "shadow-light-alpha");b.recycle()}}
 @Test fun atlasIsReusedForAllSizesAndMotionFrames(){val b=Bitmap.createBitmap(600,900,Bitmap.Config.ARGB_8888);val c=Canvas(b);BookCoverVolume.shadow(c,RectF(40f,40f,200f,280f),false);BookCoverVolume.shadow(c,RectF(40f,40f,200f,280f),true);val before=BookCoverVolume.atlasBuilds;repeat(100){BookCoverVolume.shadow(c,RectF(50f,50f,250f+it,350f+it*1.5f),it%2==0)};assertEquals(before,BookCoverVolume.atlasBuilds);b.recycle()}
 @Test fun floatingStackShadowHasWiderSofterFalloffAndReusesItsAtlas(){
  for(dark in listOf(false,true)){val hard=Bitmap.createBitmap(500,650,Bitmap.Config.ARGB_8888);val soft=Bitmap.createBitmap(500,650,Bitmap.Config.ARGB_8888);val r=RectF(120f,96f,360f,456f);BookCoverVolume.shadow(Canvas(hard),r,dark);BookCoverVolume.shadow(Canvas(soft),r,dark,true)
   assertTrue("no hard contact band",Color.alpha(soft.getPixel(361,276))<Color.alpha(hard.getPixel(361,276)));assertTrue("wide feathered shadow",Color.alpha(soft.getPixel(390,276))>0);assertEquals(0,Color.alpha(soft.getPixel(0,0)));val built=BookCoverVolume.atlasBuilds;repeat(40){BookCoverVolume.shadow(Canvas(soft),r,dark,true)};assertEquals(built,BookCoverVolume.atlasBuilds);save(soft,if(dark)"floating-shadow-dark" else "floating-shadow-light");hard.recycle();soft.recycle()
  }
 }
 @Test fun recommendationStackHasWeakerAndWiderShadowWithoutContactRing(){
  val r=RectF(120f,96f,360f,456f)
  for(dark in listOf(false,true)){
   val old=Bitmap.createBitmap(480,600,Bitmap.Config.ARGB_8888);val stack=Bitmap.createBitmap(480,600,Bitmap.Config.ARGB_8888)
   BookCoverVolume.shadow(Canvas(old),r,dark,true);BookCoverVolume.shadow(Canvas(stack),r,dark,true,true)
   assertTrue("weaker shadow beside stack",Color.alpha(stack.getPixel(365,276))<Color.alpha(old.getPixel(365,276)))
   assertTrue("feather extends into surrounding space",Color.alpha(stack.getPixel(405,276))>0)
   save(stack,if(dark)"stack-shadow-dark-0620" else "stack-shadow-light-0620");old.recycle();stack.recycle()
  }
 }
 @Test fun stackShadowAtlasHasTransparentPaddingOnItsLeftBoundary(){
  for(dark in listOf(false,true)){
   val image=Bitmap.createBitmap(960,1020,Bitmap.Config.ARGB_8888);BookCoverVolume.shadow(Canvas(image),RectF(360f,300f,600f,660f),dark,true,true)
   for(y in 0 until image.height)assertEquals("no clipped vertical line at atlas left, y=$y",0,Color.alpha(image.getPixel(120,y)))
   assertTrue("left penumbra retained",Color.alpha(image.getPixel(340,480))>0);image.recycle()
  }
 }
 @Test fun photographicCoverKeepsItsArtAndRecessedPaperIsNotWhiteGlow(){val photo=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);Canvas(photo).drawColor(0xff287d8d.toInt());val b=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888);CoverView(context,book(0),photo).drawCover(Canvas(b),RectF(0f,0f,400f,600f),book(0),photo);val centre=b.getPixel(200,300);assertTrue(Color.blue(centre)>Color.red(centre)*2);val edge=b.getPixel(395,300);assertTrue("paper edge should be warm-neutral, not white: rgb=$edge r=${Color.red(edge)} g=${Color.green(edge)} b=${Color.blue(edge)}",Color.red(edge) in 130..240 && kotlin.math.abs(Color.red(edge)-Color.blue(edge))<40);save(b,"photographic-detail");b.recycle();photo.recycle()}
 @Test fun shadowSurvivesRealUnclippedParentAndDoesNotChangeHitBounds(){val parent=FrameLayout(context).apply{clipChildren=false;clipToPadding=false};val v=CoverView(context,book(0));parent.addView(v,FrameLayout.LayoutParams(240,360).apply{leftMargin=80;topMargin=60});parent.measure(View.MeasureSpec.makeMeasureSpec(420,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(550,View.MeasureSpec.EXACTLY));parent.layout(0,0,420,550);val b=Bitmap.createBitmap(420,550,Bitmap.Config.ARGB_8888);parent.draw(Canvas(b));assertTrue("shadow below cover must not be clipped",Color.alpha(b.getPixel(200,430))>0);assertEquals(240,v.width);assertEquals(360,v.height);assertEquals(0f,v.elevation,0f);b.recycle()}
 @Test fun actualGridRecommendationAndDetailInBothThemes(){val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();try{repeat(12){Thread.sleep(40);Shadows.shadowOf(Looper.getMainLooper()).idleFor(80,TimeUnit.MILLISECONDS)}
  fun layout(){repeat(3){a.root.measure(View.MeasureSpec.makeMeasureSpec(a.d(360),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(780),View.MeasureSpec.EXACTLY));a.root.layout(0,0,a.d(360),a.d(780));a.root.viewTreeObserver.dispatchOnPreDraw();a.root.awaitGlowRaster();Shadows.shadowOf(Looper.getMainLooper()).idleFor(32,TimeUnit.MILLISECONDS)}}
  fun shot(name:String){layout();val b=Bitmap.createBitmap(a.root.width,a.root.height,Bitmap.Config.ARGB_8888);a.root.draw(Canvas(b));save(b,name);b.recycle()}
  for(theme in listOf("light","dark")){a.prefs.put("ui",theme).put("motion","reduced");a.render(false,false);a.page.removeAllViews();a.bookGrid((0..3).map{book(it)});shot("$theme-library");val covers=descendants(a.page).filterIsInstance<CoverView>();assertEquals(4,covers.size);covers.forEach{v->assertFalse((v.parent as ViewGroup).clipChildren)}
   a.page.removeAllViews();val host=a.column().apply{clipChildren=false;clipToPadding=false};a.page.addView(host);val titleField=MainActivity::class.java.getDeclaredField("tab").apply{isAccessible=true};titleField.set(a,"foryou");val cat=JSONObject(a.assets.open("recs.json").bufferedReader().readText());RecommendationDeck(a,host,cat,JSONObject().put("onboarded",true).put("coached",true)).start();shot("$theme-recommendations");a.detail(book(0));Shadows.shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS);shot("$theme-detail");a.bookDetailSheet?.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS)
  }
 }finally{controller.pause().stop().destroy()}}
 private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
}
