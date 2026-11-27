package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** «Оформление чтения» (approved design), inside the reader's bottom panel, so every change shows at once in the page
 *  above it. The main page holds brightness, theme, text size and font; «Ещё» and the font list open a level deeper. */
internal class ReaderSettings(private val a:MainActivity,private val r:NativeReader) {
    private val themes=listOf("night" to "Ночь","black" to "Чёрная","sepia" to "Сепия","paper" to "Бумага")
    private val fonts=listOf(Triple("literata","Literata","для долгого чтения"),Triple("ptserif","PT Serif","классическая книжная"),Triple("sans","Без засечек","чистая и простая"))
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val onColor get()=if(a.light)0x1f000000 else 0x24ffffff
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val H get()=a.d(50)
    private val GAP get()=a.d(12)
    /** A held control lifts on a card 6dp round it: half the gap above, below and at the sides. */
    private val RIM get()=a.d(6)
    private val refreshers=mutableListOf<()->Unit>()

    /** Opens in the reader's bottom panel (approved «Читалка А»): «Текст», and «Ещё» and the fonts a level deeper. */
    fun show(){r.panel.push(ReaderPanel.Page("Текст",mainPage(),onReturn={refresh()}))}

    // ---- pages -------------------------------------------------------------------------------

    private fun mainPage():View{
        val box=a.column().apply{clipChildren=false;clipToPadding=false};val gap=GAP
        box.addView(brightnessRow())
        box.addView(themeTiles(),LinearLayout.LayoutParams(-1,-2).apply{topMargin=gap-a.d(4);bottomMargin=-a.d(4);leftMargin=-a.d(4);rightMargin=-a.d(4)})
        // the size slider is the wider one (it is dragged); held, it lifts on its own card and the rest steps aside
        val fontSize=a.line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        val sizeWrap=FrameLayout(a).apply{setPadding(RIM,RIM,RIM,RIM);clipChildren=false}
        val size=sizeBar();sizeWrap.addView(size,FrameLayout.LayoutParams(-1,H));(size as? SizeBar)?.onGrab={on->spotlight(sizeWrap,on)}
        fontSize.addView(sizeWrap,LinearLayout.LayoutParams(0,H+2*RIM,1.45f).apply{leftMargin=-RIM;topMargin=-RIM;bottomMargin=-RIM;rightMargin=-RIM})
        fontSize.addView(fontButton(),LinearLayout.LayoutParams(0,H,1f).apply{leftMargin=a.d(10)})
        box.addView(fontSize,LinearLayout.LayoutParams(-1,-2).apply{topMargin=gap})
        val more=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(16),0);background=a.shape(pillColor,100);isClickable=true;a.press(this);setOnClickListener{open(layoutPage())}}
        more.addView(a.text("Ещё",14.5f),LinearLayout.LayoutParams(0,-2,1f))
        more.addView(Chevron(a),LinearLayout.LayoutParams(a.d(12),a.d(18)))
        box.addView(more,LinearLayout.LayoutParams(-1,H).apply{topMargin=gap})
        refresh();return box
    }
    private fun open(page:View){r.panel.push(ReaderPanel.Page(page.tag as? String,page))}
    private fun layoutPage():View{
        val box=a.column().apply{tag="Ещё";clipChildren=false;clipToPadding=false}
        box.addView(turnRow(),LinearLayout.LayoutParams(-1,-2))
        val list=a.column().apply{clipChildren=false;clipToPadding=false}
        fun held(bar:LayoutBar):View{val wrap=FrameLayout(a).apply{setPadding(RIM,RIM,RIM,RIM)};wrap.addView(bar,FrameLayout.LayoutParams(-1,H));bar.onGrab={on->spotlight(wrap,on)};return wrap}
        fun bar(title:String,from:Float,to:Float,step:Float,value:Float,default:Float,format:(Float)->String,set:(Float)->Unit)=
            held(LayoutBar(a,title,from,to,step,value,default,format){v,done->set(v);typography();if(done)a.savePreferences()})
        listOf(
            bar("Межстрочный",1.2f,2.2f,.05f,r.lineHeight(),1.6f,{fmt(it,2)}){v->a.prefs.put("lhv",v.toDouble())},
            bar("Поля",8f,48f,2f,a.prefs.optInt("margin",24).toFloat(),24f,{"${it.roundToInt()}"}){v->a.prefs.put("margin",v.roundToInt())},
            bar("Межбуквенный",-.03f,.12f,.005f,a.prefs.optDouble("ls",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ls",v.toDouble())})
            .forEach{list.addView(it,LinearLayout.LayoutParams(-1,-2))}
        if(Build.VERSION.SDK_INT>=29)list.addView(bar("Межсловный",0f,.6f,.02f,a.prefs.optDouble("ws",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ws",v.toDouble())},LinearLayout.LayoutParams(-1,-2))
        fun sw(title:String,key:String,default:Boolean):View{val wrap=FrameLayout(a).apply{setPadding(RIM,RIM,RIM,RIM)}
            wrap.addView(LayoutToggle(a,title,a.prefs.optBoolean(key,default)){on->r.crossfade{a.prefs.put(key,on);a.savePreferences();typography()}},FrameLayout.LayoutParams(-1,H));return wrap}
        val pair=a.line();pair.addView(sw("По ширине","justify",true),LinearLayout.LayoutParams(0,-2,1f));pair.addView(sw("Переносы","hyph",true),LinearLayout.LayoutParams(0,-2,1f))
        list.addView(pair,LinearLayout.LayoutParams(-1,-2));list.addView(sw("Убирать пустые строки","trimEmpty",false),LinearLayout.LayoutParams(-1,-2))
        // the capsules' own 6dp frame (the spotlight card) makes the 12dp gaps; it reaches past the page edges by 6dp
        box.addView(list,LinearLayout.LayoutParams(-1,-2).apply{topMargin=GAP-RIM;leftMargin=-RIM;rightMargin=-RIM})
        box.addView(a.text("Сбросить вёрстку",13.5f,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,GAP-RIM+a.d(8),0,a.d(4));a.press(this);setOnClickListener{reset()}},LinearLayout.LayoutParams(-1,-2))
        return box
    }
    private fun fontPage():View{
        val box=a.column().apply{tag="Шрифт"}
        val rows=mutableListOf<Pair<String,LinearLayout>>()
        fun paint(){val cur=a.prefs.str("font","literata");rows.forEach{(k,v)->v.background=a.shape(if(k==cur)onColor else pillColor,16);v.findViewWithTag<View>("tick")?.visibility=if(k==cur)View.VISIBLE else View.INVISIBLE}}
        fun build(){box.removeAllViews();rows.clear()
            val all=fonts.map{Triple(it.first,it.second,it.third)}+CustomFonts.list(a).map{Triple(it.first,it.second,"ваш шрифт")}
            all.forEach{(key,label,note)->
                val custom=key.startsWith(CustomFonts.PREFIX)
                val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(if(custom)6 else 14),0);isClickable=true;a.press(this)}
                val texts=a.column()
                texts.addView(TextView(a).apply{text=label;textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.reader(a,key);includeFontPadding=false;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
                texts.addView(a.text(note,11f,a.muted).apply{setPadding(0,a.d(3),0,0)})
                row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(a.text("✓",13f,a.bg,true).apply{tag="tick";gravity=Gravity.CENTER;background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(a.fg)}},LinearLayout.LayoutParams(a.d(22),a.d(22)))
                // your own fonts can be removed again (the book falls back to Literata if it was in use)
                if(custom)row.addView(IconView(a,"trash",a.muted).apply{isClickable=true;contentDescription="Удалить шрифт";setPadding(a.d(9),a.d(9),a.d(9),a.d(9));a.press(this)
                    setOnClickListener{a.confirm("Удалить шрифт?","«$label» пропадёт из списка шрифтов."){CustomFonts.remove(a,key);a.savePreferences();build();typography()}}},LinearLayout.LayoutParams(a.d(40),a.d(40)).apply{leftMargin=a.d(4)})
                row.setOnClickListener{a.prefs.put("font",key);a.savePreferences();paint();typography()}
                rows.add(key to row);box.addView(row,LinearLayout.LayoutParams(-1,a.d(58)).apply{bottomMargin=a.d(6)})}
            // add a font from a file: the new one is chosen at once
            val add=a.line().apply{gravity=Gravity.CENTER;isClickable=true;a.press(this);contentDescription="Добавить шрифт"
                background=GradientDrawable().apply{cornerRadius=a.d(16).toFloat();setStroke(a.d(1),if(a.light)0x26000000 else 0x2effffff,a.d(5).toFloat(),a.d(4).toFloat())}
                addView(a.text("＋",17f,a.fg,true),LinearLayout.LayoutParams(-2,-2))
                addView(a.text("Добавить шрифт",14f,a.fg,true).apply{setPadding(a.d(8),0,0,0)})
                setOnClickListener{a.pickFont{key->a.prefs.put("font",key);a.savePreferences();build();typography()}}}
            box.addView(add,LinearLayout.LayoutParams(-1,a.d(52)).apply{topMargin=a.d(2)})
            box.addView(a.text("TTF, OTF или ZIP с телефона",11.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
            paint()}
        build();return box
    }

    // ---- building blocks -----------------------------------------------------------------------

    private fun brightnessRow():View{
        val row=a.line()
        val system=try{android.provider.Settings.System.getInt(a.contentResolver,android.provider.Settings.System.SCREEN_BRIGHTNESS)/255f}catch(_:Exception){.5f}
        val start=a.prefs.optDouble("bright",-1.0).toFloat().let{if(it<0)system else it}.coerceIn(.02f,1f)
        val auto=AutoChip(a,a.prefs.optDouble("bright",-1.0)<0)
        val bar=BrightBar(a,start){v,done->a.prefs.put("bright",v.toDouble());auto.set(false);r.applyBrightness();if(done)a.savePreferences()}
        bar.dim=auto.on
        auto.setOnClickListener{val on=!auto.on;auto.set(on);bar.dim=on;a.prefs.put("bright",if(on)-1.0 else bar.value.toDouble());a.savePreferences();r.applyBrightness()}
        row.addView(bar,LinearLayout.LayoutParams(0,H,1f));row.addView(auto,LinearLayout.LayoutParams(a.d(84),H).apply{leftMargin=a.d(10)})
        return row
    }
    private fun themeTiles():View{
        val row=a.line()
        themes.forEachIndexed{i,(key,label)->
            val (bg,ink)=colors(key)
            val tile=ThemeTile(a,bg,ink).apply{isClickable=true;contentDescription="Тема: $label"}
            refreshers.add{tile.on=a.prefs.str("rt","night")==key}
            tile.setOnClickListener{if(a.prefs.str("rt","night")!=key)r.crossfade{a.prefs.put("rt",key);a.savePreferences();r.applyTheme();refresh()}}
            a.press(tile);row.addView(tile,LinearLayout.LayoutParams(0,a.d(66),1f).apply{if(i>0)leftMargin=a.d(2)})}
        return row
    }
    private fun fontButton():View{
        val v=a.line().apply{gravity=Gravity.CENTER_VERTICAL;background=a.shape(pillColor,100);setPadding(a.d(14),0,a.d(14),0);isClickable=true;a.press(this);setOnClickListener{open(fontPage())};contentDescription="Шрифт"}
        val sample=TextView(a).apply{text="Аа";textSize=19f;setTextColor(a.fg);includeFontPadding=false}
        v.addView(sample)
        val texts=a.column().apply{setPadding(a.d(10),0,a.d(10),0)}
        texts.addView(a.text("Шрифт",10f,a.muted).apply{includeFontPadding=false})
        val name=TextView(a).apply{textSize=13.5f;setTextColor(a.fg);includeFontPadding=false;maxLines=1}
        texts.addView(name,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
        v.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
        v.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        refreshers.add{val k=a.prefs.str("font","literata");name.text=fonts.firstOrNull{it.first==k}?.second?:CustomFonts.name(a,k)?:"Literata";val tf=PolkaFonts.reader(a,k);name.typeface=tf;sample.typeface=tf}
        return v
    }
    /** Text size as a capsule like brightness: «Размер» on the left, the number on the right. */
    private fun sizeBar():View{
        val bar=SizeBar(a,a.prefs.optDouble("size",19.0).toFloat()){v,done->if(a.prefs.optDouble("size",19.0).toFloat()!=v){a.prefs.put("size",v.toDouble());typography()};if(done)a.savePreferences()}
        refreshers.add{bar.sync(a.prefs.optDouble("size",19.0).toFloat())}
        return bar
    }
    private fun fmt(v:Float,digits:Int=1):String{val t=if(digits==2)"%.2f".format(java.util.Locale.US,v) else "%.1f".format(java.util.Locale.US,v);return t.trimEnd('0').trimEnd('.').replace('.',',')}
    private fun percent(v:Float):String{val p=(v*100).roundToInt();return if(p==0)"0" else if(p>0)"+$p%" else "−${-p}%"}
    private val hidden=mutableListOf<Pair<View,Float>>();private val faded=mutableListOf<Pair<android.graphics.drawable.Drawable,ValueAnimator>>()
    /** While a slider is held, everything but its row fades away (panel included), so only the text and that one control remain. */
    private val unclip=mutableListOf<Pair<ViewGroup,Pair<Boolean,Boolean>>>()
    private fun spotlight(row:View,on:Boolean){
        val reduced=this.reduced
        if(on){hidden.clear();faded.forEach{it.second.cancel()};faded.clear()
            var child=row;var parent=child.parent as? ViewGroup
            while(parent!=null){for(i in 0 until parent.childCount){val v=parent.getChildAt(i);if(v!==child){hidden.add(v to v.alpha);if(reduced)v.alpha=0f else v.animate().alpha(0f).setStartDelay(0).setDuration(160).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).start()}}
                parent.background?.let{bg->val an=ValueAnimator.ofInt(255,0).apply{duration=if(reduced)0 else 180;addUpdateListener{bg.alpha=it.animatedValue as Int};start()};faded.add(bg to an)}
                if(parent===r.panel.view)break;child=parent;parent=parent.parent as? ViewGroup}
            // the scroll view's fading edges would still draw over the background; switch them off while focused
            var g:ViewParent?=row.parent;while(g!=null&&g!==r.panel.view){(g as? SheetScroll)?.fadeEnabled=false;g=g.parent}
            // the held row becomes its own small floating card, so the book text never runs into it; the reader bars step aside
            // the card stays inside the page (the row's frame reaches 4dp past its edges and was cut off on the right) and
            // casts no shadow: it only has to keep the text off the capsule
            unclip.clear();var up:ViewParent?=row.parent;while(up is ViewGroup){unclip.add(up to (up.clipChildren to up.clipToPadding));up.clipChildren=false;up.clipToPadding=false;if(up===r.panel.view)break;up=up.parent}
            val card=GradientDrawable().apply{setColor(sheetColor);cornerRadius=row.height/2f;alpha=0};val pad=intArrayOf(row.paddingLeft,row.paddingTop,row.paddingRight,row.paddingBottom);row.background=card;row.setPadding(pad[0],pad[1],pad[2],pad[3]);row.elevation=0f
            ValueAnimator.ofFloat(0f,1f).apply{duration=if(reduced)0 else 180;addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt()};start()}
            r.fadeChrome(true)
        }else{
            (row.background as? android.graphics.drawable.Drawable)?.let{card->ValueAnimator.ofFloat(1f,0f).apply{duration=if(reduced)0 else 220;startDelay=if(reduced)0 else 80
                addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(row.background===card){val pad=intArrayOf(row.paddingLeft,row.paddingTop,row.paddingRight,row.paddingBottom);row.background=null;row.setPadding(pad[0],pad[1],pad[2],pad[3]);row.elevation=0f}}});start()}}
            r.fadeChrome(false)
            hidden.forEach{(v,alpha)->if(reduced)v.alpha=alpha else v.animate().alpha(alpha).setStartDelay(60).setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()};hidden.clear()
            faded.forEach{(bg,an)->an.cancel();ValueAnimator.ofInt(bg.alpha,255).apply{duration=if(reduced)0 else 240;startDelay=if(reduced)0 else 40;addUpdateListener{bg.alpha=it.animatedValue as Int};start()}};faded.clear()
            var g:ViewParent?=row.parent;while(g!=null&&g!==r.panel.view){(g as? SheetScroll)?.fadeEnabled=true;g=g.parent}
            val restore=unclip.toList();unclip.clear();row.postDelayed({restore.forEach{(v,c)->v.clipChildren=c.first;v.clipToPadding=c.second;v.invalidate()}},if(reduced)0L else 320L)
        }
    }
    /** How pages turn (approved variant А): five live previews in one row, scrolling being one of them. */
    private fun turnRow():View{
        val row=a.line().apply{gravity=Gravity.TOP}
        val (paper,ink)=colors(a.prefs.str("rt","night"))
        listOf("curl" to "Листание","slide" to "Сдвиг","cover" to "Наложение","none" to "Без анимации","scroll" to "Прокрутка").forEach{(key,label)->
            val col=a.column().apply{gravity=Gravity.CENTER_HORIZONTAL;isClickable=true;contentDescription=label}
            val tile=TurnTile(a,key,paper,ink);col.addView(tile,LinearLayout.LayoutParams(-1,-2))
            val name=a.text(label,11.5f,a.muted).apply{gravity=Gravity.CENTER;maxLines=2;setLineSpacing(0f,1.1f)};col.addView(name,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})
            fun current()=if(!r.paged())"scroll" else a.prefs.str("turn","curl")
            val show={val on=current()==key;tile.on=on;name.setTextColor(if(on)a.fg else a.muted);name.typeface=PolkaFonts.sans(if(on)600 else 400)}
            // shown at once: the page opens with the chosen way already ringed
            show();refreshers.add(show)
            col.setOnClickListener{if(current()==key)return@setOnClickListener
                if(key=="scroll"){refresh();r.crossfade{r.setReadingMode(false);refresh()}}
                else{a.prefs.put("turn",key);a.savePreferences();if(!r.paged())r.crossfade{r.setReadingMode(true);refresh()}}
                refresh()}
            a.press(col);row.addView(col,LinearLayout.LayoutParams(0,-2,1f))}
        return row
    }

    // ---- state -----------------------------------------------------------------------------

    private fun colors(key:String)=when(key){"black"->0xff000000.toInt() to 0xffc9c7c1.toInt();"sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt();"paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt();else->0xff18181a.toInt() to 0xffdedcd6.toInt()}
    private fun typography(){r.applyTypography();refresh()}
    private fun refresh(){refreshers.forEach{it()}}
    private fun reset(){
        listOf("lhv","ls","ws","margin").forEach{a.prefs.remove(it)}
        a.prefs.put("size",19.0);a.prefs.put("lh","normal");a.prefs.put("font","literata");a.prefs.put("justify",true);a.prefs.put("hyph",true);a.prefs.put("trimEmpty",false)
        a.savePreferences();r.applyTypography();refresh();r.panel.replace(ReaderPanel.Page("Ещё",layoutPage()))
    }
}

