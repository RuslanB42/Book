package app.polka.nativeapp

import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Typeface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*

object PolkaFonts {
    private val cache=java.util.concurrent.ConcurrentHashMap<String,Typeface>()
    fun serif(a:android.content.Context,bold:Boolean=false,italic:Boolean=false):Typeface = cache.getOrPut("lit-$bold-$italic") {
        Typeface.createFromAsset(a.assets,"fonts/literata-static-${if(bold) 600 else 400}${if(italic) "-italic" else ""}.ttf")
    }
    fun reader(a:android.content.Context,font:String):Typeface=when(font) { "sans"->Typeface.create("sans-serif",Typeface.NORMAL); "ptserif"->cache.getOrPut("pt") { Typeface.createFromAsset(a.assets,"fonts/ptserif-PT_Serif-Web-Regular.ttf") }; else->serif(a) }
}
fun TextView.cssLeading(factor:Float) { setLineSpacing(textSize*factor-paint.fontSpacing,1f) }
fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
fun MainActivity.line()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
fun MainActivity.label(value:String,parent:LinearLayout) { space(parent,20); parent.addView(text(value.uppercase(),12f,muted,true)); space(parent,10) }
fun MainActivity.edit(value:String="",hint:String="",multi:Boolean=false)=EditText(this).apply {
    setText(value); setHint(hint); setTextColor(fg); setHintTextColor(muted); textSize=16f; typeface=Typeface.create("sans-serif",Typeface.NORMAL); isSingleLine=!multi; minLines=if(multi) 3 else 1; setPadding(d(14),d(12),d(14),d(12)); this@edit.controlSurface(this,12f)
}
fun MainActivity.segment(labels:List<String>,selected:Int,onSelect:(Int)->Unit):LinearLayout {
    val box=SlidingSegment(this,selected,labels.size)
    labels.forEachIndexed { i,s -> box.addView(text(s,14f).apply { gravity=Gravity.CENTER; minHeight=d(36); isSelected=i==selected; press(this); setOnClickListener { box.choose(i); for(j in 0 until box.childCount) box.getChildAt(j).isSelected=j==i; onSelect(i) } },LinearLayout.LayoutParams(0,-2,1f)) }; return box
}
class SlidingSegment(val a:MainActivity,selected:Int,val count:Int,private val trackColor:Int?=null,private val thumbColor:Int?=null,private val pillRadius:Int=10):LinearLayout(a) {
    private var index=selected.coerceAtLeast(0).toFloat()
    private val ink=android.graphics.Paint(3)
    private val segmentThumb=OpaqueSurface(a,9f,selected=true)
    private val spring=LiquidSpring{value,_->index=value;invalidate()}
    init { orientation=HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(a.d(2),a.d(2),a.d(2),a.d(2)); background=if(trackColor!=null)a.shape(trackColor,pillRadius)else a.controlSurface(this,12f); setWillNotDraw(false);spring.snap(index) }
    fun choose(value:Int) { val target=value.coerceIn(0,(count-1).coerceAtLeast(0)).toFloat();if(a.prefs.str("motion")=="reduced")spring.snap(target)else spring.to(target) }
    override fun onDraw(c:android.graphics.Canvas) { super.onDraw(c); val pad=a.d(2).toFloat(); val cell=(width-pad*2)/count;if(thumbColor!=null){ink.color=thumbColor;c.drawRoundRect(pad+cell*index,pad,pad+cell*(index+1),height-pad,a.d(pillRadius-2).toFloat(),a.d(pillRadius-2).toFloat(),ink)}else{c.save();c.translate(pad+cell*index,pad);segmentThumb.setBounds(0,0,cell.toInt(),(height-pad*2).toInt());segmentThumb.draw(c);c.restore()} }
    override fun onDetachedFromWindow() { spring.stop(); super.onDetachedFromWindow() }
}
fun MainActivity.press(v:View) {
    val spring=LiquidSpring{value,_->v.scaleX=value;v.scaleY=value};spring.snap(1f)
    v.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(view:View){};override fun onViewDetachedFromWindow(view:View){spring.stop();v.scaleX=1f;v.scaleY=1f}})
    v.setOnTouchListener { view,e -> view.background?.setHotspot(e.x,e.y);if(prefs.str("motion")=="reduced")return@setOnTouchListener false
        when(e.actionMasked){MotionEvent.ACTION_DOWN->{spring.to(.965f)};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->spring.to(1f)};false }
}
private fun MainActivity.wideSheet()=resources.configuration.let { it.screenWidthDp>=720 && it.screenHeightDp>=600 }

