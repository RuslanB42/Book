package app.polka.nativeapp

import android.animation.*
import android.content.Context
import android.graphics.*
import android.text.*
import android.util.TypedValue
import android.view.*
import android.view.animation.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Words and quick marks offered for each rating. */
internal object Reviews {
    fun verdict(n:Int)=when(n){5->"Любимая";4->"Понравилась";3->"Неплохо";2->"Так себе";1->"Не понравилась";else->""}
    fun marks(n:Int)=when(n){
        5->listOf("Не оторваться","Перечитаю","Красивый язык","Сильный финал")
        4->listOf("Не оторваться","Заставила думать","Красивый язык","Перечитаю")
        3->listOf("Интересная идея","Легко читается","Местами затянута","Слабее ожиданий")
        in 1..2->listOf("Скучно","Не мой жанр","Слабый финал","Тяжело читалась")
        else->emptyList()}
    fun question(n:Int)=when(n){5->"Чем она так хороша?";4->"Что понравилось?";3->"Чего не хватило?";else->"Что не так?"}
}

/**
 * Wraps its children into rows. When a child moves to another row it glides there instead of jumping,
 * and the height follows on a short ease, so chips that grow or shrink never make the card snap.
 */
internal class FlowRow(c:Context,private val hGap:Int,private val vGap:Int,private val glideAll:Boolean=false,private val smooth:()->Boolean):ViewGroup(c){
    private var shownH=-1;private var targetH=-1;private var hAnim:ValueAnimator?=null
    private val spots=HashMap<View,Point>()
    init{clipChildren=false;clipToPadding=false}
    private fun place(maxW:Int,each:(View,Int,Int)->Unit):Int{var x=0;var y=0;var rowH=0
        for(i in 0 until childCount){val ch=getChildAt(i);if(ch.visibility==GONE)continue
            if(x>0&&x+ch.measuredWidth>maxW){x=0;y+=rowH+vGap;rowH=0}
            each(ch,x,y);x+=ch.measuredWidth+hGap;rowH=maxOf(rowH,ch.measuredHeight)}
        return if(rowH==0)0 else y+rowH}
    override fun onMeasure(ws:Int,hs:Int){val maxW=MeasureSpec.getSize(ws)
        for(i in 0 until childCount){val ch=getChildAt(i);val lp=ch.layoutParams
            ch.measure(if(lp!=null&&lp.width>0)MeasureSpec.makeMeasureSpec(lp.width,MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(maxW,MeasureSpec.AT_MOST),
                if(lp!=null&&lp.height>0)MeasureSpec.makeMeasureSpec(lp.height,MeasureSpec.EXACTLY) else MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED))}
        val h=place(maxW){_,_,_->}
        if(shownH<0||!smooth()||!isLaidOut){hAnim?.cancel();shownH=h;targetH=h}
        else if(h!=targetH){targetH=h;val from=shownH;hAnim?.cancel()
            hAnim=ValueAnimator.ofInt(from,h).apply{duration=320;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shownH=it.animatedValue as Int;requestLayout()};start()}}
        setMeasuredDimension(maxW,shownH)}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val glide=smooth()&&isLaidOut
        place(r-l){ch,x,y->val old=spots[ch]
            ch.layout(x,y,x+ch.measuredWidth,y+ch.measuredHeight)
            if(glide&&old!=null&&(old.y!=y||(glideAll&&old.x!=x))){ // moved to another row: start from where it was
                ch.animate().cancel();ch.translationX+=(old.x-x).toFloat();ch.translationY+=(old.y-y).toFloat()
                ch.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(340).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
            spots[ch]=Point(x,y)}}
    override fun onViewRemoved(child:View){super.onViewRemoved(child);spots.remove(child)}
    fun reset(){hAnim?.cancel();shownH=-1;targetH=-1;spots.clear()}
}

