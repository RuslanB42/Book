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

/** «Оформление чтения» (approved design): a compact panel at the bottom, under half the screen and without dimming,
 *  so every change shows at once in the page above it. The main page holds brightness, theme, text size and font; «Вёрстка» and the font list open inside the same panel at the same height. */
internal class ReaderSettings(private val a:MainActivity,private val r:NativeReader) {
    private val themes=listOf("night" to "Ночь","black" to "Чёрная","sepia" to "Сепия","paper" to "Бумага")
    private val fonts=listOf(Triple("literata","Literata","для долгого чтения"),Triple("ptserif","PT Serif","классическая книжная"),Triple("sans","Без засечек","чистая и простая"))
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val onColor get()=if(a.light)0x1f000000 else 0x24ffffff
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var dialog:android.app.Dialog?=null
    private lateinit var sheet:LinearLayout
    private lateinit var pages:FrameLayout
    private lateinit var main:View
    private var sub:View?=null
    private val refreshers=mutableListOf<()->Unit>()

    fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=FrameLayout(a).apply{isClickable=true;setOnClickListener{close()}}
        sheet=a.column().apply{isClickable=true;setPadding(a.d(18),a.d(12),a.d(18),a.d(20));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(20)})
        pages=FrameLayout(a).apply{clipChildren=true}
        main=mainPage();pages.addView(main,FrameLayout.LayoutParams(-1,-2))
        sheet.addView(pages,LinearLayout.LayoutParams(-1,-2))
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root.addView(sheet,lp)
        dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,r.backgroundColor)
            if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;sheet.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){if(sub!=null)back() else close();true}else false}
        dlg.show()
        if(!reduced){sheet.translationY=a.d(400).toFloat();sheet.post{sheet.translationY=sheet.height+a.d(20).toFloat();sheet.animate().translationY(0f).setDuration(380).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
            // rows rise in one after another as the panel arrives
            (main as? ViewGroup)?.let{g->for(i in 0 until g.childCount){val v=g.getChildAt(i);v.alpha=0f;v.translationY=a.d(14).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(90L+i*45L).setDuration(340).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}}}
    }
    private fun close(){val d=dialog?:return;dialog=null
        if(reduced){d.dismiss();return}
        sheet.animate().translationY(sheet.height+a.d(24).toFloat()).setDuration(240).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}

    // ---- pages -------------------------------------------------------------------------------

    private fun mainPage():View{
        val box=a.column();val gap=a.d(22)
        box.addView(brightnessRow())
        box.addView(themeTiles(),LinearLayout.LayoutParams(-1,-2).apply{topMargin=gap})
        val fontSize=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        fontSize.addView(sizeBar(),LinearLayout.LayoutParams(0,a.d(44),1f))
        fontSize.addView(fontButton(),LinearLayout.LayoutParams(-2,a.d(44)).apply{leftMargin=a.d(10)})
        box.addView(fontSize,LinearLayout.LayoutParams(-1,-2).apply{topMargin=gap})
        val more=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(18),0,a.d(16),0);background=a.shape(pillColor,18);isClickable=true;a.press(this);setOnClickListener{open(layoutPage())}}
        more.addView(a.text("Вёрстка",14.5f),LinearLayout.LayoutParams(0,-2,1f))
        more.addView(Chevron(a),LinearLayout.LayoutParams(a.d(12),a.d(18)))
        box.addView(more,LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=gap})
        refresh();return box
    }
    /** A sub-page slides in over the main one at the main page's height; its content scrolls if longer. */
    private fun open(page:View){if(sub!=null)return;val h=main.height
        val holder=a.column()
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(FrameLayout(a).apply{background=a.shape(pillColor,17);isClickable=true;a.press(this);setOnClickListener{back()};contentDescription="Назад";addView(Chevron(a,true),FrameLayout.LayoutParams(a.d(12),a.d(18),Gravity.CENTER))},LinearLayout.LayoutParams(a.d(34),a.d(34)))
        head.addView(a.text(page.tag as? String?:"",15f,a.fg,true).apply{setPadding(a.d(10),0,0,0)})
        holder.addView(head,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(8)})
        val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;isVerticalFadingEdgeEnabled=true;setFadingEdgeLength(a.d(24));addView(page)}
        holder.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        pages.addView(holder,FrameLayout.LayoutParams(-1,h));sub=holder
        main.layoutParams=main.layoutParams.apply{height=h}
        val w=pages.width.toFloat()
        if(reduced){main.visibility=View.INVISIBLE;return}
        holder.translationX=w;holder.animate().translationX(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()
        main.animate().translationX(-w*.3f).alpha(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
    private fun back(){val s=sub?:return;sub=null;val w=pages.width.toFloat();refresh()
        if(reduced){pages.removeView(s);main.visibility=View.VISIBLE;main.alpha=1f;main.translationX=0f;return}
        s.animate().translationX(w).setDuration(280).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).withEndAction{pages.removeView(s)}.start()
        main.animate().translationX(0f).alpha(1f).setDuration(280).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}

    private fun layoutPage():View{
        val box=a.column().apply{tag="Вёрстка"}
        box.addView(modeRow(),LinearLayout.LayoutParams(-1,-2))
        box.addView(group(
            inline("Межстрочный",1.2f,2.2f,.05f,r.lineHeight(),1.6f,{fmt(it,2)}){v->a.prefs.put("lhv",v.toDouble())},
            inline("Поля",8f,48f,2f,a.prefs.optInt("margin",24).toFloat(),24f,{"${it.roundToInt()}"}){v->a.prefs.put("margin",v.roundToInt())},
            inline("Межбуквенный",-.03f,.12f,.005f,a.prefs.optDouble("ls",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ls",v.toDouble())},
            *(if(Build.VERSION.SDK_INT>=29)arrayOf(inline("Межсловный",0f,.6f,.02f,a.prefs.optDouble("ws",0.0).toFloat(),0f,{percent(it)}){v->a.prefs.put("ws",v.toDouble())})else emptyArray()),
            toggle("По ширине","justify",true),
            toggle("Переносы слов","hyph",true),
            toggle("Убирать пустые строки","trimEmpty",false)
        ),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        box.addView(a.text("Сбросить оформление",13.5f,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(14),0,a.d(6));a.press(this);setOnClickListener{reset()}},LinearLayout.LayoutParams(-1,-2))
        return box
    }
    private fun fontPage():View{
        val box=a.column().apply{tag="Шрифт"}
        val rows=mutableListOf<Pair<String,LinearLayout>>()
        fun paint(){val cur=a.prefs.str("font","literata");rows.forEach{(k,v)->v.background=a.shape(if(k==cur)onColor else pillColor,16);(v.getChildAt(1) as View).visibility=if(k==cur)View.VISIBLE else View.INVISIBLE}}
        fonts.forEach{(key,label,note)->
            val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(14),0);isClickable=true;a.press(this)}
            val texts=a.column()
            texts.addView(TextView(a).apply{text=label;textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.reader(a,key);includeFontPadding=false})
            texts.addView(a.text(note,11f,a.muted).apply{setPadding(0,a.d(3),0,0)})
            row.addView(texts,LinearLayout.LayoutParams(0,-2,1f))
            row.addView(a.text("✓",13f,a.bg,true).apply{gravity=Gravity.CENTER;background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(a.fg)}},LinearLayout.LayoutParams(a.d(22),a.d(22)))
            row.setOnClickListener{a.prefs.put("font",key);a.savePreferences();paint();typography()}
            rows.add(key to row);box.addView(row,LinearLayout.LayoutParams(-1,a.d(58)).apply{bottomMargin=a.d(6)})}
        paint();return box
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
        row.addView(bar,LinearLayout.LayoutParams(0,a.d(44),1f));row.addView(auto,LinearLayout.LayoutParams(-2,a.d(44)).apply{leftMargin=a.d(10)})
        return row
    }
    private fun themeTiles():View{
        val row=a.line()
        themes.forEachIndexed{i,(key,label)->
            val (bg,ink)=colors(key)
            val tile=ThemeTile(a,bg,ink).apply{isClickable=true;contentDescription="Тема: $label"}
            refreshers.add{tile.on=a.prefs.str("rt","night")==key}
            tile.setOnClickListener{a.prefs.put("rt",key);a.savePreferences();typography()}
            a.press(tile);row.addView(tile,LinearLayout.LayoutParams(0,a.d(52),1f).apply{if(i>0)leftMargin=a.d(12)})}
        return row
    }
    private fun fontButton():View{
        val v=a.line().apply{gravity=Gravity.CENTER_VERTICAL;background=a.shape(pillColor,22);setPadding(a.d(14),0,a.d(14),0);isClickable=true;a.press(this);setOnClickListener{open(fontPage())};contentDescription="Шрифт"}
        val sample=TextView(a).apply{text="Аа";textSize=19f;setTextColor(a.fg);includeFontPadding=false}
        v.addView(sample)
        val texts=a.column().apply{setPadding(a.d(10),0,a.d(10),0)}
        texts.addView(a.text("Шрифт",10f,a.muted).apply{includeFontPadding=false})
        val name=TextView(a).apply{textSize=13.5f;setTextColor(a.fg);includeFontPadding=false;maxLines=1}
        texts.addView(name,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
        v.addView(texts)
        v.addView(Chevron(a),LinearLayout.LayoutParams(a.d(10),a.d(16)))
        refreshers.add{val k=a.prefs.str("font","literata");name.text=fonts.firstOrNull{it.first==k}?.second?:"Literata";val tf=PolkaFonts.reader(a,k);name.typeface=tf;sample.typeface=tf}
        return v
    }
    /** Text size as a capsule like brightness: «Размер» on the left, the number on the right. */
    private fun sizeBar():View{
        val bar=SizeBar(a,a.prefs.optDouble("size",19.0).toFloat()){v,done->if(a.prefs.optDouble("size",19.0).toFloat()!=v){a.prefs.put("size",v.toDouble());typography()};if(done)a.savePreferences()}
        refreshers.add{bar.sync(a.prefs.optDouble("size",19.0).toFloat())}
        return bar
    }
    private fun group(vararg rows:View)=a.column().apply{
        background=a.shape(pillColor,18);setPadding(0,a.d(2),0,a.d(2))
        rows.forEachIndexed{i,v->if(i>0)addView(View(a).apply{setBackgroundColor(if(a.light)0x10000000 else 0x10ffffff)},LinearLayout.LayoutParams(-1,1).apply{leftMargin=a.d(14);rightMargin=a.d(14)})
            v.setPadding(a.d(14),v.paddingTop,a.d(14),v.paddingBottom);addView(v,LinearLayout.LayoutParams(-1,-2))}
    }
    private fun fmt(v:Float,digits:Int=1):String{val t=if(digits==2)"%.2f".format(java.util.Locale.US,v) else "%.1f".format(java.util.Locale.US,v);return t.trimEnd('0').trimEnd('.').replace('.',',')}
    private fun percent(v:Float):String{val p=(v*100).roundToInt();return if(p==0)"0" else if(p>0)"+$p%" else "−${-p}%"}
    /** One line: the name, a compact slider and the value. */
    private fun inline(title:String,from:Float,to:Float,step:Float,value:Float,default:Float?,format:(Float)->String,set:(Float)->Unit):View{
        val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(46)}
        row.addView(a.text(title,14f),LinearLayout.LayoutParams(0,-2,1f))
        val valueView=a.text(format(value),12.5f,a.muted).apply{gravity=Gravity.END}
        val bar=SettingSlider(a,from,to,step,value,default){v,done->valueView.text=format(v);set(v);typography();if(done)a.savePreferences()}
        bar.onGrab={held->spotlight(row,held)}
        row.addView(bar,LinearLayout.LayoutParams(a.d(150),a.d(40)));row.addView(valueView,LinearLayout.LayoutParams(a.d(42),-2))
        return row
    }
    private val hidden=mutableListOf<Pair<View,Float>>();private val faded=mutableListOf<Pair<android.graphics.drawable.Drawable,ValueAnimator>>()
    /** While a slider is held, everything but its row fades away (panel included), so only the text and that one control remain. */
    private fun spotlight(row:View,on:Boolean){
        val reduced=this.reduced
        if(on){hidden.clear();faded.forEach{it.second.cancel()};faded.clear()
            var child=row;var parent=child.parent as? ViewGroup
            while(parent!=null){for(i in 0 until parent.childCount){val v=parent.getChildAt(i);if(v!==child){hidden.add(v to v.alpha);if(reduced)v.alpha=0f else v.animate().alpha(0f).setStartDelay(0).setDuration(160).setInterpolator(PathInterpolator(.4f,0f,.2f,1f)).start()}}
                parent.background?.let{bg->val an=ValueAnimator.ofInt(255,0).apply{duration=if(reduced)0 else 180;addUpdateListener{bg.alpha=it.animatedValue as Int};start()};faded.add(bg to an)}
                if(parent===sheet)break;child=parent;parent=parent.parent as? ViewGroup}
            // the scroll view's fading edges would still draw over the background; switch them off while focused
            var g:ViewParent?=row.parent;while(g!=null&&g!==sheet){(g as? ScrollView)?.isVerticalFadingEdgeEnabled=false;g=g.parent}
            // the held row becomes its own small floating card, so the book text never runs into it; the reader bars step aside
            val card=a.shape(sheetColor,22).apply{alpha=0};row.background=card;row.elevation=0f
            ValueAnimator.ofFloat(0f,1f).apply{duration=if(reduced)0 else 180;addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt();row.elevation=a.d(10)*k};start()}
            r.fadeChrome(true)
        }else{
            (row.background as? android.graphics.drawable.Drawable)?.let{card->ValueAnimator.ofFloat(1f,0f).apply{duration=if(reduced)0 else 220;startDelay=if(reduced)0 else 80
                addUpdateListener{val k=it.animatedValue as Float;card.alpha=(255*k).toInt();row.elevation=a.d(10)*k}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(row.background===card){row.background=null;row.elevation=0f}}});start()}}
            r.fadeChrome(false)
            hidden.forEach{(v,alpha)->if(reduced)v.alpha=alpha else v.animate().alpha(alpha).setStartDelay(60).setDuration(240).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()};hidden.clear()
            faded.forEach{(bg,an)->an.cancel();ValueAnimator.ofInt(bg.alpha,255).apply{duration=if(reduced)0 else 240;startDelay=if(reduced)0 else 40;addUpdateListener{bg.alpha=it.animatedValue as Int};start()}};faded.clear()
            var g:ViewParent?=row.parent;while(g!=null&&g!==sheet){(g as? ScrollView)?.isVerticalFadingEdgeEnabled=true;g=g.parent}
        }
    }
    private fun toggle(title:String,key:String,default:Boolean)=PolkaSwitch(a).apply{
        text=title;setTextColor(a.fg);textSize=14f;minHeight=a.d(46);isChecked=a.prefs.optBoolean(key,default)
        setOnCheckedChangeListener{_,on->a.prefs.put(key,on);a.savePreferences();typography()}
    }
    private fun modeRow():View{
        val labels=listOf("Страницы","Прокрутка");val selected=if(r.paged())0 else 1
        val seg=SlidingSegment(a,selected,2,pillColor,if(a.light)Color.WHITE else 0xfff2f2f2.toInt(),16)
        fun tint(index:Int){for(i in 0 until seg.childCount)(seg.getChildAt(i) as TextView).setTextColor(if(i==index)(if(a.light)a.fg else 0xff111111.toInt()) else a.muted)}
        labels.forEachIndexed{i,s->seg.addView(a.text(s,13.5f,bold=true).apply{gravity=Gravity.CENTER;minHeight=a.d(38);a.press(this);setOnClickListener{seg.choose(i);tint(i);r.setReadingMode(i==0);refresh()}},LinearLayout.LayoutParams(0,-2,1f))}
        tint(selected);return seg
    }

    // ---- state -----------------------------------------------------------------------------

    private fun colors(key:String)=when(key){"black"->0xff000000.toInt() to 0xffc9c7c1.toInt();"sepia"->0xfff3ead8.toInt() to 0xff3a2e22.toInt();"paper"->0xfffbfaf7.toInt() to 0xff1e1d1b.toInt();else->0xff18181a.toInt() to 0xffdedcd6.toInt()}
    private fun typography(){r.applyTypography();refresh()}
    private fun refresh(){refreshers.forEach{it()}}
    private fun reset(){
        listOf("lhv","ls","ws","margin").forEach{a.prefs.remove(it)}
        a.prefs.put("size",19.0);a.prefs.put("lh","normal");a.prefs.put("font","literata");a.prefs.put("justify",true);a.prefs.put("hyph",true);a.prefs.put("trimEmpty",false)
        a.savePreferences();r.applyTypography();val d=dialog;dialog=null;d?.dismiss();r.view.post{ReaderSettings(a,r).show()}
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
        val fx=(w*value).coerceAtLeast(h)
        p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,fx,0f,if(a.light)0xff9a9a9e.toInt() else 0xff8c8c90.toInt(),if(a.light)0xff5c5c60.toInt() else 0xffe6e6e8.toInt(),Shader.TileMode.CLAMP)
        p.alpha=if(dim)110 else 255;c.drawRoundRect(0f,0f,fx,h,r,r,p);p.shader=null;p.alpha=255
        fun sun(cx:Float,big:Boolean,ink:Int){val k=if(big)1f else .7f;p.color=ink;p.style=Paint.Style.FILL;c.drawCircle(cx,h/2,3.6f*d*k,p)
            if(big){p.style=Paint.Style.STROKE;p.strokeWidth=1.6f*d;for(i in 0 until 8){val t=Math.PI*i/4;c.drawLine(cx+(cos(t)*6.5f*d).toFloat(),h/2+(sin(t)*6.5f*d).toFloat(),cx+(cos(t)*9f*d).toFloat(),h/2+(sin(t)*9f*d).toFloat(),p)}}}
        sun(h/2,false,if(a.light)Color.WHITE else 0xff1d1d20.toInt());sun(w-h/2,true,if(fx>w-h)(if(a.light)Color.WHITE else 0xff1d1d20.toInt()) else a.muted)}
}

