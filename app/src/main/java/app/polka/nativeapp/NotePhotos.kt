package app.polka.nativeapp

import android.app.Dialog
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos in a note (approved variant А): a row of small squares under the words, «Фото» at its end (the camera or
 * the gallery). Each photo is kept in the library like a cover (an asset the quote points to), so it travels with
 * backups; it is made smaller first (1600 px at most, turned upright).
 */
internal object NotePhotos {
    const val MAX=10
    /** A photo of the note being written: one already kept ([id]) or one just taken ([bytes]). */
    class Photo(val id:String?,val bytes:ByteArray?)

    fun of(q:JSONObject):List<String> =q.optJSONArray("photos")?.let{arr->(0 until arr.length()).mapNotNull{arr.optJSONObject(it)?.str("__polkaBlob")?.takeIf{s->s.isNotBlank()}}}.orEmpty()
    fun put(q:JSONObject,ids:List<String>){if(ids.isEmpty())q.remove("photos") else q.put("photos",JSONArray(ids.map{JSONObject().put("__polkaBlob",it)}))}

    /** The picture made fit to keep: upright, at most 1600 px on its long side, JPEG. */
    fun prepare(raw:ByteArray):ByteArray?{
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(raw,0,raw.size,bounds)
        if(bounds.outWidth<=0||bounds.outHeight<=0)return null
        var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/(sample*2)>=1600)sample*=2
        var bmp=BitmapFactory.decodeByteArray(raw,0,raw.size,BitmapFactory.Options().apply{inSampleSize=sample})?:return null
        val turn=try{when(android.media.ExifInterface(ByteArrayInputStream(raw)).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1)){6->90f;3->180f;8->270f;else->0f}}catch(_:Exception){0f}
        val k=minOf(1f,1600f/maxOf(bmp.width,bmp.height))
        if(turn!=0f||k<1f){val m=Matrix().apply{postScale(k,k);postRotate(turn)};val out=Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,m,true);if(out!==bmp)bmp.recycle();bmp=out}
        return ByteArrayOutputStream().use{bmp.compress(Bitmap.CompressFormat.JPEG,86,it);bmp.recycle();it.toByteArray()}
    }

    private val cache=LruCache<String,Bitmap>(24)
    private fun decode(bytes:ByteArray,size:Int):Bitmap?{
        val o=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeByteArray(bytes,0,bytes.size,o)
        var s=1;while(minOf(o.outWidth,o.outHeight)/(s*2)>=size)s*=2
        return BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=s})}
    /** The kept photo [id] at about [size] px (its shorter side), read off the main thread. */
    fun load(a:MainActivity,id:String,size:Int,done:(Bitmap?)->Unit){val key="$id/$size";cache.get(key)?.let{done(it);return}
        var bmp:Bitmap?=null;a.work({bmp=a.library.dao.asset(id)?.bytes?.let{decode(it,size)}}){bmp?.let{cache.put(key,it)};done(bmp)}}
    fun preview(p:Photo,size:Int):Bitmap?=p.bytes?.let{decode(it,size)}

    // ───── taking photos ─────
    /** Asks: the camera or the gallery; [done] gets the pictures, made fit to keep. */
    fun pick(a:MainActivity,room:Int,done:(List<ByteArray>)->Unit){if(room<=0)return
        a.dialog().setTitle("Фото").setItemIcons("camera","image").setItems(arrayOf("Снять","Из галереи")){_,i->
            a.notePhotosPicked=done
            if(i==0){val f=File(File(a.cacheDir,"camera").apply{mkdirs()},"note-${uid()}.jpg");a.cameraFile=f
                val uri=FileProvider.getUriForFile(a,"${a.packageName}.calendar",f)
                try{a.startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT,uri).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION),22)}
                catch(_:Exception){Notice.short(a,Notice.Kind.ERROR,"Камера недоступна")}}
            else a.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{type="image/*";addCategory(Intent.CATEGORY_OPENABLE);putExtra(Intent.EXTRA_ALLOW_MULTIPLE,room>1)},21)}.show()}
    /** The pictures came back (from [MainActivity.onActivityResult]): read and made fit to keep off the main thread. */
    fun received(a:MainActivity,read:()->List<ByteArray>){val done=a.notePhotosPicked?:return;a.notePhotosPicked=null
        var out=listOf<ByteArray>();a.work({out=read().mapNotNull{prepare(it)}}){if(out.isEmpty())Notice.short(a,Notice.Kind.ERROR,"Не удалось открыть изображение") else done(out)}}

    // ───── looking at them ─────
    /** The photos large, one screen each, swiped sideways; a tap or «Назад» closes them. */
    fun show(a:MainActivity,ids:List<String>,start:Int){if(ids.isEmpty())return
        val d=Dialog(a,R.style.PolkaBlackFull)
        val root=FrameLayout(a).apply{setBackgroundColor(Color.BLACK)}
        val row=LinearLayout(a)
        val strip=object:HorizontalScrollView(a){
            private var downX=0f
            override fun onTouchEvent(e:MotionEvent):Boolean{if(e.actionMasked==MotionEvent.ACTION_DOWN)downX=e.x
                if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL){super.onTouchEvent(e)
                    val w=width.coerceAtLeast(1);val page=(scrollX.toFloat()/w).let{p->val base=Math.round(p);if(e.x-downX<-w*.12f)kotlin.math.ceil(p).toInt() else if(e.x-downX>w*.12f)kotlin.math.floor(p).toInt() else base}
                    smoothScrollTo(page.coerceIn(0,ids.size-1)*w,0);return true}
                return super.onTouchEvent(e)}
            override fun fling(velocityX:Int){}}.apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER;addView(row)}
        root.addView(strip,FrameLayout.LayoutParams(-1,-1))
        val w=a.resources.displayMetrics.widthPixels
        ids.forEach{id->val img=ImageView(a).apply{scaleType=ImageView.ScaleType.FIT_CENTER;setOnClickListener{d.dismiss()};contentDescription="Фото"}
            row.addView(img,LinearLayout.LayoutParams(w,-1));load(a,id,1600){b->img.setImageBitmap(b)}}
        d.setContentView(root);d.show()
        strip.post{strip.scrollTo(start.coerceIn(0,ids.size-1)*strip.width,0)}}

    /** A small square photo with rounded corners: [bmp] once it is there. */
    fun tile(a:MainActivity,size:Int,radius:Int):ImageView=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP
        background=GradientDrawable().apply{setColor(Ui.fill(a.light));cornerRadius=radius.toFloat()}
        outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:Outline){o.setRoundRect(0,0,v.width,v.height,radius.toFloat())}};clipToOutline=true
        minimumWidth=size;minimumHeight=size}
}
