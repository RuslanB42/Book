package app.polka.nativeapp

import android.animation.ValueAnimator
import android.graphics.Rect
import android.view.*
import android.view.inputmethod.*
import android.widget.*
import android.text.*
import androidx.core.view.*

/** Inline toolbar search: recycles results only, never replaces the focused editor. */
internal class LibrarySearchBar(private val a:MainActivity):LinearLayout(a) {
 internal val input=a.edit(a.query,"Поиск книг").apply {
  contentDescription="Поиск по библиотеке";setSingleLine(true);imeOptions=EditorInfo.IME_ACTION_SEARCH
  inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
  setPadding(a.d(12),0,a.d(36),0);a.controlSurface(this,22f)
 }
 private val field=FrameLayout(a)
 private val center=FrameLayout(a)
 private var toggleButton:View?=null
 private val clear=IconView(a,"x",a.muted).apply{contentDescription="Очистить поиск";isClickable=true;isFocusable=true;setOnClickListener{input.setText("");input.requestFocus();showKeyboard()}}
 private var pending:Runnable?=null
 private val revealRect=Rect()
 private val reveal=LiquidSpring{value,_->val f=value.coerceIn(0f,1f);field.alpha=f;field.pivotX=field.width.toFloat();field.scaleX=.94f+.06f*f;revealRect.set((field.width*(1-f)).toInt(),0,field.width,field.height);field.clipBounds=if(f>=.999f)null else revealRect;field.visibility=if(f<=.001f&&!a.searchOpen)View.INVISIBLE else View.VISIBLE}
 private var onToggleChanged:(()->Unit)?=null
 init {
  orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;clipChildren=false;clipToPadding=false
  addView(a.icon("user","Профиль"){a.profile()},LayoutParams(a.d(44),a.d(44)))
  addView(center,LayoutParams(0,a.d(44),1f).apply{leftMargin=a.d(12);rightMargin=a.d(12)})
  field.addView(input,FrameLayout.LayoutParams(-1,-1));field.addView(clear,FrameLayout.LayoutParams(a.d(36),a.d(44),Gravity.END));center.addView(field,FrameLayout.LayoutParams(-1,-1,Gravity.END))
  val controls=a.line();addView(controls,LayoutParams(-2,a.d(44)))
  fun paintToggle(){toggleButton?.let{controls.removeView(it)};val open=a.searchOpen||a.query.isNotEmpty();val v=a.icon(if(open)"x" else "search",if(open)"Закрыть поиск" else "Поиск",open){toggle()};toggleButton=v;controls.addView(v,0,LayoutParams(a.d(44),a.d(44)))}
  paintToggle();controls.addView(View(a),LayoutParams(a.d(8),1));controls.addView(a.icon("filter","Фильтры и сортировка"){a.filters()},LayoutParams(a.d(44),a.d(44)))
  a.searchOpen=a.searchOpen||a.query.isNotEmpty();field.visibility=if(a.searchOpen)View.VISIBLE else View.INVISIBLE;clear.visibility=if(a.query.isEmpty())View.GONE else View.VISIBLE
  input.addTextChangedListener(object:TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){clear.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE;pending?.let{removeCallbacks(it)};pending=Runnable{if(a.librarySearch===this@LibrarySearchBar&&a.searchOpen){a.query=input.text.toString();a.renderLibraryResults();a.scroll.scrollTo(0,0)}}.also{postDelayed(it,120)}};override fun afterTextChanged(e:Editable?) {}})
  input.setOnEditorActionListener{_,action,_->if(action==EditorInfo.IME_ACTION_SEARCH){pending?.let{removeCallbacks(it)};a.query=input.text.toString();a.renderLibraryResults();hideKeyboard();true}else false}
  input.setOnKeyListener{_,key,event->if(key==KeyEvent.KEYCODE_ESCAPE&&event.action==KeyEvent.ACTION_UP){close();true}else false}
  onToggleChanged={paintToggle()};reveal.snap(if(a.searchOpen)1f else 0f)
 }
 fun toggle(){if(a.searchOpen)close()else open()}
 fun open(){a.searchOpen=true;field.visibility=View.VISIBLE;onToggleChanged?.invoke();a.scroll.scrollTo(0,0);input.requestFocus();input.setSelection(input.length());if(a.prefs.str("motion")=="reduced")reveal.snap(1f)else reveal.to(1f);showKeyboard()}
 fun close(refresh:Boolean=true){pending?.let{removeCallbacks(it)};a.searchOpen=false;a.query="";hideKeyboard();input.clearFocus();input.setText("");pending?.let{removeCallbacks(it)};onToggleChanged?.invoke();if(a.prefs.str("motion")=="reduced")reveal.snap(0f)else reveal.to(0f);if(refresh)a.renderLibraryResults()}

 private fun showKeyboard(){input.post{if(a.searchOpen&&isAttachedToWindow){input.requestFocus();(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);WindowCompat.getInsetsController(a.window,input).show(WindowInsetsCompat.Type.ime())}}}
 private fun hideKeyboard(){(a.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0);WindowCompat.getInsetsController(a.window,input).hide(WindowInsetsCompat.Type.ime())}
 override fun onWindowFocusChanged(hasWindowFocus:Boolean){super.onWindowFocusChanged(hasWindowFocus);if(hasWindowFocus&&a.searchOpen&&input.hasFocus())showKeyboard()}
 override fun onDetachedFromWindow(){pending?.let{removeCallbacks(it)};reveal.stop();super.onDetachedFromWindow()}
}
