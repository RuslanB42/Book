package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.view.*
import android.widget.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandingTests {
 private fun shot(name:String,b:Bitmap){val dir=File(System.getProperty("qaOutputDir"),"branding-0.6.26").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 private fun run(block:(MainActivity)->Unit){val controller=Robolectric.buildActivity(MainActivity::class.java).setup();try{block(controller.get())}finally{controller.pause().stop().destroy()}}
 @Test fun iconIsGraphiteAndLauncherSafeWithMonochrome(){run{a->val icon=a.getDrawable(a.applicationInfo.icon) as AdaptiveIconDrawable;assertNotNull(icon.monochrome);val b=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);icon.setBounds(0,0,512,512);icon.draw(Canvas(b));assertEquals(0,Color.alpha(b.getPixel(0,0)));val center=(-24..24 step 4).flatMap{dy->(-24..24 step 4).map{dx->b.getPixel(256+dx,256+dy)}}.maxOf{Color.red(it)};assertTrue("glyph must light up the centre area",center>70);shot("launcher-dark",b);b.recycle()}}
 @Test fun loadingScreenUsesTheExactIconAndNoWordmark(){run{a->val v=a.bootLogo() as FrameLayout;assertEquals(1,v.childCount);assertTrue(v.getChildAt(0) is ImageView);v.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY));v.layout(0,0,390,844);val b=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));assertEquals(a.getColor(R.color.launcher_background),b.getPixel(0,0));shot("boot-icon",b);b.recycle()}}
 @Test @Config(sdk=[28]) fun oldAndroidWindowBackgroundAlsoShowsSymbol(){run{a->val drawable=a.getDrawable(R.drawable.launch_background)!!;val b=Bitmap.createBitmap(390,844,Bitmap.Config.ARGB_8888);drawable.setBounds(0,0,390,844);drawable.draw(Canvas(b));shot("api28-starting-window",b);assertTrue("symbol must be visible over the window background",(-60..60 step 6).flatMap{dy->(-60..60 step 6).map{dx->b.getPixel(195+dx,422+dy)}}.any{it!=b.getPixel(0,0)});b.recycle()}}
}
