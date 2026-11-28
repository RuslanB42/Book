package app.polka.nativeapp

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * «Найти на телефоне»: the book files lying in the phone's memory (Загрузки, Документы, Telegram's «Сохранить в
 * загрузки», Books, a memory card…), found with the app's access to all files. They are only read; what is added
 * goes through the same import as a picked file.
 */
internal object PhoneBooks {
    class Found(val file:File,val kind:String,val title:String,val author:String,val root:File){
        /** A book format (ticked at once); a PDF, a document or a long text may as well be anything else. */
        val book get()=kind in BOOK
    }
    private val BOOK=setOf("EPUB","FB2","MOBI","AZW","CBZ","CBR","CB7")
    private const val PREFS="phone-books"

    fun allowed(c:Context)=if(Build.VERSION.SDK_INT>=30)Environment.isExternalStorageManager()
        else c.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED

    fun kind(f:File):String?{val n=f.name.lowercase();return when{
        n.endsWith(".fb2.zip")||n.endsWith(".fb2")->"FB2";n.endsWith(".epub")->"EPUB";n.endsWith(".mobi")->"MOBI"
        n.endsWith(".azw3")||n.endsWith(".azw")->"AZW";n.endsWith(".cbz")->"CBZ";n.endsWith(".cbr")->"CBR";n.endsWith(".cb7")->"CB7"
        n.endsWith(".pdf")->"PDF";n.endsWith(".docx")->"DOCX";n.endsWith(".rtf")->"RTF"
        // a text that long is likely a book, not a note or a log
        n.endsWith(".txt")&&f.length()>=100*1024->"TXT"
        else->null}}

    /** The phone's memory and any memory card. */
    fun roots(c:Context):List<File>{val out=LinkedHashSet<File>()
        @Suppress("DEPRECATION") Environment.getExternalStorageDirectory()?.let{out.add(it)}
        if(Build.VERSION.SDK_INT>=30)runCatching{c.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes.forEach{v->v.directory?.let{out.add(it)}}}
        else c.getExternalFilesDirs(null).forEach{d->d?.absolutePath?.substringBefore("/Android/")?.let{out.add(File(it))}}
        return out.filter{it.canRead()}}

    // where books usually are, looked at first (so the list starts with them)
    private val FIRST=listOf("download","documents","books","telegram","книги")
    /** Every book file under [roots], in the order met; [stop] ends it early. Hidden folders and the apps' private
     *  ones are left alone (Android/media, where messengers keep what they received, is not). */
    fun walk(roots:List<File>,stop:()->Boolean,found:(File,String,File)->Unit){
        val seen=HashSet<String>()
        fun go(dir:File,depth:Int,root:File){if(stop()||depth>9)return
            val path=runCatching{dir.canonicalPath}.getOrNull()?:return;if(!seen.add(path))return
            val list=dir.listFiles()?:return
            list.filter{it.isFile&&!it.name.startsWith(".")}.sortedBy{it.name.lowercase()}.forEach{f->if(stop())return
                val k=kind(f)?:return@forEach;val n=f.length();if(n<2048||n>128L*1024*1024)return@forEach;found(f,k,root)}
            list.filter{it.isDirectory&&!it.name.startsWith(".")}.sortedWith(compareBy<File>({FIRST.indexOf(it.name.lowercase()).let{i->if(i<0)99 else i}},{it.name.lowercase()})).forEach{d->
                if(depth==0&&d.name=="Android")go(File(d,"media"),depth+2,root) else go(d,depth+1,root)}}
        roots.forEach{go(it,0,it)}}

