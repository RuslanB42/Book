package app.polka.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import java.nio.charset.Charset

/** No WebView or JavaScript: XML/HTML is parsed and rendered by native Android views. */
object BookParser {
    private const val MAX = 128 * 1024 * 1024
    fun parse(bytes: ByteArray, name: String): Imported {
        require(bytes.size <= MAX) { "Книга больше 128 МБ" }
        val zip = name.endsWith(".zip", true) || name.endsWith(".epub", true)
        if (!zip) return fb2(bytes, name)
        val files = linkedMapOf<String, ByteArray>(); var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { stream ->
            var entry = stream.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(32768)
                    while (true) { val n = stream.read(buf); if (n < 0) break; total += n; require(total <= MAX) { "Распакованная книга больше 128 МБ" }; out.write(buf, 0, n) }
                    files[normalize(entry.name)] = out.toByteArray()
                }
                entry = stream.nextEntry
            }
        }
        val fb2 = files.entries.firstOrNull { it.key.endsWith(".fb2", true) }
        val parsed = if (fb2 != null) fb2(fb2.value, name) else epub(files, name)
        val source = AssetRow(uid(), "application/octet-stream", bytes)
        parsed.content.put("source", JSONObject().put("__polkaBlob", source.id))
        return parsed.copy(assets = parsed.assets.filterNot { it.id == parsed.content.optJSONObject("source")?.str("__polkaBlob") } + source)
    }
    private fun decode(bytes: ByteArray): String {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 200)), Charsets.ISO_8859_1)
        val encoding = Regex("encoding\\s*=\\s*['\"]([^'\"]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1) ?: "UTF-8"
        return String(bytes, try { Charset.forName(encoding) } catch (_: Exception) { Charsets.UTF_8 })
    }
    private fun newBook(name: String, format: String) = JSONObject().put("id", uid()).put("title", name.substringBeforeLast('.')).put("authors", JSONArray()).put("genres", JSONArray()).put("format", format).put("fileName", name).put("addedAt", System.currentTimeMillis()).put("openedAt", 0).put("progress", JSONObject().put("ch", 0).put("block", 0).put("off", 0).put("pct", 0)).put("shelf", "").put("quotes", JSONArray()).put("bookmarks", JSONArray()).put("notes", JSONArray())
    private fun Element.local(): String = tagName().substringAfter(':').lowercase()
    private fun Element.child(tag: String): Element? = children().firstOrNull { it.local() == tag.lowercase() }
    private fun Element.all(tag: String): List<Element> = getAllElements().toList().filter { it.local() == tag.lowercase() }
    private fun ref(asset: AssetRow) = JSONObject().put("__polkaBlob", asset.id)
    private fun chapter(title: String, html: String) = JSONObject().put("title", title.ifBlank { "Начало" }).put("html", html).put("len", Jsoup.parse(html).text().length).put("depth", 0)
    fun fb2(bytes: ByteArray, name: String): Imported {
        val xml = decode(bytes); val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val root = doc.children().firstOrNull { it.local() == "fictionbook" } ?: error("Не удалось прочитать FB2")
        val ti = root.child("description")?.child("title-info")
        val assets = mutableListOf<AssetRow>(); val images = JSONObject()
        root.children().toList().filter { it.local() == "binary" }.forEach { binary ->
            val data = java.util.Base64.getMimeDecoder().decode(binary.text())
            val a = AssetRow(uid(), binary.attr("content-type").ifBlank { "image/jpeg" }, data); assets.add(a); images.put("#${binary.id()}", ref(a))
        }
        val book = newBook(name, "fb2").put("size", bytes.size).put("title", ti?.child("book-title")?.text()?.ifBlank { null } ?: name.substringBeforeLast('.'))
        book.put("authors", JSONArray(ti?.children()?.toList()?.filter { it.local() == "author" }?.map { a -> listOf("first-name", "middle-name", "last-name").mapNotNull { a.child(it)?.text() }.joinToString(" ").ifBlank { a.child("nickname")?.text() ?: "" } } ?: emptyList<String>()))
        book.put("annotation", ti?.child("annotation")?.text() ?: ""); book.put("genres", JSONArray(ti?.children()?.toList()?.filter { it.local() == "genre" }?.map { it.text() } ?: emptyList<String>()))
        val cover = ti?.child("coverpage")?.child("image")
        val key = cover?.attributes()?.firstOrNull { it.key.substringAfter(':') == "href" }?.value
        if (key != null && images.has(key)) book.put("cover", images.get(key))
        book.put("lang",ti?.child("lang")?.text() ?: "ru"); ti?.child("sequence")?.let { book.put("series",it.attr("name")).put("seriesNo",it.attr("number").toDoubleOrNull() ?: 0.0) }
        val chapters = JSONArray()
        fun nativeHtml(el: Element): String {
            val copy = el.clone()
            copy.getAllElements().toList().forEach { e -> when (e.local()) {
                "title" -> e.tagName("h2")
                "subtitle" -> { e.tagName("p"); e.addClass("subtitle") }
                "emphasis" -> e.tagName("em")
                "strikethrough" -> e.tagName("s")
                "a" -> { e.attributes().firstOrNull { it.key.substringAfter(':')=="href" }?.value?.let { e.attr("href",it) } }
                "empty-line" -> { e.tagName("p"); e.addClass("empty"); e.text("\u00a0") }
                "epigraph","poem","stanza","cite" -> { e.addClass(e.local()); e.tagName(if(e.local()=="cite") "blockquote" else "div") }
                "text-author" -> { e.tagName("p"); e.addClass("author") }
                "v" -> { e.tagName("p"); e.addClass("v") }
                "image" -> { val src = e.attributes().firstOrNull { it.key.substringAfter(':') == "href" }?.value; e.tagName("img"); e.attr("src", src ?: "") }
            } }
            return copy.html()
        }
        fun section(el: Element, depth:Int=0,notes:Boolean=false) {
            val nested = el.children().toList().filter { it.local() == "section" }
            val title = el.child("title")?.text().orEmpty()
            val copy = el.clone(); copy.children().toList().filter { it.local() == "section" }.forEach { it.remove() }
            if (copy.text().isNotBlank() || copy.all("image").isNotEmpty()) chapters.put(chapter(title.ifBlank { if(notes) "Примечания" else "Глава ${chapters.length() + 1}" }, if(el.id().isNotBlank()) "<div id=\"${el.id()}\">${nativeHtml(copy)}</div>" else nativeHtml(copy)).put("depth",depth).put("notes",notes))
            nested.forEach { section(it,depth+1,notes) }
        }
        root.children().toList().filter { it.local() == "body" }.forEach { section(it,0,it.attr("name")=="notes" || it.attr("name")=="comments") }
        require(chapters.length() > 0) { "В книге нет текста" }
        val source = AssetRow(uid(), "application/x-fictionbook+xml", bytes); assets.add(source)
        book.put("chars",chapters.objects().filterNot { it.optBoolean("notes") }.sumOf { it.optInt("len") }); val content = JSONObject().put("id", book.getString("id")).put("chapters", chapters).put("images", images).put("source", ref(source)).put("toc", JSONArray(chapters.objects().mapIndexed { i, c -> JSONObject().put("title", c.getString("title")).put("ch", i).put("depth", 0) }))
        return Imported(book, content, assets)
    }
    private fun normalize(path: String): String {
        val stack = mutableListOf<String>(); android.net.Uri.decode(path).replace('\\', '/').substringBefore('#').split('/').forEach { when(it) { "", "." -> Unit; ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); else -> stack.add(it) } }; return stack.joinToString("/")
    }
    private fun resolve(base: String, href: String):String {
        val path=href.substringBefore('#').substringBefore('?')
        return if(path.isBlank()) normalize(base) else normalize(base.substringBeforeLast('/', "").let { if(it.isEmpty()) path else "$it/$path" })
    }
    private fun target(base:String,href:String)=resolve(base,href)+(if(href.contains("#")) "#"+android.net.Uri.decode(href.substringAfter('#')) else "")
    private fun epub(files: Map<String, ByteArray>, name: String): Imported {
        val container = files["META-INF/container.xml"] ?: error("В архиве нет EPUB")
        val cd = Jsoup.parse(decode(container), "", Parser.xmlParser())
        val opfPath = cd.all("rootfile").firstOrNull()?.attr("full-path") ?: error("Нет описания EPUB")
        val opf = Jsoup.parse(decode(files[opfPath] ?: error("Нет OPF")), "", Parser.xmlParser())
        val book = newBook(name, "epub").put("title", opf.all("title").firstOrNull()?.text() ?: name.substringBeforeLast('.')).put("authors", JSONArray(opf.all("creator").map { it.text() })).put("genres", JSONArray(opf.all("subject").map { it.text() })).put("annotation", opf.all("description").firstOrNull()?.text() ?: "")
        book.put("lang",opf.all("language").firstOrNull()?.text() ?: "ru"); book.put("size",files.values.sumOf { it.size }); val items = opf.all("item").associateBy { it.id() }; val assets = mutableListOf<AssetRow>(); val images = JSONObject()
        items.values.filter { it.attr("media-type").startsWith("image/") }.forEach { item ->
            val path = resolve(opfPath, item.attr("href")); files[path]?.let { bytes -> val a = AssetRow(uid(), item.attr("media-type"), bytes); assets.add(a); images.put(path, ref(a)) }
        }
        val coverId = opf.all("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")
        val cover = items.values.firstOrNull { "cover-image" in it.attr("properties").split(' ') } ?: coverId?.let { items[it] } ?: items.values.firstOrNull { it.attr("media-type").startsWith("image/") && it.attr("href").contains("cover", true) }
        cover?.let { val path = resolve(opfPath, it.attr("href")); if (images.has(path)) book.put("cover", images.get(path)) }
        // Match the original reading model: publisher CSS is intentionally discarded.
        val entries=mutableListOf<JSONObject>()
        val nav=items.values.firstOrNull { "nav" in it.attr("properties").split(Regex("\\s+")) }
        if(nav!=null) {
            val path=resolve(opfPath,nav.attr("href"))
            files[path]?.let { data ->
                val doc=Jsoup.parse(decode(data)); val menus=doc.select("nav")
                val menu=menus.firstOrNull { it.attr("epub:type").split(' ').contains("toc") || it.attr("role")=="doc-toc" } ?: menus.firstOrNull()
                fun walk(ol:Element,depth:Int) { ol.children().toList().filter { it.local()=="li" }.forEach { li ->
                    li.selectFirst("a[href]")?.let { link -> entries.add(JSONObject().put("title",link.text()).put("target",target(path,link.attr("href"))).put("depth",depth.coerceAtMost(3))) }
                    li.children().firstOrNull { it.local()=="ol" }?.let { walk(it,depth+1) }
                } }
                menu?.children()?.firstOrNull { it.local()=="ol" }?.let { walk(it,0) }
            }
        }
        if(entries.isEmpty()) {
            val spine=opf.all("spine").firstOrNull()
            val ncx=items[spine?.attr("toc")] ?: items.values.firstOrNull { it.attr("media-type")=="application/x-dtbncx+xml" }
            ncx?.let { item -> val path=resolve(opfPath,item.attr("href")); files[path]?.let { data ->
                val doc=Jsoup.parse(decode(data),"",Parser.xmlParser())
                fun walk(el:Element,depth:Int) { el.children().toList().filter { it.local()=="navpoint" }.forEach { point ->
                    point.child("content")?.let { link -> entries.add(JSONObject().put("title",point.child("navlabel")?.text().orEmpty()).put("target",target(path,link.attr("src"))).put("depth",depth.coerceAtMost(3))) }; walk(point,depth+1)
                } }
                doc.all("navmap").firstOrNull()?.let { walk(it,0) }
            } }
        }
        val chapters = JSONArray()
        opf.all("itemref").forEach { r -> items[r.attr("idref")]?.takeIf { it.attr("media-type").contains("html") || it.attr("media-type").contains("xml") }?.let { item ->
            val path = resolve(opfPath, item.attr("href")); files[path]?.let { bytes ->
                val html = Jsoup.parse(decode(bytes))
                html.select("script,style,link,meta,base,iframe,frame,object,embed,form,input,button,select,textarea,audio,video,noscript,title").remove()
                html.getAllElements().forEach { el -> el.attributes().asList().filter { it.key.startsWith("on",true) || it.key in listOf("style","srcset","contenteditable") || it.value.trimStart().startsWith("javascript:",true) }.forEach { el.removeAttr(it.key) } }
                html.select("img").forEach { el -> el.attr("src",resolve(path,el.attr("src"))) }
                // A common EPUB cover is an SVG <image> wrapper around a raster resource.
                html.select("svg image").forEach { el -> val href=el.attr("xlink:href").ifBlank { el.attr("href") }; if(href.isNotBlank()) { val image=Element("img").attr("src",resolve(path,href)); el.closest("svg")?.replaceWith(image) } }
                html.select("a[href]").forEach { el -> val href=el.attr("href"); if(!href.contains(":")) el.attr("href",target(path,href)) }
                chapters.put(chapter("", html.body().html()).put("title","").put("heading",html.selectFirst("h1,h2,h3")?.text().orEmpty()).put("path",path).put("kind","file"))
            }
        } }
        require(chapters.length() > 0) { "В EPUB нет текста" }
        val byPath=chapters.objects().mapIndexed { i,ch -> ch.str("path") to i }.toMap(); val toc=JSONArray()
        entries.forEach { e -> val target=e.str("target"); val ch=byPath[target.substringBefore('#')]; if(ch!=null && e.str("title").isNotBlank()) {
            toc.put(JSONObject().put("title",e.str("title")).put("ch",ch).put("anchor",target.substringAfter('#',"")).put("depth",e.optInt("depth")))
            if(chapters.getJSONObject(ch).str("title").isBlank()) chapters.getJSONObject(ch).put("title",e.str("title"))
        } }
        chapters.objects().forEachIndexed { i,ch -> if(ch.str("title").isBlank()) ch.put("title",ch.str("heading").ifBlank { if(i>0) chapters.getJSONObject(i-1).str("title") else "Начало" }); ch.remove("heading") }
        if(toc.length()==0) chapters.objects().forEachIndexed { i,ch -> toc.put(JSONObject().put("title",ch.str("title")).put("ch",i).put("anchor","").put("depth",0)) }
        val meta=opf.all("meta"); val collection=meta.firstOrNull { it.attr("property")=="belongs-to-collection" }
        val series=meta.firstOrNull { it.attr("name")=="calibre:series" }?.attr("content") ?: collection?.text().orEmpty()
        val seriesNo=meta.firstOrNull { it.attr("name")=="calibre:series_index" }?.attr("content") ?: collection?.id()?.let { id -> meta.firstOrNull { it.attr("refines")=="#$id" && it.attr("property")=="group-position" }?.text() }.orEmpty()
        book.put("series",series).put("seriesNo",seriesNo).put("annotation",Jsoup.parse(book.str("annotation")).text())
        book.put("chars",chapters.objects().sumOf { it.optInt("len") })
        return Imported(book,JSONObject().put("id",book.str("id")).put("chapters",chapters).put("images",images).put("toc",toc).put("opfPath",opfPath),assets)
    }
}
