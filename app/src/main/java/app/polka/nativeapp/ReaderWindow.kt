package app.polka.nativeapp

import android.graphics.Color
import android.os.Build
import android.view.*

/** One window policy for API 26–35, including pre-edge-to-edge Samsung devices. */
@Suppress("DEPRECATION")
internal object ReaderWindow {
 fun configure(window:Window,immersive:Boolean,background:Int) {
  val light=Color.luminance(background)>.5
  val decor=window.decorView // Install DecorView before querying its insets controller.
  if(Build.VERSION.SDK_INT>=30)window.setDecorFitsSystemWindows(false)
  if(Build.VERSION.SDK_INT>=28)window.attributes=window.attributes.apply{layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES}
  if(Build.VERSION.SDK_INT>=29){window.isStatusBarContrastEnforced=false;window.isNavigationBarContrastEnforced=false}
  window.statusBarColor=Color.TRANSPARENT
  window.navigationBarColor=Color.TRANSPARENT
  if(Build.VERSION.SDK_INT>=28)window.navigationBarDividerColor=Color.TRANSPARENT
  if(Build.VERSION.SDK_INT>=30){
   val mask=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
   window.insetsController?.setSystemBarsAppearance(if(light)mask else 0,mask)
   window.insetsController?.apply{systemBarsBehavior=WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;if(immersive)hide(WindowInsets.Type.systemBars())else show(WindowInsets.Type.systemBars())}
  }else{
   decor.systemUiVisibility=View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or (if(immersive)View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else 0) or (if(light)View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
  }
 }
 fun apply(a:MainActivity,insets:WindowInsets) {
  // Keep the cached library safe area while its destination is prepared beneath the live
  // immersive reader. Showing bars/reflowing in the middle of a reverse flight causes a jump.
  if(a.bookClosing)return
  val r=a.reader
  if(r!=null){
   a.root.setPadding(0,0,0,0)
   val safe=if(Build.VERSION.SDK_INT>=28){val cutout=insets.displayCutout;intArrayOf(cutout?.safeInsetLeft?:0,cutout?.safeInsetTop?:0,cutout?.safeInsetRight?:0,cutout?.safeInsetBottom?:0)}else intArrayOf(0,0,0,0)
   val keyboard=if(Build.VERSION.SDK_INT>=30)insets.getInsets(WindowInsets.Type.ime()).bottom else (insets.systemWindowInsetBottom.takeIf{it>a.d(100)}?:0)
   val gestures=if(Build.VERSION.SDK_INT>=29)insets.systemGestureInsets.bottom else 0
   r.safeArea(safe[0],safe[1],safe[2],maxOf(safe[3],gestures,keyboard))
  }else {
   val safe=SafeArea.content(androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets,a.root))
   a.updateAppInsets(safe)
  }
 }
}
