package app.polka.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest

/** Byte-compatible with POLKA_BACKUP_V1 archives exported by the original app. */
object Backup {
    private val magic = "POLKA_BACKUP_V1\n".toByteArray(Charsets.UTF_8)
    private const val chunk = 4 * 1024 * 1024
    private const val MAX_TOTAL = 512L * 1024 * 1024
    private fun hashes(bytes: ByteArray): List<String> = (bytes.indices step chunk).map { off ->
        MessageDigest.getInstance("SHA-256").digest(bytes.copyOfRange(off, minOf(bytes.size, off + chunk))).joinToString("") { "%02x".format(it) }
    }
    private fun readExact(input: InputStream, n: Int): ByteArray {
        require(n >= 0) { "Неверный размер" }; val result = ByteArray(n); var off = 0
        while (off < n) { val got = input.read(result, off, n - off); require(got > 0) { "Архив обрезан" }; off += got }; return result
    }
    private fun visit(value: Any?, transform: (Any) -> Any): Any = when (value) {
        is JSONObject -> if (value.length() == 1 && value.has("__polkaBlob")) JSONObject().put("__polkaBlob", transform(value.get("__polkaBlob"))) else JSONObject().apply { value.keys().forEach { k -> require(k !in setOf("__proto__", "constructor", "prototype")); put(k, visit(value.get(k), transform)) } }
        is JSONArray -> JSONArray().apply { for (i in 0 until value.length()) put(visit(value.get(i), transform)) }
        null -> JSONObject.NULL
        else -> value
    }
    fun write(snapshot: Snapshot, output: OutputStream) {
        validate(snapshot)
        val parts = mutableListOf<Pair<String, ByteArray>>()
        val indexes = mutableMapOf<String, Int>()
        snapshot.assets.forEach { a -> indexes[a.id] = parts.size; parts.add(a.mime to a.bytes) }
        val records = JSONArray()
        fun add(store: String, id: String, json: String) {
            val encoded = visit(JSONObject(json)) { key -> indexes[key.toString()] ?: error("Не найден файл книги") }
            records.put(JSONObject().put("store", store).put("id", id).put("part", parts.size))
            parts.add("application/json" to encoded.toString().toByteArray(Charsets.UTF_8))
        }
        snapshot.books.forEach { add("books", it.id, it.json) }; snapshot.content.forEach { add("content", it.id, it.json) }; snapshot.meta.forEach { add("meta", it.id, it.json) }
        val descriptors = JSONArray()
        parts.forEach { (type, bytes) -> descriptors.put(JSONObject().put("size", bytes.size).put("type", type).put("hashes", JSONArray(hashes(bytes)))) }
        val manifest = JSONObject().put("app", "polka").put("version", 1).put("createdAt", java.time.Instant.now().toString()).put("parts", descriptors).put("records", records).toString().toByteArray(Charsets.UTF_8)
        require(manifest.size <= 16 * 1024 * 1024)
        val stream = DataOutputStream(output)
        stream.write(magic); stream.writeLong(manifest.size.toLong()); stream.write(hashes(manifest).joinToString("").toByteArray(Charsets.US_ASCII)); stream.write(manifest)
        parts.forEach { stream.write(it.second) }; stream.flush()
    }
    fun read(input: InputStream): Snapshot {
        require(readExact(input, magic.size).contentEquals(magic)) { "Это не резервная копия Полки" }
        val n = DataInputStream(input).readLong(); require(n in 2..16L * 1024 * 1024) { "Неверный каталог" }
        val expected = String(readExact(input, (((n + chunk - 1) / chunk) * 64).toInt()), Charsets.US_ASCII)
        val manifestBytes = readExact(input, n.toInt()); require(hashes(manifestBytes).joinToString("") == expected) { "Каталог архива повреждён" }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8)); require(manifest.str("app") == "polka" && manifest.getInt("version") == 1) { "Версия архива не поддерживается" }
        val parts = mutableListOf<Pair<String, ByteArray>>(); var total = n
        manifest.getJSONArray("parts").objects().forEach { p ->
            val size = p.getLong("size"); total += size
            require(size in 0..128L * 1024 * 1024 && total <= MAX_TOTAL) { "Архив слишком большой для этой версии" }
            val bytes = readExact(input, size.toInt()); require(hashes(bytes) == p.getJSONArray("hashes").strings()) { "Контрольная сумма не совпала. Ничего не изменено." }
            parts.add(p.getString("type") to bytes)
        }
        require(input.read() == -1) { "Неверная длина архива" }
        val assets = mutableMapOf<Int, AssetRow>(); val books = mutableListOf<BookRow>(); val content = mutableListOf<ContentRow>(); val meta = mutableListOf<MetaRow>(); val ids = mutableSetOf<String>()
        manifest.getJSONArray("records").objects().forEach { record ->
            val store = record.getString("store"); val id = record.getString("id"); val part = record.getInt("part")
            require(store in setOf("books", "content", "meta") && ids.add("$store:$id") && part in parts.indices) { "Неверная запись архива" }
            val json = visit(JSONObject(String(parts[part].second, Charsets.UTF_8))) { key ->
                require(key is Number && key.toInt().toDouble() == key.toDouble() && key.toInt() in parts.indices) { "Неверная ссылка на файл" }
                assets.getOrPut(key.toInt()) { val pair = parts[key.toInt()]; AssetRow(uid(), pair.first, pair.second) }.id
            } as JSONObject
            require(json.getString("id") == id)
            when (store) { "books" -> books.add(BookRow(id, json.toString())); "content" -> content.add(ContentRow(id, json.toString())); "meta" -> meta.add(MetaRow(id, json.toString())) }
        }
        return Snapshot(books, content, meta, assets.values.toList()).also(::validate)
    }
    fun validate(snapshot: Snapshot) {
        require(snapshot.books.map { it.id }.toSet().size == snapshot.books.size)
        require(snapshot.content.map { it.id }.toSet().size == snapshot.content.size)
        require(snapshot.meta.map { it.id }.toSet().size == snapshot.meta.size)
        val content = snapshot.content.associateBy { it.id }; val assets = snapshot.assets.map { it.id }.toSet()
        fun checkRefs(json: String) { visit(JSONObject(json)) { key -> require(key is String && key in assets) { "Не найден файл внутри копии" }; key } }
        snapshot.books.forEach { row ->
            val book = JSONObject(row.json); require(book.getString("id") == row.id && book.get("title") is String && book.optJSONArray("authors") != null)
            if (!book.optBoolean("noFile")) {
                val c = content[row.id] ?: error("В копии отсутствует текст книги")
                val chapters = JSONObject(c.json).getJSONArray("chapters"); require(chapters.length() > 0)
                chapters.objects().forEach { require(it.get("html") is String && it.get("len") is Number) }
            }
            checkRefs(row.json)
        }
        snapshot.content.forEach { checkRefs(it.json) }; snapshot.meta.forEach { checkRefs(it.json) }
        val prefs = snapshot.meta.find { it.id == "prefs" } ?: error("В копии нет настроек")
        require(JSONObject(prefs.json).getJSONObject("data").optJSONArray("shelves") != null)
        val stats = snapshot.meta.find { it.id == "stats" } ?: error("В копии нет статистики")
        require(JSONObject(stats.json).getJSONObject("data").optJSONObject("days") != null)
        snapshot.meta.find { it.id == "library-state" }?.let { require(JSONObject(it.json).getInt("count") == snapshot.books.size) { "Неверное количество книг в копии" } }
    }
    fun checkpoint(library: Library, folder: File, label: String = "latest") {
        folder.mkdirs()
        val temp = File(folder, "$label.tmp")
        try {
            FileOutputStream(temp).use { out -> write(library.snapshot(), out); out.fd.sync() }
            // Validate the finished file before replacing an existing checkpoint.
            FileInputStream(temp).use(::read)
            val target = File(folder, "$label.polka")
            if (label == "latest" && target.exists()) java.nio.file.Files.copy(target.toPath(), File(folder, "previous.polka").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
    }
}