/** The brightness bar: a wide pill that fills to the level, a small sun at the start and a big one at the end. */
internal class BrightBar(private val a:MainActivity,start:Float,private val change:(Float,Boolean)->Unit):View(a){
    var value=start.coerceIn(.02f,1f);private set
    var dim=false;set(v){field=v;invalidate()}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    init{contentDescription="Яркость экрана";isClickable=true}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);set(e.x,false)}
        MotionEvent.ACTION_MOVE->set(e.x,false)
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->set(e.x,true)};return true}
    private fun set(x:Float,done:Boolean){value=(x/width).coerceIn(.02f,1f);dim=false;invalidate();change(value,done)}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;val w=width.toFloat();val d=a.resources.displayMetrics.density
        p.style=Paint.Style.FILL;p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        val fx=w*value
        p.shader=null;p.color=SliderInk.fill(a)
        SliderInk.fillTo(c,p,w,h,fx)
        fun sun(cx:Float,big:Boolean,ink:Int){val k=if(big)1f else .78f;p.color=ink;p.style=Paint.Style.FILL;c.drawCircle(cx,h/2,(if(big)3.6f else 2.9f)*d,p)
            p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;p.strokeWidth=(if(big)1.6f else 1.4f)*d;val r0=(if(big)6.5f else 5.2f)*d;val r1=(if(big)9f else 6.9f)*d
            for(i in 0 until 8){val t=Math.PI*i/4;c.drawLine(cx+(cos(t)*r0).toFloat(),h/2+(sin(t)*r0).toFloat(),cx+(cos(t)*r1).toFloat(),h/2+(sin(t)*r1).toFloat(),p)};p.style=Paint.Style.FILL}
        // both suns in two inks, split exactly at the fill's edge — like the labels of the other sliders
        val onFill=if(a.light)Color.WHITE else 0xff1d1d20.toInt();val off=if(a.light)0xff55555a.toInt() else 0xffcfcfd4.toInt()
        c.save();c.clipRect(fx,0f,w,h);sun(h/2,false,off);sun(w-h/2,true,off);c.restore()
        c.save();c.clipRect(0f,0f,fx,h);sun(h/2,false,onFill);sun(w-h/2,true,onFill);c.restore()}
}