/** A quick mark. Choosing it fills it with the text colour while a check mark draws itself in from the left. */
internal class ReviewChip(private val a:MainActivity,val label:String,selected:Boolean,private val toggled:(ReviewChip)->Unit):View(a){
    var selectedNow=selected;private set
    private var p=if(selected)1f else 0f
    private var anim:ValueAnimator?=null
    private val dm=a.resources.displayMetrics
    private fun sp(v:Float)=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,v,dm)
    private val regular=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(400);textSize=sp(14f)}
    private val medium=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(500);textSize=sp(14f)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1).toFloat()}
    private val tick=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND}
    private val tickPath=Path();private val tickPart=Path();private val pm=PathMeasure()
    private val rect=RectF()
    private val wRegular=regular.measureText(label);private val wMedium=medium.measureText(label)
    private val restFill get()=if(a.light)0x0a000000 else 0x12ffffff
    private val restLine get()=if(a.light)0x14000000 else 0x1affffff
    private val onText get()=if(a.light)Color.WHITE else 0xff1c1c1e.toInt()
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{isClickable=true;isFocusable=true;contentDescription=label;setOnClickListener{toggle()};a.press(this)}
    private fun lerp(x:Float,y:Float,t:Float)=x+(y-x)*t
    private fun mix(c1:Int,c2:Int,t:Float)=ArgbEvaluator().evaluate(t.coerceIn(0f,1f),c1,c2) as Int
    private val padH get()=a.d(14).toFloat()
    private val left get()=lerp(padH,a.d(10).toFloat(),p)
    private val tickRoom get()=(a.d(15)+a.d(6))*p
    override fun onMeasure(ws:Int,hs:Int)=setMeasuredDimension((left+tickRoom+lerp(wRegular,wMedium,p)+padH).toInt()+1,a.d(34))
    fun toggle(){selectedNow=!selectedNow;toggled(this)
        if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        val to=if(selectedNow)1f else 0f;anim?.cancel()
        if(reduced||!isAttachedToWindow){p=to;requestLayout();invalidate();return}
        anim=ValueAnimator.ofFloat(p,to).apply{duration=if(selectedNow)300 else 220;interpolator=PathInterpolator(.2f,.9f,.3f,1f);addUpdateListener{p=it.animatedValue as Float;requestLayout();invalidate()};start()}}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2
        rect.set(0f,0f,width.toFloat(),h)
        fill.color=mix(restFill,a.fg,p);c.drawRoundRect(rect,r,r,fill)
        if(p<1f){line.color=restLine;line.alpha=(Color.alpha(restLine)*(1f-p)).toInt();rect.inset(line.strokeWidth/2,line.strokeWidth/2);c.drawRoundRect(rect,r,r,line)}
        val x=left+tickRoom
        val base=h/2-(regular.fontMetrics.ascent+regular.fontMetrics.descent)/2
        val ink=mix(a.fg,onText,p)
        regular.color=ink;regular.alpha=(255*(1f-p)).toInt();if(p<1f)c.drawText(label,x,base,regular)
        medium.color=ink;medium.alpha=(255*p).toInt();if(p>0f)c.drawText(label,x,base,medium)
        if(p>0.01f){val s=a.d(15).toFloat();val ox=left;val oy=h/2-s/2;val k=s/24f
            tickPath.reset();tickPath.moveTo(ox+5*k,oy+12.5f*k);tickPath.lineTo(ox+9.5f*k,oy+17*k);tickPath.lineTo(ox+19*k,oy+7.5f*k)
            pm.setPath(tickPath,false);tickPart.reset();pm.getSegment(0f,pm.length*p.coerceIn(0f,1f),tickPart,true)
            tick.strokeWidth=a.d(2).toFloat();tick.color=onText;tick.alpha=(255*p.coerceIn(0f,1f)).toInt();c.drawPath(tickPart,tick)}}
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.CheckBox";info.isCheckable=true;info.isChecked=selectedNow}
}

/**
 * «Ваша оценка» in the book card: the stars as before; once a book is rated, a word for the rating appears on the right,
 * and below the stars four quick marks and a short note open up. While nobody is typing, the review rests as a small
 * note: your words, the marks, the date and a pencil. Everything saves by itself.
 */