    /** Title and author from the file itself where that is cheap (FB2, EPUB), else the file's name. */
    fun meta(f:File,kind:String):Pair<String,String>{
        val got=runCatching{when(kind){
            "FB2"->fb2(if(f.name.lowercase().endsWith(".zip"))ZipFile(f).use{z->z.entries().asSequence().firstOrNull{it.name.lowercase().endsWith(".fb2")}?.let{e->z.getInputStream(e).use{it.readNBytesCompat(96*1024)}}}else f.inputStream().use{it.readNBytesCompat(96*1024)})
            "EPUB"->epub(f)
            else->null}}.getOrNull()
        return if(got!=null&&got.first.isNotBlank())got else (plain(f) to "")}
    fun plain(f:File)=f.name.removeSuffix(".zip").substringBeforeLast('.').replace('_',' ').trim()
    private fun java.io.InputStream.readNBytesCompat(n:Int):ByteArray{val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);var left=n
        while(left>0){val r=read(buf,0,minOf(buf.size,left));if(r<0)break;out.write(buf,0,r);left-=r};return out.toByteArray()}
    private fun clean(s:String)=s.replace(Regex("<[^>]+>"),"").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace("&quot;","\"").replace("&apos;","'").replace(Regex("\\s+")," ").trim()
    private fun fb2(bytes:ByteArray?):Pair<String,String>?{if(bytes==null)return null
        val head=String(bytes,0,minOf(bytes.size,200),Charsets.ISO_8859_1)
        val cs=Regex("encoding=[\"']([^\"']+)").find(head)?.groupValues?.get(1)?.let{runCatching{charset(it)}.getOrNull()}?:Charsets.UTF_8
        val text=String(bytes,cs);val info=Regex("<title-info>(.*?)</title-info>",RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1)?:text
        val title=Regex("<book-title>(.*?)</book-title>",RegexOption.DOT_MATCHES_ALL).find(info)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val author=Regex("<author>(.*?)</author>",RegexOption.DOT_MATCHES_ALL).find(info)?.groupValues?.get(1)?.let{a->
            listOf("first-name","middle-name","last-name").mapNotNull{t->Regex("<$t>(.*?)</$t>",RegexOption.DOT_MATCHES_ALL).find(a)?.groupValues?.get(1)?.let(::clean)?.takeIf{it.isNotBlank()}}.joinToString(" ")}.orEmpty()
        return title to author}
    private fun epub(f:File):Pair<String,String>?=ZipFile(f).use{z->
        val container=z.getEntry("META-INF/container.xml")?.let{e->String(z.getInputStream(e).use{it.readNBytesCompat(16*1024)})}?:return null
        val opfPath=Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)?:return null
        val opf=z.getEntry(opfPath)?.let{e->String(z.getInputStream(e).use{it.readNBytesCompat(256*1024)})}?:return null
        val title=Regex("<(?:\\w+:)?title\\b[^>]*>(.*?)</(?:\\w+:)?title>",RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.let(::clean).orEmpty()
        val author=Regex("<(?:\\w+:)?creator\\b[^>]*>(.*?)</(?:\\w+:)?creator>",RegexOption.DOT_MATCHES_ALL).find(opf)?.groupValues?.get(1)?.let(::clean).orEmpty()
        title to author}

    /** What the library already has: its books' file names and their titles with the first author. */
    class Known(books:List<JSONObject>,c:Context){
        private val names=books.mapNotNull{it.optString("fileName").takeIf{n->n.isNotBlank()}?.lowercase()}.toHashSet()
        private val titles=HashMap<String,MutableList<String>>().also{m->books.forEach{b->m.getOrPut(b.optString("title").trim().lowercase()){ArrayList()}.add(MainActivity.authors(b).substringBefore(',').trim().lowercase())}}
        private val added=c.getSharedPreferences(PREFS,0).getStringSet("added",emptySet())!!.toHashSet()
        fun has(f:Found):Boolean{if(f.file.absolutePath in added||f.file.name.lowercase() in names)return true
            val list=titles[f.title.trim().lowercase()]?:return false;val au=f.author.substringBefore(',').trim().lowercase()
            return au.isBlank()||list.any{it.isBlank()||it==au}}
    }
    fun remember(c:Context,key:String,paths:Collection<String>){if(paths.isEmpty())return;val p=c.getSharedPreferences(PREFS,0)
        p.edit().putStringSet(key,HashSet(p.getStringSet(key,emptySet())!!).apply{addAll(paths)}).apply()}
    fun scanned(c:Context,paths:Collection<String>){remember(c,"seen",paths);c.getSharedPreferences(PREFS,0).edit().putBoolean("scanned",true).apply()}

    /** The folder's name as a person calls it, and the way to it from the memory's top when that tells more. */
    fun folderName(dir:File,root:File):String{val rel=dir.absolutePath.removePrefix(root.absolutePath).trim('/')
        return when{rel.isEmpty()->"Память телефона";dir.name.equals("Download",true)->"Загрузки";dir.name.equals("Documents",true)->"Документы"
            dir.name=="WhatsApp Documents"->"WhatsApp";else->dir.name}}
    fun folderPath(dir:File,root:File)=dir.absolutePath.removePrefix(root.absolutePath).trim('/')

    /** On start, after a first search: books that have appeared on the phone since (saved from a messenger, downloaded)
     *  are offered at the top, once each. */
    fun checkNew(a:MainActivity){
        if(!a.getSharedPreferences(PREFS,0).getBoolean("scanned",false)||!allowed(a))return
        val known=Known(a.books,a);val p=a.getSharedPreferences(PREFS,0)
        val seen=p.getStringSet("seen",emptySet())!!.toHashSet().apply{addAll(p.getStringSet("added",emptySet())!!)}
        Thread{
            val all=ArrayList<String>();val fresh=ArrayList<Found>()
            runCatching{walk(roots(a),{a.isFinishing||a.isDestroyed}){f,k,root->all.add(f.absolutePath)
                if(k in BOOK&&f.absolutePath !in seen){val (t,au)=meta(f,k);val found=Found(f,k,t,au,root);if(!known.has(found))fresh.add(found)}}}
            if(a.isFinishing||a.isDestroyed)return@Thread
            remember(a,"seen",all)
            if(fresh.isEmpty())return@Thread
            a.runOnUiThread{if(a.isFinishing||a.isDestroyed||a.reader!=null)return@runOnUiThread
                val n=fresh.size;val word=when{n%100 in 11..14->"новых книг";n%10==1->"новая книга";n%10 in 2..4->"новые книги";else->"новых книг"}
                val dirs=fresh.map{it.file.parentFile}.distinct()
                val where=if(dirs.size==1)fresh[0].let{"В папке ${folderName(it.file.parentFile!!,it.root)}"} else "На телефоне"
                Notice.card(a,Notice.State(Notice.Kind.FOUND,"$n $word",where,blank=true,action="Добавить",onAction={a.addPhoneBooks(fresh.map{it.file})}))}
        }.apply{isDaemon=true;priority=Thread.MIN_PRIORITY}.start()
    }
}

