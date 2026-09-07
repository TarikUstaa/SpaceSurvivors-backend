# Savunma notları — mentör hangi soruyu sorarsa

Her başlık, gerekçesi olan bir karar. Uzun hâli `Memory_bank.md`'de (D1-D14),
nasıl çalıştığı `docs/ogrenme-rehberi.md`'de.

**Genel kural:** gerekçesi olmayan karar savunulmaz. Bilmediğin bir şey sorulursa
"bilmiyorum, bakarım" doğru cevaptır — uydurmak değil.

---

## "Neden JPA kullanmadın?"

**Geçtik — haklıydın.** Üç tablo da artık `@Entity`, üç repository de `JpaRepository`.
Önceki Gerekçem şuydu: profil tek bir jsonb blob, Java
nesnesine hiç açılmıyor; iki kritik sorgu (`ON CONFLICT` upsert ve sıralama alt sorgusu)
zaten native SQL; JPA'nın örtük davranışları (lazy loading, dirty checking) öğrenirken
kafa karıştırıyor.

**Ama yanlış şeyi optimize etmişim.** Bu proje öğrenmek için var ve piyasada Spring
denince kastedilen Spring Data JPA. Üstelik JPA bir noktada beni doğrudan yanıltıyor:
optimistic locking'i elle yazdık, `@Version` onu hazır veriyor.

Geçişte öğrendiklerim, sorarsa:

- **`@Version` 0'dan başlıyor**, bizim elle yazdığımız 1'den. Sözleşme değişikliği; Unity
  etkilenmedi çünkü sayıyı yorumlamıyor, geri yolluyor.
- **Servis hâlâ sürümü kendi karşılaştırıyor**, `@Version`'ın fırlatmasını beklemiyor —
  çünkü fırlayan `OptimisticLockException` transaction'ı rollback-only işaretler ve 409
  gövdesi için sunucunun kopyasını çeken sorgu artık çalışamaz. `PlayerService.create`'in
  düştüğü tuzağın aynısı.
- **Bileşik anahtar** (`player_id, mode`) `@IdClass` gerektirdi; `Serializable` +
  `equals`/`hashCode` şart, çünkü persistence context bir entity'yi onunla tanıyor.
- **Upsert ve rank sorgusu native kaldı** — `ON CONFLICT` ve SELECT içindeki korelasyonlu
  alt sorgu JPA'da ifade edilemiyor. Karma kullanım normaldir.
- **`updated_at` trigger'ı geriye alınamıyor** — her güncellemede `now()` basıyor, tarihi
  eskitmeye çalışan güncelleme dahil. Kolonun işi bu; throttle'ı test etmek için trigger'ı
  geçici kapatmak gerekti.

## "Neden profil bir jsonb blob? Kolonlara açsana."

Unity istemcisi profili zaten tek bir JSON nesnesi olarak üretiyor. Kolonlara açarsam
oyun her yeni alan eklediğinde **hem migration hem API değişikliği** gerekir — ve o şema
büyümeye devam edecek (Stats ve Profile ekranları planda).

`jsonb` ile istemci kendi şemasını yönetiyor, sunucu hiç açmıyor. `text` yerine `jsonb`
çünkü geçerli JSON olduğunu doğruluyor ve gerekirse içine sorgu atılabiliyor
(`progress_data->>'wallet'`).

**Bedeli:** sunucu blob'un içeriği hakkında hiçbir şey doğrulayamıyor. Kabul edilebilir —
oyuncunun kendi kayıt verisi ve tek yazan istemci.

## "player_id ile device_id neden ayrı?"

`device_id` **nasıl tanıdığımız** — istemci üretiyor, cihazda duruyor, değişebilir
(yeniden kurulum, yeni telefon). `player_id` **kim olduğu** — veritabanı üretiyor, asla
değişmiyor, ilerleme ve skorlar ona bağlı.

Aynı şey olsalardı: yeni telefon = yeni oyuncu = ilerleme gitti, ve hesabı ikinci bir
cihaza bağlamanın hiçbir yolu olmazdı. Ayrı olduğu için gerçek giriş geldiğinde
`player_id` sabit kalıyor, sadece "nasıl tanındığı" değişiyor.

`uuid`, sıralı sayı yerine: sıralı id kaç oyuncun olduğunu dışarı sızdırır.

## "Optimistic locking'i neden elle yazdın?"

**Artık elle değil — `@Version` devraldı.** Aşağısı mekanizmanın kendisi, ki JPA'nın
ürettiği SQL de birebir aynı:

İki cihaz aynı hesapta gerçek bir senaryo ve korumasız hâli tam olarak bulut kaydın
önlemek için var olduğu şey: sessiz üzerine yazma, kaybolan ilerleme. Pesimistik kilit
olmaz — mobil bir istemciden gelen ağ turu boyunca veritabanı kilidi tutmak demek olurdu.

Mekanizma: `UPDATE ... WHERE player_id = :id AND version = :v`. Başkası araya girdiyse
`WHERE` tutmaz, 0 satır etkilenir, biz onu çakışma olarak yorumlayıp 409 döneriz.

## "Kimlik neden header'da? Query param olsa olmaz mıydı?"

Çünkü o bir parametre değil, **kimlik bilgisi**:

- Her uç noktada aynı → query param olsa dört metodun imzasına da girerdi; header olunca
  tek filtrede çözülüyor ve controller'lar kimliğin nereden geldiğini bilmiyor
- **Query string sızar** — sunucu erişim logları, proxy logları, tarayıcı geçmişi. URL
  paylaşılınca kimlik de paylaşılmış olur
