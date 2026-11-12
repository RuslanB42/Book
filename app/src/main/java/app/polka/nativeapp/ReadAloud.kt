package app.polka.nativeapp

import android.app.*
import android.content.*
import android.graphics.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import java.text.BreakIterator
import kotlin.math.*

/** Text colour that can change in place every frame (the voice's ink). */
internal class InkSpan(var color:Int):android.text.style.CharacterStyle(),android.text.style.UpdateAppearance{
    override fun updateDrawState(tp:android.text.TextPaint){tp.color=color}
}

/** Small filled media glyphs for the player: play, pause, previous and next sentence. */
internal class MediaGlyph(c:Context,var kind:String,var ink:Int):View(c){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    private val play=androidx.core.graphics.PathParser.createPathFromPathData("M8.2 5.2v13.6a1 1 0 0 0 1.5.86l11-6.8a1 1 0 0 0 0-1.72l-11-6.8a1 1 0 0 0-1.5.86Z")
    private val back=androidx.core.graphics.PathParser.createPathFromPathData("M12 7.2v9.6a.8.8 0 0 1-1.25.66L4.6 12.66a.8.8 0 0 1 0-1.32l6.15-4.8A.8.8 0 0 1 12 7.2ZM20 7.2v9.6a.8.8 0 0 1-1.25.66l-6.15-4.8a.8.8 0 0 1 0-1.32l6.15-4.8A.8.8 0 0 1 20 7.2Z")
    var size=20f
    /** Changes the glyph with a small squeeze, so play and pause never just blink. */
    fun morph(k:String,animate:Boolean){if(!animate){kind=k;invalidate();return}
        animate().cancel();animate().scaleX(.55f).scaleY(.55f).alpha(0f).setDuration(110).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{kind=k;invalidate()
            animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}.start()}
    override fun onDraw(c:Canvas){val px=size*resources.displayMetrics.density;c.save();c.translate((width-px)/2f,(height-px)/2f);c.scale(px/24f,px/24f);p.color=ink
        when(kind){"play"->c.drawPath(play,p)
            "pause"->{c.drawRoundRect(6.5f,5f,10.5f,19f,1.6f,1.6f,p);c.drawRoundRect(13.5f,5f,17.5f,19f,1.6f,1.6f,p)}
            "back"->c.drawPath(back,p)
            "next"->{c.scale(-1f,1f,12f,12f);c.drawPath(back,p)}}
        c.restore()}
}

/** One line of a sentence being read: a flat colour, or on the line where the voice is, a soft horizontal edge
 *  between said and unsaid. Whole lines only, so the text is shaped exactly as before (no per-letter pieces). */
internal class InkLine(val sentence:Int,val line:Int,val from:Int,val to:Int):android.text.style.CharacterStyle(),android.text.style.UpdateAppearance{
    var color=0;var shader:Shader?=null
    override fun updateDrawState(tp:android.text.TextPaint){if(shader!=null){tp.color=0xff000000.toInt();tp.shader=shader}else{tp.color=color;tp.shader=null}}
}

/** Reading aloud with the system voice. The text fills with ink behind the voice (approved «Чернила»): the page is
 *  dimmed, the sentence being read is brighter, and what has been said is in full colour, sweeping on smoothly. */
internal class ReadAloud(private val a:MainActivity,private val r:NativeReader){
    companion object{var current:ReadAloud?=null
        val rates=listOf(.8f,1f,1.2f,1.5f,2f)}
    private var tts:TextToSpeech?=null;private var ttsReady=false;private var pendingStart:Int?=null
    var playing=false;private set
    private var chapterIdx=-1
    private var text:String=""
    private var sentences:List<IntRange> = emptyList()
    private var sIndex=0;private var nextToQueue=0
    private val utter=HashMap<String,Pair<Int,Int>>() // id -> (sentence, start offset in chapter)
    private val startedAt=HashMap<String,Long>()
    // where the voice is: an anchor (sentence start or the last word the engine reported) moving on at the learned pace
    private var anchor=0f;private var anchorAt=0L;private var cap=0f;private var sentEnd=0f;private var ranges=false
    private var lastWordStart=-1
    private var cpm=.0135f // characters per millisecond at speed 1, learned from what the voice actually takes
    private var pos=0f
    private val amt=HashMap<Int,Float>()
    private val lines=HashMap<Int,List<InkLine>>()
    private var rest:InkSpan?=null;private var spannedText:CharSequence?=null;private var spannedLayout:android.text.Layout?=null
    private var dim=0f;private var stopping=false
    private var lastFrame=0L;private var looping=false
    private var sleepAt=0L;var sleepMode="off";private set
    private var focus:AudioFocusRequest?=null
    private var switching=false
    private var player:View?=null;private var playBtn:MediaGlyph?=null;private var playerTitle:TextView?=null;private var playerSub:TextView?=null;private var speedChip:TextView?=null
    private var sheet:ReadAloudSheet?=null
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    var rate:Float get()=a.prefs.optDouble("ttsRate",1.0).toFloat();set(v){a.prefs.put("ttsRate",v.toDouble());a.savePreferences()}
    var duck:Boolean get()=a.prefs.optBoolean("ttsDuck",true);set(v){a.prefs.put("ttsDuck",v);a.savePreferences();Ambience.duck(a,v&&playing)}
    private val body get()=r.body as MarkedTextView
    private val reduced get()=a.prefs.str("motion")=="reduced"
    private val noisy=object:BroadcastReceiver(){override fun onReceive(c:Context,i:Intent){if(i.action==AudioManager.ACTION_AUDIO_BECOMING_NOISY)pause()}}
    private var noisyOn=false

