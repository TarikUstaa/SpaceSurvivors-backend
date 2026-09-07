# Kod incelemesi — backend

Tarih: 2026-09-07 · Kapsam: `src/main/java/com/tarikusta/spacesurvivors` (1138 satır, 20 sınıf)

Aşağıdaki bulguların **1, 2 ve 3'ü çalışan sunucu üzerinde yeniden üretildi** — teorik
değil, kanıtları bulguların altında.

---

## Özet

| # | Bulgu | Ciddiyet |
|---|---|---|
| 1 | Eşzamanlı ilk temas isteklerinin %87'si `500` dönüyor | 🔴 Kritik |
| 2 | Bozuk `X-Forwarded-For` her uç noktayı `500` yapıyor | 🔴 Kritik |
| 3 | `last_ip` istemci tarafından serbestçe uydurulabiliyor | 🔴 Kritik |
| 4 | Test yok (sadece `contextLoads`) | 🟠 Ciddi |
| 5 | Beklenmeyen hatalar için yakalayıcı yok | 🟠 Ciddi |
| 6 | Her istek, `GET` dahil, bir `UPDATE` yazıyor | 🟠 Ciddi |
| 7 | İstek başına gereğinden fazla sorgu (4-5) | 🟡 Orta |
| 8 | `PlayerService`'in genel API'si sızdırıyor | 🟡 Orta |
| 9 | `ProgressService.save` gelen isteği yerinde değiştiriyor | 🟡 Orta |
| 10 | Servis katmanı `HttpStatus` biliyor | 🟡 Orta |
| 11-13 | Küçük notlar | ⚪ Düşük |

---

## 🔴 1 — Eşzamanlı ilk temas: 8 isteğin 7'si `500`

**Kanıt.** Yeni bir cihaz kimliğiyle 8 paralel istek:

```
200 500 500 500 500 500 500 500
```

Sunucu logu:

```
SQL state [25P02]
ERROR: current transaction is aborted, commands ignored until end of transaction block
  ... for SQL [SELECT player_id FROM player_profile WHERE device_id = ?]
root cause: duplicate key value violates unique constraint "player_profile_device_id_key"
```

**Sebep.** `PlayerService.create()` şunu iddia ediyor:

```java
} catch (DuplicateKeyException e) {
    Optional<UUID> raced = players.findIdByDevice(caller.deviceId());
    if (raced.isPresent()) return raced.get();   // "yarışı diğeri kazandı, onu benimse"
    // isim çakışması — başka bir isimle tekrar dene
}
```

Ama `resolveOrCreate` `@Transactional`. PostgreSQL'de **başarısız bir ifade tüm işlemi
iptal eder**; o noktadan sonra `ROLLBACK`'e kadar her sorgu reddedilir. Yani `catch`
bloğundaki kurtarma sorgusu da, döngünün ikinci `insert` denemesi de **çalışamaz**.

Bunu ham SQL ile de doğruladım:

```sql
BEGIN;
INSERT ... VALUES ('probe-1','Ayni');            -- INSERT 0 1
INSERT ... VALUES ('probe-2','Ayni');            -- ERROR: duplicate key
SELECT 'bu sorgu calisti mi?';                   -- ERROR: transaction is aborted
```

Sonuç: hem yarış kurtarması hem isim yeniden deneme döngüsü **ölü kod**. Çakışma nadir
olduğu için (900k isim) tek kullanıcılı testlerde fark edilmiyor.

**Çözüm.** İstisnaya hiç düşmemek. `ON CONFLICT DO NOTHING` her iki unique kısıtı da
kapsar ve işlemi iptal etmez:

```sql
INSERT INTO player_profile (device_id, display_name, last_ip)
VALUES (:device, :name, CAST(:ip AS inet))
ON CONFLICT DO NOTHING
RETURNING player_id
```

Sıfır satır dönerse: cihazla tekrar ara — varsa yarışı diğeri kazanmış, yoksa isim
çakışmış, yeni isimle dene. İstisna yok, iptal olan işlem yok.

---

## 🔴 2 — Bozuk `X-Forwarded-For` her uç noktayı düşürüyor

**Kanıt.**

```
curl -H 'X-Device-Id: dev-iptest' -H 'X-Forwarded-For: bu-bir-ip-degil' /v1/player
→ {"status":500,"error":"Internal Server Error"}  [500]
```

**Sebep.** `DeviceAuthFilter` header'ı olduğu gibi alıyor, `PlayerRepository` de
`CAST(:ip AS inet)` ile veritabanına veriyor. Geçersiz metin Postgres'te hata fırlatıyor.

Header tamamen istemci kontrolünde olduğu için **herhangi biri, tek bir header ile,
her isteği `500` yaptırabilir.** Kimlik doğrulama gerekmiyor.

