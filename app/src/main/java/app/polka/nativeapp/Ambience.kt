package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import androidx.core.graphics.PathParser
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Background sounds while reading: four built in (recordings under a free licence, assets/sounds/<key>.ogg) and the
 * reader's own (copied to files/sounds, listed in prefs «sounds» as {id,name,ext,dur}). One sound at a time, looped
 * without a gap (the next round is queued on a second player), faded in and out. It plays while the book is open,
 * pauses when the app goes to the background and stops when the book is closed.
 */
internal object Ambience {
    class Sound(val key:String,val name:String,val note:String,val glow:Int,val deep:Int,val icon:String)
    val builtIn=listOf(
        Sound("fire","Камин","треск дров",0xffc2562b.toInt(),0xff3a1a10.toInt(),"fire"),
        Sound("rain","Дождь","по крыше и окну",0xff3f6f9e.toInt(),0xff14202d.toInt(),"rain"),
        Sound("sea","Море","волны у берега",0xff2f8d9a.toInt(),0xff0f2629.toInt(),"sea"),
        Sound("forest","Лес","птицы вечером",0xff4f8a4a.toInt(),0xff152414.toInt(),"forest"))
    const val CUSTOM="mine:"
    private const val MINE_COLOR=0xff9d98f2.toInt()

    var key:String?=null;private set
    private var cur:MediaPlayer?=null;private var queued:MediaPlayer?=null
    private var gain=0f;private var fader:ValueAnimator?=null;private var paused=false
    private val listeners=LinkedHashSet<()->Unit>()
    fun listen(f:()->Unit){listeners.add(f)};fun unlisten(f:()->Unit){listeners.remove(f)}
    private fun changed(){listeners.toList().forEach{it()}}

    fun volume(a:MainActivity)=a.prefs.optDouble("ambVol",.6).toFloat().coerceIn(0f,1f)
    fun setVolume(a:MainActivity,v:Float,save:Boolean){a.prefs.put("ambVol",v.toDouble());if(save)a.savePreferences();apply(a)}
    private fun apply(a:MainActivity){val v=volume(a)*gain;try{cur?.setVolume(v,v);queued?.setVolume(v,v)}catch(_:Exception){}}

    fun color(a:MainActivity,k:String?)=builtIn.firstOrNull{it.key==k}?.glow?:MINE_COLOR
    fun name(a:MainActivity,k:String?)=builtIn.firstOrNull{it.key==k}?.name?:mine(a).firstOrNull{CUSTOM+it.str("id")==k}?.str("name")?:""
    fun mine(a:MainActivity)=(a.prefs.optJSONArray("sounds")?:JSONArray()).let{arr->(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}}
    private fun dir(a:MainActivity)=File(a.filesDir,"sounds").apply{mkdirs()}
    private fun file(a:MainActivity,o:JSONObject)=File(dir(a),o.str("id")+"."+o.str("ext","audio"))

    private fun make(a:MainActivity,k:String):MediaPlayer=MediaPlayer().apply{
        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        if(k.startsWith(CUSTOM)){val o=mine(a).first{CUSTOM+it.str("id")==k};setDataSource(file(a,o).path)}
        else a.assets.openFd("sounds/$k.ogg").use{setDataSource(it.fileDescriptor,it.startOffset,it.length)}
        prepare()}
    /** The next round is queued on its own player: when this one ends, that one starts with no gap. */
    private fun chain(a:MainActivity,p:MediaPlayer,k:String){
        val n=try{make(a,k)}catch(_:Exception){null}?:return
        queued=n;apply(a)
        try{p.setNextMediaPlayer(n)}catch(_:Exception){}
        p.setOnCompletionListener{done->if(cur!==done)return@setOnCompletionListener;done.release();cur=n;queued=null;if(key==k)chain(a,n,k)}}