internal class ReviewBlock(private val a:MainActivity,private val book:JSONObject,private val layoutMotion:()->Unit):LinearLayout(a){
    private enum class Mode{EMPTY,EDIT,NOTE}
    private var mode=Mode.EMPTY
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val ru=Locale("ru")
    private val head=FrameLayout(a)
    private val verdict=a.text("",12f,StarRow.starYellow(a),true)
    private var stars=makeStars(book.optInt("rating"))
    private val starsBox=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    // editor
    private val editor=a.column().apply{clipChildren=false;clipToPadding=false}
    private val ask=a.text("Какой она была",12f,a.muted)
    private val chips=FlowRow(a,a.d(8),a.d(8)){!reduced}
    private val hair=View(a).apply{setBackgroundColor(if(a.light)0x12000000 else 0x14ffffff)}
    private val field=EditText(a)
    // note
    private val note=a.column().apply{clipChildren=false;clipToPadding=false}
    private val words=a.text("",17f,serif=true).apply{cssLeading(1.55f);isClickable=true}
    private val tagRow=FlowRow(a,a.d(6),a.d(6)){false}
    private val date=a.text("",12f,a.muted)
    private val pen=FrameLayout(a)
    private var shownRating=book.optInt("rating")
    private var lastSig=""
    private val saveTask=Runnable{persist()}
    private val collapseTask=Runnable{if(!field.hasFocus()&&hasContent())show(Mode.NOTE,true)}

