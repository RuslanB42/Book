package app.polka.nativeapp

import android.graphics.*
import androidx.core.graphics.PathParser

/** iOS-style icon set: 24 grid, 1.7 line, round caps and joins. Filled variants mark the selected tab. */
internal object IosIcons {
  private class Op(val fill:Boolean,val cut:Boolean,val w:Float,val round:Boolean,val d:String,val m:FloatArray?){
    val path:Path by lazy{PathParser.createPathFromPathData(d).also{p->m?.let{val mx=Matrix();mx.setValues(floatArrayOf(it[0],it[2],it[4],it[1],it[3],it[5],0f,0f,1f));p.transform(mx)}}}
  }
  private val ALIAS=mapOf("contents" to "toc","list" to "toc","bm" to "bookmark","information" to "info","shelf" to "shelves","cards" to "foryou")
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
    "back" to listOf(
      Op(false,false,1.7f,true,"M15 4.5 7.5 12l7.5 7.5",null)),
    "next" to listOf(
      Op(false,false,1.7f,true,"M9 4.5l7.5 7.5L9 19.5",null)),
    "chevdown" to listOf(
      Op(false,false,1.7f,true,"M5 9l7 7 7-7",null)),
    "x" to listOf(
      Op(false,false,1.7f,true,"M6 6l12 12M18 6 6 18",null)),
    "plus" to listOf(
      Op(false,false,1.7f,true,"M12 4.5v15M4.5 12h15",null)),
    "check" to listOf(
      Op(false,false,1.7f,true,"M4.5 12.5 9.5 17.5 19.5 6.5",null)),
    "more" to listOf(
      Op(true,false,0f,true,"M3.9 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z",null),
      Op(true,false,0f,true,"M10.4 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z",null),
      Op(true,false,0f,true,"M16.9 12a1.6 1.6 0 1 0 3.2 0a1.6 1.6 0 1 0 -3.2 0Z",null)),
    "home" to listOf(
      Op(false,false,1.7f,true,"M3 10.8 12 3.5l9 7.3",null),
      Op(false,false,1.7f,true,"M5.5 9v10a1.5 1.5 0 0 0 1.5 1.5h3V15a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v5.5h3a1.5 1.5 0 0 0 1.5-1.5V9",null)),
    "shelves" to listOf(
      Op(false,false,1.7f,true,"M20.65 13.45H6.3A2.9499999999999997 2.9499999999999997 0 0 0 6.3 19.349999999999998H20.65",null),
      Op(false,false,1f,true,"M6.3 15.7H20.099999999999998M6.3 17.099999999999998H20.099999999999998M20.099999999999998 14.299999999999999V18.5",null),
      Op(false,false,1.7f,true,"M18.65 4.6499999999999995H8.3A2.9499999999999997 2.9499999999999997 0 0 0 8.3 10.549999999999999H18.65",null),
      Op(false,false,1f,true,"M8.3 6.8999999999999995H18.099999999999998M8.3 8.299999999999999H18.099999999999998M18.099999999999998 5.5V9.7",null)),
    "quotes" to listOf(
      Op(true,false,0f,true,"M7.42 18.38Q5.7 18.38 4.6 17.21Q3.5 16.04 3.5 14.1Q3.5 12.58 4.2 11.1Q4.9 9.63 6.32 8.24Q7.75 6.85 9.89 5.62L11.62 8.04Q10.06 8.69 8.84 9.72Q7.62 10.75 7.06 11.77Q8.12 11.71 9.01 12.08Q9.89 12.46 10.41 13.19Q10.94 13.92 10.94 14.93Q10.94 16.48 9.96 17.43Q8.98 18.38 7.42 18.38ZM16.31 18.38Q14.58 18.38 13.48 17.21Q12.38 16.04 12.38 14.1Q12.38 12.58 13.08 11.1Q13.78 9.63 15.21 8.24Q16.63 6.85 18.77 5.62L20.5 8.04Q18.94 8.69 17.72 9.72Q16.5 10.75 15.95 11.77Q17.01 11.71 17.89 12.08Q18.77 12.46 19.3 13.19Q19.82 13.92 19.82 14.93Q19.82 16.48 18.84 17.43Q17.86 18.38 16.31 18.38Z",floatArrayOf(1.08f,0f,0f,1.08f,-0.96f,-0.66f))),
    "foryou" to listOf(
      Op(false,false,1.7f,true,"M10 3.5c.7 4.3 2.7 6.3 7 7-4.3.7-6.3 2.7-7 7-.7-4.3-2.7-6.3-7-7 4.3-.7 6.3-2.7 7-7Z",null),
      Op(true,false,0f,true,"M18.5 13.8c.3 1.8 1.1 2.6 2.9 2.9-1.8.3-2.6 1.1-2.9 2.9-.3-1.8-1.1-2.6-2.9-2.9 1.8-.3 2.6-1.1 2.9-2.9Z",null),
      Op(false,false,1.7f,true,"M18.5 13.8c.3 1.8 1.1 2.6 2.9 2.9-1.8.3-2.6 1.1-2.9 2.9-.3-1.8-1.1-2.6-2.9-2.9 1.8-.3 2.6-1.1 2.9-2.9Z",null),
      Op(true,false,0f,true,"M18 2.8c.2 1.1.7 1.6 1.8 1.8-1.1.2-1.6.7-1.8 1.8-.2-1.1-.7-1.6-1.8-1.8 1.1-.2 1.6-.7 1.8-1.8Z",null),
      Op(false,false,1.7f,true,"M18 2.8c.2 1.1.7 1.6 1.8 1.8-1.1.2-1.6.7-1.8 1.8-.2-1.1-.7-1.6-1.8-1.8 1.1-.2 1.6-.7 1.8-1.8Z",null)),
    "search" to listOf(
      Op(false,false,1.7f,true,"M3.7 10.5a6.8 6.8 0 1 0 13.6 0a6.8 6.8 0 1 0 -13.6 0Z",null),
      Op(false,false,1.7f,true,"M15.4 15.4 20.5 20.5",null)),
    "settings" to listOf(
      Op(false,false,1.7f,true,"M19.06,9.77L21.09,10.56A9.2 9.2 0 0 1 21.09,13.44L19.06,14.23A7.4 7.4 0 0 1 18.56,15.42L18.56,15.42L19.44,17.41A9.2 9.2 0 0 1 17.41,19.44L15.42,18.56A7.4 7.4 0 0 1 14.23,19.06L14.23,19.06L13.44,21.09A9.2 9.2 0 0 1 10.56,21.09L9.77,19.06A7.4 7.4 0 0 1 8.58,18.56L8.58,18.56L6.59,19.44A9.2 9.2 0 0 1 4.56,17.41L5.44,15.42A7.4 7.4 0 0 1 4.94,14.23L4.94,14.23L2.91,13.44A9.2 9.2 0 0 1 2.91,10.56L4.94,9.77A7.4 7.4 0 0 1 5.44,8.58L5.44,8.58L4.56,6.59A9.2 9.2 0 0 1 6.59,4.56L8.58,5.44A7.4 7.4 0 0 1 9.77,4.94L9.77,4.94L10.56,2.91A9.2 9.2 0 0 1 13.44,2.91L14.23,4.94A7.4 7.4 0 0 1 15.42,5.44L15.42,5.44L17.41,4.56A9.2 9.2 0 0 1 19.44,6.59L18.56,8.58A7.4 7.4 0 0 1 19.06,9.77Z",null),
      Op(false,false,1.7f,true,"M8.9 12a3.1 3.1 0 1 0 6.2 0a3.1 3.1 0 1 0 -6.2 0Z",null)),
    "user" to listOf(
      Op(false,false,1.7f,true,"M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z",null),
      Op(false,false,1.7f,true,"M8.8 9.8a3.2 3.2 0 1 0 6.4 0a3.2 3.2 0 1 0 -6.4 0Z",null),
      Op(false,false,1.7f,true,"M6.2 18.2c1.4-2 3.4-3 5.8-3s4.4 1 5.8 3",null)),
    "filter" to listOf(
      Op(false,false,1.7f,true,"M4 7h16M7 12h10M10 17h4",null)),
    "sliders" to listOf(
      Op(false,false,1.7f,true,"M4 7h8.5M17.5 7H20M4 12h2.5M11.5 12H20M4 17h10M19 17h1",null),
      Op(false,false,1.7f,true,"M12.7 7a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0Z",null),
      Op(false,false,1.7f,true,"M6.7 12a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0Z",null),
      Op(false,false,1.7f,true,"M14.2 17a2.3 2.3 0 1 0 4.6 0a2.3 2.3 0 1 0 -4.6 0Z",null)),
    "share" to listOf(
      Op(false,false,1.7f,true,"M12 3.5v11M8.3 7.2 12 3.5l3.7 3.7",null),
      Op(false,false,1.7f,true,"M8.5 10H7a2 2 0 0 0-2 2v6.5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V12a2 2 0 0 0-2-2h-1.5",null)),
    "download" to listOf(
      Op(false,false,1.7f,true,"M12 3.5v12M7.3 11 12 15.7l4.7-4.7M5 20h14",null)),
    "expand" to listOf(
      Op(false,false,1.7f,true,"M14 4h6v6M20 4l-6.5 6.5M10 20H4v-6M4 20l6.5-6.5",null)),
    "trash" to listOf(
      Op(false,false,1.7f,true,"M3.8 6.3h16.4M9.3 6.3V4.8a1.3 1.3 0 0 1 1.3-1.3h2.8a1.3 1.3 0 0 1 1.3 1.3v1.5",null),
      Op(false,false,1.7f,true,"M5.8 6.3l.9 12.4a2 2 0 0 0 2 1.8h6.6a2 2 0 0 0 2-1.8l.9-12.4M10 10.5v6M14 10.5v6",null)),
    "copy" to listOf(
      Op(false,false,1.7f,true,"M10.8 8.5H17.7A2.3 2.3 0 0 1 20 10.8V18.2A2.3 2.3 0 0 1 17.7 20.5H10.8A2.3 2.3 0 0 1 8.5 18.2V10.8A2.3 2.3 0 0 1 10.8 8.5Z",null),
      Op(false,false,1.7f,true,"M15.5 8.5V6A2.3 2.3 0 0 0 13.2 3.7H6.3A2.3 2.3 0 0 0 4 6v8.4a2.3 2.3 0 0 0 2.3 2.3h2.2",null)),
    "pen" to listOf(
      Op(false,false,1.7f,true,"M15.9 4.6a2 2 0 0 1 2.8 0l.7.7a2 2 0 0 1 0 2.8L8.8 18.7 4 20l1.3-4.8Z",null),
      Op(false,false,1.7f,true,"M14.2 6.3l3.5 3.5",null)),
    "note" to listOf(
      Op(false,false,1.7f,true,"M19.5 13v5.2a2.3 2.3 0 0 1-2.3 2.3H6.3A2.3 2.3 0 0 1 4 18.2V7.3A2.3 2.3 0 0 1 6.3 5h5.2",null),
      Op(false,false,1.7f,true,"M17.6 3.9a1.7 1.7 0 0 1 2.5 2.5l-7.6 7.6-3.3.9.9-3.3Z",null)),
    "quote" to listOf(
      Op(true,false,0f,true,"M7.42 18.38Q5.7 18.38 4.6 17.21Q3.5 16.04 3.5 14.1Q3.5 12.58 4.2 11.1Q4.9 9.63 6.32 8.24Q7.75 6.85 9.89 5.62L11.62 8.04Q10.06 8.69 8.84 9.72Q7.62 10.75 7.06 11.77Q8.12 11.71 9.01 12.08Q9.89 12.46 10.41 13.19Q10.94 13.92 10.94 14.93Q10.94 16.48 9.96 17.43Q8.98 18.38 7.42 18.38ZM16.31 18.38Q14.58 18.38 13.48 17.21Q12.38 16.04 12.38 14.1Q12.38 12.58 13.08 11.1Q13.78 9.63 15.21 8.24Q16.63 6.85 18.77 5.62L20.5 8.04Q18.94 8.69 17.72 9.72Q16.5 10.75 15.95 11.77Q17.01 11.71 17.89 12.08Q18.77 12.46 19.3 13.19Q19.82 13.92 19.82 14.93Q19.82 16.48 18.84 17.43Q17.86 18.38 16.31 18.38Z",floatArrayOf(1.08f,0f,0f,1.08f,-0.96f,-0.66f))),
    "translate" to listOf(
      Op(false,false,1.7f,true,"M10 14.5H5.6A2.3 2.3 0 0 1 3.3 12.2V5.6A2.3 2.3 0 0 1 5.6 3.3h6.6a2.3 2.3 0 0 1 2.3 2.3V10",null),
      Op(false,false,1.7f,true,"M5.7 10.8 8.2 5.3l2.5 5.5M6.6 9h3.2",null),
      Op(false,false,1.7f,true,"M12.3 10H18.4A2.3 2.3 0 0 1 20.7 12.3V18.4A2.3 2.3 0 0 1 18.4 20.7H12.3A2.3 2.3 0 0 1 10 18.4V12.3A2.3 2.3 0 0 1 12.3 10Z",null),
      Op(false,false,1.7f,true,"M17.6 18.2v-5.6h-1.9a1.6 1.6 0 0 0 0 3.2h1.9M15.8 15.8l-1.9 2.4",null)),
    "dictionary" to listOf(
      Op(false,false,1.7f,true,"M19 17V4.6a1.1 1.1 0 0 0-1.1-1.1H7.5A2.5 2.5 0 0 0 5 6v12.5",null),
      Op(false,false,1.7f,true,"M19 17H7.3a2.3 2.3 0 0 0 0 4.6H19",null),
      Op(false,false,1.7f,true,"M9.3 13.3 12 6.6l2.7 6.7M10.3 11h3.4",null)),
    "speaker" to listOf(
      Op(false,false,1.7f,true,"M4.5 9.2h2.8l4.4-3.9a.6.6 0 0 1 1 .5v12.4a.6.6 0 0 1-1 .5l-4.4-3.9H4.5a1 1 0 0 1-1-1V10.2a1 1 0 0 1 1-1Z",null),
      Op(false,false,1.7f,true,"M15.8 9a4.3 4.3 0 0 1 0 6M18.6 6.4a8 8 0 0 1 0 11.2",null)),
    "swap" to listOf(
      Op(false,false,1.7f,true,"M4 8h15M15.5 4.5 19 8l-3.5 3.5M20 16H5M8.5 12.5 5 16l3.5 3.5",null)),
    "bookmark" to listOf(
      Op(false,false,1.7f,true,"M7.3 3.5h9.4a1.3 1.3 0 0 1 1.3 1.3v15.4a.5.5 0 0 1-.8.4L12 16.8l-5.2 3.8a.5.5 0 0 1-.8-.4V4.8a1.3 1.3 0 0 1 1.3-1.3Z",null)),
    "toc" to listOf(
      Op(true,false,0f,true,"M3.6 6.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z",null),
      Op(true,false,0f,true,"M3.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z",null),
      Op(true,false,0f,true,"M3.6 17.5a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z",null),
      Op(false,false,1.7f,true,"M9.5 6.5h10.5M9.5 12h10.5M9.5 17.5h10.5",null)),
    "aa" to listOf(
      Op(false,false,1.7f,true,"M2.8 18.5 7.6 5.5l4.8 13M4.6 14h6",null),
      Op(false,false,1.7f,true,"M14 18.5l3.2-8.5 3.2 8.5M15.2 15.6h4",null)),
    "book" to listOf(
      Op(false,false,1.7f,true,"M12 6.3C10.2 4.9 7.7 4.3 3.5 4.3v14c4.2 0 6.7.6 8.5 2 1.8-1.4 4.3-2 8.5-2v-14c-4.2 0-6.7.6-8.5 2Z",null),
      Op(false,false,1.7f,true,"M12 6.3v14",null)),
    "pages" to listOf(
      Op(false,false,1.7f,true,"M7 3.5H15A2 2 0 0 1 17 5.5V17A2 2 0 0 1 15 19H7A2 2 0 0 1 5 17V5.5A2 2 0 0 1 7 3.5Z",null),
      Op(false,false,1.7f,true,"M8.5 21h9a2.5 2.5 0 0 0 2.5-2.5V7.5",null)),
    "scroll" to listOf(
      Op(false,false,1.7f,true,"M12 3.5v17M8.3 7.2 12 3.5l3.7 3.7M8.3 16.8l3.7 3.7 3.7-3.7",null)),
    "undo" to listOf(
      Op(false,false,1.7f,true,"M8.5 4.5 4 9l4.5 4.5",null),
      Op(false,false,1.7f,true,"M4 9h10.5a5 5 0 0 1 0 10H11",null)),
    "redo" to listOf(
      Op(false,false,1.7f,true,"M15.5 4.5 20 9l-4.5 4.5",null),
      Op(false,false,1.7f,true,"M20 9H9.5a5 5 0 0 0 0 10H13",null)),
    "info" to listOf(
      Op(false,false,1.7f,true,"M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z",null),
      Op(false,false,1.7f,true,"M12 11v5.6",null),
      Op(true,false,0f,true,"M10.75 7.8a1.25 1.25 0 1 0 2.5 0a1.25 1.25 0 1 0 -2.5 0Z",null)),
    "target" to listOf(
      Op(false,false,1.7f,true,"M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0Z",null),
      Op(false,false,1.7f,true,"M7 12a5 5 0 1 0 10 0a5 5 0 1 0 -10 0Z",null),
      Op(true,false,0f,true,"M10.6 12a1.4 1.4 0 1 0 2.8 0a1.4 1.4 0 1 0 -2.8 0Z",null)),
    "star" to listOf(
      Op(false,false,1.7f,true,"M12 3.6l2.5 5.2 5.7.8-4.1 4 1 5.6L12 16.5l-5.1 2.7 1-5.6-4.1-4 5.7-.8Z",null)),
    "flame" to listOf(
      Op(false,false,1.7f,true,"M12 21c-3.9 0-6.8-2.6-6.8-6.4 0-3.4 2.4-5.3 3.7-8.2.3 1.6 1 2.8 2 3.4.2-2.9 1.8-5.3 4-6.8-.2 2.7 1 4.4 2.3 6 1 1.3 1.6 3 1.6 5.4C18.8 18.3 15.9 21 12 21Z",null),
      Op(false,false,1.7f,true,"M12 21c-1.7 0-2.9-1.2-2.9-2.9 0-1.6 1.2-2.5 2.2-3.8.4 1 1.2 1.6 1.9 1.9.2-.7.3-1.4.2-2.1 1 .8 1.6 2.2 1.6 3.6C15 19.8 13.7 21 12 21Z",null)),
    "sound" to listOf(
      Op(false,false,1.7f,true,"M4 10v4M8 7v10M12 4v16M16 8v8M20 10.5v3",null)),
    "move" to listOf(
      Op(false,false,1.7f,true,"M20.65 13.45H6.3A2.9499999999999997 2.9499999999999997 0 0 0 6.3 19.349999999999998H20.65",null),
      Op(false,false,1f,true,"M6.3 15.7H20.099999999999998M6.3 17.099999999999998H20.099999999999998M20.099999999999998 14.299999999999999V18.5",null),
      Op(false,false,1.7f,true,"M18.65 4.6499999999999995H8.3A2.9499999999999997 2.9499999999999997 0 0 0 8.3 10.549999999999999H18.65",null),
      Op(false,false,1f,true,"M8.3 6.8999999999999995H18.099999999999998M8.3 8.299999999999999H18.099999999999998M18.099999999999998 5.5V9.7",null)),
    "paperclip" to listOf(
      Op(false,false,1.7f,true,"M19.6 11.3l-7.7 7.7a5 5 0 0 1-7.1-7.1l8.3-8.3a3.3 3.3 0 0 1 4.7 4.7l-8.3 8.3a1.6 1.6 0 0 1-2.3-2.3l7.6-7.6",null)),
    "notebook" to listOf(
      Op(false,false,1.7f,true,"M7.8 3.5H17.2A2.3 2.3 0 0 1 19.5 5.8V18.2A2.3 2.3 0 0 1 17.2 20.5H7.8A2.3 2.3 0 0 1 5.5 18.2V5.8A2.3 2.3 0 0 1 7.8 3.5Z",null),
      Op(false,false,1.7f,true,"M9.5 3.5v17M3.5 7.5h4M3.5 12h4M3.5 16.5h4",null)),
    "sunmin" to listOf(
      Op(false,false,1.7f,true,"M9.1 12a2.9 2.9 0 1 0 5.8 0a2.9 2.9 0 1 0 -5.8 0Z",null),
      Op(false,false,1.7f,true,"M17.60 12.00L19.00 12.00",null),
      Op(false,false,1.7f,true,"M15.96 15.96L16.95 16.95",null),
      Op(false,false,1.7f,true,"M12.00 17.60L12.00 19.00",null),
      Op(false,false,1.7f,true,"M8.04 15.96L7.05 16.95",null),
      Op(false,false,1.7f,true,"M6.40 12.00L5.00 12.00",null),
      Op(false,false,1.7f,true,"M8.04 8.04L7.05 7.05",null),
      Op(false,false,1.7f,true,"M12.00 6.40L12.00 5.00",null),
      Op(false,false,1.7f,true,"M15.96 8.04L16.95 7.05",null)),
    "sunmax" to listOf(
      Op(false,false,1.7f,true,"M8.4 12a3.6 3.6 0 1 0 7.2 0a3.6 3.6 0 1 0 -7.2 0Z",null),
      Op(false,false,1.7f,true,"M18.20 12.00L21.00 12.00",null),
      Op(false,false,1.7f,true,"M16.38 16.38L18.36 18.36",null),
      Op(false,false,1.7f,true,"M12.00 18.20L12.00 21.00",null),
      Op(false,false,1.7f,true,"M7.62 16.38L5.64 18.36",null),
      Op(false,false,1.7f,true,"M5.80 12.00L3.00 12.00",null),
      Op(false,false,1.7f,true,"M7.62 7.62L5.64 5.64",null),
      Op(false,false,1.7f,true,"M12.00 5.80L12.00 3.00",null),
      Op(false,false,1.7f,true,"M16.38 7.62L18.36 5.64",null)),
    "moon" to listOf(
      Op(false,false,1.7f,true,"M19.8 14.6A8.2 8.2 0 0 1 9.4 4.2a8.3 8.3 0 1 0 10.4 10.4Z",null)),
    "words" to listOf(
      Op(false,false,1.7f,true,"M10.1 3.5H17.9A2.6 2.6 0 0 1 20.5 6.1V15.9A2.6 2.6 0 0 1 17.9 18.5H10.1A2.6 2.6 0 0 1 7.5 15.9V6.1A2.6 2.6 0 0 1 10.1 3.5Z",null),
      Op(false,false,1.7f,true,"M4.5 7.2v10.5a2.8 2.8 0 0 0 2.8 2.8h8.5",null),
      Op(false,false,1.7f,true,"M11 14.5l3-7.5 3 7.5M12 12.3h4",null)),
    "learn" to listOf(
      Op(false,false,1.7f,true,"M12 5 2.5 9.5 12 14l9.5-4.5Z",null),
      Op(false,false,1.7f,true,"M6.5 11.8v4c0 1.4 2.5 2.9 5.5 2.9s5.5-1.5 5.5-2.9v-4M21.5 9.5V15",null)))}
  private val FILLED:Map<String,List<Op>> by lazy{mapOf(
    "home" to listOf(
      Op(true,false,0f,true,"M12.8 3.1a1.3 1.3 0 0 0-1.6 0L3.4 9.4c-.6.5-.2 1.4.5 1.4H5.5V19a1.5 1.5 0 0 0 1.5 1.5h3V15a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v5.5h3a1.5 1.5 0 0 0 1.5-1.5v-8.2h1.6c.7 0 1.1-.9.5-1.4Z",null)),
    "shelves" to listOf(
      Op(true,false,0f,true,"M20.9 12.6H6.3A3.8 3.8 0 0 0 6.3 20.2H20.9A.6 .6 0 0 0 21.5 19.599999999999998V13.2A.6 .6 0 0 0 20.9 12.6Z",null),
      Op(true,true,0f,true,"M22.5 14.1H6.3A2.3 2.3 0 0 0 6.3 18.7H22.5Z",null),
      Op(false,false,1.05f,true,"M6.3 15.633333333333333H20.4M6.3 17.166666666666664H20.4",null),
      Op(false,false,1.05f,true,"M20.4 14.1V18.7",null),
      Op(true,false,0f,true,"M18.9 3.8H8.3A3.8 3.8 0 0 0 8.3 11.399999999999999H18.9A.6 .6 0 0 0 19.5 10.799999999999999V4.3999999999999995A.6 .6 0 0 0 18.9 3.8Z",null),
      Op(true,true,0f,true,"M20.5 5.3H8.3A2.3 2.3 0 0 0 8.3 9.899999999999999H20.5Z",null),
      Op(false,false,1.05f,true,"M8.3 6.833333333333333H18.4M8.3 8.366666666666667H18.4",null),
      Op(false,false,1.05f,true,"M18.4 5.3V9.899999999999999",null)),
    "quotes" to listOf(
      Op(true,false,0f,true,"M7.42 18.38Q5.7 18.38 4.6 17.21Q3.5 16.04 3.5 14.1Q3.5 12.58 4.2 11.1Q4.9 9.63 6.32 8.24Q7.75 6.85 9.89 5.62L11.62 8.04Q10.06 8.69 8.84 9.72Q7.62 10.75 7.06 11.77Q8.12 11.71 9.01 12.08Q9.89 12.46 10.41 13.19Q10.94 13.92 10.94 14.93Q10.94 16.48 9.96 17.43Q8.98 18.38 7.42 18.38ZM16.31 18.38Q14.58 18.38 13.48 17.21Q12.38 16.04 12.38 14.1Q12.38 12.58 13.08 11.1Q13.78 9.63 15.21 8.24Q16.63 6.85 18.77 5.62L20.5 8.04Q18.94 8.69 17.72 9.72Q16.5 10.75 15.95 11.77Q17.01 11.71 17.89 12.08Q18.77 12.46 19.3 13.19Q19.82 13.92 19.82 14.93Q19.82 16.48 18.84 17.43Q17.86 18.38 16.31 18.38Z",floatArrayOf(1.08f,0f,0f,1.08f,-0.96f,-0.66f))),
    "foryou" to listOf(
      Op(true,false,0f,true,"M10 3.5c.7 4.3 2.7 6.3 7 7-4.3.7-6.3 2.7-7 7-.7-4.3-2.7-6.3-7-7 4.3-.7 6.3-2.7 7-7Z",null),
      Op(false,false,1.7f,true,"M10 3.5c.7 4.3 2.7 6.3 7 7-4.3.7-6.3 2.7-7 7-.7-4.3-2.7-6.3-7-7 4.3-.7 6.3-2.7 7-7Z",null),
      Op(true,false,0f,true,"M18.5 13.8c.3 1.8 1.1 2.6 2.9 2.9-1.8.3-2.6 1.1-2.9 2.9-.3-1.8-1.1-2.6-2.9-2.9 1.8-.3 2.6-1.1 2.9-2.9Z",null),
      Op(false,false,1.7f,true,"M18.5 13.8c.3 1.8 1.1 2.6 2.9 2.9-1.8.3-2.6 1.1-2.9 2.9-.3-1.8-1.1-2.6-2.9-2.9 1.8-.3 2.6-1.1 2.9-2.9Z",null),
      Op(true,false,0f,true,"M18 2.8c.2 1.1.7 1.6 1.8 1.8-1.1.2-1.6.7-1.8 1.8-.2-1.1-.7-1.6-1.8-1.8 1.1-.2 1.6-.7 1.8-1.8Z",null),
      Op(false,false,1.7f,true,"M18 2.8c.2 1.1.7 1.6 1.8 1.8-1.1.2-1.6.7-1.8 1.8-.2-1.1-.7-1.6-1.8-1.8 1.1-.2 1.6-.7 1.8-1.8Z",null)),
    "bookmark" to listOf(
      Op(true,false,0f,true,"M7.3 3.5h9.4a1.3 1.3 0 0 1 1.3 1.3v15.4a.5.5 0 0 1-.8.4L12 16.8l-5.2 3.8a.5.5 0 0 1-.8-.4V4.8a1.3 1.3 0 0 1 1.3-1.3Z",null),
      Op(false,false,1.7f,true,"M7.3 3.5h9.4a1.3 1.3 0 0 1 1.3 1.3v15.4a.5.5 0 0 1-.8.4L12 16.8l-5.2 3.8a.5.5 0 0 1-.8-.4V4.8a1.3 1.3 0 0 1 1.3-1.3Z",null)),
    "words" to listOf(
      Op(true,false,0f,true,"M10.1 3.5H17.9A2.6 2.6 0 0 1 20.5 6.1V15.9A2.6 2.6 0 0 1 17.9 18.5H10.1A2.6 2.6 0 0 1 7.5 15.9V6.1A2.6 2.6 0 0 1 10.1 3.5Z",null),
      Op(false,false,1.7f,true,"M10.1 3.5H17.9A2.6 2.6 0 0 1 20.5 6.1V15.9A2.6 2.6 0 0 1 17.9 18.5H10.1A2.6 2.6 0 0 1 7.5 15.9V6.1A2.6 2.6 0 0 1 10.1 3.5Z",null),
      Op(false,false,1.7f,true,"M4.5 7.2v10.5a2.8 2.8 0 0 0 2.8 2.8h8.5",null),
      Op(false,true,1.7f,true,"M11 14.5l3-7.5 3 7.5M12 12.3h4",null)))}
}
