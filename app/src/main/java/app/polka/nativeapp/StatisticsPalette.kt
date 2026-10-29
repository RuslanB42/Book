package app.polka.nativeapp

/** Deliberate pigment pairs, not gray surfaces washed with a tiny accent overlay. */
internal data class StatisticsTone(val start:Int,val end:Int,val accent:Int,val control:Int,val track:Int)
internal object StatisticsPalette {
    private fun c(hex:String)=0xff000000.toInt() or hex.toInt(16)
    fun tone(light:Boolean,accent:Int?):StatisticsTone {
        val values=when(accent) {
            statMint->if(light)listOf("D6F4E6","EFFBF4","066C4A","C0EAD7","AADAC5")else listOf("0B493C","0C2926","65E5B5","135244","1C5D4E")
            statAmber->if(light)listOf("FFE5C2","FFF6E9","98490D","F8D7AA","EDC994")else listOf("502E0D","302012","FFBC60","603A17","6B431D")
            statViolet->if(light)listOf("E9DDFB","F8F1FF","693DAA","DCCBF0","CFB9E9")else listOf("3C265B","251A39","C7A3FF","4B326C","523D6D")
            statCyan->if(light)listOf("D1F2F5","EFFBFC","06687C","B5E5EB","9CD8E0")else listOf("094653","102D37","60E0EE","12515E","205968")
            statBlue->if(light)listOf("D8E9FF","EEF5FF","1856B5","C1DAFA","ABC8F0")else listOf("103D6C","111F36","79BEFF","194A78","173E65")
            else->if(light)listOf("E7EFF8","F7FAFE","1856B5","D1E0F2","BACDE5")else listOf("1A2C43","141F30","79BEFF","233F60","304F73")
        }
        return StatisticsTone(c(values[0]),c(values[1]),c(values[2]),c(values[3]),c(values[4]))
    }
    fun secondary(light:Boolean)=if(light)c("4D5969")else c("C3CEDC")
    fun onAccent(light:Boolean)=if(light)c("FFFFFF")else c("082330")
    fun graph(light:Boolean,speed:Boolean)=if(speed) {
        if(light)intArrayOf(c("077184"),c("06667D"))else intArrayOf(c("6AEEF2"),c("23B9D4"))
    }else if(light)intArrayOf(c("096AB8"),c("2259C7"))else intArrayOf(c("69D9FF"),c("3985F5"))
}