- Auth geldiğinde yerine `Authorization: Bearer <token>` geçecek, endpoint'ler değişmeyecek

**Kabul ettiğim eksik:** `X-` öneki. RFC 6648 onu 2012'de terk etti; doğrusu kimliği
`Authorization` header'ında taşımak.

## "Controller'da neden switch var?"

Çünkü orada karar verilen şey "çakışma kavramının HTTP'deki karşılığı" — yani status
kodu seçimi, controller'ın var oluş sebebi. İş kuralı controller'a girmiyor: `save`
sonucun içine bile bakmıyor, `SaveAccepted.of(...)` / `SaveConflict.of(...)` çağırıyor.

**Neden exception yapmadım:** çakışma, optimistic lock protokolünün **normal** bir
sonucu, istisnai bir durum değil. Beklenen akışı exception'la yönetmek kötü desendir.
`sealed` tip iki sonucu da eşit derecede normal gösteriyor ve bir durumu unutursam
derleyici hata veriyor.

## "Servisler neden kendi istisnalarını fırlatıyor?"

Servis katmanının HTTP'den haberi olmaması için. Başta öyle değildi — `PlayerService`
tek başına `HttpStatus`'u 14 kez import ediyordu, yani controller'ları temizlerken sınır
diğer yönde sızmıştı.

Şimdi servisler `NotFoundException`, `AlreadyTakenException` gibi **alan** istisnaları
fırlatıyor; hangisinin hangi status'a döneceğine tek bir yer karar veriyor:
`ApiExceptionHandler`. Taşımayı değiştirsen servisler değişmiyor.

Gövdeler `ProblemDetail` (RFC 9457) — Spring Boot 4 hazır veriyor, elle şekil uydurmaya
gerek yoktu.

## "Leaderboard neden oyuncu başına tek satır?"

Oyunun tablosu "oyuncu başına en iyi"yi gösteriyor. Her run'ı saklamak sınırsız büyüme +
göstermek için `DISTINCT ON` demek, üstelik kimsenin okumadığı veri için.

**Bilerek kabul ettiğim borç:** run geçmişi *geriye dönük üretilemez*. `player_run` diye
ekle-sadece bir tablo önerdim, şimdilik ertelendi. Sonucu: Stats ekranı, "son 20 run",
run dağılımına dayalı anti-cheat — hepsi o tablo eklendiği günden itibaren başlayacak.

## "Anti-cheat var mı?"

Zayıf ve bunu biliyorum. İki katman:
- **Alan doğrulama** (anotasyonlar) → 400
- **İş kuralı**: mod whitelist'i, saniyede öldürme oranı eşiği → 422

Bu bariz çöpü durdurur, kararlı bir saldırganı durdurmaz. Gerçek koruma sunucu-otoriter
run gerektirir; bu oyun için kapsam dışı, muhtemelen hep öyle kalacak.

## "V1 migration'ı düzenlemişsin, git geçmişinde görünüyor."

Doğru, üç kez. Gerekçe: şema henüz yayınlanmadı, veri atılabilirdi ve "V1 users/players
yaratıyor, V2 hemen düşürüyor" anlamsız bir tarih olurdu.

**Gerçek bir sistemde yapılmaz** — Flyway migration'ı checksum'la takip eder, değiştirirsen
açılışta patlar. O pencere kapandı: bundan sonrası `V2__`.

## "Testler ne kadar kapsıyor?"

88 test: servislerin iş kuralları (birim), filtrenin header işleme, controller status
eşlemeleri (`@WebMvcTest`).

**Repository testleri artık var** — gerçek Postgres'e karşı 17 test: jsonb'nin çift
kodlanmadan gidip gelmesi, `@Version`'ın bayat yazımı reddetmesi, `ON CONFLICT`'in
fırlatmak yerine 0 döndürmesi, isim benzersizliğinin harf duyarsız olması, touch
throttle'ının hem tutması hem bırakması, upsert'ün satır eklemek yerine değiştirmesi,
rank'in tablo sıralamasıyla uyuşması.

**Hâlâ eksik:** Testcontainers. Testler yerel Postgres'e bağlı; CI'da çalışması için
kapsayıcı tabanlı olmalı.

Bir test benim yanlış varsayımımı yakaladı: bozuk bir IP header'ında `null` döneceğini
sanmıştım, kod soket adresine geri düşüyordu — ki daha doğrusu o. Testi düzelttim.

## "Neden GET yazma yapıyor?"

Doğru, `GET /v1/player` ve `/v1/progress` `updated_at`/`last_ip` güncelliyor. Gerekçe:
ilk temas, birinin oyuncu olduğu andır ve `first_login_date`'i damgalamanın tek dürüst
yeri orası.

**Bedeli kabul edilmiş bir taviz:** HTTP'de `GET` "güvenli" olmalıdır. Yazma yükünü
5 dakikada bire indirdim (önce her istekte yazıyordu), ama semantik ihlal duruyor.

## "Auth yok, farkında mısın?"

Evet, bilinçli bir erteleme. Şu an `X-Device-Id`'de ne yazarsan o oluyorsun — cihaz
kimliğini ele geçiren biri profili okur, ilerlemeyi siler, adına skor gönderir.

Cihaz kimliği pratikte **süresi dolmayan, iptal edilemeyen, değiştirilemeyen bir şifre**
gibi davranıyor. Firebase token doğrulaması gelince değişecek tek dosya
`DeviceAuthFilter` — seam'i baştan bunun için koyduk.

**Auth'un çözmediği:** sahte hesap üretimi (rate limiting gerekir) ve hile (sunucu-otoriter
run gerekir). Bunları karıştırmamak lazım.
