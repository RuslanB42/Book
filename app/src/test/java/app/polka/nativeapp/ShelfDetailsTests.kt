package app.polka.nativeapp
import android.graphics.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfDetailsTests {
 @Test fun allFourScrewsHaveIndependentOpaqueMetalPaint(){val b=Bitmap.createBitmap(360,72,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(0xff242426.toInt());val brightness=ShelfScrew.turns.mapIndexed{i,turn->ShelfScrew.draw(c,45f+i*90,36f,24f,3f,turn);var sum=0L;for(y in 24..47)for(x in 33+i*90..56+i*90){val p=b.getPixel(x,y);sum+=Color.red(p)+Color.green(p)+Color.blue(p)};sum.toDouble()};assertTrue("first bolt must not inherit glass alpha: $brightness",brightness.minOrNull()!!>brightness.maxOrNull()!!*.85);b.recycle()}
}
