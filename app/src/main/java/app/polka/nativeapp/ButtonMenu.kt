package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.view.*
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.core.graphics.ColorUtils
import kotlin.math.*

/**
 * The «⋯» menus, moving like a drop of liquid (as asked, after Telegram's menus): under the finger the round button
 * swells, stretches toward where the menu will stand, and the whole menu, its rows already in it, comes out of that
 * shape growing from the button's corner and settles on a spring. Closing runs it back: the menu shrinks into its
 * corner, its rows go, what is left rounds into a drop over the button, and the button swallows it, swollen for a
 * moment and springing back to its size. The page dims under it. A destructive row comes last, red, after a thin line.
 * [inset]: how far inside the anchor its round is drawn (a 40 dp button in a 48 dp tap), so the drop starts and ends
 * on what is seen.
 */
internal class ButtonMenu(private val a:MainActivity,private val anchor:View,private val items:List<Item>,private val inset:Int=0){
    class Item(val icon:String,val label:String,val destructive:Boolean=false,val act:()->Unit)
    private var view:MenuView?=null
    private var clock:ValueAnimator?=null
    private var closing=false
    private val reduced get()=a.reducedMotion

    fun open(){
        if(anchor.width==0||!anchor.isShown||items.isEmpty())return
        a.buttonMenu?.close();a.buttonMenu=this
        val v=MenuView();view=v
        a.root.addView(v,FrameLayout.LayoutParams(-1,-1))
        anchor.visibility=View.INVISIBLE
        if(reduced){v.card(1f,1f,1f);return}
        val spring=Motion.expand;val total=CARD_AT+spring.duration
        clock=ValueAnimator.ofFloat(0f,total.toFloat()).apply{duration=total;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                // the button swells under the finger, then stretches toward the menu
                val swell=MotionSpec.enter.getInterpolation((ms/90f).coerceIn(0f,1f))
                val stretch=MotionSpec.enter.getInterpolation(((ms-70f)/90f).coerceIn(0f,1f))
                // the menu grows out of it from the corner, on the spring, its rows in it from the start
                val grow=CARD_FROM+(1f-CARD_FROM)*spring.interpolator.getInterpolation(((ms-CARD_AT)/spring.duration).coerceIn(0f,1f))
                val merge=MotionSpec.enter.getInterpolation(((ms-CARD_AT)/90f).coerceIn(0f,1f))
                val rows=MotionSpec.enter.getInterpolation(((ms-CARD_AT-10f)/90f).coerceIn(0f,1f))
                v.drop(swell,stretch,grow,merge,rows,MotionSpec.enter.getInterpolation((ms/260f).coerceIn(0f,1f)))}
            start()}
    }

    /** [then]: what was chosen, started while the menu is still going back into the button. */
    fun close(then:(()->Unit)?=null){if(closing)return;closing=true
        if(a.buttonMenu===this)a.buttonMenu=null
        val v=view?:run{then?.invoke();return}
        clock?.cancel()
        fun done(){(v.parent as? ViewGroup)?.removeView(v);anchor.visibility=View.VISIBLE}
        if(reduced){done();then?.invoke();return}
        then?.let{go->a.root.postDelayed({go()},120)}
        val g0=v.grow;val d0=v.dim
        clock=ValueAnimator.ofFloat(0f,LANDS.toFloat()).apply{duration=LANDS;interpolator=LinearInterpolator()
            addUpdateListener{val ms=it.animatedValue as Float
                // the menu shrinks into its corner and its rows go; what is left rounds into a drop over the button
                val shrink=MotionSpec.exit.getInterpolation((ms/120f).coerceIn(0f,1f))
                val rows=1f-MotionSpec.exit.getInterpolation((ms/80f).coerceIn(0f,1f))
                val round=MotionSpec.move.getInterpolation(((ms-70f)/(LANDS-70f)).coerceIn(0f,1f))
                v.back(g0+(BLOB_FROM-g0)*shrink,rows,round,d0*(1f-MotionSpec.move.getInterpolation((ms/LANDS).coerceIn(0f,1f))))}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){done();swallow()}})
            start()}
    }

    /** The button takes the drop in: it stands swollen to the drop's size and springs back («Отклик»). */
    private fun swallow(){if(reduced||!anchor.isAttachedToWindow){anchor.scaleX=1f;anchor.scaleY=1f;return}
        anchor.animate().cancel();val sp=Motion.response
        ValueAnimator.ofFloat(0f,1f).apply{duration=sp.duration;interpolator=sp.interpolator
            addUpdateListener{val s=SWALLOWED+(1f-SWALLOWED)*(it.animatedValue as Float);anchor.scaleX=s;anchor.scaleY=s}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){anchor.scaleX=1f;anchor.scaleY=1f}})
            start()}}

    private inner class MenuView:FrameLayout(a){
        // the button's round as seen (where the drop starts and ends) and the menu (where it stands), in the root's coordinates
        private val from:RectF
        private val whole:RectF
        private val to:RectF
        private val r1=a.d(26).toFloat()
        private val light=a.light
        private val menuColor=Ui.menu(light)
        /** The button's own colour, so the drop is the button until it becomes the menu. */
        private val buttonColor:Int
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG);private val path=Path();private val shape=RectF()
        private var radius=0f;private var colour=0;private var shade=0f
        val rows=ArrayList<View>()
        private val snapshot=ImageView(a)
        private val column=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL;setPadding(0,a.d(6),0,a.d(6))}
        // the corner of the button the menu grows from: the one nearest the middle of the screen stays put
        private val right:Boolean;private val down:Boolean
        var grow=0f;var dim=0f

        init{
            setWillNotDraw(false);clipChildren=false;isClickable=true
            // above everything on the page, sheets that stand raised included (no shadow of its own: it draws its shade)
            outlineProvider=null;elevation=a.d(48).toFloat()
            val at=IntArray(2);val base=IntArray(2);anchor.getLocationInWindow(at);a.root.getLocationInWindow(base)
            val l=(at[0]-base[0]).toFloat();val tp=(at[1]-base[1]).toFloat()
            whole=RectF(l,tp,l+anchor.width,tp+anchor.height)
            from=RectF(whole).apply{inset(inset.toFloat(),inset.toFloat())}
            items.forEachIndexed{i,it->if(it.destructive&&i>0&&!items[i-1].destructive)divider();row(it)}
            val w=min(a.d(262).toFloat(),a.root.width-a.d(24).toFloat())
            val h=a.d(6)*2f+Size.row(a,52)*rows.size+column.childCount.minus(rows.size)*a.d(13)
            right=from.centerX()>a.root.width/2f;down=from.centerY()<a.root.height/2f
            val x0=if(right)from.right-w else from.left;val y0=if(down)from.top else from.bottom-h
            to=RectF(x0,y0,x0+w,y0+h)
            // the button as it is now: it swells, then its dots fade into the drop
            var sampled=menuColor
            runCatching{Bitmap.createBitmap(anchor.width,anchor.height,Bitmap.Config.ARGB_8888)}.getOrNull()?.let{bmp->anchor.draw(Canvas(bmp));snapshot.setImageBitmap(bmp)
                val px=bmp.getPixel(bmp.width/2,(inset+a.d(4)).coerceAtMost(bmp.height-1));if(Color.alpha(px)>200)sampled=px}
            buttonColor=sampled
            snapshot.importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(snapshot,LayoutParams(anchor.width,anchor.height).apply{leftMargin=whole.left.toInt();topMargin=whole.top.toInt()})
            addView(column,LayoutParams(to.width().toInt(),to.height().toInt()).apply{leftMargin=to.left.toInt();topMargin=to.top.toInt()})
            column.pivotX=if(right)to.width() else 0f;column.pivotY=if(down)0f else to.height()
            column.alpha=0f
        }

        private fun divider(){val v=View(a).apply{setBackgroundColor(ColorUtils.setAlphaComponent(a.fg,if(light)18 else 20))}
            column.addView(v,LinearLayout.LayoutParams(-1,a.d(1)).apply{setMargins(a.d(16),a.d(6),a.d(16),a.d(6))})}

        private fun row(item:Item){
            val ink=if(item.destructive)Ui.danger(a.light) else a.fg
            val bg=android.graphics.drawable.GradientDrawable().apply{cornerRadius=a.d(Radius.R20).toFloat();setColor(Color.TRANSPARENT)}
            val r=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(16),0,a.d(16),0);isClickable=true;isFocusable=true;contentDescription=item.label;background=bg}
            r.addView(IconView(a,item.icon,ink),LinearLayout.LayoutParams(a.d(24),a.d(24)))
            r.addView(a.text(item.label,Type.BODY,ink).apply{typeface=PolkaFonts.sans(500);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=a.d(14)})
            // under the finger the row lights up and swells a little, as in the «＋» menu
            var lit=0f;var anim:ValueAnimator?=null
            fun glow(on:Boolean){anim?.cancel();anim=ValueAnimator.ofFloat(lit,if(on)1f else 0f).apply{duration=if(on)Motion.FAST else Motion.STANDARD;interpolator=MotionSpec.enter
                addUpdateListener{lit=it.animatedValue as Float;bg.setColor(ColorUtils.setAlphaComponent(ink,((if(light)20 else 38)*lit).roundToInt()));val s=1f+.025f*lit;r.scaleX=s;r.scaleY=s};start()}}
            r.setOnTouchListener{_,e->when(e.actionMasked){MotionEvent.ACTION_DOWN->glow(true);MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->glow(false)};false}
            r.setOnClickListener{if(!closing)close(item.act)}
            rows.add(r);column.addView(r,LinearLayout.LayoutParams(-1,Size.row(a,52)).apply{setMargins(a.d(6),0,a.d(6),0)})
        }

        /** The menu at [g] of its size, grown from its corner. */
        private fun cardRect(g:Float,out:RectF){val w=to.width()*g;val h=to.height()*g
            val x=if(right)to.right-w else to.left;val y=if(down)to.top else to.bottom-h;out.set(x,y,x+w,y+h)}
        /** The button swollen by [swell] and stretched by [stretch] toward the menu's side. */
        private fun capsule(swell:Float,stretch:Float,out:RectF){val d=from.width()*(1f+(SWOLLEN-1f)*swell)
            val w=d*(1f+.75f*stretch);val h=d*(1f+.08f*stretch);val cy=from.centerY()
            val near=if(right)from.centerX()+d/2 else from.centerX()-d/2
            if(right)out.set(near-w,cy-h/2,near,cy+h/2) else out.set(near,cy-h/2,near+w,cy+h/2)}
        private fun mix(p:RectF,q:RectF,k:Float,out:RectF)=out.set(p.left+(q.left-p.left)*k,p.top+(q.top-p.top)*k,p.right+(q.right-p.right)*k,p.bottom+(q.bottom-p.bottom)*k)
        private val t1=RectF();private val t2=RectF()

        /** Opening: [swell] and [stretch] of the button, the menu's [g] of size, [merge] from the drop into the menu,
         *  its rows [shown], the page's [shade]. */
        fun drop(swell:Float,stretch:Float,g:Float,merge:Float,shown:Float,shade:Float){
            grow=g;dim=shade
            capsule(swell,stretch,t1);cardRect(g,t2);mix(t1,t2,merge,shape)
            radius=t1.height()/2+(r1*g-t1.height()/2)*merge;colour=ColorUtils.blendARGB(buttonColor,menuColor,merge);this.shade=merge
            val s=1f+(SWOLLEN-1f)*swell;snapshot.scaleX=s;snapshot.scaleY=s
            snapshot.alpha=1f-MotionSpec.exit.getInterpolation((stretch*1.4f).coerceIn(0f,1f))
            column.scaleX=g;column.scaleY=g;column.alpha=shown
            invalidate()}

        /** Closing: the menu at [g] of its size, its rows still [shown], [round] of the way into the drop over the
         *  button, the page's [shade]. */
        fun back(g:Float,shown:Float,round:Float,shade:Float){
            grow=g;dim=shade
            cardRect(g,t1);val d=from.width()*SWALLOWED;t2.set(from.centerX()-d/2,from.centerY()-d/2,from.centerX()+d/2,from.centerY()+d/2)
            mix(t1,t2,round,shape);radius=r1*g+(d/2-r1*g)*round
            colour=ColorUtils.blendARGB(menuColor,buttonColor,round);this.shade=1f-round
            snapshot.alpha=0f;column.scaleX=g;column.scaleY=g;column.alpha=shown
            invalidate()}

        /** The menu standing (no motion): [g] of its size. */
        fun card(g:Float,shown:Float,shade:Float){grow=g;dim=shade;cardRect(g,shape);radius=r1*g;colour=menuColor;this.shade=1f
            snapshot.alpha=0f;column.scaleX=g;column.scaleY=g;column.alpha=shown;invalidate()}

        override fun onDraw(c:Canvas){
            if(dim>0f)c.drawColor(Ui.scrim(light,dim))
            if(shade>0f)FloatShade.draw(c,a,shape,radius,light,(255*shade).roundToInt(),lift=Lift.OVER,bakeAs=to)
            fill.color=colour;c.drawRoundRect(shape,radius,radius,fill)}

        // the rows (and the button's picture) are seen only inside the drop's shape
        override fun dispatchDraw(c:Canvas){path.rewind();path.addRoundRect(shape,radius,radius,Path.Direction.CW)
            c.save();c.clipPath(path);super.dispatchDraw(c);c.restore()}

        // a tap anywhere off the menu closes it
        override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_UP&&!to.contains(e.x,e.y))close();return true}
        override fun onDetachedFromWindow(){if(view===this){clock?.cancel();anchor.visibility=View.VISIBLE;if(a.buttonMenu===this@ButtonMenu)a.buttonMenu=null};super.onDetachedFromWindow()}
    }

    private companion object{
        /** The button under the finger, swollen. */
        const val SWOLLEN=1.12f
        /** When the menu starts out of the drop (ms) and at what share of its size. */
        const val CARD_AT=110L;const val CARD_FROM=.42f
        /** How small the menu is when it has rounded into the drop. */
        const val BLOB_FROM=.4f
        /** The drop over the button when it lands, and the button swallowing it. */
        const val SWALLOWED=1.22f
        /** Closing, from the tap to the drop on the button (ms). */
        const val LANDS=190L
    }
}
