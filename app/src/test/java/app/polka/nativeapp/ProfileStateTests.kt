package app.polka.nativeapp
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE,qualifiers="w390dp-h844dp-mdpi")
class ProfileStateTests {
    @Test fun recommendationOrderMatchesOriginalJavaScriptForFixedSeed() {
        val fixture=JSONObject(java.io.File(System.getProperty("recReferenceFixture")).readText()); val cat=JSONObject(ApplicationProvider.getApplicationContext<android.content.Context>().assets.open("recs.json").bufferedReader().readText())
        fixture.getJSONArray("cases").objects().forEachIndexed { i,test -> val actual=RecommendationEngine.rank(cat,test.getJSONArray("books").objects(),emptySet(),test.getJSONObject("state"),fixture.getInt("seed")).map { it.str("rec") }; assertEquals("reference case $i",test.getJSONArray("expected").strings(),actual) }
    }
    @Test fun originalStatisticsThresholdsAndRounding() {
        assertEquals(0,readingSpeed(1000,59999)); assertEquals(0,readingSpeed(300,60000)); assertEquals(100,readingSpeed(640,60000))
        assertEquals("<1 мин",duration(1000)); assertEquals("1 мин",duration(30000)); assertEquals("1 ч",duration(3600000)); assertEquals("1 ч 1 мин",duration(3660000))
    }
    @Test fun rhythmIsEightWeekCalendarAverageNotAllTimeSum() {
        val today=LocalDate.of(2026,10,26); val days=JSONObject().put(today.toString(),JSONObject().put("ms",8*60000)).put(today.minusDays(56).toString(),JSONObject().put("ms",999*60000))
        val model=ProfileData(JSONObject().put("days",days),today); assertEquals(1.0,model.rhythm()[0],.0001); assertEquals(0.0,model.rhythm()[1],.0001)
    }
    @Test fun historicalGoalsAndBestStreakSurviveGoalChange() {
        val today=LocalDate.of(2026,10,26); val days=JSONObject(); repeat(3) { days.put(today.minusDays((it+1).toLong()).toString(),JSONObject().put("ms",60000).put("met",true)) }
        val model=ProfileData(JSONObject().put("goal",60).put("days",days).put("best",8),today); assertEquals(3,model.streak); assertEquals(8,model.best); assertFalse(model.met(today))
    }
    @Test fun replacingStateRemovesDeletedCoverAndKeepsIdentity() { val current=JSONObject().put("id","x").put("cover","old"); replaceJson(current,JSONObject().put("id","x").put("rating",5)); assertFalse(current.has("cover")); assertEquals(5,current.optInt("rating")) }
    @Test fun openBookDetailUpdatesWithoutReopeningAndObserverDetaches() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>(); context.deleteDatabase("polka.sqlite")
        val db=Room.databaseBuilder(context,LibraryDb::class.java,"polka.sqlite").allowMainThreadQueries().build(); val library=Library(db); library.initialize()
        library.save(JSONObject().put("id","live").put("title","До изменения").put("authors",JSONArray()).put("shelf","Проза").put("notes",JSONArray()).put("progress",JSONObject().put("pct",0))); library.savePrefs(library.prefs().put("motion","reduced")); library.updateMarker(); db.close()
        val controller=Robolectric.buildActivity(MainActivity::class.java).setup(); val a=controller.get()
        fun drain() { repeat(15) { Thread.sleep(60); Shadows.shadowOf(Looper.getMainLooper()).idle() } }
        fun texts(v:View):List<String> { val values=mutableListOf<String>(); if(v is TextView) values.add(v.text.toString()); if(v is ViewGroup) for(i in 0 until v.childCount) values.addAll(texts(v.getChildAt(i))); return values }
        drain(); a.detail(a.books.first()); val dialog=a.bookDetailSheet!!; val fresh=a.books.first().copyJson().put("title","После изменения").put("shelf","Новая полка").put("notes",JSONArray().put(JSONObject().put("scope","book").put("text","Новые мысли")))
        a.saveBook(fresh); drain(); val labels=texts(dialog); assertTrue(dialog.isShowing); assertTrue(labels.contains("После изменения")); assertTrue(labels.contains("Новая полка")); assertFalse("text reviews are no longer shown",labels.contains("Новые мысли")); assertFalse(labels.contains("До изменения"))
        dialog.finish(); a.showProfile(JSONObject().put("goal",10),7,false); val overlay=a.profileOverlay; a.render(true,false); assertSame(overlay,a.profileOverlay); assertNotNull(overlay?.parent)
        a.prefs.put("ui","light"); a.refreshProfile(); assertNotSame(overlay,a.profileOverlay); assertNotNull(a.profileOverlay?.parent)
        val profile=a.profileOverlay!!; profile.measure(View.MeasureSpec.makeMeasureSpec(390,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(844,View.MeasureSpec.EXACTLY)); profile.layout(0,0,390,844)
        fun findLabel(v:View,label:String):TextView? { if(v is TextView && v.text.toString()==label) return v; if(v is ViewGroup) for(i in 0 until v.childCount) findLabel(v.getChildAt(i),label)?.let { return it }; return null }
        listOf("Лучшая серия","Рекорд за день","Дочитано книг","Библиотека","Время по книгам").forEach { label -> assertNull("Unrequested metric removed: $label",findLabel(profile,label)) }
        listOf("Прочитано книг","Время чтения","Дни подряд","Любимый автор","Скорость чтения").forEach { label -> assertNotNull(label,findLabel(profile,label)) }
        controller.pause().stop().destroy()
    }
}
