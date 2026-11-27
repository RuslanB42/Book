package app.polka.nativeapp

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The pieces both readers' settings pages are built from (approved «Текст А: слоями, как в Apple Books»): one grid,
 * 16 dp at the panel's sides and between blocks, controls 48 dp high, rows 52 dp in cards split by thin lines.
 * [refreshers] are run when a page shows the current values again.
 */
internal class PanelKit(private val a:MainActivity,private val refreshers:MutableList<()->Unit>){
    val muted get()=if(a.light)0xff6e6e73.toInt() else 0xffa4a4a4.toInt()
    val cardColor get()=if(a.light)Color.WHITE else 0xff2a2a2d.toInt()

    fun caption(t:String,top:Int=18)=a.text(t,12f,muted,true).apply{letterSpacing=.06f;setPadding(a.d(4),a.d(top),0,a.d(9))}

    /** A group as one card, its rows split by thin lines; a tappable row lights up under the finger. */
    fun card(rows:List<View>):View{val box=a.column().apply{background=a.shape(cardColor,20)}
        fun lit(top:Boolean,bottom:Boolean)=StateListDrawable().apply{setExitFadeDuration(220)
            val rad=a.d(20).toFloat();val t=if(top)rad else 0f;val b=if(bottom)rad else 0f
            addState(intArrayOf(android.R.attr.state_pressed),GradientDrawable().apply{setColor(if(a.light)0x0c000000 else 0x0fffffff);cornerRadii=floatArrayOf(t,t,t,t,b,b,b,b)})
            addState(intArrayOf(),ColorDrawable(0))}
        rows.forEachIndexed{i,v->if(i>0)box.addView(View(a).apply{setBackgroundColor(if(a.light)0x12000000 else 0x14ffffff)},LinearLayout.LayoutParams(-1,1).apply{leftMargin=a.d(16)})
            if(v.isClickable)v.background=lit(i==0,i==rows.lastIndex)
            box.addView(v,LinearLayout.LayoutParams(-1,if(v is LayoutToggle)a.d(52) else -2))}
        return box}

    /** A row that opens a page: the name, its current value in grey and a chevron. */
    fun row(name:String,open:()->Unit):Pair<LinearLayout,TextView>{
        val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(52);setPadding(a.d(16),0,a.d(14),0);isClickable=true;isFocusable=true;setOnClickListener{open()}}
        r.addView(a.text(name,15.5f,a.fg),LinearLayout.LayoutParams(0,-2,1f))
        val value=a.text("",15f,muted).apply{maxLines=1};r.addView(value,LinearLayout.LayoutParams(-2,-2).apply{rightMargin=a.d(8)})
        r.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        return r to value}

    /** Choices in a card, one row each, the chosen one with a round check; a tap chooses (with a tick). */
    fun checkList(items:List<Pair<String,String>>,current:()->String,pick:(String)->Unit):View{
        val rows=items.map{(key,label)->
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(52);setPadding(a.d(16),0,a.d(14),0);isClickable=true;isFocusable=true;contentDescription=label}
            r.addView(a.text(label,15.5f,a.fg),LinearLayout.LayoutParams(0,-2,1f))
            val mark=checkMark();r.addView(mark,LinearLayout.LayoutParams(a.d(24),a.d(24)))
            refreshers.add{mark.visibility=if(current()==key)View.VISIBLE else View.INVISIBLE}
            r.setOnClickListener{if(current()!=key){r.tick(a);pick(key);refreshers.forEach{it()}}}
            r as View}
        refreshers.forEach{it()};return card(rows)}
    fun checkMark()=FrameLayout(a).apply{background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(a.fg)};addView(IconView(a,"check",a.bg),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))}

    /** Colour choices as cards in their own colours (themes, the comics' backdrop); the chosen one ringed inside. */
    fun colourCards(items:List<Triple<String,String,Pair<Int,Int>>>,glyph:Boolean,current:()->String,pick:(String)->Unit):View{
        val row=a.line()
        items.forEachIndexed{i,(key,label,colours)->
            val tile=ThemeCard(a,colours.first,colours.second,label,glyph).apply{isClickable=true;contentDescription=label}
            refreshers.add{tile.on=current()==key}
            tile.setOnClickListener{if(current()!=key){tile.tick(a);pick(key)}}
            a.press(tile);row.addView(tile,LinearLayout.LayoutParams(0,-1,1f).apply{if(i>0)leftMargin=a.d(8)})}
        return row}

    /** Brightness: a thin slider on its capsule with the suns, and «Авто» beside it. [apply] puts it on the window. */
    fun brightness(apply:()->Unit):View{
        val row=a.line()
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0).apply{typeface=PolkaFonts.sans(600)}
        val bar=ThinSlider(a,.02f,1f,0f,start,null,pill=true){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);apply();if(done)a.savePreferences()}.apply{contentDescription="Яркость экрана"}
        bar.dim=auto.on
        auto.setOnClickListener{val on=!auto.on;auto.tick(a);auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value.toDouble());a.savePreferences();apply()}
        row.addView(bar,LinearLayout.LayoutParams(0,-1,1f));row.addView(auto,LinearLayout.LayoutParams(a.d(76),-1).apply{leftMargin=a.d(8)})
        return row}

    /** How pages turn: a card for each way with a little live page turning that way; the cards scroll sideways and
     *  reach out to the panel's edges. [items]: the setting's key, the page drawn (curl, slide, cover, none, scroll) and the name. */
    fun turnCards(items:List<Triple<String,String,String>>,paper:Int,ink:Int,current:()->String,pick:(String)->Unit):View{
        val strip=a.line().apply{setPadding(a.d(16),a.d(2),a.d(16),a.d(2));clipToPadding=false}
        items.forEachIndexed{i,(key,kind,label)->
            val ring=RingCard(a,cardColor)
            val col=a.column().apply{gravity=Gravity.CENTER;isClickable=true;contentDescription=label;background=ring}
            col.addView(TurnTile(a,kind,paper,ink),LinearLayout.LayoutParams(a.d(56),a.d(76)))
            val name=a.text(label,12f,muted).apply{gravity=Gravity.CENTER;maxLines=1};col.addView(name,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(9)})
            refreshers.add{val on=current()==key;ring.on=on;name.setTextColor(if(on)a.fg else muted);name.typeface=PolkaFonts.sans(if(on)600 else 500)}
            col.setOnClickListener{if(current()!=key){col.tick(a);pick(key);refreshers.forEach{it()}}}
            a.press(col);strip.addView(col,LinearLayout.LayoutParams(a.d(96),a.d(118)).apply{if(i>0)leftMargin=a.d(8)})}
        refreshers.forEach{it()}
        return HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(strip)}}
    val bleed get()=LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-a.d(16);rightMargin=-a.d(16)}
}
