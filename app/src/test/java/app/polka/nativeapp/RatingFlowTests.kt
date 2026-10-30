package app.polka.nativeapp

import android.content.Context
import android.os.Looper
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RatingFlowTests {
 private lateinit var controller:org.robolectric.android.controller.ActivityController<MainActivity>
 private lateinit var a:MainActivity
 private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
 private fun drain(){repeat(8){Thread.sleep(30);Shadows.shadowOf(Looper.getMainLooper()).idleFor(70,TimeUnit.MILLISECONDS)}}
 private val book get()=JSONObject().put("id","r1").put("title","Тихий берег").put("authors",JSONArray().put("Автор")).put("noFile",true)
 @Before fun open(){val c=ApplicationProvider.getApplicationContext<Context>();c.deleteDatabase("polka.sqlite");val db=Room.databaseBuilder(c,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build();val lib=Library(db);lib.initialize();lib.savePrefs(lib.prefs().put("motion","reduced"));lib.updateMarker();db.close();controller=Robolectric.buildActivity(MainActivity::class.java).setup();a=controller.get();drain()}
 @After fun close(){controller.pause().stop().destroy()}

 @Test fun starSavesRatingAndPromptClosesItself(){
  var got=0;val prompt=RatingPrompt(a,book){got=it};a.ratingPrompt=prompt;prompt.show();assertTrue(prompt.isShowing);assertNotNull(prompt.parent)
  val texts=all(prompt).filterIsInstance<TextView>().map{it.text.toString()};assertTrue(texts.contains("Книга дочитана"));assertTrue(texts.contains("Не сейчас"))
  assertTrue("rating only, never a text field",all(prompt).none{it is EditText})
  val stars=all(prompt).filterIsInstance<NativeRating>().single();stars.getChildAt(3).performClick();assertEquals(4,got)
  Shadows.shadowOf(Looper.getMainLooper()).idleFor(2,TimeUnit.SECONDS);assertNull(prompt.parent);assertNull(a.ratingPrompt)
 }
 @Test fun backAndLaterDismissWithoutRating(){
  var got=0;val prompt=RatingPrompt(a,book){got=it};a.ratingPrompt=prompt;prompt.show();a.onBackPressed();assertNull(prompt.parent);assertEquals(0,got)
  val second=RatingPrompt(a,book){got=it};a.ratingPrompt=second;second.show();all(second).filterIsInstance<TextView>().first{it.text.toString()=="Не сейчас"}.performClick();assertNull(second.parent);assertEquals(0,got)
 }
 @Test fun bookMenuHasRatingButNoReview(){
  a.bookActions(book);val labels=all(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().window!!.decorView).filterIsInstance<TextView>().map{it.text.toString()}
  assertTrue(labels.contains("Оценить"));assertFalse(labels.contains("Мысли о книге"))
 }
}
