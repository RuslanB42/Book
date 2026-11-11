package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.*
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.JSONArray

/**
 * «Свой цвет» (approved Б + saving): a square of saturation/brightness, a rainbow of hues, the picked colour with its
 * code and «Сохранить», and «Мои цвета» — saved colours, one tap to use; a long press lets you remove them.
 * The quote recolours live while you drag ([pick] with final=false), and for good when the finger lifts (final=true).
 *
 * Every knob and ring is drawn inside its own view (the views carry the room for them), so nothing can be clipped.
 */
private const val S_SAVE=0
private const val S_SAVED=1
private const val S_DONE=2
internal class QuoteColorSheet(private val a:MainActivity,private val readerBg:Int,start:String,private val pick:(String,Boolean)->Unit){
    var onClose:(()->Unit)?=null
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private var dialog:android.app.Dialog?=null
    private lateinit var holder:View
    private val hsv=FloatArray(3).also{Color.colorToHSV((0xff shl 24) or QuoteColors.rgb(start),it)}
    private val color get()=Color.HSVToColor(hsv)
    private val hex get()=QuoteColors.hex(color)
    private lateinit var pad:SvPad;private lateinit var hue:HueBar
    private lateinit var swatch:Chip;private lateinit var code:TextView;private lateinit var save:SaveButton
    private lateinit var row:LinearLayout
    private var editing=false
    private val knobRoom=a.d(20)

    fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=DragRoot().apply{isClickable=true;setOnClickListener{close()}}
        val sheet=a.column().apply{clipToPadding=false;clipChildren=false;isClickable=true;setPadding(a.d(18),a.d(12),a.d(18),a.d(20));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(14)})
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(a.text("Свой цвет",16f,a.fg,true),LinearLayout.LayoutParams(0,-2,1f))
        head.addView(a.text("Готово",13.5f,a.fg,true).apply{setPadding(a.d(10),a.d(8),a.d(2),a.d(8));isClickable=true;a.press(this);setOnClickListener{close()}})
        sheet.addView(head,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(10)})
        // the pickers reach into the sheet's side padding, which is where their knobs have room
        pad=SvPad();sheet.addView(pad,LinearLayout.LayoutParams(-1,a.d(170)+knobRoom*2).apply{leftMargin=-knobRoom;rightMargin=-knobRoom;topMargin=-knobRoom+a.d(4)})
        hue=HueBar();sheet.addView(hue,LinearLayout.LayoutParams(-1,a.d(30)+knobRoom*2).apply{leftMargin=-knobRoom;rightMargin=-knobRoom;topMargin=-knobRoom+a.d(14)-knobRoom})
        val cur=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(7),0,a.d(6),0);background=GradientDrawable().apply{setColor(if(a.light)0x0c000000 else 0x0dffffff);cornerRadius=a.d(16).toFloat()}}
        swatch=Chip();cur.addView(swatch,LinearLayout.LayoutParams(a.d(30),a.d(30)))
        code=a.text("",14f,if(a.light)0xff55555a.toInt() else 0xffcfcfd4.toInt(),true).apply{letterSpacing=.04f};cur.addView(code,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(12)})
        save=SaveButton().apply{setOnClickListener{if(editing)setEditing(false) else store()}}
        cur.addView(save,LinearLayout.LayoutParams(a.d(124),a.d(32)))
        sheet.addView(cur,LinearLayout.LayoutParams(-1,a.d(46)).apply{topMargin=a.d(14)-knobRoom})
        val sect=a.text("",12f,a.muted,true).apply{letterSpacing=.06f};sectLabel=sect
        sheet.addView(sect,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18);bottomMargin=a.d(6);leftMargin=a.d(2)})
        row=a.line().apply{gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false}
        val rowScroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;addView(row)}
        sheet.addView(rowScroll,LinearLayout.LayoutParams(-1,a.d(52)).apply{leftMargin=-a.d(4)})
        buildRow(null)
        holder=sheet
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root.addView(sheet,lp)
        dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,readerBg)
            if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;sheet.layoutParams=lp}
        dlg.setOnKeyListener{_,c,e->if(c==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.setOnDismissListener{onClose?.invoke()}
        paint()
        dlg.show()
        if(!reduced){sheet.translationY=a.d(1200).toFloat();sheet.post{val h=sheet.height+a.d(24).toFloat();LiquidSpring{v,_->sheet.translationY=h*v}.apply{snap(1f);to(0f)}}}
    }
    private var sectLabel:TextView?=null
    private fun close(velocity:Float=0f){val d=dialog?:return;dialog=null
        if(reduced){d.dismiss();return}
        val v=holder;val left=v.height+a.d(24)-v.translationY
        v.animate().translationY(v.height+a.d(24).toFloat()).setDuration((if(velocity>0f)(left/velocity*1000f).toLong() else 240L).coerceIn(140L,260L)).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}

    /** Everything that shows the current colour. */
    private fun paint(){pad.invalidate();hue.invalidate();swatch.invalidate()
        code.text=hex
        if(!editing)save.set(if(hex in QuoteColors.saved(a))S_SAVED else S_SAVE)
        for(i in 0 until row.childCount)(row.getChildAt(i) as? Swatch)?.let{it.on=!editing&&it.hex==hex}
        (row.getChildAt(0) as? AddDot)?.invalidate()}
    private fun changed(final:Boolean){paint();pick(hex,final)}

    private fun store(){val h=hex;val list=QuoteColors.saved(a).toMutableList()
        if(h in list)return
        list.add(0,h);QuoteColors.setSaved(a,list)
        save.set(S_SAVED);buildRow(h)}
    private fun setEditing(on:Boolean){editing=on
        if(on)save.set(S_DONE) else paint()
        buildRow(null)}
    /** [fresh]: a colour just saved — it hops in. */
    private fun buildRow(fresh:String?){row.removeAllViews()
        val saved=QuoteColors.saved(a)
        sectLabel?.text="МОИ ЦВЕТА"
        if(!editing)row.addView(AddDot(),LinearLayout.LayoutParams(a.d(52),a.d(52)))
        saved.forEach{h->val s=Swatch(h);row.addView(s,LinearLayout.LayoutParams(a.d(52),a.d(52)))
            if(h==fresh&&!reduced){s.scaleX=.3f;s.scaleY=.3f;s.alpha=0f;s.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(380).setInterpolator(OvershootInterpolator(2f)).start()}}
        if(saved.isEmpty()&&editing)setEditing(false)
        for(i in 0 until row.childCount)(row.getChildAt(i) as? Swatch)?.let{it.on=!editing&&it.hex==hex}}

    /** The picked colour, beside its code. */
    private inner class Chip:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){val r=a.d(9).toFloat();p.style=Paint.Style.FILL;p.color=color;c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),r,r,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=if(a.light)0x22000000 else 0x33ffffff;c.drawRoundRect(.5f,.5f,width-.5f,height-.5f,r,r,p)}
    }
    /** «Сохранить» → «Сохранён»: the filled pill eases into a quiet one while the words trade places (the old one rises
     *  and fades, the new one rises in) and a check draws itself in front of «Сохранён». In editing it reads «Готово». */
    private inner class SaveButton:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val t=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=PolkaFonts.sans(600);textSize=13.5f*a.resources.displayMetrics.scaledDensity;textAlign=Paint.Align.CENTER}
        private var state=-1;private var from=-1;private var k=1f;private var anim:ValueAnimator?=null
        private val quiet get()=if(a.light)0x12000000 else 0x14ffffff
        init{isClickable=true;a.press(this)}
        fun set(s:Int){if(s==state)return;from=state;state=s;anim?.cancel();contentDescription=label(s)
            if(from<0||reduced||!isAttachedToWindow){k=1f;invalidate();return}
            k=0f;anim=ValueAnimator.ofFloat(0f,1f).apply{duration=420;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{k=it.animatedValue as Float;invalidate()};start()}}
        private fun label(s:Int)=when(s){S_SAVE->"Сохранить";S_SAVED->"Сохранён";else->"Готово"}
        private fun fill(s:Int)=if(s==S_SAVE)a.fg else quiet
        private fun ink(s:Int)=if(s==S_SAVE)a.bg else a.fg
        override fun onDraw(c:Canvas){if(state<0)return;val w=width.toFloat();val h=height.toFloat();val r=h/2
            val bg=if(from<0)fill(state) else android.animation.ArgbEvaluator().evaluate(k,fill(from),fill(state)) as Int
            p.style=Paint.Style.FILL;p.color=bg;c.drawRoundRect(0f,0f,w,h,r,r,p)
            val base=h/2-(t.ascent()+t.descent())/2;val lift=a.d(8).toFloat()
            c.save();c.clipRect(0f,0f,w,h)
            if(from>=0&&k<1f){val out=(1f-k*1.6f).coerceIn(0f,1f);t.color=ink(from);t.alpha=(255*out).toInt();drawLabel(c,from,w,base-lift*k,1f)}
            val inK=if(from<0)1f else ((k-.25f)/.75f).coerceIn(0f,1f);t.color=ink(state);t.alpha=(255*inK).toInt()
            drawLabel(c,state,w,base+lift*(1f-inK),if(from<0)1f else ((k-.35f)/.65f).coerceIn(0f,1f))
            c.restore();t.alpha=255}
        private val check=Path();private val part=Path();private val pm=PathMeasure()
        private fun drawLabel(c:Canvas,s:Int,w:Float,y:Float,draw:Float){val text=label(s)
            if(s!=S_SAVED){c.drawText(text,w/2,y,t);return}
            // «✓ Сохранён»: a drawn check, then the word
            val tw=t.measureText(text);val cs=a.d(12).toFloat();val gap=a.d(5).toFloat();val x0=w/2-(tw+cs+gap)/2
            val cy=y-(-t.ascent()-t.descent())/2-a.d(.5f)
            check.reset();check.moveTo(x0,cy);check.lineTo(x0+cs*.36f,cy+cs*.34f);check.lineTo(x0+cs,cy-cs*.38f)
            pm.setPath(check,false);part.reset();pm.getSegment(0f,pm.length*draw,part,true)
            p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2f);p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=0xff4fb06a.toInt();p.alpha=t.alpha;c.drawPath(part,p);p.alpha=255;p.style=Paint.Style.FILL
            t.textAlign=Paint.Align.LEFT;c.drawText(text,x0+cs+gap,y,t);t.textAlign=Paint.Align.CENTER}
    }
    /** «+»: saves the colour shown — a small dot of it inside a dashed ring. */
    private inner class AddDot:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        init{isClickable=true;contentDescription="Сохранить цвет";a.press(this);setOnClickListener{store()}}
        override fun onDraw(c:Canvas){val cx=width/2f;val cy=height/2f
            p.style=Paint.Style.STROKE;p.strokeWidth=a.d(1.5f);p.color=if(a.light)0x33000000 else 0x33ffffff;c.drawCircle(cx,cy,a.d(19).toFloat(),p)
            p.style=Paint.Style.FILL;p.color=color;c.drawCircle(cx,cy,a.d(11).toFloat(),p)
            p.color=Color.WHITE;p.strokeWidth=a.d(2f);p.style=Paint.Style.STROKE;p.strokeCap=Paint.Cap.ROUND;val k=a.d(4.5f);c.drawLine(cx-k,cy,cx+k,cy,p);c.drawLine(cx,cy-k,cx,cy+k,p);p.style=Paint.Style.FILL}
    }
    /** A saved colour. The ring and the remove badge are drawn inside the view, never past its edge. */
    private inner class Swatch(val hex:String):View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var ring=0f;private var ringAnim:ValueAnimator?=null
        var on=false;set(v){if(field==v)return;field=v;ringAnim?.cancel();if(reduced||!isAttachedToWindow){ring=if(v)1f else 0f;invalidate();return}
            ringAnim=ValueAnimator.ofFloat(ring,if(v)1f else 0f).apply{duration=220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{ring=it.animatedValue as Float;invalidate()};start()}}
        init{isClickable=true;contentDescription="Цвет $hex";a.press(this)
            setOnClickListener{if(editing)remove() else{Color.colorToHSV((0xff shl 24) or QuoteColors.rgb(hex),hsv);changed(true)}}
            setOnLongClickListener{if(!editing){if(a.prefs.optBoolean("haptics",true))performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);setEditing(true)};true}}
        private fun remove(){QuoteColors.setSaved(a,QuoteColors.saved(a).filter{it!=hex})
            if(reduced){buildRow(null);return};animate().scaleX(.2f).scaleY(.2f).alpha(0f).setDuration(180).withEndAction{buildRow(null)}.start()}
        override fun onDraw(c:Canvas){val cx=width/2f;val cy=height/2f;val r=a.d(17).toFloat()
            if(ring>0f){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2f);p.color=a.fg;p.alpha=(255*ring).toInt();c.drawCircle(cx,cy,r+a.d(4f),p);p.alpha=255}
            p.style=Paint.Style.FILL;p.color=(0xff shl 24) or QuoteColors.rgb(hex);c.drawCircle(cx,cy,r,p)
            p.style=Paint.Style.STROKE;p.strokeWidth=1f;p.color=0x2effffff;c.drawCircle(cx,cy,r,p)
            if(editing){val bx=cx+r*.72f;val by=cy-r*.72f;val br=a.d(9).toFloat()
                p.style=Paint.Style.FILL;p.color=sheetColor;c.drawCircle(bx,by,br+a.d(2f),p);p.color=if(a.light)0xffd9d9dc.toInt() else 0xff3a3a3e.toInt();c.drawCircle(bx,by,br,p)
                p.style=Paint.Style.STROKE;p.strokeWidth=a.d(1.6f);p.strokeCap=Paint.Cap.ROUND;p.color=a.fg;val k=a.d(3.2f);c.drawLine(bx-k,by-k,bx+k,by+k,p);c.drawLine(bx+k,by-k,bx-k,by+k,p);p.style=Paint.Style.FILL}}
    }

    /** Saturation (left → right) and brightness (top → bottom) of the chosen hue. */
    private inner class SvPad:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val r=a.d(18).toFloat()
        init{contentDescription="Насыщенность и яркость"}
        private fun area()=RectF(knobRoom.toFloat(),knobRoom.toFloat(),width-knobRoom.toFloat(),height-knobRoom.toFloat())
        override fun onTouchEvent(e:MotionEvent):Boolean{val b=area()
            when(e.actionMasked){MotionEvent.ACTION_DOWN->parent?.requestDisallowInterceptTouchEvent(true)}
            hsv[1]=((e.x-b.left)/b.width()).coerceIn(0f,1f);hsv[2]=1f-((e.y-b.top)/b.height()).coerceIn(0f,1f)
            changed(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL);return true}
        override fun onDraw(c:Canvas){val b=area();val pure=Color.HSVToColor(floatArrayOf(hsv[0],1f,1f))
            c.save();c.clipPath(Path().apply{addRoundRect(b,r,r,Path.Direction.CW)})
            p.shader=LinearGradient(b.left,0f,b.right,0f,Color.WHITE,pure,Shader.TileMode.CLAMP);c.drawRect(b,p)
            p.shader=LinearGradient(0f,b.top,0f,b.bottom,0,Color.BLACK,Shader.TileMode.CLAMP);c.drawRect(b,p);p.shader=null;c.restore()
            knob(c,p,b.left+b.width()*hsv[1],b.top+b.height()*(1-hsv[2]),a.d(13).toFloat(),color)}
    }
    /** The rainbow of hues. */
    private inner class HueBar:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        private val stops=IntArray(13){Color.HSVToColor(floatArrayOf(it*30f%360f,1f,1f))}
        init{contentDescription="Оттенок"}
        private fun area()=RectF(knobRoom.toFloat(),knobRoom.toFloat(),width-knobRoom.toFloat(),height-knobRoom.toFloat())
        override fun onTouchEvent(e:MotionEvent):Boolean{val b=area()
            when(e.actionMasked){MotionEvent.ACTION_DOWN->parent?.requestDisallowInterceptTouchEvent(true)}
            hsv[0]=(((e.x-b.left)/b.width()).coerceIn(0f,1f)*360f).coerceAtMost(359.9f)
            if(hsv[1]<.05f&&hsv[2]>.9f){hsv[1]=.7f}   // from white, a hue change should show a colour
            changed(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL);return true}
        override fun onDraw(c:Canvas){val b=area();val r=b.height()/2
            p.shader=LinearGradient(b.left,0f,b.right,0f,stops,null,Shader.TileMode.CLAMP);c.drawRoundRect(b,r,r,p);p.shader=null
            knob(c,p,b.left+b.width()*hsv[0]/360f,b.centerY(),r,Color.HSVToColor(floatArrayOf(hsv[0],1f,1f)))}
    }
    private fun knob(c:Canvas,p:Paint,x:Float,y:Float,r:Float,fill:Int){
        p.style=Paint.Style.FILL;p.color=0x40000000;c.drawCircle(x,y+a.d(1.5f),r+a.d(3f),p)
        p.color=Color.WHITE;c.drawCircle(x,y,r+a.d(3f),p);p.color=fill;c.drawCircle(x,y,r,p)}

    /** Pull the panel down to close it (the pickers keep their own drags). */
    private inner class DragRoot:FrameLayout(a){
        private var y0=0f;private var x0=0f;private var dragging=false;private var vt:VelocityTracker?=null
        private fun inSheet(x:Float,y:Float):Boolean{val r=Rect();holder.getGlobalVisibleRect(r);return r.contains(x.toInt(),y.toInt())}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{x0=e.rawX;y0=e.rawY;dragging=false;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e)}
            MotionEvent.ACTION_MOVE->{vt?.addMovement(e);val dy=e.rawY-y0;val dx=e.rawX-x0
                if(!dragging&&inSheet(x0,y0)&&dy>a.d(10)&&dy>kotlin.math.abs(dx)*1.4f){dragging=true;y0=e.rawY;return true}}};return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);vt?.addMovement(e)
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->holder.translationY=(e.rawY-y0).coerceAtLeast(0f)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{vt?.computeCurrentVelocity(1000);val v=vt?.yVelocity?:0f;dragging=false
                    if(holder.translationY>holder.height*.25f||v>a.d(900))close(v.coerceAtLeast(0f))
                    else holder.animate().translationY(0f).setDuration(320).setInterpolator(OvershootInterpolator(.8f)).start()}};return true}
    }
    private fun MainActivity.d(v:Float)=v*resources.displayMetrics.density
}
