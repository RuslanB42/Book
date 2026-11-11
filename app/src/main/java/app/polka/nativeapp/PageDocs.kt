package app.polka.nativeapp

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Books made of pages rather than text: comics (CBZ, CBR, CB7, CBT, or a ZIP of pictures) and PDF. Their pages live
 * in the app's own folder (files/docs/<id>): a comic's pictures unpacked in reading order, a PDF as the file itself.
 * The book keeps {"doc":{"id","type":"images"|"pdf","count"}} and «comic» for comics, whose covers are drawn as a
 * glossy issue (approved «Б2»).
 */
internal object PageDocs {
    private val IMAGE=Regex(".*\\.(jpe?g|png|webp|gif|bmp)$",RegexOption.IGNORE_CASE)
    fun isPagesName(name:String)=name.substringAfterLast('.',"").lowercase() in setOf("cbz","cbr","cb7","cbt","pdf")
    fun isDoc(book:JSONObject)=book.optJSONObject("doc")!=null
    fun dir(c:android.content.Context,book:JSONObject)=File(c.filesDir,"docs/"+book.optJSONObject("doc")?.str("id"))
    fun drop(c:android.content.Context,book:JSONObject){if(isDoc(book))dir(c,book).deleteRecursively()}
    /** A ZIP holds a comic when it has pictures but no FB2 and no EPUB package. */
    fun zipIsComic(bytes:ByteArray):Boolean{var images=0;var book=false
        try{java.util.zip.ZipInputStream(bytes.inputStream()).use{z->while(true){val e=z.nextEntry?:break;val n=e.name.lowercase();if(n.endsWith(".fb2")||n.endsWith(".opf")||n.endsWith("container.xml"))book=true;if(IMAGE.matches(n))images++}}}catch(_:Exception){return false}
        return !book&&images>0}

    /** Natural order: «page 2» before «page 10». */
    private val natural=Comparator<String>{x,y->val a=Regex("\\d+|\\D+").findAll(x.lowercase()).map{it.value}.toList();val b=Regex("\\d+|\\D+").findAll(y.lowercase()).map{it.value}.toList()
        for(i in 0 until minOf(a.size,b.size)){val p=a[i];val q=b[i];val c=if(p[0].isDigit()&&q[0].isDigit())(p.trimStart('0').length.compareTo(q.trimStart('0').length)).takeIf{it!=0}?:p.trimStart('0').compareTo(q.trimStart('0')) else p.compareTo(q);if(c!=0)return@Comparator c}
        a.size.compareTo(b.size)}

