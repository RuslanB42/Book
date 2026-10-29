package app.polka.nativeapp

import android.graphics.*
import android.os.Looper
import android.view.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfGlassTests {
 @Test fun gaussianHasSameHorizontalAndVerticalProfile(){val src=Bitmap.createBitmap(41,41,Bitmap.Config.ARGB_8888);src.setPixel(20,20,Color.WHITE);val result=ShelfFrost.blur(src,3f);for(y in 0..40)for(x in 0..40)assertEquals("$x,$y",Color.alpha(result.getPixel(x,y)),Color.alpha(result.getPixel(y,x)));assertTrue(Color.alpha(result.getPixel(20,20))>0);src.recycle();result.recycle()}
 @Test fun transparentBlurKeepsCoverColourWithoutOpaqueBackground(){val src=Bitmap.createBitmap(41,41,Bitmap.Config.ARGB_8888);Canvas(src).drawRect(15f,15f,26f,26f,Paint().apply{color=Color.RED});val result=ShelfFrost.blur(src,3f);assertEquals(Color.TRANSPARENT,result.getPixel(0,0));val edge=result.getPixel(12,20);assertTrue(Color.alpha(edge) in 1..254);assertTrue(Color.red(edge)>=250);assertEquals(0,Color.green(edge));assertEquals(0,Color.blue(edge));src.recycle();result.recycle()}
 @Test fun allFourScrewsHaveIndependentOpaqueMetalPaint(){val b=Bitmap.createBitmap(360,72,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(0xff242426.toInt());val brightness=ShelfScrew.turns.mapIndexed{i,turn->ShelfScrew.draw(c,45f+i*90,36f,24f,3f,turn);var sum=0L;for(y in 24..47)for(x in 33+i*90..56+i*90){val p=b.getPixel(x,y);sum+=Color.red(p)+Color.green(p)+Color.blue(p)};sum.toDouble()};assertTrue("first bolt must not inherit glass alpha: $brightness",brightness.minOrNull()!!>brightness.maxOrNull()!!*.85);b.recycle()}
 @Test fun firstDrawHasCompleteFrostAndReturningShelfUsesCachedIdenticalRaster(){
  val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
  try{val b=JSONObject().put("id","first-frame").put("title","Тихие волны").put("authors",JSONArray().put("Полка"))
   for(light in listOf(false,true)){
    fun view()=ShelfArt(a,listOf(b to null),light).also{a.root.addView(it,android.widget.FrameLayout.LayoutParams(a.d(150),a.d(141)));it.measure(View.MeasureSpec.makeMeasureSpec(a.d(150),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(141),View.MeasureSpec.EXACTLY));it.layout(0,0,a.d(150),a.d(141))}
    fun capture(v:View)=Bitmap.createBitmap(a.d(150),a.d(141),Bitmap.Config.ARGB_8888).also{v.draw(Canvas(it))}
    val v=view();val first=capture(v);val frost=ShelfArt::class.java.getDeclaredField("frost").apply{isAccessible=true};assertNotNull("first draw must not contain an empty glass front",frost.get(v));val built=ShelfFrost.builds
    repeat(12){Thread.sleep(20);Shadows.shadowOf(Looper.getMainLooper()).idleFor(25,TimeUnit.MILLISECONDS)};val later=capture(v);assertTrue(first.sameAs(later));a.root.removeView(v)
    val returned=view();val again=capture(returned);assertTrue(first.sameAs(again));assertEquals(built,ShelfFrost.builds);a.root.removeView(returned);first.recycle();later.recycle();again.recycle()
   }
  }finally{controller.pause().stop().destroy()}
 }
 @Test fun glassSnapshotsCoverEmptyOneAndThreeInBothThemes(){val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get();try{repeat(12){Thread.sleep(50);Shadows.shadowOf(Looper.getMainLooper()).idleFor(100,TimeUnit.MILLISECONDS)};
  val books=listOf("Смотритель маяка","Как устроена Полка","Третья книга").mapIndexed{i,title->JSONObject().put("id","glass-$i").put("title",title).put("authors",JSONArray().put("Полка"))}
  val dir=File(System.getProperty("qaOutputDir"),"surfaces-0.6.25/shelves").apply{mkdirs()}
  for(light in listOf(false,true))for(n in listOf(0,1,3)){
   val art=ShelfArt(a,books.take(n).map{it to null},light);a.root.addView(art,android.widget.FrameLayout.LayoutParams(a.d(150),a.d(141)));art.measure(View.MeasureSpec.makeMeasureSpec(a.d(150),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(a.d(141),View.MeasureSpec.EXACTLY));art.layout(0,0,a.d(150),a.d(141));val b=Bitmap.createBitmap(a.d(150),a.d(141),Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(if(light)0xfff2f2f0.toInt()else 0xff161617.toInt());art.draw(c)
   repeat(16){Thread.sleep(50);Shadows.shadowOf(Looper.getMainLooper()).idleFor(50,TimeUnit.MILLISECONDS)}
   val field=ShelfArt::class.java.getDeclaredField("frost").apply{isAccessible=true};assertNotNull("background frost committed",field.get(art));c.drawColor(if(light)0xfff2f2f0.toInt()else 0xff161617.toInt());art.draw(c);if(n>0){val centre=b.getPixel(a.d(75),a.d(100));assertTrue("cover must remain visible through glass: light=$light rgb=$centre",Color.green(centre) in (if(light)140..160 else 65..85));val border=b.getPixel(a.d(75),a.d(65));assertTrue("border must not inherit cover colour",kotlin.math.abs(Color.red(border)-Color.green(border))<=2)};File(dir,"${if(light)"light" else "dark"}-$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};assertTrue(Color.alpha(b.getPixel(a.d(75),a.d(100)))>250);a.root.removeView(art);assertNull(field.get(art));b.recycle()
  }
 }finally{controller.pause().stop().destroy()}}
}