**Çözüm.** IP'yi filtrede ayrıştır, geçersizse `null` kabul et (kolon zaten nullable).
`InetAddress.getByName` ya da basit bir doğrulama yeterli. Veritabanına asla
doğrulanmamış metin gitmemeli.

---

## 🔴 3 — `last_ip` istemcinin uydurduğu bir değer

**Kanıt.**

```
curl -H 'X-Device-Id: dev-spoof' -H 'X-Forwarded-For: 8.8.8.8' /v1/player
→ 200

SELECT device_id, host(last_ip) FROM player_profile;
  dev-spoof -> 8.8.8.8
```

**Sebep.** Filtre `X-Forwarded-For`'a koşulsuz güveniyor. O header'ı ancak **güvenilen bir
proxy** eklediyse anlamlıdır; doğrudan bağlanan bir istemciden geldiğinde sadece bir
yalandır.

Şu haliyle `last_ip` kolonu veri değil, temenni. Coğrafi analiz, kötüye kullanım tespiti,
oran sınırlama — hiçbirinde kullanılamaz.

**Çözüm.** `X-Forwarded-For`'a yalnızca istek bilinen bir proxy'den geldiğinde güven.
Spring'in `ForwardedHeaderFilter`'ı + `server.forward-headers-strategy=framework` ayarı
bunu standart şekilde yapar. Lokalde proxy olmadığı için değer her zaman `::1` olmalı.

---

## 🟠 4 — Test yok

`src/test` altında tek dosya var ve tek yaptığı Spring context'inin ayağa kalktığını
görmek:

```java
@SpringBootTest
class SpacesurvivorsApplicationTests {
    @Test void contextLoads() { }
}
```

`pom.xml`'de `spring-boot-starter-webmvc-test`, `-jdbc-test`, `-validation-test` **zaten
duruyor** ama hiç kullanılmamış.

Yukarıdaki 1. bulgu, `PlayerService` için yazılmış bir eşzamanlılık testiyle ilk günden
yakalanırdı. Öncelik sırası:

1. `LeaderboardService` — saf iş kuralları, veritabanı gerekmez, sahte repository ile
   birim testi: mod normalizasyonu, hile eşiği, "rekoru geçmezse yazma".
2. `PlayerService` — isim kuralları, yeniden deneme, yarış.
3. Controller'lar — `@WebMvcTest` ile status kodu eşlemeleri (200/400/404/409/422).
4. Repository'ler — Testcontainers ile gerçek Postgres'e karşı; optimistic lock ve
   upsert davranışı ancak burada doğrulanır.

Şu an her doğrulama elle curl ile yapılıyor — bir kez çalışır, regresyon yakalamaz.

---

## 🟠 5 — Beklenmeyen hatalar için yakalayıcı yok

`ApiExceptionHandler` yalnızca `ApiException` ve `MethodArgumentNotValidException`
biliyor. Diğer her şey Spring'in varsayılan hata gövdesine düşüyor:

```json
{"timestamp":"...","status":500,"error":"Internal Server Error","path":"/v1/player"}
```

2. bulgudaki `500` bu yüzden böyle görünüyor. Eklenmesi gereken:

```java
@ExceptionHandler(Exception.class)
ResponseEntity<...> unexpected(Exception e) {
    log.error("unhandled", e);                 // sunucuda tam ayrıntı
    return status(500).body(Map.of("error", "internal error"));   // istemciye hiçbir iç bilgi
}
```

Ayrıca `DataAccessException` için ayrı bir dal, veritabanı hatalarının istemciye SQL
metni sızdırmamasını garantiler.

---

## 🟠 6 — Her istek bir `UPDATE` yazıyor

`resolveOrCreate` her çağrıldığında `players.touch()` çalışıyor — `GET /v1/player`,
`GET /v1/progress`, `GET /v1/leaderboard` dahil. Yani **salt okuma uçları da yazıyor.**

İki sonucu var:

- **HTTP semantiği:** `GET` tanımı gereği "güvenli"dir, durum değiştirmemelidir. Ara
  sunucular ve tarayıcılar bu varsayımla önbellekler.
- **Ölçek:** her istek bir satır güncellemesi + WAL kaydı. 10k günlük kullanıcı,
  kullanıcı başına 20 istek = günde 200k gereksiz yazma.

`updated_at`'in dakika hassasiyetinde olması yeterli. Öneri: yalnızca son dokunuştan bu
yana N dakika geçtiyse yaz (`WHERE updated_at < now() - interval '5 minutes'`).

---

## 🟡 7 — İstek başına gereğinden fazla sorgu