/** A theme as a rounded tile with «Аа» in its own ink; the ring around the chosen one draws in softly. */
internal class ThemeTile(private val a:MainActivity,private val bg:Int,private val ink:Int):View(a){
    private var k=0f;private var anim:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||!isAttachedToWindow||a.prefs.str("motion")=="reduced"){first=false;k=target;invalidate();return}
        anim?.cancel();anim=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val inset=4*d;val r=RectF(inset,inset,width-inset,height-inset);val rad=16*d
        if(k>0f){p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;p.alpha=(255*k).toInt();val o=d+(1f-k)*2f*d;c.drawRoundRect(RectF(o,o,width-o,height-o),rad+inset-o,rad+inset-o,p);p.alpha=255}
        p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(Color.red(bg)>128)0x1a000000 else 0x24ffffff;c.drawRoundRect(r,rad,rad,p)
        p.style=Paint.Style.FILL;p.color=ink;p.textSize=17*a.resources.displayMetrics.scaledDensity;p.typeface=PolkaFonts.reader(a,"literata");p.textAlign=Paint.Align.CENTER
        c.drawText("Аа",width/2f,height/2f-(p.ascent()+p.descent())/2f,p)}
}

/** A drawn chevron for «go to a page» (or back when [back]), rounded and in the caption colour. */
internal class Chevron(private val a:MainActivity,private val back:Boolean=false):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val path=Path()
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val w=9*d;val h=16*d;p.strokeWidth=2.2f*d;p.color=if(back)a.fg else (if(a.light)0xff7a7a80.toInt() else 0xffa3a3a9.toInt())
        c.save();c.translate((width-w)/2f,(height-h)/2f);if(back)c.scale(-1f,1f,w/2f,h/2f)
        path.rewind();path.moveTo(1.5f*d,1.8f*d);path.lineTo(7.3f*d,8f*d);path.lineTo(1.5f*d,14.2f*d);c.drawPath(path,p);c.restore()}
}

