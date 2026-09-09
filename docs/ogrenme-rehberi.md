# Spring Boot öğrenme rehberi — bu projenin kodu üzerinden

Bu dosya `src/main/java/com/tarikusta/spacesurvivors/` altındaki sınıfların **neden** öyle
yazıldığını anlatır. IntelliJ'de kodu açıp yanına bu dosyayı koy.

Kararların uzun gerekçeleri `Memory_bank.md`'de (D1-D22), mentör sorularının kısa
cevapları `docs/savunma-notlari.md`'de.

---

## 0. Paket haritası

```
com.tarikusta.spacesurvivors
├── auth/          SecurityConfig, TokenController,
│                  TokenService, CurrentPlayer,
│                  ClientAddress                       "sen kimsin"
├── domain/        6 istisna sınıfı                    "ne ters gitti" (HTTP'den bağımsız)
├── player/        PlayerController, PlayerService,
│                  PlayerProfileRepository,
│                  PlayerProfile (@Entity), Player,
│                  PlayerDtos                          kimlik + isim
├── progress/      ProgressController, ProgressService,
│                  PlayerProgressRepository,
│                  PlayerProgress (@Entity),
│                  ProgressDtos                        bulut kayıt
├── leaderboard/   LeaderboardController, ...Service,
│                  LeaderboardEntryRepository,
│                  LeaderboardEntry (@Entity),
│                  LeaderboardEntryId, BoardRow,
│                  LeaderboardDtos                     skorlar
└── web/           ApiExceptionHandler, OpenApiConfig,
                   RateLimitFilter, RateLimitConfig,
                   HealthController, HealthService     HTTP ortak işleri
```

**Paket-özellik-başına** (package-by-feature) düzen: bir özelliğin controller'ı, servisi,
repository'si ve DTO'ları yan yana. Alternatifi paket-katman-başına olurdu
(`controllers/`, `services/`, `repositories/`) — o düzende bir özelliği değiştirmek için
üç ayrı klasörde dolaşırsın. Bu düzende `player/` klasörünü açtığında oyuncuyla ilgili
her şey oradadır.

---

## 1. Neden katmanlar var?

Tek bir sınıfa her şeyi yazabilirdik: HTTP'yi karşıla, kuralları uygula, SQL çalıştır.
Küçük projede çalışır. Sorun işler büyüdüğünde başlar:

- SQL'i değiştirmek istediğinde HTTP koduna dokunmak zorunda kalırsın
- "Rekoru geçti mi?" kuralını test etmek için HTTP isteği kurman gerekir
- Aynı kuralı ikinci bir yerden çağırmak istediğinde kopyalarsın

Katmanlar bunu çözer. Her katmanın **tek bir sorumluluğu** olur ve sadece bir alt
katmanı tanır:

```
HTTP isteği
    ↓
Controller   "HTTP'yi anla"        → URL, method, body, status kodu
    ↓
Service      "kuralları uygula"    → rekoru geçti mi? mod geçerli mi?
    ↓
Repository   "veriyi getir/yaz"    → SQL
    ↓
Veritabanı
```

**Altın kural: oklar tek yöne bakar.** Repository, Service'i tanımaz. Service,
Controller'ı tanımaz. Böylece HTTP'yi tamamen değiştirsen Service ve Repository'ye hiç
dokunmazsın.

### Somut örnek — `LeaderboardService`

`kills / survivedSeconds > 60` kuralı Service'te. Neden Controller'da değil? Çünkü o kural
HTTP'yle ilgili değil — oyunun kuralı. Yarın aynı kontrolü bir toplu içe aktarma işinden
çağırırsan Controller olmadan çağırabilirsin.

Aynı şekilde `ORDER BY survived_seconds DESC` Repository'de, çünkü o SQL'in işi. Service
"bana ilk 100'ü ver" der, nasıl sıralandığını bilmez.

### Bu kuralın bir kez ihlal edildiği yer — ve düzeltilişi

Başlangıçta controller'lar temizdi ama sınır **diğer yönde** sızmıştı: `PlayerService`
tek başına `org.springframework.http.HttpStatus`'u 14 kez import ediyordu. Yani
"controller'da servis kodu yok" ama "servis'te HTTP kodu var".

Bu, bu rehberin kendi iddiasıyla çelişiyordu. Düzeltildi — 5. bölüme bak.

---

## 2. Entity ve JPA

Spring Boot öğrenirken her yerde şu dörtlüyü görürsün, ve bu projede dördü de var:

> Controller → Service → **Repository (JPA)** → **Entity**

Üç tablo, üç entity:

| Entity | Tablo | Öne çıkan |
|---|---|---|
| `PlayerProfile` | `player_profile` | `@GeneratedValue`, `inet` kolonu |
| `PlayerProgress` | `player_progress` | `@JdbcTypeCode(SqlTypes.JSON)`, **`@Version`** |
| `LeaderboardEntry` | `leaderboard` | `@IdClass` — bileşik anahtar |

### Entity ile `record` farkı — bu projedeki en önemli ayrım

```java
@Entity @Table(name = "player_progress")
public class PlayerProgress {
    @Id  @Column(name = "player_id")  private UUID playerId;

    @JdbcTypeCode(SqlTypes.JSON)                    // String -> jsonb
    @Column(name = "progress_data")   private String progressData;

    @Version                          private int version;
}
```

**Entity yönetilir.** Transaction içinde Hibernate onu izler; bir alanını değiştirirsen
`save()` çağırmasan bile commit'te veritabanına yazılır. `PlayerService.applyName` tam
bunu kullanıyor: `profile.setDisplayName(name)` diyor, gerisi Hibernate'in.

**`record`'lar sadece taşır.** `Player`, `BoardRow`, `PlayerDtos.PlayerView` — okunur,
kullanılır, atılır. Değiştirsen hiçbir şey olmaz.

O yüzden ikisi yan yana: `PlayerProfile` (entity, veritabanı satırı) ve `Player` (record,
uygulamanın konuştuğu şekil), arada `toPlayer()`. **Entity servis katmanının dışına
çıkmıyor.**

