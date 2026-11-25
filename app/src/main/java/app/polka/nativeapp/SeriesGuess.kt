package app.polka.nativeapp

import org.json.JSONObject

/**
 * A comic's series and number read from its title, for issues that came without them: «Ghost Rider #07 [RPU]» is
 * Ghost Rider, 7; «Saga Vol. 3», «Хеллбой том 2», «Spawn 012» the same way. A bare four-digit year is not a number.
 */
internal object SeriesGuess {
    private val marked=Regex("""^(.*?)\s*(?:#\s*|№\s*|[\s._\-–—:,]+(?:no\.?|vol(?:ume)?\.?|v\.|issue|ch(?:apter)?\.?|том|выпуск|глава|часть)\s*)(\d+(?:[.,]\d+)?)(?!\d)""",RegexOption.IGNORE_CASE)
    private val trailing=Regex("""^(.*\D)\s+(\d{1,4})$""")
    fun of(title:String):Pair<String,Double>?=try{guess(title)}catch(_:Exception){null}
    private fun guess(title:String):Pair<String,Double>?{
        val t=title.replace(Regex("""\[[^\]]*\]|\([^)]*\)|\{[^}]*\}"""),"").replace('_',' ').trim()
        val m=marked.find(t)?.takeIf{it.groupValues[1].isNotBlank()}?:trailing.find(t)?.takeIf{m->m.groupValues[2].let{n->!(n.length==4&&n.toInt() in 1900..2100)}}?:return null
        val name=m.groupValues[1].trim().trimEnd('-','–','—',':',',','.').trim()
        val no=m.groupValues[2].replace(',','.').toDoubleOrNull()?:return null
        if(name.length<2||name.none{it.isLetter()})return null
        return name to no}
    /** [name] as the library already writes it (a series is one however its issues spell it). */
    fun canonical(name:String,books:List<JSONObject>)=books.map{it.str("series").trim()}.firstOrNull{it.equals(name,true)}?:name
    /** Fills in the series of a comic that has none, from its title; true when something was set. */
    fun fill(book:JSONObject,books:List<JSONObject>):Boolean{
        if(!isComic(book)||book.str("series").isNotBlank())return false
        val (name,no)=of(book.str("title"))?:return false
        book.put("series",canonical(name,books));if(no>0)book.put("seriesNo",no);return true}
}