/** Native bottom sheet: no stock Material alert layouts and no web renderer. */
class PolkaSheetBuilder(private val a:MainActivity) {
    private var tall=false
    fun setTall(value:Boolean)=apply{tall=value}
    private var title=""; private var message=""; private var view:View?=null; private var cancel=true
    private var items:Array<out CharSequence>?=null; private var onItem:((DialogInterface,Int)->Unit)?=null
    private var itemIcons:List<String>?=null; private var destructiveItems:Set<Int> = emptySet()
    fun setItemIcons(vararg icons:String)=apply{itemIcons=icons.toList()}
    fun setDestructiveItems(vararg indices:Int)=apply{destructiveItems=indices.toSet()}
    private var checked:BooleanArray?=null; private var onCheck:((DialogInterface,Int,Boolean)->Unit)?=null
    private var positive:Pair<String,((DialogInterface,Int)->Unit)?>?=null
    private var negative:Pair<String,((DialogInterface,Int)->Unit)?>?=null
    fun setTitle(s:CharSequence)=apply { title=s.toString() }
    fun setMessage(s:CharSequence)=apply { message=s.toString() }
    fun setView(v:View)=apply { view=v }
    fun setCancelable(c:Boolean)=apply { cancel=c }
    fun setItems(v:Array<out CharSequence>,callback:(DialogInterface,Int)->Unit)=apply { items=v; onItem=callback }
    fun setMultiChoiceItems(v:Array<out CharSequence>,checks:BooleanArray,callback:(DialogInterface,Int,Boolean)->Unit)=apply { items=v; checked=checks; onCheck=callback }
    fun setPositiveButton(s:CharSequence,callback:((DialogInterface,Int)->Unit)?)=apply { positive=s.toString() to callback }
    fun setNegativeButton(s:CharSequence,callback:((DialogInterface,Int)->Unit)?)=apply { negative=s.toString() to callback }
    /**
     * AlertDialog marks every window FLAG_ALT_FOCUSABLE_IM unless its OWN setView() holds a text field.
     * This sheet installs a custom content view, so the flag stayed on and the window could never
     * connect to the keyboard: the IME opened only by luck. Clear it for the sheet and, when the
     * sheet is an input form, focus the first field and raise the keyboard once the window has focus.
     */
    private fun prepareKeyboard(sheet:AlertDialog) {
        val w=sheet.window ?: return
        fun fields(v:View):List<EditText> = if(v is EditText)listOf(v) else if(v is ViewGroup)(0 until v.childCount).flatMap{fields(v.getChildAt(it))} else emptyList()
        w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        val inputs=fields(w.decorView).filter{it.visibility==View.VISIBLE}
        if(inputs.isEmpty()) return
        w.setSoftInputMode(w.attributes.softInputMode or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // Long forms (several fields) should not grab the keyboard before the person picks a field.
        if(inputs.size>2) return
        val first=inputs.first(); first.requestFocus(); first.setSelection(first.text.length)
        var shown=false
        fun raise() { if(shown||!sheet.isShowing) return; shown=true; first.requestFocus(); androidx.core.view.WindowCompat.getInsetsController(w,first).show(androidx.core.view.WindowInsetsCompat.Type.ime())
            (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(first,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
        val focus=object:ViewTreeObserver.OnWindowFocusChangeListener { override fun onWindowFocusChanged(has:Boolean) { if(has){ first.post{raise()}; if(first.viewTreeObserver.isAlive) first.viewTreeObserver.removeOnWindowFocusChangeListener(this) } } }
        if(first.hasWindowFocus()) first.post{raise()} else first.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        first.postDelayed({raise()},250)
    }
    fun show():AlertDialog {
        val sheet=object:AlertDialog(a,R.style.PolkaSheetTheme) {
            private var panel:View?=null
            private var closing=false
            private var allowFinalDismiss=false
            private var widePanel=false
            private var shade:View?=null
            private var motion:LiquidSpring?=null
            private fun moveSheet(fraction:Float){val body=panel?:return;val f=fraction.coerceIn(0f,1f);body.translationY=(if(widePanel)a.d(24).toFloat()else body.height.toFloat())*f;body.alpha=if(widePanel)1-f else 1f;shade?.alpha=1-f;if(closing&&fraction>=.999f){allowFinalDismiss=true;motion?.stop();super.dismiss()}}
            override fun dismiss() {
                val body=panel
                if(allowFinalDismiss || body==null || !isShowing || a.isFinishing || a.isDestroyed || a.prefs.str("motion")=="reduced") { super.dismiss(); return }
                if(closing) return; closing=true; body.animate().cancel(); body.isEnabled=false
                motion?.to(1f)
            }
            override fun onCreate(b:Bundle?) {
                super.onCreate(b)
                val owner:DialogInterface=this
                val wide=a.wideSheet();widePanel=wide
                val box=a.column().apply { background=a.shape(if(a.light) 0xfff7f7f5.toInt() else 0xff1c1c1e.toInt(),22); clipToOutline=true }
                if(!wide){val r=a.d(22).toFloat();(box.background as android.graphics.drawable.GradientDrawable).cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
                a.controlSurface(box,22f,sheet=true).apply{flushBottom=!wide;refresh()};panel=box
                val animated=a.prefs.str("motion")!="reduced";if(animated)box.alpha=0f
                motion=LiquidSpring{value,_->moveSheet(value)}
                val handle=FrameLayout(a).apply { addView(View(a).apply { background=a.shape(if(a.light) 0xffcccccc.toInt() else 0xff424244.toInt(),4) },FrameLayout.LayoutParams(a.d(38),a.d(5),Gravity.CENTER)) }
                box.addView(handle,LinearLayout.LayoutParams(-1,a.d(18)))
                val head=FrameLayout(a); head.addView(a.text(title,17f,bold=true).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,a.d(48)))
                if(cancel) head.addView(IconView(a,"x",a.muted).apply { a.controlSurface(this,24f);a.press(this);contentDescription="Закрыть"; setOnClickListener { dismiss() } },FrameLayout.LayoutParams(a.d(32),a.d(32),Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin=a.d(12) })
                box.addView(head)
                val content=a.column().apply { setPadding(a.d(20),a.d(6),a.d(20),a.d(24)) }
                if(message.isNotBlank()) content.addView(a.text(message,16f).apply { setPadding(0,a.d(8),0,a.d(16)) })
                view?.let { (it.parent as? ViewGroup)?.removeView(it); content.addView(it) }
                items?.forEachIndexed { i,s ->
                    if(checked!=null) content.addView(CheckBox(a).apply { text=s; setTextColor(a.fg); minHeight=a.d(48); isChecked=checked!![i]; buttonTintList=android.content.res.ColorStateList.valueOf(a.accent); setOnCheckedChangeListener { _,on -> checked!![i]=on; onCheck?.invoke(owner,i,on) } })
                    else content.addView(a.text(s.toString(),16f,if(i in destructiveItems)0xffd9504e.toInt() else a.fg).apply {
                        minHeight=a.d(52);gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(12),a.d(12),a.d(12),a.d(12));isFocusable=true
                        itemIcons?.getOrNull(i)?.let{kind->
                            setCompoundDrawablesRelative(ActionIconDrawable(a,kind,currentTextColor).apply{setBounds(0,0,a.d(22),a.d(22))},null,null,null);compoundDrawablePadding=a.d(16)
                        }
                        a.press(this);setOnClickListener { if(isEnabled){isEnabled=false;dismiss();onItem?.invoke(owner,i)} }
                    },LinearLayout.LayoutParams(-1,-2))
                }
                positive?.let { (s,fn) -> a.space(content,12); content.addView(a.button(s,true) { dismiss(); fn?.invoke(this,-1) }) }
                negative?.let { (s,fn) -> a.space(content,8); content.addView(a.button(s) { dismiss(); fn?.invoke(this,-2) }) }
                val scroller=ScrollView(a).apply { isFillViewport=false; addView(content) }
                box.addView(scroller,if(tall)LinearLayout.LayoutParams(-1,0,1f)else LinearLayout.LayoutParams(-1,-2))
                val frame=PolkaSheetFrame(a,box,tall,wide).apply {
                    isClickable=true
                    setOnClickListener { if(cancel)dismiss() }
                }
                frame.sampleEntry={ms->motion?.sample(ms)}
                shade=frame.shade
                if(animated)frame.shade.alpha=0f
                box.isClickable=true // A tap inside the surface must not reach the outside-dismiss layer.
                setContentView(frame)
                val w=window!!
                ReaderWindow.configure(w,a.reader!=null,a.bg)
                SafeArea.bind(frame) { i -> frame.safeArea(SafeArea.content(i,android.os.Build.VERSION.SDK_INT>=30)) }
                w.decorView.setPadding(0,0,0,0)
                if(android.os.Build.VERSION.SDK_INT>=30) w.attributes=w.attributes.apply { setFitInsetsTypes(0);setFitInsetsSides(0) }
                w.setWindowAnimations(0);w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.attributes=w.attributes.apply { dimAmount=0f }; w.setSoftInputMode(if(android.os.Build.VERSION.SDK_INT>=30)WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                // The window owns the entire display. Only the PANEL is bottom-aligned;
                // fitting a floating dialog window to navigation bars leaves a transparent strip.
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                w.setGravity(Gravity.FILL);w.setLayout(-1,-1)
                if(animated)frame.startEntry={
                    if(!closing){motion?.snap(1f);motion?.to(0f)}
                }else moveSheet(0f)
                var down=0f; var downAt=0L;var grabbed=0f
                val drag=View.OnTouchListener { _,e -> if(wide || !cancel) return@OnTouchListener false; when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { down=e.rawY; downAt=e.eventTime;box.animate().cancel();motion?.stop();grabbed=box.translationY }
                    MotionEvent.ACTION_MOVE -> {motion?.snap(((grabbed+e.rawY-down)/box.height.coerceAtLeast(1)).coerceIn(0f,1f))}
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { val dy=box.translationY; val speed=dy/maxOf(1,e.eventTime-downAt); if(e.actionMasked==MotionEvent.ACTION_UP && (dy>a.d(120) || speed>.6f*a.resources.displayMetrics.density)) dismiss() else {motion?.snap(dy/box.height.coerceAtLeast(1));if(a.prefs.str("motion")=="reduced")motion?.snap(0f)else motion?.to(0f)} }
                }; true }
                handle.setOnTouchListener(drag); head.setOnTouchListener(drag);box.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){motion?.stop()}})
            }
        }
        sheet.setCancelable(cancel); sheet.setCanceledOnTouchOutside(cancel); sheet.show(); prepareKeyboard(sheet); if(a.reader!=null){ReaderWindow.configure(sheet.window!!,true,a.bg);sheet.setOnDismissListener{if(a.reader!=null)ReaderWindow.configure(a.window,true,a.bg)}}; return sheet
    }
}

