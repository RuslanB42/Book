package app.polka.nativeapp

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/** Original on-device neighbour ranking, including series gates and author diversity. */
object RecommendationEngine {
    private val weights=doubleArrayOf(0.0,-1.0,-.6,.3,1.1,1.6)
    private fun norm(s:String)=s.lowercase().replace('ё','е').replace(Regex("[«»\"'„“”‘’.,:;!?()\\[\\]—–\\-…]")," ").replace(Regex("\\s+")," ").trim()
    private fun seriesNumber(n:Double)=if(n%1.0==0.0) n.toLong().toString() else n.toString()
    fun jitter(index:Int,seed:Int):Double { var x=((index+1L)*2654435761L).toInt() xor seed; x=(x xor (x ushr 15))*2246822507L.toInt(); x=x xor (x ushr 13); return ((x.toLong() and 0xffffffffL)%1000)/1000.0 }
    fun sameBook(a:JSONObject,b:JSONObject):Boolean { val ta=norm(a.str("title")); val tb=norm(b.str("title")); if(ta!=tb && !(minOf(ta.length,tb.length)>=6 && (ta.startsWith(tb) || tb.startsWith(ta)))) return false; val sa=surnames(MainActivity.authors(a)); val sb=surnames(MainActivity.authors(b)); return sa.isEmpty() || sb.isEmpty() || sa.any { it in sb } }
    private fun surnames(s:String)=s.split(',').map { norm(it).substringAfterLast(' ') }.filter { it.isNotBlank() }.toSet()
    private data class Index(val ids:Map<String,Int>,val titles:Map<String,List<Int>>,val surnames:List<Set<String>>)
    private val indexes=java.util.WeakHashMap<JSONObject,Index>()
    @Synchronized private fun index(catalog:JSONObject):Index = indexes.getOrPut(catalog) {
        val rows=catalog.getJSONArray("b");val ids=linkedMapOf<String,Int>();val titles=linkedMapOf<String,MutableList<Int>>();val names=mutableListOf<Set<String>>()
        for(i in 0 until rows.length()){val row=rows.getJSONArray(i);ids[row.optString(12)]=i;listOf(row.optString(0),row.optString(10)).forEach{titles.getOrPut(norm(it)){mutableListOf()}.add(i)};names.add(surnames(row.optString(1))+surnames(row.optString(11)))};Index(ids,titles,names)
    }
    fun match(catalog:JSONObject,book:JSONObject):Int? {
        val lookup=index(catalog);lookup.ids[book.str("rec")]?.let{return it};val title=norm(book.str("title"));val sur=surnames(MainActivity.authors(book));var candidates=lookup.titles[title].orEmpty()
        if(candidates.isEmpty()){val short=norm(book.str("title").split(Regex("[.:(]"))[0]);if(short.length>3)candidates=lookup.titles[short].orEmpty()}
        return candidates.firstOrNull{if(sur.isEmpty())candidates.size==1 else lookup.surnames[it].any{it in sur}}
    }
    fun signals(catalog:JSONObject,books:List<JSONObject>,state:JSONObject):Map<Int,Pair<Double,String>> {
        val rows=catalog.getJSONArray("b"); val n=rows.length(); val byId=index(catalog).ids
        val signals=linkedMapOf<Int,Pair<Double,String>>()
        fun add(index:Int?,weight:Double,label:String="") { if(index!=null && index in 0 until n) { val old=signals[index]; if(old==null || kotlin.math.abs(weight)>kotlin.math.abs(old.first)) signals[index]=weight to label.ifBlank { rows.getJSONArray(index).optString(0) } } }
        books.filterNot { it.optBoolean("sample") }.forEach { book -> val rating=book.optInt("rating"); add(match(catalog,book),if(rating in 1..5) weights[rating] else if(book.optBoolean("noFile")) .6 else when(Library.status(book)) { "done" ->1.0;"reading"->.75;else->.5 },book.str("title")) }
        state.optJSONArray("picks")?.let { picks -> for(i in 0 until picks.length()) add(byId[picks.optString(i)],1.0) }
        state.optJSONObject("sw")?.let { sw -> sw.keys().forEach { id -> val vote=sw.optJSONObject(id) ?: JSONObject(); val rating=vote.optInt("r"); add(byId[id],when(vote.str("a")) { "nope"->-.5;"read"->if(rating in 1..5) weights[rating] else .8;else->.6 }) } }
        return signals
    }
    fun positive(catalog:JSONObject,books:List<JSONObject>,state:JSONObject)=signals(catalog,books,state).values.count { it.first>0 }
    fun startPages(catalog:JSONObject,books:List<JSONObject>,state:JSONObject):List<List<JSONObject>> {
        val rows=catalog.getJSONArray("b"); val tags=catalog.getJSONArray("tags").strings(); val sig=signals(catalog,books,state)
        val order=(0 until rows.length()).filter { it !in sig && rows.getJSONArray(it).optDouble(3,0.0)<=1 }.toMutableList()
        order.sortByDescending { i -> val r=rows.getJSONArray(i); val ts=r.optJSONArray(9); val t=(0 until (ts?.length() ?: 0)).map { ts!!.optInt(it) }; r.optDouble(7)+(if(tags.indexOf("русская литература") in t) 12 else 0)+(if(tags.indexOf("классика") in t) 4 else 0) }
        val pool=order.take(260).toMutableList(); val pages=mutableListOf<List<JSONObject>>(); val page=mutableListOf<JSONObject>(); val used=mutableMapOf<Int,Int>()
        while(pool.isNotEmpty() && pages.size<8) { val k=pool.indexOfFirst { (used[rows.getJSONArray(it).optJSONArray(9)?.optInt(0,-1) ?: -1] ?: 0)<2 }.coerceAtLeast(0); val i=pool.removeAt(k); val theme=rows.getJSONArray(i).optJSONArray(9)?.optInt(0,-1) ?: -1; used[theme]=(used[theme] ?: 0)+1; page.add(bookAt(catalog,i)); if(page.size==12) { pages.add(page.toList()); page.clear(); used.clear() } }
        return pages
    }
    fun bookAt(catalog:JSONObject,i:Int):JSONObject {
        val b=catalog.getJSONArray("b").getJSONArray(i)
        return JSONObject().put("id","rec-"+b.optString(12)).put("title",b.optString(0)).put("authors",JSONArray(b.optString(1).split(',').map { it.trim() }.filter { it.isNotBlank() })).put("series",b.optString(2).takeIf { it!="null" }.orEmpty()).put("seriesNo",b.opt(3)).put("annotation",b.optString(4)).put("year",b.opt(5)).put("recRating",b.optDouble(8)).put("themes",JSONArray((0 until (b.optJSONArray(9)?.length() ?: 0)).map { k -> catalog.getJSONArray("tags").optString(b.getJSONArray(9).optInt(k)) })).put("rec",b.opt(12)).put("recPages",b.optInt(6)).put("addedAt",System.currentTimeMillis()).put("quotes",JSONArray()).put("notes",JSONArray()).put("bookmarks",JSONArray()).put("progress",JSONObject().put("ch",0).put("block",0).put("off",0).put("pct",0))
    }
    fun rank(catalog:JSONObject,books:List<JSONObject>,dismissed:Set<String>,state:JSONObject=JSONObject(),seed:Int=0):List<JSONObject> {
        val rows=catalog.getJSONArray("b"); val neighbors=catalog.optJSONArray("nb"); val n=rows.length(); val byId=index(catalog).ids
        val signals=signals(catalog,books,state)
        val scores=FloatArray(n); val best=FloatArray(n); val why=IntArray(n) { -1 }; val kinds=IntArray(n); var pos=0.0; val seriesGood=mutableSetOf<String>(); val seriesBad=mutableSetOf<String>()
        signals.forEach { (i,pair) -> val (weight,_)=pair; val b=rows.getJSONArray(i); val series=b.optString(2).takeIf { it!="null" }.orEmpty(); if(series.isNotBlank()) { (if(weight>0) seriesGood else seriesBad).add(series+"#"+seriesNumber(b.optDouble(3,0.0))); if(weight<0) seriesBad.add(series) }; if(weight>0) pos+=weight
            val nb=neighbors?.optJSONArray(i); if(nb!=null) for(k in 0 until nb.length() step 3) { if(k+2<nb.length()) { val j=nb.optInt(k,-1); if(j in 0 until n) { val c=weight*nb.optDouble(k+1)/100; scores[j]=(scores[j]+c).toFloat(); if(c>best[j]) { best[j]=c.toFloat(); why[j]=i; kinds[j]=nb.optInt(k+2) } } } }
        }
        val norm=if(pos>0) 1/sqrt(pos) else 0.0
        val ranked=(0 until n).filter { i -> val b=rows.getJSONArray(i); val series=b.optString(2).takeIf { it!="null" }.orEmpty(); val no=b.optDouble(3,1.0).takeIf { it!=0.0 } ?: 1.0; i !in signals && b.optString(12) !in dismissed && scores[i]>=0 && (series.isBlank() || (series !in seriesBad && (no<=1 || series+"#"+seriesNumber(no-1) in seriesGood))) }.sortedByDescending { i -> val b=rows.getJSONArray(i); scores[i]*norm*1.4+.22*b.optDouble(7)/100+.08*maxOf(0.0,b.optDouble(8)-3.6)+.07*jitter(i,seed) }
        val pool=ranked.take(160).toMutableList(); val result=mutableListOf<JSONObject>(); val recent=mutableListOf<String>()
        while(pool.isNotEmpty() && result.size<40) {
            val index=pool.indexOfFirst { surnames(rows.getJSONArray(it).optString(1)).firstOrNull() !in recent }.coerceAtLeast(0); val i=pool.removeAt(index); val b=rows.getJSONArray(i); recent.add(surnames(b.optString(1)).firstOrNull().orEmpty()); if(recent.size>2) recent.removeAt(0)
            result.add(bookAt(catalog,i).put("reason",if(why[i]<0) "Популярное у читателей" else (if(kinds[i]>0) "Похоже на «" else "Любят читатели «")+signals[why[i]]!!.second+"»"))
        }
        return result
    }
}
