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

/** The app's type pair: Brygada 1918 (serif — headings, book titles, big numbers) + Geologica (sans — everything else).
 *  Both are variable fonts; each weight / optical size is built once and cached. The reading text keeps its own
 *  fonts (Literata by default, PT Serif, system sans) — see [reader]. */
object PolkaFonts {
    private val cache=java.util.concurrent.ConcurrentHashMap<String,Typeface>()
    private var assets:android.content.res.AssetManager?=null
    fun init(c:android.content.Context){if(assets==null)assets=c.applicationContext.assets}
    private fun variable(file:String,settings:String):Typeface=cache.getOrPut("$file|$settings"){
        val am=assets?:return@getOrPut Typeface.DEFAULT
        try{Typeface.Builder(am,"fonts/$file").setFontVariationSettings(settings).build()?:Typeface.DEFAULT}catch(e:Exception){Typeface.DEFAULT}}
    /** Geologica at [weight] (400 text, 500 medium, 600 for what used to be bold). */
    fun sans(weight:Int=400):Typeface=variable("geologica.ttf","'wght' $weight")
    /** Brygada 1918 for headings and titles: semibold, or regular for text sizes. */
    fun serif(a:android.content.Context,bold:Boolean=false,italic:Boolean=false):Typeface{init(a);return if(bold)variable("brygada.ttf","'wght' 600") else variable("brygada.ttf","'wght' 400")}
    /** Brygada 1918 for the big numbers of the statistics. */
    fun display(weight:Int=600):Typeface=variable("brygada.ttf","'wght' $weight")
    /** Literata — the default reading font (not used for the app's own interface). */
    fun literata(a:android.content.Context,bold:Boolean=false,italic:Boolean=false):Typeface = cache.getOrPut("lit-$bold-$italic") {
        Typeface.createFromAsset(a.assets,"fonts/literata-static-${if(bold) 600 else 400}${if(italic) "-italic" else ""}.ttf")
    }
    fun reader(a:android.content.Context,font:String):Typeface=when { font=="sans"->Typeface.create("sans-serif",Typeface.NORMAL); font=="ptserif"->cache.getOrPut("pt") { Typeface.createFromAsset(a.assets,"fonts/ptserif-PT_Serif-Web-Regular.ttf") }
        font.startsWith(CustomFonts.PREFIX)->cache[font] ?: CustomFonts.typeface(a,font)?.also { cache[font]=it } ?: literata(a); else->literata(a) }
    fun forget(key:String){cache.remove(key)}
}
fun TextView.cssLeading(factor:Float) { setLineSpacing(textSize*factor-paint.fontSpacing,1f) }
fun MainActivity.column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
fun MainActivity.line()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
fun MainActivity.label(value:String,parent:LinearLayout) { space(parent,20); parent.addView(text(value.uppercase(),12f,muted,true)); space(parent,10) }
fun MainActivity.edit(value:String="",hint:String="",multi:Boolean=false)=EditText(this).apply {
    setText(value); setHint(hint); setTextColor(fg); setHintTextColor(muted); textSize=16f; typeface=PolkaFonts.sans(400); isSingleLine=!multi; minLines=if(multi) 3 else 1; setPadding(d(14),d(12),d(14),d(12)); this@edit.controlSurface(this,12f)
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
    override fun onDraw(c:android.graphics.Canvas) { super.onDraw(c); val pad=a.d(2).toFloat(); val cell=(width-pad*2)/count;
        // the selected thumb stands a little above the track: the app's shade at a touch's height, in both themes
        // (kept inside the track: the thumb shades the track, not the page round it)
        val r=if(thumbColor!=null)a.d(pillRadius-2).toFloat() else a.d(9).toFloat();val tr=if(trackColor!=null)a.d(pillRadius).toFloat() else a.d(12).toFloat()
        c.save();c.clipPath(android.graphics.Path().apply{addRoundRect(0f,0f,width.toFloat(),height.toFloat(),tr,tr,android.graphics.Path.Direction.CW)})
        FloatShade.draw(c,a,android.graphics.RectF(pad+cell*index,pad,pad+cell*(index+1),height-pad),r,a.light,lift=Lift.TOUCH);c.restore()
if(thumbColor!=null){ink.color=thumbColor;c.drawRoundRect(pad+cell*index,pad,pad+cell*(index+1),height-pad,a.d(pillRadius-2).toFloat(),a.d(pillRadius-2).toFloat(),ink)}else{c.save();c.translate(pad+cell*index,pad);segmentThumb.setBounds(0,0,cell.toInt(),(height-pad*2).toInt());segmentThumb.draw(c);c.restore()} }
    override fun onDetachedFromWindow() { spring.stop(); super.onDetachedFromWindow() }
}
fun MainActivity.press(v:View) {
    v.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(view:View){};override fun onViewDetachedFromWindow(view:View){v.animate().cancel();v.scaleX=1f;v.scaleY=1f}})
    v.setOnTouchListener { view,e -> view.background?.setHotspot(e.x,e.y);if(prefs.str("motion")=="reduced")return@setOnTouchListener false
        // a gentle eased dip under the finger and a soft settle on release (no snap, no bounce)
        when(e.actionMasked){MotionEvent.ACTION_DOWN->{v.animate().cancel();v.animate().scaleX(.955f).scaleY(.955f).setDuration(160).setInterpolator(android.view.animation.PathInterpolator(.2f,0f,0f,1f)).start()}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{v.animate().cancel();v.animate().scaleX(1f).scaleY(1f).setDuration(280).setInterpolator(android.view.animation.PathInterpolator(.22f,1f,.36f,1f)).start()}};false }
}
private fun MainActivity.wideSheet()=resources.configuration.let { it.screenWidthDp>=720 && it.screenHeightDp>=600 }