### `@Version` — elle yazdığımızın yerine geçen tek anotasyon

Bu proje optimistic locking'i başta elle yazıyordu:

```sql
UPDATE player_progress SET ..., version = version + 1
 WHERE player_id = :id AND version = :v      -- 0 satır dönerse çakışma
```

`@Version` tam olarak bu SQL'i üretiyor ve eşleşme olmazsa
`ObjectOptimisticLockingFailureException` fırlatıyor.

**Ama servis hâlâ sürümü kendi karşılaştırıyor**, fırlatmasını beklemiyor. Sebebi
kritik: fırlayan istisna transaction'ı **rollback-only** işaretler, dolayısıyla `409`
gövdesi için sunucunun kopyasını çeken sorgu artık çalışamaz. `@Version` yine de bir işe
yarıyor — bizim okumamızla yazmamız arasına giren dar yarışı yakalıyor.

*(Bu, `PlayerService.create`'in `DuplicateKeyException` ile düştüğü tuzağın aynısı.
Genel kural 6. bölümde.)*

### `@Entity` kullanmayan bir şey de kaldı

`HealthService` hâlâ `JdbcClient` ile `SELECT 1` atıyor. Doğrusu bu: ortada bir tablo
satırı yok, eşlenecek nesne yok. **JPA ile düz JDBC aynı projede yan yana durabilir**;
soru "hangisi doğru" değil, "bu iş hangisine ait".

### `equals` / `hashCode` — entity'lerde neden özel

```java
@Override public int hashCode() { return getClass().hashCode(); }   // sabit!
```

Tuhaf görünüyor ama kasıtlı. `hashCode` bir entity'nin **tüm yaşamı boyunca** aynı
kalmalı — id atanmadan önce de. `id.hashCode()` yazsaydık, bir `Set`'e koyduğun entity
kaydedilir kaydedilmez hash'i değişir ve koleksiyon onu kaybederdi.

`equals` ise sadece anahtara bakıyor. Değişebilen alanlara bakan bir `equals`, birisi
ismi değiştirdiği anda entity'nin kimliğini değiştirmiş olurdu.
---

## 3. Spring'in sihri: Bean'ler ve Dependency Injection

Kodda hiçbir yerde `new PlayerService(...)` yazmadım. Ama çalışıyor. Nasıl?

### Bean nedir?

Spring açılışta paketleri tarar, belirli anotasyonlara sahip sınıfları bulur, onlardan
**birer nesne üretir** ve bir havuzda tutar. O nesnelere **bean**, havuza **application
context** (IoC container) denir.

| Anotasyon | Anlamı | Bizdeki örnek |
|---|---|---|
| `@RestController` | HTTP uçları | `PlayerController`, `ProgressController`, `LeaderboardController` |
| `@Service` | İş kuralları | `PlayerService`, `ProgressService`, `LeaderboardService`, `HealthService` |
| `@Repository` | Veri erişimi | `PlayerProfileRepository`, `PlayerProgressRepository`, `LeaderboardEntryRepository` |
| `@Configuration` | Bean tanımlayan sınıf | `SecurityConfig`, `RateLimitConfig`, `WebConfig` |
| `@RestControllerAdvice` | Global hata yakalayıcı | `ApiExceptionHandler` |