/** A theme as a rounded tile with «Аа» in its own ink; the ring around the chosen one draws in softly. */
internal class ThemeTile(private val a:MainActivity,private val bg:Int,private val ink:Int):View(a){
    private var k=0f;private var anim:ValueAnimator?=null;private var first=true
    var on=false;set(v){if(field==v&&!first)return;field=v;val target=if(v)1f else 0f
        if(first||!isAttachedToWindow||a.prefs.str("motion")=="reduced"){first=false;k=target;invalidate();return}
        anim?.cancel();anim=ValueAnimator.ofFloat(k,target).apply{duration=300;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val d=a.resources.displayMetrics.density;val inset=3.5f*d;val r=RectF(inset,inset,width-inset,height-inset);val rad=15*d
        if(k>0f){p.style=Paint.Style.STROKE;p.strokeWidth=2*d;p.color=a.fg;p.alpha=(255*k).toInt();val o=d+(1f-k)*2.5f*d;c.drawRoundRect(RectF(o,o,width-o,height-o),rad+2.5f*d,rad+2.5f*d,p);p.alpha=255}
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
    init{contentDescription="Размер текста";isClickable=true}
    private fun frac(v:Float)=(v-from)/(to-from)
    private val reduced get()=a.prefs.str("motion")=="reduced"
    fun sync(v:Float){val n=v.coerceIn(from,to).roundToInt().toFloat();if(n==value)return;value=n;glide(frac(n))}
    private fun glide(target:Float){settle?.cancel();if(reduced||!isAttachedToWindow){pos=target;invalidate();return}
        settle=ValueAnimator.ofFloat(pos,target).apply{duration=260;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{pos=it.animatedValue as Float;invalidate()};start()}}
    private fun knobFrac(x:Float):Float{val h=height.toFloat();return ((x-h*.5f)/(width-h).coerceAtLeast(1f)).coerceIn(0f,1f)}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);settle?.cancel();track(e.x,false)}
            MotionEvent.ACTION_MOVE->track(e.x,false)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{track(e.x,true);glide(frac(value))}}
        return true}
    private fun track(x:Float,done:Boolean){val f=knobFrac(x);pos=f;val n=(from+f*(to-from)).roundToInt().toFloat()
        if(n!=value){value=n;if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);bump()}
        invalidate();change(value,done)}
    private fun bump(){if(reduced)return;pulseAnim?.cancel();pulseAnim=ValueAnimator.ofFloat(1f,0f).apply{duration=240;addUpdateListener{pulse=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){settle?.cancel();pulseAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2;val w=width.toFloat();val d=a.resources.displayMetrics.density
        p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
        val fx=h+(w-h)*pos
        p.color=Color.WHITE;p.shader=LinearGradient(0f,0f,fx,0f,if(a.light)0xff9a9a9e.toInt() else 0xff8c8c90.toInt(),if(a.light)0xff5c5c60.toInt() else 0xffe6e6e8.toInt(),Shader.TileMode.CLAMP)
        c.drawRoundRect(0f,0f,fx,h,r,r,p);p.shader=null
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

/** A quiet track, a default tick and a white knob; snaps softly to the default value with a tick of haptics. */
internal class SettingSlider(private val a:MainActivity,private val from:Float,private val to:Float,private val step:Float,value:Float,private val default:Float?,private val onChange:(Float,Boolean)->Unit):SeekBar(a){
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG)
    private var shown=value.coerceIn(from,to);private var detent=false
    var dim=false;set(v){field=v;invalidate()}
    init{
        background=null;splitTrack=false;setPadding(a.d(14),0,a.d(14),0)
        thumb=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.TRANSPARENT);setSize(a.d(28),a.d(28))}
        max=((to-from)/step).roundToInt();progress=((shown-from)/step).roundToInt()
        setOnSeekBarChangeListener(object:OnSeekBarChangeListener{
            override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean){
                if(!user)return
                var v=(from+p*step).coerceIn(from,to)
                if(default!=null){val near=abs(v-default)<=(to-from)*.03f;if(near&&!detent&&a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);detent=near;if(near)v=default}
                shown=v;invalidate();onChange(v,false)
            }
            override fun onStartTrackingTouch(s:SeekBar?){parent?.requestDisallowInterceptTouchEvent(true);onGrab?.invoke(true)}
            override fun onStopTrackingTouch(s:SeekBar?){onChange(shown,true);onGrab?.invoke(false)}
        })
    }
    fun value()=shown
    /** Called with true when the finger takes the knob and false when it lets go. */
    var onGrab:((Boolean)->Unit)?=null
    override fun onDraw(c:Canvas){
        val y=height/2f;val left=paddingLeft.toFloat();val right=(width-paddingRight).toFloat();val span=(right-left).coerceAtLeast(1f)
        fun xOf(v:Float)=left+span*((v-from)/(to-from)).coerceIn(0f,1f)
        val x=xOf(shown);val h=a.d(3).toFloat()
        ink.style=Paint.Style.FILL;ink.clearShadowLayer()
        ink.color=if(a.light)0xffdcdcd9.toInt() else 0xff3c3c40.toInt();c.drawRoundRect(left,y-h,right,y+h,h,h,ink)
        ink.color=if(dim)(a.fg and 0x00ffffff) or 0x55000000 else a.fg;c.drawRoundRect(left,y-h,x,y+h,h,h,ink)
        default?.let{val dx=xOf(it);if(abs(dx-x)>a.d(8)){ink.color=if(a.light)0x40000000 else 0x50ffffff;c.drawCircle(dx,y,a.d(2).toFloat(),ink)}}
        ink.color=0x26000000;c.drawCircle(x,y+a.d(1),a.d(13).toFloat(),ink)
        ink.color=Color.WHITE;c.drawCircle(x,y,a.d(12).toFloat(),ink)
        if(!a.light){ink.style=Paint.Style.STROKE;ink.strokeWidth=1f;ink.color=0x22000000;c.drawCircle(x,y,a.d(12).toFloat(),ink)}
    }
}

