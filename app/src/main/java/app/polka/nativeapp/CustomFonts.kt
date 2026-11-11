package app.polka.nativeapp

import android.content.Context
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reading fonts the reader adds from a file: a TTF/OTF, or a ZIP with fonts inside. The files are copied into the
 * app's own folder, so they keep working when the originals move; names are read from the fonts themselves.
 * Files of one family (regular, bold, italic…) become one font whose bold and italic text use the real faces.
 * Listed in prefs «fonts» as {id,name}; chosen as «custom:<id>». Each font's faces are listed in fonts/<id>.json
 * (fonts added by 1.4.2 have a single fonts/<id>.font instead).
 */
internal object CustomFonts {
    const val PREFIX="custom:"
    private fun dir(c:Context)=File(c.filesDir,"fonts").apply{mkdirs()}
    private fun file(c:Context,id:String)=File(dir(c),"$id.font")
    private fun manifest(c:Context,id:String)=File(dir(c),"$id.json")
    fun list(a:MainActivity):List<Pair<String,String>> = (a.prefs.optJSONArray("fonts")?:JSONArray()).let{arr->(0 until arr.length()).mapNotNull{arr.optJSONObject(it)}.map{PREFIX+it.str("id") to it.str("name")}}
    fun name(a:MainActivity,key:String)=list(a).firstOrNull{it.first==key}?.second

    private class Face(val bytes:ByteArray,val family:String?,val full:String?,val weight:Int,val italic:Boolean)
    private class Stored(val file:File,val weight:Int,val italic:Boolean)

    /** The typeface of a custom font, or null when its files are gone (e.g. after restoring a backup on another phone). */
    fun typeface(c:Context,key:String):Typeface?{val id=key.removePrefix(PREFIX)
        val m=manifest(c,id)
        val faces=if(m.exists())try{JSONArray(m.readText()).let{arr->(0 until arr.length()).map{arr.getJSONObject(it)}.map{Stored(File(dir(c),it.getString("f")),it.optInt("w",400),it.optBoolean("i"))}}.filter{it.file.exists()}}catch(_:Exception){emptyList()}
            else listOfNotNull(file(c,id).takeIf{it.exists()}?.let{Stored(it,400,false)})
        if(faces.isEmpty())return null
        val regular=faces.minByOrNull{(if(it.italic)1000 else 0)+kotlin.math.abs(it.weight-400)}!!
        if(faces.size>1&&android.os.Build.VERSION.SDK_INT>=29)try{
            // one family: the text's bold and italic spans pick the real faces instead of a drawn-over imitation
            var fam:android.graphics.fonts.FontFamily.Builder?=null
            (listOf(regular)+(faces-regular)).forEach{s->try{
                val f=android.graphics.fonts.Font.Builder(s.file).setWeight(s.weight.coerceIn(1,1000)).setSlant(if(s.italic)android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC else android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT).build()
                if(fam==null)fam=android.graphics.fonts.FontFamily.Builder(f) else fam!!.addFont(f)}catch(_:Exception){}}   // two faces of the same style: the second is skipped
            fam?.let{return Typeface.CustomFallbackBuilder(it.build()).setStyle(android.graphics.fonts.FontStyle(regular.weight.coerceIn(1,1000),android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT)).build()}
        }catch(_:Exception){}
        return try{Typeface.Builder(regular.file).build()}catch(_:Exception){null}}

