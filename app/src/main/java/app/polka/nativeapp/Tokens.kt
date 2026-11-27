package app.polka.nativeapp

import android.graphics.Color
import android.os.Build
import android.graphics.Rect
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup

/*
 * The design system (approved «Glyph: система перед релизом»). Screens take sizes, colours, radii and motion from
 * here by their role, never as numbers of their own; drawings (covers, illustrations, charts' own palette) are the
 * only exception.
 */

/** Nine type steps, each with one role. Nothing is set smaller than [CAPTION]. The book's own text is not on this
 *  scale: its size is the reader's choice. */
internal object Type {
    /** Group captions, badges, chart labels: the minimum. */
    const val CAPTION=12f
    /** Values, captions under covers, dates. */
    const val SECONDARY=13f
    /** Descriptions, quotes in lists, chips. */
    const val TEXT=15f
    /** Rows, menu items, buttons. */
    const val BODY=16f
    /** A window's or panel's title. */
    const val TITLE=17f
    /** Sections, empty screens. */
    const val H2=20f
    /** A book's title on its card, a shelf's name. */
    const val H1=26f
    /** A tab's own title. */
    const val LARGE=34f
    /** Totals in the statistics. */
    const val NUMBER=40f
    /** The step a computed size falls on. */
    fun snap(sp:Float)=when{sp<12.25f->CAPTION;sp<13.75f->SECONDARY;sp<15.25f->TEXT;sp<16.75f->BODY;sp<18.5f->TITLE;sp<23f->H2;sp<32f->H1;sp<39f->LARGE;sp<44f->NUMBER;else->sp}
}

/** The colour roles, each for the light ([l]) and the dark theme. There is no coloured accent: the ink itself marks
 *  what is chosen. */
internal object Ui {
    fun bg(l:Boolean)=if(l)0xfff2f2f0.toInt() else 0xff161617.toInt()
    /** Sheets and panels. */
    fun sheet(l:Boolean)=if(l)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt()
    /** Groups of rows, cards on a screen. */
    fun card(l:Boolean)=if(l)Color.WHITE else 0xff2a2a2d.toInt()
    /** Menus that grow out of a button. */
    fun menu(l:Boolean)=if(l)Color.WHITE else 0xff303033.toInt()
    /** Under a window. */
    fun scrim(l:Boolean)=if(l)0x66f2f2f0 else 0x99000000.toInt()
    fun ink(l:Boolean)=if(l)0xff1a1a1c.toInt() else 0xfff3f2ef.toInt()
    /** The one secondary grey: 5.7:1 on the light ground, 7.3:1 on the dark one. */
    fun ink2(l:Boolean)=if(l)0xff5f5f63.toInt() else 0xffa4a4a4.toInt()
    /** Icons and what is not available now. */
    fun icon(l:Boolean)=if(l)0xff737373.toInt() else 0xff8e8e93.toInt()
    /** Text on a filled (ink-coloured) button. */
    fun onInk(l:Boolean)=bg(l)
    fun line(l:Boolean)=if(l)0x12000000 else 0x14ffffff
    /** A button standing on the ground by itself (opaque): white and raised in the light theme, [fill] over [bg] in
     *  the dark one. */
    fun control(l:Boolean)=if(l)Color.WHITE else 0xff262627.toInt()
    /** Round buttons, chips, fields. */
    fun fill(l:Boolean)=if(l)0x0f000000 else 0x11ffffff
    /** Under the finger. */
    fun press(l:Boolean)=if(l)0x1c000000 else 0x1fffffff
    /** Delete and other things that cannot be taken back: 4.9:1 light, 5.1:1 on a dark card. */
    fun danger(l:Boolean)=if(l)0xffc2342f.toInt() else 0xffff6961.toInt()
}

/** Six radii and the capsule. An inner corner is the outer one less the gap between them, rounded to the scale. */
internal object Radius {
    const val R8=8;const val R12=12;const val R16=16;const val R20=20;const val R24=24;const val R28=28
}