    // ── starting and the voice ──
    fun start(offset:Int){
        current?.takeIf{it!==this}?.stop(false);current=this;stopping=false
        if(tts==null){tts=TextToSpeech(a.applicationContext){st->handler.post{
            if(st!=TextToSpeech.SUCCESS){Notice.short(a,Notice.Kind.ERROR,"На телефоне нет синтеза речи");stop(false);return@post}
            ttsReady=true;setupVoice();pendingStart?.let{pendingStart=null;speakFrom(it)}}}
            tts?.setOnUtteranceProgressListener(listener)}
        // the player takes the place of the reader's bars
        r.hideChrome()
        showPlayer();loop()
        if(ttsReady)speakFrom(offset) else pendingStart=offset
    }
    private fun setupVoice(){val t=tts?:return
        t.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        val res=t.setLanguage(r.bookLocale())
        if(res==TextToSpeech.LANG_MISSING_DATA||res==TextToSpeech.LANG_NOT_SUPPORTED)Notice.short(a,Notice.Kind.INFO,"Голоса для языка книги нет, нужен голос в настройках синтеза речи")
        t.setSpeechRate(rate)}
    private fun loadChapter(){
        if(chapterIdx==r.chapter&&text.isNotEmpty())return
        chapterIdx=r.chapter
        text=r.selectionFullText().toString().replace('￼',' ')
        val out=ArrayList<IntRange>();val bi=BreakIterator.getSentenceInstance(r.bookLocale());bi.setText(text)
        var s=bi.first();var e=bi.next()
        while(e!=BreakIterator.DONE){var a0=s;var b0=e;while(a0<b0&&text[a0].isWhitespace())a0++;while(b0>a0&&text[b0-1].isWhitespace())b0--
            if(b0>a0&&text.substring(a0,b0).any{it.isLetterOrDigit()}){
                var from=a0;while(b0-from>1200){var cut=text.lastIndexOf(' ',from+1200);if(cut<=from)cut=from+1200;out.add(from until cut);from=cut;while(from<b0&&text[from].isWhitespace())from++}
                if(b0>from)out.add(from until b0)}
            s=e;e=bi.next()}
        sentences=out;amt.clear();dropLines()
    }
    fun speakFrom(offset:Int){
        if(!r.chapterReady()){handler.postDelayed({if(current===this&&!stopping)speakFrom(offset)},120);return}
        loadChapter();switching=false;if(sentences.isEmpty()){chapterEnded();return}
        val t=tts?:return
        t.stop();utter.clear();startedAt.clear()
        val i=sentences.indexOfFirst{it.last>=offset}.let{if(it<0)sentences.lastIndex else it}
        if(i!=sIndex)amt[sIndex]?.let{}  // the old sentence settles on its own
        sIndex=i;nextToQueue=i
        val from=offset.coerceIn(sentences[i].first,sentences[i].last)
        anchor=from.toFloat();anchorAt=System.currentTimeMillis();cap=(sentences[i].last+1).toFloat();sentEnd=cap;ranges=false;pos=from.toFloat();lastWordStart=-1
        if(!amt.containsKey(i))amt[i]=0f
        playing=true;requestFocus();Ambience.duck(a,duck);registerNoisy(true)
        queue(from);queue();queue()
        loop();syncPlayer();sheet?.syncPlay();ReadAloudService.update(a)
    }
    private fun queue(startAt:Int?=null){val t=tts?:return;if(nextToQueue>=sentences.size)return
        val i=nextToQueue++;val rng=sentences[i];val s=startAt?:rng.first
        val id="u${chapterIdx}_${i}_$s";utter[id]=i to s
        t.speak(text.substring(s,rng.last+1),TextToSpeech.QUEUE_ADD,null,id)}
    private val listener=object:UtteranceProgressListener(){
        override fun onStart(id:String){handler.post{val u=utter[id]?:return@post;startedAt[id]=System.currentTimeMillis()
            if(u.first!=sIndex)pos=u.second.toFloat()
            sIndex=u.first;if(!amt.containsKey(sIndex))amt[sIndex]=0f
            anchor=u.second.toFloat();anchorAt=System.currentTimeMillis();sentEnd=(sentences.getOrNull(u.first)?.last?.plus(1)?:u.second).toFloat();cap=sentEnd;ranges=false;lastWordStart=-1;syncPlayer()}}
        override fun onRangeStart(id:String,start:Int,end:Int,frame:Int){handler.post{val u=utter[id]?:return@post;if(u.first!=sIndex)return@post;word(u.second+start,u.second+end)}}
        override fun onDone(id:String){handler.post{val u=utter[id]?:return@post;utter.remove(id)
            // learn the pace from how long the whole sentence took
            startedAt.remove(id)?.let{t0->val dur=(System.currentTimeMillis()-t0).toFloat();val chars=(sentences.getOrNull(u.first)?.last?.plus(1)?:u.second)-u.second
                if(dur>400f&&chars>8){val v=chars/dur/rate;cpm=cpm*.6f+v.coerceIn(.006f,.03f)*.4f}}
            if(u.first==sIndex){anchor=sentEnd;cap=sentEnd}
            if(!playing)return@post
            if(sleepMode=="chapter"&&u.first==sentences.lastIndex){pause();sleepMode="off";sheet?.refresh();return@post}
            if(u.first==sentences.lastIndex)chapterEnded() else queue()}}
        @Deprecated("") override fun onError(id:String){handler.post{utter.remove(id)}}
        override fun onError(id:String,code:Int){handler.post{utter.remove(id)}}
    }
    private fun word(ws:Int,we:Int){
        ranges=true;lastWordStart=ws
        // the ink never runs ahead of the voice and never jumps back: it is re-anchored at the word just begun
        anchor=max(pos,ws.toFloat());anchorAt=System.currentTimeMillis();cap=min(sentEnd,we+1f)
        follow(ws)}
    /** The page goes along with the voice: when the word leaves the page, the page turns. */
    private fun follow(offset:Int){
        if(r.chapter!=chapterIdx)return
        if(r.paged()){val lim=r.pageLimit();if(offset>=lim&&offset<lim+400){if(a.hasWindowFocus())r.turn(1) else r.jumpOffset(offset)}}
        else{val l=body.layout?:return;val line=l.getLineForOffset(offset.coerceIn(0,l.text.length));val y=l.getLineTop(line)+body.paddingTop
            val sc=r.scroll;if(y>sc.scrollY+sc.height*.72f||y<sc.scrollY)sc.smoothScrollTo(0,(y-sc.height*.3f).toInt().coerceAtLeast(0))}
    }
    private fun chapterEnded(){
        if(r.chapter+1>=r.chapters.length()){pause();Notice.short(a,Notice.Kind.OK,"Книга дочитана вслух");return}
        switching=true;dropLines();amt.clear();r.changeChapter(r.chapter+1)
        handler.postDelayed({if(current===this&&playing)speakFrom(0)},250)
    }