/** Native bottom sheet: no stock Material alert layouts and no web renderer. */
class PolkaSheetBuilder(private val a:MainActivity) {
    private var tall=false
    fun setTall(value:Boolean)=apply{tall=value}
    /** No title row and no close button: the content is the whole sheet (it still closes by swipe or a tap outside). */
    private var bare=false
    fun setBare()=apply{bare=true}
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
        // A text field of the page keeping focus under the sheet took the keyboard back for a moment when the sheet
        // closed (it popped up and went down again); the page lets go of it while a sheet is up.
        if(a.currentFocus is EditText&&!a.searchOpen){a.root.isFocusableInTouchMode=true;a.root.requestFocus()}
        // The page's own window must not still «want» the keyboard (left so by an earlier search or shelf field, whose
        // keyboard was put away through another window): when the sheet closed, the keyboard was handed back to it and
        // shown full height for a few frames before going away again.
        if(!a.searchOpen)androidx.core.view.WindowCompat.getInsetsController(a.window,a.root).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        val sheet=object:AlertDialog(a,R.style.PolkaSheetTheme) {
            private var panel:View?=null
            private var closing=false
            private var allowFinalDismiss=false
            private var widePanel=false
            private var shade:View?=null
            private var motion:SheetMotion?=null
            private fun moveSheet(fraction:Float){val body=panel?:return;val f=fraction.coerceIn(0f,1f);body.translationY=(if(widePanel)a.d(24).toFloat()else body.height.toFloat())*f;body.alpha=if(widePanel)1-f else 1f;shade?.alpha=1-f;if(closing&&fraction>=.999f&&!allowFinalDismiss){allowFinalDismiss=true;motion?.stop()
                // After the keyboard: the window is removed only a little later. Removed the moment the keyboard reported
                // itself gone, Android had not finished hiding it yet and handed it to the page's window still visible:
                // it flashed full height for a few frames before vanishing. Meanwhile the empty window lets touches through.
                if(imeWaited){window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);a.root.postDelayed({if(isShowing)super.dismiss()},380)}else super.dismiss()}}
            override fun dismiss() {
                val body=panel
                if(allowFinalDismiss || body==null || !isShowing || a.isFinishing || a.isDestroyed || a.prefs.str("motion")=="reduced") { super.dismiss(); return }
                if(closing) return
                // With the keyboard up, it goes away first (the sheet rides down with it) and the sheet leaves only once
                // it is gone: leaving earlier handed the keyboard to the page below for a moment, and it popped up again.
                val dv=window?.decorView
                if(!imeWaited&&dv!=null&&androidx.core.view.ViewCompat.getRootWindowInsets(dv)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())==true){
                    imeWaited=true;closing=true
                    // put away through this window's own controller (so nothing still asks for it), the field lets go
                    window?.let{w->androidx.core.view.WindowCompat.getInsetsController(w,dv).hide(androidx.core.view.WindowInsetsCompat.Type.ime())}
                    (a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(dv.windowToken,0)
                    window?.decorView?.let{d->d.isFocusableInTouchMode=true;d.requestFocus()} // (a cleared field would take the focus straight back in touch mode)
                    window?.let{w->w.setSoftInputMode((w.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)}
                    val started=android.os.SystemClock.uptimeMillis()
                    val wait=object:Runnable{override fun run(){val i=androidx.core.view.ViewCompat.getRootWindowInsets(dv)
                        val gone=i==null||(!i.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())&&i.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom==0)
                        if(gone||android.os.SystemClock.uptimeMillis()-started>900||!dv.isAttachedToWindow){closing=false;dismiss()} else dv.postOnAnimation(this)}}
                    dv.postOnAnimation(wait);return}
                closing=true; body.animate().cancel(); body.isEnabled=false
                window?.decorView?.let{d->d.isFocusableInTouchMode=true;d.requestFocus()} // (a cleared field would take the focus straight back in touch mode)
                window?.let{w->w.setSoftInputMode((w.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)}
                // the page under the sheet gets the window back without asking for the keyboard
                androidx.core.view.WindowCompat.getInsetsController(a.window,a.root).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                motion?.to(1f)
            }
            private var imeWaited=false
            override fun onCreate(b:Bundle?) {
                super.onCreate(b)
                val owner:DialogInterface=this
                val wide=a.wideSheet();widePanel=wide
                val box=a.column().apply { background=a.shape(if(a.light) 0xfff7f7f5.toInt() else 0xff1c1c1e.toInt(),22); clipToOutline=true }
                if(!wide){val r=a.d(22).toFloat();(box.background as android.graphics.drawable.GradientDrawable).cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)}
                a.controlSurface(box,22f,sheet=true).apply{flushBottom=!wide;refresh()};panel=box
                val animated=a.prefs.str("motion")!="reduced";if(animated)box.alpha=0f
                motion=SheetMotion{value->moveSheet(value)}
                val handle=FrameLayout(a).apply { addView(View(a).apply { background=a.shape(if(a.light) 0xffcccccc.toInt() else 0xff424244.toInt(),4) },FrameLayout.LayoutParams(a.d(38),a.d(5),Gravity.CENTER)) }
                box.addView(handle,LinearLayout.LayoutParams(-1,a.d(18)))
                val head=FrameLayout(a); head.addView(a.text(title,17f,bold=true).apply { gravity=Gravity.CENTER },FrameLayout.LayoutParams(-1,a.d(48)))
                if(cancel) head.addView(IconView(a,"x",a.muted).apply { a.controlSurface(this,24f);a.press(this);contentDescription="Закрыть"; setOnClickListener { dismiss() } },FrameLayout.LayoutParams(a.d(32),a.d(32),Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin=a.d(12) })
                if(!bare) box.addView(head)
                val content=a.column().apply { setPadding(a.d(20),a.d(6),a.d(20),a.d(24)) }
                if(message.isNotBlank()) content.addView(a.text(message,16f).apply { setPadding(0,a.d(8),0,a.d(16)) })
                view?.let { (it.parent as? ViewGroup)?.removeView(it); content.addView(it) }
                items?.forEachIndexed { i,s ->
                    // the app's own row for a list of boxes to tick (as on «Статус и полки»), not Android's check box
                    if(checked!=null){val row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=a.d(50);setPadding(a.d(6),a.d(4),a.d(6),a.d(4));isClickable=true;isFocusable=true}
                        row.addView(a.text(s.toString(),16f).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=a.d(12)})
                        val tick=TickBox(a,checked!![i]);row.addView(tick,LinearLayout.LayoutParams(a.d(24),a.d(24)))
                        fun describe(){row.contentDescription=s.toString()+if(tick.on)", выбрано" else ""};describe()
                        row.setOnClickListener{tick.on=!tick.on;checked!![i]=tick.on;describe();onCheck?.invoke(owner,i,tick.on)}
                        a.press(row);content.addView(row,LinearLayout.LayoutParams(-1,-2))}
                    else content.addView(a.text(s.toString(),16f,if(i in destructiveItems)0xffd9504e.toInt() else a.fg).apply {
                        minHeight=a.d(52);gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(12),a.d(12),a.d(12),a.d(12));isFocusable=true
                        itemIcons?.getOrNull(i)?.let{kind->
                            setCompoundDrawablesRelative(ActionIconDrawable(kind,currentTextColor).apply{setBounds(0,0,a.d(22),a.d(22))},null,null,null);compoundDrawablePadding=a.d(16)
                        }
                        a.press(this);setOnClickListener { if(isEnabled){isEnabled=false;dismiss();onItem?.invoke(owner,i)} }
                    },LinearLayout.LayoutParams(-1,-2))
                }
                positive?.let { (s,fn) -> a.space(content,12); content.addView(a.button(s,true) { dismiss(); fn?.invoke(this,-1) }) }
                negative?.let { (s,fn) -> a.space(content,8); content.addView(a.button(s) { dismiss(); fn?.invoke(this,-2) }) }
                val scroller=SheetScroll(a,if(a.light) 0xfff7f7f5.toInt() else 0xff1c1c1e.toInt()).apply { isFillViewport=false; addView(content) }
                box.addView(scroller,if(tall)LinearLayout.LayoutParams(-1,0,1f)else LinearLayout.LayoutParams(-1,-2))
                val frame=PolkaSheetFrame(a,box,tall,wide).apply {
                    isClickable=true
                    setOnClickListener { if(cancel)dismiss() }
                }
                shade=frame.shade
                if(animated)frame.shade.alpha=0f
                box.isClickable=true // A tap inside the surface must not reach the outside-dismiss layer.
                setContentView(frame)
                val w=window!!
                ReaderWindow.configure(w,a.reader!=null||a.immersiveSheets,a.bg)
                // The sheet follows the keyboard's own slide frame by frame (its final height arrives at once, before the
                // slide starts, and used to throw the sheet up in one jump), and the line being typed stays above it.
                val follows=android.os.Build.VERSION.SDK_INT>=30
                var base=androidx.core.graphics.Insets.NONE;var keyboard=0;var sliding=false
                // A sheet that opens with the keyboard (a name to type) waits, hidden, for the keyboard to start rising and
                // then rises together with it in one motion: risen alone it first stood low and was then thrown up by the
                // keyboard; placed ahead at the keyboard's height it hung over an empty gap until the keyboard came.
                fun edits(v:View?):Int=when(v){is EditText->1;is ViewGroup->(0 until v.childCount).sumOf{edits(v.getChildAt(it))};else->0}
                val typing=follows&&edits(view) in 1..2
                var entry:(()->Unit)?=null
                fun enter(){val go=entry?:return;entry=null;go()}
                fun place()=frame.safeArea(androidx.core.graphics.Insets.of(base.left,base.top,base.right,maxOf(base.bottom,keyboard)))
                fun imeOf(i:androidx.core.view.WindowInsetsCompat?)=i?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom?:0
                SafeArea.bind(frame) { i -> base=SafeArea.content(i,false);if(follows&&!sliding)keyboard=imeOf(i);place() }
                if(follows)androidx.core.view.ViewCompat.setWindowInsetsAnimationCallback(frame,object:androidx.core.view.WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_STOP){
                    private fun ime(an:androidx.core.view.WindowInsetsAnimationCompat)=an.typeMask and androidx.core.view.WindowInsetsCompat.Type.ime()!=0
                    override fun onPrepare(an:androidx.core.view.WindowInsetsAnimationCompat){if(ime(an)){sliding=true;frame.post{enter()}}}
                    override fun onProgress(i:androidx.core.view.WindowInsetsCompat,running:List<androidx.core.view.WindowInsetsAnimationCompat>):androidx.core.view.WindowInsetsCompat{
                        if(running.any{ime(it)}){val k=imeOf(i);if(k!=keyboard){keyboard=k;place()}};return i}
                    override fun onEnd(an:androidx.core.view.WindowInsetsAnimationCompat){if(!ime(an))return;sliding=false;keyboard=imeOf(androidx.core.view.ViewCompat.getRootWindowInsets(frame));place()}})
                ImeKeeper(frame,a.d(20)){keyboard}.attach()
                w.decorView.setPadding(0,0,0,0)
                if(android.os.Build.VERSION.SDK_INT>=30) w.attributes=w.attributes.apply { setFitInsetsTypes(0);setFitInsetsSides(0) }
                w.setWindowAnimations(0);w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.attributes=w.attributes.apply { dimAmount=0f }; w.setSoftInputMode(if(android.os.Build.VERSION.SDK_INT>=30)WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                // The window owns the entire display. Only the PANEL is bottom-aligned;
                // fitting a floating dialog window to navigation bars leaves a transparent strip.
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                w.setGravity(Gravity.FILL);w.setLayout(-1,-1)
                if(animated)frame.startEntry={
                    if(!closing){motion?.snap(1f)
                        if(typing){entry={if(!closing)motion?.to(0f)};frame.postDelayed({enter()},450)} else motion?.to(0f)}
                }else moveSheet(0f)
                var down=0f; var downAt=0L;var grabbed=0f
                val drag=View.OnTouchListener { _,e -> if(wide || !cancel) return@OnTouchListener false; when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { down=e.rawY; downAt=e.eventTime;box.animate().cancel();motion?.stop();grabbed=box.translationY }
                    MotionEvent.ACTION_MOVE -> {motion?.snap(((grabbed+e.rawY-down)/box.height.coerceAtLeast(1)).coerceIn(0f,1f))}
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { val dy=box.translationY; val speed=dy/maxOf(1,e.eventTime-downAt); if(e.actionMasked==MotionEvent.ACTION_UP && (dy>a.d(120) || speed>.6f*a.resources.displayMetrics.density)) dismiss() else {motion?.snap(dy/box.height.coerceAtLeast(1));if(a.prefs.str("motion")=="reduced")motion?.snap(0f)else motion?.to(0f)} }
                }; true }
                handle.setOnTouchListener(drag); head.setOnTouchListener(drag);frame.drag=drag;box.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){};override fun onViewDetachedFromWindow(v:View){motion?.stop()}})
            }
        }
        sheet.setCancelable(cancel); sheet.setCanceledOnTouchOutside(cancel); sheet.show(); prepareKeyboard(sheet); if(a.immersiveSheets&&a.reader==null)ReaderWindow.configure(sheet.window!!,true,a.bg); if(a.reader!=null){ReaderWindow.configure(sheet.window!!,true,a.bg);sheet.setOnDismissListener{if(a.reader!=null)ReaderWindow.configure(a.window,true,a.bg)}}; return sheet
    }
}

