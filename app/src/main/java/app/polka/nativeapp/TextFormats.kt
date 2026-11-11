package app.polka.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipInputStream

/**
 * Text books in other formats, turned into the same chapters of plain HTML the reader uses for FB2 and EPUB:
 * TXT, HTML, DOCX, RTF and Kindle MOBI / AZW / AZW3 (without DRM).
 */
internal object TextFormats {
    fun kind(name:String,head:ByteArray):String?{
        val ext=name.substringAfterLast('.',"").lowercase()
        if(head.size>=68&&String(head,60,8,Charsets.ISO_8859_1).let{it=="BOOKMOBI"||it=="TEXtREAd"})return "mobi"
        if(head.size>=5&&String(head,0,5,Charsets.ISO_8859_1)=="{\\rtf")return "rtf"
        return when(ext){"txt","text"->"txt";"htm","html","xhtml"->"html";"docx"->"docx";"rtf"->"rtf";"mobi","azw","azw3","prc","pdb","kf8"->"mobi";else->null}
    }
    fun parse(kind:String,bytes:ByteArray,name:String):Imported=when(kind){
        "txt"->txt(bytes,name);"html"->html(bytes,name);"docx"->docx(bytes,name);"rtf"->rtf(bytes,name);else->Mobi(bytes,name).read()}