**Önemli:** `@Service`, `@Repository` ve `@Component` teknik olarak neredeyse aynı şeyi
yapar (hepsi `@Component`'in özelleşmiş hâli). Farkları **niyet belirtmek** — kodu okuyan
biri `@Service` görünce "burada iş kuralı var" der. `@Repository` ek olarak veritabanı
istisnalarını Spring'in kendi tiplerine çevirir (bu yüzden `DuplicateKeyException`
yakalayabiliyoruz, Postgres'e özgü bir istisna değil).

### Dependency Injection

Bir bean'in ihtiyaç duyduğu diğer bean'leri Spring **kendisi verir**:

```java
@Service
public class ProgressService {

    private final ProgressRepository progress;
    private final PlayerService players;
    private final ObjectMapper json;

    public ProgressService(ProgressRepository progress, PlayerService players, ObjectMapper json) {
        this.progress = progress;
        this.players = players;
        this.json = json;
    }
}
```

Spring: "`ProgressService` üretmem lazım, yapıcı üç şey istiyor. `ProgressRepository` var,
`PlayerService` var, `ObjectMapper`'ı Jackson yapılandırmasından ben üretmiştim. Hepsini
verip nesneyi kuruyorum."

**Constructor injection** tercih edilir:
- `final` yapabilirsin → nesne kurulduktan sonra değişemez
- Testte elle `new ProgressService(sahteRepo, sahteService, mapper)` diyebilirsin —
  `LeaderboardServiceTest` ve `PlayerServiceTest` tam olarak bunu yapıyor
- Bağımlılık eksikse **açılışta** patlar, çalışma anında değil

> Eski eğitimlerde `@Autowired` ile alan enjeksiyonu görürsün. Artık önerilmiyor; tek
> yapıcı varsa `@Autowired` yazmana bile gerek yok.

### Repository'ler nereden geliyor?

`PlayerProfileRepository` bir **arayüz**; gövdesi yok, `new` ile üretilmiyor. Zincir:

1. `application.properties` → `spring.datasource.*`
2. Spring Boot bir **`DataSource`** üretir (HikariCP bağlantı havuzu)
3. `spring-boot-starter-data-jpa` var → `EntityManagerFactory` kurulur, `@Entity`
   sınıfları taranır
4. Spring Data açılışta repository arayüzlerini bulur ve **her biri için bir sınıf
   üretir** — türetilmiş metotların SQL'ini metot adından, `@Query`'lerinkini
   anotasyondan
5. Servis onu ister, Spring verir

Buna **auto-configuration** denir: "şu bağımlılık + şu ayar → şu bean'ler". Spring
Boot'un asıl gücü budur.

Bir de şu ayar var, mentörün soracağı türden:

```properties
spring.jpa.hibernate.ddl-auto=validate   # şemayı Flyway kurar, Hibernate sadece denetler
spring.jpa.open-in-view=false            # varsayılan açık; kapattık
```

`validate`: Hibernate entity'lerle gerçek şemayı karşılaştırır ve uyuşmazsa **açılmaz**.
Bir eşleme hatasını kullanıcıdan önce açılışta yakalamak her zaman daha ucuz.

`open-in-view`: varsayılan olarak açıktır ve isteğin tamamı boyunca bir veritabanı
oturumunu açık tutar, ki cevap yazılırken lazy bir ilişki hâlâ yüklenebilsin. Sorguların
gerçekte nerede çalıştığını gizler ve bağlantıyı gereğinden çok daha uzun tutar.

---

## 4. Kimlik zinciri — bu projenin en kritik akışı

Kimlik **iki aşamada** çalışıyor: oturumda bir kez kimlik kanıtlanıp bir bilet alınıyor,
sonraki her istek o bileti taşıyor.

### Aşama 1 — bileti al (oturumda bir kez)

```
POST /v1/auth/token   {deviceId, deviceSecret}
       ↓
RateLimitFilter        adres başına 30/dk. Reddedilirse BCrypt'e HİÇ varmaz.
       ↓
Spring Security        /v1/auth/token public — geçiyor
       ↓
TokenController        tek satır → TokenService.issue(...)
       ↓
PlayerService          .authenticateDevice(deviceId, secret, ip)
       ↓                cihaz kimliğini bilen TEK sınıf
       ↓                cihaz yok  → kaydet: UserNNNNNN + BCrypt(secret)
       ↓                cihaz var  → BCrypt.matches; tutmazsa 401
       ↓
TokenService.mint      JWT üret: sub = player_id, exp = +1 saat, HS256 imzalı
```

### Aşama 2 — bileti kullan (diğer her istek)

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
       ↓
Spring Security        imzayı ve süreyi doğrular
       ↓                geçersizse Controller'a HİÇ varmaz — 401
       ↓
CurrentPlayerArgumentResolver   token'ın sub claim'ini UUID'ye çevirir
       ↓
Controller             @CurrentPlayer UUID playerId
       ↓
ProgressService /      sadece player_id görür
LeaderboardService     PlayerService'e artık HİÇ bağımlı değiller
```

**Bu ikinci akıştaki en önemli şey ne olmadığı:** veritabanı sorgusu yok. Token'ın içinde
zaten `player_id` yazıyor. Eskiden her istek "bu cihaz hangi oyuncu" diye bir `SELECT`
atıyordu; o sorgu tamamen kalktı.

### Üç kimlik, üç farklı iş

| | `device_id` | `device_secret` | `player_id` |
|---|---|---|---|
| Ne | **Seni nasıl tanıyoruz** | **Kanıt** | **Sen kimsin** |
| Üreten | Unity, `Guid.NewGuid()` | Unity, 2 GUID = 64 karakter | Postgres, `gen_random_uuid()` |
| Nerede durur | Cihazda PlayerPrefs | Cihazda PlayerPrefs | DB + token'ın içinde |
| Sunucu ne saklar | Değerin kendisi | **Sadece BCrypt hash'i** | Değerin kendisi |
| Ağa ne zaman çıkar | Sadece `/v1/auth/token` | Sadece `/v1/auth/token` | Her token'ın içinde |
| Değişir mi | Evet — yeniden kurulum, yeni telefon | Hayır | **Asla** |

`device_id` ile `player_id` aynı şey olsaydı yeni telefon = yeni oyuncu = ilerleme gitti
olurdu. Ayrı oldukları için gerçek hesap sistemi geldiğinde tek `player_id`'ye birden çok
cihaz bağlanabilecek — hiçbir ilerleme satırına dokunmadan.

### Kimlik iddiası ile kanıt neden ayrı

`device_id` tek başına bir **iddia**: herkes bir tane uydurabilir. Eskiden sunucu ona
inanıyordu, yani öğrendiğin her cihaz kimliği o oyuncu olabilmen demekti. Parolanın hiçbir
korumasına sahip olmayan bir paroladan farksızdı: açıkta saklanıyor, her istekte gidiyor,
süresi dolmuyor, iptal edilemiyor.

`device_secret` kanıt. Sunucu onu **saklamıyor**, sadece BCrypt hash'ini tutuyor:

```java
} else if (!passwordEncoder.matches(rawSecret, storedHash)) {
    throw new AuthenticationFailedException("device id or secret is not recognised");
}
```

**Neden BCrypt, neden SHA-256 değil?** BCrypt kasıtlı olarak yavaş (~82 ms, ölçtük) ve
satır başına ayrı tuzlanıyor. Tablo çalınsa bile milyonlarca şifreyi tek tek denemek
pratik değil. Hızlı bir hash saniyede milyarlarca denenebilir — bu özelliğe sahip değil.

**Neden her hata aynı cevabı veriyor?** "Böyle bir cihaz yok" ile "sır yanlış"ı ayırmak,
birinin istek istek "hangi cihaz kimlikleri kayıtlı" diye taramasına izin vermek demek
olurdu (account enumeration). Mesaj tek: `device id or secret is not recognised`.

**Neden bilinmeyen cihaz reddedilmiyor, kaydediliyor?** Oyunda "üye ol" ekranı yok. Cihaz
ilk kez token isteyince sunucu sessizce bir oyuncu yaratıyor.

> **Kabul edilen sınır:** sır cihazdaki PlayerPrefs'te duruyor. Kilidi açık cihaza erişen
> hesaba da erişir. Cihaz tabanlı kimliğin dürüst tavanı bu — ve "telefonumu kaybettim"in
> cevabının gerçek hesap (Firebase, e-posta) olmasının sebebi.

### `@CurrentPlayer` — parametre *olmayan* parametre

```java
@GetMapping
public PlayerDtos.PlayerView me(@CurrentPlayer UUID playerId) {
    return PlayerDtos.PlayerView.of(players.view(playerId));
}
```

Bu değer **isteğin gövdesinden ya da URL'inden gelmiyor** ve asla gelmemeli — çağıranın
kendi `player_id`'sini yazabilmesi, kimlik doğrulamanın var olma sebebinin ta kendisi.
İmzalı token'dan geliyor, başka hiçbir yerden.

`CurrentPlayerArgumentResolver` bunu yapan sınıf. Spring MVC'ye "şu anotasyonu görürsen
parametreyi şöyle doldur" diyorsun; `WebConfig` onu kaydediyor. Spring Security imzayı
zaten doğruladığı için `SecurityContextHolder`'daki token'a güvenilebilir.

### Neden filtre, neden bu sırada

Spring Security bir **filtre zinciri** — istek Controller'a varmadan önce çalışan halkalar:

```
İstek → RateLimitFilter → Spring Security → DispatcherServlet → Controller
```

`RateLimitFilter` bilerek Security'den **önce**. Sebebi: korumak istediği pahalı iş
(BCrypt) daha ilerideki Controller'da. Sonra konsaydı reddedilen istek token ayrıştırmadan
geçmiş olurdu — filtre yine `429` dönerdi ama engellemek istediği işi engellemezdi.
Ölçtük: kabul edilen istek ~82 ms, reddedilen ~1.1 ms. **75 kat.**

Bir filtre `chain.doFilter(request, response)` çağırmazsa istek Controller'a hiç ulaşmaz —
reddetme böyle yapılır.

### IP doğrulaması — iki gerçek hatanın yaşadığı yer

`ClientAddress` (auth paketinde) çağıranın adresini bir Postgres `inet` kolonuna uygun
hâle getiriyor:

```java
static String of(HttpServletRequest request) {
    return literalOrNull(request.getRemoteAddr());
}
```

Ayrıştırılamayan bir metin `inet` kolonuna gidince **tüm SQL ifadesini** patlatır. Bu
gerçekten oldu: doğrulanmamış bir header değeri, herkesin tek bir header'la her isteği
`500` yapabilmesi demekti.

İkinci hata: `X-Forwarded-For` koşulsuz okunuyordu, yani `last_ip` istemcinin yazdığı
şeydi. **Artık hiç okunmuyor.** O header ancak *bizim işlettiğimiz* bir proxy eklediyse
anlamlı. Rate limiter'da bu daha da keskin: header'a güvenseydik yeni bir değer yeni bir
kova demek olurdu — saldırgan sayıyı artırarak limiti tamamen kaldırırdı.

> **Hostname çözümlemesi tuzağı:** `InetAddress.getByName("example.com")` DNS sorgusu
> yapar — yavaş ve istismar edilebilir. Bu yüzden önce "bu bir literal adres mi" diye
> bakılıyor: iki nokta içeriyorsa IPv6 olabilir (hostname'de iki nokta olmaz), yoksa dört
> ondalık grup şartı var. Ancak ondan sonra `getByName` çağrılıyor.

---

## 5. Hata yönetimi — servisler HTTP bilmez

Servisler `domain/` altındaki istisnaları fırlatır. Bunlar **status kodu taşımaz**:

```java
// PlayerService
throw new AlreadyTakenException("name already taken");
throw new InvalidInputException("name must be 3-16 characters");

