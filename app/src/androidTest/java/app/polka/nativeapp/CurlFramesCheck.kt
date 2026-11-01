package app.polka.nativeapp

import android.graphics.*
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Renders the page curl at several stages (corner grab and mid-edge grab) into one picture. */
@RunWith(AndroidJUnit4::class)
class CurlFramesCheck {
    private fun page(w:Int,h:Int,title:String,paper:Int,ink:Int):Bitmap{val b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(paper);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=ink;textSize=15f;typeface=Typeface.SERIF}
        p.textSize=22f;p.isFakeBoldText=true;c.drawText(title,24f,60f,p);p.isFakeBoldText=false;p.textSize=14f
        val words="Вечером над морем зажёгся маяк и его свет был виден далеко от берега мы шли вдоль набережной где пахло смолой".split(" ")
        var y=100f;var i=0;while(y<h-40){var x=24f;while(x<w-60){val wd=words[i++%words.size];c.drawText(wd,x,y,p);x+=p.measureText(wd+" ")};y+=22f};return b}
    @Test fun frames(){
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val w=300;val h=500
        for((theme,paper,ink) in listOf(Triple("light",0xfff6f1e7.toInt(),0xff2a2622.toInt()),Triple("dark",0xff18181a.toInt(),0xffdedcd6.toInt()))){
            val src=page(w,h,"Глава первая",paper,ink);val dst=page(w,h,"Следующая страница",paper,ink)
            val stages=listOf(.12f,.3f,.5f,.7f,.88f)
            val out=Bitmap.createBitmap((w+10)*stages.size,(h+10)*2,Bitmap.Config.ARGB_8888);val oc=Canvas(out);oc.drawColor(0xff0aa6d8.toInt())
            InstrumentationRegistry.getInstrumentation().runOnMainSync{
                listOf(h*.97f,h*.5f).forEachIndexed{row,grab->stages.forEachIndexed{k,p->
                    val v=PageCurlView(ctx,src,dst,1,paper);v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h)
                    v.pose(p,grab-(if(row==0)30f else 0f)*p,grab)
                    val f=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(Canvas(f));oc.drawBitmap(f,(k*(w+10)).toFloat(),(row*(h+10)).toFloat(),null);f.recycle()}}}
            val dir=File(ctx.filesDir,"qa").apply{mkdirs()};File(dir,"curl-$theme.png").outputStream().use{out.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
}
