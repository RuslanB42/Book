package app.polka.nativeapp
import org.json.*
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.util.zip.*
import android.util.Base64

object BookExport {
    private fun x(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun build(lib:Library,b:JSONObject,format:String):ByteArray {
        val content=JSONObject(lib.dao.content(b.str("id"))?.json ?: error("Текст книги не найден")); val chapters=content.getJSONArray("chapters").objects()
        val blobs=linkedMapOf<String,AssetRow>()
        content.optJSONObject("images")?.let { o -> o.keys().forEach { key -> o.optJSONObject(key)?.str("__polkaBlob")?.let { id -> lib.dao.asset(id)?.let { blobs[key]=it } } } }
        val cover=b.optJSONObject("cover")?.str("__polkaBlob")?.let { lib.dao.asset(it) }
        return if(format=="fb2") {
            val imageIds=blobs.keys.mapIndexed { i,key -> key to "image$i" }.toMap()
            val body=chapters.joinToString("") { ch ->
                val doc=Jsoup.parse(ch.str("html")); doc.select("script,style").remove()
                doc.select("img").forEach { el -> val key=el.attr("data-src").ifBlank { el.attr("src") }; val id=imageIds[key]; if(id==null) el.remove() else { el.tagName("image"); el.clearAttributes(); el.attr("l:href","#$id") } }
                doc.select("h1,h2,h3,h4,h5,h6").forEach { it.tagName("subtitle") }; doc.select("em,i").forEach { it.tagName("emphasis") }; doc.select("b").forEach { it.tagName("strong") }; doc.select("blockquote").forEach { it.tagName("cite") }; doc.select("li").forEach { it.tagName("p") }; doc.select("ul,ol,div,span").forEach { it.unwrap() }
                doc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml)
                "<section><title><p>${x(ch.str("title"))}</p></title>${doc.body().html()}</section>"
            }
            val authors=b.optJSONArray("authors")?.strings().orEmpty().joinToString("") { "<author><nickname>${x(it)}</nickname></author>" }
            val genres=b.optJSONArray("genres")?.strings().orEmpty().ifEmpty { listOf("prose_contemporary") }.joinToString("") { "<genre>${x(it)}</genre>" }
            val binaries=blobs.entries.joinToString("") { (key,v) -> "<binary id=\"${imageIds[key]}\" content-type=\"${x(v.mime)}\">${Base64.encodeToString(v.bytes,Base64.NO_WRAP)}</binary>" }+(cover?.let { "<binary id=\"cover\" content-type=\"${x(it.mime)}\">${Base64.encodeToString(it.bytes,Base64.NO_WRAP)}</binary>" } ?: "")
            ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\" xmlns:l=\"http://www.w3.org/1999/xlink\"><description><title-info>$genres$authors<book-title>${x(b.str("title"))}</book-title><annotation><p>${x(b.str("annotation"))}</p></annotation>"+(if(cover!=null) "<coverpage><image l:href=\"#cover\"/></coverpage>" else "")+"<lang>${x(b.str("lang","ru"))}</lang>"+(if(b.str("series").isNotBlank()) "<sequence name=\"${x(b.str("series"))}\" number=\"${b.optDouble("seriesNo",0.0)}\"/>" else "")+"</title-info><document-info><author><nickname>Полка</nickname></author><id>${x(b.str("id"))}</id><version>1.0</version></document-info></description><body>$body</body>$binaries</FictionBook>").toByteArray()
        } else {
            val out=ByteArrayOutputStream(); ZipOutputStream(out).use { z ->
                fun entry(name:String,bytes:ByteArray,stored:Boolean=false) { val e=ZipEntry(name); if(stored) { e.method=ZipEntry.STORED; e.size=bytes.size.toLong(); e.compressedSize=e.size; e.crc=CRC32().apply { update(bytes) }.value }; z.putNextEntry(e); z.write(bytes); z.closeEntry() }
                fun text(name:String,s:String)=entry(name,s.toByteArray())
                entry("mimetype","application/epub+zip".toByteArray(),true)
                text("META-INF/container.xml","<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"OEBPS/package.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>")
                val ids=blobs.entries.mapIndexed { i,e -> e.key to "images/$i.${when(e.value.mime) { "image/png"->"png"; "image/svg+xml"->"svg"; else->"jpg" }}" }.toMap()
                blobs.forEach { (key,v)->entry("OEBPS/${ids[key]}",v.bytes) }; cover?.let { entry("OEBPS/images/cover",it.bytes) }
                val pathToChapter=chapters.mapIndexed { i,ch -> ch.str("path") to i }.filter { it.first.isNotBlank() }.toMap()
                fun link(value:String,current:Int):String { if(value.contains(":")) return value; val path=value.substringBefore('#'); val anchor=value.substringAfter('#',""); val index=if(path.isBlank()) current else pathToChapter[path] ?: return value; return "ch$index.xhtml"+if(anchor.isNotBlank()) "#$anchor" else "" }
                chapters.forEachIndexed { i,ch -> val doc=Jsoup.parse(ch.str("html")); doc.select("script,style").remove(); doc.select("a[href],a[data-href]").forEach { el -> val value=el.attr("data-href").ifBlank { el.attr("href") }; el.attr("href",link(value,i)); el.removeAttr("data-href") }; doc.select("img").forEach { el -> val key=el.attr("data-src").ifBlank { el.attr("src") }; ids[key]?.let { el.attr("src",it); el.removeAttr("data-src") } }; doc.outputSettings().syntax(org.jsoup.nodes.Document.OutputSettings.Syntax.xml); text("OEBPS/ch$i.xhtml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>${x(ch.str("title"))}</title></head><body>${doc.body().html()}</body></html>") }
                val toc=content.optJSONArray("toc")?.objects().orEmpty().ifEmpty { chapters.mapIndexed { i,ch -> ch.copyJson().put("ch",i) } }
                val nav=StringBuilder("<ol>"); var depth=0; var opened=false
                toc.forEach { entry -> val next=entry.optInt("depth").coerceIn(0,depth+1)
                    if(opened) { when { next>depth -> nav.append("<ol>"); next==depth -> nav.append("</li>"); else -> { nav.append("</li>"); repeat(depth-next) { nav.append("</ol></li>") } } } }
                    nav.append("<li><a href=\"ch${entry.optInt("ch").coerceIn(chapters.indices)}.xhtml${if(entry.str("anchor").isNotBlank()) "#"+x(entry.str("anchor")) else ""}\">${x(entry.str("title"))}</a>"); depth=if(opened) next else 0; opened=true
                }
                if(opened) nav.append("</li>"); repeat(depth) { nav.append("</ol></li>") }; nav.append("</ol>")
                text("OEBPS/nav.xhtml","<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\"><head><title>Оглавление</title></head><body><nav epub:type=\"toc\">$nav</nav></body></html>")
                text("OEBPS/package.opf","<?xml version=\"1.0\" encoding=\"UTF-8\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:identifier id=\"uid\">${x(b.str("id"))}</dc:identifier><dc:title>${x(b.str("title"))}</dc:title>${b.optJSONArray("authors")?.strings().orEmpty().joinToString("") { "<dc:creator>${x(it)}</dc:creator>" }}<dc:language>${x(b.str("lang","ru"))}</dc:language><dc:description>${x(b.str("annotation"))}</dc:description>${b.optJSONArray("genres")?.strings().orEmpty().joinToString("") { "<dc:subject>${x(it)}</dc:subject>" }}${if(b.str("series").isNotBlank()) "<meta property=\"belongs-to-collection\" id=\"series\">${x(b.str("series"))}</meta><meta refines=\"#series\" property=\"collection-type\">series</meta><meta refines=\"#series\" property=\"group-position\">${x(b.str("seriesNo"))}</meta>" else ""}<meta property=\"dcterms:modified\">${java.time.Instant.now().toString().substringBefore('.')}Z</meta></metadata><manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>${chapters.mapIndexed { i,_ -> "<item id=\"ch$i\" href=\"ch$i.xhtml\" media-type=\"application/xhtml+xml\"/>" }.joinToString("")}${blobs.entries.mapIndexed { i,e -> "<item id=\"im$i\" href=\"${ids[e.key]}\" media-type=\"${x(e.value.mime)}\"/>" }.joinToString("")}${cover?.let { "<item id=\"cover\" href=\"images/cover\" media-type=\"${x(it.mime)}\" properties=\"cover-image\"/>" } ?: ""}</manifest><spine>${chapters.indices.joinToString("") { "<itemref idref=\"ch$it\"/>" }}</spine></package>")
            }; out.toByteArray()
        }
    }
}