    // ── controls ──
    fun pause(){if(!playing)return;playing=false;tts?.stop();utter.clear();Ambience.duck(a,false);registerNoisy(false);abandonFocus();syncPlayer();ReadAloudService.update(a);sheet?.syncPlay()}
    fun resume(){if(playing)return;val at=if(lastWordStart>=0)lastWordStart else max(pos.toInt(),sentences.getOrNull(sIndex)?.first?:r.visibleStart());speakFrom(at)}
    fun toggle(){if(playing)pause() else resume()}
    fun next(){val n=sIndex+1;if(n<sentences.size)speakFrom(sentences[n].first) else chapterEnded()}
    fun prev(){val cur=sentences.getOrNull(sIndex)?:return
        val i=if(pos-cur.first<12&&sIndex>0)sIndex-1 else sIndex;speakFrom(sentences[i].first)}
    fun changeRate(v:Float){rate=v;tts?.setSpeechRate(v);if(playing){val at=if(lastWordStart>=0)lastWordStart else max(pos.toInt(),sentences.getOrNull(sIndex)?.first?:0);speakFrom(at)};syncPlayer();sheet?.refresh()}
    fun setSleep(mode:String){sleepMode=mode;sleepAt=when(mode){"15"->System.currentTimeMillis()+15*60_000L;"30"->System.currentTimeMillis()+30*60_000L;else->0L};sheet?.refresh()}
    /** Stops reading: the page brightens back and the player slides away (at once when [animated] is false). */
    fun stop(animated:Boolean=true){
        if(stopping&&animated)return
        playing=false;tts?.stop();tts?.shutdown();tts=null;ttsReady=false;utter.clear()
        Ambience.duck(a,false);registerNoisy(false);abandonFocus()
        sheet?.close();sheet=null
        if(current===this)current=null
        r.readAloud=null;ReadAloudService.stop(a)
        stopping=true
        val p=player
        if(animated&&!reduced&&looping){p?.animate()?.alpha(0f)?.translationY(a.d(28).toFloat())?.setDuration(240)?.setInterpolator(PathInterpolator(.4f,0f,1f,1f))?.withEndAction{(p.parent as? ViewGroup)?.removeView(p)}?.start();player=null;return}
        finish();p?.let{(it.parent as? ViewGroup)?.removeView(it)};player=null
    }
    private fun finish(){looping=false;dropLines();removeRest();body.onNewText=null;body.invalidate()}
    private fun requestFocus(){if(focus!=null)return
        val am=a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val f=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener{c->if(c==AudioManager.AUDIOFOCUS_LOSS||c==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)handler.post{pause()}}.build()
        am.requestAudioFocus(f);focus=f}
    private fun abandonFocus(){val f=focus?:return;focus=null;(a.getSystemService(Context.AUDIO_SERVICE) as AudioManager).abandonAudioFocusRequest(f)}
    private fun registerNoisy(on:Boolean){if(on==noisyOn)return;noisyOn=on
        try{if(on)a.registerReceiver(noisy,IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) else a.unregisterReceiver(noisy)}catch(_:Exception){}}

