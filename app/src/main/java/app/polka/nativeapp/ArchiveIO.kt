package app.polka.nativeapp

import android.content.Context
import androidx.room.Room
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Streaming archive I/O: an entire library is never loaded into RAM for backup/restore. */
object ArchiveIO {
    private val magic="POLKA_BACKUP_V1\n".toByteArray()
    private const val CHUNK=4*1024*1024
    private data class Part(val type:String,val size:Long,val offset:Long,val hashes:List<String>)
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun hashFile(file:RandomAccessFile,offset:Long,size:Long):List<String> { file.seek(offset); var remaining=size; val hashes=mutableListOf<String>(); while(remaining>0) { val buf=ByteArray(minOf(CHUNK.toLong(),remaining).toInt()); file.readFully(buf); hashes.add(digest(buf)); remaining-=buf.size }; return hashes }
    private fun transform(value:Any?, depth:Int=0, blob:(Any)->Any):Any {
        require(depth<100) { "Слишком глубокая структура архива" }
        return when(value) {
            is JSONObject -> if(value.length()==1 && value.has("__polkaBlob")) JSONObject().put("__polkaBlob",blob(value.get("__polkaBlob"))) else JSONObject().apply { value.keys().forEach { key -> require(key !in setOf("__proto__","constructor","prototype")); put(key,transform(value.get(key),depth+1,blob)) } }
            is JSONArray -> JSONArray().apply { for(i in 0 until value.length()) put(transform(value.get(i),depth+1,blob)) }
            null -> JSONObject.NULL
            else -> value
        }
    }
    fun write(library:Library, destination:File) {
        val spool=File(destination.parentFile,destination.name+".parts")
        try {
            val parts=JSONArray(); val records=JSONArray(); val indexes=mutableMapOf<String,Int>()
            FileOutputStream(spool).use { output ->
                library.db.runInTransaction {
                    val database=library.db.openHelper.readableDatabase
                    database.query("SELECT DISTINCT assetId, mime FROM assets ORDER BY assetId").use { ids -> while(ids.moveToNext()) {
                        val id=ids.getString(0); val mime=ids.getString(1); val hashes=JSONArray(); val buffer=ByteArrayOutputStream(CHUNK); var total=0L
                        fun flush() { if(buffer.size()>0) { hashes.put(digest(buffer.toByteArray())); buffer.reset() } }
                        database.query("SELECT bytes FROM assets WHERE assetId=? ORDER BY part",arrayOf(id)).use { chunks -> while(chunks.moveToNext()) { val bytes=chunks.getBlob(0); output.write(bytes); total+=bytes.size; var off=0; while(off<bytes.size) { val n=minOf(CHUNK-buffer.size(),bytes.size-off); buffer.write(bytes,off,n); off+=n; if(buffer.size()==CHUNK) flush() } } }; flush()
                        indexes[id]=parts.length(); parts.put(JSONObject().put("size",total).put("type",mime).put("hashes",hashes))
                    } }
                    for(store in listOf("books","content","meta")) {
                        database.query("SELECT DISTINCT id FROM $store ORDER BY id").use { ids -> while(ids.moveToNext()) {
                            val id=ids.getString(0); val json=StringBuilder(); database.query("SELECT json FROM $store WHERE id=? ORDER BY part",arrayOf(id)).use { chunks -> while(chunks.moveToNext()) json.append(chunks.getString(0)) }
                            val encoded=transform(JSONObject(json.toString())) { key -> indexes[key.toString()] ?: error("Отсутствует вложенный файл") }.toString().toByteArray(Charsets.UTF_8)
                            records.put(JSONObject().put("store",store).put("id",id).put("part",parts.length()))
                            output.write(encoded); val hashes=JSONArray(); for(off in encoded.indices step CHUNK) hashes.put(digest(encoded.copyOfRange(off,minOf(encoded.size,off+CHUNK))))
                            parts.put(JSONObject().put("size",encoded.size).put("type","application/json").put("hashes",hashes))
                        } }
                    }
                }
                output.fd.sync()
            }
            val manifest=JSONObject().put("app","polka").put("version",1).put("createdAt",java.time.Instant.now()).put("records",records).put("parts",parts).toString().toByteArray(Charsets.UTF_8)
            require(manifest.size<=16*1024*1024) { "Слишком большой каталог архива" }
            FileOutputStream(destination).use { raw -> val output=DataOutputStream(raw); output.write(magic); output.writeLong(manifest.size.toLong()); for(off in manifest.indices step CHUNK) output.write(digest(manifest.copyOfRange(off,minOf(manifest.size,off+CHUNK))).toByteArray(Charsets.US_ASCII)); output.write(manifest); spool.inputStream().use { it.copyTo(output) }; output.flush(); raw.fd.sync() }
            inspect(destination)
        } finally { spool.delete() }
    }
    private fun inspect(file:File):Pair<JSONObject,List<Part>> = RandomAccessFile(file,"r").use { input ->
        val got=ByteArray(magic.size); input.readFully(got); require(got.contentEquals(magic)) { "Это не резервная копия Полки" }
        val n=input.readLong(); require(n in 2..16L*1024*1024) { "Повреждён каталог архива" }
        val hashBytes=ByteArray((((n+CHUNK-1)/CHUNK)*64).toInt()); input.readFully(hashBytes); val manifestOffset=input.filePointer
        val bytes=ByteArray(n.toInt()); input.readFully(bytes); require(hashFile(input,manifestOffset,n).joinToString("")==String(hashBytes,Charsets.US_ASCII)) { "Каталог архива повреждён" }
        val manifest=JSONObject(String(bytes,Charsets.UTF_8)); require(manifest.str("app")=="polka" && manifest.optInt("version")==1) { "Версия архива не поддерживается" }
        val parts=mutableListOf<Part>(); var off=manifestOffset+n
        manifest.getJSONArray("parts").objects().forEach { part -> val size=part.getLong("size"); require(size>=0 && size<=file.length()-off) { "Архив обрезан" }; val hashes=part.getJSONArray("hashes").strings(); require(hashFile(input,off,size)==hashes) { "Контрольная сумма не совпала. Данные не изменены." }; parts.add(Part(part.getString("type"),size,off,hashes)); off+=size }
        require(off==file.length()) { "Неверная длина архива" }; manifest to parts
    }
    fun checkpoint(library:Library,folder:File,label:String="latest") {
        folder.mkdirs(); val temp=File(folder,"$label.tmp"); val target=File(folder,"$label.polka")
        try { write(library,temp); if(label=="latest" && target.exists()) { val prev=File(folder,"previous.tmp"); Files.copy(target.toPath(),prev.toPath(),StandardCopyOption.REPLACE_EXISTING); Files.move(prev.toPath(),File(folder,"previous.polka").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) }; Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE) } finally { temp.delete() }
    }
    fun restore(context:Context, library:Library, archive:File, beforeReplace:()->Unit) {
        val (manifest,parts)=inspect(archive)
        val stageName="polka-restore-${uid()}.sqlite"
        val stage=Room.databaseBuilder(context,LibraryDb::class.java,stageName).build(); val staging=Library(stage)
        try {
            val assets=mutableMapOf<Int,String>(); val seen=mutableSetOf<String>()
            RandomAccessFile(archive,"r").use { input -> stage.runInTransaction {
                manifest.getJSONArray("records").objects().forEach { record ->
                    val store=record.getString("store"); val id=record.getString("id"); val partIndex=record.getInt("part")
                    require(store in setOf("books","content","meta") && seen.add("$store:$id") && partIndex in parts.indices) { "Неверная запись архива" }
                    val part=parts[partIndex]; require(part.size<=64L*1024*1024) { "Запись книги больше 64 МБ" }
                    val bytes=ByteArray(part.size.toInt()); input.seek(part.offset); input.readFully(bytes)
                    val json=transform(JSONObject(String(bytes,Charsets.UTF_8))) { key ->
                        require(key is Number && key.toInt().toDouble()==key.toDouble() && key.toInt() in parts.indices) { "Неверная ссылка на файл" }
                        assets.getOrPut(key.toInt()) {
                            val assetId=uid(); val source=parts[key.toInt()]; var off=0L; var index=0
                            if(source.size==0L) staging.dao.putAssetChunk(AssetChunkRow(assetId,0,source.type,byteArrayOf()))
                            while(off<source.size) { val data=ByteArray(minOf(512*1024L,source.size-off).toInt()); input.seek(source.offset+off); input.readFully(data); staging.dao.putAssetChunk(AssetChunkRow(assetId,index++,source.type,data)); off+=data.size }; assetId
                        }
                    } as JSONObject
                    require(json.getString("id")==id)
                    when(store) { "books" -> staging.dao.putBook(BookRow(id,json.toString())); "content" -> staging.dao.putContent(ContentRow(id,json.toString())); "meta" -> staging.dao.putMeta(MetaRow(id,json.toString())) }
                }
            } }
            // Validate each book independently, not the whole library in memory.
            val books=staging.allBooks()
            books.forEach { book ->
                require(book.get("title") is String && book.optJSONArray("authors")!=null)
                if(!book.optBoolean("noFile")) { val c=staging.dao.content(book.getString("id")) ?: error("Отсутствует текст книги"); val chapters=JSONObject(c.json).getJSONArray("chapters"); require(chapters.length()>0); chapters.objects().forEach { require(it.get("html") is String && it.get("len") is Number) } }
            }
            val settings=staging.dao.meta("prefs") ?: error("В архиве нет настроек"); require(JSONObject(settings.json).getJSONObject("data").optJSONArray("shelves")!=null)
            val stats=staging.dao.meta("stats") ?: error("В архиве нет статистики"); require(JSONObject(stats.json).getJSONObject("data").optJSONObject("days")!=null)
            staging.dao.meta("library-state")?.let { require(JSONObject(it.json).getInt("count")==books.size) { "Количество книг не совпало" } }
            beforeReplace()
            library.db.runInTransaction {
                val dest=library.db.openHelper.writableDatabase
                for(table in listOf("books","content","meta","assets")) {
                    dest.execSQL("DELETE FROM $table")
                    val columns=if(table=="assets") listOf("assetId","part","mime","bytes") else listOf("id","part","json")
                    val statement=dest.compileStatement("INSERT INTO $table (${columns.joinToString(",")}) VALUES (${columns.joinToString(",") { "?" }})")
                    try { stage.openHelper.readableDatabase.query("SELECT ${columns.joinToString(",")} FROM $table").use { cursor -> while(cursor.moveToNext()) { statement.clearBindings(); columns.forEachIndexed { index,name -> when(name) { "part" -> statement.bindLong(index+1,cursor.getLong(index)); "bytes" -> statement.bindBlob(index+1,cursor.getBlob(index)); else -> statement.bindString(index+1,cursor.getString(index)) } }; statement.executeInsert() } } } finally { statement.close() }
                }
                library.updateMarker()
            }
        } finally { stage.close(); context.deleteDatabase(stageName) }
    }
}
