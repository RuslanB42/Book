package app.polka.nativeapp

import android.animation.*
import android.graphics.*
import android.view.*
import android.view.animation.PathInterpolator
import android.widget.*
import org.json.*

/** Source book-sheet v3, in the activity window: no duplicate dialog/header or window handoff. */
class BookDetailSheet(private val a:MainActivity,private val book:JSONObject,private val source:View?):FrameLayout(a){
 private val scrim=View(a).apply{setBackgroundColor(0x80000000.toInt());setOnClickListener{dismiss()}}
 private val panel=DetailPanel().apply{background=a.shape(if(a.light)0xfff7f7f5.toInt() else 0xff1c1c1e.toInt(),22).apply{val r=a.d(22).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)};clipToOutline=true}
 private val scroller=ScrollView(a).apply{isFillViewport=false;clipChildren=false}
 private val content=a.column().apply{clipChildren=false;clipToPadding=false;setPadding(0,0,0,a.d(28))}
 private lateinit var heroCover:CoverView
 private lateinit var heroGlow:BookGlowView
 private val updates=mutableListOf<()->Unit>()
 private var clock:ValueAnimator?=null;private var closing=false;private var ended=false;private var begun=false
 private var flight:CoverView?=null;private var from=RectF();private var to=RectF();private var base=1f
 private val curve=PathInterpolator(.2f,.75f,.25f,1f)
 private var spent=0L;private var readSpeed=190.0
 val isShowing get()=parent!=null&&!ended
 val panelView:View get()=panel
 private fun reduced()=a.prefs.str("motion")=="reduced"
 init{a.controlSurface(panel,22f,sheet=true).apply{flushBottom=true;refresh()};clipChildren=false;clipToPadding=false;elevation=a.d(40).toFloat();isClickable=true;isFocusableInTouchMode=true;contentDescription="О книге: ${book.str("title")}";addView(scrim,LayoutParams(-1,-1));addView(panel,LayoutParams(-1,-1,Gravity.BOTTOM).apply{topMargin=a.d(48)})
  panel.addView(scroller,LayoutParams(-1,-1));scroller.addView(content);panel.addView(DetailTopFade(a),LayoutParams(-1,a.d(DetailTopFade.DEPTH_DP),Gravity.TOP));build()
  val handle=FrameLayout(a);handle.addView(View(a).apply{background=a.shape(if(a.light)0x55000000 else 0x73ffffff,3)},LayoutParams(a.d(38),a.d(5),Gravity.CENTER));panel.addView(handle,LayoutParams(-1,a.d(18),Gravity.TOP))

 }
 private fun live(v:TextView,fn:()->String){updates.add{v.text=fn()};v.text=fn()}
 private fun build(){
  // Floating controls, not an opaque full-width toolbar. The panel outline is the
  // only clipping boundary; content and halo continue behind controls to its real top edge.
  val top=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(12),a.d(18),a.d(12),0)}
  top.addView(a.icon("chevdown","Закрыть"){dismiss()},LinearLayout.LayoutParams(a.d(40),a.d(40)))
  top.addView(View(a),LinearLayout.LayoutParams(0,-1,1f))
  top.addView(a.icon("more","Ещё"){a.bookActions(book)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
  panel.addView(top,LayoutParams(-1,a.d(68),Gravity.TOP))
  a.space(content,78)
  val hero=FrameLayout(a).apply{clipChildren=false;clipToPadding=false};heroCover=CoverView(a,book,a.cover(book)).apply{softShadow=true}
  heroGlow=BookGlowView(a,heroCover).apply{alpha=0f};hero.addView(heroGlow,heroGlow.haloLayout(a.d(132),a.d(198),a.d(8)))
  hero.addView(heroCover,LayoutParams(a.d(132),a.d(198),Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=a.d(8)})
  content.addView(hero,LinearLayout.LayoutParams(-1,a.d(234)))
  val main=a.column().apply{setPadding(a.d(20),0,a.d(20),0);clipChildren=false;clipToPadding=false};content.addView(main)
  if(book.optBoolean("sample")){main.addView(a.text("КНИГА-ПРИМЕР",10f,a.accent,true).apply{gravity=Gravity.CENTER;letterSpacing=.12f});a.space(main,7)}
  val title=a.text("",25f,bold=true,serif=true).apply{gravity=Gravity.CENTER;cssLeading(1.15f)};live(title){book.str("title")};main.addView(title)
  a.space(main,8);val author=a.text("",14f,a.muted).apply{gravity=Gravity.CENTER};live(author){MainActivity.authors(book).ifBlank{"Автор не указан"}};main.addView(author)
  val series=a.text("",12f,a.muted).apply{gravity=Gravity.CENTER;setPadding(0,a.d(7),0,0)};main.addView(series);updates.add{series.text=book.str("series");series.visibility=if(series.text.isBlank())View.GONE else View.VISIBLE}
  a.space(main,24)
  fun insetCard()=a.column().apply{a.floatCard(this,20);setPadding(a.d(16),a.d(16),a.d(16),a.d(16))}
  fun divider(parent:LinearLayout){parent.addView(View(a).apply{setBackgroundColor(hair())},LinearLayout.LayoutParams(-1,1).apply{topMargin=a.d(14);bottomMargin=a.d(14)})}
  val reading=insetCard();main.addView(reading)
  val progressRow=a.line();val status=a.text("",14f,bold=true);val percent=a.text("",13f,a.muted);progressRow.addView(status,LinearLayout.LayoutParams(0,-2,1f));progressRow.addView(percent);reading.addView(progressRow)
  val chapter=a.text("",13f,a.muted).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,a.d(6),0,0)};reading.addView(chapter)
  val track=FrameLayout(a).apply{background=a.shape(if(a.light)0xffe2e2df.toInt()else 0xff39393c.toInt(),2)};val bar=View(a).apply{background=a.shape(a.fg,2);pivotX=0f};track.addView(bar,LayoutParams(-1,-1));reading.addView(track,LinearLayout.LayoutParams(-1,a.d(3)).apply{topMargin=a.d(14)})
  val facts=a.line();val volume=a.text("",12f,a.muted);val time=a.text("",12f,a.muted).apply{gravity=Gravity.END};facts.addView(volume,LinearLayout.LayoutParams(0,-2,1f));facts.addView(time,LinearLayout.LayoutParams(0,-2,1f));reading.addView(facts,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(10)})
  val go=a.button("Читать",true){if(book.optBoolean("noFile")){a.attachId=book.str("id");a.pickBooks(14)}else{a.returnDetailId=book.str("id");a.openBook(book,heroCover)}};reading.addView(go,LinearLayout.LayoutParams(-1,a.d(48)).apply{topMargin=a.d(16)})
  val nofile=a.text("Добавьте файл FB2 или EPUB, чтобы читать книгу и сохранять цитаты.",13f,a.muted).apply{cssLeading(1.5f);setPadding(0,a.d(10),0,0)};reading.addView(nofile)
  var chapterTitle=""
  updates.add{val p=pct();val missing=book.optBoolean("noFile");status.text=when{missing->"В вашей библиотеке";p>=1->"Книга прочитана";Library.status(book)=="reading"->"Сейчас читаете";else->"Начните новую историю"};percent.text=if(missing||p<=0)"" else "${(p*100).toInt()}%";chapter.text=chapterTitle;chapter.visibility=if(chapterTitle.isBlank()||missing||p>=1)View.GONE else View.VISIBLE;track.visibility=if(missing||p>=1)View.GONE else View.VISIBLE;bar.scaleX=p;go.text=when{missing->"Добавить файл книги";p>=1->"Читать снова";Library.status(book)=="reading"->"Продолжить чтение";else->"Читать"};nofile.visibility=if(missing)View.VISIBLE else View.GONE
   val chars=book.optInt("chars",book.optInt("recPages")*1800);volume.text=if(chars>0)"${maxOf(1,kotlin.math.round(chars/1800.0).toInt())} стр." else "Объём уточняется";time.text=if(p>=1)"За чтением · ${duration(spent)}" else if(chars>0)"≈ ${duration(maxOf(60000L,(chars*(1-p)/5.0/readSpeed*60000).toLong()))}" else ""}
  val shelfRow=a.line().apply{gravity=Gravity.CENTER_VERTICAL;setPadding(a.d(4),a.d(12),a.d(4),a.d(12));minimumHeight=a.d(52);setOnClickListener{a.moveBook(book)};isFocusable=true;contentDescription="Сменить полку"};shelfRow.addView(IconView(a,"shelves",a.muted),LinearLayout.LayoutParams(a.d(22),a.d(22)));val shelf=a.text("",14f).apply{setPadding(a.d(10),0,a.d(12),0)};live(shelf){book.str("shelf").ifBlank{"Без полки"}};shelfRow.addView(shelf,LinearLayout.LayoutParams(0,-2,1f));shelfRow.addView(a.text("Изменить",12f,a.muted));main.addView(shelfRow)
  fun section(top:Int):LinearLayout{val sec=a.column().apply{setPadding(0,a.d(top),0,0);clipChildren=false;clipToPadding=false};main.addView(sec);return sec}
  val about=section(4);val aboutReveal=DetailReveal(a);about.addView(aboutReveal);val annotation=a.text("",16f,serif=true).apply{cssLeading(1.6f)};aboutReveal.body.addView(annotation);var expandedAbout=false;val more=a.text("Читать полностью",14f,a.accent).apply{minHeight=a.d(44);gravity=Gravity.CENTER_VERTICAL};about.addView(more)
  fun updateAbout(){val value=book.str("annotation");annotation.text=value.ifBlank{"Описания пока нет. Добавьте его через меню «⋯» → «Изменить данные»."};annotation.setTextColor(if(value.isBlank())a.muted else a.fg);annotation.maxLines=if(expandedAbout||value.length<=260)Int.MAX_VALUE else 6;annotation.ellipsize=android.text.TextUtils.TruncateAt.END;more.visibility=if(value.length>260)View.VISIBLE else View.GONE;more.text=if(expandedAbout)"Свернуть" else "Читать полностью"};more.contentDescription="Развернуть описание";more.setOnClickListener{expandedAbout=!expandedAbout;aboutReveal.change(true,false){updateAbout()};more.contentDescription=if(expandedAbout)"Свернуть описание" else "Развернуть описание"};updates.add{updateAbout()}
  // No heading and no «Показать»: the pile itself is the control. Tapping it fans the cards out of the pile.
  val quoteSection=section(18)
  val quoteReveal=DetailReveal(a);val quoteBody=quoteReveal.body.apply{clipChildren=false;clipToPadding=false};quoteSection.addView(quoteReveal);var quoteKey="";var quotesOpen=false;var quotePage=0
  val ease=android.view.animation.PathInterpolator(.2f,1f,.3f,1f)
  fun reducedMotion()=a.prefs.str("motion")=="reduced"
  var collapseQuotes:(List<View>)->Unit={}
  var folding=false
  fun renderQuotes(animated:Boolean=false,unfold:Boolean=false,settle:Boolean=false){
   folding=false;val cards=mutableListOf<View>();val extras=mutableListOf<View>()
   val fan=unfold&&animated&&!reducedMotion()
   quoteReveal.change(animated,dissolveInk=!unfold&&!settle,crossfadeInk=fan||settle,duration=if(fan)580L else if(settle)200L else 360L,clip=!(fan||settle),curve=if(fan)android.view.animation.PathInterpolator(.32f,.06f,.16f,1f) else MotionSpec.settle){
   quoteBody.removeAllViews()
   val quotes=book.optJSONArray("quotes")?.objects().orEmpty().sortedWith(compareBy<JSONObject>{it.optInt("ch")}.thenBy{it.optInt("start")})
   quoteSection.visibility=if(quotes.isEmpty())View.GONE else View.VISIBLE
   if(quotes.isEmpty()){quotesOpen=false;return@change}
   if(!quotesOpen){quoteBody.addView(a.quoteStack(book,quotes){quotesOpen=true;renderQuotes(true,true)}.apply{contentDescription="Показать цитаты"});return@change}
   val pages=maxOf(1,(quotes.size+2)/3);quotePage=quotePage.coerceIn(0,pages-1)
   quotes.drop(quotePage*3).take(3).forEach{quote->
    val open={if(book.optBoolean("noFile")){a.attachId=book.str("id");a.pickBooks(14)}else{val p=book.optJSONObject("progress")?.copyJson()?:JSONObject();p.put("ch",quote.optInt("ch")).put("nativeOffset",maxOf(0,quote.optInt("start"))).remove("nativeFraction");a.returnDetailId=book.str("id");a.openBook(book.copyJson().put("progress",p),heroCover)}}
    val card=a.quoteCardNative(book,quote,open).apply{isFocusable=true;contentDescription="Открыть цитату: ${quote.str("text")}";setOnClickListener{open()}}
    quoteBody.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(14)});cards.add(card)
   }
   val controls=a.column()
   fun round(icon:String,label:String,turn:Float,enabled:Boolean,fn:()->Unit)=a.icon(icon,label){fn()}.apply{background=a.shape(if(a.light)0xffefefec.toInt() else 0xff2c2c2f.toInt(),20);rotation=turn;isEnabled=enabled;alpha=if(enabled)1f else .35f}
   if(pages>1){val pager=a.line().apply{gravity=Gravity.CENTER_VERTICAL}
    pager.addView(round("back","Предыдущие цитаты",0f,quotePage>0){quotePage--;renderQuotes(true)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
    pager.addView(a.text("${quotePage+1} из $pages",13f,a.muted).apply{gravity=Gravity.CENTER;contentDescription="Страница цитат ${quotePage+1} из $pages"},LinearLayout.LayoutParams(0,-2,1f))
    pager.addView(round("back","Следующие цитаты",180f,quotePage<pages-1){quotePage++;renderQuotes(true)},LinearLayout.LayoutParams(a.d(40),a.d(40)))
    controls.addView(pager,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=a.d(12)})}
   // Full width, like «Читать» and the plaques above it.
   val fold=a.button("Свернуть"){collapseQuotes(cards)}.apply{contentDescription="Свернуть цитаты"}
   controls.addView(fold,LinearLayout.LayoutParams(-1,a.d(48)))
   quoteBody.addView(controls,LinearLayout.LayoutParams(-1,-2).apply{topMargin=a.d(2)});extras.add(controls)
   }
   if(fan&&cards.isNotEmpty()){
    // Fan-out in lockstep with the growing block: every frame (after layout, before draw) each card is placed
    // between its spot in the pile (narrower, a few dp lower, behind the first) and its final spot.
    val pile=a.d(9).toFloat();val inset=a.d(12).toFloat()
    cards.forEach{it.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
    val driver=object:android.view.ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{
     val p=quoteReveal.progress
     cards.forEachIndexed{i,c->if(c.width>0){val k=minOf(i,2);val t=((p-i*.07f)/(1f-.14f)).coerceIn(0f,1f)
      c.pivotX=c.width/2f;c.pivotY=0f;c.translationY=(-c.top+pile*k)*(1f-t);val sx=(c.width-2*inset*k)/c.width;c.scaleX=sx+(1f-sx)*t;c.translationZ=-a.d(3)*k*(1f-t);c.alpha=if(i<3)1f else t}}
     extras.forEach{e->val t=((p-.55f)/.45f).coerceIn(0f,1f);e.alpha=t;e.translationY=a.d(10)*(1f-t)}
     if(p>=1f){cards.forEach{it.setLayerType(View.LAYER_TYPE_NONE,null)};if(quoteBody.viewTreeObserver.isAlive)quoteBody.viewTreeObserver.removeOnPreDrawListener(this)}
     return true}}
    quoteBody.viewTreeObserver.addOnPreDrawListener(driver)
   }
  }
  // Folding back: the cards slide up into the pile and fade, the block shrinks to the pile's height (content never spills
  // over the plaques below), and a picture of the pile fades in on top; at the end the real pile replaces its picture.
  fun collapseQuotesImpl(cards:List<View>){
   if(folding)return
   val quotes=book.optJSONArray("quotes")?.objects().orEmpty()
   if(reducedMotion()||cards.isEmpty()||quotes.isEmpty()||!quoteReveal.isLaidOut||quoteBody.width<=0){quotesOpen=false;quotePage=0;renderQuotes(true);return}
   val w=quoteBody.width;val probe=a.quoteStack(book,quotes){};probe.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));probe.layout(0,0,w,probe.measuredHeight)
   val picture=android.graphics.Bitmap.createBitmap(w,probe.measuredHeight.coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888);probe.draw(android.graphics.Canvas(picture))
   val pile=a.d(9).toFloat();val inset=a.d(12).toFloat();val extras=(0 until quoteBody.childCount).map{quoteBody.getChildAt(it)}.filter{it !in cards}
   folding=true;cards.forEach{it.animate().cancel();it.setLayerType(View.LAYER_TYPE_HARDWARE,null)}
   quoteReveal.overlay=picture;quoteReveal.overlayAlpha=0f
   quoteReveal.animateHeight(probe.measuredHeight,440L,android.view.animation.PathInterpolator(.33f,0f,.2f,1f),{p->
    cards.forEachIndexed{i,c->if(c.width>0){val k=minOf(i,2);val back=cards.size-1-i;val q=((p-back*.05f)/(1f-.1f)).coerceIn(0f,1f)
     c.pivotX=c.width/2f;c.pivotY=0f;c.translationY=(-c.top+pile*k)*q;val sx=(c.width-2*inset*k)/c.width;c.scaleX=1f+(sx-1f)*q;c.translationZ=-a.d(3)*k*q
     c.alpha=1f-((q-.55f)/.45f).coerceIn(0f,1f)}}
    extras.forEach{e->e.alpha=(1f-p/.3f).coerceIn(0f,1f)}
    quoteReveal.overlayAlpha=((p-.45f)/.5f).coerceIn(0f,1f)
   }){cards.forEach{it.setLayerType(View.LAYER_TYPE_NONE,null)};quotesOpen=false;quotePage=0;renderQuotes()}
  }
  collapseQuotes={cards->collapseQuotesImpl(cards)}
  updates.add{val key=(book.optJSONArray("quotes")?.toString()?:"[]")+(book.optJSONArray("notes")?.toString()?:"[]");if(key!=quoteKey){quoteKey=key;renderQuotes()}}
  val feedback=section(18);val feedbackBody=insetCard();feedback.addView(feedbackBody);var feedbackKey=""
  updates.add{val key="${book.optInt("rating")}";if(key!=feedbackKey){feedbackKey=key;feedbackBody.removeAllViews();feedbackBody.addView(a.text("Ваша оценка",12f,a.muted));feedbackBody.addView(a.ratingStars(book){n->book.put("rating",n);feedbackKey="$n";a.saveBook(book){}})}}
  val info=section(18);val infoBody=insetCard();info.addView(infoBody);var infoKey=""
  updates.add{val rows=mutableListOf<Pair<String,String>>();if(book.str("series").isNotBlank())rows.add("Серия" to (book.str("series")+if(book.optDouble("seriesNo",0.0)>0)" · ${book.str("seriesNo")}" else ""));book.optJSONArray("genres")?.strings()?.takeIf{it.isNotEmpty()}?.let{rows.add("Жанры" to it.joinToString(", "))};if(book.optInt("year")!=0)rows.add("Год" to book.optInt("year").toString());if(book.str("lang").isNotBlank())rows.add("Язык" to when(book.str("lang")){"ru"->"Русский";"en"->"Английский";else->book.str("lang").uppercase()});rows.add("Файл" to if(book.optBoolean("noFile"))"Ещё не добавлен" else book.str("format").uppercase()+if(book.optLong("size")>0)" · ${book.optLong("size")/1024} КБ" else "");if(book.optLong("addedAt")>0)rows.add("Добавлена" to java.text.SimpleDateFormat("d MMM yyyy",java.util.Locale("ru")).format(java.util.Date(book.optLong("addedAt"))));if(book.optInt("edits")>0)rows.add("Правки текста" to "${book.optInt("edits")} сохранений");val key=rows.toString();if(key!=infoKey){infoKey=key;infoBody.removeAllViews();rows.forEachIndexed{i,(label,value)->if(i>0)divider(infoBody);val row=a.line();row.addView(a.text(label,13f,a.muted),LinearLayout.LayoutParams(0,-2,.35f));row.addView(a.text(value,14f).apply{gravity=Gravity.END;setPadding(a.d(12),0,0,0)},LinearLayout.LayoutParams(0,-2,.65f));infoBody.addView(row)}}}
  a.followInsets(this){i->panel.layoutParams=(panel.layoutParams as LayoutParams).apply{topMargin=a.d(48)+i.top};content.setPadding(0,0,0,a.d(28)+i.bottom);top.setPadding(a.d(12)+i.left,a.d(18),a.d(12)+i.right,0);main.setPadding(a.d(20)+i.left,0,a.d(20)+i.right,0)}
  a.prefetchContent(book)
  updates.forEach{it()};a.observeBook(this,book.str("id")){fresh->val coverChanged=book.optJSONObject("cover")?.toString()!=fresh.optJSONObject("cover")?.toString()
   // Sections that appear, disappear or change height move smoothly instead of snapping.
   if(!reduced()&&isShowing&&!closing)android.transition.TransitionManager.beginDelayedTransition(content,android.transition.AutoTransition().apply{duration=360;interpolator=PathInterpolator(.2f,0f,0f,1f);ordering=android.transition.TransitionSet.ORDERING_TOGETHER})
   replaceJson(book,fresh);updates.forEach{it()}
   if(coverChanged&&!reduced()&&isShowing){heroCover.animate().cancel();heroCover.animate().alpha(.25f).scaleX(.96f).scaleY(.96f).setDuration(140).withEndAction{heroCover.invalidate();heroGlow.refresh();heroCover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(380).setInterpolator(android.view.animation.OvershootInterpolator(1.4f)).start()}.start()}
   else{heroCover.invalidate();heroGlow.refresh()}}
  val id=book.str("id");val chIndex=book.optJSONObject("progress")?.optInt("ch")?:0;var detailStats=Triple(0L,190.0,Pair(0,""))
  a.work({val stats=a.library.dao.meta("stats")?.let{JSONObject(it.json).optJSONObject("data")};val total=stats?.optJSONObject("books")?.optJSONObject(id)?.optLong("ms")?:0;val days=stats?.optJSONObject("days");var ms=0L;var chars=0L;days?.keys()?.forEach{k->val d=days.optJSONObject(k);ms+=d?.optLong("ms")?:0;chars+=d?.optLong("chars")?:0};val speed=if(ms>0)chars/5.0/(ms/60000.0)else 190.0;val chapters=a.chapterIndex(book);detailStats=Triple(total,if(speed in 60.0..900.0)speed else 190.0,Pair(chapters?.objects()?.sumOf{it.optInt("len")}?:0,chapters?.optJSONObject(chIndex)?.str("title").orEmpty()))}){if(isShowing){spent=detailStats.first;readSpeed=detailStats.second;if(detailStats.third.first>0)book.put("chars",detailStats.third.first);chapterTitle=detailStats.third.second;updates.forEach{it()}}}
 }
 // Draw the shared cover explicitly in the root overlay coordinate space.
 // A transformed child inside a bounded saveLayer can be cropped by RenderNode
 // clipping/reordering on hardware even when clipChildren=false. No alpha layer,
 // no panel clip and no DST/SRC compositing layer is needed for this overlay.
 private val flightFadeMatrix=Matrix()
 private val flightFadePaint by lazy{Paint(Paint.ANTI_ALIAS_FLAG).apply{shader=DetailTopFade.shader(a.d(DetailTopFade.DEPTH_DP).toFloat(),if(a.light)0xfff7f7f5.toInt()else 0xff1c1c1e.toInt())}}
 override fun drawChild(c:Canvas,child:View,drawingTime:Long):Boolean = if(child===flight)true else super.drawChild(c,child,drawingTime)
 override fun dispatchDraw(c:Canvas){
  super.dispatchDraw(c)
  val v=flight?.takeIf{it.visibility==View.VISIBLE}?:return
  val save=c.save();c.translate(v.x,v.y);c.scale(v.scaleX,v.scaleY);v.draw(c);c.restoreToCount(save)
  val amount=landingLight(presentation)
  if(amount>0f){
   val bounds=RectF(v.x,v.y,v.x+v.width*v.scaleX,v.y+v.height*v.scaleY)
   val clip=c.save();val radius=bounds.width()*.012f;c.clipPath(Path().apply{addRoundRect(bounds,radius,radius,Path.Direction.CW)})
   // Anchor the fade to where the cover will REST in the sheet, not to the still-rising panel: mid-flight the cover must not pass through the dense top of the gradient.
   flightFadeMatrix.setTranslate(0f,panel.top+(v.y-to.top));flightFadePaint.shader.setLocalMatrix(flightFadeMatrix);flightFadePaint.alpha=(amount*255).toInt();c.drawRect(bounds,flightFadePaint);c.restoreToCount(clip)
  }
 }
 private fun pct()=(book.optJSONObject("progress")?.optDouble("pct",0.0)?:0.0).toFloat().coerceIn(0f,1f)
 private fun hair()=if(a.light)0x18161617 else 0x18ffffff
 private fun hairlines()=object:android.graphics.drawable.Drawable(){val p=Paint().apply{color=hair()};override fun draw(c:Canvas){c.drawRect(0f,0f,bounds.width().toFloat(),1f,p);c.drawRect(0f,bounds.height()-1f,bounds.width().toFloat(),bounds.height().toFloat(),p)};override fun setAlpha(x:Int){};override fun setColorFilter(x:ColorFilter?){};@Deprecated("Drawable")override fun getOpacity()=PixelFormat.TRANSLUCENT}
 private fun duration(ms:Long):String{if(ms<=0)return "—";val m=maxOf(1,ms/60000);return if(m<60)"$m мин" else "${m/60} ч"+if(m%60>0)" ${m%60} мин" else ""}
 /** Shown already settled (returning from the reader under a fade): no flight, no slide. */
 var instant=false
 fun show(){a.bookDetailSheet?.finish();a.bookDetailSheet=this;panel.alpha=0f;scrim.alpha=0f;a.root.addView(this,LayoutParams(-1,-1));requestFocus();panel.viewTreeObserver.addOnPreDrawListener(object:ViewTreeObserver.OnPreDrawListener{override fun onPreDraw():Boolean{panel.viewTreeObserver.removeOnPreDrawListener(this);if(instant){begun=true;sampleMotion(460);heroGlow.prepare{if(!ended)sampleMotion(460)};return true};heroGlow.prepare{if(!ended&&!closing&&!begun){begun=true;setupFlight();if(reduced()){sampleMotion(460);clearFlight()}else animate(false)}};return true}})}
 private fun rect(v:View):RectF{val at=IntArray(2);val root=IntArray(2);v.getLocationOnScreen(at);getLocationOnScreen(root);return RectF((at[0]-root[0]).toFloat(),(at[1]-root[1]).toFloat(),(at[0]-root[0]+v.width).toFloat(),(at[1]-root[1]+v.height).toFloat())}
 private fun cover(v:View?):CoverView?{if(v is CoverView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)cover(v.getChildAt(i))?.let{return it};return null}
 private var originCover:CoverView?=null
 private fun setupFlight(){val src=cover(source);originCover=src;if(reduced()||src==null||!src.isAttachedToWindow||src.width<=0)return;from=rect(src);to=rect(heroCover).apply{offset(0f,-panel.translationY)};if(from.bottom<0||from.top>height||closing&&(to.bottom<0||to.top>height))return;base=maxOf(from.width(),to.width());val v=CoverView(a,book,a.cover(book)).apply{softShadow=src.softShadow;stackShadow=src.stackShadow};flight=v;addView(v,LayoutParams(base.toInt(),(base*1.5f).toInt()));v.measure(MeasureSpec.makeMeasureSpec(base.toInt(),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec((base*1.5f).toInt(),MeasureSpec.EXACTLY));v.layout(0,0,v.measuredWidth,v.measuredHeight);v.pivotX=0f;v.pivotY=0f;src.visibility=View.INVISIBLE;heroCover.visibility=View.INVISIBLE}
 // Exactly the same positional progress drives the cover and its attached light.
 // Broader landing window + cubic easing avoids concentrating brightness into a few frames.
 // No second timer: pausing/reversing the gesture preserves the identical light state.
 internal fun landingLight(q:Float):Float {val arrival=((q-.60f)/.40f).coerceIn(0f,1f);val t=arrival*arrival;return t*t*(3-2*t)}
 private fun wFor(q:Float)=from.width()+(to.width()-from.width())*q
 private var presentation=0f
 private fun pose(q0:Float){val q=q0.coerceIn(0f,1f);presentation=q;panel.alpha=1f;panel.translationY=panel.height*(1-q);scrim.alpha=q;heroGlow.alpha=landingLight(q)
  flight?.let{v->v.translationX=from.left+(to.left-from.left)*q;v.translationY=from.top+(to.top-from.top)*q;v.scaleX=(from.width()+(to.width()-from.width())*q)/base;v.scaleY=v.scaleX
   val heroRect=rect(heroCover);heroGlow.pivotX=heroCover.left-heroGlow.left+heroCover.width*.5f;heroGlow.pivotY=heroCover.top-heroGlow.top+heroCover.height*.5f;heroGlow.scaleX=wFor(q)/heroCover.width;heroGlow.scaleY=heroGlow.scaleX;heroGlow.translationX=v.translationX+wFor(q)*.5f-heroRect.centerX();heroGlow.translationY=v.translationY+wFor(q)*.75f-heroRect.centerY()
  }
  // Manual overlay transform must re-record the parent display list every frame.
  invalidate()
 }
 fun sampleMotion(elapsed:Long,returning:Boolean=closing){val t=curve.getInterpolation((elapsed/460f).coerceIn(0f,1f));pose(if(returning)1-t else t)}
 fun freezeMotion(){clock?.removeAllListeners();clock?.cancel();clock=null}
 private fun animate(back:Boolean){pose(if(back)1f else 0f);animateTo(if(back)0f else 1f)}
 private fun animateTo(target:Float){freezeMotion();val initial=presentation;clock=ValueAnimator.ofFloat(initial,target).apply{duration=(460*maxOf(.35f,kotlin.math.abs(target-initial))).toLong();interpolator=curve;addUpdateListener{pose(it.animatedValue as Float)};addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){if(target==0f)finish()else clearFlight()}});start()}}
 internal fun beginDrag(){if(ended||closing||!begun)return;freezeMotion();if(flight==null)setupFlight()}
 internal fun dragTo(offset:Float){if(!ended&&!closing&&begun)pose(1-offset.coerceAtLeast(0f)/maxOf(1,panel.height))}
 internal fun endDrag(commit:Boolean){if(ended||closing)return;if(commit)dismiss()else if(reduced()){pose(1f);clearFlight()}else animateTo(1f)}
 internal val motionProgress get()=presentation
 private inner class DetailPanel:FrameLayout(a){
  private var downX=0f;private var downY=0f;private var startY=0f;private var dragging=false;private var eligible=false;private var velocity:VelocityTracker?=null
  init{isClickable=true}
  override fun onInterceptTouchEvent(e:MotionEvent):Boolean{
   when(e.actionMasked){
    MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startY=translationY;dragging=false;eligible=e.y<a.d(68)||scroller.scrollY==0;velocity?.recycle();velocity=VelocityTracker.obtain().also{it.addMovement(e)}}
    MotionEvent.ACTION_MOVE->{velocity?.addMovement(e);val dy=e.rawY-downY;if(eligible&&!closing&&begun&&dy>ViewConfiguration.get(a).scaledTouchSlop&&dy>kotlin.math.abs(e.rawX-downX)){dragging=true;beginDrag();dragTo(startY+dy);parent?.requestDisallowInterceptTouchEvent(true);return true}}
    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.recycle();velocity=null}
   };return dragging
  }
  override fun onTouchEvent(e:MotionEvent):Boolean{
   velocity?.addMovement(e)
   when(e.actionMasked){
    MotionEvent.ACTION_MOVE->{val dy=e.rawY-downY;if(!dragging&&eligible&&!closing&&begun&&dy>ViewConfiguration.get(a).scaledTouchSlop&&dy>kotlin.math.abs(e.rawX-downX)){dragging=true;beginDrag();parent?.requestDisallowInterceptTouchEvent(true)};if(dragging)dragTo(startY+dy)}
    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{if(dragging){velocity?.computeCurrentVelocity(1000);val fast=(velocity?.yVelocity?:0f)>a.d(900);endDrag(e.actionMasked==MotionEvent.ACTION_UP&&(translationY>minOf(a.d(120).toFloat(),height*.22f)||fast))};dragging=false;velocity?.recycle();velocity=null}
   };return true
  }
 }
 private fun clearFlight(){heroGlow.alpha=1f;heroGlow.translationX=0f;heroGlow.translationY=0f;heroGlow.scaleX=1f;heroGlow.scaleY=1f;flight?.let{removeView(it)};flight=null;originCover?.visibility=View.VISIBLE;heroCover.visibility=View.VISIBLE}
 fun dismiss(){if(closing||ended)return;closing=true;freezeMotion();if(reduced()||!begun){finish();return};if(flight==null)setupFlight();animateTo(0f)}
 val bookId get()=book.str("id")
 /** The book is gone (deleted): no cover flight back to the grid; the sheet sinks away, the cover shrinks out with it. */
 fun discard(){if(closing||ended)return;closing=true;freezeMotion();clearFlight();if(reduced()){finish();return}
  val start=presentation;clock=ValueAnimator.ofFloat(0f,1f).apply{duration=380;interpolator=PathInterpolator(.4f,0f,.2f,1f)
   addUpdateListener{val t=it.animatedValue as Float;panel.translationY=panel.height*(1-start*(1-t));scrim.alpha=start*(1-t);heroCover.alpha=1f-t;heroCover.scaleX=1f-.2f*t;heroCover.scaleY=heroCover.scaleX}
   addListener(object:AnimatorListenerAdapter(){override fun onAnimationEnd(x:Animator){finish()}});start()}}
 fun finish(){if(ended)return;ended=true;freezeMotion();clearFlight();(parent as? ViewGroup)?.removeView(this);if(a.bookDetailSheet===this)a.bookDetailSheet=null;a.flushDeferredRender()}
 override fun onDetachedFromWindow(){freezeMotion();clearFlight();if(a.bookDetailSheet===this)a.bookDetailSheet=null;super.onDetachedFromWindow()}
}
