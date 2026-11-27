package app.polka.nativeapp

/**
 * A failure in plain words (approved «Понятные ошибки»): what did not work, why, and what to do. Chosen by the kind
 * of failure, never by the system's message, which stays under «Подробности». The app's own messages, already in
 * Russian («Шрифт повреждён — его не удалось открыть»), are only split into a title and its explanation.
 */
internal object ErrorText {
    class Text(val title:String,val text:String)

    fun of(e:Throwable):Text{
        val own=e.message?.takeIf{m->m.any{it in 'а'..'я'||it in 'А'..'Я'}}
        if(own!=null&&(e is IllegalArgumentException||e is IllegalStateException)){
            val parts=own.split(" — ",limit=2)
            return if(parts.size==2)Text(parts[0],parts[1].replaceFirstChar{it.uppercase()}.let{if(it.endsWith('.'))it else "$it."})
                else Text(own,when{own.contains("PDF")->"Снимите пароль в другом приложении и добавьте файл снова.";else->""})}
        val chain=generateSequence(e){it.cause}.take(6).toList()
        fun any(test:(Throwable)->Boolean)=chain.any(test)
        val text=chain.joinToString(" "){it.message.orEmpty()}
        return when{
            any{it is OutOfMemoryError}->Text("Книга слишком большая","Телефону не хватило памяти, чтобы её открыть. Закройте другие приложения и повторите.")
            text.contains("ENOSPC")||text.contains("No space left",true)->Text("На телефоне мало места","Освободите место и повторите.")
            any{it is java.net.UnknownHostException||it is java.net.ConnectException||it is java.net.SocketTimeoutException||it is javax.net.ssl.SSLException}->
                Text("Нет интернета","Повторите, когда появится соединение.")
            any{it is java.io.FileNotFoundException||it is SecurityException}->
                Text("Нет доступа к файлу","Файл перенесли, удалили или у приложения больше нет разрешения его открыть. Выберите его ещё раз.")
            any{it is java.util.zip.ZipException||it is java.io.EOFException||it is java.io.UTFDataFormatException||it is java.nio.charset.CharacterCodingException||
                it is org.xmlpull.v1.XmlPullParserException||it is org.xml.sax.SAXException||it is org.json.JSONException}->
                Text("Файл не удалось открыть","Он повреждён или это не EPUB, FB2, PDF или комикс. Попробуйте скачать его заново.")
            else->Text("Что-то пошло не так","Попробуйте ещё раз. Если повторится, в «Подробностях» будет текст для разработчика.")
        }
    }
}
