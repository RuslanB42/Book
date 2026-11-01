package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONObject

/**
 * One shelf: its name and how many books are on it, then the covers standing in a row on a coloured glass plank
 * whose colour comes from the books themselves. The row scrolls sideways; tapping the name opens the whole shelf.
 */
internal class ShelfRow(private val a:MainActivity,private val title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?):LinearLayout(a){
    private val plank=Plank()
    private val coverW=a.d(88);private val coverH=a.d(132)
    private val tints=HashMap<String,Int>()
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40);isClickable=true;setOnClickListener{open()};actions?.let{f->setOnLongClickListener{f();true}};contentDescription="$title, ${MainActivity.countBooks(list.size)}"}
        head.addView(a.text(title,17f,a.fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LayoutParams(0,-2,1f))
        head.addView(a.text(MainActivity.countBooks(list.size),13f,a.muted));head.addView(IconView(a,"next",a.muted).apply{alpha=.7f},LayoutParams(a.d(18),a.d(18)).apply{leftMargin=a.d(4)})
        addView(head,LayoutParams(-1,-2));a.space(this,8)
        val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
        stage.addView(plank,FrameLayout.LayoutParams(-1,(coverH*.5f).toInt()+a.d(14),Gravity.BOTTOM))
        val scroller=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false}
        val row=a.row().apply{gravity=Gravity.BOTTOM;clipChildren=false;clipToPadding=false;setPadding(a.d(20),a.d(6),a.d(20),a.d(14))}
        if(list.isEmpty())row.addView(a.text("Пока пусто — добавьте сюда книги",13.5f,a.muted).apply{setPadding(0,0,0,a.d(18))},LayoutParams(-2,coverH*2/3))
        list.forEachIndexed{i,b->
            val box=FrameLayout(a).apply{clipChildren=false;isClickable=true;contentDescription=b.str("title");setOnClickListener{a.detail(b,this)};a.press(this)}
            fun put(bmp:Bitmap?){box.removeAllViews();box.addView(CoverView(a,b,bmp).apply{softShadow=true},FrameLayout.LayoutParams(-1,-1));if(bmp!=null&&i<4){tints[b.str("id")]=average(bmp);plank.tint(blend())}}
            put(a.cover(b));if(a.cover(b)==null&&b.optJSONObject("cover")!=null)a.requestCover(b){bmp->if(bmp!=null)put(bmp)}
            row.addView(box,LayoutParams(coverW,coverH).apply{if(i>0)leftMargin=a.d(12)})
        }
        scroller.addView(row);stage.addView(scroller,FrameLayout.LayoutParams(-1,-2))
        addView(stage,LayoutParams(-1,-2).apply{leftMargin=-a.d(20);rightMargin=-a.d(20)})
        plank.tint(blend(),false)
    }
    /** Mean colour of a cover, a little more saturated, as the plank's colour. */
    private fun average(b:Bitmap):Int{val s=Bitmap.createScaledBitmap(b,6,9,true);var r=0;var g=0;var bl=0;for(x in 0 until 6)for(y in 0 until 9){val c=s.getPixel(x,y);r+=Color.red(c);g+=Color.green(c);bl+=Color.blue(c)};if(s!==b)s.recycle()
        val hsv=FloatArray(3);Color.RGBToHSV(r/54,g/54,bl/54,hsv);hsv[1]=(hsv[1]*1.35f).coerceIn(.25f,.85f);hsv[2]=hsv[2].coerceIn(.55f,.92f);return Color.HSVToColor(hsv)}
    private fun blend():Int{if(tints.isEmpty())return fallback();var r=0;var g=0;var b=0;tints.values.forEach{r+=Color.red(it);g+=Color.green(it);b+=Color.blue(it)};val n=tints.size;return Color.rgb(r/n,g/n,b/n)}
    private fun fallback():Int{val palette=intArrayOf(0xfff0a742.toInt(),0xff4f8dff.toInt(),0xff3fb58a.toInt(),0xffe0584a.toInt(),0xff9b7bf2.toInt(),0xff3fb7c9.toInt());return palette[Math.floorMod(title.hashCode(),palette.size)]}

    /** The plank: a glassy band in the shelf's colour — lighter on top, a fine light edge, soft shadow line where books stand. */
    private inner class Plank:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var color=fallback()
        private var anim:ValueAnimator?=null
        fun tint(c:Int,animate:Boolean=true){if(c==color)return;anim?.cancel();if(!animate||a.prefs.str("motion")=="reduced"){color=c;invalidate();return}
            val from=color;anim=ValueAnimator.ofObject(android.animation.ArgbEvaluator(),from,c).apply{duration=420;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{color=it.animatedValue as Int;invalidate()};start()}}
        override fun onDraw(cv:Canvas){val w=width.toFloat();val h=height.toFloat();val light=a.light
            val top=(color and 0xffffff) or ((if(light)0x8c else 0x99) shl 24);val bottom=(color and 0xffffff) or ((if(light)0xd9 else 0xcc) shl 24)
            p.shader=LinearGradient(0f,0f,0f,h,top,bottom,Shader.TileMode.CLAMP);cv.drawRect(0f,0f,w,h,p);p.shader=null
            p.shader=LinearGradient(0f,0f,0f,a.d(10).toFloat(),0x40ffffff,0x00ffffff,Shader.TileMode.CLAMP);cv.drawRect(0f,0f,w,a.d(10).toFloat(),p);p.shader=null
            p.color=0x33ffffff;cv.drawRect(0f,0f,w,a.d(1).toFloat(),p)
            // the line the books stand on
            p.shader=LinearGradient(0f,h-a.d(16),0f,h-a.d(12),0x00000000,0x30000000,Shader.TileMode.CLAMP);cv.drawRect(0f,h-a.d(16),w,h-a.d(12),p);p.shader=null}
    }
}

/** «Полки»: every shelf as a row of covers on its own coloured plank (saved order kept), then «Новая полка». */
internal fun MainActivity.shelfRows(){
    val byShelf=books.groupBy{it.str("shelf")};val names=(prefs.optJSONArray("shelves")?.strings().orEmpty()+byShelf.keys.filter{it.isNotBlank()}+REC_SHELF).distinct()
    val manual=names.sortedWith(compareByDescending<String>{byShelf[it]?.size ?: 0}.thenBy{it})
    data class Item(val key:String,val name:String,val list:List<JSONObject>,val open:()->Unit,val actions:(()->Unit)?)
    val all=manual.map{name->Item("s:$name",name,byShelf[name].orEmpty(),{pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;shelf=name;render(false,false)},{shelfActions(name)})}+
        listOf("reading","want","done").map{key->Item("a:$key",MainActivity.autoTitle(key),books.groupBy{Library.status(it)}[key].orEmpty(),{pendingMotion=MotionPose(.4f,x=d(18).toFloat()) to MotionSpec.SHELF;auto=key;render(false,false)},null)}
    val saved=prefs.optJSONArray("shelfOrder")?.strings().orEmpty()
    val ordered=all.sortedBy{saved.indexOf(it.key).let{i->if(i<0)Int.MAX_VALUE else i}}
    ordered.forEachIndexed{i,it->page.addView(ShelfRow(this,it.name,it.list.sortedByDescending{b->maxOf(b.optLong("openedAt"),b.optLong("addedAt"))},it.open,it.actions),LinearLayout.LayoutParams(-1,-2).apply{if(i>0)topMargin=d(26)})}
    space(page,24);page.addView(button("＋  Новая полка"){createShelf()},LinearLayout.LayoutParams(-1,d(48)))
}
