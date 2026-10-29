package app.polka.nativeapp

import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
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
class SheetInsetsTests {
 private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
 private lateinit var a:MainActivity
 private fun drain(){repeat(10){Thread.sleep(40);Shadows.shadowOf(Looper.getMainLooper()).idleFor(80,TimeUnit.MILLISECONDS)}}
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 @Before fun open(){val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("polka.sqlite");val db=Room.databaseBuilder(c,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build();val lib=Library(db);lib.initialize();lib.savePrefs(lib.prefs().put("motion","reduced"));db.close();controller=Robolectric.buildActivity(MainActivity::class.java).setup();a=controller.get();drain();a.root.measure(exact(360),exact(780));a.root.layout(0,0,a.d(360),a.d(780))}
 @After fun close(){org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()?.dismiss();controller.pause().stop().destroy()}
 private fun exact(dp:Int)=View.MeasureSpec.makeMeasureSpec(a.d(dp),View.MeasureSpec.EXACTLY)
 private fun frame(d:AlertDialog)=all(d.window!!.decorView).filterIsInstance<PolkaSheetFrame>().single()
 private fun layout(d:AlertDialog,w:Int=360,h:Int=780):PolkaSheetFrame {val f=frame(d);repeat(2){f.measure(exact(w),exact(h));f.layout(0,0,a.d(w),a.d(h))};return f}
 private fun insets(bottom:Int,ime:Int=0,left:Int=0,right:Int=0)=WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(),Insets.of(0,a.d(24),0,0)).setInsets(WindowInsets.Type.navigationBars(),Insets.of(a.d(left),0,a.d(right),a.d(bottom))).setInsets(WindowInsets.Type.ime(),Insets.of(0,0,0,a.d(ime))).build()
 private fun shot(f:PolkaSheetFrame,name:String):Bitmap {val b=Bitmap.createBitmap(f.width,f.height,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.MAGENTA);f.draw(c);val dir=File(System.getProperty("qaOutputDir"),"sheets-0.6.7").apply{mkdirs()};File(dir,"$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};return b}
 private fun solidBottom(f:PolkaSheetFrame,name:String){val b=shot(f,name);assertTrue(f.panel.background is OpaqueSurface);assertTrue((f.panel.background as OpaqueSurface).flushBottom);for(x in listOf(0,1,b.width/2,b.width-2,b.width-1)){val pixel=b.getPixel(x,b.height-1);assertEquals("bottom composite remains opaque at x=$x",255,Color.alpha(pixel));assertEquals("opaque panel must completely cover the sentinel at x=$x",(f.panel.background as OpaqueSurface).fillColor,pixel)};b.recycle();assertEquals(f.height,f.panel.bottom)}
 private fun form(tall:Boolean=false):AlertDialog {val box=a.column();box.addView(a.edit("","Название"));repeat(if(tall)24 else 1){box.addView(a.text("Параметр ${it+1}",16f).apply{minHeight=a.d(48)})};return a.dialog().setTall(tall).setTitle("Настройки полки").setView(box).setPositiveButton("Сохранить",null).show()}
 @Test fun gestureAndButtonInsetsDoNotFitTheWindowAboveNavigation(){val d=form();val w=d.window!!;assertEquals(-1,w.attributes.height);assertEquals(-1,w.attributes.width);assertEquals(0,w.attributes.fitInsetsTypes);val attr=d.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating));assertFalse(attr.getBoolean(0,true));attr.recycle();for(bottom in listOf(0,24,48)){val f=frame(d);f.dispatchApplyWindowInsets(insets(bottom));layout(d);assertEquals(a.d(bottom),f.panel.paddingBottom);assertEquals(0,f.paddingBottom);solidBottom(f,"dark-bottom-$bottom")};assertEquals(Color.TRANSPARENT,w.navigationBarColor);assertFalse(w.isNavigationBarContrastEnforced)}
 @Test fun lightSheetAlsoPaintsUnderThreeButtonArea(){a.prefs.put("ui","light");a.render(false,false);val d=form();frame(d).dispatchApplyWindowInsets(insets(48));val f=layout(d);assertTrue(a.light);solidBottom(f,"light-buttons");assertTrue(f.panel.height>a.d(48));assertEquals(0,d.window!!.decorView.paddingBottom)}
 @Test fun keyboardMovesFormControlsButDoesNotCreateTransparentStrip(){val d=form();val f=frame(d);val edit=all(f).filterIsInstance<EditText>().single();edit.requestFocus();f.dispatchApplyWindowInsets(insets(24,300));layout(d);assertTrue(edit.hasFocus());assertEquals(a.d(780),f.height);assertEquals(a.d(300),f.panel.paddingBottom);assertTrue(f.panel.top>=a.d(72));val save=all(f).filterIsInstance<TextView>().first{it.text.toString()=="Сохранить"};val rect=Rect();save.getDrawingRect(rect);f.offsetDescendantRectToMyCoords(save,rect);assertTrue("button remains above IME",rect.bottom<=f.height-a.d(300));solidBottom(f,"keyboard-open");f.dispatchApplyWindowInsets(insets(24));layout(d);assertEquals(a.d(24),f.panel.paddingBottom);assertTrue(edit.hasFocus());solidBottom(f,"keyboard-closed")}
 @Test fun tallSheetIsBoundedAndLastActionCanScrollAboveGestures(){val d=form(true);frame(d).dispatchApplyWindowInsets(insets(28));val f=layout(d);assertEquals(a.d(780-24-48),f.panel.height);val scroll=all(f).filterIsInstance<ScrollView>().single();scroll.isSmoothScrollingEnabled=false;scroll.fullScroll(View.FOCUS_DOWN);layout(d);assertTrue(scroll.scrollY>0);val save=all(f).filterIsInstance<TextView>().first{it.text.toString()=="Сохранить"};val rect=Rect();save.getDrawingRect(rect);f.offsetDescendantRectToMyCoords(save,rect);assertTrue(rect.bottom<=f.height-a.d(28));solidBottom(f,"tall-scrolled")}
 @Test @Config(sdk=[28]) fun olderAndroidAlsoUsesNonFloatingFullWindow(){val d=form();val f=frame(d);f.dispatchApplyWindowInsets(androidx.core.view.WindowInsetsCompat.Builder().setSystemWindowInsets(androidx.core.graphics.Insets.of(0,a.d(24),0,a.d(24))).setStableInsets(androidx.core.graphics.Insets.of(0,a.d(24),0,a.d(24))).build().toWindowInsets()!!);layout(d);assertEquals(-1,d.window!!.attributes.height);assertEquals(a.d(24),f.panel.paddingBottom);val attr=d.context.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsFloating));assertFalse(attr.getBoolean(0,true));attr.recycle();assertTrue(d.window!!.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION!=0);solidBottom(f,"api28-gestures")}
 @Test fun outsideTapClosesOnlyCancelableSheetAndSurfaceDoesNotClickThrough(){val d=form();val f=layout(d);assertTrue(f.panel.isClickable);f.panel.performClick();assertTrue(d.isShowing);f.performClick();assertFalse(d.isShowing);val fixed=a.dialog().setCancelable(false).setTitle("Обработка").setMessage("Подождите").show();val ff=layout(fixed);ff.performClick();assertTrue(fixed.isShowing);assertFalse(all(ff).any{it.contentDescription=="Закрыть"});fixed.dismiss()}
 @Test @Config(qualifiers="w780dp-h360dp-land-xxhdpi") fun landscapeSurfaceCoversSidesAndBottomWhileControlsAvoidCutout(){val d=form();val f=frame(d);f.dispatchApplyWindowInsets(insets(24,left=36,right=24));layout(d,780,360);assertEquals(a.d(36),f.panel.paddingLeft);assertEquals(a.d(24),f.panel.paddingRight);assertEquals(0,f.panel.left);assertEquals(f.width,f.panel.right);solidBottom(f,"landscape")}
 @Test @Config(qualifiers="w800dp-h1000dp-xxhdpi") fun tabletKeepsCenteredBoundedPanelInsideFullWindow(){val d=form();frame(d).dispatchApplyWindowInsets(insets(28));val f=layout(d,800,1000);assertEquals(-1,d.window!!.attributes.width);assertEquals(a.d(540),f.panel.width);assertEquals((f.width-f.panel.width)/2,f.panel.left);assertTrue(f.panel.top>0);assertTrue(f.panel.bottom<f.height-a.d(28));shot(f,"tablet-centered").recycle();f.dispatchApplyWindowInsets(insets(28,400));layout(d,800,1000);assertTrue("tablet panel stays above IME too",f.panel.bottom<=f.height-a.d(400));shot(f,"tablet-keyboard").recycle()}
}
