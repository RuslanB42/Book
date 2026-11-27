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

/** Quick marks and the note's question offered for each rating. */
internal object Reviews {
    /** The whole stars of a rating in half stars (4,5 reads as a four that is better than four). */
    private fun whole(h:Int)=h/2
    fun marks(h:Int)=when(whole(h)){
        5->listOf("Не оторваться","Перечитаю","Красивый язык","Сильный финал")
        4->listOf("Не оторваться","Заставила думать","Красивый язык","Перечитаю")
        3->listOf("Интересная идея","Легко читается","Местами затянута","Слабее ожиданий")
        in 0..2->if(h>0)listOf("Скучно","Не мой жанр","Слабый финал","Тяжело читалась") else emptyList()
        else->emptyList()}
    fun question(h:Int)=when{h>=10->"Чем она так хороша?";h>=8->"Что понравилось?";h>=6->"Чего не хватило?";else->"Что не так?"}
}

/**
 * Wraps its children into rows. When a child moves to another row it glides there instead of jumping,
 * and the height follows on a short ease, so chips that grow or shrink never make the card snap.
 */
internal class FlowRow(c:Context,private val hGap:Int,private val vGap:Int,private val glideAll:Boolean=false,private val stretch:Boolean=false,private val smooth:()->Boolean):ViewGroup(c){
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
        // stretched: each line's free width goes evenly to its pills, so every line reaches the right edge
        if(stretch){val line=ArrayList<View>();var x=0
            fun widen(){val free=maxW-x+hGap;if(line.isEmpty()||free<=0)return;val each=free/line.size
                line.forEach{ch->ch.measure(MeasureSpec.makeMeasureSpec(ch.measuredWidth+each,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(ch.measuredHeight,MeasureSpec.EXACTLY))}}
            for(i in 0 until childCount){val ch=getChildAt(i);if(ch.visibility==GONE)continue
                if(x>0&&x+ch.measuredWidth>maxW){widen();line.clear();x=0}
                line.add(ch);x+=ch.measuredWidth+hGap}
            widen()}
        val h=place(maxW){_,_,_->}
        if(shownH<0||!smooth()||!isLaidOut){hAnim?.cancel();shownH=h;targetH=h}
        else if(h!=targetH){targetH=h;val from=shownH;hAnim?.cancel()
            hAnim=ValueAnimator.ofInt(from,h).apply{duration=Motion.STANDARD;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shownH=it.animatedValue as Int;requestLayout()};start()}}
        setMeasuredDimension(maxW,shownH)}
    override fun onLayout(changed:Boolean,l:Int,t:Int,r:Int,b:Int){val glide=smooth()&&isLaidOut
        place(r-l){ch,x,y->val old=spots[ch]
            ch.layout(x,y,x+ch.measuredWidth,y+ch.measuredHeight)
            if(glide&&old!=null&&(old.y!=y||(glideAll&&old.x!=x))){ // moved to another row: start from where it was
                ch.animate().cancel();ch.translationX+=(old.x-x).toFloat();ch.translationY+=(old.y-y).toFloat()
                ch.animate().translationX(0f).translationY(0f).setStartDelay(0).setDuration(Motion.EMPHASIS).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}
            spots[ch]=Point(x,y)}}
    override fun onViewRemoved(child:View){super.onViewRemoved(child);spots.remove(child)}
    fun reset(){hAnim?.cancel();shownH=-1;targetH=-1;spots.clear()}
}