| İşlem | Sorgu | Neden |
|---|---|---|
| `PATCH /v1/player` | 4 | resolve(SELECT) + touch(UPDATE) + rename(UPDATE) + require(SELECT) |
| `PUT /v1/progress` | 4 | resolve + touch + find(SELECT) + insert/update |
| `POST /v1/leaderboard` | 5 | resolve + touch + personalBest + saveBest + findMe |

Bu ölçekte sorun değil ama ikisi kolayca giderilir:

- `PUT /v1/progress`'teki `find()` gereksiz: doğrudan `UPDATE ... WHERE version = :v`
  çalıştır; 0 satır dönerse *o zaman* "satır mı yok, sürüm mü eski" diye bak. Mutlu yol
  bir sorgu kısalır.
- `resolveOrCreate` + `require` tek bir `SELECT`'te birleşebilir (`RETURNING` ya da tek
  sorguda tüm kolonları çekmek).

---

## 🟡 8 — `PlayerService`'in genel API'si sızdırıyor

```java
public PlayerDtos.PlayerView rename(Caller caller, String requestedName)   // uç nokta
public void                  rename(UUID playerId, String requestedName)   // iç adım
public PlayerRepository.PlayerRow require(UUID playerId)                   // iç adım
```

Aynı isimde iki metot farklı işler yapıyor; ikincisi ve `require` yalnızca sınıfın kendi
içinden çağrılıyor (kontrol edildi). İkisi de `private` olmalı, alttaki `rename` de
`applyName` gibi ayrı bir ad almalı. `require`'ın `PlayerRepository.PlayerRow`
döndürmesi ayrıca repository'nin iç tipini dışarı açıyor.

---

## 🟡 9 — Gelen istek yerinde değiştiriliyor

```java
ObjectNode toStore = (ObjectNode) request.progress();
toStore.put("userId", playerId.toString());
```

`request` nesnesi Jackson'ın deserializasyon çıktısı; onu değiştirmek çalışır ama girdi
parametresini mutasyona uğratmak beklenmedik bir yan etkidir. `deepCopy()` maliyeti
ihmal edilebilir ve niyeti netleştirir.

Ayrıca cast korumasız: `requireObject` önce çağrıldığı için güvenli, ama bu iki satır
arasındaki bağ örtük — `requireObject`'in dönüş tipi `ObjectNode` olsaydı derleyici
garanti ederdi.

---

## 🟡 10 — Servis katmanı HTTP biliyor

`PlayerService`, `ProgressService`, `LeaderboardService` hepsi
`org.springframework.http.HttpStatus` import ediyor. `docs/ogrenme-rehberi.md`'nin
kendisi "Service, HTTP diye bir şey olduğunu bilmez" diyor — yani belge ile kod çelişiyor.

Yaygın ve savunulabilir bir kısayol, ama mentörün sorması muhtemel. Alternatif: servisler
alan istisnaları fırlatsın (`NameTakenException`, `ProgressNotFoundException`), eşleme
`ApiExceptionHandler`'da olsun. Maliyeti birkaç küçük sınıf, kazancı servis katmanının
tamamen taşınabilir olması.

---

## ⚪ 11-13 — Küçük notlar

- **`device_id` doğrulanmıyor.** Uzunluk/biçim kontrolü yok; `text` kolona ne gelirse
  giriyor. Basit bir uzunluk sınırı yeterli.
- **`/health` kimlik doğrulaması istemiyor** ve veritabanının erişilebilirliğini
  açıklıyor. Bu aşamada kabul edilebilir, ama internete açılırken düşünülmeli.
- **Doğrulama mesajları JVM diline göre değişiyor** — bazıları Türkçe, bazıları İngilizce
  dönüyor. İstemciler alan adına bakmalı, mesaja değil; sabitlemek istersen
  `LocaleResolver` ayarlanır.

---

## Doğru yapılmış olanlar

İnceleme dengesi olsun diye:

- Katmanlama net: controller'lar tek satır delegasyon, kural yok, `Map.of` yok.
- `sealed SaveOutcome` + exhaustive `switch` — unutulan durum derleme hatası.
- Tüm SQL parametreli (`:id`), string birleştirme hiç yok → SQL injection yüzeyi sıfır.
- `findMe`'deki korelasyonlu alt sorgu, kaydı olmayan oyuncuya yanlışlıkla "1. sıra"
  demiyor; sıralama eşitlik kuralı liste sorgusuyla aynı.
- Optimistic lock doğru kurgulanmış (`WHERE version = :v` + etkilenen satır sayısı).
- `player_id` / `device_id` ayrımı ileriye dönük doğru bir karar.
- Migration'lar Flyway'de, şema kodla birlikte versiyonlanıyor.
- İsim benzersizliği veritabanı kısıtıyla garanti altında, uygulama kontrolüyle değil.