/** Whether a search is waiting for the access asked for (it starts by itself when the person comes back with it). */
internal var phoneScanAsked=false

internal fun MainActivity.findPhoneBooks(){
    if(PhoneBooks.allowed(this)){PhoneScan(this).show();return}
    if(Build.VERSION.SDK_INT>=30){
        dialog().setTitle("Доступ к файлам").setMessage("Чтобы найти книги на телефоне, Glyph нужен доступ к файлам. Он только читает их.")
            .setPositiveButton("Открыть настройки"){_,_->phoneScanAsked=true
                try{startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:$packageName")))}
                catch(e:Exception){runCatching{startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))}}}.show()
    } else {phoneScanAsked=true;requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),31)}
}
/** Back from the access screen (or the permission question): the search starts if the access was given. */
internal fun MainActivity.phoneScanResumed(){if(!phoneScanAsked)return;phoneScanAsked=false;if(PhoneBooks.allowed(this))PhoneScan(this).show()}

internal fun MainActivity.addPhoneBooks(files:List<File>){
    if(files.isEmpty())return
    importUris(files.map{Uri.fromFile(it)}){read->PhoneBooks.remember(this,"added",read.mapNotNull{it.path})}
}

/** The sheet of what was found: it fills in while the search goes on (a thin line runs under the title), grouped by
 *  folder; the books are ticked, a PDF or a document is left for the person to tick, and what the library already has
 *  stays quiet with «В библиотеке». */