    // ── the ink ──
    private fun ink()=r.body.currentTextColor
    private fun withAlpha(c:Int,al:Float)=(c and 0xffffff) or ((al*255).roundToInt().coerceIn(0,255) shl 24)
    private fun mix(x:Int,y:Int,f:Float):Int{val t=f.coerceIn(0f,1f)
        fun ch(sh:Int)=(((x ushr sh) and 255)+((((y ushr sh) and 255)-((x ushr sh) and 255))*t)).roundToInt().coerceIn(0,255)
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)}
    private fun ease(x:Float)=if(x<=0f)0f else if(x>=1f)1f else x*x*(3-2*x)
    /** A picture of the next page (page turn) gets the same dimmed ink. */
    fun dimmed(t:CharSequence):CharSequence{val s=SpannableString(t);s.setSpan(InkSpan(mix(ink(),withAlpha(ink(),.4f),dim)),0,s.length,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return s}
    private fun dropLines(){val t=spannedText as? Spannable;lines.values.flatten().forEach{t?.removeSpan(it)};lines.clear()}
    private fun removeRest(){val t=r.body.text as? Spannable;if(t!=null)t.getSpans(0,t.length,InkSpan::class.java).forEach{t.removeSpan(it)};(spannedText as? Spannable)?.let{s->rest?.let{s.removeSpan(it)}};rest=null;spannedText=null}
    private fun prepareText():Spannable?{val t=r.body.text as? Spannable?:return null
        if(spannedText===t&&rest!=null)return t
        lines.clear();spannedText=t;spannedLayout=null
        rest=InkSpan(ink()).also{t.setSpan(it,0,t.length,Spanned.SPAN_INCLUSIVE_INCLUSIVE)}
        return t}
    private fun loop(){if(looping)return;looping=true;lastFrame=System.nanoTime()
        body.onNewText={spannedText=null;rest=null;lines.clear();if(looping&&r.chapter==chapterIdx)paint()}
        Choreographer.getInstance().postFrameCallback(frame)}
    private val frame=object:Choreographer.FrameCallback{override fun doFrame(now:Long){
        if(!looping)return
        val dt=((now-lastFrame)/1e6f).coerceIn(0f,100f);lastFrame=now
        if(r.chapter!=chapterIdx&&playing&&!switching&&chapterIdx>=0){pause()}
        if(sleepAt>0&&System.currentTimeMillis()>=sleepAt){sleepAt=0;sleepMode="off";pause();sheet?.refresh()}
        // the page dims in when reading starts and brightens back when it stops
        val dimTarget=if(stopping)0f else 1f
        dim=if(reduced)dimTarget else dim+(dimTarget-dim)*(1f-exp(-dt/(if(stopping)140f else 180f)))
        if(playing){val tNow=System.currentTimeMillis()
            val target=min(cap,anchor+(tNow-anchorAt)*cpm*rate)
            // follow the target closely but smoothly; never move backwards within a sentence
            if(target>pos)pos+=(target-pos)*(1f-exp(-dt/55f))}
        val keys=(amt.keys+sIndex).toSet()
        for(k in keys){val target=if(k==sIndex&&!stopping)1f else 0f;val v=amt[k]?:0f
            val tau=if(target>v)140f else 320f;val nv=if(reduced)target else v+(target-v)*(1f-exp(-dt/tau))
            if(nv<.004f&&target==0f){amt.remove(k);lines.remove(k)?.forEach{(spannedText as? Spannable)?.removeSpan(it)}} else amt[k]=nv}
        paint()
        placePlayer(dt)
        if(stopping&&dim<.01f&&amt.isEmpty()){finish();return}
        if((now/500_000_000L)!=((now-(dt*1e6f).toLong())/500_000_000L))syncPlayer()
        Choreographer.getInstance().postFrameCallback(this)}}
    /** Spans for the lines of sentence [k] on the shown text (page coordinates). Whole lines only. */
    private fun linesFor(k:Int,t:Spannable,l:android.text.Layout,base:Int):List<InkLine>{
        val rng=sentences.getOrNull(k)?:return emptyList()
        val from=max(rng.first,base)-base;val to=min(rng.last+1,base+t.length)-base
        if(to<=from)return emptyList()
        val out=ArrayList<InkLine>()
        var line=l.getLineForOffset(from);val last=l.getLineForOffset((to-1).coerceAtLeast(from))
        while(line<=last){val s=max(from,l.getLineStart(line));val e=min(to,l.getLineEnd(line));if(e>s){val sp=InkLine(k,line,s,e);t.setSpan(sp,s,e,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);out.add(sp)};line++}
        return out}
    private fun paint(){
        val t=prepareText()?:return
        val c=ink();val cRest=mix(c,withAlpha(c,.4f),dim);val cUp=withAlpha(c,.66f);val cRead=withAlpha(c,1f)
        var changed=false
        rest?.let{if(it.color!=cRest){it.color=cRest;changed=true}}
        val l=body.layout
        if(l!=null&&r.chapter==chapterIdx&&sentences.isNotEmpty()){
            if(spannedLayout!==l){dropLines();spannedLayout=l}
            val base=r.pageBase()
            val soft=body.textSize*.9f
            for((k,v) in amt){val list=lines.getOrPut(k){linesFor(k,t,l,base)}
                for(sp in list){
                    val readAll=k<sIndex;val cur=k==sIndex
                    val pageOff=pos-base
                    var col:Int;var sh:Shader?=null
                    if(readAll)col=mix(cRest,cRead,v)
                    else if(!cur)col=mix(cRest,cUp,v)
                    else if(pageOff>=sp.to)col=mix(cRest,cRead,v)
                    else if(pageOff<=sp.from)col=mix(cRest,cUp,v)
                    else{
                        // the edge between said and unsaid, at the drawn x of the voice's position on this line
                        val i0=floor(pageOff).toInt().coerceIn(sp.from,sp.to);val i1=(i0+1).coerceAtMost(sp.to);val f=pageOff-i0
                        val x0=JustifiedGeometry.x(body,l,sp.line,i0);val x1=JustifiedGeometry.x(body,l,sp.line,i1)
                        val x=x0+(x1-x0)*f
                        col=mix(cRest,cRead,v)
                        sh=LinearGradient(x-soft,0f,x+soft*.6f,0f,mix(cRest,cRead,v),mix(cRest,cUp,v),Shader.TileMode.CLAMP)}
                    if(sp.color!=col||sh!=null||sp.shader!=null){sp.color=col;sp.shader=sh;changed=true}}}}
        if(changed)r.body.invalidate()
    }

    // ── the mini player ──
    private fun showPlayer(){if(player!=null)return
        val dark=!a.light
        val pill=a.line().apply{setPadding(a.d(8),0,a.d(6),0);elevation=a.d(14).toFloat()
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(dark)0xff2a2a2d.toInt() else Color.WHITE);cornerRadius=a.d(32).toFloat()}
            outlineProvider=ViewOutlineProvider.BACKGROUND;isClickable=true;setOnClickListener{openSheet()}}
        val fg=if(dark)0xfff3f2ef.toInt() else 0xff1d1c1a.toInt()
        fun glyph(k:String,fn:()->Unit)=MediaGlyph(a,k,fg).apply{isClickable=true;contentDescription=if(k=="back")"Предложение назад" else "Предложение вперёд";a.press(this);setOnClickListener{fn()}}
        pill.addView(glyph("back"){prev()},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        val pb=MediaGlyph(a,"pause",if(dark)0xff161617.toInt() else Color.WHITE).apply{background=a.shape(fg,24);isClickable=true;contentDescription="Пауза";a.press(this);setOnClickListener{toggle()}}
        playBtn=pb;pill.addView(pb,LinearLayout.LayoutParams(a.d(48),a.d(48)))
        pill.addView(glyph("next"){next()},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        val mid=a.column().apply{setPadding(a.d(8),0,a.d(6),0)}
        playerTitle=a.text("",13.5f,fg,true).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END};mid.addView(playerTitle)
        playerSub=a.text("",12.5f,0xff8b8b91.toInt()).apply{maxLines=1};mid.addView(playerSub)
        pill.addView(mid,LinearLayout.LayoutParams(0,-2,1f))
        speedChip=a.text("",13f,fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(11),0,a.d(11),0);background=a.shape(if(dark)0x12ffffff else 0x0d000000,17);isClickable=true;a.press(this)
            setOnClickListener{val i=rates.indexOfFirst{abs(it-rate)<.01f};changeRate(rates[(i+1)%rates.size])}}
        pill.addView(speedChip,LinearLayout.LayoutParams(-2,a.d(34)).apply{marginEnd=a.d(2)})
        pill.addView(IconView(a,"x",if(dark)0xffa9a9ae.toInt() else 0xff6e6e73.toInt()).apply{isClickable=true;contentDescription="Остановить чтение";setOnClickListener{stop()}},LinearLayout.LayoutParams(a.d(44),a.d(44)))
        r.view.addView(pill,FrameLayout.LayoutParams(-1,a.d(64),Gravity.BOTTOM).apply{leftMargin=a.d(16);rightMargin=a.d(16);bottomMargin=a.d(24)})
        player=pill;shown=0f;pill.alpha=0f;pill.translationY=a.d(28).toFloat()
        syncPlayer()
    }
    private var shown=0f
    /** The player slides in when reading starts, and out of the way while the reader's own bars are open. */
    private fun placePlayer(dt:Float){val p=player?:return
        val target=if(r.chromeVisible||stopping)0f else 1f
        shown=if(reduced)target else shown+(target-shown)*(1f-exp(-dt/(if(target>shown)110f else 80f)))
        p.alpha=shown;p.translationY=(1f-shown)*a.d(28);p.visibility=if(shown<.02f)View.INVISIBLE else View.VISIBLE}
    internal fun minutesLeft():Int{if(sentences.isEmpty())return 0;val left=(text.length-pos).coerceAtLeast(0f);return ceil(left/(cpm*rate)/60000f).toInt().coerceAtLeast(1)}
    internal fun minutesDone():Int=((pos-(sentences.firstOrNull()?.first?:0)).coerceAtLeast(0f)/(cpm*rate)/60000f).toInt()
    internal fun chapterFraction()=if(text.isEmpty())0f else (pos/text.length).coerceIn(0f,1f)
    internal fun chapterTitle():String{val n=r.chapterName(r.chapter).trim();return if(n.isNotEmpty()&&n.all{it.isDigit()})"Глава $n" else n}
    internal fun bookTitle()=r.book.str("title")
    private fun rateLabel(v:Float)=(if(v==v.roundToInt().toFloat())"${v.roundToInt()}" else "%.1f".format(java.util.Locale("ru"),v))+"×"
    private fun syncPlayer(){playBtn?.let{val k=if(playing)"pause" else "play";if(it.kind!=k)it.morph(k,!reduced);it.contentDescription=if(playing)"Пауза" else "Читать"}
        playerTitle?.text=chapterTitle();playerSub?.text="ещё ${minutesLeft()} мин";speedChip?.text=rateLabel(rate)}
    private fun openSheet(){if(sheet!=null)return;sheet=ReadAloudSheet(a,this,r).also{it.show()}}
    internal fun sheetClosed(){sheet=null}
}

