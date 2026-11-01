package app.polka.nativeapp

import android.graphics.*
import android.view.*
import android.widget.*
import org.json.JSONObject

/**
 * One shelf: its name and how many books are on it, then the covers standing in a row behind our frosted glass
 * shelf with screws (the same glass as the shelf tiles) — the glass covers the lower part of the books and stays
 * put while the covers scroll sideways behind it. Tapping the name opens the whole shelf.
 */
internal class ShelfRow(private val a:MainActivity,private val title:String,private val list:List<JSONObject>,private val open:()->Unit,private val actions:(()->Unit)?):LinearLayout(a){
    private val coverW=a.d(88);private val coverH=a.d(132)
    private val scroller=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false}
    private val row=a.row().apply{gravity=Gravity.BOTTOM;clipChildren=false;clipToPadding=false;setPadding(a.d(20),a.d(6),a.d(20),a.d(10))}
    private val glass=Glass()
    init{
        orientation=VERTICAL;clipChildren=false;clipToPadding=false
        val head=a.row().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(40);isClickable=true;setOnClickListener{open()};actions?.let{f->setOnLongClickListener{f();true}};contentDescription="$title, ${MainActivity.countBooks(list.size)}"}
        head.addView(a.text(title,17f,a.fg,true,serif=true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LayoutParams(0,-2,1f))
        head.addView(a.text(MainActivity.countBooks(list.size),13f,a.muted));head.addView(IconView(a,"next",a.muted).apply{alpha=.7f},LayoutParams(a.d(18),a.d(18)).apply{leftMargin=a.d(4)})
        addView(head,LayoutParams(-1,-2));a.space(this,6)
        val stage=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
        if(list.isEmpty())row.addView(a.text("Пока пусто — добавьте сюда книги",13.5f,a.muted).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(4),0,0,a.d(10))},LayoutParams(-2,coverH*3/4))
        list.forEachIndexed{i,b->
            val box=FrameLayout(a).apply{clipChildren=false;isClickable=true;contentDescription=b.str("title");setOnClickListener{a.detail(b,this)};a.press(this)}
            fun put(bmp:Bitmap?){box.removeAllViews();box.addView(CoverView(a,b,bmp).apply{softShadow=true},FrameLayout.LayoutParams(-1,-1));glass.refresh()}
            put(a.cover(b));if(a.cover(b)==null&&b.optJSONObject("cover")!=null)a.requestCover(b){bmp->if(bmp!=null)put(bmp)}
            row.addView(box,LayoutParams(coverW,if(list.isEmpty())-2 else coverH).apply{if(i>0)leftMargin=a.d(12)})
        }
        scroller.addView(row);stage.addView(scroller,FrameLayout.LayoutParams(-1,-2))
        scroller.setOnScrollChangeListener{_,_,_,_,_->glass.scrolled()}
        // The glass view is larger than the glass by its shadow; it never takes touches, so the row still scrolls.
        val glassH=(coverH*.42f).toInt()+a.d(8)
        stage.addView(glass,FrameLayout.LayoutParams(-1,glassH+glass.pad*2,Gravity.BOTTOM).apply{leftMargin=a.d(12)-glass.pad;rightMargin=a.d(12)-glass.pad;bottomMargin=-glass.pad})
        addView(stage,LayoutParams(-1,-2).apply{leftMargin=-a.d(20);rightMargin=-a.d(20)})
    }

    /** Our frosted glass shelf (as on the shelf tiles): soft shadow, opaque backing, blurred books behind, hairline edge, four screws. */
    private inner class Glass:View(a){
        val pad=a.d(22)
        private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var frost:Bitmap?=null;private var frostTop=0f;private var generation=0;private var queued=false
        init{isClickable=false;isFocusable=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
        /** Covers changed: re-blur the whole row once (off the UI thread). Scrolling only shifts the ready frost. */
        fun refresh(){if(queued)return;queued=true;post{queued=false;capture()}}
        fun scrolled()=invalidate()
        override fun onTouchEvent(e:MotionEvent)=false
        override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){super.onLayout(changed,l,t,r,b);if(changed)refresh()}
        /** The whole row of covers behind the glass band, drawn at one pixel per dp; blurred on a worker thread. */
        private fun capture(){if(width<1||row.width<1)return;val density=resources.displayMetrics.density
            val gTop=top+pad-(scroller.top+row.top).toFloat();val gH=(height-2*pad).toFloat()
            val tw=(row.width/density).toInt().coerceIn(1,4096);val th=(gH/density).toInt().coerceAtLeast(1)
            val bmp=Bitmap.createBitmap(tw,th,Bitmap.Config.ARGB_8888);val c=Canvas(bmp);c.drawFilter=PaintFlagsDrawFilter(0,Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            c.scale(tw/row.width.toFloat(),th/gH);c.translate(0f,-gTop);row.draw(c)
            val gen=++generation
            Thread{val out=try{ShelfFrost.blur(bmp,3f)}catch(e:Throwable){null}finally{bmp.recycle()}
                post{if(gen!=generation||!isAttachedToWindow){out?.recycle();return@post};frost?.recycle();frost=out;frostTop=gTop;invalidate()}}.start()}
        // Static parts are rendered once per size/theme into bitmaps (soft shadow under, tint/edge/screws over),
        // so a scroll frame only draws three bitmaps — no shadow blur, no software layer.
        private var under:Bitmap?=null;private var over:Bitmap?=null;private var staticKey=""
        private val clip=Path();private val frostPaint=Paint(Paint.FILTER_BITMAP_FLAG)
        private fun statics(){val light=a.light;val key="$width:$height:$light";if(key==staticKey&&under!=null)return;staticKey=key
            under?.recycle();over?.recycle()
            val density=resources.displayMetrics.density;val radius=a.d(9).toFloat()
            val g=RectF(pad.toFloat(),pad.toFloat(),(width-pad).toFloat(),(height-pad).toFloat());val background=if(light)0xfff2f2f0.toInt() else 0xff161617.toInt()
            clip.reset();clip.addRoundRect(g,radius,radius,Path.Direction.CW)
            val m=ColorMatrix().apply{setSaturation(1.12f)};val k=if(light)1.05f else .82f;m.postConcat(ColorMatrix(floatArrayOf(k,0f,0f,0f,0f,0f,k,0f,0f,0f,0f,0f,k,0f,0f,0f,0f,0f,1f,0f)));frostPaint.colorFilter=ColorMatrixColorFilter(m)
            under=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                p.color=background;p.setShadowLayer(if(light)22*density else 26*density,0f,if(light)5*density else 7*density,if(light)0x12000000 else 0x40000000);c.drawRoundRect(g,radius,radius,p);p.clearShadowLayer()}
            over=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{bmp->val c=Canvas(bmp)
                c.save();c.clipPath(clip);p.color=if(light)0x66ffffff else 0x0effffff;c.drawRect(g,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=density;p.color=if(light)0xccffffff.toInt() else 0x21ffffff
                val e=(radius-density*.5f).coerceAtLeast(0f);c.drawRoundRect(RectF(g.left+density*.5f,g.top+density*.5f,g.right-density*.5f,g.bottom-density*.5f),e,e,p);p.style=Paint.Style.FILL;c.restore()
                val size=9*density;val inset=density+radius*.55f+size/2
                listOf(g.left+inset to g.top+inset,g.right-inset to g.top+inset,g.left+inset to g.bottom-inset,g.right-inset to g.bottom-inset).forEachIndexed{i,(x,y)->ShelfScrew.draw(c,x,y,size,density,ShelfScrew.turns[i])}}
        }
        override fun onDraw(c:Canvas){if(width<1||height<1)return;statics()
            under?.let{c.drawBitmap(it,0f,0f,null)}
            frost?.let{f->c.save();c.clipPath(clip);val dx=(scroller.left+row.left-scroller.scrollX-left).toFloat();c.drawBitmap(f,null,RectF(dx,pad.toFloat(),dx+row.width,(height-pad).toFloat()),frostPaint);c.restore()}
            over?.let{c.drawBitmap(it,0f,0f,null)}
        }
        override fun onDetachedFromWindow(){generation++;frost?.recycle();frost=null;under?.recycle();over?.recycle();under=null;over=null;staticKey="";super.onDetachedFromWindow()}
        override fun onAttachedToWindow(){super.onAttachedToWindow();refresh()}
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