internal class PhoneScan(private val a:MainActivity){
    private class Item(val f:PhoneBooks.Found,var on:Boolean,val have:Boolean){var tick:TickBox?=null}
    private inner class Group(val view:LinearLayout,val toggle:TextView){val items=ArrayList<Item>()}
    private val groups=LinkedHashMap<String,Group>()
    private val list=a.column()
    private val line=ScanLine(a)
    private val button=a.button("Добавить",true){add()}
    private var sheet:android.app.AlertDialog?=null
    @Volatile private var stopped=false
    private val known=PhoneBooks.Known(a.books,a)
    private val pending=ArrayList<PhoneBooks.Found>()
    private val all=ArrayList<String>()

    fun show(){
        val box=a.column()
        box.addView(line,LinearLayout.LayoutParams(-1,a.d(3)).apply{topMargin=a.d(4);bottomMargin=a.d(2)})
        box.addView(list,LinearLayout.LayoutParams(-1,-2))
        val foot=a.column().apply{setPadding(a.d(20),a.d(10),a.d(20),a.d(16));addView(button,LinearLayout.LayoutParams(-1,-2))}
        count()
        sheet=a.dialog().setTitle("Ищу книги").setView(box).setTall(true).setFooter(foot).show()
        sheet?.setOnDismissListener{stopped=true}
        val roots=PhoneBooks.roots(a)
        Thread{
            var last=0L
            runCatching{PhoneBooks.walk(roots,{stopped}){f,k,root->val (t,au)=PhoneBooks.meta(f,k)
                synchronized(pending){pending.add(PhoneBooks.Found(f,k,t,au,root));all.add(f.absolutePath)}
                val now=android.os.SystemClock.uptimeMillis();if(now-last>150){last=now;a.runOnUiThread{flush()}}}}
            a.runOnUiThread{flush();done()}
        }.apply{isDaemon=true;priority=Thread.MIN_PRIORITY}.start()
    }

    private fun flush(){if(stopped)return;val batch=synchronized(pending){ArrayList(pending).also{pending.clear()}}
        batch.forEach{f->val have=known.has(f);val item=Item(f,f.book&&!have,have)
            val dir=f.file.parentFile?:return@forEach;val g=groups.getOrPut(dir.absolutePath){group(dir,f.root)}
            g.items.add(item);g.view.addView(row(item,g),LinearLayout.LayoutParams(-1,-2));refresh(g)}
        count()}

