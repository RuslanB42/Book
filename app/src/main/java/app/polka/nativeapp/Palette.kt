package app.polka.nativeapp

/**
 * The colours that carry meaning of their own, beside the interface's roles ([Ui]): the sounds, the kinds of notice,
 * the kinds of file, the stars, the statistics and the tiers. Each is defined here once, by name; screens never write
 * a colour as a number. Drawings (covers, the flame, the snowflake) keep their own.
 */
internal object Palette {
    private fun c(v:Long)=v.toInt()

    /** A sound's light (its tile's glow) and its depth. */
    class Glow(val light:Int,val deep:Int)
    val fire=Glow(c(0xffc2562b),c(0xff3a1a10))
    val rain=Glow(c(0xff3f6f9e),c(0xff14202d))
    val sea=Glow(c(0xff2f8d9a),c(0xff0f2629))
    val forest=Glow(c(0xff4f8a4a),c(0xff152414))
    /** A sound of your own. */
    val mine=c(0xff9d98f2)

    /** Notices: in progress or half done, done, failed, a plain note, the book is already there, books found. */
    val waiting=c(0xfff0a742)
    val done=c(0xff34b866)
    val failed=c(0xffef5b4c)
    val note=c(0xff8e8e94)
    val known=c(0xff6e6e74)
    val found=c(0xff5b8def)

    /** The kinds of file found on the phone. */
    fun fileType(kind:String):Int?=when(kind){"EPUB"->c(0xff5b8def);"FB2"->c(0xffe0a63a);"PDF"->c(0xffe06a5a);"CBZ","CBR","CB7"->c(0xff8f7af0)
        "MOBI","AZW"->c(0xff4fb38a);"DOCX","RTF"->c(0xff6aa0e0);else->null}

    /** Filled stars (empty ones are [Ui.icon]). */
    fun star(l:Boolean)=if(l)c(0xfff5a524) else c(0xffffc53d)
    /** A link in a book's text: [dark] — on a dark page. */
    fun link(dark:Boolean)=if(dark)c(0xff8ab4f8) else c(0xff2f6bd0)
    /** A quote's highlight, by its name (RGB without alpha, as kept with the quote). */
    val quotes=listOf("amber" to 0xf0a742,"mint" to 0x7cc98e,"sky" to 0x6fa8f0,"rose" to 0xef7fa8,"lavender" to 0xa98bf7)
    /** Saved (a quote's own colour). */
    val saved=c(0xff4fb06a)
    /** A word learnt and a word missed in a lesson. */
    fun right(l:Boolean)=if(l)c(0xff1f9d6b) else c(0xff63d2a1)
    fun wrong(l:Boolean)=if(l)c(0xffd2543f) else c(0xffff8a7a)

    // ── statistics ──
    val statBlue=c(0xff79beff)
    val statMint=c(0xff65e5b5)
    val statAmber=c(0xffffbc60)
    val statViolet=c(0xffc7a3ff)
    val statCyan=c(0xff60e0ee)
    fun blue(l:Boolean)=if(l)c(0xff3f6fe0) else c(0xff6a94f8)
    fun mint(l:Boolean)=if(l)c(0xff1f9d73) else c(0xff4fd6a2)
    fun violet(l:Boolean)=if(l)c(0xff7a56d8) else c(0xffa98bf7)
    fun rose(l:Boolean)=if(l)c(0xffd0457f) else c(0xfff28bb5)
    fun orange(l:Boolean)=if(l)c(0xffe07a10) else c(0xffff9a3c)
    /** The best place, the warm one among the rest. */
    fun warm(l:Boolean)=if(l)c(0xffc87600) else c(0xfff0a83a)
    /** The streak's days: read, saved by a freeze. */
    fun streak(l:Boolean)=if(l)c(0xfff07c12) else c(0xffff8a1e)
    fun ice(l:Boolean)=if(l)c(0xff5aaee6) else c(0xff8fd0fa)
    /** The streak card's own warmth, and the goal reached. */
    val flame=c(0xfff9601f)
    fun reached(l:Boolean)=if(l)c(0xff009a5c) else c(0xff37c080)
    /** The year's and the tier list's gold light. */
    fun gold(l:Boolean)=if(l)c(0xfff5b52a) else c(0xffe0a12a)

    /** A tier's light and deep tone (approved «Приглушённая»: S red, A orange, B amber, C yellow, D green). */
    fun tier(l:Boolean,k:Int):Pair<Int,Int> =if(l)when(k){0->c(0xffd9625c) to c(0xffb8433d);1->c(0xffd98a4a) to c(0xffb0662a);2->c(0xffcfa548) to c(0xffa37c22);3->c(0xffb5a83e) to c(0xff8a7f22);else->c(0xff7fa650) to c(0xff5c8233)}
        else when(k){0->c(0xfff2a3a0) to c(0xffd9625c);1->c(0xfff2c49b) to c(0xffd98a4a);2->c(0xfff0d79a) to c(0xffcfa548);3->c(0xffe8e2a0) to c(0xffbdb45a);else->c(0xffc9e3a2) to c(0xff8fb862)}
    /** Not rated yet. */
    val untiered=c(0xff8a8a90)
}