/** Full-window host: surface touches the physical bottom; insets move controls, never its edge. */
internal class PolkaSheetFrame(private val a:MainActivity,internal val panel:LinearLayout,private val tall:Boolean,private val wide:Boolean):FrameLayout(a) {
    internal var startEntry:(()->Unit)?=null
    /** The sheet's own drag (the one its grip and title use); a downward swipe anywhere on the sheet hands over to it. */
    internal var drag:View.OnTouchListener?=null
    private var dy0=0f;private var dx0=0f;private var watching=false;private var dragging=false
    private val slop=android.view.ViewConfiguration.get(a).scaledTouchSlop
    /** Whether something under the finger can still scroll up (a list not at its top): then the swipe scrolls it. */
    private fun scrollsUp(v:View,x:Int,y:Int):Boolean{if(!v.isShown)return false;val r=android.graphics.Rect();if(!v.getGlobalVisibleRect(r)||!r.contains(x,y))return false
        if(v.canScrollVertically(-1))return true
        if(v is ViewGroup)for(i in v.childCount-1 downTo 0)if(scrollsUp(v.getChildAt(i),x,y))return true;return false}
    /** Swipe down anywhere on the sheet to close it, not only by its grip: once the finger clearly goes down (and no
     *  list under it can scroll up), the touch becomes the sheet's drag and the content under it gets a cancel. */
    override fun dispatchTouchEvent(e:MotionEvent):Boolean{val d=drag?:return super.dispatchTouchEvent(e)
        when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{dragging=false;val r=android.graphics.Rect();panel.getGlobalVisibleRect(r)
                watching=r.contains(e.rawX.toInt(),e.rawY.toInt())&&!scrollsUp(panel,e.rawX.toInt(),e.rawY.toInt());dy0=e.rawY;dx0=e.rawX}
            MotionEvent.ACTION_MOVE->if(watching&&!dragging){val dy=e.rawY-dy0;val dx=e.rawX-dx0
                if(dy<-slop||kotlin.math.abs(dx)>slop&&kotlin.math.abs(dx)>dy)watching=false
                else if(dy>slop&&dy>kotlin.math.abs(dx)*1.5f){dragging=true
                    val now=android.os.SystemClock.uptimeMillis();val c=MotionEvent.obtain(now,now,MotionEvent.ACTION_CANCEL,e.x,e.y,0);super.dispatchTouchEvent(c);c.recycle()
                    val down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,e.rawX,dy0,0);d.onTouch(this,down);down.recycle()}}
        }
        if(dragging){d.onTouch(this,e);if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL){dragging=false;watching=false};return true}
        return super.dispatchTouchEvent(e)}
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
        // a tall sheet runs to the bottom edge of the screen: its list scrolls on under the gesture bar (the room for
        // the bar is a padding inside the list, not an empty band under the sheet)
        val runDown=tall&&!wide
        panel.setPadding(if(wide)0 else value.left,0,if(wide)0 else value.right,if(wide||runDown)0 else value.bottom)
        if(runDown)for(i in 0 until panel.childCount)(panel.getChildAt(i) as? SheetScroll)?.let{it.clipToPadding=false;it.setPadding(it.paddingLeft,it.paddingTop,it.paddingRight,value.bottom)}
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

