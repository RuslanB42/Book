package app.polka.nativeapp
import android.content.Context
import android.graphics.*
import org.json.*
object OriginalIcons {
    var source:JSONObject?=null
    fun draw(context:Context,c:Canvas,kind:String,ink:Int):Boolean {
        if(source==null) source=JSONObject(context.assets.open("icons.json").bufferedReader().readText())
        val name=when(kind) { "home"->"home"; "shelves"->"shelf"; "quotes"->"quote"; "foryou"->"cards"; "bookmark"->"bm"; "toc"->"list"; "contents"->"toc"; else->kind }
        val data=source?.optJSONArray(name) ?: return false
        for(i in 0 until data.length()) { val el=data.getJSONObject(i); val p=Paint(3).apply { color=if(el.str("stroke")=="var(--bg)") (context as? MainActivity)?.bg ?: ink else ink; style=if(el.str("fill")=="currentColor") Paint.Style.FILL else Paint.Style.STROKE; strokeWidth=el.optDouble("stroke-width",1.6).toFloat(); strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; alpha=(255*el.optDouble("opacity",1.0)).toInt() }
            fun f(key:String)=el.optDouble(key,0.0).toFloat()
            when(el.str("tag")) { "path"->androidx.core.graphics.PathParser.createPathFromPathData(el.str("d"))?.let { c.drawPath(it,p) }; "circle"->c.drawCircle(f("cx"),f("cy"),f("r"),p); "rect"->c.drawRoundRect(f("x"),f("y"),f("x")+f("width"),f("y")+f("height"),f("rx"),f("rx"),p); "line"->c.drawLine(f("x1"),f("y1"),f("x2"),f("y2"),p); "polyline","polygon"->{ val numbers=el.str("points").split(Regex("[ ,]+")).mapNotNull { it.toFloatOrNull() }; val path=Path(); numbers.chunked(2).forEachIndexed { j,pair -> if(pair.size==2) { if(j==0) path.moveTo(pair[0],pair[1]) else path.lineTo(pair[0],pair[1]) } }; if(el.str("tag")=="polygon") path.close(); c.drawPath(path,p) } }
        }; return true
    }
}