/** Full-window host: surface touches the physical bottom; insets move controls, never its edge. */
internal class PolkaSheetFrame(private val a:MainActivity,internal val panel:LinearLayout,private val tall:Boolean,private val wide:Boolean):FrameLayout(a) {
    internal var sampleEntry:((Long)->Unit)?=null
    internal var startEntry:(()->Unit)?=null
    internal val shade=View(a).apply{setBackgroundColor(0x57000000)}
    private var safe=androidx.core.graphics.Insets.NONE
    init {
        addView(shade,LayoutParams(-1,-1))
        clipChildren=true;clipToPadding=false
        addView(panel,LayoutParams(if(wide)a.d(540)else -1,-2,if(wide)Gravity.CENTER else Gravity.BOTTOM))
        safeArea(a.appInsets)
    }
    internal fun safeArea(value:androidx.core.graphics.Insets) {
        safe=value
        // Background belongs to panel, so its bottom padding is opaque as well.
        panel.setPadding(if(wide)0 else value.left,0,if(wide)0 else value.right,if(wide)0 else value.bottom)
        requestLayout()
    }
    override fun onMeasure(widthSpec:Int,heightSpec:Int) {
        val w=MeasureSpec.getSize(widthSpec);val h=MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w,h)
        shade.measure(MeasureSpec.makeMeasureSpec(w,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(h,MeasureSpec.EXACTLY))
        val panelWidth=if(wide)minOf(a.d(540),(w-safe.left-safe.right-a.d(32)).coerceAtLeast(1))else w
        val available=(h-safe.top-a.d(48)-(if(wide)safe.bottom+a.d(24)else 0)).coerceAtLeast(1)
        panel.measure(MeasureSpec.makeMeasureSpec(panelWidth,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(available,if(tall)MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
    }
    override fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int) {
        super.onLayout(changed,left,top,right,bottom)
        if(wide) {
            // Center in the USABLE rectangle, not behind an open keyboard or side cutout.
            val x=safe.left+(width-safe.left-safe.right-panel.measuredWidth)/2
            val y=safe.top+a.d(24)+(height-safe.top-safe.bottom-a.d(48)-panel.measuredHeight)/2
            panel.layout(x,y,x+panel.measuredWidth,y+panel.measuredHeight)
        }
        if(panel.height>0){val begin=startEntry;startEntry=null;begin?.invoke()}
    }
}

/** Original 51×31 switch geometry with native Switch accessibility and state. */
class PolkaSwitch(private val a:MainActivity):Switch(a) {
    private val ink=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var fraction=if(isChecked) 1f else 0f
    private var animator:android.animation.ValueAnimator?=null
    override fun setChecked(checked:Boolean) { val changed=checked!=isChecked; super.setChecked(checked); if(!changed) return; animator?.cancel(); val next=if(checked) 1f else 0f; if(!isAttachedToWindow || a.prefs.str("motion")=="reduced") { fraction=next; invalidate() } else animator=android.animation.ValueAnimator.ofFloat(fraction,next).apply { duration=220; interpolator=PathInterpolator(.2f,.8f,.2f,1f); addUpdateListener { fraction=it.animatedValue as Float; invalidate() }; start() } }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
    override fun onMeasure(ws:Int,hs:Int) {
        ink.textSize=a.d(16).toFloat(); val caption=if(text.isNullOrBlank()) 0 else ink.measureText(text.toString()).toInt()+a.d(20)
        val desired=a.d(51)+caption+paddingLeft+paddingRight
        val width=if(View.MeasureSpec.getMode(ws)==View.MeasureSpec.EXACTLY) View.MeasureSpec.getSize(ws) else minOf(desired,if(View.MeasureSpec.getMode(ws)==View.MeasureSpec.AT_MOST) View.MeasureSpec.getSize(ws) else desired)
        setMeasuredDimension(width,a.d(if(caption>0) 48 else 31))
    }
    override fun onDraw(c:android.graphics.Canvas) {
        val left=width-a.d(51).toFloat(); val top=(height-a.d(31))/2f; val radius=a.d(16).toFloat(); ink.color=android.animation.ArgbEvaluator().evaluate(fraction,if(a.light) 0xffd8d8d8.toInt() else 0xff353537.toInt(),0xff30ca5c.toInt()) as Int; c.drawRoundRect(left,top,width.toFloat(),top+a.d(31),radius,radius,ink)
        ink.color=android.graphics.Color.WHITE; val cx=left+a.d(31)/2f+a.d(20)*fraction; c.drawCircle(cx,top+a.d(31)/2f,a.d(13).toFloat(),ink)
        if(!text.isNullOrBlank()) { ink.color=a.fg; ink.textSize=a.d(16).toFloat(); ink.typeface=android.graphics.Typeface.create("sans-serif",android.graphics.Typeface.NORMAL); c.drawText(text.toString(),paddingLeft.toFloat(),height/2f-(ink.ascent()+ink.descent())/2,ink) }
    }
}

/** Replace, including deleted fields; preserve identity for open native views. */
fun replaceJson(target:org.json.JSONObject,source:org.json.JSONObject) { if(target===source) return; val copy=source.copyJson(); target.keys().asSequence().toList().forEach { target.remove(it) }; copy.keys().forEach { target.put(it,copy.get(it)) } }

/** Persistent native indicator; content updates do not restart its transition. */
class PolkaNavigation(val a:MainActivity):FrameLayout(a) {
    private val pill=View(a)
    private val buttons=mutableListOf<IconView>()
    private val keys=listOf("home","shelves","quotes","foryou")
    internal val indicatorOffset get()=spring.value
    internal fun sampleIndicator(ms:Long)=spring.sample(ms)
    private var choose:((String)->Unit)?=null
    private var dragStart=0f;private var dragOrigin=0f;private var dragging=false
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{dragStart=e.x;dragOrigin=spring.value;dragging=false};MotionEvent.ACTION_MOVE->if(kotlin.math.abs(e.x-dragStart)>a.d(8)){dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true}};return false}
    override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);when(e.actionMasked){MotionEvent.ACTION_MOVE->{spring.snap((dragOrigin+e.x-dragStart).coerceIn(0f,a.d(168).toFloat()));};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(e.actionMasked==MotionEvent.ACTION_UP)spring.snap((dragOrigin+e.x-dragStart).coerceIn(0f,a.d(168).toFloat()));val index=if(e.actionMasked==MotionEvent.ACTION_CANCEL)selected else kotlin.math.round(spring.value/a.d(56)).toInt().coerceIn(0,3);dragging=false;spring.to(a.d(index*56).toFloat());if(index!=selected)choose?.invoke(keys[index])}};return true}
    private var selected=-1
    private var theme:Boolean?=null
    private var material=OpaqueSurface(a,30f)
    private val spring=LiquidSpring{value,speed->pill.translationX=value;val stretch=(kotlin.math.abs(speed)/a.d(2000)).coerceAtMost(.09f);pill.scaleX=1+stretch;pill.scaleY=1-stretch*.45f}
    init {
        background=material;elevation=a.d(3).toFloat();clipChildren=false;clipToPadding=false
        addView(pill,LayoutParams(a.d(52),a.d(48)).apply { leftMargin=a.d(5); topMargin=a.d(5) })
        val row=a.line();keys.forEachIndexed { i,key->val button=IconView(a,key,a.muted).apply{contentDescription=listOf("Мои книги","Полки","Цитаты","Для вас")[i];isClickable=true;isFocusable=true;a.press(this)};buttons.add(button);row.addView(button,LinearLayout.LayoutParams(a.d(52),a.d(48)).apply{if(i>0)marginStart=a.d(4)}) };addView(row,LayoutParams(-2,a.d(48)).apply{leftMargin=a.d(5);topMargin=a.d(5)})
    }
    fun bind(tab:String,onSelect:(String)->Unit) {
        choose=onSelect;
        if(theme!=a.light){theme=a.light;material.refresh();pill.background=a.shape(a.fg,24)}
        val index=keys.indexOf(tab).coerceAtLeast(0);val x=a.d(index*56).toFloat()
        if(selected<0||a.prefs.str("motion")=="reduced")spring.snap(x)else if(selected!=index)spring.to(x);selected=index
        buttons.forEachIndexed{i,b->b.ink=if(i==index)a.bg else a.muted;b.isSelected=i==index;b.invalidate();b.setOnClickListener{onSelect(keys[i])}}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(selected>=0&&kotlin.math.abs(spring.value-a.d(selected*56))>.01f){if(a.prefs.str("motion")=="reduced")spring.snap(a.d(selected*56).toFloat())else spring.to(a.d(selected*56).toFloat())}}
    override fun onDetachedFromWindow(){spring.stop();super.onDetachedFromWindow()}
}

fun scrollAncestor(view:View):ScrollView? { var parent=view.parent; while(parent!=null) { if(parent is ScrollView) return parent; parent=parent.parent }; return null }

/** Same chevron, optical size and 44dp target as standalone back buttons. */
internal fun MainActivity.backControl(label:String,description:String,action:()->Unit):View = line().apply {
 minimumHeight=d(44);gravity=Gravity.CENTER_VERTICAL;isFocusable=true;contentDescription=description
 addView(icon("back",description,action=action).apply{isClickable=false;isFocusable=false;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(d(44),d(44)))
 addView(text(label,16f).apply{setPadding(d(8),0,0,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(0,-2,1f))
 setOnClickListener{action()}
}
