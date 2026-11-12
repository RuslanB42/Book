package app.polka.nativeapp

import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.*
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * «Прочитанные книги» — its own page over the statistics: the years you have read in, the count for the chosen
 * year, and the books grouped by month — cover, title, author, the day you finished it and your rating medal.
 * Slides in from the right on a spring; Back (button or gesture) slides it away.
 */
internal fun MainActivity.readBooksPage(model:ReadingStatistics,startYear:Int){
    readBooksOverlay?.let{return}
    val ru=Locale("ru");val motion=prefs.str("motion")!="reduced"
    val years=(model.finished.mapNotNull{it.date?.year}+model.today.year).distinct().sortedDescending()
    var year=if(startYear in years)startYear else years.first()
    val page=ScrollView(this).apply{isFillViewport=true;clipToPadding=true;elevation=d(24).toFloat()
        background=android.graphics.drawable.ColorDrawable(bg)
        isClickable=true}
    val content=column().apply{clipChildren=false;clipToPadding=false}
    page.addView(content)
    followInsets(content){i->page.setPadding(0,i.top,0,0);content.setPadding(d(16)+i.left,d(8),d(16)+i.right,d(40)+i.bottom)}
    val head=line().apply{gravity=Gravity.CENTER_VERTICAL}
    head.addView(icon("back","Назад"){closeReadBooks()},LinearLayout.LayoutParams(d(44),d(44)))
    head.addView(text("Прочитанные книги",17f,fg,true,serif=true).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f))
    head.addView(View(this),LinearLayout.LayoutParams(d(44),d(44)))
    content.addView(head);space(content,14)
    val chips=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;clipToPadding=false}
    val chipRow=line();chips.addView(chipRow);content.addView(chips,LinearLayout.LayoutParams(-1,-2));space(content,18)
    val body=column().apply{clipChildren=false;clipToPadding=false};content.addView(body,LinearLayout.LayoutParams(-1,-2))
    fun card()=column().apply{background=GlassCardDrawable(this@readBooksPage,d(24).toFloat());setPadding(d(16),d(16),d(16),d(16));elevation=d(if(light)6 else 10).toFloat()
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,d(24).toFloat())}}
        if(android.os.Build.VERSION.SDK_INT>=28){outlineAmbientShadowColor=if(light)0x1a000000 else 0x99000000.toInt();outlineSpotShadowColor=if(light)0x40000000 else 0xcc000000.toInt()}}
    fun row(b:JSONObject,date:LocalDate?):View{val r=line().apply{gravity=Gravity.CENTER_VERTICAL;minimumHeight=d(92);isClickable=true}
        val cv=CoverView(this,b,cover(b)).apply{softShadow=true};if(cover(b)==null&&b.optJSONObject("cover")!=null)requestCover(b){cv.invalidate()}
        r.addView(cv,LinearLayout.LayoutParams(d(52),d(78)))
        val m=column().apply{setPadding(d(14),0,d(8),0)}
        m.addView(text(b.str("title"),15.5f,fg,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END})
        MainActivity.authors(b).takeIf{it.isNotBlank()}?.let{m.addView(text(it,13f,muted).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END})}
        date?.let{space(m,6);m.addView(text("прочитана ${it.format(DateTimeFormatter.ofPattern("d MMMM",ru))}",12f,muted))}
        r.addView(m,LinearLayout.LayoutParams(0,-2,1f))
        val rating=b.optInt("rating");if(rating>0)r.addView(RatingMedal(this,rating))
        r.setOnClickListener{detail(b,cv)};press(r);return r}
    fun bind(animate:Boolean){
        chipRow.removeAllViews()
        years.forEach{y->val on=y==year
            chipRow.addView(text("$y",14f,if(on)(if(light)android.graphics.Color.WHITE else 0xff161617.toInt()) else fg,on).apply{gravity=Gravity.CENTER;setPadding(d(16),0,d(16),0);minHeight=d(36)
                background=if(on)shape(fg,18) else GradientDrawable().apply{setColor(if(light)0x0a000000 else 0x12ffffff);cornerRadius=d(18).toFloat();setStroke(d(1),if(light)0x14000000 else 0x1affffff)}
                isClickable=true;setOnClickListener{if(y!=year){year=y;bind(true)}};press(this)},LinearLayout.LayoutParams(-2,d(36)).apply{rightMargin=d(8)})}
        body.removeAllViews()
        val list=model.finished.filter{it.date?.year==year}.sortedByDescending{it.date}
        val sum=line().apply{gravity=Gravity.BOTTOM}
        sum.addView(text("${list.size}",44f,fg).apply{typeface=PolkaFonts.display();letterSpacing=-.01f;includeFontPadding=false;fontFeatureSettings="lnum, tnum"})
        sum.addView(text("  ${MainActivity.countBooks(list.size).substringAfter(' ')} за $year год",16f,muted).apply{setPadding(0,0,0,d(6))})
        body.addView(sum);space(body,16)
        if(list.isEmpty()){body.addView(text("В этом году пока ни одной прочитанной книги.",15f,muted));return}
        val views=ArrayList<View>()
        list.groupBy{it.date!!.month}.forEach{(month,items)->
            body.addView(text(month.getDisplayName(TextStyle.FULL_STANDALONE,ru).replaceFirstChar{it.titlecase()},13f,muted,true).apply{setPadding(d(6),0,0,d(8))})
            val c=card();items.forEachIndexed{i,f->if(i>0)c.addView(View(this).apply{setBackgroundColor(if(light)0x0f000000 else 0x12ffffff)},LinearLayout.LayoutParams(-1,d(1)).apply{topMargin=d(10);bottomMargin=d(10);leftMargin=d(66)})
                c.addView(row(f.book,f.date))}
            body.addView(c,LinearLayout.LayoutParams(-1,-2));space(body,18);views.add(c)}
        if(animate||motion){val sp=Spring.gentle;val delays=Stagger.delays(views.size,60,start=if(animate)0 else 140,ease=Ease.outQuad)
            if(motion)views.forEachIndexed{i,v->v.alpha=0f;v.translationY=d(12).toFloat();v.animate().alpha(1f).translationY(0f).setStartDelay(delays[i]).setDuration(400).setInterpolator(Ease.outCubic).start()}}
    }
    bind(false)
    root.addView(page,FrameLayout.LayoutParams(-1,-1));readBooksOverlay=page
    if(motion){page.translationX=resources.displayMetrics.widthPixels.toFloat();page.animate().translationX(0f).setDuration(360).setInterpolator(Ease.outQuart).start()}
}

internal fun MainActivity.closeReadBooks(){val page=readBooksOverlay?:return;readBooksOverlay=null
    if(prefs.str("motion")=="reduced"){root.removeView(page);return}
    page.animate().cancel();page.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(260).setInterpolator(Ease.inOutCubic).withEndAction{root.removeView(page)}.start()}