    init{orientation=VERTICAL;clipChildren=false;clipToPadding=false
        a.floatCard(this,24);setPadding(a.d(16),a.d(16),a.d(16),a.d(14));clipToOutline=true
        head.addView(a.text("Ваша оценка",12f,a.muted),FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL))
        head.addView(verdict,FrameLayout.LayoutParams(-2,-2,Gravity.END or Gravity.CENTER_VERTICAL))
        addView(head,LayoutParams(-1,-2))
        starsBox.addView(stars,FrameLayout.LayoutParams(-1,-1))
        addView(starsBox,LayoutParams(-1,a.d(52)).apply{topMargin=a.d(2);leftMargin=-a.d(4);rightMargin=-a.d(4)})
        // editor
        editor.addView(ask,LayoutParams(-1,-2).apply{topMargin=a.d(10);bottomMargin=a.d(10)})
        editor.addView(chips,LayoutParams(-1,-2))
        editor.addView(hair,LayoutParams(-1,1).apply{topMargin=a.d(14)})
        field.apply{background=null;setPadding(0,a.d(12),0,a.d(2));textSize=16f;typeface=PolkaFonts.sans(400);setTextColor(a.fg);setHintTextColor((a.muted and 0x00ffffff) or 0x88000000.toInt())
            includeFontPadding=false;cssLeading(1.5f);minHeight=a.d(46);gravity=Gravity.TOP or Gravity.START
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI;filters=arrayOf(InputFilter.LengthFilter(600))
            setText(book.optString("review",""))
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){};override fun onTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){}
                override fun afterTextChanged(s:Editable?){removeCallbacks(saveTask);postDelayed(saveTask,450)}})
            setOnFocusChangeListener{_,has->removeCallbacks(collapseTask)
                if(has){reveal(140);reveal(420)}else{persist();postDelayed(collapseTask,240)}}}
        editor.addView(field,LayoutParams(-1,-2))
        addView(editor,LayoutParams(-1,-2))
        // note
        words.setOnClickListener{edit()}
        note.addView(words,LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        note.addView(tagRow,LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        val foot=FrameLayout(a)
        foot.addView(date,FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL))
        pen.background=a.shape(if(a.light)0x0a000000 else 0x12ffffff,16);pen.contentDescription="Изменить отзыв";pen.isClickable=true;pen.isFocusable=true;a.press(pen);pen.setOnClickListener{edit()}
        pen.addView(IconView(a,"pen",a.fg),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))
        foot.addView(pen,FrameLayout.LayoutParams(a.d(32),a.d(32),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=-a.d(4)})
        note.addView(foot,LayoutParams(-1,a.d(32)).apply{topMargin=a.d(12)})
        addView(note,LayoutParams(-1,-2))
        // taps on the marks must not take the keyboard away from the note being written
        addOnAttachStateChangeListener(object:OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){a.focusKeepers.add(chips)}
            override fun onViewDetachedFromWindow(v:View){a.focusKeepers.remove(chips);removeCallbacks(saveTask);removeCallbacks(collapseTask);persist()}})
        lastSig=signature()
        val r=book.optInt("rating")
        setVerdict(r,false);fillChips(r,false);field.hint=Reviews.question(r)
        show(if(r==0)Mode.EMPTY else if(hasContent())Mode.NOTE else Mode.EDIT,false)
    }

    private fun makeStars(r:Int)=NativeRating(a,r,true){n->rate(n)}
    private fun tags():List<String> =book.optJSONArray("reviewTags")?.let{j->(0 until j.length()).map{j.optString(it)}.filter{it.isNotBlank()}}?:emptyList()
    private fun text()=if(mode==Mode.EDIT||field.hasFocus())field.text.toString().trim() else book.optString("review","").trim()
    private fun hasContent()=text().isNotEmpty()||tags().isNotEmpty()

    private fun rate(n:Int){val old=shownRating;shownRating=n
        book.put("rating",n);persist(true)
        setVerdict(n,true);field.hint=if(n>0)Reviews.question(n) else ""
        if(n==0){show(Mode.EMPTY,true);return}
        // marks the new rating also offers stay chosen; the rest fall away
        val keep=tags().filter{it in Reviews.marks(n)};if(keep!=tags()){putTags(keep);persist(true)}
        if(mode==Mode.EDIT&&old!=n){swapChips(n)}else fillChips(n,false)
        if(mode!=Mode.EDIT)show(Mode.EDIT,true)
    }

    private fun setVerdict(n:Int,animate:Boolean){val s=Reviews.verdict(n)
        if(!animate||reduced||!isAttachedToWindow){verdict.animate().cancel();verdict.text=s;verdict.alpha=if(s.isEmpty())0f else 1f;verdict.translationY=0f;return}
        val up=-a.d(6).toFloat()
        verdict.animate().cancel();verdict.animate().alpha(0f).translationY(up).setStartDelay(0).setDuration(if(verdict.text.isEmpty())1 else 120).setInterpolator(AccelerateInterpolator()).withEndAction{
            verdict.text=s;verdict.translationY=-up
            if(s.isNotEmpty())verdict.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,.9f,.3f,1f)).start()}.start()}

    private fun fillChips(n:Int,stagger:Boolean){chips.removeAllViews();if(!stagger)chips.reset();val chosen=tags()
        Reviews.marks(n).forEachIndexed{i,m->val c=ReviewChip(a,m,m in chosen){chip->onChip(chip)}
            chips.addView(c);if(stagger)enter(c,90L+i*45)}}
    /** A new rating offers other marks: the old ones fade out, the new ones rise in one by one. */
    private fun swapChips(n:Int){if(reduced||!isAttachedToWindow){fillChips(n,false);return}
        val old=(0 until chips.childCount).map{chips.getChildAt(it)}
        if(old.isEmpty()){fillChips(n,true);return}
        old.forEach{it.animate().cancel();it.animate().alpha(0f).translationY(-a.d(4).toFloat()).setStartDelay(0).setDuration(130).start()}
        chips.postDelayed({if(isAttachedToWindow)fillChips(shownRating,true)},140)}
    private fun enter(v:View,delay:Long){if(reduced)return;v.alpha=0f;v.translationY=a.d(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(360).setInterpolator(PathInterpolator(.2f,.9f,.3f,1f)).start()}

    private fun onChip(chip:ReviewChip){val set=tags().toMutableList()
        if(chip.selectedNow){if(chip.label !in set)set.add(chip.label)}else set.remove(chip.label)
        val order=Reviews.marks(shownRating);putTags(set.sortedBy{order.indexOf(it).let{i->if(i<0)99 else i}});persist()}
    private fun putTags(list:List<String>){if(list.isEmpty())book.remove("reviewTags") else book.put("reviewTags",JSONArray(list))}

    private fun signature()=book.optString("review","").trim()+"\u0000"+tags().joinToString("\u0000")
    /** Saves when the words or marks really changed (then the date moves too); [force] saves a new rating anyway. */
    private fun persist(force:Boolean=false){removeCallbacks(saveTask)
        val t=if(mode==Mode.EDIT||field.hasFocus())field.text.toString().trim() else book.optString("review","").trim()
        if(t.isEmpty())book.remove("review") else book.put("review",t)
        val sig=signature();val changed=sig!=lastSig
        if(changed){lastSig=sig;if(t.isNotEmpty()||tags().isNotEmpty())book.put("reviewAt",System.currentTimeMillis()) else book.remove("reviewAt")}
        if(changed||force)a.saveBook(book){}}

    private fun fillNote(){val t=book.optString("review","").trim()
        words.text=t;words.visibility=if(t.isEmpty())GONE else VISIBLE
        tagRow.removeAllViews();tagRow.reset()
        tags().forEach{m->tagRow.addView(a.text(m,12.5f,a.fg).apply{alpha=.88f;gravity=Gravity.CENTER;setPadding(a.d(10),0,a.d(10),0);minHeight=a.d(26)
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0x0a000000 else 0x12ffffff);cornerRadius=a.d(13).toFloat();setStroke(a.d(1),if(a.light)0x14000000 else 0x1affffff)}},ViewGroup.LayoutParams(-2,a.d(26)))}
        tagRow.visibility=if(tags().isEmpty())GONE else VISIBLE
        (tagRow.layoutParams as LayoutParams).topMargin=a.d(if(t.isEmpty())8 else 12)
        val at=book.optLong("reviewAt",0L)
        date.text=if(at<=0)"" else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().let{d->d.format(DateTimeFormatter.ofPattern(if(d.year==java.time.LocalDate.now().year)"d MMMM" else "d MMMM yyyy",ru))}}

    private fun show(next:Mode,animate:Boolean){val from=mode;mode=next
        val motion=animate&&!reduced&&isAttachedToWindow&&from!=next
        if(motion)layoutMotion()
        when(next){
            Mode.EMPTY->{editor.visibility=GONE;note.visibility=GONE}
            Mode.EDIT->{field.setText(book.optString("review",""));field.setSelection(field.text.length)
                editor.visibility=VISIBLE;note.visibility=GONE
                if(motion){enter(ask,40);for(i in 0 until chips.childCount)enter(chips.getChildAt(i),90L+i*45);enter(hair,260);enter(field,300)}
                if(motion&&from==Mode.EMPTY)reveal(420)}
            Mode.NOTE->{fillNote();editor.visibility=GONE;note.visibility=VISIBLE
                if(motion)for(i in 0 until note.childCount)enter(note.getChildAt(i),60L+i*50)}}
    }

    /** Pencil or a tap on the words: back to writing, with the keyboard. */
    private fun edit(){removeCallbacks(collapseTask);show(Mode.EDIT,true)
        field.postDelayed({if(!isAttachedToWindow)return@postDelayed;field.requestFocus();field.setSelection(field.text.length)
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT)},if(reduced)0L else 200L)}

    /** Scrolls the sheet just enough for the whole card to sit above the keyboard. */
    private fun reveal(delay:Long){postDelayed({if(isAttachedToWindow)requestRectangleOnScreen(Rect(0,0,width,height+a.d(16)),false)},delay)}

    /** The book changed elsewhere (rated in the finish window, restored…): follow it without disturbing typing. */
    fun sync(){val r=book.optInt("rating")
        if(r!=shownRating){shownRating=r;starsBox.removeAllViews();stars=makeStars(r);starsBox.addView(stars,FrameLayout.LayoutParams(-1,-1))
            setVerdict(r,false);field.hint=Reviews.question(r);fillChips(r,false)
            show(if(r==0)Mode.EMPTY else if(hasContent())Mode.NOTE else Mode.EDIT,isAttachedToWindow);return}
        if(mode==Mode.NOTE)fillNote()}
}