/** What a finger needs and what everything is measured by (dp). */
internal object Size {
    /** The least a tap target may be. */
    const val TOUCH=48
    /** A row's least height in sheets and on screens; it grows with its text. */
    const val ROW=56
    /** A row's least height in the reader's bottom panel. */
    const val PANEL_ROW=52
    /** A window's head. */
    const val HEADER=56
    /** The round button seen inside a [TOUCH] target. */
    const val ROUND=40
    const val BUTTON=48
    /** The main button of a window. */
    const val BUTTON_MAIN=56
    const val ICON=24
    /** A one-line row's height in px: [least] dp, or more when the system's font is set larger. */
    fun row(a:MainActivity,least:Int=ROW,sp:Float=Type.BODY)=maxOf(a.d(least),(sp*a.resources.displayMetrics.scaledDensity*1.3f).toInt()+a.d(24))
}

/** Four durations (ms). With «Меньше движения» changes are a plain fade of [FAST]. */
internal object Motion {
    const val FAST=150L
    const val STANDARD=250L
    const val EMPHASIS=400L
    const val LONG=600L
}

/** One map of haptics for the whole app, all off with the «Вибрация» setting. Moving between pages, sheets and menus
 *  does not vibrate: a tick always means something was done. */
internal enum class Haptic{
    /** A choice made: theme, font, turn, sort, zoom. */
    SELECT,
    /** A switch on or off. */
    TOGGLE_ON,TOGGLE_OFF,
    /** A slider's step, ± size. */
    STEP,
    /** Done: reset, saved, goal reached. */
    CONFIRM,
    /** Not possible: a limit, an error, an empty field. */
    REJECT,
    /** A long press: selection, dragging. */
    LONG,
    /** A gesture past its point: swipe back, a sheet pulled to close. */
    THRESHOLD;
    fun constant():Int=when(this){
        SELECT->HapticFeedbackConstants.CLOCK_TICK
        TOGGLE_ON->if(Build.VERSION.SDK_INT>=34)HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
        TOGGLE_OFF->if(Build.VERSION.SDK_INT>=34)HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
        STEP->if(Build.VERSION.SDK_INT>=34)HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK
        CONFIRM->if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        REJECT->if(Build.VERSION.SDK_INT>=30)HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        LONG->HapticFeedbackConstants.LONG_PRESS
        THRESHOLD->if(Build.VERSION.SDK_INT>=34)HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.CLOCK_TICK}
}
internal fun View.haptic(a:MainActivity,kind:Haptic){if(a.prefs.optBoolean("haptics",true))performHapticFeedback(kind.constant())}

/** A small control keeps its look and still takes the 48 dp a finger needs: [host], an ancestor with room round it,
 *  passes on the taps that land near it (see [GrowTouch]). */
internal fun View.growTouch(a:MainActivity,host:ViewGroup){val v=this
    host.addOnLayoutChangeListener{_,_,_,_,_,_,_,_,_->host.post{GrowTouch.of(host).place(v,a.d(Size.TOUCH))}}}

/** Several grown tap areas in one parent (Android's own [TouchDelegate] holds only one). */
internal class GrowTouch private constructor(private val host:ViewGroup):TouchDelegate(Rect(),host){
    companion object{fun of(host:ViewGroup)=(host.touchDelegate as? GrowTouch)?:GrowTouch(host).also{host.touchDelegate=it}}
    private val areas=LinkedHashMap<View,TouchDelegate>()
    private var active:TouchDelegate?=null
    fun place(v:View,size:Int){
        if(!v.isAttachedToWindow||v.width==0||v.visibility!=View.VISIBLE){areas.remove(v);return}
        val r=Rect();v.getDrawingRect(r)
        try{host.offsetDescendantRectToMyCoords(v,r)}catch(_:IllegalArgumentException){areas.remove(v);return}
        r.inset(-maxOf(0,(size-r.width()+1)/2),-maxOf(0,(size-r.height()+1)/2))
        areas[v]=TouchDelegate(r,v)}
    override fun onTouchEvent(e:MotionEvent):Boolean{
        if(e.actionMasked==MotionEvent.ACTION_DOWN){active=areas.values.firstOrNull{it.onTouchEvent(e)};return active!=null}
        val d=active?:return false
        val done=d.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL)active=null
        return done}
}