/** Text size 14…32 as a capsule like the brightness bar. The fill follows the finger and settles on whole steps;
 *  «Размер» is dark where the fill covers it and light elsewhere; the number on the right. A light tick on every step. */
internal class SizeBar(private val a:MainActivity,start:Float,private val change:(Float,Boolean)->Unit):View(a){
    private val from=14f;private val to=32f
    private var value=start.coerceIn(from,to).roundToInt().toFloat()
    private var pos=frac(value);private var settle:ValueAnimator?=null;private var pulse=0f;private var pulseAnim:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG);private val face by lazy{a.text("",1f).typeface}
    var onGrab:((Boolean)->Unit)?=null
    init{contentDescription="Размер текста";isClickable=true}
    private fun frac(v:Float)=(v-from)/(to-from)
    private val reduced get()=a.prefs.str("motion")=="reduced"
    fun sync(v:Float){val n=v.coerceIn(from,to).roundToInt().toFloat();if(n==value)return;value=n;glide(frac(n))}
    private fun glide(target:Float){settle?.cancel();if(reduced||!isAttachedToWindow){pos=target;invalidate();return}
        settle=ValueAnimator.ofFloat(pos,target).apply{duration=260;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pos=it.animatedValue as Float;invalidate()};start()}}
    private fun knobFrac(x:Float):Float{val h=height.toFloat();return ((x-h*.5f)/(width-h).coerceAtLeast(1f)).coerceIn(0f,1f)}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);settle?.cancel();onGrab?.invoke(true);track(e.x,false)}
            MotionEvent.ACTION_MOVE->track(e.x,false)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{track(e.x,true);glide(frac(value));onGrab?.invoke(false)}}
        return true}
    private fun track(x:Float,done:Boolean){val f=knobFrac(x);pos=f;val n=(from+f*(to-from)).roundToInt().toFloat()
        if(n!=value){value=n;if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);bump()}
        invalidate();change(value,done)}
    private fun bump(){if(reduced)return;pulseAnim?.cancel();pulseAnim=ValueAnimator.ofFloat(1f,0f).apply{duration=240;addUpdateListener{pulse=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){settle?.cancel();pulseAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;val w=width.toFloat();val d=a.resources.displayMetrics.density
        p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        val fx=w*pos
        p.shader=null;p.color=SliderInk.fill(a)
        SliderInk.fillTo(c,p,w,h,fx)
        val onFill=if(a.light)Color.WHITE else 0xff1d1d20.toInt();val off=if(a.light)0xff55555a.toInt() else 0xffcfcfd4.toInt()
        t.typeface=face;t.textSize=13.5f*a.resources.displayMetrics.scaledDensity;t.textAlign=Paint.Align.LEFT
        val base=h/2f-(t.ascent()+t.descent())/2f;val lx=16*d
        // the label in two inks, split exactly at the fill's edge
        t.color=off;c.save();c.clipRect(fx,0f,w,h);c.drawText("Размер",lx,base,t);c.restore()
        t.color=onFill;c.save();c.clipRect(0f,0f,fx,h);c.drawText("Размер",lx,base,t);c.restore()
        val num=value.roundToInt().toString();t.textAlign=Paint.Align.RIGHT;t.isFakeBoldText=true
        val nx=w-16*d;t.color=if(fx>nx-4*d)onFill else (if(a.light)a.fg else 0xfff3f2ef.toInt())
        c.save();val s=1f+.14f*pulse;c.scale(s,s,nx-t.measureText(num)/2f,h/2f);c.drawText(num,nx,base,t);c.restore();t.isFakeBoldText=false}
}

/** «Авто» pill used for brightness. */
internal class AutoChip(private val a:MainActivity,initial:Boolean):TextView(a){
    var on=initial;private set
    init{text="Авто";textSize=13f;gravity=Gravity.CENTER;setPadding(a.d(14),a.d(6),a.d(14),a.d(6));minHeight=a.d(30);a.press(this);paint()}
    fun set(value:Boolean){if(value==on)return;on=value;paint()}
    private fun paint(){background=a.shape(if(on)a.fg else (if(a.light)0xffe6e6e3.toInt() else 0xff333336.toInt()),100);setTextColor(if(on)a.bg else a.muted)}
}

/** A layout setting as a capsule like «Размер»: the name on the left in two inks split at the fill, the value on the
 *  right, a quiet dot at the default (the fill catches on it with a tick). The fill follows the finger and settles on whole steps. */
internal class LayoutBar(private val a:MainActivity,private val title:String,private val from:Float,private val to:Float,private val step:Float,start:Float,private val default:Float?,private val format:(Float)->String,private val change:(Float,Boolean)->Unit):View(a){
    var onGrab:((Boolean)->Unit)?=null
    private fun snap(v:Float)=(from+((v-from)/step).roundToInt()*step).coerceIn(from,to)
    private var value=snap(start);private var pos=frac(value);private var settle:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG);private val face by lazy{a.text("",1f).typeface}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{contentDescription=title;isClickable=true}
    private fun frac(v:Float)=((v-from)/(to-from)).coerceIn(0f,1f)
    private fun knobFrac(x:Float):Float{val h=height.toFloat();return ((x-h*.5f)/(width-h).coerceAtLeast(1f)).coerceIn(0f,1f)}
    override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
        MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);settle?.cancel();onGrab?.invoke(true);track(e.x,false)}
        MotionEvent.ACTION_MOVE->track(e.x,false)
        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{track(e.x,true);glide(frac(value));onGrab?.invoke(false)}};return true}
    private fun track(x:Float,done:Boolean){var f=knobFrac(x);val d=default;if(d!=null&&abs(f-frac(d))<.025f)f=frac(d)
        pos=f;val n=snap(from+f*(to-from))
        if(n!=value){value=n;if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)}
        invalidate();change(value,done)}
    private fun glide(target:Float){settle?.cancel();if(reduced||!isAttachedToWindow){pos=target;invalidate();return}
        settle=ValueAnimator.ofFloat(pos,target).apply{duration=240;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pos=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){settle?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;val w=width.toFloat();val d=a.resources.displayMetrics.density
        p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        val fx=w*pos
        p.shader=null;p.color=SliderInk.fill(a)
        SliderInk.fillTo(c,p,w,h,fx)
        default?.let{dv->val dx=h/2+(w-h)*frac(dv);p.color=if(dx<fx)(if(a.light)0x59ffffff else 0x591d1d20) else (if(a.light)0x29000000 else 0x38ffffff);c.drawCircle(dx,h/2,3*d,p)}
        val onFill=if(a.light)Color.WHITE else 0xff1d1d20.toInt();val off=if(a.light)0xff55555a.toInt() else 0xffcfcfd4.toInt()
        t.typeface=face;t.textSize=13f*a.resources.displayMetrics.scaledDensity;t.textAlign=Paint.Align.LEFT
        val base=h/2f-(t.ascent()+t.descent())/2f;val lx=15*d
        t.color=off;c.save();c.clipRect(fx,0f,w,h);c.drawText(title,lx,base,t);c.restore()
        t.color=onFill;c.save();c.clipRect(0f,0f,fx,h);c.drawText(title,lx,base,t);c.restore()
        val num=format(value);t.textAlign=Paint.Align.RIGHT;t.isFakeBoldText=true
        val nx=w-15*d;t.color=if(fx>nx-4*d)onFill else (if(a.light)a.fg else 0xfff3f2ef.toInt());c.drawText(num,nx,base,t);t.isFakeBoldText=false}
}

