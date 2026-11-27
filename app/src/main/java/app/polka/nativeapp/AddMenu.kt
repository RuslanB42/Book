package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/**
 * The «＋» menu (approved «А: панель становится меню»): the navigation bar under the thumb grows up into the menu.
 * Its icons blur away, the bar stretches into a card with a little spring, and the rows come out of a blur one after
 * another; the «＋» darkens into the card's colour and turns into a cross. Closing runs it all back into the bar.
 * Two ways to bring books in sit together («Найти на телефоне», «Выбрать файл»), the new shelf after a thin line.
 */
internal class AddMenu(private val a:MainActivity){
    private var view:MenuView?=null
    private var clock:ValueAnimator?=null
    private var closing=false
    private val reduced get()=a.prefs.str("motion")=="reduced"

    fun open(){
        val nav=a.nativeNavigation?:return;val fab=a.addButton?:return
        if(nav.width==0||!nav.isShown)return
        a.addMenu=this
        val v=MenuView(nav,fab);view=v
        a.root.addView(v,a.root.indexOfChild(fab),FrameLayout.LayoutParams(-1,-1))
        nav.visibility=View.INVISIBLE
        if(reduced){v.pose(1f,1f,FloatArray(v.rows.size){1f},0f,1f);return}
        val spring=Spring.of(.22,420.0);val total=max(spring.duration,620L)
        clock=ValueAnimator.ofFloat(0f,total.toFloat()).apply{duration=total;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                val t=spring.interpolator.getInterpolation((ms/spring.duration).coerceIn(0f,1f))
                val rows=FloatArray(v.rows.size){i->MotionSpec.enter.getInterpolation(((ms-150f-45f*i)/300f).coerceIn(0f,1f))}
                v.pose(t,MotionSpec.enter.getInterpolation((ms/260f).coerceIn(0f,1f)),rows,MotionSpec.exit.getInterpolation((ms/140f).coerceIn(0f,1f)),
                    MotionSpec.enter.getInterpolation((ms/300f).coerceIn(0f,1f)))}
            start()}
    }

    /** [then]: what was chosen, run once the menu has gone back into the bar. */
    fun close(then:(()->Unit)?=null){if(closing)return;closing=true
        if(a.addMenu===this)a.addMenu=null
        val v=view?:run{then?.invoke();return}
        clock?.cancel()
        fun done(){(v.parent as? ViewGroup)?.removeView(v);a.nativeNavigation?.let{if(it.visibility==View.INVISIBLE&&a.shelf==null&&a.auto==null)it.visibility=View.VISIBLE}}
        if(reduced){v.pose(0f,0f,FloatArray(v.rows.size),1f,0f);done();then?.invoke();return}
        // what was chosen starts while the menu is still going back (a picker or a sheet comes over it)
        then?.let{go->a.root.postDelayed({go()},170)}
        // from wherever the opening had got to
        val t0=v.t;val dim0=v.dim;val rows0=v.rowsShown.copyOf();val bar0=v.barGone;val cross0=v.cross
        clock=ValueAnimator.ofFloat(0f,400f).apply{duration=Motion.EMPHASIS;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                val out=MotionSpec.exit.getInterpolation((ms/140f).coerceIn(0f,1f))
                val back=MotionSpec.move.getInterpolation(((ms-40f)/340f).coerceIn(0f,1f))
                val bar=MotionSpec.enter.getInterpolation(((ms-200f)/180f).coerceIn(0f,1f))
                v.pose(t0*(1f-back),dim0*(1f-back),FloatArray(rows0.size){i->rows0[i]*(1f-out)},bar0*(1f-bar),cross0*(1f-MotionSpec.move.getInterpolation((ms/300f).coerceIn(0f,1f))))}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){done()}})
            start()}
    }

    private inner class MenuView(private val nav:View,private val fab:View):FrameLayout(a){
        // where it starts (the bar) and where it ends (the menu), in this view's coordinates (the root's)
        private val from=RectF(nav.left.toFloat(),nav.top.toFloat(),nav.right.toFloat(),nav.bottom.toFloat())
        private val to:RectF
        private val r0=from.height()/2f;private val r1=a.d(26).toFloat()
        private val light=a.light
        private val barColor=Ui.card(light)
        private val menuColor=Ui.menu(light)
        private val fabFill=a.fg;private val fabInk=a.bg
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path();private val now=RectF()
        val rows=ArrayList<View>()
        private val snapshot=ImageView(a)
        private val column=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(a.d(6),a.d(6),a.d(6),a.d(6))}
        var t=0f;var dim=0f;var rowsShown=FloatArray(0);var barGone=0f;var cross=0f

        init{
            setWillNotDraw(false);clipChildren=false;importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            val w=min(a.d(300).toFloat(),from.width());val h=a.d(6)*2f+Size.row(a,54)*3f+a.d(13)
            to=RectF(from.left,from.bottom-h,from.left+w,from.bottom)
            // the bar's own icons, as they are now, to blur away inside the growing card
            runCatching{Bitmap.createBitmap(nav.width,nav.height,Bitmap.Config.ARGB_8888)}.getOrNull()?.let{bmp->val c=Canvas(bmp)
                (nav as? PolkaNavigation)?.paintContent(c)
                snapshot.setImageBitmap(bmp)}
            snapshot.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(snapshot,LayoutParams(nav.width,nav.height).apply{leftMargin=from.left.toInt();topMargin=from.top.toInt()})
            row("fsearch","Найти на телефоне"){a.findPhoneBooks()}
            row("download","Выбрать файл"){a.pickBooks()}
            column.addView(View(a).apply{setBackgroundColor(ColorUtils.setAlphaComponent(a.fg,if(light)18 else 20))},LinearLayout.LayoutParams(-1,a.d(1)).apply{setMargins(a.d(16),a.d(6),a.d(16),a.d(6))})
            row("shelves","Новая полка"){a.createShelf()}
            rowsShown=FloatArray(rows.size)
            addView(column,LayoutParams(to.width().toInt(),to.height().toInt()).apply{leftMargin=to.left.toInt();topMargin=to.top.toInt()})
        }

        private fun row(icon:String,label:String,act:()->Unit){
            val bg=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(Radius.R20).toFloat();setColor(Color.TRANSPARENT)}
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(16),0);isClickable=true;isFocusable=true;contentDescription=label;background=bg}
            r.addView(IconView(a,icon,a.fg),LinearLayout.LayoutParams(a.d(24),a.d(24)))
            r.addView(a.text(label,Type.BODY,a.fg).apply{typeface=PolkaFonts.sans(500);maxLines=1},LinearLayout.LayoutParams(-2,-2).apply{leftMargin=a.d(14)})
            // under the finger the row lights up and swells a little (the reference's tile), and settles back after
            var lit=0f;var anim:ValueAnimator?=null
            fun glow(on:Boolean){anim?.cancel();anim=ValueAnimator.ofFloat(lit,if(on)1f else 0f).apply{duration=if(on)140 else 320;interpolator=MotionSpec.enter
                addUpdateListener{lit=it.animatedValue as Float;bg.setColor(ColorUtils.setAlphaComponent(a.fg,((if(light)20 else 38)*lit).roundToInt()));val s=1f+.025f*lit;r.scaleX=s;r.scaleY=s};start()}}
            r.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->glow(true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->glow(false)};false}
            r.setOnClickListener{if(!closing)close(act)}
            rows.add(r);column.addView(r,LinearLayout.LayoutParams(-1,Size.row(a,54)))
        }

        /** [morph]: bar 0 → menu 1 (a spring passes 1 a little); [shade]: the page dims; [shown]: each row out of its blur;
         *  [bar]: the bar's icons gone; [x]: the «＋» turned into the cross. */
        fun pose(morph:Float,shade:Float,shown:FloatArray,bar:Float,x:Float){
            t=morph;dim=shade;rowsShown=shown;barGone=bar;cross=x
            shown.forEachIndexed{i,k->val r=rows[i];r.alpha=k;r.translationY=a.d(8)*(1f-k);blur(r,a.d(8)*(1f-k))}
            column.getChildAt(2).alpha=shown.getOrElse(1){0f}
            snapshot.alpha=1f-bar;val s=1f-.08f*bar;snapshot.scaleX=s;snapshot.scaleY=s;blur(snapshot,a.d(10)*bar)
            fab.rotation=45f*x
            (fab as? IconView)?.let{it.ink=ColorUtils.blendARGB(fabInk,a.fg,x);it.invalidate()}
            fab.background?.colorFilter=if(x<=0f)null else PorterDuffColorFilter(ColorUtils.blendARGB(fabFill,menuColor,x),PorterDuff.Mode.SRC_IN)
            invalidate()}

        private fun blur(v:View,radius:Float){if(android.os.Build.VERSION.SDK_INT<31)return
            v.setRenderEffect(if(radius<.5f)null else RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.DECAL))}

        private fun current():RectF{val k=t;val w=from.width()+(to.width()-from.width())*k;val h=from.height()+(to.height()-from.height())*k
            now.set(from.left,from.bottom-h,from.left+w,from.bottom);return now}

        override fun onDraw(c:Canvas){
            if(dim>0f)c.drawColor(ColorUtils.setAlphaComponent(if(light)0xfff2f2f0.toInt() else Color.BLACK,((if(light)102 else 153)*dim).roundToInt()))
            val r=current();val k=t.coerceIn(0f,1f);val radius=r0+(r1-r0)*k
            // the bar has no shade; the menu over the page takes its own as it grows (made for its full size and
            // stretched to the shape on the way)
            if(k>0f)FloatShade.draw(c,a,r,radius,light,(255*k).roundToInt(),lift=Lift.OVER,bakeAs=to)
            fill.style=Paint.Style.FILL;fill.color=ColorUtils.blendARGB(barColor,menuColor,k);c.drawRoundRect(r,radius,radius,fill)
            // the light bar's thin edge, gone once it is the menu
            if(light&&k<1f){fill.style=Paint.Style.STROKE;fill.strokeWidth=a.d(1).toFloat();fill.color=ColorUtils.setAlphaComponent(Color.BLACK,(18*(1f-k)).roundToInt())
                val e=a.d(1)/2f;c.drawRoundRect(r.left+e,r.top+e,r.right-e,r.bottom-e,radius-e,radius-e,fill)}}

        override fun dispatchDraw(c:Canvas){val r=current();val k=t.coerceIn(0f,1f);val radius=r0+(r1-r0)*k
            path.rewind();path.addRoundRect(r,radius,radius,Path.Direction.CW);c.save();c.clipPath(path);super.dispatchDraw(c);c.restore()}

        // a tap anywhere off the menu closes it (the «＋» above it closes it too)
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP&&!to.contains(e.x,e.y))close();return true}
        override fun onDetachedFromWindow(){if(view===this){clock?.cancel();if(a.addMenu===this@AddMenu)a.addMenu=null};super.onDetachedFromWindow()}
    }
}
