package app.polka.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A derived, per-chapter copy of a book's text in app files, so opening a book reads only a small index and the
 * chapter being read instead of loading and parsing the whole book from the database. The database stays the
 * only source of truth: this copy is keyed by the book's file/edit state and rebuilt from it whenever it is stale.
 */
internal object ChapterStore {
    private fun dir(root:File,id:String)=File(File(root,"chapters"),id.replace(Regex("[^A-Za-z0-9_-]"),"_"))

    /** Index (everything except chapter html) with the current chapter's html filled in, or null if missing/stale. */
    fun read(root:File,id:String,key:String,current:Int):JSONObject? = runCatching {
        val d=dir(root,id);if(File(d,"key").takeIf{it.exists()}?.readText()!=key)return null
        val index=JSONObject(File(d,"index.json").readText())
        val chapters=index.getJSONArray("chapters");val n=chapters.length()
        if(n>0){val i=current.coerceIn(0,n-1);chapters.getJSONObject(i).put("html",File(d,"$i.html").readText())}
        index
    }.getOrNull()

    /** One chapter's html, or null if the copy is gone. */
    fun html(root:File,id:String,index:Int):String? = runCatching{File(dir(root,id),"$index.html").readText()}.getOrNull()

    /** Write the copy from a fully parsed content object. Written into a temp folder and swapped in, never half-done. */
    fun write(root:File,id:String,key:String,content:JSONObject){
        runCatching {
            val chapters=content.getJSONArray("chapters");if((0 until chapters.length()).any{!chapters.getJSONObject(it).has("html")})return
            val final=dir(root,id);val tmp=File(final.parentFile,final.name+".tmp");tmp.deleteRecursively();tmp.mkdirs()
            val light=JSONObject();content.keys().forEach{k->if(k!="chapters")light.put(k,content.get(k))}
            val list=JSONArray()
            for(i in 0 until chapters.length()){val ch=chapters.getJSONObject(i);File(tmp,"$i.html").writeText(ch.str("html"));val c=JSONObject();ch.keys().forEach{k->if(k!="html")c.put(k,ch.get(k))};list.put(c)}
            light.put("chapters",list);File(tmp,"index.json").writeText(light.toString());File(tmp,"key").writeText(key)
            final.deleteRecursively();tmp.renameTo(final)
        }
    }
    /** Just the index (chapter titles, lengths, toc) — no html. */
    fun index(root:File,id:String,key:String):JSONObject? = runCatching{val d=dir(root,id);if(File(d,"key").readText()!=key)null else JSONObject(File(d,"index.json").readText())}.getOrNull()
    fun drop(root:File,id:String){runCatching{dir(root,id).deleteRecursively()}}
    fun has(root:File,id:String,key:String)=runCatching{File(dir(root,id),"key").readText()==key}.getOrDefault(false)
}

/** Book images loaded from the database only when a page actually shows them (always off the main thread). */
internal class LazyImages(private val library:Library,private val refs:JSONObject?):AbstractMap<String,ByteArray>(){
    private val loaded=HashMap<String,ByteArray>()
    private val missing=HashSet<String>()
    override val entries:Set<Map.Entry<String,ByteArray>> get(){refs?.keys()?.forEach{get(it)};return synchronized(loaded){HashMap(loaded).entries}}
    override fun containsKey(key:String)=refs?.has(key)==true
    override fun get(key:String):ByteArray?{
        synchronized(loaded){loaded[key]?.let{return it};if(key in missing)return null}
        val id=refs?.optJSONObject(key)?.str("__polkaBlob");val bytes=id?.let{library.dao.asset(it)?.bytes}
        synchronized(loaded){if(bytes!=null)loaded[key]=bytes else missing.add(key)};return bytes
    }
}

