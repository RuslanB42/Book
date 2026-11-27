package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*

/**
 * A text-entry sheet: a short helper line, one calm rounded field (card colour, hairline that brightens on focus,
 * a caret in the text colour, a clear button), and one primary action that wakes up only when there is something
 * new to save. Enter on the keyboard does the same; the cross in the corner or a swipe closes it.
 */
internal fun MainActivity.inputDialog(title:String,value:String="",hint:String="",helper:String?=null,action:String="Готово",done:(String)->Unit){
    val box=column()
    helper?.let{box.addView(text(it,Type.TEXT,muted).apply{gravity=Gravity.CENTER;setPadding(d(8),0,d(8),0)});space(box,14)}
    val fieldBg=Ui.card(light)
    val rest=Ui.press(light);val focus=Ui.alpha(fg,0x66)
    val frame=FrameLayout(this).apply{background=GradientDrawable().apply{setColor(fieldBg);cornerRadius=d(Radius.R16).toFloat();setStroke(d(1),rest)}}
    val input=EditText(this).apply{
        typeface=PolkaFonts.sans(400);setText(value);setHint(hint);setTextColor(fg);setHintTextColor(muted);textSize=Type.TITLE;background=null;isSingleLine=true
        imeOptions=EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(d(16),0,d(44),0);gravity=Gravity.CENTER_VERTICAL
        if(android.os.Build.VERSION.SDK_INT>=29){textCursorDrawable=GradientDrawable().apply{setColor(fg);setSize(d(2),d(22))};highlightColor=Ui.alpha(fg,0x33)}
        setSelection(text.length)}
    frame.addView(input,FrameLayout.LayoutParams(-1,-1))
    val clear=IconView(this,"x",muted).apply{contentDescription="Очистить";isClickable=true;setOnClickListener{input.setText("");input.requestFocus()};visibility=if(value.isEmpty())View.GONE else View.VISIBLE}
    frame.addView(clear,FrameLayout.LayoutParams(d(44),-1,Gravity.END))
    box.addView(frame,LinearLayout.LayoutParams(-1,d(54)))
    // the hairline glides to a brighter tone while the field is active
    var stroke:ValueAnimator?=null
    input.setOnFocusChangeListener{_,has->stroke?.cancel();val bg=frame.background as GradientDrawable
        stroke=ValueAnimator.ofArgb(if(has)rest else focus,if(has)focus else rest).apply{duration=Motion.FAST;addUpdateListener{bg.setStroke(d(1),it.animatedValue as Int)};start()}}
    space(box,16)
    var sheet:android.app.AlertDialog?=null
    fun ready()=input.text.toString().trim().let{it.isNotEmpty()&&it!=value.trim()}
    // (the sheet itself lets the keyboard go first and leaves after it, see PolkaSheetBuilder.dismiss)
    val go=button(action,true){if(ready()){val v=input.text.toString().trim();sheet?.dismiss();done(v)}}
    fun paint(){val on=ready();go.isEnabled=on;go.animate().alpha(if(on)1f else .38f).setDuration(Motion.FAST).start()}
    go.alpha=if(ready())1f else .38f;go.isEnabled=ready()
    box.addView(go,LinearLayout.LayoutParams(-1,d(52)))
    input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){}
        override fun afterTextChanged(s:Editable?){clear.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE;paint()}})
    input.setOnEditorActionListener{_,act,_->if(act==EditorInfo.IME_ACTION_DONE){go.performClick();true}else false}
    sheet=dialog().setTitle(title).setView(box).show()
    // keyboard right away, once the sheet is on screen
    // the keyboard is asked for at once, so the sheet and the keyboard rise together in one motion (asking later let
    // the sheet settle low first and then jump up with the keyboard)
    input.requestFocus()
    input.post{if(input.isAttachedToWindow){input.requestFocus();(getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT)}}
}