// LeaderboardService
throw new RuleViolationException("kill rate is not achievable in that time");
```

Hangisinin hangi HTTP koduna döneceğine **tek bir yer** karar verir:

```java
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)      → 404
    @ExceptionHandler(AlreadyTakenException.class)  → 409
    @ExceptionHandler(InvalidInputException.class)  → 400
    @ExceptionHandler(RuleViolationException.class) → 422
    @ExceptionHandler(TooLargeException.class)      → 413
    @ExceptionHandler(DataAccessException.class)    → 503  (loglanır, dışarı sızmaz)
    @ExceptionHandler(Exception.class)              → 500  (son güvenlik ağı)
}
```

Kazancı: servis katmanı Spring web'e hiç referans vermiyor. Taşımayı değiştirsen (gRPC,
mesaj kuyruğu) servisler aynen kalır.

### `400` ile `422` farkı — en çok karıştırılan ayrım

| | Anlamı | Örnek |
|---|---|---|
| `400` | **Anlayamadım** | `kills: -5` — eksi öldürme diye bir şey yok |
| `422` | **Anladım ama olmaz** | 10 saniyede 50.000 kill — JSON kusursuz, oyun bunu üretemez |

Buna paralel iki doğrulama katmanı var:
- **Alan biçimi** → DTO üzerindeki anotasyonlar (`@PositiveOrZero`, `@Min`), `@Valid` ile
  tetiklenir, kod servise **hiç girmez**
- **İş kuralı** → serviste elle, çünkü birden fazla alanı karşılaştırıyor

### Spring'in kendi istisnaları — düzeltilen gerçek bir hata

`ApiExceptionHandler` bir de `ResponseEntityExceptionHandler`'ı genişletiyor. Bu süs
değil: Spring MVC, bir isteğin sıradan yollarla yanlış olması için kendi istisnalarını
fırlatır — bilinmeyen yol, endpoint'in sunmadığı bir fiil, okunamayan gövde, kabul
edilmeyen içerik tipi.

Taban sınıf olmadan aşağıdaki `Exception` yakalayıcısı bunların **hepsini** yutuyordu:

| İstek | Dönmeli | Dönüyordu |
|---|---|---|
| Yanlış yazılmış URL | `404` | **`500`** |
| Yanlış method | `405` | **`500`** |
| Yanlış Content-Type | `415` | **`500`** |
| Bozuk JSON | `400` | **`500`** |

Üstelik her biri `ERROR` seviyesinde loglanıyordu — `/wp-admin` arayan bir bot hata
logunu hiçbir şeye dair kayıtlarla dolduruyordu. Şimdi taban sınıf doğru status'ları
veriyor, bunlar `debug` seviyesinde loglanıyor, ve `Exception` yakalayıcısı asıl işine
bakıyor: **gerçekten öngörmediğimiz hatalar.**

Ders genel: **yakalama zincirinin en altına `Exception` koyduğunda, üstünde çerçevenin
kendi istisnalarını doğru ele alan bir katman olduğundan emin ol.**

### `ProblemDetail`

Hata gövdeleri RFC 9457 formatında:

```json
{"type":"about:blank","title":"Conflict","status":409,"detail":"name already taken"}
```

Spring Boot 4 bunu hazır veriyor. Başta elle `Map.of("error", ...)` yazmıştım — verilen
standardı kullanmamak için sebep yoktu.

---

## 6. Sınıf sınıf

### `SpacesurvivorsApplication` (13 satır)

`@SpringBootApplication` üç anotasyonun kısaltması:
- `@ComponentScan` — **bu sınıfın paketinden aşağıya** tarar. Tüm kodun
  `com.tarikusta.spacesurvivors` altında olmasının sebebi bu.
- `@EnableAutoConfiguration` — auto-configuration'ı açar
- `@SpringBootConfiguration` — bu sınıf da yapılandırma kaynağı olabilir

### Controller'lar — üçü de tek satırlık delegasyon

```java
@RestController
@RequestMapping("/v1/player")           // sınıf seviyesi: taban yol
public class PlayerController {

