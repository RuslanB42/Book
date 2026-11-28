package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * A quote's note written in place (as asked: no window of its own). Everything behind blurs away, the quote's card lifts
 * out of its list to the top of the screen, and the note is written right inside it, under the quote, the keyboard
 * below. «Готово», Back or a tap on the blurred page keeps what was written; the card goes back where it came from and
 * the page comes back into focus. [source]: the card it was opened from, if any (it lifts out of there).
 */
internal class NoteFocus(private val a:MainActivity,private val fragment:String,private val ink:Int,private val value:String,
                         private val saved:String,private val savedPhotos:List<String>,private val source:View?,
                         private val done:(String,List<NotePhotos.Photo>)->Unit){
    private val light=a.light
    private val reduced get()=a.reducedMotion
    private val layer=object:FrameLayout(a){
        // a tap on the blurred page puts the note away (kept, as everywhere a note is written)
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP)finish();return true}}
    private val card=a.column()
    private lateinit var input:EditText
    private val photos=savedPhotos.map{NotePhotos.Photo(it,null)}.toMutableList()
    private val strip=a.line().apply{clipChildren=false;clipToPadding=false}
    private lateinit var stripScroll:HorizontalScrollView
    private val behind=ArrayList<View>()
    private var clock:ValueAnimator?=null
    private var shown=0f
    private var closing=false
    /** Where the card stands while the note is written, and how far it came from (the source card's place). */
    private var fromY=0f

    fun show(){
        a.releaseTextFocus()
        // the reader keeps its page as it is while the keyboard is up (the card is above the keyboard anyway)
        a.reader?.imeFollower={}
        layer.isClickable=true;layer.elevation=a.d(60).toFloat();layer.clipChildren=false
        build()
        val side=a.d(16);val top=topInset()+a.d(12)
        val src=source?.takeIf{it.isAttachedToWindow&&it.width>0}
        val at=IntArray(2);val base=IntArray(2);a.root.getLocationInWindow(base)
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.TOP).apply{leftMargin=side;rightMargin=side;topMargin=top}
        if(src!=null){src.getLocationInWindow(at);lp.leftMargin=at[0]-base[0];lp.width=src.width;lp.rightMargin=0;fromY=(at[1]-base[1]-top).toFloat()}
        else fromY=a.d(48).toFloat()
        layer.addView(card,lp)
        for(i in 0 until a.root.childCount)a.root.getChildAt(i).let{if(it.visibility==View.VISIBLE)behind.add(it)}
        a.root.addView(layer,FrameLayout.LayoutParams(-1,-1))
        a.noteFocus=this
        src?.alpha=0f
        run(true){input.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)}
    }

    /** Keeps what was written and puts the note away. */
    fun finish(){if(closing)return;closing=true;a.noteFocus=null
        val v=input.text.toString().trim();val changed=v!=saved.trim()||photos.any{it.id==null}||photos.mapNotNull{it.id}!=savedPhotos
        a.releaseTextFocus()
        run(false){a.reader?.imeFollower=null;(layer.parent as? android.view.ViewGroup)?.removeView(layer);source?.alpha=1f;if(changed)done(v,photos.toList())}
    }

    private fun topInset()=androidx.core.view.ViewCompat.getRootWindowInsets(a.root)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars())?.top?:a.d(24)

    private fun build(){
        a.quoteSurface(card);card.setPadding(a.d(18),a.d(14),a.d(14),a.d(10));card.isClickable=true
        // the quote as on its card: the mark in its colour, the line in the margin
        card.addView(a.text("“",Type.NUMBER,ink,true,true).apply{includeFontPadding=false;gravity=Gravity.TOP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO},LinearLayout.LayoutParams(a.d(30),a.d(26)).apply{topMargin=a.d(4)})
        val body=a.line().apply{gravity=Gravity.TOP}
        body.addView(View(a).apply{background=a.shape(ink,2)},LinearLayout.LayoutParams(a.d(3),-1).apply{rightMargin=a.d(14)})
        body.addView(a.text(fragment,Type.TITLE,serif=true).apply{cssLeading(1.55f);maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,0,a.d(4),0)},LinearLayout.LayoutParams(0,-2,1f))
        card.addView(body,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        // the note, written where it stands on the card: plain text under the fragment
        input=EditText(a).apply{typeface=PolkaFonts.sans(400);setText(value);hint="Ваша заметка";setTextColor(a.fg);setHintTextColor(a.muted);textSize=Type.BODY;background=null
            isSingleLine=false;minLines=2;maxLines=6;gravity=Gravity.TOP or Gravity.START;setLineSpacing(0f,1.3f);setPadding(0,a.d(4),a.d(4),a.d(4))
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            if(Build.VERSION.SDK_INT>=29){textCursorDrawable=android.graphics.drawable.GradientDrawable().apply{setColor(a.fg);setSize(a.d(2),a.d(20))};highlightColor=Ui.alpha(a.fg,0x33)}
            setSelection(text.length)}
        card.addView(input,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        stripScroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(strip)}
        card.addView(stripScroll,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        fillStrip()
        card.addView(View(a).apply{setBackgroundColor(Ui.line(light))},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(12)})
        val foot=a.line().apply{gravity=Gravity.CENTER_VERTICAL or Gravity.END;setPadding(0,a.d(10),0,a.d(2))}
        foot.addView(a.text("Готово",Type.TEXT,a.bg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(18),0,a.d(18),0);background=a.shape(a.fg,Radius.R20);isClickable=true;isFocusable=true;a.press(this)
            setOnClickListener{finish()}},LinearLayout.LayoutParams(-2,a.d(38)))
        card.addView(foot,LinearLayout.LayoutParams(-1,-2))
    }

    /** The note's photos: small squares with a cross, «Фото» last (to add one). */
    private fun fillStrip(){strip.removeAllViews();val size=a.d(56)
        photos.forEachIndexed{_,p->val cell=FrameLayout(a)
            val img=NotePhotos.tile(a,size,a.d(10));cell.addView(img,FrameLayout.LayoutParams(size,size))
            if(p.id!=null)NotePhotos.load(a,p.id,size){bmp->img.setImageBitmap(bmp)} else img.setImageBitmap(NotePhotos.preview(p,size))
            img.setOnClickListener{p.id?.let{NotePhotos.show(a,photos.mapNotNull{it.id},photos.filter{it.id!=null}.indexOf(p))}}
            cell.addView(FrameLayout(a).apply{background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(Ui.OVER_PICTURE)};isClickable=true;contentDescription="Убрать фото"
                addView(IconView(a,"x",Color.WHITE),FrameLayout.LayoutParams(a.d(10),a.d(10),Gravity.CENTER))
                setOnClickListener{photos.remove(p);fillStrip()}},FrameLayout.LayoutParams(a.d(20),a.d(20),Gravity.TOP or Gravity.END).apply{setMargins(0,a.d(4),a.d(4),0)})
            strip.addView(cell,LinearLayout.LayoutParams(size,size).apply{marginEnd=a.d(8)})}
        if(photos.size<NotePhotos.MAX){val add=a.column().apply{gravity=Gravity.CENTER;isClickable=true;contentDescription="Добавить фото"
                background=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(Radius.R12).toFloat();setStroke(a.d(1)+a.d(1)/2,Ui.outline(light))};a.press(this)
                addView(IconView(a,"camera",a.muted),LinearLayout.LayoutParams(a.d(20),a.d(20)))
                addView(a.text("Фото",Type.CAPTION,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(4),0,0)})
                setOnClickListener{NotePhotos.pick(a,NotePhotos.MAX-photos.size){got->got.take(NotePhotos.MAX-photos.size).forEach{photos.add(NotePhotos.Photo(null,it))};fillStrip()
                    stripScroll.post{stripScroll.fullScroll(View.FOCUS_RIGHT)}}}}
            strip.addView(add,LinearLayout.LayoutParams(size,size))}}

    /** In ([on]) or out: the page blurs and dims, the card flies between its place in the list and the top; [then] at the end. */
    private fun run(on:Boolean,then:()->Unit){
        clock?.cancel()
        val target=if(on)1f else 0f
        if(reduced){pose(target);then();return}
        val start=shown
        val sp=Motion.expand
        clock=ValueAnimator.ofFloat(0f,1f).apply{duration=if(on)sp.duration else Motion.STANDARD;interpolator=if(on)sp.interpolator else MotionSpec.move
            addUpdateListener{pose(start+(target-start)*(it.animatedValue as Float))}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){then()}})
            start()}}

    /** [k]: 0 the card in its list and the page sharp, 1 the card at the top over the blurred page. */
    private fun pose(k:Float){shown=k
        val c=k.coerceIn(0f,1f)
        layer.setBackgroundColor(Ui.scrim(light,c))
        card.translationY=fromY*(1f-k)
        // the note's own part (the field, the photos, «Готово») opens under the quote as the card lifts
        val inner=((k-.35f)/.65f).coerceIn(0f,1f)
        for(i in 2 until card.childCount)card.getChildAt(i).alpha=inner
        if(source==null)card.alpha=c
        if(Build.VERSION.SDK_INT>=31){val r=a.d(18)*c
            behind.forEach{it.setRenderEffect(if(r<.5f)null else RenderEffect.createBlurEffect(r,r,Shader.TileMode.CLAMP))}}}
}

/** Writes or changes a quote's note in place (see [NoteFocus]). */
internal fun MainActivity.noteFocus(fragment:String,ink:Int,value:String,saved:String,savedPhotos:List<String>,source:View?,done:(String,List<NotePhotos.Photo>)->Unit)=
    NoteFocus(this,fragment,ink,value,saved,savedPhotos,source,done).show()