    /** Imports a picked file of pages (worker thread). [bytes]: already read, for a ZIP found to be a comic. */
    fun import(a:MainActivity,uri:Uri?,bytes:ByteArray?,name:String):Imported{
        val id=uid();val dir=File(a.filesDir,"docs/$id").apply{mkdirs()}
        try{
            val src=File(dir,"src")
            if(bytes!=null)src.writeBytes(bytes) else (a.contentResolver.openInputStream(uri!!)?:error("Файл недоступен")).use{inp->src.outputStream().use{out->val buf=ByteArray(65536);var total=0L
                while(true){val n=inp.read(buf);if(n<0)break;total+=n;require(total<=1536L*1024*1024){"Файл больше 1,5 ГБ"};out.write(buf,0,n)}}}
            val head=ByteArray(512).also{arr->src.inputStream().use{it.read(arr)}}
            val magic=String(head,0,8,Charsets.ISO_8859_1)
            return when{
                magic.startsWith("%PDF")->pdf(a,id,dir,src,name)
                magic.startsWith("PK")->images(a,id,dir,src,name,"cbz"){emit->java.util.zip.ZipFile(src).use{z->z.entries().toList().filter{!it.isDirectory}.forEach{e->emit(e.name){z.getInputStream(e)}}}}
                magic.startsWith("Rar!")->images(a,id,dir,src,name,"cbr"){emit->
                    try{com.github.junrar.Archive(src).use{arc->arc.fileHeaders.filter{!it.isDirectory}.forEach{h->emit(h.fileName.replace('\\','/')){val o=java.io.ByteArrayOutputStream();arc.extractFile(h,o);o.toByteArray().inputStream()}}}}
                    catch(e:com.github.junrar.exception.UnsupportedRarV5Exception){throw IllegalArgumentException("Архив RAR5 пока не поддерживается — пересохраните комикс как CBZ")}}
                head[0]=='7'.code.toByte()&&head[1]=='z'.code.toByte()->images(a,id,dir,src,name,"cb7"){emit->
                    @Suppress("DEPRECATION") org.apache.commons.compress.archivers.sevenz.SevenZFile(src).use{z->while(true){val e=z.nextEntry?:break;if(e.isDirectory)continue
                        val o=java.io.ByteArrayOutputStream();val buf=ByteArray(65536);while(true){val n=z.read(buf);if(n<0)break;o.write(buf,0,n)};emit(e.name){o.toByteArray().inputStream()}}}}
                String(head,257,5,Charsets.ISO_8859_1)=="ustar"->images(a,id,dir,src,name,"cbt"){emit->
                    org.apache.commons.compress.archivers.tar.TarArchiveInputStream(src.inputStream().buffered()).use{t->while(true){val e=t.nextEntry?:break;if(e.isDirectory)continue;val data=t.readBytes();emit(e.name){data.inputStream()}}}}
                else->error("Неизвестный формат файла")}
        }catch(e:Throwable){dir.deleteRecursively();throw if(e is SecurityException)IllegalArgumentException("PDF защищён паролем") else e}
    }
    private fun newBook(id:String,name:String,format:String)=JSONObject().put("id",uid()).put("title",name.substringBeforeLast('.').replace('_',' ').trim()).put("authors",JSONArray()).put("genres",JSONArray()).put("format",format).put("fileName",name)
        .put("addedAt",System.currentTimeMillis()).put("openedAt",0).put("progress",JSONObject().put("ch",0).put("page",0).put("pct",0)).put("shelf","").put("quotes",JSONArray()).put("bookmarks",JSONArray()).put("notes",JSONArray())
    private fun coverAsset(bmp:Bitmap):AssetRow{val o=java.io.ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.JPEG,88,o);return AssetRow(uid(),"image/jpeg",o.toByteArray())}
    private fun content(book:JSONObject)=JSONObject().put("id",book.getString("id")).put("chapters",JSONArray()).put("images",JSONObject()).put("toc",JSONArray())

    private fun pdf(a:MainActivity,id:String,dir:File,src:File,name:String):Imported{
        val file=File(dir,"doc.pdf");src.renameTo(file)
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{fd->PdfRenderer(fd).use{r->
            require(r.pageCount>0){"В PDF нет страниц"}
            val page=r.openPage(0);val w=600;val h=(w*page.height/page.width.toFloat()).toInt().coerceIn(200,1600)
            val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE);page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);page.close()
            val cover=coverAsset(bmp);bmp.recycle()
            val book=newBook(id,name,"pdf").put("doc",JSONObject().put("id",id).put("type","pdf").put("count",r.pageCount)).put("size",file.length()).put("cover",JSONObject().put("__polkaBlob",cover.id))
            pdfTitle(file)?.let{book.put("title",it)}
            return Imported(book,content(book),listOf(cover))}}}
    /** The /Title of a PDF's info, when it is plain text near the end of the file. */
    private fun pdfTitle(f:File):String?=try{val len=f.length();val take=minOf(len,256*1024L).toInt();val buf=ByteArray(take)
        java.io.RandomAccessFile(f,"r").use{it.seek(len-take);it.readFully(buf)};val s=String(buf,Charsets.ISO_8859_1)
        Regex("/Title\\s*\\(([^)]{2,120})\\)").find(s)?.groupValues?.get(1)?.takeIf{t->t.all{it.code in 32..126}&&t.isNotBlank()}}catch(_:Exception){null}

    /** Unpacks the pictures of a comic in reading order; ComicInfo.xml gives title and author when present. */
    private fun images(a:MainActivity,id:String,dir:File,src:File,name:String,format:String,walk:((String,()->java.io.InputStream)->Unit)->Unit):Imported{
        val pages=File(dir,"pages").apply{mkdirs()};val found=ArrayList<Pair<String,File>>();var info:ByteArray?=null;var k=0
        walk{path,open->val base=path.substringAfterLast('/')
            if(base.equals("ComicInfo.xml",true)){info=open().use{it.readBytes()};return@walk}
            if(!IMAGE.matches(path)||path.contains("__MACOSX")||base.startsWith("."))return@walk
            val f=File(pages,"t${k++}");open().use{inp->f.outputStream().use{inp.copyTo(it)}};found.add(path to f)}
        src.delete()
        require(found.isNotEmpty()){"В архиве нет страниц комикса"}
        val sorted=found.sortedWith(Comparator{x,y->natural.compare(x.first,y.first)})
        val sizes=JSONArray();val names=JSONArray()
        sorted.forEachIndexed{i,(path,f)->val ext=path.substringAfterLast('.').lowercase();val dest=File(pages,"%04d.%s".format(i,ext));f.renameTo(dest);names.put(dest.name)
            val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(dest.path,o);sizes.put(JSONArray().put(o.outWidth.coerceAtLeast(1)).put(o.outHeight.coerceAtLeast(1)))}
        File(dir,"pages.json").writeText(JSONObject().put("files",names).put("sizes",sizes).toString())
        val first=File(pages,names.getString(0));val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(first.path,o)
        var sample=1;while(o.outWidth/(sample*2)>=600)sample*=2
        val bmp=BitmapFactory.decodeFile(first.path,BitmapFactory.Options().apply{inSampleSize=sample})?:error("Не удалось прочитать обложку")
        val cover=coverAsset(bmp);bmp.recycle()
        val book=newBook(id,name,format).put("comic",true).put("doc",JSONObject().put("id",id).put("type","images").put("count",names.length())).put("size",pages.listFiles()?.sumOf{it.length()}?:0L).put("cover",JSONObject().put("__polkaBlob",cover.id))
        info?.let{x->try{val d=org.jsoup.Jsoup.parse(String(x,Charsets.UTF_8),"",org.jsoup.parser.Parser.xmlParser())
            val series=d.selectFirst("Series")?.text().orEmpty();val number=d.selectFirst("Number")?.text().orEmpty();val title=d.selectFirst("Title")?.text().orEmpty()
            val t=when{series.isNotBlank()&&number.isNotBlank()->"$series #$number";series.isNotBlank()->series;else->title};if(t.isNotBlank())book.put("title",t)
            if(series.isNotBlank()){book.put("series",series);number.toDoubleOrNull()?.let{book.put("seriesNo",it)}}
            listOf("Writer","Penciller").firstNotNullOfOrNull{tag->d.selectFirst(tag)?.text()?.takeIf{it.isNotBlank()}}?.let{book.put("authors",JSONArray(it.split(',').map{s->s.trim()}.filter{s->s.isNotBlank()}))}
            d.selectFirst("Summary")?.text()?.takeIf{it.isNotBlank()}?.let{book.put("annotation",it)}
            if(d.selectFirst("Manga")?.text().equals("YesAndRightToLeft",true))book.put("pageMode","rtl")}catch(_:Exception){}}
        return Imported(book,content(book),listOf(cover))}

    /** Builds a CBZ / returns the PDF, for «Скачать файл». */
    fun export(c:android.content.Context,book:JSONObject):Pair<String,ByteArray>{val d=dir(c,book)
        if(book.optJSONObject("doc")?.str("type")=="pdf")return "${book.str("title")}.pdf" to File(d,"doc.pdf").readBytes()
        val o=java.io.ByteArrayOutputStream();java.util.zip.ZipOutputStream(o).use{z->File(d,"pages").listFiles()?.sortedBy{it.name}?.forEach{f->z.putNextEntry(java.util.zip.ZipEntry(f.name));f.inputStream().use{it.copyTo(z)};z.closeEntry()}}
        return "${book.str("title")}.cbz" to o.toByteArray()}
}