/** A switch as a capsule: the name on the left, a small switch on the right. After the reference: under the finger
 *  the knob swells toward where it would go; let go, it stretches across the track (its far end first, the near end
 *  following and settling a little past and back) while the track takes its colour. */
/** A switch with its name: on its own capsule, or [plain], a row of a settings card (the card draws the ground and
 *  lights the row under a finger). The switch is the platforms' size, 52 x 32 dp. */
internal class LayoutToggle(private val a:MainActivity,private val title:String,initial:Boolean,private val plain:Boolean=false,private val change:(Boolean)->Unit):View(a){
    private var on=initial
    /** Where the knob's two ends are, 0 the left place and 1 the right one; equal at rest. */
    private var lead=if(initial)1f else 0f;private var trail=lead
    private var press=0f;private var anim:ValueAnimator?=null;private var pressAnim:ValueAnimator?=null
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=Paint(Paint.ANTI_ALIAS_FLAG);private val face by lazy{a.text("",1f).typeface}
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{isClickable=true;contentDescription=title;if(!plain)a.press(this);setOnClickListener{flip()}}
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.isCheckable=true;info.isChecked=on;info.className="android.widget.Switch"}
    override fun drawableStateChanged(){super.drawableStateChanged();if(reduced)return;val to=if(isPressed)1f else 0f;if(press==to)return
        pressAnim?.cancel();pressAnim=ValueAnimator.ofFloat(press,to).apply{duration=if(isPressed)160 else 240;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{press=it.animatedValue as Float;invalidate()};start()}}
    private fun flip(){on=!on;anim?.cancel();if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val to=if(on)1f else 0f
        if(reduced){lead=to;trail=to;invalidate()}
        else{val l0=lead;val t0=trail;val reach=PathInterpolator(.3f,0f,.1f,1f);val follow=android.view.animation.OvershootInterpolator(1.7f)
            anim=ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=android.view.animation.LinearInterpolator()
                addUpdateListener{val x=it.animatedValue as Float;lead=l0+(to-l0)*reach.getInterpolation((x/.55f).coerceIn(0f,1f))
                    trail=t0+(to-t0)*follow.getInterpolation(((x-.22f)/.78f).coerceIn(0f,1f));invalidate()};start()}}
        // let the knob start moving before the page is rebuilt
        postDelayed({change(on)},60)}
    override fun onDetachedFromWindow(){anim?.cancel();pressAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val w=width.toFloat();val d=a.resources.displayMetrics.density
        if(!plain){p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,h/2,h/2,p)}
        t.typeface=face;t.textSize=(if(plain)16f else 13f)*a.resources.displayMetrics.scaledDensity;t.color=if(plain)a.fg else if(a.light)0xff3a3a3e.toInt() else 0xffcfcfd4.toInt()
        // on a capsule the switch sits in its end at the same distance from every side
        val sw=52*d;val sh=32*d;val sx=w-(if(plain)16*d else (h-sh)/2)-sw;val sy=(h-sh)/2;val tx=(if(plain)18 else 14)*d
        val room=sx-tx-8*d;var label=title;if(t.measureText(label)>room){while(label.length>1&&t.measureText("$label…")>room)label=label.dropLast(1);label="$label…"}
        c.drawText(label,tx,h/2f-(t.ascent()+t.descent())/2f,t)
        val k=((lead+trail)/2f).coerceIn(0f,1f)
        val offTrack=if(a.light)0x29000000 else 0xff3a3a3d.toInt();val onTrack=if(a.light)0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
        p.color=androidx.core.graphics.ColorUtils.blendARGB(offTrack,onTrack,k);c.drawRoundRect(sx,sy,sx+sw,sy+sh,sh/2,sh/2,p)
        val kr=(sh-8*d)/2;val left=sx+4*d+kr;val span=sw-8*d-2*kr
        val a0=left+span*minOf(lead,trail);val b0=left+span*maxOf(lead,trail)
        // stretched it thins a little; pressed it swells toward the other side
        val stretch=abs(lead-trail);val swell=.5f*kr*press;val ry=kr*(1f-.12f*stretch-.08f*press)
        val l=a0-kr-(if(on)swell else 0f);val r=b0+kr+(if(on)0f else swell)
        val offKnob=if(a.light)Color.WHITE else 0xffcfcfd4.toInt();val onKnob=if(a.light)Color.WHITE else 0xff1d1d20.toInt()
        // the knob stands a little above its track: the app's shade at a touch's height, kept inside the track
        c.save();c.clipPath(Path().apply{addRoundRect(sx,sy,sx+sw,sy+sh,sh/2,sh/2,Path.Direction.CW)});FloatShade.draw(c,a,RectF(l,h/2-ry,r,h/2+ry),ry,a.light,lift=Lift.TOUCH);c.restore()
        p.color=androidx.core.graphics.ColorUtils.blendARGB(offKnob,onKnob,k);c.drawRoundRect(l,h/2-ry,r,h/2+ry,ry,ry,p)}
}

/** One look for every pill slider: a solid fill in the text colour (no grey gradient, never dimmed), growing exactly
 *  with the value inside the rounded track — empty at zero, full at the end. */
internal object SliderInk{
    fun fill(a:MainActivity)=if(a.light)0xff1d1d1f.toInt() else 0xfff3f2ef.toInt()
    fun fillTo(c:Canvas,p:Paint,w:Float,h:Float,fx:Float){if(fx<=0f)return;val r=h/2;c.save();c.clipPath(Path().apply{addRoundRect(0f,0f,w,h,r,r,Path.Direction.CW)})
        c.drawRoundRect(-r,0f,fx.coerceAtMost(w),h,r,r,p);c.restore()}
}
