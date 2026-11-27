package app.polka.nativeapp

import android.view.Gravity
import android.view.View
import android.widget.FrameLayout

/**
 * The one head of every window (approved «Одна шапка на все окна»): 56 dp high; on the left one round button, 40 dp
 * seen and 48 to tap, always in the same place — «‹» goes a level back, «×» closes; the title is centred on the whole
 * width, whatever stands at its sides; on the right at most one action, in words.
 */
internal fun MainActivity.windowHead(title:String,close:Boolean,ink:Int,fill:Int,lead:(()->Unit)?,action:View?=null):FrameLayout{
    val head=FrameLayout(this)
    head.addView(text(title,Type.TITLE,ink,true).apply{gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;tag="sheet-title";setPadding(d(64),0,d(64),0)},
        FrameLayout.LayoutParams(-1,-1))
    if(lead!=null)head.addView(leadButton(close,ink,fill,lead),FrameLayout.LayoutParams(d(Size.TOUCH),d(Size.TOUCH),Gravity.START or Gravity.CENTER_VERTICAL))
    action?.let{head.addView(it,FrameLayout.LayoutParams(-2,d(Size.TOUCH),Gravity.END or Gravity.CENTER_VERTICAL))}
    return head}

/** The head's left button: a round 40 dp seen in a 48 dp tap, «×» ([close]) or «‹». */
internal fun MainActivity.leadButton(close:Boolean,ink:Int,fill:Int,lead:()->Unit):View{
    val tap=FrameLayout(this).apply{isClickable=true;isFocusable=true;contentDescription=if(close)"Закрыть" else "Назад";setOnClickListener{lead()}}
    val round=FrameLayout(this).apply{background=shape(fill,Size.ROUND/2)}
    if(close)round.addView(IconView(this,"x",ink),FrameLayout.LayoutParams(d(20),d(20),Gravity.CENTER))
    else round.addView(Chevron(this,true).also{it.ink=ink},FrameLayout.LayoutParams(d(12),d(18),Gravity.CENTER))
    tap.addView(round,FrameLayout.LayoutParams(d(Size.ROUND),d(Size.ROUND),Gravity.CENTER));press(tap)
    return tap}

/** A head's action on the right: words in the secondary grey, the full 48 dp to tap. */
internal fun MainActivity.headAction(label:String,ink:Int,fn:()->Unit)=text(label,Type.TEXT,ink).apply{typeface=PolkaFonts.sans(500);gravity=Gravity.CENTER_VERTICAL
    minHeight=d(Size.TOUCH);setPadding(d(12),0,d(8),0);isClickable=true;isFocusable=true;setOnClickListener{fn()}}