/** A quick mark. Choosing it fills it with the text colour; its size never changes, so nothing around it moves. */
internal class ReviewChip(private val a:MainActivity,val label:String,selected:Boolean,private val toggled:(ReviewChip)->Unit):View(a){
    var selectedNow=selected;private set
    private var p=if(selected)1f else 0f
    private var anim:ValueAnimator?=null
    private val text=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(500);textSize=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,14f,a.resources.displayMetrics)}
    private val fill=Paint(Paint.ANTI_ALIAS_FLAG)
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.STROKE;strokeWidth=a.d(1).toFloat()}
    private val rect=RectF()
    private val w=text.measureText(label)
    private val restFill get()=Ui.fill(a.light)
    private val restLine get()=Ui.press(a.light)
    private val onText get()=if(a.light)Color.WHITE else 0xff1c1c1e.toInt()
    private val reduced get()=a.prefs.str("motion")=="reduced"
    init{isClickable=true;isFocusable=true;contentDescription=label;setOnClickListener{toggle()};a.press(this)}
    private fun mix(c1:Int,c2:Int,t:Float)=ArgbEvaluator().evaluate(t.coerceIn(0f,1f),c1,c2) as Int
    override fun onMeasure(ws:Int,hs:Int)=setMeasuredDimension((w+a.d(28)).toInt()+1,a.d(34))
    fun toggle(){selectedNow=!selectedNow;toggled(this)
        haptic(a,Haptic.SELECT)
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        val to=if(selectedNow)1f else 0f;anim?.cancel()
        if(reduced||!isAttachedToWindow){p=to;invalidate();return}
        anim=ValueAnimator.ofFloat(p,to).apply{duration=Motion.STANDARD;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{p=it.animatedValue as Float;invalidate()};start()}}
    override fun onDraw(c:Canvas){val h=height.toFloat();val r=h/2
        rect.set(0f,0f,width.toFloat(),h)
        fill.color=mix(restFill,a.fg,p);c.drawRoundRect(rect,r,r,fill)
        if(p<1f){line.color=restLine;line.alpha=(Color.alpha(restLine)*(1f-p)).toInt();rect.inset(line.strokeWidth/2,line.strokeWidth/2);c.drawRoundRect(rect,r,r,line)}
        text.color=mix(a.fg,onText,p)
        c.drawText(label,(width-w)/2f,h/2-(text.fontMetrics.ascent+text.fontMetrics.descent)/2,text)}
    override fun onInitializeAccessibilityNodeInfo(info:android.view.accessibility.AccessibilityNodeInfo){super.onInitializeAccessibilityNodeInfo(info);info.className="android.widget.CheckBox";info.isCheckable=true;info.isChecked=selectedNow}
}

/**
 * «Ваша оценка» in the book card: the stars in the middle; once a book is rated, four quick marks and a short note
 * open up below them. While nobody is typing, the review rests as a small
 * note: your words, the marks, the date and a pencil. Everything saves by itself.
 */
internal class ReviewBlock(private val a:MainActivity,private val book:JSONObject):LinearLayout(a){
    private enum class Mode{EMPTY,EDIT,NOTE}
    private var mode=Mode.EMPTY
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val ru=Locale("ru")
    private var stars=makeStars(Rating.halves(book))
    private val starsBox=FrameLayout(a).apply{clipChildren=false;clipToPadding=false}
    // editor
    private val editor=a.column().apply{clipChildren=false;clipToPadding=false}
    private val ask=a.text("Какой она была",Type.CAPTION,a.muted)
    private val chips=FlowRow(a,a.d(8),a.d(8)){false}
    private val hair=View(a).apply{setBackgroundColor(Ui.line(a.light))}
    private val field=EditText(a)
    // note
    private val note=a.column().apply{clipChildren=false;clipToPadding=false}
    private val words=a.text("",Type.TITLE,serif=true).apply{cssLeading(1.55f);isClickable=true}
    private val tagRow=FlowRow(a,a.d(6),a.d(6)){false}
    private val date=a.text("",Type.CAPTION,a.muted)
    private val pen=FrameLayout(a)
    private var shownRating=Rating.halves(book)
    private var lastSig=""
    private val saveTask=Runnable{persist()}
    private val collapseTask=Runnable{if(!field.hasFocus()&&hasContent())show(Mode.NOTE,true)}

