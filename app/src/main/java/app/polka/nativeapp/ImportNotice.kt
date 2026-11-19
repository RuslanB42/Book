package app.polka.nativeapp
import android.animation.*
import android.graphics.*
import android.view.Gravity
import android.view.View
import android.view.animation.*
import android.widget.*


internal object GradientDrawableCompat { fun circle(color:Int)=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(color)} }

