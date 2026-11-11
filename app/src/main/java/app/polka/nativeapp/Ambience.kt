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

/** Four bars that dance while a sound plays (and rest still otherwise). */
internal class EqBars(private val a:MainActivity,var ink:Int):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var t=0f;private var loop:ValueAnimator?=null
    var live=false;set(v){if(field==v)return;field=v;if(v)start() else{loop?.cancel();loop=null;invalidate()}}
    private fun start(){if(loop!=null||!isAttachedToWindow||a.prefs.str("motion")=="reduced"){invalidate();return}
        loop=ValueAnimator.ofFloat(0f,1000f).apply{duration=1_000_000;repeatCount=ValueAnimator.INFINITE;interpolator=null;addUpdateListener{t=it.animatedValue as Float;invalidate()};start()}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(live)start()}
    override fun onDetachedFromWindow(){loop?.cancel();loop=null;super.onDetachedFromWindow()}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val bw=w/7f;p.color=ink
        val rest=floatArrayOf(.55f,1f,.42f,.78f)
        for(i in 0..3){val k=if(live)(.3f+.7f*(.5f+.5f*kotlin.math.sin(t*(6.1f+i*1.7f)+i*1.9f))*(.6f+.4f*kotlin.math.sin(t*2.3f+i))) else rest[i]
            val bh=(h*k.coerceIn(.18f,1f));val x=i*bw*2f;c.drawRoundRect(x,h-bh,x+bw,h,bw/2,bw/2,p)}}
}

/** The bottom bar's button: the sound icon at rest; while playing, the bars dance in the sound's colour on a tint. */
internal class SoundButton(private val a:MainActivity,ink:Int):FrameLayout(a){
    private val still=IconSlot(a,ink);private val bars=EqBars(a,0)
    private val tint=GradientDrawable().apply{shape=GradientDrawable.OVAL}
    private val disc=View(a).apply{background=tint;alpha=0f}
    private val update={refresh()}
    var ink=ink;set(v){field=v;still.ink=v;still.invalidate()}
    init{addView(disc,LayoutParams(a.d(40),a.d(40),Gravity.CENTER));addView(still,LayoutParams(a.d(24),a.d(24),Gravity.CENTER));addView(bars,LayoutParams(a.d(17),a.d(15),Gravity.CENTER));contentDescription="Фоновые звуки";refresh()}
    override fun onAttachedToWindow(){super.onAttachedToWindow();Ambience.listen(update);refresh()}
    override fun onDetachedFromWindow(){Ambience.unlisten(update);super.onDetachedFromWindow()}
    private fun refresh(){val on=Ambience.key!=null;val col=Ambience.color(a,Ambience.key)
        tint.setColor((col and 0xffffff) or 0x38000000);bars.ink=if(a.light)col else blend(col,0xffffffff.toInt(),.35f)
        still.visibility=if(on)View.INVISIBLE else View.VISIBLE;bars.visibility=if(on)View.VISIBLE else View.GONE;disc.alpha=if(on)1f else 0f;bars.live=Ambience.playing}
    private fun blend(x:Int,y:Int,t:Float)=Color.rgb((Color.red(x)*(1-t)+Color.red(y)*t).toInt(),(Color.green(x)*(1-t)+Color.green(y)*t).toInt(),(Color.blue(x)*(1-t)+Color.blue(y)*t).toInt())
    private class IconSlot(private val a:MainActivity,var ink:Int):View(a){private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){Ambience.icon(c,"sound",0f,0f,width.toFloat(),ink,p,1.8f)}}
}

/** The «Звуки» panel (approved «А»): four tiles, the volume, your own sounds and «Добавить свой звук». */
internal class AmbienceSheet(private val a:MainActivity,private val readerBg:Int){
    private val sheetColor get()=if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt()
    private val pillColor get()=if(a.light)0x0f000000 else 0x11ffffff
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private var dialog:android.app.Dialog?=null
    private lateinit var sheet:LinearLayout
    private val tiles=mutableListOf<Tile>()
    private lateinit var off:TextView
    private lateinit var mineBox:LinearLayout
    private val update={paint()}