    init{orientation=VERTICAL;clipChildren=false;clipToPadding=false
        a.floatCard(this,24);setPadding(a.d(16),a.d(10),a.d(16),a.d(10));clipToOutline=true
        // the stars in the middle, wide apart, no words
        starsBox.addView(stars,FrameLayout.LayoutParams(-1,-1))
        addView(starsBox,LayoutParams(-1,a.d(52)))
        // editor
        editor.addView(ask,LayoutParams(-1,-2).apply{topMargin=a.d(6);bottomMargin=a.d(10)})
        editor.addView(chips,LayoutParams(-1,-2))
        editor.addView(hair,LayoutParams(-1,1).apply{topMargin=a.d(14)})
        field.apply{background=null;setPadding(0,a.d(12),0,a.d(2));textSize=Type.TITLE;typeface=PolkaFonts.serif(a,false);setTextColor(a.fg);setHintTextColor((a.muted and 0x00ffffff) or 0x88000000.toInt())
            includeFontPadding=false;cssLeading(1.55f);minHeight=a.d(46);gravity=Gravity.TOP or Gravity.START
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI;filters=arrayOf(InputFilter.LengthFilter(600))
            setText(book.optString("review",""))
            addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){};override fun onTextChanged(s:CharSequence?,x:Int,y:Int,z:Int){}
                override fun afterTextChanged(s:Editable?){removeCallbacks(saveTask);postDelayed(saveTask,450)}})
            setOnFocusChangeListener{_,has->removeCallbacks(collapseTask)
                if(has)reveal(420)else{persist();postDelayed(collapseTask,240)}}}
        editor.addView(field,LayoutParams(-1,-2))
        addView(editor,LayoutParams(-1,-2))
        // note
        words.setOnClickListener{edit()}
        note.addView(words,LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        note.addView(tagRow,LayoutParams(-1,-2).apply{topMargin=a.d(12)})
        val foot=FrameLayout(a)
        foot.addView(date,FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL))
        pen.background=a.shape(Ui.fill(a.light),Radius.R16);pen.contentDescription="Изменить отзыв";pen.isClickable=true;pen.isFocusable=true;a.press(pen);pen.setOnClickListener{edit()}
        pen.addView(IconView(a,"pen",a.fg),FrameLayout.LayoutParams(a.d(16),a.d(16),Gravity.CENTER))
        foot.addView(pen,FrameLayout.LayoutParams(a.d(32),a.d(32),Gravity.END or Gravity.CENTER_VERTICAL).apply{rightMargin=-a.d(4)})
        note.addView(foot,LayoutParams(-1,a.d(32)).apply{topMargin=a.d(12);bottomMargin=a.d(4)})
        addView(note,LayoutParams(-1,-2))
        // taps on the marks must not take the keyboard away from the note being written
        addOnAttachStateChangeListener(object:OnAttachStateChangeListener{override fun onViewAttachedToWindow(v:View){a.focusKeepers.add(chips)}
            override fun onViewDetachedFromWindow(v:View){a.focusKeepers.remove(chips);removeCallbacks(saveTask);removeCallbacks(collapseTask);persist()}})
        lastSig=signature()
        val r=Rating.halves(book)
        fillChips(r,false);field.hint=Reviews.question(r)
        show(if(r==0)Mode.EMPTY else if(hasContent())Mode.NOTE else Mode.EDIT,false)
    }

    private fun makeStars(r:Int)=NativeRating(a,r,true,32f,22f){n->rate(n)}
    private fun tags():List<String> =book.optJSONArray("reviewTags")?.let{j->(0 until j.length()).map{j.optString(it)}.filter{it.isNotBlank()}}?:emptyList()
    private fun text()=if(mode==Mode.EDIT||field.hasFocus())field.text.toString().trim() else book.optString("review","").trim()
    private fun hasContent()=text().isNotEmpty()||tags().isNotEmpty()

    private fun rate(n:Int){val old=shownRating;shownRating=n
        Rating.put(book,n);persist(true)
        field.hint=if(n>0)Reviews.question(n) else ""
        if(n==0){show(Mode.EMPTY,true);return}
        // marks the new rating also offers stay chosen; the rest fall away
        val keep=tags().filter{it in Reviews.marks(n)};if(keep!=tags()){putTags(keep);persist(true)}
        if(Reviews.marks(old)!=Reviews.marks(n)){if(mode==Mode.EDIT)swapChips(n) else fillChips(n,false)}
        if(mode!=Mode.EDIT)show(Mode.EDIT,true)
    }

    private fun fillChips(n:Int,stagger:Boolean){chips.removeAllViews();if(!stagger)chips.reset();val chosen=tags()
        Reviews.marks(n).forEachIndexed{i,m->val c=ReviewChip(a,m,m in chosen){chip->onChip(chip)}
            chips.addView(c);if(stagger)enter(c,90L+i*45)}}
    /** A new rating offers other marks: the old ones fade out, the new ones rise in one by one. */
    private fun swapChips(n:Int){if(reduced||!isAttachedToWindow){fillChips(n,false);return}
        val old=(0 until chips.childCount).map{chips.getChildAt(it)}
        if(old.isEmpty()){fillChips(n,true);return}
        old.forEach{it.animate().cancel();it.animate().alpha(0f).setStartDelay(0).setDuration(Motion.FAST).start()}
        chips.postDelayed({if(!isAttachedToWindow)return@postDelayed
            morph(!reduced){fillChips(shownRating,false)}
            for(i in 0 until chips.childCount){val c=chips.getChildAt(i);c.alpha=0f;c.animate().alpha(1f).setStartDelay(40L+i*30).setDuration(Motion.STANDARD).start()}},130)}
    private fun enter(v:View,delay:Long){if(reduced)return;v.alpha=0f;v.translationY=a.d(8).toFloat()
        v.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(Motion.EMPHASIS).setInterpolator(PathInterpolator(.2f,.9f,.3f,1f)).start()}

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
        tags().forEach{m->tagRow.addView(a.text(m,Type.SECONDARY,a.fg).apply{alpha=.88f;gravity=Gravity.CENTER;setPadding(a.d(10),0,a.d(10),0);minHeight=a.d(26)
            background=android.graphics.drawable.GradientDrawable().apply{setColor(Ui.fill(a.light));cornerRadius=a.d(13).toFloat();setStroke(a.d(1),Ui.press(a.light))}},ViewGroup.LayoutParams(-2,a.d(26)))}
        tagRow.visibility=if(tags().isEmpty())GONE else VISIBLE
        (tagRow.layoutParams as LayoutParams).topMargin=a.d(if(t.isEmpty())8 else 12)
        val at=book.optLong("reviewAt",0L)
        date.text=if(at<=0)"" else Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().let{d->d.format(DateTimeFormatter.ofPattern(if(d.year==java.time.LocalDate.now().year)"d MMMM" else "d MMMM yyyy",ru))}}

    private var hAnim:ValueAnimator?=null
    /**
     * Changes what the card shows in one smooth move: the card's height glides from the old to the new size
     * (everything below follows it), what leaves fades out inside the clipped card, what comes fades in.
     */
    private fun morph(motion:Boolean,change:()->Unit){
        if(!motion||!isLaidOut||width==0){hAnim?.cancel();layoutParams?.let{it.height=LayoutParams.WRAP_CONTENT;layoutParams=it};change();return}
        val before=listOf(editor,note).filter{it.visibility==VISIBLE}
        val h0=height;hAnim?.cancel()
        change()
        val after=listOf(editor,note).filter{it.visibility==VISIBLE}
        measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));val h1=measuredHeight
        val leaving=before-after.toSet();val coming=after-before.toSet()
        leaving.forEach{v->v.visibility=VISIBLE;v.animate().cancel();v.animate().alpha(0f).setStartDelay(0).setDuration(Motion.FAST).withEndAction{v.visibility=GONE;v.alpha=1f}.start()}
        coming.forEach{v->v.animate().cancel();v.alpha=0f;v.animate().alpha(1f).setStartDelay(if(leaving.isEmpty())60 else 150).setDuration(Motion.STANDARD).start()}
        val lp=layoutParams;lp.height=h0;layoutParams=lp
        hAnim=ValueAnimator.ofInt(h0,h1).apply{duration=Motion.EMPHASIS;interpolator=PathInterpolator(.2f,0f,0f,1f)
            addUpdateListener{val l=layoutParams;l.height=it.animatedValue as Int;layoutParams=l}
            addListener(object:AnimatorListenerAdapter(){var cancelled=false
                override fun onAnimationCancel(x:Animator){cancelled=true}
                override fun onAnimationEnd(x:Animator){if(cancelled)return;leaving.forEach{it.animate().cancel();it.visibility=GONE;it.alpha=1f};val l=layoutParams;l.height=LayoutParams.WRAP_CONTENT;layoutParams=l}});start()}
    }
    private fun show(next:Mode,animate:Boolean){val from=mode;mode=next
        val motion=animate&&!reduced&&isAttachedToWindow&&from!=next
        morph(motion){when(next){
            Mode.EMPTY->{editor.visibility=GONE;note.visibility=GONE}
            Mode.EDIT->{field.setText(book.optString("review",""));field.setSelection(field.text.length);editor.visibility=VISIBLE;note.visibility=GONE}
            Mode.NOTE->{fillNote();editor.visibility=GONE;note.visibility=VISIBLE}}}
        if(motion&&next==Mode.EDIT&&from==Mode.EMPTY)reveal(420)
    }

    /** Pencil or a tap on the words: back to writing, with the keyboard. */
    private fun edit(){removeCallbacks(collapseTask);show(Mode.EDIT,true)
        field.postDelayed({if(!isAttachedToWindow)return@postDelayed;field.requestFocus();field.setSelection(field.text.length)
            (a.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field,InputMethodManager.SHOW_IMPLICIT)},if(reduced)0L else 200L)}

    /** Scrolls the sheet just enough for the whole card to sit above the keyboard. */
    // the line being typed rides the keyboard by itself (ImeKeeper); this only brings the rest of the card up once it has settled
    private fun reveal(delay:Long){postDelayed({a.revealAboveKeyboard(this,0)},delay)}

    /** The book changed elsewhere (rated in the finish window, restored…): follow it without disturbing typing. */
    fun sync(){val r=Rating.halves(book)
        if(r!=shownRating){shownRating=r;starsBox.removeView(stars);stars=makeStars(r);starsBox.addView(stars,0,FrameLayout.LayoutParams(-1,-1))
            field.hint=Reviews.question(r);fillChips(r,false)
            show(if(r==0)Mode.EMPTY else if(hasContent())Mode.NOTE else Mode.EDIT,isAttachedToWindow);return}
        if(mode==Mode.NOTE)fillNote()}
}
