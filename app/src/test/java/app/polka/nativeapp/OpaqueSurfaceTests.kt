package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpaqueSurfaceTests {
 @Test fun everyMaterialStateIsOpaqueAndIndependentOfTheBackdrop(){
  val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
  try{for(light in listOf(false,true))for(selected in listOf(false,true))for(sheet in listOf(false,true))for(pressed in listOf(false,true)){
   val material=OpaqueSurface(a,12f,selected,sheet).apply{themeLight=light;setBounds(0,0,180,90);state=if(pressed)intArrayOf(android.R.attr.state_pressed)else intArrayOf();refresh()}
   var previous:Int?=null
   for(background in listOf(Color.RED,Color.BLUE,Color.MAGENTA,Color.TRANSPARENT)){
    val b=Bitmap.createBitmap(180,90,Bitmap.Config.ARGB_8888);b.eraseColor(background);material.draw(Canvas(b));val pixel=b.getPixel(90,45)
    assertEquals(255,Color.alpha(pixel));assertEquals(material.fillColor,pixel);previous?.let{assertEquals(it.toInt(),pixel)};previous=pixel;b.recycle()
   }
  }}finally{controller.pause().stop().destroy()}
 }
 @Test @Config(sdk=[28]) fun oldAndroidUsesTheSameSolidMaterialWithoutAnyWarmUp(){
  val controller=Robolectric.buildActivity(MainActivity::class.java).setup();val a=controller.get()
  try{val host=FrameLayout(a);val material=a.controlSurface(host,22f,sheet=true).apply{flushBottom=true;setBounds(0,0,200,160);refresh()};val b=Bitmap.createBitmap(200,160,Bitmap.Config.ARGB_8888);b.eraseColor(Color.MAGENTA);material.draw(Canvas(b));for(x in listOf(0,1,100,198,199))assertEquals(material.fillColor,b.getPixel(x,159));b.recycle()}finally{controller.pause().stop().destroy()}
 }
}
