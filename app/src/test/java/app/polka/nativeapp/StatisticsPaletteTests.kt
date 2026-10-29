package app.polka.nativeapp

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.pow

class StatisticsPaletteTests {
 private fun rgb(color:Int)=listOf((color ushr 16) and 255,(color ushr 8) and 255,color and 255)
 private fun luminance(color:Int)=rgb(color).map{it/255.0}.map{if(it<=.04045)it/12.92 else ((it+.055)/1.055).pow(2.4)}.zip(listOf(.2126,.7152,.0722)).sumOf{it.first*it.second}
 private fun contrast(a:Int,b:Int):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
 private val accents=listOf(statBlue,statMint,statAmber,statViolet,statCyan,null)
 @Test fun bodyAccentAndControlsMeetTextContrastInBothThemes(){for(light in listOf(false,true))for(role in accents){val p=StatisticsPalette.tone(light,role);for(bg in listOf(p.start,p.end)){assertTrue("body $light $role",contrast(StatisticsPalette.secondary(light),bg)>=4.5);assertTrue("metric $light $role",contrast(p.accent,bg)>=4.5)};assertTrue("filled action $light $role",contrast(StatisticsPalette.onAccent(light),p.accent)>=4.5);assertTrue("tonal action $light $role",contrast(p.accent,p.control)>=4.5)}}
 @Test fun activeGraphicsSeparateFromTheirTracks(){for(light in listOf(false,true))for(speed in listOf(false,true)){val p=StatisticsPalette.tone(light,if(speed)statCyan else statBlue);for(color in StatisticsPalette.graph(light,speed))assertTrue("chart $light $speed ${contrast(color,p.track)}",contrast(color,p.track)>=3.0)}}
 @Test fun coloredSurfacesAreNotNeutralGrayWashes(){for(light in listOf(false,true)){val starts=accents.map{StatisticsPalette.tone(light,it).start};assertEquals(starts.size,starts.distinct().size);for(role in accents.filterNotNull()){val c=rgb(StatisticsPalette.tone(light,role).start);assertTrue("pigment remains visible",c.max()-c.min()>=28)}}}
}
