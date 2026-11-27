package app.polka.nativeapp

import android.graphics.*
import androidx.core.graphics.PathParser

/**
 * The app's icons: Lucide (https://lucide.dev, ISC licence; the text is in assets/icons/lucide-LICENSE.txt), on a
 * 24 grid with a 1.75 line, round caps and joins. The selected tab draws the same icon with a heavier line.
 */
internal object IosIcons {
  private class Op(val fill:Boolean,val cut:Boolean,val w:Float,val round:Boolean,val d:String,val m:FloatArray?){
    val path:Path by lazy{PathParser.createPathFromPathData(d).also{p->m?.let{val mx=Matrix();mx.setValues(floatArrayOf(it[0],it[2],it[4],it[1],it[3],it[5],0f,0f,1f));p.transform(mx)}}}
  }
  private val ALIAS=mapOf("contents" to "toc","list" to "toc","bm" to "bookmark","information" to "info","shelf" to "shelves","cards" to "foryou","down" to "chevdown")
  private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{strokeJoin=Paint.Join.ROUND}
  private fun key(kind:String)=ALIAS[kind]?:kind
  fun has(kind:String)=OUTLINE.containsKey(key(kind))
  fun hasFilled(kind:String)=FILLED.containsKey(key(kind))
  /** Draws on a 24-unit canvas. [cut] is the colour behind the icon, used to carve details out of filled shapes. */
  fun draw(c:Canvas,kind:String,ink:Int,filled:Boolean=false,cut:Int=0):Boolean{
    val k=key(kind);val ops=(if(filled)FILLED[k] else null)?:OUTLINE[k]?:return false
    for(op in ops){paint.color=if(op.cut)cut else ink;paint.style=if(op.fill)Paint.Style.FILL else Paint.Style.STROKE;paint.strokeWidth=op.w;paint.strokeCap=if(op.round)Paint.Cap.ROUND else Paint.Cap.BUTT;c.drawPath(op.path,paint)}
    return true
  }
  private val OUTLINE:Map<String,List<Op>> by lazy{mapOf(
    "back" to listOf(Op(false,false,1.75f,true,"M15 18 l-6 -6 6 -6",null)),
    "next" to listOf(Op(false,false,1.75f,true,"M9 18 l6 -6 -6 -6",null)),
    "chevdown" to listOf(Op(false,false,1.75f,true,"M6 9 l6 6 6 -6",null)),
    "arrowup" to listOf(Op(false,false,1.75f,true,"M12 19 V5 M5 12 l7 -7 7 7",null)),
    "arrowdown" to listOf(Op(false,false,1.75f,true,"M12 5 v14 M19 12 l-7 7 -7 -7",null)),
    "sort" to listOf(Op(false,false,1.75f,true,"M3 16 l4 4 4 -4 M7 20 V4 M21 8 l-4 -4 -4 4 M17 4 v16",null)),
    "x" to listOf(Op(false,false,1.75f,true,"M18 6 6 18 M6 6 l12 12",null)),
    "play" to listOf(Op(false,false,1.75f,true,"M6 5 a1.5 1.5 0 0 1 2.26 -1.3 l11.2 6.99 a1.5 1.5 0 0 1 0 2.62 l-11.2 6.99 A1.5 1.5 0 0 1 6 19 z",null)),
    "plus" to listOf(Op(false,false,1.75f,true,"M5 12 h14 M12 5 v14",null)),
    "check" to listOf(Op(false,false,1.75f,true,"M20 6 9 17 l-5 -5",null)),
    "done" to listOf(Op(false,false,1.75f,true,"M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M16 9 l-5.5 5.5 L8 12",null)),
    "paused" to listOf(Op(false,false,1.75f,true,"M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M10 15 L10 9 M14 15 L14 9",null)),
    "more" to listOf(Op(false,false,1.75f,true,"M11 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 M18 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0 M4 12 a1 1 0 1 0 2 0 a1 1 0 1 0 -2 0",null)),
    "home" to listOf(Op(false,false,1.75f,true,"M15 21 v-8 a1 1 0 0 0 -1 -1 h-4 a1 1 0 0 0 -1 1 v8 M3 10 a2 2 0 0 1 .709 -1.528 l7 -6 a2 2 0 0 1 2.582 0 l7 6 A2 2 0 0 1 21 10 v9 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 z",null)),
    "shelves" to listOf(Op(false,false,1.75f,true,"M4 3 h6 a1 1 0 0 1 1 1 v16 a1 1 0 0 1 -1 1 h-6 a1 1 0 0 1 -1 -1 v-16 a1 1 0 0 1 1 -1ZM7 3 v18 M20.4 18.9 c.2 .5 -.1 1.1 -.6 1.3 l-1.9 .7 c-.5 .2 -1.1 -.1 -1.3 -.6 L11.1 5.1 c-.2 -.5 .1 -1.1 .6 -1.3 l1.9 -.7 c.5 -.2 1.1 .1 1.3 .6Z",null)),
    "quotes" to listOf(Op(false,false,1.75f,true,"M16 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 zM5 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 z",null)),
    "foryou" to listOf(Op(false,false,1.75f,true,"M11.017 2.814 a1 1 0 0 1 1.966 0 l1.051 5.558 a2 2 0 0 0 1.594 1.594 l5.558 1.051 a1 1 0 0 1 0 1.966 l-5.558 1.051 a2 2 0 0 0 -1.594 1.594 l-1.051 5.558 a1 1 0 0 1 -1.966 0 l-1.051 -5.558 a2 2 0 0 0 -1.594 -1.594 l-5.558 -1.051 a1 1 0 0 1 0 -1.966 l5.558 -1.051 a2 2 0 0 0 1.594 -1.594 zM20 2 v4 M22 4 h-4 M2 20 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",null)),
    "search" to listOf(Op(false,false,1.75f,true,"M21 21 l-4.34 -4.34 M3 11 a8 8 0 1 0 16 0 a8 8 0 1 0 -16 0",null)),
    "settings" to listOf(Op(false,false,1.75f,true,"M9.671 4.136 a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1 -2.33 4.033 2.34 2.34 0 0 0 -3.319 1.915 2.34 2.34 0 0 1 -4.659 0 2.34 2.34 0 0 0 -3.32 -1.915 2.34 2.34 0 0 1 -2.33 -4.033 2.34 2.34 0 0 0 0 -3.831 A2.34 2.34 0 0 1 6.35 6.051 a2.34 2.34 0 0 0 3.319 -1.915 M9 12 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",null)),
    "user" to listOf(Op(false,false,1.75f,true,"M19 21 v-2 a4 4 0 0 0 -4 -4 H9 a4 4 0 0 0 -4 4 v2 M8 7 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0",null)),
    "sliders" to listOf(Op(false,false,1.75f,true,"M10 5 H3 M12 19 H3 M14 3 v4 M16 17 v4 M21 12 h-9 M21 19 h-5 M21 5 h-7 M8 10 v4 M8 12 H3",null)),
    "share" to listOf(Op(false,false,1.75f,true,"M12 2 v13 M16 6 l-4 -4 -4 4 M4 12 v8 a2 2 0 0 0 2 2 h12 a2 2 0 0 0 2 -2 v-8",null)),
    "download" to listOf(Op(false,false,1.75f,true,"M12 15 V3 M21 15 v4 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 v-4 M7 10 l5 5 5 -5",null)),
    "fsearch" to listOf(Op(false,false,1.75f,true,"M10.7 20 H4 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 h3.9 a2 2 0 0 1 1.69 .9 l.81 1.2 a2 2 0 0 0 1.67 .9 H20 a2 2 0 0 1 2 2 v4.1 M14 17 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0 M21 21 l-1.9 -1.9",null)),
    "expand" to listOf(Op(false,false,1.75f,true,"M15 3 h6 v6 M21 3 l-7 7 M3 21 l7 -7 M9 21 H3 v-6",null)),
    "camera" to listOf(Op(false,false,1.75f,true,"M14.5 4 h-5 L7 7 H4 a2 2 0 0 0 -2 2 v9 a2 2 0 0 0 2 2 h16 a2 2 0 0 0 2 -2 V9 a2 2 0 0 0 -2 -2 h-3 z M9 13 a3 3 0 1 0 6 0 a3 3 0 1 0 -6 0",null)),
    "image" to listOf(Op(false,false,1.75f,true,"M5 3 h14 a2 2 0 0 1 2 2 v14 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V5 a2 2 0 0 1 2 -2 z M7 9 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0 M21 15 l-3.086 -3.086 a2 2 0 0 0 -2.828 0 L6 21",null)),
    "trash" to listOf(Op(false,false,1.75f,true,"M10 11 v6 M14 11 v6 M19 6 v14 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 V6 M3 6 h18 M8 6 V4 a2 2 0 0 1 2 -2 h4 a2 2 0 0 1 2 2 v2",null)),
    "copy" to listOf(Op(false,false,1.75f,true,"M10 8 h10 a2 2 0 0 1 2 2 v10 a2 2 0 0 1 -2 2 h-10 a2 2 0 0 1 -2 -2 v-10 a2 2 0 0 1 2 -2ZM4 16 c-1.1 0 -2 -.9 -2 -2 V4 c0 -1.1 .9 -2 2 -2 h10 c1.1 0 2 .9 2 2",null)),
    "pen" to listOf(Op(false,false,1.75f,true,"M21.174 6.812 a1 1 0 0 0 -3.986 -3.987 L3.842 16.174 a2 2 0 0 0 -.5 .83 l-1.321 4.352 a.5 .5 0 0 0 .623 .622 l4.353 -1.32 a2 2 0 0 0 .83 -.497 zM15 5 l4 4",null)),
    "note" to listOf(Op(false,false,1.75f,true,"M12 3 H5 a2 2 0 0 0 -2 2 v14 a2 2 0 0 0 2 2 h14 a2 2 0 0 0 2 -2 v-7 M18.375 2.625 a1 1 0 0 1 3 3 l-9.013 9.014 a2 2 0 0 1 -.853 .505 l-2.873 .84 a.5 .5 0 0 1 -.62 -.62 l.84 -2.873 a2 2 0 0 1 .506 -.852 z",null)),
    "quote" to listOf(Op(false,false,1.75f,true,"M16 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 zM5 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 z",null)),
    "translate" to listOf(Op(false,false,1.75f,true,"M5 8 l6 6 M4 14 l6 -6 2 -3 M2 5 h12 M7 2 h1 M22 22 l-5 -10 -5 10 M14 18 h6",null)),
    "dictionary" to listOf(Op(false,false,1.75f,true,"M4 19.5 v-15 A2.5 2.5 0 0 1 6.5 2 H19 a1 1 0 0 1 1 1 v18 a1 1 0 0 1 -1 1 H6.5 a1 1 0 0 1 0 -5 H20 M8 13 l4 -7 4 7 M9.1 11 h5.7",null)),
    "speaker" to listOf(Op(false,false,1.75f,true,"M11 4.702 a.705 .705 0 0 0 -1.203 -.498 L6.413 7.587 A1.4 1.4 0 0 1 5.416 8 H3 a1 1 0 0 0 -1 1 v6 a1 1 0 0 0 1 1 h2.416 a1.4 1.4 0 0 1 .997 .413 l3.383 3.384 A.705 .705 0 0 0 11 19.298 zM16 9 a5 5 0 0 1 0 6 M19.364 18.364 a9 9 0 0 0 0 -12.728",null)),
    "swap" to listOf(Op(false,false,1.75f,true,"M8 3 4 7 l4 4 M4 7 h16 M16 21 l4 -4 -4 -4 M20 17 H4",null)),
    "bookmark" to listOf(Op(false,false,1.75f,true,"M17 3 a2 2 0 0 1 2 2 v15 a1 1 0 0 1 -1.496 .868 l-4.512 -2.578 a2 2 0 0 0 -1.984 0 l-4.512 2.578 A1 1 0 0 1 5 20 V5 a2 2 0 0 1 2 -2 z",null)),
    "toc" to listOf(Op(false,false,1.75f,true,"M3 5 h.01 M3 12 h.01 M3 19 h.01 M8 5 h13 M8 12 h13 M8 19 h13",null)),
    "aa" to listOf(Op(false,false,1.75f,true,"M14.2 19 L17.35 9.7 a1 1 0 0 1 1.9 0 L22.4 19 M15.3 16 h6 M1.5 19 L6.25 5.5 a.55 .55 0 0 1 1 0 L12 19 M3.2 14.5 h7.1",null)),
    "book" to listOf(Op(false,false,1.75f,true,"M12 5 v16 M20.001 19 A2 2 0 0 0 22 17 V5 a2 2 0 0 0 -1.999 -2 L16 3.002 A5 5 0 0 0 12 5 a5 5 0 0 0 -4 -2 H4 a2 2 0 0 0 -2 2 v12 a2 2 0 0 0 1.999 2 H8 a5 5 0 0 1 4 2 5 5 0 0 1 4 -2 z",null)),
    "pages" to listOf(Op(false,false,1.75f,true,"M15 2 h-4 a2 2 0 0 0 -2 2 v11 a2 2 0 0 0 2 2 h8 a2 2 0 0 0 2 -2 V8 M16.706 2.706 A2.4 2.4 0 0 0 15 2 v5 a1 1 0 0 0 1 1 h5 a2.4 2.4 0 0 0 -.706 -1.706 zM5 7 a2 2 0 0 0 -2 2 v11 a2 2 0 0 0 2 2 h8 a2 2 0 0 0 1.732 -1",null)),
    "scroll" to listOf(Op(false,false,1.75f,true,"M21 16 l-4 4 -4 -4 M17 20 V4 M3 8 l4 -4 4 4 M7 4 v16",null)),
    "undo" to listOf(Op(false,false,1.75f,true,"M9 14 4 9 l5 -5 M4 9 h10.5 a5.5 5.5 0 0 1 5.5 5.5 a5.5 5.5 0 0 1 -5.5 5.5 H11",null)),
    "redo" to listOf(Op(false,false,1.75f,true,"M15 14 l5 -5 -5 -5 M20 9 H9.5 A5.5 5.5 0 0 0 4 14.5 A5.5 5.5 0 0 0 9.5 20 H13",null)),
    "info" to listOf(Op(false,false,1.75f,true,"M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M12 16 v-4 M12 8 h.01",null)),
    "target" to listOf(Op(false,false,1.75f,true,"M2 12 a10 10 0 1 0 20 0 a10 10 0 1 0 -20 0 M6 12 a6 6 0 1 0 12 0 a6 6 0 1 0 -12 0 M10 12 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",null)),
    "star" to listOf(Op(false,false,1.75f,true,"M11.525 2.295 a.53 .53 0 0 1 .95 0 l2.31 4.679 a2.123 2.123 0 0 0 1.595 1.16 l5.166 .756 a.53 .53 0 0 1 .294 .904 l-3.736 3.638 a2.123 2.123 0 0 0 -.611 1.878 l.882 5.14 a.53 .53 0 0 1 -.771 .56 l-4.618 -2.428 a2.122 2.122 0 0 0 -1.973 0 L6.396 21.01 a.53 .53 0 0 1 -.77 -.56 l.881 -5.139 a2.122 2.122 0 0 0 -.611 -1.879 L2.16 9.795 a.53 .53 0 0 1 .294 -.906 l5.165 -.755 a2.122 2.122 0 0 0 1.597 -1.16 z",null)),
    "flame" to listOf(Op(false,false,1.75f,true,"M12 3 q1 4 4 6.5 t3 5.5 a1 1 0 0 1 -14 0 5 5 0 0 1 1 -3 1 1 0 0 0 5 0 c0 -2 -1.5 -3 -1.5 -5 q0 -2 2.5 -4",null)),
    "sound" to listOf(Op(false,false,1.75f,true,"M2 10 v3 M6 6 v11 M10 3 v18 M14 8 v7 M18 5 v13 M22 10 v3",null)),
    "move" to listOf(Op(false,false,1.75f,true,"M5 7 a2 2 0 0 0 -2 2 v11 M5.803 18 H5 a2 2 0 0 0 0 4 h9.5 a.5 .5 0 0 0 .5 -.5 V21 M9 15 V4 a2 2 0 0 1 2 -2 h9.5 a.5 .5 0 0 1 .5 .5 v14 a.5 .5 0 0 1 -.5 .5 H11 a2 2 0 0 1 0 -4 h10",null)),
    "paperclip" to listOf(Op(false,false,1.75f,true,"M16 6 l-8.414 8.586 a2 2 0 0 0 2.829 2.829 l8.414 -8.586 a4 4 0 1 0 -5.657 -5.657 l-8.379 8.551 a6 6 0 1 0 8.485 8.485 l8.379 -8.551",null)),
    "notebook" to listOf(Op(false,false,1.75f,true,"M2 6 h4 M2 10 h4 M2 14 h4 M2 18 h4 M6 2 h12 a2 2 0 0 1 2 2 v16 a2 2 0 0 1 -2 2 h-12 a2 2 0 0 1 -2 -2 v-16 a2 2 0 0 1 2 -2ZM16 2 v20",null)),
    "sunmin" to listOf(Op(false,false,1.75f,true,"M8 12 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 M12 4 h.01 M20 12 h.01 M12 20 h.01 M4 12 h.01 M17.657 6.343 h.01 M17.657 17.657 h.01 M6.343 17.657 h.01 M6.343 6.343 h.01",null)),
    "sunmax" to listOf(Op(false,false,1.75f,true,"M8 12 a4 4 0 1 0 8 0 a4 4 0 1 0 -8 0 M12 2 v2 M12 20 v2 M4.93 4.93 l1.41 1.41 M17.66 17.66 l1.41 1.41 M2 12 h2 M20 12 h2 M6.34 17.66 l-1.41 1.41 M19.07 4.93 l-1.41 1.41",null)),
    "moon" to listOf(Op(false,false,1.75f,true,"M20.985 12.486 a9 9 0 1 1 -9.473 -9.472 c.405 -.022 .617 .46 .402 .803 a6 6 0 0 0 8.268 8.268 c.344 -.215 .825 -.004 .803 .401",null)),
    "words" to listOf(Op(false,false,1.75f,true,"M4 19.5 v-15 A2.5 2.5 0 0 1 6.5 2 H19 a1 1 0 0 1 1 1 v18 a1 1 0 0 1 -1 1 H6.5 a1 1 0 0 1 0 -5 H20 M8 13 l4 -7 4 7 M9.1 11 h5.7",null)),
    "learn" to listOf(Op(false,false,1.75f,true,"M21.42 10.922 a1 1 0 0 0 -.019 -1.838 L12.83 5.18 a2 2 0 0 0 -1.66 0 L2.6 9.08 a1 1 0 0 0 0 1.832 l8.57 3.908 a2 2 0 0 0 1.66 0 zM22 10 v6 M6 12.5 V16 a6 3 0 0 0 12 0 v-3.5",null)),
    "go" to listOf(Op(false,false,1.75f,true,"M5 12 h14 M12 5 l7 7 -7 7",null)),
    "bolt" to listOf(Op(false,false,1.75f,true,"M15.914 4 a1.5 1.5 0 0 0 -2.474 -1.561 l-9 9 A1.5 1.5 0 0 0 5.5 14 h4.002 a.5 .5 0 0 1 .471 .666 L8.086 20 a1.5 1.5 0 0 0 2.475 1.56 l9 -9 A1.5 1.5 0 0 0 18.5 10 h-3.997 a.5 .5 0 0 1 -.472 -.667 z",null)),
    "hourglass" to listOf(Op(false,false,1.75f,true,"M5 22 h14 M5 2 h14 M17 22 v-4.172 a2 2 0 0 0 -.586 -1.414 L12 12 l-4.414 4.414 A2 2 0 0 0 7 17.828 V22 M7 2 v4.172 a2 2 0 0 0 .586 1.414 L12 12 l4.414 -4.414 A2 2 0 0 0 17 6.172 V2",null)),
    "sheet" to listOf(Op(false,false,1.75f,true,"M6 22 a2 2 0 0 1 -2 -2 V4 a2 2 0 0 1 2 -2 h8 a2.4 2.4 0 0 1 1.704 .706 l3.588 3.588 A2.4 2.4 0 0 1 20 8 v12 a2 2 0 0 1 -2 2 zM14 2 v5 a1 1 0 0 0 1 1 h5 M10 9 H8 M16 13 H8 M16 17 H8",null)),
    "head" to listOf(Op(false,false,1.75f,true,"M4 12 h8 M4 18 V6 M12 18 V6 M17 12 l3 -2 v8",null)))}
  private val FILLED:Map<String,List<Op>> by lazy{mapOf(
    "home" to listOf(Op(false,false,2.3f,true,"M15 21 v-8 a1 1 0 0 0 -1 -1 h-4 a1 1 0 0 0 -1 1 v8 M3 10 a2 2 0 0 1 .709 -1.528 l7 -6 a2 2 0 0 1 2.582 0 l7 6 A2 2 0 0 1 21 10 v9 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 z",null)),
    "shelves" to listOf(Op(false,false,2.3f,true,"M4 3 h6 a1 1 0 0 1 1 1 v16 a1 1 0 0 1 -1 1 h-6 a1 1 0 0 1 -1 -1 v-16 a1 1 0 0 1 1 -1ZM7 3 v18 M20.4 18.9 c.2 .5 -.1 1.1 -.6 1.3 l-1.9 .7 c-.5 .2 -1.1 -.1 -1.3 -.6 L11.1 5.1 c-.2 -.5 .1 -1.1 .6 -1.3 l1.9 -.7 c.5 -.2 1.1 .1 1.3 .6Z",null)),
    "quotes" to listOf(Op(false,false,2.3f,true,"M16 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 zM5 3 a2 2 0 0 0 -2 2 v6 a2 2 0 0 0 2 2 1 1 0 0 1 1 1 v1 a2 2 0 0 1 -2 2 1 1 0 0 0 -1 1 v2 a1 1 0 0 0 1 1 6 6 0 0 0 6 -6 V5 a2 2 0 0 0 -2 -2 z",null)),
    "foryou" to listOf(Op(false,false,2.3f,true,"M11.017 2.814 a1 1 0 0 1 1.966 0 l1.051 5.558 a2 2 0 0 0 1.594 1.594 l5.558 1.051 a1 1 0 0 1 0 1.966 l-5.558 1.051 a2 2 0 0 0 -1.594 1.594 l-1.051 5.558 a1 1 0 0 1 -1.966 0 l-1.051 -5.558 a2 2 0 0 0 -1.594 -1.594 l-5.558 -1.051 a1 1 0 0 1 0 -1.966 l5.558 -1.051 a2 2 0 0 0 1.594 -1.594 zM20 2 v4 M22 4 h-4 M2 20 a2 2 0 1 0 4 0 a2 2 0 1 0 -4 0",null)),
    "bookmark" to listOf(Op(false,false,2.3f,true,"M17 3 a2 2 0 0 1 2 2 v15 a1 1 0 0 1 -1.496 .868 l-4.512 -2.578 a2 2 0 0 0 -1.984 0 l-4.512 2.578 A1 1 0 0 1 5 20 V5 a2 2 0 0 1 2 -2 z",null)),
    "words" to listOf(Op(false,false,2.3f,true,"M4 19.5 v-15 A2.5 2.5 0 0 1 6.5 2 H19 a1 1 0 0 1 1 1 v18 a1 1 0 0 1 -1 1 H6.5 a1 1 0 0 1 0 -5 H20 M8 13 l4 -7 4 7 M9.1 11 h5.7",null)))}
}