    fun play(a:MainActivity,k:String){if(key==k&&cur!=null){if(paused)resume(a);return}
        release();key=k;paused=false
        try{val p=make(a,k);cur=p;gain=0f;apply(a);p.start();chain(a,p,k);fade(a,1f,900){}}
        catch(e:Exception){release();key=null;Notice.short(a,Notice.Kind.ERROR,"Не удалось включить звук")}
        changed()}
    fun stop(a:MainActivity,fast:Boolean=false){if(key==null)return;key=null;changed()
        val p=cur;val q=queued;cur=null;queued=null
        if(p==null)return
        if(fast||a.prefs.str("motion")=="reduced"){p.release();q?.release();return}
        // the old sound fades on its own while nothing else changes
        val v0=volume(a)*gain;fader?.cancel();gain=0f
        ValueAnimator.ofFloat(1f,0f).apply{duration=600;addUpdateListener{val t=it.animatedValue as Float;try{p.setVolume(v0*t,v0*t)}catch(_:Exception){}}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){p.release();q?.release()}});start()}}
    fun pause(a:MainActivity){val p=cur?:return;if(paused)return;paused=true;try{p.pause()}catch(_:Exception){};changed()}
    fun resume(a:MainActivity){val p=cur?:return;if(!paused)return;paused=false;try{gain=0f;apply(a);p.start();fade(a,1f,700){}}catch(_:Exception){};changed()}
    val playing get()=key!=null&&!paused
    private fun fade(a:MainActivity,to:Float,ms:Long,then:()->Unit){fader?.cancel();val from=gain
        fader=ValueAnimator.ofFloat(from,to).apply{duration=ms;addUpdateListener{gain=it.animatedValue as Float;apply(a)}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){then()}});start()}}
    private fun release(){fader?.cancel();try{cur?.release()}catch(_:Exception){};try{queued?.release()}catch(_:Exception){};cur=null;queued=null}

    /** Stores a picked audio file; returns {id,name,ext,dur} or throws with a reader-facing reason. Worker thread. */
    fun add(a:MainActivity,bytes:ByteArray,fileName:String):JSONObject{
        val id=java.util.UUID.randomUUID().toString().take(12);val ext=fileName.substringAfterLast('.',"audio").lowercase().take(5)
        val f=File(dir(a),"$id.$ext");f.writeBytes(bytes)
        val mr=android.media.MediaMetadataRetriever();var dur=0L;var title:String?=null
        try{mr.setDataSource(f.path);dur=mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?:0L;title=mr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)}catch(_:Exception){}finally{try{mr.release()}catch(_:Exception){}}
        val ok=try{MediaPlayer().apply{setDataSource(f.path);prepare()}.release();true}catch(_:Exception){false}
        if(!ok||dur<=0L){f.delete();throw IllegalArgumentException("Это не звуковой файл — нужен MP3, M4A, OGG или WAV")}
        val name=title?.trim()?.takeIf{it.isNotBlank()&&it.length<=40}?:fileName.substringBeforeLast('.').replace(Regex("[-_]+")," ").trim().ifBlank{"Мой звук"}
        return JSONObject().put("id",id).put("name",name).put("ext",ext).put("dur",dur)}
    fun register(a:MainActivity,o:JSONObject){(a.prefs.optJSONArray("sounds")?:JSONArray().also{a.prefs.put("sounds",it)}).put(o)}
    fun remove(a:MainActivity,o:JSONObject){val k=CUSTOM+o.str("id");if(key==k)stop(a,true);file(a,o).delete()
        a.prefs.put("sounds",JSONArray(mine(a).filter{it.str("id")!=o.str("id")}))}

    // ── drawing ───────────────────────────────────────────────────────────────────────────────
    private val paths=HashMap<String,Path>()
    private val icons=mapOf(
        "fire" to "M2.5 4.5h19M3.5 4.5v2.5h17V4.5M5 7v14.5M19 7v14.5M3 21.5h18M8 21.5v-7a4 4 0 0 1 8 0v7",
        "fireFlame" to "M12 20.4c-1.35 0-2.2-.85-2.2-2 0-1.35.95-2.05 1.55-3.3.5 1.1 2.85 1.75 2.85 3.3 0 1.15-.85 2-2.2 2z",
        "rain" to "M7 15a4.5 4.5 0 0 1-.6-9A6 6 0 0 1 18 7.2 3.9 3.9 0 0 1 17.5 15zM8.5 18l-1 2.5M12.5 18l-1 2.5M16.5 18l-1 2.5",
        "sea" to "M3 9c1.5 0 1.5-1.5 3-1.5S7.5 9 9 9s1.5-1.5 3-1.5S13.5 9 15 9s1.5-1.5 3-1.5S19.5 9 21 9M3 13.5c1.5 0 1.5-1.5 3-1.5s1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5 1.5-1.5 3-1.5 1.5 1.5 3 1.5M3 18c1.5 0 1.5-1.5 3-1.5S7.5 18 9 18s1.5-1.5 3-1.5S13.5 18 15 18s1.5-1.5 3-1.5S19.5 18 21 18",
        "forest" to "M9 3l4.5 7H11l4 6H3l4-6H4.5zM10 16v4M16.5 7l3.5 5.5h-2l3 4.5h-8M18 17v3",
        "note" to "M9 18V6l11-2v12M9 18a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0zM20 16a2.5 2.5 0 1 1-5 0 2.5 2.5 0 0 1 5 0z",
        "sound" to "M4 10v4M8 7v10M12 4v16M16 8v8M20 10.5v3")
    fun path(n:String)=paths.getOrPut(n){PathParser.createPathFromPathData(icons.getValue(n))}
    /** A 24-unit icon at [size] px into [c] at (x,y); the fireplace's flame is filled so it stands out of the lines. */
    fun icon(c:Canvas,n:String,x:Float,y:Float,size:Float,ink:Int,p:Paint,stroke:Float=1.7f){c.save();c.translate(x,y);c.scale(size/24f,size/24f)
        p.shader=null;p.style=Paint.Style.STROKE;p.strokeWidth=stroke;p.strokeCap=Paint.Cap.ROUND;p.strokeJoin=Paint.Join.ROUND;p.color=ink;c.drawPath(path(n),p)
        if(n=="fire"){p.style=Paint.Style.FILL_AND_STROKE;p.strokeWidth=1.2f;c.drawPath(path("fireFlame"),p)};p.style=Paint.Style.FILL;c.restore()}
}

