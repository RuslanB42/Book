package app.polka.nativeapp

import android.graphics.*
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «Стопка страниц» and «Прочитано слов» (approved): two small cards side by side on the statistics screen. Both come
 * from the characters read: a page is 1 800 characters, a word 6,4 (as for the reading speed), and a sheet of paper
 * (two pages) is 0,1 mm thick. The pictures of things to compare the stack with are Microsoft Fluent Emoji (MIT
 * licence, assets/heights).
 */
internal object ReadVolume {
    const val CHARS_PER_PAGE=1800.0
    const val CHARS_PER_WORD=6.4
    /** Characters read in [year] (null — all time). */
    fun chars(data:JSONObject,year:Int?):Long{val days=data.optJSONObject("days")?:return 0L;var sum=0L
        val it=days.keys();while(it.hasNext()){val k=it.next();if(year==null||k.startsWith("$year-"))sum+=max(0L,days.optJSONObject(k)?.optLong("chars")?:0L)};return sum}
    fun pages(chars:Long)=(chars/CHARS_PER_PAGE).roundToInt()
    fun words(chars:Long)=(chars/CHARS_PER_WORD).toLong()
    /** The height of [pages] as a stack, in centimetres. */
    fun cm(pages:Int)=pages*.005
    fun height(cm:Double):String=when{cm<100->"${cm.roundToInt()} см";cm<100_000->"${dec(cm/100)} м";else->"${dec(cm/100_000)} км"}
    fun dec(v:Double):String{val r=(v*10).roundToInt()/10.0;return if(r==floor(r))"${r.toLong()}" else String.format(java.util.Locale("ru"),"%.1f",r)}
    fun thousands(n:Long):String=String.format(java.util.Locale("ru"),"%,d",n).replace(' ',' ').replace(',',' ')
    fun pagesWord(n:Int)=if(n%100 in 11..14)"страниц" else when(n%10){1->"страница";2,3,4->"страницы";else->"страниц"}

    class Thing(val key:String,val name:String,val of:String,val cm:Double)
    val things=listOf(Thing("cat","кошка","кошки",25.0),Thing("dog2","собака","собаки",60.0),Thing("person","человек","человека",175.0),
        Thing("elephant","слон","слона",320.0),Thing("giraffe","жираф","жирафа",550.0),Thing("building","девятиэтажка","девятиэтажки",2700.0),
        Thing("liberty","Статуя Свободы","Статуи Свободы",9300.0),Thing("tower","Токийская башня","Токийской башни",33300.0),Thing("fuji","Фудзи","Фудзи",377600.0))
    /** What the stack is compared with: the tallest thing it has outgrown (or the first, while it is smaller). */
    fun compare(cm:Double):Pair<Thing,Boolean>{val passed=things.lastOrNull{cm>=it.cm};return if(passed!=null)passed to true else things.first() to false}

    class Book(val title:String,val words:Long)
    val books=listOf(Book("«Маленький принц»",15_000),Book("«Мастер и Маргарита»",130_000),Book("«Война и мир»",450_000),Book("«Гарри Поттер», все семь книг",1_000_000))
    const val WAR_AND_PEACE=450_000.0

    private val cache=HashMap<String,Bitmap?>()
    fun picture(a:MainActivity,key:String):Bitmap?=cache.getOrPut(key){try{a.assets.open("heights/$key.png").use{BitmapFactory.decodeStream(it)}}catch(e:Exception){null}}
}

/** One of the things the stack is compared with, standing on the bottom of its box, whole and never cropped. */
internal class ThingView(private val a:MainActivity,private val key:String,private val dim:Boolean=false):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val b=ReadVolume.picture(a,key)?:return;val s=min(width/b.width.toFloat(),height/b.height.toFloat())
        val w=b.width*s;val h=b.height*s;p.alpha=if(dim)150 else 255;c.drawBitmap(b,null,RectF((width-w)/2,height-h,(width+w)/2,height.toFloat()),p)}
}

