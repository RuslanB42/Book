package app.polka.nativeapp

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val REC_SHELF = "Рекомендации"
fun uid(): String = UUID.randomUUID().toString()
fun String.safeChunks(limit:Int=128*1024):List<String> { val result=mutableListOf<String>(); var off=0; while(off<length) { var end=minOf(length,off+limit); if(end<length && this[end-1].isHighSurrogate() && this[end].isLowSurrogate()) end--; result.add(substring(off,end)); off=end }; return result }
fun JSONObject.str(key: String, fallback: String = ""): String = if (isNull(key)) fallback else optString(key, fallback)
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.copyJson(): JSONObject = JSONObject(toString())

data class BookRow(val id: String, val json: String)
@Entity(tableName = "books", primaryKeys = ["id", "part"]) data class BookChunkRow(val id: String, val part: Int, val json: String)
data class ContentRow(val id: String, val json: String)
@Entity(tableName = "content", primaryKeys = ["id", "part"]) data class ContentChunkRow(val id: String, val part: Int, val json: String)
data class AssetRow(val id: String, val mime: String, val bytes: ByteArray)
@Entity(tableName = "assets", primaryKeys = ["assetId", "part"]) data class AssetChunkRow(val assetId: String, val part: Int, val mime: String, @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val bytes: ByteArray)
data class MetaRow(val id: String, val json: String)
@Entity(tableName = "meta", primaryKeys = ["id", "part"]) data class MetaChunkRow(val id: String, val part: Int, val json: String)
@Dao abstract class LibraryDao {
    @Query("SELECT * FROM books ORDER BY id, part") abstract fun bookChunks(): List<BookChunkRow>
    fun books(): List<BookRow> = bookChunks().groupBy { it.id }.map { (id, rows) -> BookRow(id, rows.joinToString("") { it.json }) }
    @Query("SELECT * FROM content ORDER BY id, part") abstract fun contentChunks(): List<ContentChunkRow>
    fun contents(): List<ContentRow> = contentChunks().groupBy { it.id }.map { (id, rows) -> ContentRow(id, rows.joinToString("") { it.json }) }
    @Query("SELECT * FROM meta ORDER BY id, part") abstract fun metaChunks(): List<MetaChunkRow>
    fun metas(): List<MetaRow> = metaChunks().groupBy { it.id }.map { (id, rows) -> MetaRow(id, rows.joinToString("") { it.json }) }
    @Query("SELECT * FROM assets ORDER BY assetId, part") abstract fun assetChunks(): List<AssetChunkRow>
    @Query("SELECT * FROM assets WHERE assetId=:id ORDER BY part") abstract fun assetChunks(id: String): List<AssetChunkRow>
    fun assets(): List<AssetRow> = assetChunks().groupBy { it.assetId }.values.map { rows -> AssetRow(rows[0].assetId, rows[0].mime, rows.fold(java.io.ByteArrayOutputStream()) { out, row -> out.apply { write(row.bytes) } }.toByteArray()) }
    fun asset(id: String): AssetRow? = assetChunks(id).takeIf { it.isNotEmpty() }?.let { rows -> AssetRow(id, rows[0].mime, rows.fold(java.io.ByteArrayOutputStream()) { out, row -> out.apply { write(row.bytes) } }.toByteArray()) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun putAssetChunk(row: AssetChunkRow)
    @Transaction open fun putAsset(row: AssetRow) { deleteAsset(row.id); if (row.bytes.isEmpty()) putAssetChunk(AssetChunkRow(row.id, 0, row.mime, byteArrayOf())); else (row.bytes.indices step (512 * 1024)).forEachIndexed { index, off -> putAssetChunk(AssetChunkRow(row.id, index, row.mime, row.bytes.copyOfRange(off, minOf(off + 512 * 1024, row.bytes.size)))) } }
    @Query("SELECT * FROM content WHERE id=:id ORDER BY part") abstract fun contentChunks(id: String): List<ContentChunkRow>
    fun content(id: String): ContentRow? = contentChunks(id).takeIf { it.isNotEmpty() }?.let { ContentRow(id, it.joinToString("") { it.json }) }
    @Query("SELECT * FROM books WHERE id=:id ORDER BY part") abstract fun bookChunks(id: String): List<BookChunkRow>
    fun book(id: String): BookRow? = bookChunks(id).takeIf { it.isNotEmpty() }?.let { BookRow(id, it.joinToString("") { it.json }) }
    @Query("SELECT * FROM meta WHERE id=:id ORDER BY part") abstract fun metaChunks(id: String): List<MetaChunkRow>
    fun meta(id: String): MetaRow? = metaChunks(id).takeIf { it.isNotEmpty() }?.let { MetaRow(id, it.joinToString("") { it.json }) }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun putBookChunk(row: BookChunkRow)
    @Transaction open fun putBook(row: BookRow) { deleteBook(row.id); row.json.safeChunks().forEachIndexed { i, json -> putBookChunk(BookChunkRow(row.id, i, json)) } }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun putContentChunk(row: ContentChunkRow)
    @Transaction open fun putContent(row: ContentRow) { deleteContent(row.id); row.json.safeChunks().forEachIndexed { i, json -> putContentChunk(ContentChunkRow(row.id, i, json)) } }
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract fun putMetaChunk(row: MetaChunkRow)
    @Query("DELETE FROM meta WHERE id=:id") abstract fun deleteMeta(id: String)
    @Transaction open fun putMeta(row: MetaRow) { deleteMeta(row.id); row.json.safeChunks().forEachIndexed { i, json -> putMetaChunk(MetaChunkRow(row.id, i, json)) } }
    @Query("DELETE FROM books WHERE id=:id") abstract fun deleteBook(id: String)
    @Query("DELETE FROM content WHERE id=:id") abstract fun deleteContent(id: String)
    @Query("DELETE FROM books") abstract fun clearBooks()
    @Query("DELETE FROM content") abstract fun clearContent()
    @Query("DELETE FROM assets") abstract fun clearAssets()
    @Query("SELECT DISTINCT assetId FROM assets") abstract fun assetIds(): List<String>
    @Query("DELETE FROM assets WHERE assetId=:id") abstract fun deleteAsset(id: String)
    @Query("DELETE FROM meta") abstract fun clearMeta()
}
@Database(entities = [BookChunkRow::class, ContentChunkRow::class, AssetChunkRow::class, MetaChunkRow::class], version = 1, exportSchema = true)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        fun open(context: Context): LibraryDb = Room.databaseBuilder(context, LibraryDb::class.java, "polka.sqlite")
            .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous=FULL")
                }
            }).build()
    }
}
data class Snapshot(val books: List<BookRow>, val content: List<ContentRow>, val meta: List<MetaRow>, val assets: List<AssetRow>)
data class Imported(val book: JSONObject, val content: JSONObject, val assets: List<AssetRow>)
class Library(val db: LibraryDb) {
    val dao get() = db.dao()
    fun snapshot(): Snapshot { var result: Snapshot? = null; db.runInTransaction { result = Snapshot(dao.books(), dao.contents(), dao.metas(), dao.assets()) }; return result!! }
    fun prefs(): JSONObject = dao.meta("prefs")?.let { JSONObject(it.json).optJSONObject("data") } ?: JSONObject().put("shelves", JSONArray()).put("showRecommendations", false).put("theme", "dark").put("fontSize", 19)
    fun savePrefs(prefs: JSONObject) = dao.putMeta(MetaRow("prefs", JSONObject().put("id", "prefs").put("data", prefs).toString()))
    /** Reuse the visible subset's global slots, keeping unrelated shelves in place. */
    fun saveOrder(ids:List<String>):List<JSONObject> {
        require(ids.size==ids.distinct().size)
        var result=emptyList<JSONObject>()
        db.runInTransaction {
            val all=allBooks().sortedWith(compareBy<JSONObject>{it.optDouble("pos",it.optLong("addedAt").toDouble())}.thenBy{it.str("title")}).toMutableList()
            val selected=ids.toSet();val byId=all.associateBy{it.str("id")}
            require(ids.all{it in byId}) { "Библиотека изменилась. Повторите перенос." }
            val slots=all.indices.filter{all[it].str("id") in selected}
            slots.forEachIndexed{i,slot->all[slot]=byId.getValue(ids[i])}
            all.forEachIndexed{i,b->b.put("pos",i);save(b)}
            savePrefs(prefs().put("sort","manual"));result=all
        }
        return result
    }
    fun allBooks(): List<JSONObject> = dao.books().map { JSONObject(it.json) }
    fun save(book: JSONObject) = dao.putBook(BookRow(book.getString("id"), book.toString()))
    fun importBooks(imported: List<Imported>) = db.runInTransaction {
        imported.forEach { item ->
            item.assets.forEach(dao::putAsset)
            dao.putContent(ContentRow(item.book.getString("id"), item.content.toString()))
            save(item.book)
        }
        pruneAssets()
        updateMarker()
    }
    fun replace(snapshot: Snapshot) {
        Backup.validate(snapshot)
        db.runInTransaction {
            dao.clearBooks(); dao.clearContent(); dao.clearMeta(); dao.clearAssets()
            snapshot.assets.forEach(dao::putAsset); snapshot.content.forEach(dao::putContent); snapshot.books.forEach(dao::putBook); snapshot.meta.forEach(dao::putMeta)
            updateMarker()
        }
    }
    fun pruneAssets() {
        val used=mutableSetOf<String>()
        fun refs(value:Any?) { when(value) { is JSONObject -> { if(value.length()==1 && value.has("__polkaBlob")) used.add(value.getString("__polkaBlob")); else value.keys().forEach { refs(value.get(it)) } }; is JSONArray -> for(i in 0 until value.length()) refs(value.get(i)) } }
        val database=db.openHelper.readableDatabase
        for(table in listOf("books","content","meta")) database.query("SELECT DISTINCT id FROM $table").use { ids -> while(ids.moveToNext()) { val json=StringBuilder(); database.query("SELECT json FROM $table WHERE id=? ORDER BY part",arrayOf(ids.getString(0))).use { parts -> while(parts.moveToNext()) json.append(parts.getString(0)) }; refs(JSONObject(json.toString())) } }
        dao.assetIds().filterNot { it in used }.forEach(dao::deleteAsset)
    }
    fun updateMarker() = dao.putMeta(MetaRow("library-state", JSONObject().put("id", "library-state").put("count", dao.books().size).put("at", System.currentTimeMillis()).toString()))
    fun initialize() = db.runInTransaction {
        // Never silently reset or destructively migrate a failed database.
        val result = db.openHelper.writableDatabase.query("PRAGMA quick_check")
        result.use { check(it.moveToFirst() && it.getString(0) == "ok") { "Проверка базы не пройдена. Данные не изменены." } }
        check(dao.books().isEmpty() || (dao.meta("prefs") != null && dao.meta("stats") != null)) { "База неполная. Автоматическая перезапись остановлена." }
        if (dao.meta("prefs") == null) savePrefs(prefs())
        if (dao.meta("stats") == null) dao.putMeta(MetaRow("stats", "{\"id\":\"stats\",\"data\":{\"days\":{},\"books\":{}}}"))
        val marker = dao.meta("library-state")
        if (marker != null) check(JSONObject(marker.json).getInt("count") == dao.books().size) { "Количество книг не совпало. Данные не изменены." }
        updateMarker()
    }
    companion object {
        fun home(books: List<JSONObject>, show: Boolean): List<JSONObject> = books.filter { show || it.str("shelf") != REC_SHELF }
        fun status(book: JSONObject): String = when { (book.optJSONObject("progress")?.optDouble("pct", 0.0) ?: 0.0) >= 1.0 -> "done"; book.optLong("openedAt") > 0L || (book.optJSONObject("progress")?.optDouble("pct", 0.0) ?: 0.0) > 0 -> "reading"; else -> "want" }
    }
}
