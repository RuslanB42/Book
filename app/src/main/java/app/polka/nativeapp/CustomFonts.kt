package app.polka.nativeapp

import android.content.Context
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reading fonts the reader adds from a file (TTF/OTF). Each is copied into the app's own folder under a fresh id, so
 * it keeps working when the original file moves; its name is read from the font itself. Listed in prefs «fonts»
 * as {id,name}; chosen as «custom:<id>».
 */
internal object CustomFonts {
    const val PREFIX="custom:"
    private fun dir(c:Context)=File(c.filesDir,"fonts").apply{mkdirs()}
    fun file(c:Context,id:String)=File(dir(c),"$id.font")
    fun list(a:MainActivity):List<Pair<String,String>> = (a.prefs.optJSONArray("fonts")?:JSONArray()).let{arr->(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}.map{PREFIX+it.str("id") to it.str("name")}}
    fun name(a:MainActivity,key:String)=list(a).firstOrNull{it.first==key}?.second
    /** The typeface of a custom font, or null when its file is gone (e.g. after restoring a backup on another phone). */
    fun typeface(c:Context,key:String):Typeface?{val f=file(c,key.removePrefix(PREFIX));if(!f.exists())return null
        return try{Typeface.Builder(f).build()}catch(_:Exception){null}}

    /** Checks and stores a picked file; returns the new key and the font's name, or throws with a reader-facing reason. */
    fun add(a:MainActivity,bytes:ByteArray,fileName:String):Pair<String,String>{
        val tag=if(bytes.size>=4)String(bytes,0,4,Charsets.ISO_8859_1) else ""
        require(tag=="\u0000\u0001\u0000\u0000"||tag=="OTTO"||tag=="true") { if(tag=="wOFF"||tag=="wOF2") "Веб-шрифты WOFF не поддерживаются — нужен TTF или OTF" else "Это не файл шрифта — нужен TTF или OTF" }
        val id=java.util.UUID.randomUUID().toString().take(12);val f=file(a,id);f.writeBytes(bytes)
        val tf=try{Typeface.Builder(f).build()}catch(_:Exception){null}
        if(tf==null){f.delete();throw IllegalArgumentException("Шрифт повреждён — его не удалось открыть")}
        val name=fontName(bytes)?:fileName.substringBeforeLast('.').replace(Regex("[-_]+")," ").trim().ifBlank{"Мой шрифт"}
        return PREFIX+id to name
    }
    /** Lists a stored font (on the main thread: prefs are not touched from the worker). */
    fun register(a:MainActivity,key:String,name:String){val arr=a.prefs.optJSONArray("fonts")?:JSONArray().also{a.prefs.put("fonts",it)}
        arr.put(JSONObject().put("id",key.removePrefix(PREFIX)).put("name",name))}
    fun remove(a:MainActivity,key:String){val id=key.removePrefix(PREFIX);file(a,id).delete();PolkaFonts.forget(key)
        val arr=a.prefs.optJSONArray("fonts")?:return;a.prefs.put("fonts",JSONArray((0 until arr.length()).mapNotNull{arr.optJSONObject(it)}.filter{it.str("id")!=id}))
        if(a.prefs.str("font")==key)a.prefs.put("font","literata")}

    /** The full name (or the family) from the font's own «name» table. */
    private fun fontName(b:ByteArray):String?=try{
        fun u16(o:Int)=((b[o].toInt() and 255) shl 8) or (b[o+1].toInt() and 255)
        fun u32(o:Int)=(u16(o).toLong() shl 16) or u16(o+2).toLong()
        val tables=u16(4);var name=-1
        for(i in 0 until tables){val r=12+i*16;if(String(b,r,4,Charsets.ISO_8859_1)=="name"){name=u32(r+8).toInt();break}}
        if(name<0) null else {
            val count=u16(name+2);val strings=name+u16(name+4);val found=HashMap<Int,String>()
            for(i in 0 until count){val r=name+6+i*12;val platform=u16(r);val lang=u16(r+4);val id=u16(r+6);val len=u16(r+8);val off=strings+u16(r+10)
                if(id!=1&&id!=4)continue
                val s=when(platform){3,0->String(b,off,len,Charsets.UTF_16BE);1->String(b,off,len,Charsets.ISO_8859_1);else->continue}.trim()
                // English (Windows 0x409 / Mac 0) first, then whatever comes
                val rank=id*10+(if(platform==3&&lang==0x409||platform==1&&lang==0)1 else 0)
                if(s.isNotBlank()&&(found[id]==null||rank%10==1))found[id]=s}
            (found[4]?:found[1])?.takeIf{it.length<=48}
        }}catch(_:Exception){null}
}