    @GetMapping                          // → GET /v1/player
    public PlayerDtos.PlayerView me(@CurrentPlayer UUID playerId) {
        return PlayerDtos.PlayerView.of(players.view(playerId));
    }
}
```

Anotasyonlar:

| Anotasyon | İşi |
|---|---|
| `@RequestMapping("/v1/player")` | Sınıf seviyesi ön ek. Yol tek yerde yazılıyor. |
| `@GetMapping` / `@PutMapping` / `@PatchMapping` / `@PostMapping` | Hangi HTTP fiili |
| `@CurrentPlayer` | Doğrulanmış token'daki `player_id` — bizim yazdığımız anotasyon (§4) |
| `@RequestBody` | Gövdedeki JSON'u Java nesnesine çevir (Jackson) |
| `@RequestParam` | URL'deki `?mode=infinite` |
| `@Valid` | DTO'daki doğrulama anotasyonlarını çalıştır |

> `@RequestMapping(method = RequestMethod.GET)` eski kullanımdır; `@GetMapping` onun
> 2016'dan beri gelen kısayolu. Sınıf seviyesindeki `@RequestMapping` ise **taban yol**
> için doğru ve güncel — fiil kısayolunun taban yol karşılığı yok.

**Tek istisna** `ProgressController.save`:

```java
return switch (progress.save(playerId, body)) {
    case SaveOutcome.Accepted accepted -> ResponseEntity.ok(SaveAccepted.of(accepted));
    case SaveOutcome.Conflict conflict -> ResponseEntity.status(CONFLICT).body(SaveConflict.of(conflict));
};
```

Bu iş kuralı değil, "çakışma kavramının HTTP karşılığı" — controller'ın var oluş sebebi.
`sealed` tip sayesinde bir durumu unutursan **derleyici hata verir**. Gövdeleri kurmak da
controller'ın işi değil, o yüzden `SaveAccepted.of(...)` — controller sonucun içine hiç
bakmıyor.

**Neden exception değil:** çakışma, optimistic lock protokolünün **normal** bir sonucu.
Beklenen akışı exception'la yönetmek kötü desendir.

### `PlayerService` (190 satır) — kimlik kuralları

En yoğun sınıf, ve cihaz kimliği diye bir şey olduğunu bilen **tek** sınıf. İçindeki en
öğretici parça `register()`:

```java
String secretHash = passwordEncoder.encode(rawSecret);
for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
    players.insertIfFree(deviceId, generateName(), ip, secretHash);

    // Bu cihaza ait satır artık varsa: ya bizim insert tuttu, ya aynı cihaz için
    // eşzamanlı bir istek yarışı kazandı. İkisi de doğru cevap.
    Optional<PlayerProfile> profile = players.findByDeviceId(deviceId);
    if (profile.isPresent()) return profile.get().getPlayerId();
    // satır yoksa üretilen isim doluydu — başka isimle tekrar dene
}
```

Buradaki ders büyük ve pahalıya mal oldu:

> **Bir transaction içinde kısıt ihlali, fırlatılmasına izin verdiğin sürece kurtarılamaz.**

Önceki hâli `DuplicateKeyException` yakalayıp kurtarmaya çalışıyordu. Ama PostgreSQL'de
başarısız bir ifade **tüm işlemi iptal eder**; o noktadan sonra `ROLLBACK`'e kadar her
sorgu `25P02` ile reddedilir. Yani `catch` bloğundaki kurtarma sorgusu da, döngünün
sonraki denemesi de ölü koddu. Aynı cihazdan 8 eşzamanlı istekte 7'si `500` dönüyordu.

Çözüm istisnaya hiç düşmemek: `ON CONFLICT DO NOTHING` her iki unique kısıtı da kapsıyor
ve **satır döndürmeyerek** haber veriyor.

`applyName` hâlâ `DuplicateKeyException` yakalıyor — orada güvenli, çünkü ondan sonra
hiçbir şey çalışmıyor.

### Repository'ler — üç farklı sorgu tekniği

`LeaderboardEntryRepository` bu projenin en öğretici dosyası: dört sorgu, dört farklı yol,
ve her biri farklı bir sebeple seçilmiş.

**1. Türetilmiş** — Spring Data metot adını okuyup SQL'i kendi yazıyor:
```java
Optional<PlayerProfile> findByDeviceId(String deviceId);   // gövde yok, anotasyon yok
```

**2. Skaler JPQL** — sadece bir kolon lazımsa entity yüklemenin anlamı yok:
```java
@Query("SELECT e.survivedSeconds FROM LeaderboardEntry e WHERE e.playerId = :playerId AND e.mode = :mode")
Optional<Double> findPersonalBest(...);
```

**3. JPQL constructor projeksiyonu** — sonucu doğrudan bir `record`'a döküyor:
```java
@Query("""
    SELECT new com.tarikusta.spacesurvivors.leaderboard.BoardRow(
               p.displayName, e.survivedSeconds, e.kills, e.reachedLevel)
      FROM LeaderboardEntry e
      JOIN PlayerProfile p ON p.playerId = e.playerId
     WHERE e.mode = :mode
     ORDER BY e.survivedSeconds DESC, e.achievedAt ASC
    """)