    fun show(){
        val dlg=android.app.Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=FrameLayout(a).apply{isClickable=true;setOnClickListener{close()}}
        sheet=a.column().apply{clipToPadding=false;clipChildren=false;isClickable=true;setPadding(a.d(16),a.d(12),a.d(16),a.d(18));elevation=a.d(16).toFloat()
            background=GradientDrawable().apply{setColor(sheetColor);cornerRadius=a.d(30).toFloat();setStroke(1,if(a.light)0x10000000 else 0x10ffffff)}}
        sheet.addView(View(a).apply{background=a.shape(if(a.light)0x33000000 else 0x38ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(14)})
        val head=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
        head.addView(a.text("Звуки",16f,a.fg,true),LinearLayout.LayoutParams(0,-2,1f))
        off=a.text("Выключить",13f,a.muted).apply{setPadding(a.d(10),a.d(8),a.d(4),a.d(8));isClickable=true;a.press(this);setOnClickListener{Ambience.stop(a)}}
        head.addView(off);sheet.addView(head,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(10);leftMargin=a.d(2)})
        // two rows of two tiles; each tile carries its own ring room (4) so nothing sticks out of the rows
        Ambience.builtIn.chunked(2).forEachIndexed{ri,pair->val row=a.line()
            pair.forEachIndexed{i,s->val t=Tile(s);tiles.add(t);t.setOnClickListener{toggle(s.key)};a.press(t);row.addView(t,LinearLayout.LayoutParams(0,a.d(104),1f).apply{if(i>0)leftMargin=a.d(2)})}
            sheet.addView(row,LinearLayout.LayoutParams(-1,-2).apply{leftMargin=-a.d(4);rightMargin=-a.d(4);if(ri>0)topMargin=a.d(2)})}
        sheet.addView(VolumeBar(),LinearLayout.LayoutParams(-1,a.d(50)).apply{topMargin=a.d(10)})
        sheet.addView(a.text("СВОИ ЗВУКИ",12f,a.muted,true).apply{letterSpacing=.06f},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(18);bottomMargin=a.d(10);leftMargin=a.d(4)})
        mineBox=a.column();sheet.addView(mineBox,LinearLayout.LayoutParams(-1,-2))
        val add=a.line().apply{gravity=Gravity.CENTER;isClickable=true;a.press(this);contentDescription="Добавить свой звук"
            background=GradientDrawable().apply{cornerRadius=a.d(18).toFloat();setStroke(a.d(1),if(a.light)0x26000000 else 0x2effffff,a.d(5).toFloat(),a.d(4).toFloat())}
            addView(a.text("＋",17f,a.fg,true));addView(a.text("Добавить свой звук",14f,a.fg,true).apply{setPadding(a.d(8),0,0,0)})
            setOnClickListener{a.pickSound{o->buildMine();toggle(Ambience.CUSTOM+o.str("id"))}}}
        sheet.addView(add,LinearLayout.LayoutParams(-1,a.d(50)))
        sheet.addView(a.text("MP3, M4A, OGG или WAV с телефона",11.5f,a.muted).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(8)})
        buildMine()
        val scroll=ScrollView(a).apply{isVerticalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false;clipChildren=false;addView(sheet)}
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),0,a.d(8),a.d(8))}
        root.addView(scroll,lp)
        dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,readerBg)
            if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.topMargin=s.top+a.d(60);lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;scroll.layoutParams=lp}
        dlg.setOnKeyListener{_,code,e->if(code==KeyEvent.KEYCODE_BACK&&e.action==KeyEvent.ACTION_UP){close();true}else false}
        dlg.setOnDismissListener{Ambience.unlisten(update)}
        Ambience.listen(update);paint()
        dlg.show()
        if(!reduced){scroll.translationY=a.d(1200).toFloat();scroll.post{val h=scroll.height+a.d(24).toFloat();LiquidSpring{v,_->scroll.translationY=h*v}.apply{snap(1f);to(0f)}}}
    }
    private fun close(){val d=dialog?:return;dialog=null
        val v=(sheet.parent as View)
        if(reduced){d.dismiss();return}
        v.animate().translationY(v.height+a.d(24).toFloat()).setDuration(240).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}
    private fun toggle(k:String){if(Ambience.key==k)Ambience.stop(a) else Ambience.play(a,k)}
    private fun paint(){tiles.forEach{it.refresh()};off.visibility=if(Ambience.key!=null)View.VISIBLE else View.INVISIBLE
        for(i in 0 until mineBox.childCount)(mineBox.getChildAt(i) as? MineRow)?.refresh()}
    private fun buildMine(){mineBox.removeAllViews();Ambience.mine(a).forEach{o->mineBox.addView(MineRow(o),LinearLayout.LayoutParams(-1,a.d(54)).apply{bottomMargin=a.d(8)})}}

    /** A sound tile: its colour glowing from the top right over a deep shade, the icon, the name and a quiet line;
     *  the chosen one in the same ring as the chosen theme, with dancing bars. */
    private inner class Tile(val s:Ambience.Sound):FrameLayout(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private val ring=a.d(4).toFloat()
        private val bars=EqBars(a,Color.WHITE)
        private val name=a.text(s.name,14.5f,Color.WHITE,true)
        private val note=a.text(s.note,11.5f,0x8cffffff.toInt())
        private var on=0f;private var anim:ValueAnimator?=null
        init{setWillNotDraw(false);contentDescription=s.name
            val texts=a.column();texts.addView(name);texts.addView(note,LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
            addView(texts,LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply{leftMargin=a.d(18);bottomMargin=a.d(16)})
            addView(bars,LayoutParams(a.d(17),a.d(15),Gravity.TOP or Gravity.END).apply{topMargin=a.d(20);rightMargin=a.d(20)})}
        fun refresh(){val chosen=Ambience.key==s.key;note.text=if(chosen)(if(Ambience.playing)"играет" else "на паузе") else s.note
            bars.visibility=if(chosen)View.VISIBLE else View.INVISIBLE;bars.live=chosen&&Ambience.playing
            val to=if(chosen)1f else 0f;if(to==on&&anim==null)return
            anim?.cancel();if(reduced){on=to;invalidate();return}
            anim=ValueAnimator.ofFloat(on,to).apply{duration=260;interpolator=PathInterpolator(.2f,0f,0f,1f);addUpdateListener{on=it.animatedValue as Float;invalidate()}
                addListener(object:android.animation.AnimatorListenerAdapter(){override fun onAnimationEnd(x:android.animation.Animator){anim=null}});start()}}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=a.d(20).toFloat()
            val l=ring;val t=ring;val rr=w-ring;val b=h-ring
            p.style=Paint.Style.FILL
            p.shader=LinearGradient(l,t,rr,b,s.deep,if(a.light)0xff2a2a2d.toInt() else 0xff1b1b1d.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(l,t,rr,b,r,r,p)
            val glow=(s.glow and 0xffffff) or ((0x66+(0xcc-0x66)*on).toInt() shl 24)
            p.shader=RadialGradient(rr,t,(rr-l)*1.05f,intArrayOf(glow,(s.glow and 0xffffff) or 0x18000000,0),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP);c.drawRoundRect(l,t,rr,b,r,r,p);p.shader=null
            Ambience.icon(c,s.icon,l+a.d(14),t+a.d(14),a.d(28).toFloat(),Color.WHITE,p)
            if(on>0f){p.style=Paint.Style.STROKE;p.strokeWidth=a.d(2).toFloat();p.color=a.fg;p.alpha=(255*on).toInt();val k=a.d(1).toFloat();c.drawRoundRect(k,k,w-k,h-k,r+ring-k,r+ring-k,p);p.alpha=255;p.style=Paint.Style.FILL}}
    }
    /** Your own sound: a note, its name and length, dancing bars when it plays, a bin to remove it. */
    private inner class MineRow(val o:JSONObject):LinearLayout(a){
        private val key=Ambience.CUSTOM+o.str("id");private val bars=EqBars(a,a.fg)
        private val ring=GradientDrawable().apply{setColor(pillColor);cornerRadius=a.d(18).toFloat()}
        init{orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(14),0,a.d(4),0);background=ring;isClickable=true;a.press(this);setOnClickListener{toggle(key)}
            addView(object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){Ambience.icon(c,"note",0f,0f,width.toFloat(),a.muted,p)}},LayoutParams(a.d(18),a.d(18)).apply{marginEnd=a.d(12)})
            val texts=a.column();texts.addView(a.text(o.str("name"),14f,a.fg).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
            val d=o.optLong("dur")/1000;texts.addView(a.text("${d/60}:${"%02d".format(d%60)} · по кругу",11.5f,a.muted),LayoutParams(-2,-2).apply{topMargin=a.d(2)})
            addView(texts,LayoutParams(0,-2,1f))
            addView(bars,LayoutParams(a.d(17),a.d(15)).apply{marginEnd=a.d(6)})
            addView(IconView(a,"trash",a.muted).apply{isClickable=true;contentDescription="Удалить звук";setPadding(a.d(9),a.d(9),a.d(9),a.d(9));a.press(this)
                setOnClickListener{a.confirm("Удалить звук?","«${o.str("name")}» пропадёт из списка."){Ambience.remove(a,o);a.savePreferences();buildMine();paint()}}},LayoutParams(a.d(40),a.d(40)))
            refresh()}
        fun refresh(){val chosen=Ambience.key==key;bars.visibility=if(chosen)View.VISIBLE else View.GONE;bars.live=chosen&&Ambience.playing
            ring.setStroke(if(chosen)a.d(2) else 0,a.fg)}
    }
    /** Volume as a capsule like «Яркость»: a speaker and «Громкость» on the left, the number on the right. */
    private inner class VolumeBar:View(a){
        private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var v=Ambience.volume(a)
        init{isClickable=true;contentDescription="Громкость"}
        override fun onTouchEvent(e:MotionEvent):Boolean{when(e.actionMasked){
            MotionEvent.ACTION_DOWN->{parent?.requestDisallowInterceptTouchEvent(true);set(e.x,false)}
            MotionEvent.ACTION_MOVE->set(e.x,false)
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->set(e.x,true)};return true}
        private fun set(x:Float,done:Boolean){v=(x/width).coerceIn(0f,1f);invalidate();Ambience.setVolume(a,v,done)}
        override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=h/2
            p.style=Paint.Style.FILL;p.shader=null;p.color=if(a.light)0x14000000 else 0x12ffffff;c.drawRoundRect(0f,0f,w,h,r,r,p)
            val fx=(w*v).coerceAtLeast(h)
            p.shader=LinearGradient(0f,0f,fx,0f,if(a.light)0xff9a9a9e.toInt() else 0xff55555a.toInt(),if(a.light)0xff5c5c60.toInt() else 0xff9a9aa0.toInt(),Shader.TileMode.CLAMP);c.drawRoundRect(0f,0f,fx,h,r,r,p);p.shader=null
            val ink=if(a.light)Color.WHITE else 0xff141416.toInt();val dk=a.d(1).toFloat()
            c.save();c.translate(dk*16,h/2-dk*9);c.scale(dk*18/24f,dk*18/24f);p.style=Paint.Style.STROKE;p.strokeWidth=1.9f;p.strokeJoin=Paint.Join.ROUND;p.color=ink
            c.drawPath(Path().apply{moveTo(4f,9.5f);lineTo(4f,14.5f);lineTo(7.5f,14.5f);lineTo(12f,18f);lineTo(12f,6f);lineTo(7.5f,9.5f);close()},p)
            if(v>0f)c.drawArc(11f,8f,18f,16f,-55f,110f,false,p);c.restore();p.style=Paint.Style.FILL
            p.textSize=dk*14;p.typeface=PolkaFonts.sans(500);p.color=if(fx>dk*120)ink else a.fg;c.drawText("Громкость",dk*42,h/2+dk*5,p)
            p.typeface=PolkaFonts.sans(600);p.textSize=dk*13.5f;p.color=a.fg;p.textAlign=Paint.Align.RIGHT;c.drawText("${(v*100).toInt()}",w-dk*18,h/2+dk*5,p);p.textAlign=Paint.Align.LEFT}
    }
}
