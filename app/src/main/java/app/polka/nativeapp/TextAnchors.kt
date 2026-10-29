package app.polka.nativeapp
/** Bridges HTML paragraph whitespace and native rich-text offsets without losing repeated quotes. */
object TextAnchors {
    fun locate(text:String,needle:String,near:Int=0):IntRange? {
        if(needle.isBlank()) return null
        var at=text.indexOf(needle); var best=-1; var distance=Int.MAX_VALUE
        while(at>=0) { val d=kotlin.math.abs(at-near); if(d<distance) { distance=d; best=at }; at=text.indexOf(needle,at+1) }
        if(best>=0) return best until best+needle.length
        val chars=StringBuilder(); val offsets=ArrayList<Int>(); var space=false
        text.forEachIndexed { i,ch -> if(ch.isWhitespace() || ch=='\u00a0') { if(!space) { chars.append(' '); offsets.add(i) }; space=true } else { chars.append(ch); offsets.add(i); space=false } }
        val n=needle.replace(Regex("[\\s\\u00a0]+")," ").trim(); if(n.isEmpty()) return null
        at=chars.indexOf(n); best=-1; distance=Int.MAX_VALUE
        while(at>=0) { val d=kotlin.math.abs(offsets[at]-near); if(d<distance) { distance=d; best=at }; at=chars.indexOf(n,at+1) }
        return if(best<0) null else offsets[best]..offsets[best+n.length-1]
    }
}