/** Four bars that dance while a sound plays: each wanders on its own mix of slow waves, so they never move in step.
 *  Starting, they rise from the floor; stopping, they settle back to a quiet resting shape. */
internal class EqBars(private val a:MainActivity,var ink:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var loop:ValueAnimator?=null
    private var level=0f;private var levelAnim:ValueAnimator?=null;private var t0=android.os.SystemClock.uptimeMillis()
    private val rest=floatArrayOf(.5f,.9f,.38f,.72f)
    private val reduced get()=a.prefs.str("motion")=="reduced"
    var live=false;private set
    /** [grow]: the bars rise from nothing (a sound was just chosen). */
    fun set(on:Boolean,grow:Boolean){if(on==live&&!grow)return;live=on
        levelAnim?.cancel()
        if(reduced){level=if(on)1f else 0f;invalidate();return}
        if(on){if(grow)level=0f;tick()}
        levelAnim=ValueAnimator.ofFloat(level,if(on)1f else 0f).apply{duration=if(on)520 else 420;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{level=it.animatedValue as Float;invalidate()}
            addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){if(!live){loop?.cancel();loop=null}}});start()}}
    private fun tick(){if(loop!=null||!isAttachedToWindow||reduced)return
        loop=ValueAnimator.ofFloat(0f,1f).apply{duration=1000;repeatCount=ValueAnimator.INFINITE;addUpdateListener{invalidate()};start()}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(live)tick()}
    override fun onDetachedFromWindow(){loop?.cancel();loop=null;levelAnim?.cancel();super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val bw=w/7f;p.color=ink
        val t=(android.os.SystemClock.uptimeMillis()-t0)/1000f
        for(i in 0..3){
            val dance=(.55f+.25f*kotlin.math.sin(t*(4.3f+i*1.37f)+i*2.1f)+.2f*kotlin.math.sin(t*(9.7f-i*1.1f)+i))   // 0.1 … 1
            val k=rest[i]*(1f-level)+dance*level
            val bh=(h*k).coerceIn(bw,h);val x=i*bw*2f;c.drawRoundRect(x,h-bh,x+bw,h,bw/2,bw/2,p)}}
}

/** The bottom bar's button: the sound icon at rest; once a sound plays, a tinted disc swells in behind and the icon
 *  turns into dancing bars in the sound's colour (and back, when it stops). */
