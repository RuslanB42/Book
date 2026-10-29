package app.polka.nativeapp
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar
/** Source range geometry; SeekBar still owns touch, keyboard and accessibility. */
internal class ReaderRange(private val a:MainActivity):SeekBar(a){
 private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
 var foreground=a.fg
 init{background=null;setPadding(a.d(12),0,a.d(12),0);thumb=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.WHITE);setSize(a.d(24),a.d(24))};splitTrack=false}
 override fun onDraw(c:Canvas){val y=height/2f;val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val x=left+(right-left)*progress/max.coerceAtLeast(1);ink.color=if(a.light)0xffd8d8d6.toInt()else 0xff38383a.toInt();c.drawRoundRect(left,y-a.d(2),right,y+a.d(2),a.d(2).toFloat(),a.d(2).toFloat(),ink);ink.color=foreground;c.drawRoundRect(left,y-a.d(2),x,y+a.d(2),a.d(2).toFloat(),a.d(2).toFloat(),ink);ink.color=Color.WHITE;c.drawCircle(x,y,a.d(12).toFloat(),ink)}
}
