# Sorun Analizi

**Sorun:**
NuvioTV uygulamasında bazı `nzbdav` akışları oynatılırken "401 Unauthorized" hatası alınmaktadır. Bu akışlar genellikle kullanıcı adı ve şifre içeren URL'ler (örneğin: `http://user:pass@host/video.mkv`) kullanır.

Uygulamanın medya oynatıcısı (`ExoPlayer`), ağ istekleri için `OkHttp` kütüphanesini kullanmaktadır (`PlayerMediaSourceFactory.kt` dosyasında yapılandırılmıştır). Ancak, `OkHttp` varsayılan olarak URL içindeki kimlik bilgilerini (user info) kullanarak otomatik kimlik doğrulama yapmaz. Bu nedenle, oynatıcı sunucuya bağlanırken gerekli olan `Authorization` başlığını göndermez ve sunucu isteği reddeder.

**Çözüm:**
Sorunu çözmek için `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt` dosyasındaki `createMediaSource` fonksiyonunda bir düzenleme yapılmalıdır.

1.  **URL Analizi:** Akış URL'si (`url`) `java.net.URI` kullanılarak analiz edilmeli ve `userInfo` kısmı kontrol edilmelidir.
2.  **Başlık Oluşturma:** Eğer URL içinde kullanıcı bilgisi mevcutsa ve `headers` parametresinde zaten bir `Authorization` başlığı yoksa, bu bilgiler kullanılarak `Basic Authentication` başlığı oluşturulmalıdır (`okhttp3.Credentials.basic` kullanılarak).
3.  **Başlığı Ekleme:** Oluşturulan bu başlık, `OkHttpDataSource.Factory` yapılandırılırken isteklere eklenmelidir.

**Stremio Core Karşılaştırması:**
`stremio-core` kod tabanı incelendiğinde, akış URL'leri için doğrudan bir `userInfo` (kullanıcı adı/şifre) ayrıştırma veya işleme mantığı bulunmamaktadır. Stremio ekosisteminde bu tür kimlik doğrulama işlemleri genellikle `stremio-server` (yerel sunucu proxy'si) veya kullanılan oynatıcı kütüphanesinin (örneğin libmpv) kendi yetenekleri tarafından ele alınır. NuvioTV ise istemci tarafında saf bir ExoPlayer + OkHttp implementasyonu kullandığı için, bu URL tabanlı kimlik doğrulama desteğinin manuel olarak eklenmesi gerekmektedir.

**Örnek Kod Mantığı:**

```kotlin
val uri = java.net.URI(url)
val userInfo = uri.userInfo

// Eğer URL'de kullanıcı bilgisi varsa ve Authorization başlığı henüz eklenmemişse:
val authHeaders = if (!userInfo.isNullOrEmpty() && !headers.keys.any { it.equals("Authorization", ignoreCase = true) }) {
     val parts = userInfo.split(":", limit = 2)
     val username = parts[0]
     val password = if (parts.size > 1) parts[1] else ""
     val credentials = okhttp3.Credentials.basic(username, password)
     mapOf("Authorization" to credentials)
} else {
     emptyMap()
}

val finalHeaders = sanitizedHeaders + authHeaders

val okHttpFactory = OkHttpDataSource.Factory(getOrCreateOkHttpClient()).apply {
    setDefaultRequestProperties(finalHeaders)
    // ...
}
```