internal class SoundButton(private val a:MainActivity,ink:Int):FrameLayout(a){
    private val still=IconSlot(a,ink);private val bars=EqBars(a,0)
    private val tint=GradientDrawable().apply{shape=GradientDrawable.OVAL}
    private val disc=View(a).apply{background=tint;alpha=0f;scaleX=.6f;scaleY=.6f}
    private val update={refresh(true)}
    private var shown:Boolean?=null
    var ink=ink;set(v){field=v;still.ink=v;still.invalidate()}
    init{clipChildren=false;addView(disc,LayoutParams(a.d(40),a.d(40),Gravity.CENTER));addView(still,LayoutParams(a.d(24),a.d(24),Gravity.CENTER));addView(bars,LayoutParams(a.d(17),a.d(15),Gravity.CENTER));contentDescription="Фоновые звуки";refresh(false)}
    override fun onAttachedToWindow(){super.onAttachedToWindow();Ambience.listen(update);refresh(false)}
    override fun onDetachedFromWindow(){Ambience.unlisten(update);super.onDetachedFromWindow()}
    private fun refresh(animate:Boolean){val on=Ambience.key!=null;val col=Ambience.color(a,Ambience.key)
        if(on){tint.setColor((col and 0xffffff) or 0x38000000);bars.ink=if(a.light)col else androidx.core.graphics.ColorUtils.blendARGB(col,Color.WHITE,.35f)}
        val changed=shown!=on;shown=on
        val motion=animate&&changed&&a.prefs.str("motion")!="reduced"
        fun show(v:View,vis:Boolean,from:Float){v.animate().cancel();if(!motion){v.alpha=if(vis)1f else 0f;v.scaleX=1f;v.scaleY=1f;return}
            if(vis){v.scaleX=from;v.scaleY=from;v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()}
            else v.animate().alpha(0f).scaleX(from).scaleY(from).setDuration(200).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).start()}
        if(changed||!animate){show(disc,on,.5f);show(still,!on,.6f);show(bars,on,.4f)}
        bars.set(Ambience.playing,changed&&on)}
    private class IconSlot(private val a:MainActivity,var ink:Int):View(a){private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){Ambience.icon(c,"sound",0f,0f,width.toFloat(),ink,p,1.8f)}}
}

/** The top bar's chip while a sound plays: small dancing bars and the sound's name, kept short (it never takes the
 *  book's title away); it pops in when a sound starts and folds away when it stops. Tap: «Звуки». */
internal class SoundChip(private val a:MainActivity,open:()->Unit):LinearLayout(a){
    private val bars=EqBars(a,0)
    private val label=a.text("",12f,a.fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=a.d(92)}
    private val bg=GradientDrawable().apply{cornerRadius=a.d(14).toFloat()}
    private val update={refresh(true)}
    private var key:String?="?"
    init{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(10),0,a.d(11),0);background=bg;isClickable=true;contentDescription="Фоновые звуки";setOnClickListener{open()};a.press(this)
        addView(bars,LayoutParams(a.d(11),a.d(10)).apply{marginEnd=a.d(6)});addView(label);refresh(false)}
    override fun onAttachedToWindow(){super.onAttachedToWindow();Ambience.listen(update);refresh(false)}
    override fun onDetachedFromWindow(){Ambience.unlisten(update);super.onDetachedFromWindow()}
    fun repaint(){key="?";refresh(false)}
    private fun refresh(animate:Boolean){val k=Ambience.key;val motion=animate&&a.prefs.str("motion")!="reduced"
        bars.set(Ambience.playing,false)
        if(k==key)return;val was=key;key=k
        if(k!=null){val col=Ambience.color(a,k);bg.setColor((col and 0xffffff) or 0x38000000);val ink=if(a.light)col else androidx.core.graphics.ColorUtils.blendARGB(col,Color.WHITE,.4f)
            label.setTextColor(ink);bars.ink=ink;label.text=Ambience.name(a,k)}
        animate().cancel()
        when{
            k!=null&&(was==null||was=="?"&&visibility!=View.VISIBLE)->{visibility=View.VISIBLE;if(motion){alpha=0f;scaleX=.7f;scaleY=.7f;animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()}else{alpha=1f;scaleX=1f;scaleY=1f}}
            k==null->{if(motion&&visibility==View.VISIBLE)animate().alpha(0f).scaleX(.7f).scaleY(.7f).setDuration(200).withEndAction{visibility=View.GONE}.start() else visibility=View.GONE}
            motion->{label.alpha=0f;label.animate().alpha(1f).setDuration(260).start()}
        }}
}

/** The «Звуки» panel (approved «А»): four tiles, the volume, your own sounds and «Добавить свой звук».
 *  It slides up on the sheet spring with its rows rising in, follows the finger when pulled down and closes. */