/** A page-coloured circle with «Аа» in the page ink; the accent ring grows in when chosen. */
internal class ThemeSwatch(private val a:MainActivity,private val bg:Int,private val ink:Int,selected:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var ring=if(selected)1f else 0f;private var anim:ValueAnimator?=null
    init{importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO}
    fun select(on:Boolean){val target=if(on)1f else 0f;if(target==ring)return;anim?.cancel()
        if(a.prefs.str("motion")=="reduced"||!isAttachedToWindow){ring=target;invalidate();return}
        anim=ValueAnimator.ofFloat(ring,target).apply{duration=260;interpolator=PathInterpolator(.2f,1f,.3f,1f);addUpdateListener{ring=it.animatedValue as Float;invalidate()};start()}}
    override fun onDetachedFromWindow(){anim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val outer=minOf(width,height)/2f-a.d(1);val inner=outer-a.d(5)
        if(ring>0f){p.style=Paint.Style.STROKE;p.strokeWidth=(2.5f*a.resources.displayMetrics.density)*ring;p.color=a.accent;c.drawCircle(cx,cy,outer-p.strokeWidth/2f,p)}
        val r=inner-a.d(1)*(1f-ring)
        p.style=Paint.Style.FILL;p.color=bg;c.drawCircle(cx,cy,r,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(Color.red(bg)>128)0x22000000 else 0x30ffffff;c.drawCircle(cx,cy,r,p)
        p.style=Paint.Style.FILL;p.color=ink;p.textSize=a.d(17).toFloat();p.typeface=PolkaFonts.reader(a,"literata");p.textAlign=Paint.Align.CENTER
        c.drawText("Аа",cx,cy-(p.ascent()+p.descent())/2f,p)
    }
}

/** «Авто» pill used for brightness. */
internal class AutoChip(private val a:MainActivity,initial:Boolean):TextView(a){
    var on=initial;private set
    init{text="Авто";textSize=13f;gravity=Gravity.CENTER;setPadding(a.d(14),a.d(6),a.d(14),a.d(6));minHeight=a.d(30);a.press(this);paint()}
    fun set(value:Boolean){if(value==on)return;on=value;paint()}
    private fun paint(){background=a.shape(if(on)a.fg else (if(a.light)0xffe6e6e3.toInt() else 0xff333336.toInt()),15);setTextColor(if(on)a.bg else a.muted)}
}

/** Small sun for the brightness ends. */
internal class SunGlyph(private val a:MainActivity,private val large:Boolean):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeCap=Paint.Cap.ROUND}
    override fun onDraw(c:Canvas){
        val cx=width/2f;val cy=height/2f;val k=if(large)1f else .72f;p.color=a.muted
        p.style=Paint.Style.FILL;c.drawCircle(cx,cy,a.d(4)*k,p)
        p.style=Paint.Style.STROKE;p.strokeWidth=(1.6f*a.resources.displayMetrics.density)*k
        for(i in 0 until 8){val t=Math.PI*i/4;val r1=(6.5f*a.resources.displayMetrics.density)*k;val r2=(9f*a.resources.displayMetrics.density)*k;c.drawLine(cx+(cos(t)*r1).toFloat(),cy+(sin(t)*r1).toFloat(),cx+(cos(t)*r2).toFloat(),cy+(sin(t)*r2).toFloat(),p)}
    }
}