/** The stack of paper in the Fluent manner of the pictures beside it (approved): seen from the front, soft matte
 *  bundles with rounded corners in Fluent's lavender-white, lit from the top, one pressed groove each, a gentle shadow
 *  from every bundle onto the one below and a soft shadow on the floor. It stands on the bottom of its box; its
 *  height is [fill] of the box. */
internal class PaperStack(private val a:MainActivity,private val fill:Float):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val d=a.resources.displayMetrics.density
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val widths=floatArrayOf(1f,.94f,.98f,.93f,.97f,.95f,.99f,.94f)
    private val offs=floatArrayOf(0f,.035f,-.02f,.03f,-.03f,.015f,-.01f,.025f)
    override fun onDraw(c:Canvas){
        val floor=height-4*d;val w=min(width*.82f,height*.62f);val h=(floor*fill.coerceIn(.08f,1f));val S=w/60f;val left=(width-w)/2f;val top=floor-h
        // the soft shadow on the floor
        p.shader=RadialGradient(0f,0f,1f,intArrayOf(0x80000000.toInt(),0x00000000),null,Shader.TileMode.CLAMP)
        val m=Matrix();m.setScale(w*.62f,3.8f*S);m.postTranslate(left+w/2f,floor-1*S);p.shader.setLocalMatrix(m);c.drawOval(left-w*.1f,floor-5*S,left+w*1.1f,floor+3*S,p);p.shader=null
        val n=max(3,(h/(13*d)).roundToInt());val bh=h/n
        for(i in 0 until n){val ww=w*widths[i%8];val x=left+(w-ww)/2f+offs[i%8]*w*.6f;val y=floor-(i+1)*bh;val r=min(bh*.22f,4*S);val bottom=y+bh-.6f*S
            if(i>0){p.shader=LinearGradient(0f,y+bh-2*S,0f,y+bh+3*S,intArrayOf(0x00000000,0x4d1a1430,0x00000000),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x+S,y+bh-2*S,x+ww-S,y+bh+3*S,2*S,2*S,p)}
            p.shader=LinearGradient(0f,y,0f,bottom,intArrayOf(0xfff6f4fa.toInt(),0xffe6e2ee.toInt(),0xffcdc7da.toInt()),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x,y,x+ww,bottom,r,r,p)
            p.shader=LinearGradient(x,0f,x+ww,0f,intArrayOf(0x59ffffff,0x00ffffff,0x005b4f7a,0x385b4f7a),floatArrayOf(0f,.35f,.8f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x,y,x+ww,bottom,r,r,p);p.shader=null
            p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND
            val gy=y+(bottom-y)*.52f
            if(bh>7*d){p.strokeWidth=1.1f*S;p.color=0xffbdb6cf.toInt();c.drawLine(x+r*.8f,gy,x+ww-r*.8f,gy,p)
                p.strokeWidth=.7f*S;p.color=0xbfffffff.toInt();c.drawLine(x+r*.8f,gy+1.1f*S,x+ww-r*.8f,gy+1.1f*S,p)}
            p.strokeWidth=1f*S;p.color=0xe6ffffff.toInt();c.drawLine(x+r,y+.8f*S,x+ww-r,y+.8f*S,p);p.style=Paint.Style.FILL}
    }
}

/** Volumes of «Война и мир» standing on a shelf (approved): leather spines with gold bands and the title; the last
 *  one fills from the bottom by [fraction], its empty part a dashed outline. */