    // ── shared ──
    private fun newBook(name:String,format:String)=JSONObject().put("id",uid()).put("title",name.substringBeforeLast('.').replace('_',' ').trim()).put("authors",JSONArray()).put("genres",JSONArray()).put("format",format).put("fileName",name)
        .put("addedAt",System.currentTimeMillis()).put("openedAt",0).put("progress",JSONObject().put("ch",0).put("block",0).put("off",0).put("pct",0)).put("shelf","").put("quotes",JSONArray()).put("bookmarks",JSONArray()).put("notes",JSONArray())
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
    private fun chapter(title:String,html:String)=JSONObject().put("title",title.ifBlank{"Начало"}).put("html",html).put("len",Jsoup.parse(html).text().length).put("depth",0)
    private class Draft(val title:String,val html:StringBuilder=StringBuilder())
    /** Puts the pieces together the way the reader expects them (chapters, a table of contents, images, the source). */
    private fun assemble(book:JSONObject,drafts:List<Draft>,images:JSONObject,assets:MutableList<AssetRow>,source:ByteArray,mime:String):Imported{
        val chapters=JSONArray();drafts.forEachIndexed{i,d->val h=d.html.toString();if(Jsoup.parse(h).text().isNotBlank()||h.contains("<img"))chapters.put(chapter(d.title.ifBlank{if(i==0)"Начало" else "Глава ${chapters.length()+1}"},h))}
        require(chapters.length()>0){"В книге нет текста"}
        val src=AssetRow(uid(),mime,source);assets.add(src)
        book.put("size",source.size).put("chars",chapters.objects().sumOf{it.optInt("len")})
        val content=JSONObject().put("id",book.getString("id")).put("chapters",chapters).put("images",images).put("source",JSONObject().put("__polkaBlob",src.id))
            .put("toc",JSONArray(chapters.objects().mapIndexed{i,c->JSONObject().put("title",c.str("title")).put("ch",i).put("anchor","").put("depth",0)}))
        return Imported(book,content,assets)}
    /** Splits text-only paragraphs into chapters at heading-like lines, or into parts of a comfortable length. */
    private fun chaptersFromParagraphs(paras:List<String>):List<Draft>{
        val heading=Regex("^(глава|часть|книга|пролог|эпилог|послесловие|предисловие|chapter|part|prologue|epilogue)\\b.{0,60}$|^[IVXLC]{1,6}\\.?$|^\\d{1,3}\\.?$",RegexOption.IGNORE_CASE)
        val out=mutableListOf<Draft>();var cur=Draft("")
        val headings=paras.count{it.length<70&&heading.matches(it.trim())}
        paras.forEach{p->val t=p.trim();if(t.isEmpty())return@forEach
            if(headings>=2&&t.length<70&&heading.matches(t)){if(cur.html.isNotEmpty()||out.isEmpty()&&cur.title.isNotEmpty())out.add(cur);cur=Draft(t);cur.html.append("<h2>").append(esc(t)).append("</h2>");return@forEach}
            if(headings<2&&cur.html.length>60_000){out.add(cur);cur=Draft("Часть ${out.size+1}")}
            cur.html.append("<p>").append(esc(t)).append("</p>")}
        out.add(cur);return out}
    /** UTF-8 when the bytes are valid UTF-8 (or carry a BOM), otherwise Windows-1251 (what Russian TXT/RTF use). */
    fun decodeText(b:ByteArray):String{
        if(b.size>=2&&(b[0]==0xFF.toByte()&&b[1]==0xFE.toByte()))return String(b,2,b.size-2,Charsets.UTF_16LE)
        if(b.size>=2&&(b[0]==0xFE.toByte()&&b[1]==0xFF.toByte()))return String(b,2,b.size-2,Charsets.UTF_16BE)
        val start=if(b.size>=3&&b[0]==0xEF.toByte()&&b[1]==0xBB.toByte()&&b[2]==0xBF.toByte())3 else 0
        return try{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b,start,b.size-start)).toString()}
            catch(_:Exception){String(b,Charset.forName("windows-1251"))}}

    // ── TXT ──
    private fun txt(bytes:ByteArray,name:String):Imported{
        val text=decodeText(bytes).replace("\r\n","\n").replace('\r','\n')
        val blank=Regex("\n[ \t]*\n").findAll(text).count();val lines=text.count{it=='\n'}
        // books with blank lines between paragraphs keep their wrapped lines together; others have a paragraph per line
        val paras=if(blank>lines/6)text.split(Regex("\n[ \t]*\n")).map{it.replace(Regex("\\s*\n\\s*")," ")} else text.split('\n')
        return assemble(newBook(name,"txt"),chaptersFromParagraphs(paras),JSONObject(),mutableListOf(),bytes,"text/plain")}

    // ── HTML ──
    private fun clean(root:Element){root.select("script,style,iframe,object,embed,form,input,button,nav,link,meta").remove()
        root.getAllElements().forEach{el->el.attributes().asList().filter{it.key.startsWith("on",true)||it.key in listOf("style","srcset","contenteditable","class")||it.value.trimStart().startsWith("javascript:",true)}.forEach{el.removeAttr(it.key)}}}
    /** Chapters at the top-level headings: h1 when there are several, else h2. */
    private fun splitHtml(body:Element):List<Draft>{
        val tag=if(body.select("h1").size>=2)"h1" else if(body.select("h2").size>=2)"h2" else null
        val nodes=body.childNodes().toList()
        if(tag==null)return listOf(Draft("",StringBuilder(body.html())))
        val out=mutableListOf<Draft>();var cur=Draft("")
        nodes.forEach{n->val el=n as? Element
            if(el!=null&&(el.tagName()==tag||el.selectFirst(tag)!=null&&el.text().length<120)){if(cur.html.isNotBlank())out.add(cur);cur=Draft(el.text())}
            cur.html.append(n.outerHtml())}
        out.add(cur);return out}
    private fun html(bytes:ByteArray,name:String):Imported{
        val head=String(bytes,0,minOf(bytes.size,2000),Charsets.ISO_8859_1)
        val cs=Regex("charset=[\"']?([\\w-]+)",RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        val text=cs?.let{try{String(bytes,Charset.forName(it))}catch(_:Exception){null}}?:decodeText(bytes)
        val doc=Jsoup.parse(text);clean(doc.body())
        val book=newBook(name,"html");doc.title().takeIf{it.isNotBlank()}?.let{book.put("title",it)}
        doc.selectFirst("meta[name=author]")?.attr("content")?.takeIf{it.isNotBlank()}?.let{book.put("authors",JSONArray(listOf(it)))}
        doc.body().select("img").forEach{if(!it.attr("src").startsWith("data:"))it.remove()}
        // inline images (data: URLs) become stored images like in any book
        val images=JSONObject();val assets=mutableListOf<AssetRow>()
        doc.body().select("img").forEachIndexed{i,img->val src=img.attr("src");val mime=src.substringAfter("data:").substringBefore(';');val data=try{java.util.Base64.getDecoder().decode(src.substringAfter("base64,"))}catch(_:Exception){null}
            if(data==null){img.remove();return@forEachIndexed};val a=AssetRow(uid(),mime.ifBlank{"image/jpeg"},data);assets.add(a);images.put("img/$i",JSONObject().put("__polkaBlob",a.id));img.attr("src","img/$i")}
        return assemble(book,splitHtml(doc.body()),images,assets,bytes,"text/html")}

    // ── DOCX ──
    private fun docx(bytes:ByteArray,name:String):Imported{
        val files=HashMap<String,ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use{z->while(true){val e=z.nextEntry?:break;if(!e.isDirectory&&(e.name.startsWith("word/")||e.name.startsWith("docProps/")))files[e.name]=z.readBytes()}}
        val doc=Jsoup.parse(String(files["word/document.xml"]?:error("Это не документ Word (DOCX)"),Charsets.UTF_8),"",Parser.xmlParser())
        val rels=files["word/_rels/document.xml.rels"]?.let{Jsoup.parse(String(it,Charsets.UTF_8),"",Parser.xmlParser()).select("Relationship").associate{r->r.attr("Id") to r.attr("Target")}}.orEmpty()
        val book=newBook(name,"docx")
        files["docProps/core.xml"]?.let{core->val c=Jsoup.parse(String(core,Charsets.UTF_8),"",Parser.xmlParser())
            c.selectFirst("dc|title")?.text()?.takeIf{it.isNotBlank()}?.let{book.put("title",it)};c.selectFirst("dc|creator")?.text()?.takeIf{it.isNotBlank()}?.let{book.put("authors",JSONArray(listOf(it)))}}
        val images=JSONObject();val assets=mutableListOf<AssetRow>();val drafts=mutableListOf<Draft>();var cur=Draft("")
        fun image(id:String):String?{val target=rels[id]?:return null;val path="word/"+target.removePrefix("/word/").removePrefix("./");val data=files[path]?:return null
            if(!images.has(path)){val mime=when(path.substringAfterLast('.').lowercase()){"png"->"image/png";"gif"->"image/gif";else->"image/jpeg"};val a=AssetRow(uid(),mime,data);assets.add(a);images.put(path,JSONObject().put("__polkaBlob",a.id))};return path}
        fun local(e:Element)=e.tagName().substringAfter(':')
        val body=doc.getElementsByTag("w:body").firstOrNull()?:error("Пустой документ")
        body.getAllElements().filter{local(it)=="p"&&it.parents().none{p->local(p)=="p"}}.forEach{p->
            val style=p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty().lowercase()
            val level=when{style=="title"->1;style.startsWith("heading")->style.removePrefix("heading").toIntOrNull()?:1;style.matches(Regex("\\d"))->style.toInt();style.startsWith("заголовок")->1;else->0}
            val sb=StringBuilder()
            p.getAllElements().filter{local(it)=="r"}.forEach{r->
                val pr=r.getElementsByTag("w:rPr").firstOrNull();val b=pr?.getElementsByTag("w:b")?.isNotEmpty()==true;val it=pr?.getElementsByTag("w:i")?.isNotEmpty()==true
                val t=StringBuilder();r.children().forEach{c->when(local(c)){"t"->t.append(esc(c.text()));"tab"->t.append(" ");"br"->t.append("<br>")
                    "drawing","pict"->c.getAllElements().firstOrNull{x->x.hasAttr("r:embed")||x.hasAttr("r:id")}?.let{x->image(x.attr("r:embed").ifBlank{x.attr("r:id")})}?.let{path->t.append("<img src=\"").append(path).append("\">")}}}
                var s=t.toString();if(b)s="<strong>$s</strong>";if(it)s="<em>$s</em>";sb.append(s)}
            val inner=sb.toString();if(Jsoup.parse(inner).text().isBlank()&&!inner.contains("<img"))return@forEach
            if(level in 1..2){if(cur.html.isNotBlank())drafts.add(cur);cur=Draft(Jsoup.parse(inner).text());cur.html.append("<h2>").append(inner).append("</h2>")}
            else if(level>=3)cur.html.append("<h3>").append(inner).append("</h3>") else cur.html.append("<p>").append(inner).append("</p>")
            if(level==0&&drafts.isEmpty()&&cur.html.length>60_000&&cur.title.isEmpty()){drafts.add(cur);cur=Draft("Часть ${drafts.size+1}")}}
        drafts.add(cur)
        return assemble(book,drafts,images,assets,bytes,"application/vnd.openxmlformats-officedocument.wordprocessingml.document")}

    // ── RTF ──
    private fun rtf(bytes:ByteArray,name:String):Imported{
        val s=String(bytes,Charsets.ISO_8859_1);var i=0
        var cs=Charset.forName(Regex("\\\\ansicpg(\\d+)").find(s)?.groupValues?.get(1)?.let{"windows-$it"}?.takeIf{try{Charset.forName(it);true}catch(_:Exception){false}}?:"windows-1251")
        val paras=mutableListOf<String>();val cur=StringBuilder();val pending=java.io.ByteArrayOutputStream()
        var title="";var author=""
        data class G(var skip:Boolean,var uc:Int,var dest:String)
        val stack=ArrayDeque<G>();var g=G(false,1,"");var skipChars=0
        fun flushBytes(){if(pending.size()>0){val t=String(pending.toByteArray(),cs);pending.reset();when(g.dest){"title"->title+=t;"author"->author+=t;else->if(!g.skip)cur.append(t)}}}
        fun put(ch:Char){if(skipChars>0){skipChars--;return};flushBytes();when(g.dest){"title"->title+=ch;"author"->author+=ch;else->if(!g.skip)cur.append(ch)}}
        fun para(){flushBytes();if(!g.skip&&g.dest==""){paras.add(cur.toString());cur.setLength(0)}}
        val skipDest=setOf("fonttbl","colortbl","stylesheet","info","pict","header","footer","headerl","headerr","footerl","footerr","object","themedata","colorschememapping","latentstyles","datastore","xmlnstbl","listtable","listoverridetable","rsidtbl","generator","fldinst","shppict","nonshppict","bkmkstart","bkmkend")
        while(i<s.length){val c=s[i]
            when(c){
                '{'->{flushBytes();stack.addLast(g.copy());i++}
                '}'->{flushBytes();g=stack.removeLastOrNull()?:G(false,1,"");i++}
                '\\'->{i++;if(i>=s.length)break;val n=s[i]
                    when{n=='\''->{val hex=s.substring(i+1,minOf(i+3,s.length));i+=3;if(skipChars>0)skipChars-- else hex.toIntOrNull(16)?.let{pending.write(it)}}
                        n=='*'->{g.skip=true;i++}
                        n=='\\'||n=='{'||n=='}'->{put(n);i++}
                        n=='~'->{put('\u00a0');i++}
                        n=='-'||n=='_'->{i++}
                        n=='\n'||n=='\r'->{para();i++}
                        n.isLetter()->{val st=i;while(i<s.length&&s[i].isLetter())i++;val word=s.substring(st,i);val ns=i;if(i<s.length&&(s[i]=='-'||s[i].isDigit())){i++;while(i<s.length&&s[i].isDigit())i++};val num=s.substring(ns,i).toIntOrNull();if(i<s.length&&s[i]==' ')i++
                            when(word){"par","sect"->para();"line"->put('\n');"tab"->put(' ');"emdash"->put('—');"endash"->put('–');"lquote"->put('‘');"rquote"->put('’');"ldblquote"->put('«');"rdblquote"->put('»');"bullet"->put('•')
                                "u"->{num?.let{v->flushBytes();if(skipChars==0)put((if(v<0)v+65536 else v).toChar()) else skipChars--;skipChars=g.uc}}
                                "uc"->g.uc=num?:1
                                "ansicpg"->num?.let{try{cs=Charset.forName("windows-$it")}catch(_:Exception){}}
                                "title"->g.dest="title";"author"->g.dest="author"
                                else->if(word in skipDest){if(word!="info")g.skip=true else g.skip=true}}
                            if(word=="title"||word=="author")g.skip=false}
                        else->i++}}
                '\r','\n'->i++
                else->{if(skipChars>0){skipChars--;i++}else{flushBytes();put(c);i++}}}}
        para()
        val book=newBook(name,"rtf");title.trim().takeIf{it.isNotBlank()}?.let{book.put("title",it)};author.trim().takeIf{it.isNotBlank()}?.let{book.put("authors",JSONArray(listOf(it)))}
        return assemble(book,chaptersFromParagraphs(paras.map{it.replace('\n',' ')}),JSONObject(),mutableListOf(),bytes,"application/rtf")}

    // ── MOBI / AZW / AZW3 (PalmDB with PalmDOC or HUFF/CDIC compression) ──
    private class Mobi(val b:ByteArray,val name:String){
        fun u16(o:Int)=((b[o].toInt() and 255) shl 8) or (b[o+1].toInt() and 255)
        fun u32(o:Int)=((b[o].toInt() and 255) shl 24) or ((b[o+1].toInt() and 255) shl 16) or ((b[o+2].toInt() and 255) shl 8) or (b[o+3].toInt() and 255)
        val count=u16(76);val offs=IntArray(count){u32(78+it*8)}
        fun rec(i:Int):ByteArray{val s=offs[i];val e=if(i+1<count)offs[i+1] else b.size;return b.copyOfRange(s,e)}
        fun read():Imported{
            require(count>1){"Файл MOBI повреждён"}
            val r0=offs[0];val compression=u16(r0);val textRecords=u16(r0+8);val encryption=u16(r0+12)
            require(encryption==0){"Книга защищена (DRM) — такие файлы открываются только в Kindle"}
            val mobi=String(b,r0+16,4,Charsets.ISO_8859_1)=="MOBI";val hlen=if(mobi)u32(r0+20) else 0
            val encoding=if(mobi)u32(r0+28) else 1252;val version=if(mobi)u32(r0+36) else 0
            val firstImage=if(mobi&&hlen>=0x5C)u32(r0+0x6C) else -1
            val extraFlags=if(mobi&&hlen>=0xE4)u16(r0+0xF2) else 0
            var title=if(mobi)String(b,r0+u32(r0+0x54),u32(r0+0x58),if(encoding==65001)Charsets.UTF_8 else Charset.forName("windows-1252")) else name.substringBeforeLast('.')
            val authors=mutableListOf<String>();var coverOff=-1
            if(mobi&&(u32(r0+0x80) and 0x40)!=0){val ex=r0+16+hlen;if(String(b,ex,4,Charsets.ISO_8859_1)=="EXTH"){val n=u32(ex+8);var p=ex+12
                repeat(n){val type=u32(p);val len=u32(p+4);val data=b.copyOfRange(p+8,p+len);val str={String(data,if(encoding==65001)Charsets.UTF_8 else Charset.forName("windows-1252"))}
                    when(type){100->authors.add(str().trim());503->title=str().trim();201->coverOff=if(data.size>=4)((data[0].toInt() and 255) shl 24) or ((data[1].toInt() and 255) shl 16) or ((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255) else -1};p+=len}}}
            val huff=if(compression==17480)Huff(this,u32(r0+0x70),u32(r0+0x74)) else null
            val out=java.io.ByteArrayOutputStream()
            for(i in 1..textRecords){if(i>=count)break;var data=rec(i);data=data.copyOfRange(0,(data.size-trailing(data,extraFlags)).coerceAtLeast(0))
                out.write(when(compression){1->data;2->palmdoc(data);17480->huff!!.unpack(data);else->error("Неизвестное сжатие MOBI")})}
            val text=String(out.toByteArray(),if(encoding==65001)Charsets.UTF_8 else Charset.forName("windows-1252"))
            val book=newBook(name,if(version>=8)"azw3" else "mobi").put("title",title.ifBlank{name.substringBeforeLast('.')}).put("authors",JSONArray(authors.filter{it.isNotBlank()}))
            val images=JSONObject();val assets=mutableListOf<AssetRow>()
            fun imageAt(n:Int):String?{if(firstImage<0)return null;val idx=firstImage+n;if(idx<0||idx>=count)return null;val key="rec/$idx";if(images.has(key))return key
                val data=rec(idx);val mime=when{data.size>3&&data[0]==0xFF.toByte()&&data[1]==0xD8.toByte()->"image/jpeg";data.size>3&&data[1]=='P'.code.toByte()&&data[2]=='N'.code.toByte()->"image/png";data.size>3&&data[0]=='G'.code.toByte()->"image/gif";else->return null}
                val a=AssetRow(uid(),mime,data);assets.add(a);images.put(key,JSONObject().put("__polkaBlob",a.id));return key}
            if(coverOff>=0)imageAt(coverOff)?.let{book.put("cover",images.get(it))}
            // pieces: KF8 keeps each file as its own <html>, older MOBI breaks pages with <mbp:pagebreak/>
            val pieces=if(version>=8)text.split(Regex("(?=<\\?xml|<html[\\s>])",RegexOption.IGNORE_CASE)).filter{it.isNotBlank()} else text.split(Regex("<mbp:pagebreak\\s*/?>",RegexOption.IGNORE_CASE))
            val drafts=pieces.mapNotNull{piece->val doc=Jsoup.parse(piece);clean(doc.body())
                doc.body().select("img").forEach{img->val rec=img.attr("recindex").toIntOrNull();val kind=Regex("kindle:embed:([0-9A-V]{4})").find(img.attr("src"))?.groupValues?.get(1)
                    val key=when{rec!=null->imageAt(rec-1);kind!=null->imageAt(kind.toInt(32)-1);else->null};if(key==null)img.remove() else{img.attributes().asList().map{it.key}.forEach{k->img.removeAttr(k)};img.attr("src",key)}}
                doc.body().select("a[filepos]").forEach{it.removeAttr("filepos")}
                if(doc.body().text().isBlank()&&doc.body().select("img").isEmpty())null else Draft(doc.body().selectFirst("h1,h2,h3")?.text()?.take(80).orEmpty(),StringBuilder(doc.body().html()))}
            // a long book without breaks still gets chapters at its headings
            val merged=if(drafts.size<=1)drafts.flatMap{splitHtml(Jsoup.parse(it.html.toString()).body())} else drafts
            return assemble(book,merged,images,assets,b,"application/x-mobipocket-ebook")}
        /** Size of the trailing entries at the end of a text record (multibyte flag and the variable-length extras). */
        fun trailing(d:ByteArray,flags:Int):Int{var size=0;var f=flags shr 1
            while(f!=0){if(f and 1!=0){var v=0;var k=0;var pos=d.size-size-1;while(pos>=0&&k<4){val x=d[pos].toInt() and 255;v=v or ((x and 0x7F) shl (7*k));k++;pos--;if(x and 0x80!=0)break};size+=v};f=f shr 1}
            if(flags and 1!=0&&d.size-size-1>=0)size+=(d[d.size-size-1].toInt() and 3)+1
            return size}
        fun palmdoc(d:ByteArray):ByteArray{val o=java.io.ByteArrayOutputStream(d.size*2);var i=0
            val outArr=ByteArray(8192*2);var n=0
            fun put(x:Int){if(n<outArr.size){outArr[n++]=x.toByte()}}
            while(i<d.size){val c=d[i++].toInt() and 255
                when{c==0||c in 9..0x7F->put(c)
                    c in 1..8->{repeat(c){if(i<d.size)put(d[i++].toInt() and 255)}}
                    c in 0x80..0xBF->{if(i>=d.size)break;val x=(c shl 8) or (d[i++].toInt() and 255);val dist=(x shr 3) and 0x7FF;val len=(x and 7)+3;repeat(len){if(n-dist>=0)put(outArr[n-dist].toInt() and 255)}}
                    else->{put(' '.code);put(c xor 0x80)}}}
            o.write(outArr,0,n);return o.toByteArray()}
    }
    /** HUFF/CDIC decompression (used by some Amazon MOBI files). */
    private class Huff(m:Mobi,huffRec:Int,huffCount:Int){
        val dict1=LongArray(256);val mincode=LongArray(33);val maxcode=LongArray(33);val dict=ArrayList<Pair<ByteArray,Boolean>>()
        init{val h=m.rec(huffRec);fun u32(a:ByteArray,o:Int)=(((a[o].toLong() and 255) shl 24) or ((a[o+1].toLong() and 255) shl 16) or ((a[o+2].toLong() and 255) shl 8) or (a[o+3].toLong() and 255))
            val o1=u32(h,8).toInt();val o2=u32(h,12).toInt()
            for(i in 0 until 256)dict1[i]=u32(h,o1+i*4)
            for(i in 0 until 64){val v=u32(h,o2+i*4);if(i%2==0)mincode[i/2+1]=v else maxcode[i/2+1]=v}
            for(k in 1..32){mincode[k]=mincode[k] shl (32-k);maxcode[k]=((maxcode[k]+1) shl (32-k))-1}
            for(r in 1 until huffCount){val c=m.rec(huffRec+r);val phrases=u32(c,8).toInt();val bits=u32(c,12).toInt();val n=minOf(1 shl bits,phrases-dict.size)
                for(j in 0 until n){val off=((c[16+j*2].toInt() and 255) shl 8) or (c[17+j*2].toInt() and 255);val blen=((c[16+off].toInt() and 255) shl 8) or (c[17+off].toInt() and 255)
                    dict.add(c.copyOfRange(18+off,18+off+(blen and 0x7FFF)) to (blen and 0x8000!=0))}}}
        fun unpack(d:ByteArray,depth:Int=0):ByteArray{val out=java.io.ByteArrayOutputStream();var bitsleft=d.size*8L
            val data=d+ByteArray(8);var pos=0;var x=0L;var n=32
            fun load(){x=0;for(k in 0 until 8)x=(x shl 8) or (data[pos+k].toLong() and 255)}
            load()
            while(true){if(n<=0){pos+=4;load();n+=32}
                val code=(x ushr n) and 0xFFFFFFFFL
                val v=dict1[(code ushr 24).toInt()];var codelen=(v and 0x1F).toInt();var maxc=(((v ushr 8)+1) shl (32-codelen))-1
                if(v and 0x80==0L){while(code<mincode[codelen])codelen++;maxc=maxcode[codelen]}
                n-=codelen;bitsleft-=codelen;if(bitsleft<0)break
                val r=((maxc-code) ushr (32-codelen)).toInt()
                val (slice,flag)=dict[r];if(!flag&&depth<16){val un=unpack(slice,depth+1);dict[r]=un to true;out.write(un)}else out.write(slice)}
            return out.toByteArray()}
    }
}