internal class AmbienceSheet(private val a:MainActivity,private val readerBg:Int){
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var dialog:android.app.Dialog?=null
    private lateinit var sheet:LinearLayout
    private lateinit var holder:View
    private val tiles=mutableListOf<Tile>()
    private lateinit var off:TextView
    private lateinit var mineBox:LinearLayout
    private val update={paint(true)}

    fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=DragRoot().apply{isClickable=true;setOnClickListener{close()}}
        sheet=a.column().apply{clipToPadding=false;clipChildren=false;isClickable=true;setPadding(a.d(16),a.d(12),a.d(16),a.d(18));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(14)})
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(a.text("Звуки",16f,a.fg,true),LinearLayout.LayoutParams(0,-2,1f))
        off=a.text("Выключить",13f,a.muted).apply{setPadding(a.d(10),a.d(8),a.d(4),a.d(8));isClickable=true;a.press(this);setOnClickListener{Ambience.stop(a)}}
        head.addView(off);sheet.addView(head,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(10);leftMargin=a.d(2)})
        // two rows of two tiles; each tile carries its own ring room (4) so nothing sticks out of the rows
        Ambience.builtIn.chunked(2).forEachIndexed{ri,pair->val row=a.line().apply{clipChildren=false;clipToPadding=false}
            pair.forEachIndexed{i,s->val t=Tile(s);tiles.add(t);t.setOnClickListener{toggle(s.key)};a.press(t);row.addView(t,LinearLayout.LayoutParams(0,a.d(104),1f).apply{if(i>0)leftMargin=a.d(2)})}
            sheet.addView(row,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-a.d(4);rightMargin=-a.d(4);if(ri>0)topMargin=a.d(2)})}
        sheet.addView(VolumeBar(),LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=a.d(10)})
        sheet.addView(a.text("СВОИ ЗВУКИ",12f,a.muted,true).apply{letterSpacing=.06f},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18);bottomMargin=a.d(10);leftMargin=a.d(4)})
        mineBox=a.column();sheet.addView(mineBox,LinearLayout.LayoutParams(-1,-2))
        val add=a.line().apply{gravity=Gravity.CENTER;isClickable=true;a.press(this);contentDescription="Добавить свой звук"
            background=GradientDrawable().apply{cornerRadius=a.d(18).toFloat();setStroke(a.d(1),if(a.light)0x26000000 else 0x2effffff,a.d(5).toFloat(),a.d(4).toFloat())}
            addView(a.text("＋",17f,a.fg,true));addView(a.text("Добавить свой звук",14f,a.fg,true).apply{setPadding(a.d(8),0,0,0)})
            setOnClickListener{a.pickSound{o->buildMine(o.str("id"));toggle(Ambience.CUSTOM+o.str("id"))}}}
        sheet.addView(add,LinearLayout.LayoutParams(-1,a.d(50)))
        sheet.addView(a.text("MP3, M4A, OGG или WAV с телефона",11.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        buildMine(null)
        val scroll=SheetScroll(a,sheetColor,30,18).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false;addView(sheet)}
        holder=scroll
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root.addView(scroll,lp)
        dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,readerBg)
            if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.topMargin=s.top+a.d(60);lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;scroll.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.setOnDismissListener{Ambience.unlisten(update)}
        Ambience.listen(update);paint(false)
        dlg.show()
        if(!reduced){scroll.translationY=a.d(1200).toFloat();scroll.post{val h=scroll.height+a.d(24).toFloat();LiquidSpring{v,_->scroll.translationY=h*v}.apply{snap(1f);to(0f)}
            // the rows rise in one after another as the panel arrives
            for(i in 1 until sheet.childCount){val v=sheet.getChildAt(i);v.alpha=0f;v.translationY=a.d(16).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(90L+i*40L).setDuration(360).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}}
    }
    private fun close(velocity:Float=0f){val d=dialog?:return;dialog=null
        if(reduced){d.dismiss();return}
        val v=holder;val left=v.height+a.d(24)-v.translationY
        v.animate().translationY(v.height+a.d(24).toFloat()).setDuration((if(velocity>0f)(left/velocity*1000f).toLong() else 240L).coerceIn(140L,260L)).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}
    private fun toggle(k:String){if(Ambience.key==k)Ambience.stop(a) else Ambience.play(a,k)}
    private fun paint(animate:Boolean){tiles.forEach{it.refresh(animate)}
        val on=Ambience.key!=null;off.animate().cancel()
        if(animate&&!reduced)off.animate().alpha(if(on)1f else 0f).setDuration(200).start() else off.alpha=if(on)1f else 0f;off.isClickable=on
        for(i in 0 until mineBox.childCount)(mineBox.getChildAt(i) as? MineRow)?.refresh(animate)}
    /** [fresh]: the id of a sound just added — its row slides in. */
    private fun buildMine(fresh:String?){mineBox.removeAllViews();Ambience.mine(a).forEach{o->val row=MineRow(o);mineBox.addView(row,LinearLayout.LayoutParams(-1,a.d(54)).apply{bottomMargin=a.d(8)})
        if(o.str("id")==fresh&&!reduced){row.alpha=0f;row.translationY=a.d(12).toFloat();row.animate().alpha(1f).translationY(0f).setDuration(360).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}}

    /** Pull the panel down to close it: from its handle, its empty parts, or when its list is scrolled to the top. */
    private inner class DragRoot:FrameLayout(a){
        private var y0=0f;private var x0=0f;private var dragging=false;private var vt:VelocityTracker?=null
        private fun inSheet(x:Float,y:Float):Boolean{val r=Rect();holder.getGlobalVisibleRect(r);return r.contains(x.toInt(),y.toInt())}
        override fun onInterceptTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{x0=e.rawX;y0=e.rawY;dragging=false;vt?.recycle();vt=VelocityTracker.obtain();vt?.addMovement(e)}
            MotionEvent.ACTION_MOVE->{vt?.addMovement(e);val dy=e.rawY-y0;val dx=e.rawX-x0
                if(!dragging&&inSheet(x0,y0)&&dy>a.d(10)&&dy>kotlin.math.abs(dx)*1.4f&&!holder.canScrollVertically(-1)){dragging=true;y0=e.rawY;return true}}};return false}
        override fun onTouchEvent(e:MotionEvent):Boolean{if(!dragging)return super.onTouchEvent(e);vt?.addMovement(e)
            when(e.actionMasked){
                MotionEvent.ACTION_MOVE->holder.translationY=(e.rawY-y0).coerceAtLeast(0f)
                MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{vt?.computeCurrentVelocity(1000);val v=vt?.yVelocity?:0f;dragging=false
                    if(holder.translationY>holder.height*.25f||v>a.d(900))close(v.coerceAtLeast(0f))
                    else holder.animate().translationY(0f).setDuration(320).setInterpolator(android.view.animation.OvershootInterpolator(.8f)).start()}};return true}
    }

    /** A sound tile: its colour glowing from the top right over a deep shade, the icon, the name and a quiet line.
     *  Chosen, it gives a soft pulse, the glow swells, the ring draws in around it and the bars rise. */
    private inner class Tile(val s:Ambience.Sound):FrameLayout(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val ring=a.d(4).toFloat()
        private val bars=EqBars(a,Color.WHITE)
        private val name=a.text(s.name,14.5f,Color.WHITE,true).apply{maxLines=1}
        private val note=a.text(s.note,11.5f,0x8cffffff.toInt()).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        private var on=0f;private var anim:ValueAnimator?=null;private var chosen:Boolean?=null
        init{setWillNotDraw(false);clipChildren=false;contentDescription=s.name
            val texts=a.column();texts.addView(name,LinearLayout.LayoutParams(-1,-2));texts.addView(note,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(2)})
            addView(texts,LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.START).apply{leftMargin=a.d(18);rightMargin=a.d(14);bottomMargin=a.d(16)})
            addView(bars,LayoutParams(a.d(17),a.d(15),Gravity.TOP or Gravity.END).apply{topMargin=a.d(20);rightMargin=a.d(20)})}
        fun refresh(animate:Boolean){val now=Ambience.key==s.key;val motion=animate&&!reduced
            val text=if(now)(if(Ambience.playing)"играет" else "на паузе") else s.note
            if(note.text!=text){if(motion){note.animate().cancel();note.animate().alpha(0f).translationY(-a.d(3).toFloat()).setDuration(120).withEndAction{note.text=text;note.translationY=a.d(3).toFloat();note.animate().alpha(1f).translationY(0f).setDuration(220).start()}.start()}else note.text=text}
            bars.visibility=if(now)View.VISIBLE else View.INVISIBLE;bars.set(now&&Ambience.playing,motion&&now&&chosen==false)
            if(chosen==now&&anim==null){chosen=now;return};val becameOn=now&&chosen==false;chosen=now
            val to=if(now)1f else 0f;anim?.cancel()
            if(!motion){on=to;invalidate();return}
            if(becameOn){scaleX=.96f;scaleY=.96f;animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()}
            drawing=now
            anim=ValueAnimator.ofFloat(on,to).apply{duration=if(now)420 else 220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{on=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){anim=null}});start()}}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=a.d(20).toFloat()
            val l=ring;val t=ring;val rr=w-ring;val b=h-ring
            p.style=Paint.Style.FILL
            p.shader=LinearGradient(l,t,rr,b,s.deep,if(a.light)0xff2a2a2d.toInt() else 0xff1b1b1d.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(l,t,rr,b,r,r,p)
            val glow=(s.glow and 0xffffff) or ((0x66+(0xcc-0x66)*on).toInt() shl 24)
            p.shader=RadialGradient(rr,t,(rr-l)*(1.05f+.15f*on),intArrayOf(glow,(s.glow and 0xffffff) or 0x18000000,0),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(l,t,rr,b,r,r,p);p.shader=null
            Ambience.icon(c,s.icon,l+a.d(14),t+a.d(14),a.d(28).toFloat(),Color.WHITE,p)
            // the ring draws itself round the tile (two strokes meeting at the bottom), always inside the tile's own
            // bounds, so no parent can cut it; switching off it simply fades
            if(on>0f){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=a.fg;val k=a.d(1).toFloat()+p.strokeWidth/2f-a.d(1)
                if(!drawing){p.alpha=(255*on).toInt();c.drawRoundRect(k,k,w-k,h-k,r+ring-k,r+ring-k,p)}
                else{ringPath.reset();val rr2=r+ring-k;ringPath.moveTo(w/2f,k);ringPath.lineTo(w-k-rr2,k);ringPath.arcTo(w-k-2*rr2,k,w-k,k+2*rr2,-90f,90f,false);ringPath.lineTo(w-k,h-k-rr2);ringPath.arcTo(w-k-2*rr2,h-k-2*rr2,w-k,h-k,0f,90f,false);ringPath.lineTo(w/2f,h-k)
                    ringMeasure.setPath(ringPath,false);val len=ringMeasure.length*on;ringPart.reset();ringMeasure.getSegment(0f,len,ringPart,true)
                    p.alpha=255;c.drawPath(ringPart,p);c.save();c.scale(-1f,1f,w/2f,0f);c.drawPath(ringPart,p);c.restore()}
                p.alpha=255;p.style=Paint.Style.FILL}}
        private var drawing=false;private val ringPath=Path();private val ringPart=Path();private val ringMeasure=PathMeasure()
    }
    /** Your own sound: a note, its name and length, dancing bars when it plays, a bin to remove it. */
    private inner class MineRow(val o:JSONObject):LinearLayout(a){
        private val key=Ambience.CUSTOM+o.str("id");private val bars=EqBars(a,a.fg)
        private val bg=GradientDrawable().apply{setColor(pillColor);cornerRadius=a.d(18).toFloat()}
        private var chosen:Boolean?=null
        init{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(4),0);background=bg;isClickable=true;a.press(this);setOnClickListener{toggle(key)}
            addView(object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){Ambience.icon(c,"note",0f,0f,width.toFloat(),a.muted,p)}},LayoutParams(a.d(18),a.d(18)).apply{marginEnd=a.d(12)})
            val texts=a.column();texts.addView(a.text(o.str("name"),14f,a.fg).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            val d=o.optLong("dur")/1000;texts.addView(a.text("${d/60}:${"%02d".format(d%60)} · по кругу",11.5f,a.muted),LayoutParams(-2,-2).apply{topMargin=a.d(2)})
            addView(texts,LayoutParams(0,-2,1f))
            addView(bars,LayoutParams(a.d(17),a.d(15)).apply{marginEnd=a.d(6);marginStart=a.d(8)})
            addView(IconView(a,"trash",a.muted).apply{isClickable=true;contentDescription="Удалить звук";setPadding(a.d(9),a.d(9),a.d(9),a.d(9));a.press(this)
                setOnClickListener{a.confirm("Удалить звук?","«${o.str("name")}» пропадёт из списка."){Ambience.remove(a,o);a.savePreferences();buildMine(null);paint(false)}}},LayoutParams(a.d(40),a.d(40)))
            refresh(false)}
        fun refresh(animate:Boolean){val now=Ambience.key==key;val motion=animate&&!reduced
            bars.visibility=if(now)View.VISIBLE else View.INVISIBLE;bars.set(now&&Ambience.playing,motion&&now&&chosen==false)
            if(chosen!=now){bg.setStroke(if(now)a.d(2) else 0,a.fg);if(motion&&now){scaleX=.97f;scaleY=.97f;animate().scaleX(1f).scaleY(1f).setDuration(360).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()}};chosen=now}
    }
    /** Volume as a capsule like «Размер» in the text settings: a bright fill grows from the left, and the speaker,
     *  «Громкость» and the number are drawn twice — dark over the fill, light over the track — so they always read. */
    private inner class VolumeBar:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var v=Ambience.volume(a);private var shown=v;private var glide:ValueAnimator?=null
        private val speaker=Path().apply{moveTo(4f,9.5f);lineTo(4f,14.5f);lineTo(7.5f,14.5f);lineTo(12f,18f);lineTo(12f,6f);lineTo(7.5f,9.5f);close()}
        private var held=false;private var lift=0f
        init{isClickable=true;contentDescription="Громкость"}
        override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);held=true;press(true);set(e.x,false,true)}
            MotionEvent.ACTION_MOVE->set(e.x,false,false)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{held=false;press(false);set(e.x,true,false)}};return true}
        private fun press(on:Boolean){if(reduced){lift=0f;return};ValueAnimator.ofFloat(lift,if(on)1f else 0f).apply{duration=if(on)140 else 280;addUpdateListener{lift=it.animatedValue as Float;scaleX=1f+.015f*lift;scaleY=1f+.04f*lift;invalidate()};start()}}
        /** A tap jumps there smoothly; dragging follows the finger. */
        private fun set(x:Float,done:Boolean,tap:Boolean){v=(x/width).coerceIn(0f,1f);Ambience.setVolume(a,v,done)
            glide?.cancel();if(tap&&!reduced){glide=ValueAnimator.ofFloat(shown,v).apply{duration=220;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{shown=it.animatedValue as Float;invalidate()};start()}}else{shown=v;invalidate()}}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=h/2;val dk=a.d(1).toFloat()
            p.style=Paint.Style.FILL;p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
            val fx=h+(w-h)*shown
            p.shader=LinearGradient(0f,0f,fx,0f,if(a.light)0xff8e8e93.toInt() else 0xff8e8e93.toInt(),if(a.light)0xff5c5c60.toInt() else 0xffe6e6e8.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,fx,h,r,r,p);p.shader=null
            val onFill=if(a.light)Color.WHITE else 0xff1d1d1f.toInt();val onTrack=a.fg
            fun content(ink:Int,value:Int){p.color=ink
                c.save();c.translate(dk*17,h/2-dk*9);c.scale(dk*18/24f,dk*18/24f);p.style=Paint.Style.STROKE;p.strokeWidth=1.9f;p.strokeJoin=Paint.Join.ROUND;p.strokeCap=Paint.Cap.ROUND
                c.drawPath(speaker,p);if(shown>.01f)c.drawArc(10.5f,8.5f,17f,15.5f,-55f,110f,false,p);if(shown>.5f)c.drawArc(11f,5.5f,21f,18.5f,-55f,110f,false,p);c.restore();p.style=Paint.Style.FILL
                p.textSize=dk*14;p.typeface=PolkaFonts.sans(500);c.drawText("Громкость",dk*44,h/2+dk*5,p)
                p.typeface=PolkaFonts.sans(600);p.textSize=dk*14;p.textAlign=Paint.Align.RIGHT;c.drawText("$value",w-dk*20,h/2+dk*5,p);p.textAlign=Paint.Align.LEFT}
            val value=(shown*100).roundToIntSafe()
            c.save();c.clipRect(fx,0f,w,h);content(onTrack,value);c.restore()
            c.save();c.clipRect(0f,0f,fx,h);content(onFill,value);c.restore()}
        private fun Float.roundToIntSafe()=kotlin.math.round(this).toInt()
    }
}