internal class WarAndPeace(private val a:MainActivity,private val full:Int,private val fraction:Float):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG)
    private val d=a.resources.displayMetrics.density
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val count=full+if(fraction>0f)1 else 0;if(count==0)return
        val gap=5*d;val sw=min(height*.27f,(width-gap*(count-1))/count);val total=sw*count+gap*(count-1);var x=(width-total)/2f;val h=height.toFloat()
        for(i in 0 until count){val f=if(i<full)1f else fraction;spine(c,x,sw,h,f);x+=sw+gap}}
    private fun spine(c:Canvas,x:Float,w:Float,h:Float,f:Float){val r=3*d
        if(f<1f){p.shader=null;p.style=Paint.Style.FILL;p.color=if(a.light)0x08000000 else 0x08ffffff;c.drawRoundRect(x,0f,x+w,h,r,r,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1.5f*d;p.color=if(a.light)0x33000000 else 0x26ffffff;p.pathEffect=DashPathEffect(floatArrayOf(3*d,3*d),0f)
            c.drawRoundRect(x+.75f*d,.75f*d,x+w-.75f*d,h-.75f*d,r,r,p);p.pathEffect=null;p.style=Paint.Style.FILL}
        c.save();c.clipRect(x,h*(1-f),x+w,h)
        p.shader=LinearGradient(x,0f,x+w,0f,intArrayOf(0xff5a1822.toInt(),0xff9b3242.toInt(),0xff8a2a39.toInt(),0xff4a121b.toInt()),floatArrayOf(0f,.35f,.6f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(x,0f,x+w,h,r,r,p);p.shader=null
        p.color=0xffd9b46c.toInt();for(y in floatArrayOf(8*d,h-13*d)){c.drawRect(x+2*d,y,x+w-2*d,y+1.4f*d,p);p.alpha=204;c.drawRect(x+2*d,y+3*d,x+w-2*d,y+3.8f*d,p);p.alpha=255}
        if(f>=1f){t.typeface=PolkaFonts.display(600);t.textSize=w*.33f;t.color=0xffecd9b0.toInt();t.textAlign=Paint.Align.CENTER;t.letterSpacing=.04f
            c.save();c.rotate(-90f,x+w/2f,h/2f);c.drawText("ВОЙНА И МИР",x+w/2f,h/2f-(t.ascent()+t.descent())/2f,t);c.restore()}
        c.restore()}
}

/** The two small cards, side by side. */
internal fun MainActivity.volumeCards(data:JSONObject,year:Int,card:()->LinearLayout,kicker:(String)->View):LinearLayout{
    val row=line().apply{gravity=Gravity.TOP;clipChildren=false;clipToPadding=false}
    val yearChars=ReadVolume.chars(data,year);val pages=ReadVolume.pages(yearChars);val cm=ReadVolume.cm(pages)
    fun bottomTexts(box:LinearLayout,number:String,unit:String,first:String,second:String){
        val num=line().apply{gravity=Gravity.BOTTOM}
        num.addView(text(number,30f,fg,true,serif=true));num.addView(text(unit,16f,fg,true,serif=true),LinearLayout.LayoutParams(-2,-2).apply{marginStart=d(5);bottomMargin=d(2)})
        box.addView(num,LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(12)})
        box.addView(text(first,13f,fg).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(6)})
        box.addView(text(second,13f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(2)})}
    fun shelf()=View(this).apply{setBackgroundColor(if(light)0x14000000 else 0x14ffffff)}
    // the stack
    val s=card();s.addView(kicker("Стопка страниц"))
    val (thing,passed)=ReadVolume.compare(cm)
    val pic=FrameLayout(this);val objH=d(62)
    val stackFill=(62f/84f*(cm/thing.cm).toFloat()).coerceIn(.12f,1f)
    val pair=line().apply{gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL}
    pair.addView(PaperStack(this,stackFill),LinearLayout.LayoutParams(d(46),d(88)))
    pair.addView(ThingView(this,thing.key),LinearLayout.LayoutParams(d(66),objH).apply{marginStart=d(8)})
    pic.addView(pair,FrameLayout.LayoutParams(-1,d(88),Gravity.BOTTOM))
    s.addView(pic,LinearLayout.LayoutParams(-1,d(92)).apply{topMargin=d(10)});s.addView(shelf(),LinearLayout.LayoutParams(-1,1))
    val h=ReadVolume.height(cm);val num=h.substringBeforeLast(' ');val unit=h.substringAfterLast(' ')
    bottomTexts(s,num,unit,"${ReadVolume.thousands(pages.toLong())} ${ReadVolume.pagesWord(pages)}",if(passed)"выше ${thing.of}" else "до ${thing.of} ${ReadVolume.height(thing.cm-cm)}")
    s.isClickable=true;s.contentDescription="Стопка страниц: $h, $pages ${ReadVolume.pagesWord(pages)}";s.setOnClickListener{stackSheet(data,year)};press(s)
    // the words
    val w=card();w.addView(kicker("Прочитано слов"))
    val words=ReadVolume.words(yearChars);val wp=words/ReadVolume.WAR_AND_PEACE
    val full=min(floor(wp).toInt(),5);val part=if(full>=5)0f else (wp-floor(wp)).toFloat()
    w.addView(WarAndPeace(this,full,if(full==0&&part<.06f)max(part,.06f) else part),LinearLayout.LayoutParams(-1,d(92)).apply{topMargin=d(10)});w.addView(shelf(),LinearLayout.LayoutParams(-1,1))
    val (wn,wu)=when{words>=1_000_000->ReadVolume.dec(words/1_000_000.0) to "млн";words>=1000->"${words/1000}" to "тыс.";else->"$words" to "слов"}
    bottomTexts(w,wn,wu,if(wp>=.1)"${ReadVolume.dec(wp)} «Войны и мира»" else "${ReadVolume.dec(words/15_000.0)} «Маленького принца»","за этот год")
    w.isClickable=true;w.contentDescription="Прочитано слов за год: $words";w.setOnClickListener{wordsSheet(data,year)};press(w)
    row.addView(s,LinearLayout.LayoutParams(0,-1,1f));row.addView(w,LinearLayout.LayoutParams(0,-1,1f).apply{marginStart=d(12)})
    return row
}