/** A comic's cover drawn as a thin laminated issue (approved «Б2»): the cover rolls round the spine fold (a light on
 *  the very edge, shade and a faint crease just inside), the sheet bows with its free edge darker and catching a
 *  hair of light, one broad soft sheen of the laminate plus a diagonal reflection, and a short close shadow. */
internal object ComicCover {
    private val p=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m=Matrix()
    fun shadow(c:Canvas,r:RectF,dark:Boolean){val left=r.width()*.045f;val right=r.width()*.012f
        p.shader=null;p.color=0;p.setShadowLayer(r.width()*.035f,r.width()*.008f,r.width()*.03f,if(dark)0x8c000000.toInt() else 0x33000000)
        c.drawPath(outline(r,left,right),p);p.clearShadowLayer()}
    private fun outline(r:RectF,left:Float,right:Float)=Path().apply{addRoundRect(r,floatArrayOf(left,left,right,right,right,right,left,left),Path.Direction.CW)}
    fun draw(c:Canvas,r:RectF,bitmap:Bitmap?,fallback:Int){
        val w=r.width();val h=r.height();val path=outline(r,maxOf(w*.045f,3f),maxOf(w*.012f,1.5f))
        c.save();c.clipPath(path)
        if(bitmap!=null){val s=maxOf(w/bitmap.width,h/bitmap.height);val bw=bitmap.width*s;val bh=bitmap.height*s;p.shader=null;p.color=Color.WHITE;c.drawBitmap(bitmap,null,RectF(r.centerX()-bw/2,r.centerY()-bh/2,r.centerX()+bw/2,r.centerY()+bh/2),p)}
        else{p.shader=null;p.color=fallback;c.drawRect(r,p)}
        fun lin(x0:Float,y0:Float,x1:Float,y1:Float,colors:IntArray,stops:FloatArray){p.shader=LinearGradient(x0,y0,x1,y1,colors,stops,Shader.TileMode.CLAMP);c.drawRect(r,p)}
        // the fold at the spine
        lin(r.left,0f,r.right,0f,intArrayOf(0x99000000.toInt(),0x80ffffff.toInt(),0x38ffffff,0x4d000000,0x29000000,0x24ffffff,0x0affffff,0),floatArrayOf(0f,.013f,.03f,.05f,.075f,.11f,.15f,.19f))
        // the bow of the sheet and its free edge
        lin(r.left,0f,r.right,0f,intArrayOf(0,0x17ffffff,0,0x14000000,0x3d000000,0x2effffff),floatArrayOf(.12f,.3f,.5f,.72f,.97f,1f))
        // laminate: one broad soft sheen…
        p.shader=RadialGradient(0f,0f,1f,intArrayOf(0x52ffffff,0x1cffffff,0),floatArrayOf(0f,.45f,1f),Shader.TileMode.CLAMP)
        m.setScale(w*.7f,h*.45f);m.postTranslate(r.left+w*.3f,r.top+h*.26f);p.shader.setLocalMatrix(m);c.drawRect(r,p)
        // …a diagonal reflection, and the faint glare along the top edge
        val a=Math.toRadians(28.0);val dx=(kotlin.math.cos(a)*w).toFloat();val dy=(kotlin.math.sin(a)*w).toFloat()
        lin(r.left,r.top,r.left+dx,r.top+dy,intArrayOf(0,0x29ffffff,0x08ffffff,0),floatArrayOf(.52f,.6f,.66f,.72f))
        lin(0f,r.top,0f,r.top+h*.03f,intArrayOf(0x33ffffff,0),floatArrayOf(0f,1f))
        p.shader=null;p.color=0x4dffffff;c.drawRect(r.right-maxOf(1f,w*.009f),r.top+h*.012f,r.right,r.bottom-h*.012f,p)
        c.restore()}
}