List<BoardRow> topEntries(String mode, Pageable limit);
```
Hiçbir entity yüklenmiyor ve tablonun göstermediği hiçbir kolon çekilmiyor — `player_id`
ve `device_id` veritabanından hiç çıkmıyor. `JOIN ... ON`, sadece tek bir sorgunun
kullanacağı bir `@ManyToOne` uydurmayı gereksiz kılıyor.

**4. Native** — JPA'nın ifade edemediği iki şey için:
```sql
ON CONFLICT (player_id, mode) DO UPDATE SET ...   -- upsert
(SELECT count(*) + 1 FROM leaderboard o WHERE ...) AS rank   -- korelasyonlu alt sorgu
```

> **Karma kullanım normaldir.** "Her şey JPA olmalı" diye bir kural yok; veritabanının
> JPA'da karşılığı olmayan bir yeteneği varsa native sorgu doğru araçtır.

### `JpaRepository` her zaman doğru taban değil

```java
public interface LeaderboardEntryRepository
        extends Repository<LeaderboardEntry, LeaderboardEntryId> {   // JpaRepository DEĞİL
```

`JpaRepository`'yi genişletseydi `save(entity)`, `deleteAll()`, `findAll()` gibi onlarca
metot miras alırdı. Sorun şu: **bir leaderboard satırı yalnızca `saveBest` ile
yazılmalı**, çünkü "sadece rekoru geçerse" kuralını o uyguluyor. Miras alınan bir
`save(entity)` bir oyuncunun rekorunu daha kötü bir run ile sessizce ezerdi ve tip sistemi
buna itiraz etmezdi.

Çıplak `Repository` işaretleyici arayüzü sadece bizim yazdığımız dört metodu bırakıyor.
**Repository, çerçevenin üretebildiği her işlemi değil, alanın izin verdiği işlemleri
açmalı.**

*(`PlayerProfileRepository` ve `PlayerProgressRepository` `JpaRepository`'yi genişletiyor,
çünkü onlar `findById` / `save` / `saveAndFlush` metotlarını gerçekten kullanıyor.)*

### `@Modifying`'in iki bayrağı

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
```

Native bir yazma, Hibernate'in persistence context'inin arkasından veritabanına gider.
`flushAutomatically` bekleyen değişiklikleri önce dışarı iter; `clearAutomatically`
sonrasında context'i boşaltır, ki **hemen ardından yapılan okuma o satırı görsün**, önbelleğe
alınmış bir "yok" cevabını değil. `PlayerService.create` tam olarak buna dayanıyor.


### `ProgressService` — sorgu sırasının önemi

```java
// Önce yazmayı dene. Yerleşik bir oyuncunun tekrar kaydetmesi — yani olağan durum —
// böylece tek ifadeye iniyor.
Optional<Integer> newVersion = progress.update(playerId, progressJson, request.version());
if (newVersion.isPresent()) return new SaveOutcome.Accepted(newVersion.get());

// Hiçbir şey güncellenmedi: ya satır yok ya sürüm eski. Ancak şimdi sormaya değer.
```

Önce `find()` sonra `update()` yapıyordu; mutlu yolda gereksiz bir sorguydu.

Bir de savunmacı kopya:

```java
private static ObjectNode requireObject(JsonNode body) {
    if (body == null || !body.isObject()) throw new InvalidInputException(...);
    return (ObjectNode) body.deepCopy();
}
```

Daraltılmış tip döndürmek çağıranın kontrolü unutmasını imkânsız kılıyor; `deepCopy` de
Jackson'ın verdiği isteği yolda değiştirmemeyi garanti ediyor.

### DTO nedir, neden ayrı?

**DTO** = Data Transfer Object. Katmanlar veya sistemler arası veri taşır, davranışı yok.

Aynı oyuncunun **üç temsili** var ve üçü aynı değil:

| Katman | Tip | İçerik |
|---|---|---|
| Veritabanı | `player_profile` satırı | 7 kolon (`last_ip`, `first_login_date`, `updated_at` dahil) |
| Uygulama | `Player` | 4 alan |
| İstemci | `PlayerDtos.PlayerView` | 2 alan (`displayName`, `country`) |

`PlayerView`'da **`id` ve `deviceId` yok** — kimlikler dışarı çıkmıyor, leaderboard'da
bile sadece `displayName` görünüyor. Kesme noktası `PlayerView.of()`.

> **Not:** `progress` ve `leaderboard` paketlerinde ayrı bir alan tipi **yok** — oradaki
> DTO'lar zaten alan şekliyle birebir aynı olurdu, ikinci bir kayıt seti katmanlama değil
> kopyalama olurdu. Katman ayrımı bir kural değil, bir araç: fayda ürettiği yerde uygula.

---

## 7. Bir isteğin tam yolculuğu

`PUT /v1/progress`:

```
 1. Tomcat               8080'de HTTP isteğini alır
 2. RateLimitFilter      yolu bakar: /v1/auth/token değil → dokunmadan geçer
 3. Spring Security      Authorization: Bearer ... → imza + süre doğrulanır
                         geçersizse burada biter: 401, Controller görmez
 4. DispatcherServlet    "PUT /v1/progress" kime ait? → ProgressController.save()
 5. Argüman çözümü       @CurrentPlayer → token'ın sub'ı → UUID (DB sorgusu YOK)
                         @Valid @RequestBody → Jackson JSON'u SaveRequest'e çevirir,
                         version'ın @PositiveOrZero kuralı burada işler
 6. ProgressController   → progress.save(playerId, body)
 7. Proxy                @Transactional gördü → BEGIN
 8. ProgressService      progress objesi mi? değilse InvalidInputException
                         blob'a userId bas (sunucu kimliğin sahibi), boyut kontrolü
                         findById → satır yok → save() → Accepted(0)
                         satır var → version tutuyor mu?
                             tutuyor  → saveAndFlush → Accepted(v+1)
                             tutmuyor → Conflict(sunucudaki sürüm + kopya)
 9. Proxy                istisna yok → COMMIT
10. ProgressController   switch → ResponseEntity.ok(SaveAccepted.of(accepted))
11. Jackson              record'u JSON'a çevirir: {"version":1}
12. Tomcat               200 OK
```

Her adımda **kimin ne bildiğine** dikkat et:
- Filter, ilerleme diye bir şey olduğunu bilmiyor
- Controller, SQL diye bir şey olduğunu bilmiyor
- Service, HTTP diye bir şey olduğunu bilmiyor
- Repository, kuralları bilmiyor

5. adıma özellikle dikkat: **oyuncunun kim olduğunu bulmak için veritabanına gidilmiyor.**
Token zaten söylüyor. Bu yüzden `ProgressService` ve `LeaderboardService` `PlayerService`'e
hiç bağımlı değil — kimlik, kendi endpoint'inde bir kez çözülüp bilete yazılıyor.

### `@Transactional` — en önemli anotasyonlardan biri

Bir servis metodunda birden fazla DB işlemi olabiliyor. Anotasyon olmasa her biri ayrı
ayrı kalıcı olurdu: `authenticateDevice` içinde oyuncu satırı yazılır, hemen ardından
`touch` patlar → yarım yazılmış bir kayıt kalır.

`@Transactional` hepsini **tek işleme** sarar: metot normal biterse `COMMIT`,
`RuntimeException` çıkarsa `ROLLBACK`.

> **Bu projede öğrenilmiş bir tuzak (D13):** Postgres'te bir işlem içinde **kısıt ihlali
> fırlarsa tüm işlem iptal olur** — sonraki her sorgu `25P02` ile patlar. Yani
> `try/catch (DuplicateKeyException)` yazıp "yakalayıp toparlarım" diyemezsin: `catch`
> bloğunun içi ölü koddur. Bu yüzden ilk kayıt `ON CONFLICT DO NOTHING` kullanıyor —
> hiçbir şey fırlamıyor. **İşlem içinde bir kısıt ihlali, ancak hiç fırlatmazsan
> kurtarılabilir.**

**Nasıl:** Spring, `@Transactional` gördüğü bean'i bir **proxy** ile sarar. Sen servisi
çağırdığında aslında proxy'yi çağırırsın; o işlemi açar, gerçek metodu çalıştırır,
sonucuna göre commit/rollback yapar.

İki tuzağı:
1. **Aynı sınıf içinden çağrı proxy'yi atlar.** `this.digerTransactional()` işlem açmaz.
2. **Varsayılan olarak sadece `RuntimeException`'da rollback olur**, checked exception'da
   olmaz.

---

## 8. Testler

**110 test.** Yapıyı doğru kurmanın somut karşılığı bu: servisler HTTP ve SQL bilmediği
için veritabanı olmadan, milisaniyelerde test edilebiliyorlar.

| Sınıf | Ne test ediyor | Nasıl |
|---|---|---|
| `LeaderboardServiceTest` | daha iyi/kötü/eşit run, mod normalizasyonu, hile eşiği, sıralama, limit | Mockito |
| `PlayerServiceTest` | isim kuralları, ad üretimi, **iki yarış senaryosu** | Mockito |
| `RateLimitFilterTest` | kova harcama, `429` + `Retry-After`, çağıran ayrımı | `MockHttpServletRequest` |
| `RateLimitConcurrencyTest` | **64 eşzamanlı istek kapasiteden fazlasını alamıyor** | `ExecutorService` |
| `AuthenticationIntegrationTest` | kayıt, yanlış sır `401`, **kurcalanmış token reddi** | `@SpringBootTest` |
| `RateLimitIntegrationTest` | filtrenin gerçekten zincirde olduğu | `@SpringBootTest` |
| `TestDatabaseWiringTest` | testlerin gerçekten kapsayıcıya bağlandığı | `@SpringBootTest` |
| `ProgressControllerTest` | status eşlemeleri (200/404/409/413/400) | `@WebMvcTest` |
| `ApiExceptionHandlerTest` | 404/405/415/400 — hepsi eskiden `500` dönüyordu | `@WebMvcTest` |
| `PlayerProfileRepositoryTest` | `ON CONFLICT`, harf duyarsız benzersizlik, touch throttle | **gerçek Postgres** |
| `PlayerProgressRepositoryTest` | jsonb gidiş-dönüşü, `@Version`, bayat yazım | **gerçek Postgres** |
| `LeaderboardEntryRepositoryTest` | upsert, JPQL join, rank tutarlılığı | **gerçek Postgres** |
| `OpenApiDocsTest` | üretilen dokümanın API'yi gerçekten anlatması | `@SpringBootTest` |

Üç tür test bir arada: **birim** (mock, DB yok), **dilim** (`@WebMvcTest`, sadece web
katmanı), **entegrasyon** (`@SpringBootTest`, gerçek veritabanı). Hangisinin ne zaman
doğru olduğunu görmek için üçü de var.

### Veritabanı artık senin makinenden gelmiyor (D22)

`@DatabaseTest` yazan her test kendi **tek kullanımlık Postgres kapsayıcısını** başlatıyor
(Testcontainers). Eskiden testler senin diskindeki Postgres'e ve git'e girmeyen bir şifre
dosyasına bağlıydı — yani suite yalnızca *tek bir* makinede geçiyordu, dolayısıyla CI
imkânsızdı. Kapsayıcı her seferinde boş başladığı için Flyway migration'ları da her koşuda
sıfırdan sınanıyor.

### Tekrar eden ders: **davranışı değil, kablolamayı test et**

Üç kez aynı şey oldu:

| Ne | Testler ne diyordu | Gerçek |
|---|---|---|
| **D20** — `last_ip` `inet` kolonu | hepsi yeşil | `PATCH /v1/player` **her zaman 503**. Testler repository'yi mock'luyordu, Hibernate'in attığı SQL hiç Postgres'e ulaşmamıştı |
| **D21** — rate limiter | filtre doğru sayıyordu | filtrenin uygulamada *çağrıldığı* test edilmiyordu. Yanlış sıraya kaydedilmiş bir filtre bütün testleri geçer, hiçbir şeyi korumaz |
| **D22** — Testcontainers | hepsi yeşil olurdu | `@ServiceConnection` bozuk olsa datasource sessizce `localhost:5432`'ye düşerdi ve **testler yine geçerdi** — bu makinede orada doğru şemalı bir Postgres olduğu için |

Üçünün ortak dersi: bir kablolama parçası bir şeyi *gerçek* yapıyorsa, **kabloyu doğrula** —
çünkü onun sağladığı davranış kazara da üretilebilir.

### Testlerin gerçekten yakaladıkları

Bunlar teorik değil — hepsi yazılırken çıktı:

1. **Bozuk IP header'ında `null` döneceğini sanmıştım**; kod soket adresine geri
   düşüyordu, ki daha doğrusu o. **Testi düzelttim, kodu değil.**
2. **`updated_at` trigger'ı geriye alınamıyor.** Throttle testi yazarken çıktı: trigger
   her güncellemede `now()` basıyor, satırı eskitmeye çalışan güncelleme dahil. Kolonun
   var olma sebebi bu.
3. **`/v3/api-docs` `401` dönüyordu.** OpenAPI testi ilk çalıştırmada yakaladı: filtre
   her yolu koruyunca, hangi kimlik bilgisinin gönderileceğini anlatan dokümanı okumak
   için o kimlik bilgisini zaten bilmen gerekiyordu.

**CI:** `.github/workflows/ci.yml` her push ve pull request'te `./mvnw test` koşuyor.
Bu ancak D22'den sonra mümkün oldu — testler taşınabilir olmadan çalıştıracak yer yoktu.

---

## 9. Bu projede *kullanmadığımız* şeyler

| Şey | Neden yok |
|---|---|

| **Lombok** (`@Data`, `@Getter`) | Java `record`'ları aynı işi dille yapıyor |
| **`@Autowired` alan enjeksiyonu** | Constructor injection tercih edildi |
| **Servis arayüzleri** (`XService` + `XServiceImpl`) | Tek implementasyon varken gereksiz katman |
| **Servis katmanında HTTP tipleri** | Statü seçimi tek yerde: `ApiExceptionHandler` (§5) |
| **Sayfalama** (`Pageable` API'de) | Tahta 100 satırla sınırlı; gerçek ihtiyaç doğunca |
| **`player_run` geçmiş tablosu** | Bilerek ertelendi (D12) — **geriye dönük doldurulamaz** |

Son maddeyi açayım: eski Java projelerinde her servisin bir `interface`'i ve bir `Impl`'i
olurdu. Sebebi eski test araçlarının sınıfları taklit edememesiydi. Modern Mockito
sınıfları da taklit edebiliyor — nitekim testlerimiz arayüzsüz sınıfları mock'luyor.
Arayüz, **gerçekten birden fazla implementasyon olduğunda** açılır: Unity tarafındaki
`IProfileStore` gibi (local ve HTTP olmak üzere iki implementasyonu var).

---

## 10. Kendini sınama

1. **Kolay:** `GET /v1/leaderboard`'a `limit` yerine `page` eklemek isteseydin hangi
   dosyalara dokunurdun?
2. **Kolay:** `MAX_KILLS_PER_SECOND`'ı 30'a düşür, `LeaderboardServiceTest`'i çalıştır.
   Hangi test kırılıyor, neden?
3. **Orta:** `DELETE /v1/progress` eklemek isteseydin hangi sınıflara ne eklerdin?
4. **Orta:** `ProgressService.save`'den `@Transactional`'ı kaldırsan hangi senaryoda veri
   tutarsız kalır?
5. **Orta:** `AlreadyTakenException`'ı `409` yerine `422` yapmak isteseydin kaç dosyaya
   dokunurdun? (Cevap: bir. Neden?)
6. **Zor:** `register()` içindeki `ON CONFLICT DO NOTHING` yerine tekrar `try/catch` koysan
   hangi test kırılır? O testi ezbere anlatabiliyor musun?
7. **Zor:** İki cihaz aynı anda `PUT /v1/progress` yollarsa ne olur? İkisi de aynı
   `version`'ı gönderirse hangisi kazanır, diğeri ne alır?
8. **Zor:** `RateLimitConfig.FILTER_ORDER`'ı `-100`'ün üstüne çıkarsan hangi test kırılır?
   Kırılmasaydı ne kaybederdik? (§4'teki 82 ms / 1.1 ms ölçümünü hatırla.)
9. **Zor:** `@CurrentPlayer` yerine `playerId`'yi `@RequestParam` yapsak ne olurdu?
   Tam olarak hangi saldırı mümkün hâle gelirdi?

---

## 11. Sonraki okumalar

- **JPA ilişkileri** (`@OneToMany`, `@ManyToOne`) — bu projede hiç ihtiyaç olmadı, ama
  gerçek bir nesne grafiğinde işin merkezi orası
- **Spring Security'nin derinliği** — burada tek bir `SecurityFilterChain` ve JWT var;
  rol/yetki, method security (`@PreAuthorize`), OAuth2 akışları hiç kullanılmadı
- **Profiller** — `application-local.properties` ve `application-test.properties` zaten
  kullanılıyor, prod'da genişleyecek
- **Observability** — Actuator açık ama metrik toplanmıyor. Rate limiter'ın "kaç kez
  reddettim"i tam olarak buraya ait (§4)

Resmî dokümantasyon gerçekten iyi: <https://docs.spring.io/spring-boot/index.html>