/** «Стопка страниц» opened: the stack and its thing large, the pages this year and all time, and the ladder of things
 *  — outgrown ones ticked, the next one with how far there is still to grow. */
internal fun MainActivity.stackSheet(data:JSONObject,year:Int){
    val chars=ReadVolume.chars(data,year);val pages=ReadVolume.pages(chars);val cm=ReadVolume.cm(pages);val all=ReadVolume.cm(ReadVolume.pages(ReadVolume.chars(data,null)))
    val box=column();val (thing,_)=ReadVolume.compare(cm)
    val head=line().apply{gravity=Gravity.BOTTOM}
    head.addView(PaperStack(this,(92f/120f*(cm/thing.cm).toFloat()).coerceIn(.12f,1f)),LinearLayout.LayoutParams(d(62),d(124)))
    head.addView(ThingView(this,thing.key),LinearLayout.LayoutParams(d(96),d(92)).apply{marginStart=d(8)})
    val t=column();val h=ReadVolume.height(cm)
    t.addView(text("СТОПКА СТРАНИЦ",11.5f,muted,true).apply{letterSpacing=.06f})
    val num=line().apply{gravity=Gravity.BOTTOM};num.addView(text(h.substringBeforeLast(' '),46f,fg,true,serif=true));num.addView(text(h.substringAfterLast(' '),22f,fg,true,serif=true),LinearLayout.LayoutParams(-2,-2).apply{marginStart=d(6);bottomMargin=d(4)})
    t.addView(num,LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(6)})
    t.addView(text("${ReadVolume.thousands(pages.toLong())} ${ReadVolume.pagesWord(pages)} за $year год",14f,fg),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(6)})
    t.addView(text("за всё время ${ReadVolume.height(all)}",13.5f,muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(2)})
    head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=d(14)})
    box.addView(head)
    box.addView(text("Выше кого вырастет стопка",13f,muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(22);bottomMargin=d(4)})
    val next=ReadVolume.things.firstOrNull{cm<it.cm}
    ReadVolume.things.forEachIndexed{i,th->
        if(i>0)box.addView(View(this).apply{setBackgroundColor(if(light)0x0d000000 else 0x0dffffff)},LinearLayout.LayoutParams(-1,1))
        val done=cm>=th.cm;val isNext=th===next
        val row=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(60);alpha=if(done||isNext)1f else .6f}
        row.addView(ThingView(this,th.key),LinearLayout.LayoutParams(d(52),d(42)).apply{marginEnd=d(14)})
        val mid=column();mid.addView(text(th.name,15f,fg))
        if(isNext){val prev=ReadVolume.things.lastOrNull{it.cm<=cm}?.cm?:0.0;mid.addView(GoalTrackPlain(this,((cm-prev)/(th.cm-prev)).toFloat()),LinearLayout.LayoutParams(d(150),d(4)).apply{topMargin=d(8)})}
        row.addView(mid,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text((if(done)"✓ " else "")+ReadVolume.height(th.cm),14f,if(done)(if(light)0xffa8792c.toInt() else 0xffe8c58a.toInt()) else muted))
        box.addView(row,LinearLayout.LayoutParams(-1,-2))}
    box.addView(text("Страница — 1 800 знаков, лист — 0,1 мм.",12f,muted).apply{alpha=.7f},LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(14)})
    dialog().setView(box).show()
}

