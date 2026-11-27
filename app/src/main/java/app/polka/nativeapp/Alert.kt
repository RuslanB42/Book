package app.polka.nativeapp

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The fourth kind of window (approved «Правило окон»): a card in the middle of the screen for what cannot be taken
 * back, and for errors. The title asks (or says what failed) and names the thing, one or two lines say what follows,
 * and the button names the action — «Удалить», never «Продолжить». It is its own window, so it stands over sheets
 * and both readers alike. [details]: the system's own text for an error, folded under «Подробности».
 */
internal class Alert(private val a:MainActivity,private val title:String,private val message:String,
                     private val action:String?,private val destructive:Boolean=false,private val cancel:String?="Отмена",
                     private val details:String?=null,private val onClose:((Boolean)->Unit)?=null,private val onAction:(()->Unit)?=null){
    private val light=a.light
    private var dialog:Dialog?=null
    private val reduced get()=a.prefs.str("motion")=="reduced"

    fun show():Alert{
        val d=Dialog(a,android.R.style.Theme_Translucent_NoTitleBar);dialog=d
        val root=FrameLayout(a).apply{setBackgroundColor(Ui.scrim(light));isClickable=true;setOnClickListener{close()}}
        val card=a.column().apply{background=a.shape(Ui.sheet(light),Radius.R28);setPadding(a.d(20),a.d(24),a.d(20),a.d(16));isClickable=true
            elevation=a.d(24).toFloat()}
        card.addView(a.text(title,Type.H2,Ui.ink(light),true).apply{setLineSpacing(0f,1.15f)})
        if(message.isNotBlank())card.addView(a.text(message,Type.TEXT,Ui.ink2(light)).apply{setLineSpacing(0f,1.2f)},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        details?.takeIf{it.isNotBlank()}?.let{card.addView(detailsBlock(it),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})}
        val buttons=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        cancel?.let{label->buttons.addView(button(label,false){close()},LinearLayout.LayoutParams(0,-2,1f))}
        action?.let{label->buttons.addView(button(label,true){close(true);onAction?.invoke()},LinearLayout.LayoutParams(0,-2,1f).apply{if(cancel!=null)leftMargin=a.d(8)})}
        card.addView(buttons,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(20)})
        val side=a.d(24)
        root.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply{leftMargin=side;rightMargin=side})
        d.setContentView(root)
        d.window?.let{w->w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT);w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w,false);w.statusBarColor=Color.TRANSPARENT;w.navigationBarColor=Color.TRANSPARENT
            w.setWindowAnimations(0)}
        d.setOnCancelListener{close()}
        d.show()
        if(destructive||details!=null)root.haptic(a,Haptic.REJECT)
        if(!reduced){root.alpha=0f;card.scaleX=.96f;card.scaleY=.96f
            root.animate().alpha(1f).setDuration(Motion.STANDARD).setInterpolator(MotionSpec.enter).start()
            card.animate().scaleX(1f).scaleY(1f).setDuration(Motion.STANDARD).setInterpolator(MotionSpec.enter).start()}
        return this}

    fun close(chosen:Boolean=false){val d=dialog?:return;dialog=null;onClose?.invoke(chosen)
        val root=(d.window?.decorView as? ViewGroup)?.findViewById<View>(android.R.id.content)
        if(reduced||root==null){d.dismiss();return}
        root.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(MotionSpec.exit).withEndAction{d.dismiss()}.start()}

    private fun button(label:String,primary:Boolean,click:()->Unit):TextView{
        val fill=when{primary&&destructive->Ui.danger(light);primary->Ui.ink(light);else->Ui.fill(light)}
        val ink=when{primary&&destructive->if(light)Color.WHITE else Ui.bg(false);primary->Ui.onInk(light);else->Ui.ink(light)}
        return a.text(label,Type.BODY,ink,true).apply{gravity=Gravity.CENTER;minHeight=a.d(Size.BUTTON);setPadding(a.d(12),a.d(12),a.d(12),a.d(12))
            background=a.shape(fill,Size.BUTTON/2);isClickable=true;isFocusable=true;setOnClickListener{click()};a.press(this)}}

    /** «Подробности»: the system's own text, small and copyable, for whoever has to look into it. */
    private fun detailsBlock(text:String):View{
        val box=a.column()
        val toggle=a.text("Подробности",Type.TEXT,Ui.ink2(light)).apply{minHeight=a.d(Size.TOUCH);gravity=Gravity.CENTER_VERTICAL;isClickable=true;isFocusable=true}
        val body=a.text(text,Type.CAPTION,Ui.ink2(light)).apply{typeface=android.graphics.Typeface.MONOSPACE;setTextIsSelectable(true);visibility=View.GONE
            background=a.shape(Ui.card(light),Radius.R16);setPadding(a.d(14),a.d(12),a.d(14),a.d(12))}
        toggle.setOnClickListener{body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE}
        box.addView(toggle,LinearLayout.LayoutParams(-2,-2));box.addView(body,LinearLayout.LayoutParams(-1,-2))
        return box}
}