    private fun done(){if(stopped)return
        PhoneBooks.scanned(a,synchronized(pending){ArrayList(all)})
        sheet?.window?.decorView?.findViewWithTag<TextView>("sheet-title")?.text="Найдено на телефоне"
        line.finish()
        if(groups.isEmpty()){list.addView(a.text("Книг на телефоне не нашлось",Type.TEXT,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(28),0,a.d(28))},LinearLayout.LayoutParams(-1,-2))
            button.text="Закрыть";button.isEnabled=true;button.alpha=1f;button.setOnClickListener{sheet?.dismiss()}}}

    private fun group(dir:java.io.File,root:java.io.File):Group{
        val view=a.column()
        val head=a.line().apply{setPadding(a.d(6),a.d(18),a.d(6),a.d(6))}
        val names=a.column()
        val name=PhoneBooks.folderName(dir,root);val path=PhoneBooks.folderPath(dir,root)
        names.addView(a.text(name,Type.TITLE,a.fg,true))
        if(path.isNotEmpty()&&path!=name)names.addView(a.text(path,Type.CAPTION,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.START;setPadding(0,a.d(3),0,0)})
        head.addView(names,LinearLayout.LayoutParams(0,-2,1f))
        val toggle=a.text("",Type.SECONDARY,a.fg).apply{typeface=PolkaFonts.sans(500);setPadding(a.d(10),a.d(8),0,a.d(8));isClickable=true}
        head.addView(toggle)
        view.addView(head,LinearLayout.LayoutParams(-1,-2))
        list.addView(view,LinearLayout.LayoutParams(-1,-2))
        val g=Group(view,toggle)
        toggle.setOnClickListener{val free=g.items.filter{!it.have};val on=!free.all{it.on};free.forEach{it.on=on;it.tick?.on=on};refresh(g);count()}
        return g}

    private fun refresh(g:Group){val free=g.items.filter{!it.have}
        g.toggle.visibility=if(free.size>=2)View.VISIBLE else View.GONE
        g.toggle.text=if(free.all{it.on})"Снять все" else "Выбрать все"}

    private fun row(item:Item,g:Group):View{val f=item.f
        val r=a.line().apply{setPadding(a.d(6),a.d(8),a.d(6),a.d(8))}
        val tile=FrameLayout(a).apply{background=a.shape(Ui.pressed(a.light),Radius.R8)
            addView(a.text(f.kind,Type.CAPTION,color(f.kind),true).apply{letterSpacing=.04f;gravity=Gravity.CENTER},FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{bottomMargin=a.d(7)})}
        r.addView(tile,LinearLayout.LayoutParams(a.d(40),a.d(52)))
        val texts=a.column()
        texts.addView(a.text(f.title,Type.BODY,a.fg).apply{typeface=PolkaFonts.sans(500);maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})
        texts.addView(a.text(listOf(f.author,size(f.file.length())).filter{it.isNotBlank()}.joinToString(", "),Type.SECONDARY,a.muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(3),0,0)})
        r.addView(texts,LinearLayout.LayoutParams(0,-2,1f).apply{marginStart=a.d(14);marginEnd=a.d(12)})
        if(item.have){r.addView(a.text("В библиотеке",Type.SECONDARY,a.muted));r.alpha=.45f;r.contentDescription="${f.title}, в библиотеке"}
        else{val tick=TickBox(a,item.on);item.tick=tick;r.addView(tick,LinearLayout.LayoutParams(a.d(24),a.d(24)))
            fun describe(){r.contentDescription=f.title+if(tick.on)", выбрано" else ""};describe()
            r.isClickable=true;r.isFocusable=true;r.setOnClickListener{tick.on=!tick.on;item.on=tick.on;describe();refresh(g);count()};a.press(r)}
        return r}

    private fun chosen()=groups.values.flatMap{g->g.items.filter{it.on&&!it.have}}
    private fun count(){val n=chosen().size
        if(stopped&&groups.isEmpty())return
        button.text=if(n==0)"Добавить" else "Добавить ${MainActivity.countBooks(n)}"
        button.isEnabled=n>0;button.alpha=if(n>0)1f else .4f}

    private fun add(){val files=chosen().map{it.f.file};if(files.isEmpty())return;stopped=true;sheet?.dismiss();a.addPhoneBooks(files)}

    private fun color(kind:String)=Palette.fileType(kind)?:a.muted
    private fun size(n:Long):String{val kb=n/1024.0;return if(kb<1000)"${kb.toInt().coerceAtLeast(1)} КБ" else String.format(java.util.Locale("ru"),"%.1f МБ",kb/1024).replace(",0 МБ"," МБ")}
}

/** The thin line under the sheet's title while the search goes on: a short stroke running along it. */
private class ScanLine(private val a:MainActivity):View(a){
    private val p=Paint(Paint.ANTI_ALIAS_FLAG);private var x=0f
    private val run=ValueAnimator.ofFloat(0f,1f).apply{duration=Motion.CYCLE;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator();addUpdateListener{x=it.animatedValue as Float;invalidate()}}
    override fun onAttachedToWindow(){super.onAttachedToWindow();if(!a.reducedMotion)run.start()}
    override fun onDetachedFromWindow(){run.cancel();super.onDetachedFromWindow()}
    fun finish(){animate().alpha(0f).setDuration(Motion.STANDARD).withEndAction{run.cancel();visibility=GONE}.start()}
    override fun onDraw(c:Canvas){val w=width.toFloat();val h=height.toFloat();val r=h/2
        p.color=Ui.press(a.light);c.drawRoundRect(0f,0f,w,h,r,r,p)
        val len=w*.38f;val e=MotionSpec.move.getInterpolation(x);val left=-len+(w+len)*e
        c.save();c.clipRect(0f,0f,w,h);p.color=a.fg;c.drawRoundRect(left,0f,left+len,h,r,r,p);c.restore()}
}
