package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/**
 * The «⋯» menus (approved «Меню «Ещё» А: кнопка раскрывается в меню»), in the «＋» menu's way: the round button itself
 * grows into the menu card from its own corner, with a little spring, while its dots blur away; the page dims, and the
 * rows come out of a blur one after another. Closing runs it all back into the button. A destructive row comes last,
 * red, after a thin line.
 */
internal class ButtonMenu(private val a:MainActivity,private val anchor:View,private val items:List<Item>){
    class Item(val icon:String,val label:String,val destructive:Boolean=false,val act:()->Unit)
    private var view:MenuView?=null
    private var clock:ValueAnimator?=null
    private var closing=false
    private val reduced get()=a.prefs.str("motion")=="reduced"

    fun open(){
        if(anchor.width==0||!anchor.isShown||items.isEmpty())return
        a.buttonMenu?.close();a.buttonMenu=this
        val v=MenuView();view=v
        a.root.addView(v,FrameLayout.LayoutParams(-1,-1))
        anchor.visibility=View.INVISIBLE
        if(reduced){v.pose(1f,1f,FloatArray(v.rows.size){1f},1f);return}
        val spring=Spring.of(.22,420.0);val total=max(spring.duration,620L)
        clock=ValueAnimator.ofFloat(0f,total.toFloat()).apply{duration=total;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                val t=spring.interpolator.getInterpolation((ms/spring.duration).coerceIn(0f,1f))
                val rows=FloatArray(v.rows.size){i->MotionSpec.enter.getInterpolation(((ms-150f-45f*i)/300f).coerceIn(0f,1f))}
                v.pose(t,MotionSpec.enter.getInterpolation((ms/260f).coerceIn(0f,1f)),rows,MotionSpec.exit.getInterpolation((ms/140f).coerceIn(0f,1f)))}
            start()}
    }

    /** [then]: what was chosen, started while the menu is still going back into the button. */
    fun close(then:(()->Unit)?=null){if(closing)return;closing=true
        if(a.buttonMenu===this)a.buttonMenu=null
        val v=view?:run{then?.invoke();return}
        clock?.cancel()
        fun done(){(v.parent as? ViewGroup)?.removeView(v);anchor.visibility=View.VISIBLE}
        if(reduced){done();then?.invoke();return}
        then?.let{go->a.root.postDelayed({go()},170)}
        val t0=v.t;val dim0=v.dim;val rows0=v.rowsShown.copyOf();val dots0=v.dotsGone
        clock=ValueAnimator.ofFloat(0f,400f).apply{duration=Motion.EMPHASIS;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                val out=MotionSpec.exit.getInterpolation((ms/140f).coerceIn(0f,1f))
                val back=MotionSpec.move.getInterpolation(((ms-40f)/340f).coerceIn(0f,1f))
                val dots=MotionSpec.enter.getInterpolation(((ms-200f)/180f).coerceIn(0f,1f))
                v.pose(t0*(1f-back),dim0*(1f-back),FloatArray(rows0.size){i->rows0[i]*(1f-out)},dots0*(1f-dots))}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){done()}})
            start()}
    }

    private inner class MenuView:FrameLayout(a){
        // the button (where it starts) and the menu (where it ends), in the root's coordinates
        private val from:RectF
        private val to:RectF
        private val r0:Float;private val r1=a.d(26).toFloat()
        private val light=a.light
        private val menuColor=Ui.menu(light)
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path();private val now=RectF()
        val rows=ArrayList<View>()
        private val snapshot=ImageView(a)
        private val column=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(0,a.d(6),0,a.d(6))}
        private val lines=ArrayList<Pair<View,Int>>()
        // the corner of the button the menu grows from: the one nearest the middle of the screen stays put
        private val right:Boolean;private val down:Boolean
        var t=0f;var dim=0f;var rowsShown=FloatArray(0);var dotsGone=0f

        init{
            setWillNotDraw(false);clipChildren=false;isClickable=true
            // above everything on the page, sheets that stand raised included (no shadow of its own: it draws its shade)
            outlineProvider=null;elevation=a.d(48).toFloat()
            val at=IntArray(2);val base=IntArray(2);anchor.getLocationInWindow(at);a.root.getLocationInWindow(base)
            val l=(at[0]-base[0]).toFloat();val tp=(at[1]-base[1]).toFloat()
            from=RectF(l,tp,l+anchor.width,tp+anchor.height);r0=min(from.width(),from.height())/2f
            items.forEachIndexed{i,it->if(it.destructive&&i>0&&!items[i-1].destructive)divider();row(it)}
            rowsShown=FloatArray(rows.size)
            val w=min(a.d(262).toFloat(),a.root.width-a.d(24).toFloat())
            val h=a.d(6)*2f+Size.row(a,52)*rows.size+lines.size*a.d(13)
            right=from.centerX()>a.root.width/2f;down=from.centerY()<a.root.height/2f
            val x0=if(right)from.right-w else from.left;val y0=if(down)from.top else from.bottom-h
            to=RectF(x0,y0,x0+w,y0+h)
            // the button as it is now, to blur away inside the growing card
            runCatching{Bitmap.createBitmap(anchor.width,anchor.height,Bitmap.Config.ARGB_8888)}.getOrNull()?.let{bmp->anchor.draw(Canvas(bmp));snapshot.setImageBitmap(bmp)}
            snapshot.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(snapshot,LayoutParams(anchor.width,anchor.height).apply{leftMargin=from.left.toInt();topMargin=from.top.toInt()})
            addView(column,LayoutParams(to.width().toInt(),to.height().toInt()).apply{leftMargin=to.left.toInt();topMargin=to.top.toInt()})
        }

        private fun divider(){val v=View(a).apply{setBackgroundColor(ColorUtils.setAlphaComponent(a.fg,if(light)18 else 20))}
            lines.add(v to rows.size);column.addView(v,LinearLayout.LayoutParams(-1,a.d(1)).apply{setMargins(a.d(16),a.d(6),a.d(16),a.d(6))})}

        private fun row(item:Item){
            val ink=if(item.destructive)Ui.danger(a.light) else a.fg
            val bg=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(Radius.R20).toFloat();setColor(Color.TRANSPARENT)}
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(16),0);isClickable=true;isFocusable=true;contentDescription=item.label;background=bg}
            r.addView(IconView(a,item.icon,ink),LinearLayout.LayoutParams(a.d(24),a.d(24)))
            r.addView(a.text(item.label,Type.BODY,ink).apply{typeface=PolkaFonts.sans(500);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=a.d(14)})
            // under the finger the row lights up and swells a little, as in the «＋» menu
            var lit=0f;var anim:ValueAnimator?=null
            fun glow(on:Boolean){anim?.cancel();anim=ValueAnimator.ofFloat(lit,if(on)1f else 0f).apply{duration=if(on)140 else 320;interpolator=MotionSpec.enter
                addUpdateListener{lit=it.animatedValue as Float;bg.setColor(ColorUtils.setAlphaComponent(ink,((if(light)20 else 38)*lit).roundToInt()));val s=1f+.025f*lit;r.scaleX=s;r.scaleY=s};start()}}
            r.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->glow(true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->glow(false)};false}
            r.setOnClickListener{if(!closing)close(item.act)}
            rows.add(r);column.addView(r,LinearLayout.LayoutParams(-1,Size.row(a,52)).apply{setMargins(a.d(6),0,a.d(6),0)})
        }

        /** [morph]: button 0 → menu 1 (a spring passes 1 a little); [shade]: the page dims; [shown]: each row out of its
         *  blur; [dots]: the button's dots gone. */
        fun pose(morph:Float,shade:Float,shown:FloatArray,dots:Float){
            t=morph;dim=shade;rowsShown=shown;dotsGone=dots
            shown.forEachIndexed{i,k->val r=rows[i];r.alpha=k;r.translationY=a.d(8)*(1f-k);blur(r,a.d(8)*(1f-k))}
            lines.forEach{(v,next)->v.alpha=shown.getOrElse(next){1f}}
            snapshot.alpha=1f-dots;val s=1f-.08f*dots;snapshot.scaleX=s;snapshot.scaleY=s;blur(snapshot,a.d(10)*dots)
            invalidate()}

        private fun blur(v:View,radius:Float){if(android.os.Build.VERSION.SDK_INT<31)return
            v.setRenderEffect(if(radius<.5f)null else RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.DECAL))}

        private fun current():RectF{val k=t;val w=from.width()+(to.width()-from.width())*k;val h=from.height()+(to.height()-from.height())*k
            val x=if(right)from.right-w else from.left;val y=if(down)from.top else from.bottom-h
            now.set(x,y,x+w,y+h);return now}

        override fun onDraw(c:Canvas){
            if(dim>0f)c.drawColor(ColorUtils.setAlphaComponent(if(light)0xfff2f2f0.toInt() else Color.BLACK,((if(light)102 else 153)*dim).roundToInt()))
            val r=current();val k=t.coerceIn(0f,1f);val radius=r0+(r1-r0)*k
            if(k>0f)FloatShade.draw(c,a,r,radius,light,(255*k).roundToInt(),lift=Lift.OVER,bakeAs=to)
            // the card takes its colour as it leaves the button (whose own ground is in the picture blurring away)
            fill.color=ColorUtils.setAlphaComponent(menuColor,(255*min(1f,k*3f)).roundToInt());c.drawRoundRect(r,radius,radius,fill)}

        override fun dispatchDraw(c:Canvas){val r=current();val k=t.coerceIn(0f,1f);val radius=r0+(r1-r0)*k
            path.rewind();path.addRoundRect(r,radius,radius,Path.Direction.CW);c.save();c.clipPath(path);super.dispatchDraw(c);c.restore()}

        // a tap anywhere off the menu closes it
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP&&!to.contains(e.x,e.y))close();return true}
        override fun onDetachedFromWindow(){if(view===this){clock?.cancel();anchor.visibility=View.VISIBLE;if(a.buttonMenu===this@ButtonMenu)a.buttonMenu=null};super.onDetachedFromWindow()}
    }
}