/** Replace, including deleted fields; preserve identity for open native views. */
fun replaceJson(target:org.json.JSONObject,source:org.json.JSONObject) { if(target===source) return; val copy=source.copyJson(); target.keys().asSequence().toList().forEach { target.remove(it) }; copy.keys().forEach { target.put(it,copy.get(it)) } }

/** Persistent native indicator; content updates do not restart its transition. */
/** The active tab's pill, standing a little above its bar: the app's shade at a touch's height (see [Lift.TOUCH]),
 *  in both themes. */
internal class LiftedPill(private val a:MainActivity,private val fill:Int):android.graphics.drawable.Drawable(){
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=fill}
    override fun draw(c:android.graphics.Canvas){val b=android.graphics.RectF(bounds);val r=b.height()/2f
        FloatShade.draw(c,a,b,r,a.light,paint.alpha,lift=Lift.TOUCH)
        c.drawRoundRect(b,r,r,paint)}
    override fun setAlpha(alpha:Int){paint.alpha=alpha}
    override fun setColorFilter(f:android.graphics.ColorFilter?){paint.colorFilter=f}
    @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}
class PolkaNavigation(val a:MainActivity):FrameLayout(a) {
    private val pill=View(a)
    private val buttons=mutableListOf<IconView>()
    private val keys=listOf("home","shelves","quotes","words")
    private var choose:((String)->Unit)?=null
    private var dragStart=0f;private var dragOrigin=0f;private var dragging=false
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){MotionEvent.ACTION_DOWN->{dragStart=e.x;dragOrigin=spring.value;dragging=false};MotionEvent.ACTION_MOVE->if(kotlin.math.abs(e.x-dragStart)>a.d(8)){dragging=true;parent?.requestDisallowInterceptTouchEvent(true);return true}};return false}
    override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);when(e.actionMasked){MotionEvent.ACTION_MOVE->{spring.snap((dragOrigin+e.x-dragStart).coerceIn(0f,a.d((keys.size-1)*56).toFloat()));};MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(e.actionMasked==MotionEvent.ACTION_UP)spring.snap((dragOrigin+e.x-dragStart).coerceIn(0f,a.d((keys.size-1)*56).toFloat()));val index=if(e.actionMasked==MotionEvent.ACTION_CANCEL)selected else kotlin.math.round(spring.value/a.d(56)).toInt().coerceIn(0,keys.size-1);dragging=false;spring.to(a.d(index*56).toFloat());if(index!=selected)choose?.invoke(keys[index])}};return true}
    private var selected=-1
    private var theme:Boolean?=null
    private var material=OpaqueSurface(a,30f)
    private val spring=LiquidSpring{value,speed->pill.translationX=value;val stretch=(kotlin.math.abs(speed)/a.d(2000)).coerceAtMost(.09f);pill.scaleX=1+stretch;pill.scaleY=1-stretch*.45f;tint(value)}
    /** Each icon takes its colour from how much of the sliding pill is under it right now: the dark «on the pill» ink and
     *  the filled shape only where the pill is, so no icon ever turns dark on the bar (and vanishes) before the pill arrives
     *  or stays muted on the pill after it has gone. */
    private fun tint(x:Float){if(buttons.isEmpty())return;val step=a.d(56).toFloat()
        buttons.forEachIndexed{i,b->val k=(1f-kotlin.math.abs(x-i*step)/step).coerceIn(0f,1f)
            val ink=android.animation.ArgbEvaluator().evaluate(k,a.muted,a.bg) as Int;val on=k>.5f
            if(b.ink!=ink||b.isSelected!=on){b.ink=ink;b.cut=a.fg;b.isSelected=on;b.invalidate()}}}
    init {
        material.float=true;background=material;elevation=0f;clipChildren=false;clipToPadding=false
        addView(pill,LayoutParams(a.d(52),a.d(48)).apply { leftMargin=a.d(5); topMargin=a.d(5) })
        // The active tab's pill floats: a soft shadow under it; icons sit above it.
        val row=a.line();keys.forEachIndexed { i,key->val button=IconView(a,key,a.muted).apply{contentDescription=listOf("Мои книги","Полки","Цитаты","Слова")[i];isClickable=true;isFocusable=true;a.press(this)};buttons.add(button);row.addView(button,LinearLayout.LayoutParams(a.d(52),a.d(48)).apply{if(i>0)marginStart=a.d(4)}) };addView(row,LayoutParams(-2,a.d(48)).apply{leftMargin=a.d(5);topMargin=a.d(5)})
    }
    fun bind(tab:String,onSelect:(String)->Unit) {
        choose=onSelect;
        if(theme!=a.light){theme=a.light;material.float=true;material.refresh();pill.background=LiftedPill(a,a.fg)}
        val index=keys.indexOf(tab).coerceAtLeast(0);val x=a.d(index*56).toFloat()
        if(selected<0||a.prefs.str("motion")=="reduced")spring.snap(x)else if(selected!=index)spring.to(x);selected=index
        buttons.forEachIndexed{i,b->b.cut=a.fg;b.setOnClickListener{onSelect(keys[i])}};tint(spring.value)
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(selected>=0&&kotlin.math.abs(spring.value-a.d(selected*56))>.01f){if(a.prefs.str("motion")=="reduced")spring.snap(a.d(selected*56).toFloat())else spring.to(a.d(selected*56).toFloat())}}
    override fun onDetachedFromWindow(){spring.stop();super.onDetachedFromWindow()}
}