/** Reading settings (approved mock): progress in the chapter, controls, speed, sleep timer, voice, quieter background sounds. */
internal class ReadAloudSheet(private val a:MainActivity,private val ra:ReadAloud,private val r:NativeReader){
    private var dialog:Dialog?=null;private lateinit var holder:LinearLayout;private lateinit var body:LinearLayout
    private val quiet get()=if(a.light)0x0f000000 else 0x0fffffff
    fun show(){val dlg=Dialog(a,R.style.PolkaSheetTheme);dialog=dlg
        val root=FrameLayout(a).apply{isClickable=true;setOnClickListener{close()}}
        holder=a.column().apply{isClickable=true;setPadding(a.d(20),a.d(12),a.d(20),a.d(22));elevation=a.d(16).toFloat()
            background=android.graphics.drawable.GradientDrawable().apply{setColor(if(a.light)0xfff7f7f5.toInt() else 0xff1f1f22.toInt());cornerRadius=a.d(28).toFloat()}}
        holder.addView(View(a).apply{background=a.shape(if(a.light)0x30000000 else 0x30ffffff,3)},LinearLayout.LayoutParams(a.d(36),a.d(5)).apply{gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=a.d(16)})
        body=a.column();holder.addView(body)
        val lp=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{setMargins(a.d(8),a.d(80),a.d(8),a.d(8))}
        root.addView(holder,lp);dlg.setContentView(root)
        dlg.window?.let{w->w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0));w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setLayout(-1,-1);w.setWindowAnimations(0)
            ReaderWindow.configure(w,true,r.backgroundColor);if(Build.VERSION.SDK_INT>=30)w.attributes=w.attributes.apply{setFitInsetsTypes(0);setFitInsetsSides(0)}}
        SafeArea.bind(root){i->val s=SafeArea.content(i,false);lp.bottomMargin=a.d(8)+s.bottom;lp.leftMargin=a.d(8)+s.left;lp.rightMargin=a.d(8)+s.right;holder.layoutParams=lp}
        dlg.setOnDismissListener{ra.sheetClosed()}
        refresh();dlg.show()
        if(a.prefs.str("motion")!="reduced"){holder.translationY=a.d(600).toFloat();holder.post{holder.animate().translationY(0f).setDuration(320).setInterpolator(PathInterpolator(.2f,0f,0f,1f)).start()}}
    }
    fun close(){val d=dialog?:return;dialog=null
        if(a.prefs.str("motion")=="reduced"){d.dismiss();return}
        holder.animate().translationY(holder.height+a.d(24).toFloat()).setDuration(220).setInterpolator(PathInterpolator(.4f,0f,1f,1f)).withEndAction{d.dismiss()}.start()}
    private fun chip(label:String,on:Boolean,fn:()->Unit)=a.text(label,13f,if(on)a.bg else a.fg,true).apply{gravity=Gravity.CENTER;setPadding(a.d(11),0,a.d(11),0);background=a.shape(if(on)a.fg else quiet,18);isClickable=true;a.press(this);setOnClickListener{fn()}}
    private fun label(s:String)=a.text(s.uppercase(),11f,a.muted,true).apply{letterSpacing=.06f}
    private var big:MediaGlyph?=null
    fun syncPlay(){big?.let{val k=if(ra.playing)"pause" else "play";if(it.kind!=k)it.morph(k,a.prefs.str("motion")!="reduced");it.contentDescription=if(ra.playing)"Пауза" else "Читать"}}
    fun refresh(){if(dialog==null&&!::body.isInitialized)return
        body.removeAllViews()
        body.addView(TextView(a).apply{text=ra.bookTitle();textSize=19f;setTextColor(a.fg);typeface=PolkaFonts.literata(a,true);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        body.addView(a.text(ra.chapterTitle(),13f,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END},LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(4)})
        val bar=object:View(a){val p=Paint(Paint.ANTI_ALIAS_FLAG);override fun onDraw(c:Canvas){val rr=height/2f;p.color=(a.fg and 0xffffff) or (0x18 shl 24);c.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),rr,rr,p);p.color=a.fg;c.drawRoundRect(0f,0f,max(height.toFloat(),width*ra.chapterFraction()),height.toFloat(),rr,rr,p)}}
        body.addView(bar,LinearLayout.LayoutParams(-1,a.d(4)).apply{topMargin=a.d(18)})
        val times=a.line();times.addView(a.text("${ra.minutesDone()} мин",12f,a.muted),LinearLayout.LayoutParams(0,-2,1f));times.addView(a.text("осталось ${ra.minutesLeft()} мин",12f,a.muted))
        body.addView(times,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(6)})
        val ctl=a.line().apply{gravity=Gravity.CENTER}
        ctl.addView(MediaGlyph(a,"back",a.fg).apply{size=26f;isClickable=true;contentDescription="Предложение назад";a.press(this);setOnClickListener{ra.prev()}},LinearLayout.LayoutParams(a.d(56),a.d(56)))
        ctl.addView(MediaGlyph(a,if(ra.playing)"pause" else "play",a.bg).apply{big=this;size=28f;background=a.shape(a.fg,34);isClickable=true;contentDescription=if(ra.playing)"Пауза" else "Читать";a.press(this);setOnClickListener{ra.toggle()}},LinearLayout.LayoutParams(a.d(68),a.d(68)).apply{marginStart=a.d(22);marginEnd=a.d(22)})
        ctl.addView(MediaGlyph(a,"next",a.fg).apply{size=26f;isClickable=true;contentDescription="Предложение вперёд";a.press(this);setOnClickListener{ra.next()}},LinearLayout.LayoutParams(a.d(56),a.d(56)))
        body.addView(ctl,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(14)})
        body.addView(label("Скорость"),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18);bottomMargin=a.d(8)})
        val sp=a.line();ReadAloud.rates.forEach{v->sp.addView(chip((if(v==v.roundToInt().toFloat())"${v.roundToInt()}" else "%.1f".format(java.util.Locale("ru"),v))+"×",abs(v-ra.rate)<.01f){ra.changeRate(v)},LinearLayout.LayoutParams(-2,a.d(36)).apply{marginEnd=a.d(6)})};body.addView(sp)
        body.addView(label("Таймер сна"),LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18);bottomMargin=a.d(8)})
        val sl=a.line();listOf("off" to "Выкл","15" to "15 мин","30" to "30 мин","chapter" to "Конец главы").forEach{(k,l)->sl.addView(chip(l,ra.sleepMode==k){ra.setSleep(k)},LinearLayout.LayoutParams(-2,a.d(36)).apply{marginEnd=a.d(6)})}
        body.addView(HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(sl)})
        val voice=a.line().apply{isClickable=true;setOnClickListener{try{a.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}catch(_:Exception){Notice.short(a,Notice.Kind.INFO,"Откройте настройки телефона: синтез речи")}}}
        val vc=a.column();vc.addView(a.text("Голос",15f,a.fg));vc.addView(a.text("Системный, ${r.bookLocale().getDisplayLanguage(java.util.Locale("ru"))}",12.5f,a.muted),LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
        voice.addView(vc,LinearLayout.LayoutParams(0,-2,1f));voice.addView(a.text("Изменить",14f,a.muted))
        body.addView(voice,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(18)})
        val duckRow=a.line().apply{isClickable=true;setOnClickListener{ra.duck=!ra.duck;refresh()}}
        val dc=a.column();dc.addView(a.text("Тише фоновые звуки",15f,a.fg));dc.addView(a.text("Камин и дождь приглушаются, пока звучит голос",12.5f,a.muted).apply{cssLeading(1.35f)},LinearLayout.LayoutParams(-2,-2).apply{topMargin=a.d(2)})
        duckRow.addView(dc,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=a.d(12)})
        duckRow.addView(Toggle(ra.duck),LinearLayout.LayoutParams(a.d(46),a.d(28)))
        body.addView(duckRow,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(16)})
    }
    private inner class Toggle(val on:Boolean):View(a){private val p=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c:Canvas){val h=height.toFloat();p.color=if(on)0xff34b866.toInt() else (a.fg and 0xffffff) or (0x26 shl 24);c.drawRoundRect(0f,0f,width.toFloat(),h,h/2,h/2,p)
            p.color=Color.WHITE;val r0=h/2-a.d(3);c.drawCircle(if(on)width-h/2 else h/2,h/2,r0,p)}}
}