/** «Прочитано слов» opened: the exact number, all time, the volumes, and what that is in well-known books. */
internal fun MainActivity.wordsSheet(data:JSONObject,year:Int){
    val words=ReadVolume.words(ReadVolume.chars(data,year));val all=ReadVolume.words(ReadVolume.chars(data,null));val wp=words/ReadVolume.WAR_AND_PEACE
    val box=column()
    val head=line().apply{gravity=Gravity.BOTTOM}
    val full=min(floor(wp).toInt(),5);val part=if(full>=5)0f else (wp-floor(wp)).toFloat()
    val count=full+if(part>0f)1 else 0
    head.addView(WarAndPeace(this,full,if(full==0&&part<.06f)max(part,.06f) else part),LinearLayout.LayoutParams(d(max(1,count)*41),d(128)))
    val t=column()
    t.addView(text("ПРОЧИТАНО СЛОВ",11.5f,muted,true).apply{letterSpacing=.06f})
    t.addView(text(ReadVolume.thousands(words),38f,fg,true,serif=true),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(6)})
    t.addView(text("за $year год",14f,fg),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(6)})
    t.addView(text("за всё время "+(if(all>=1_000_000)"${ReadVolume.dec(all/1_000_000.0)} млн" else ReadVolume.thousands(all)),13.5f,muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=d(2)})
    head.addView(t,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=d(16)})
    box.addView(head)
    box.addView(text("Это как",13f,muted,true),LinearLayout.LayoutParams(-1,-2).apply{topMargin=d(24);bottomMargin=d(4)})
    ReadVolume.books.forEachIndexed{i,b->
        if(i>0)box.addView(View(this).apply{setBackgroundColor(if(light)0x0d000000 else 0x0dffffff)},LinearLayout.LayoutParams(-1,1))
        val row=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(52)}
        row.addView(text(b.title,16f,fg,serif=true),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(text("×"+ReadVolume.dec(words.toDouble()/b.words),18f,if(light)0xffb8364c.toInt() else 0xffe06b7d.toInt(),true,serif=true))
        box.addView(row,LinearLayout.LayoutParams(-1,-2))}
    dialog().setView(box).show()
}

/** A thin rounded track filled to [fraction] in the stack's paper gold. */
internal class GoalTrackPlain(private val a:MainActivity,private val fraction:Float):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,width.toFloat(),h,r,r,p)
        val w=max(h,width*fraction.coerceIn(0f,1f));p.color=if(a.light)0xffc9a25a.toInt() else 0xffe8c58a.toInt();c.drawRoundRect(0f,0f,w,h,r,r,p)}
}