    /** Checks and stores a picked file (a font or a ZIP of fonts); returns the new fonts as key → name, or throws with a
     *  reader-facing reason. Runs on the worker: prefs are left to [register]. */
    fun add(c:Context,bytes:ByteArray,fileName:String):List<Pair<String,String>>{
        val faces=if(bytes.size>=4&&bytes[0]=='P'.code.toByte()&&bytes[1]=='K'.code.toByte()) fromZip(bytes) else listOf(face(bytes)?:throw IllegalArgumentException(reason(bytes)))
        // a family per name; a font without a readable name keeps the file's own name
        val families=faces.groupBy{it.family?:""}.toList()
        return families.map{(family,list)->
            val id=java.util.UUID.randomUUID().toString().take(12);val arr=JSONArray()
            list.distinctBy{it.weight to it.italic}.forEachIndexed{k,f->val name="$id-$k.font";File(dir(c),name).writeBytes(f.bytes);arr.put(JSONObject().put("f",name).put("w",f.weight).put("i",f.italic))}
            manifest(c,id).writeText(arr.toString())
            if(typeface(c,PREFIX+id)==null){remove(c,id);throw IllegalArgumentException("Шрифт повреждён — его не удалось открыть")}
            val name=family.ifBlank{list.first().full?:fileName.substringBeforeLast('.').replace(Regex("[-_]+")," ").trim()}.ifBlank{"Мой шрифт"}
            PREFIX+id to name}
    }
    private fun fromZip(bytes:ByteArray):List<Face>{
        val out=mutableListOf<Face>();var total=0L
        try{java.util.zip.ZipInputStream(bytes.inputStream()).use{zip->
            while(true){val e=zip.nextEntry?:break;val n=e.name.substringAfterLast('/')
                if(e.isDirectory||e.name.contains("__MACOSX")||n.startsWith(".")||!(n.endsWith(".ttf",true)||n.endsWith(".otf",true))){zip.closeEntry();continue}
                val data=zip.readBytes();total+=data.size
                require(total<=60L*1024*1024){"Архив слишком большой"}
                face(data)?.let{out.add(it)};zip.closeEntry()}}}
        catch(e:IllegalArgumentException){throw e}catch(_:Exception){throw IllegalArgumentException("Архив повреждён — его не удалось открыть")}
        require(out.isNotEmpty()){"В архиве нет шрифтов TTF или OTF"}
        return out
    }
    private fun tag(b:ByteArray)=if(b.size>=4)String(b,0,4,Charsets.ISO_8859_1) else ""
    private fun reason(b:ByteArray)=if(tag(b)=="wOFF"||tag(b)=="wOF2")"Веб-шрифты WOFF не поддерживаются — нужен TTF, OTF или ZIP" else "Это не файл шрифта — нужен TTF, OTF или ZIP"
    /** A face read from a font file: its family and full name, weight and slant (from the font's own tables). */
    private fun face(b:ByteArray):Face?{val t=tag(b);if(t!="\u0000\u0001\u0000\u0000"&&t!="OTTO"&&t!="true")return null
        return try{
            fun u16(o:Int)=((b[o].toInt() and 255) shl 8) or (b[o+1].toInt() and 255)
            fun u32(o:Int)=(u16(o).toLong() shl 16) or u16(o+2).toLong()
            val tables=HashMap<String,Int>();for(i in 0 until u16(4)){val r=12+i*16;tables[String(b,r,4,Charsets.ISO_8859_1)]=u32(r+8).toInt()}
            val names=HashMap<Int,String>()
            tables["name"]?.let{name->val count=u16(name+2);val strings=name+u16(name+4)
                for(i in 0 until count){val r=name+6+i*12;val platform=u16(r);val lang=u16(r+4);val id=u16(r+6);val len=u16(r+8);val off=strings+u16(r+10)
                    if(id!=1&&id!=2&&id!=4&&id!=16&&id!=17)continue
                    val s=when(platform){3,0->String(b,off,len,Charsets.UTF_16BE);1->String(b,off,len,Charsets.ISO_8859_1);else->continue}.trim()
                    val english=platform==3&&lang==0x409||platform==1&&lang==0
                    if(s.isNotBlank()&&(names[id]==null||english))names[id]=s}}
            var weight=400;var italic=false
            tables["OS/2"]?.let{o->weight=u16(o+4);italic=(u16(o+62) and 1)!=0}
            val sub=(names[17]?:names[2]).orEmpty()
            if(sub.contains("italic",true)||sub.contains("oblique",true))italic=true
            Face(b,(names[16]?:names[1])?.takeIf{it.length<=48},names[4]?.takeIf{it.length<=48},weight.takeIf{it in 1..1000}?:400,italic)
        }catch(_:Exception){Face(b,null,null,400,false)}}

    /** Lists stored fonts (on the main thread). */
    fun register(a:MainActivity,key:String,name:String){val arr=a.prefs.optJSONArray("fonts")?:JSONArray().also{a.prefs.put("fonts",it)}
        arr.put(JSONObject().put("id",key.removePrefix(PREFIX)).put("name",name))}
    private fun remove(c:Context,id:String){dir(c).listFiles()?.filter{it.name==("$id.font")||it.name==("$id.json")||it.name.startsWith("$id-")}?.forEach{it.delete()}}
    fun remove(a:MainActivity,key:String){val id=key.removePrefix(PREFIX);remove(a as Context,id);PolkaFonts.forget(key)
        val arr=a.prefs.optJSONArray("fonts")?:return;a.prefs.put("fonts",JSONArray((0 until arr.length()).mapNotNull{arr.optJSONObject(it)}.filter{it.str("id")!=id}))
        if(a.prefs.str("font")==key)a.prefs.put("font","literata")}
}