/** Keeps reading when the screen is off: a media notification with the lock-screen and headphone controls. */
class ReadAloudService:Service(){
    companion object{
        private const val CH="read_aloud";private const val ID=4207
        @Volatile private var running=false
        internal fun update(c:Context){
            val ra=ReadAloud.current?:return
            if(!running){try{val i=Intent(c,ReadAloudService::class.java);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i) else c.startService(i)}catch(_:Exception){}}
            else instance?.post()
        }
        internal fun stop(c:Context){if(running)try{c.stopService(Intent(c,ReadAloudService::class.java))}catch(_:Exception){}}
        private var instance:ReadAloudService?=null
    }
    private var session:MediaSession?=null
    override fun onBind(i:Intent?)=null
    override fun onCreate(){super.onCreate();instance=this
        val nm=getSystemService(NotificationManager::class.java)
        if(Build.VERSION.SDK_INT>=26&&nm.getNotificationChannel(CH)==null)nm.createNotificationChannel(NotificationChannel(CH,"Чтение вслух",NotificationManager.IMPORTANCE_LOW).apply{setShowBadge(false)})
        session=MediaSession(this,"Листай").apply{
            setCallback(object:MediaSession.Callback(){
                override fun onPlay(){ReadAloud.current?.resume()}
                override fun onPause(){ReadAloud.current?.pause()}
                override fun onSkipToNext(){ReadAloud.current?.next()}
                override fun onSkipToPrevious(){ReadAloud.current?.prev()}
                override fun onStop(){ReadAloud.current?.stop()}})
            isActive=true}
    }
    override fun onStartCommand(i:Intent?,flags:Int,id:Int):Int{
        when(i?.action){"toggle"->ReadAloud.current?.toggle();"next"->ReadAloud.current?.next();"prev"->ReadAloud.current?.prev();"close"->ReadAloud.current?.stop()}
        val n=build()
        if(Build.VERSION.SDK_INT>=29)startForeground(ID,n,android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID,n)
        running=true
        if(ReadAloud.current==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        return START_NOT_STICKY
    }
    internal fun post(){try{getSystemService(NotificationManager::class.java).notify(ID,build())}catch(_:Exception){}}
    private fun action(icon:Int,title:String,act:String)=Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,icon),title,
        PendingIntent.getService(this,act.hashCode(),Intent(this,ReadAloudService::class.java).setAction(act),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
    private fun build():Notification{
        val ra=ReadAloud.current;val playing=ra?.playing==true
        session?.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,ra?.bookTitle()?:"Листай").putString(MediaMetadata.METADATA_KEY_ARTIST,ra?.chapterTitle()?:"").build())
        session?.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
            .setState(if(playing)PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,PlaybackState.PLAYBACK_POSITION_UNKNOWN,1f).build())
        val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b=if(Build.VERSION.SDK_INT>=26)Notification.Builder(this,CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(R.drawable.ic_stat_read).setContentTitle(ra?.bookTitle()?:"Листай").setContentText(ra?.chapterTitle()?:"").setContentIntent(open).setOngoing(playing).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(action(android.R.drawable.ic_media_previous,"Назад","prev"))
            .addAction(action(if(playing)android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,if(playing)"Пауза" else "Читать","toggle"))
            .addAction(action(android.R.drawable.ic_media_next,"Вперёд","next"))
            .setStyle(Notification.MediaStyle().setMediaSession(session?.sessionToken).setShowActionsInCompactView(0,1,2)).build()
    }
    override fun onDestroy(){running=false;instance=null;session?.release();session=null;super.onDestroy()}
}
