package app.polka.nativeapp

import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Temporary diagnostics for the statistics entrance: records every frame for a few seconds (where the time went —
 * animations, layout, drawing code, bitmap upload, GPU) and how long each custom view's drawing took, then shows a
 * report that can be copied. Used to find the real cause of the stutter instead of guessing.
 */
internal object StatsProbe{
    @Volatile var active=false
    private var t0=0L
    private val marks=ArrayList<String>()
    private val frames=ArrayList<String>()
    private val views=HashMap<String,LongArray>() // name → [count, total ns, max ns]
    private var nFrames=0;private var nSlow=0;private var lastVsync=0L
    private var thread:HandlerThread?=null
    private var listener:Window.OnFrameMetricsAvailableListener?=null
    fun t():Long=if(active)System.nanoTime() else 0L
    fun add(name:String,start:Long){if(!active||start==0L)return;val dt=System.nanoTime()-start
        synchronized(views){val v=views.getOrPut(name){LongArray(3)};v[0]++;v[1]+=dt;if(dt>v[2])v[2]=dt}}
    fun mark(what:String){if(!active)return;synchronized(marks){marks.add("${(System.nanoTime()-t0)/1_000_000} мс — $what")}}
    fun start(a:MainActivity){if(android.os.Build.VERSION.SDK_INT<26)return;stop(a)
        t0=System.nanoTime();marks.clear();frames.clear();views.clear();nFrames=0;nSlow=0;lastVsync=0L;active=true
        val th=HandlerThread("probe").also{it.start()};thread=th
        val l=Window.OnFrameMetricsAvailableListener{_,m,_->
            fun ms(id:Int)=m.getMetric(id)/1_000_000.0
            val vs=m.getMetric(FrameMetrics.VSYNC_TIMESTAMP);val gap=if(lastVsync==0L)0.0 else (vs-lastVsync)/1_000_000.0;lastVsync=vs
            val total=ms(FrameMetrics.TOTAL_DURATION);nFrames++
            if(total>17||gap>25){nSlow++
                val gpu=if(android.os.Build.VERSION.SDK_INT>=31)" gpu %.0f".format(ms(FrameMetrics.GPU_DURATION)) else ""
                synchronized(frames){frames.add("%d мс: кадр %.0f (пауза %.0f) · аним %.0f · раскл %.0f · рисов %.0f · загр %.0f · команды %.0f · swap %.0f%s".format(
                    (vs-t0)/1_000_000,total,gap,ms(FrameMetrics.ANIMATION_DURATION),ms(FrameMetrics.LAYOUT_MEASURE_DURATION),ms(FrameMetrics.DRAW_DURATION),
                    ms(FrameMetrics.SYNC_DURATION),ms(FrameMetrics.COMMAND_ISSUE_DURATION),ms(FrameMetrics.SWAP_BUFFERS_DURATION),gpu))}}}
        listener=l;a.window.addOnFrameMetricsAvailableListener(l,Handler(th.looper))
        a.root.postDelayed({report(a)},4500)}
    private fun stop(a:MainActivity){listener?.let{try{a.window.removeOnFrameMetricsAvailableListener(it)}catch(_:Exception){}};listener=null;thread?.quitSafely();thread=null;active=false}
    private fun report(a:MainActivity){if(!active)return;stop(a)
        val sb=StringBuilder()
        sb.append("Кадров: $nFrames, медленных: $nSlow\n\nЭтапы:\n");synchronized(marks){marks.forEach{sb.append(it).append('\n')}}
        sb.append("\nОтрисовка элементов (раз · всего · макс):\n")
        synchronized(views){views.entries.sortedByDescending{it.value[1]}.forEach{(k,v)->sb.append("$k: ${v[0]} · %.0f мс · %.1f мс\n".format(v[1]/1e6,v[2]/1e6))}}
        sb.append("\nМедленные кадры:\n");synchronized(frames){frames.take(60).forEach{sb.append(it).append('\n')}}
        val text=sb.toString()
        if(a.isFinishing||a.isDestroyed)return
        android.app.AlertDialog.Builder(a).setTitle("Замер открытия статистики").setMessage(text)
            .setPositiveButton("Копировать"){_,_->(a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("замер",text))}
            .setNegativeButton("Закрыть",null).show()}
}
