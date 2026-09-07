# Spring Boot öğrenme rehberi — bu projenin kodu üzerinden

Bu dosya `src/main/java/com/tarikusta/spacesurvivors/` altındaki sınıfların **neden** öyle
yazıldığını anlatır. IntelliJ'de kodu açıp yanına bu dosyayı koy.

Kararların uzun gerekçeleri `Memory_bank.md`'de (D1-D14), mentör sorularının kısa
cevapları `docs/savunma-notlari.md`'de.

---

## 0. Paket haritası

```
com.tarikusta.spacesurvivors
├── auth/          Caller, DeviceAuthFilter        "sen kimsin"
├── domain/        6 istisna sınıfı                "ne ters gitti" (HTTP'den bağımsız)
├── player/        Controller, Service, Repository,
│                  Player, PlayerDtos              kimlik + isim
├── progress/      Controller, Service, Repository,
│                  ProgressDtos                    bulut kayıt
├── leaderboard/   Controller, Service, Repository,
│                  LeaderboardDtos                 skorlar
└── web/           ApiExceptionHandler,
                   HealthController, HealthService  HTTP ortak işleri
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

## 2. "Entity" nerede? — ve JPA'ya geçiş kararı

Spring Boot öğrenirken her yerde şu dörtlüyü görürsün:

> Controller → Service → **Repository (JPA)** → **Entity**

Bu projede şu an **Entity yok**, çünkü JPA yerine `JdbcClient` kullanıyoruz. **Ama bu
değişecek** — Tarık'ın mentörü JPA'ya geçilmesini istedi ve haklı.

### JPA ne yapar?

JPA (Java Persistence API) bir **ORM**'dir — Object-Relational Mapping. Java nesnelerini
otomatik olarak tablo satırlarına çevirir:

```java
@Entity
@Table(name = "player_progress")
class PlayerProgress {

    @Id
    private UUID playerId;

    @JdbcTypeCode(SqlTypes.JSON)      // Hibernate'in jsonb desteği
    private String progressData;

    @Version                          // optimistic locking'i JPA yönetir
    private int version;
}

interface PlayerProgressRepository extends JpaRepository<PlayerProgress, UUID> {
    // SQL yazmadan hazır gelir: save(), findById(), delete()...
}
```

### Neden başta kullanmadım, neden şimdi geçiyoruz

Gerekçem şuydu: profil tek bir jsonb blob (ORM'in çevireceği nesne yok), iki kritik sorgu
zaten native SQL (`ON CONFLICT` upsert, sıralama alt sorgusu), ve JPA'nın örtük
davranışları (lazy loading, dirty checking, N+1) öğrenirken kafa karıştırır.

**Ama yanlış şeyi optimize etmişim.** Bu proje öğrenmek için var ve piyasada Spring
denince kastedilen Spring Data JPA. Üstelik JPA bir noktada beni doğrudan yanıltıyor:

> `@Version` = bizim elle yazdığımız optimistic locking.

`ProgressRepository.update`'te `WHERE version = :v` yazıp etkilenen satır sayısına bakarak
yaptığımız şeyi, JPA tek anotasyonla veriyor.

**Geçişte iki dikkat noktası:**
- `leaderboard`'ın bileşik anahtarı (`player_id, mode`) → `@IdClass` veya `@EmbeddedId`
- Upsert'ün JPA karşılığı yok → `@Query(nativeQuery = true)` olarak kalacak. Karma
  kullanım normaldir, utanılacak bir şey değil.

Şema değişmiyor, sadece repository katmanı değişiyor.

### Şu anki "Entity" karşılığımız

Anotasyonsuz, sadece veri taşıyan `record`'lar:

```java
// player/Player.java — uygulama bu oyuncuyu böyle biliyor
public record Player(UUID id, String deviceId, String displayName, String country) { }

// progress/ProgressRepository.java içinde — SQL'den okunan ham hâl
public record StoredProgress(String json, int version) { }
```

Fark: Entity **veritabanı satırıdır** — JPA onu takip eder, değiştirince otomatik
kaydeder. Bizim record'larımız sadece **taşıma kabıdır**: okunur, kullanılır, atılır.
Sihir yok. JPA'ya geçince bu fark somutlaşacak.

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
| `@Repository` | Veri erişimi | `PlayerRepository`, `ProgressRepository`, `LeaderboardRepository` |
| `@Component` | Genel amaçlı | `DeviceAuthFilter` |
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

### `JdbcClient` nereden geldi?

1. `application.properties` → `spring.datasource.url/username/password`
2. Spring Boot bunu görüp bir **`DataSource`** bean'i üretir (HikariCP bağlantı havuzu)
3. `spring-boot-starter-jdbc` bağımlılığı var → `DataSource`'tan bir **`JdbcClient`** üretir
4. Repository onu ister, Spring verir

Buna **auto-configuration** denir: "şu bağımlılık + şu ayar → şu bean'ler". Spring Boot'un
asıl gücü budur. JPA'ya geçince aynı mekanizma `EntityManager` ve `JpaRepository`
implementasyonlarını üretecek.

---

## 4. Kimlik zinciri — bu projenin en kritik akışı

Her istek "sen kimsin"le başlıyor. Zincir dört adım ve her adım bir katman:

```
Authorization: Device dev-a3f91c22
       ↓
DeviceAuthFilter          header'ı ayrıştır, IP'yi doğrula → Caller(deviceId, ip)
       ↓                  DB'ye HİÇ dokunmaz
Controller                @RequestAttribute ile Caller'ı alır, servise geçirir
       ↓
PlayerService             .resolveOrCreate(caller) → player_id (uuid)
       ↓                  cihaz kimliğini bilen TEK sınıf
ProgressService /         sadece player_id görür
LeaderboardService
```

### İki kimlik, iki farklı iş

| | `device_id` | `player_id` |
|---|---|---|
| Ne | **Seni nasıl tanıyoruz** | **Sen kimsin** |
| Üreten | Unity, `Guid.NewGuid()` | Postgres, `gen_random_uuid()` |
| Nerede durur | Cihazdaki PlayerPrefs | Sadece veritabanı |
| Değişir mi | Evet — yeniden kurulum, yeni telefon | **Asla** |
| Neye bağlı | — | Tüm ilerleme, skorlar, isim |
| API'de görünür mü | Header'da gider | **Hayır** |

Aynı şey olsalardı yeni telefon = yeni oyuncu = ilerleme gitti olurdu. Ayrı oldukları için
gerçek giriş geldiğinde `player_id` sabit kalacak, sadece "nasıl tanındığı" değişecek.

### `DeviceAuthFilter` — katmanların *dışında*

**Filter nedir?** İstek Controller'a varmadan **önce** çalışan ara katman:

```
İstek → Filter1 → Filter2 → DispatcherServlet → Controller
```

`chain.doFilter(request, response)` = "bir sonraki halkaya geç". Çağırmazsan istek
Controller'a hiç ulaşmaz — kimlik reddi böyle yapılır:

```java
String deviceId = deviceId(request);
if (deviceId == null) {
    reject(response);       // 401, chain.doFilter çağrılmıyor
    return;
}
request.setAttribute(Caller.ATTR, new Caller(deviceId, clientIp(request)));
chain.doFilter(request, response);
```

**Neden `Authorization` header'ı?** Cihaz kimliği bir parametre değil, **kimlik bilgisi**:
her uç noktada aynı, ve query string erişim loglarına, proxy loglarına, tarayıcı geçmişine
sızar. `X-Device-Id` gibi özel bir header yerine `Authorization` çünkü RFC 6648 `X-`
önekini 2012'de terk etti — ve Firebase gelince sadece şema `Device`'tan `Bearer`'a döner.

**Neden `null` fallback yok?** Eskiden header yoksa `dev-unknown` diye paylaşılan bir
hesaba düşüyordu. Header'ı unutan herkes aynı oyuncu oluyordu — birbirlerinin kaydını
okuyup ezebiliyorlardı. Daha kötüsü istemci hatasını gizliyordu: Unity header göndermeyi
bıraksa her şey sessizce çalışmaya devam ederdi. Şimdi `401`.

**Neden DB'ye dokunmuyor?** Filtre her istekte çalışır, `/health` dahil. Ucuz kalmalı. Ve
"oyuncu satırı var olmalı mı" bir iş kuralı — servis işi.

### IP doğrulaması — iki gerçek hatanın yaşadığı yer

```java
private static String ipLiteralOrNull(String value) { ... }
```

Buradaki değer bir Postgres `inet` kolonuna gidiyor ve ayrıştırılamayan bir metin **tüm
SQL ifadesini** patlatır. Header tamamen istemci kontrolünde olduğu için doğrulanmamış bir
değer, herkesin tek bir header'la her isteği `500` yapabilmesi demekti. Gerçekten
olmuştu; `DeviceAuthFilterTest` o senaryoların hepsini test ediyor.

İkinci hata: `X-Forwarded-For` koşulsuz okunuyordu. O header ancak **bizim işlettiğimiz
bir proxy** eklediyse anlamlı; doğrudan bağlanan bir istemciden geldiğinde sadece bir
iddiadır. Şimdi `app.trust-forwarded-for` kapalıyken hiç okunmuyor.

> **Hostname çözümlemesi tuzağı:** `InetAddress.getByName("example.com")` DNS sorgusu
> yapar — yavaş ve istismar edilebilir. Bu yüzden önce "bu bir literal adres mi" diye
> bakıyoruz: iki nokta içeriyorsa IPv6 olabilir (hostname'de iki nokta olmaz), yoksa dört
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
    public PlayerDtos.PlayerView me(@RequestAttribute(Caller.ATTR) Caller caller) {
        return PlayerDtos.PlayerView.of(players.view(caller));
    }
}
```

Anotasyonlar:

| Anotasyon | İşi |
|---|---|
| `@RequestMapping("/v1/player")` | Sınıf seviyesi ön ek. Yol tek yerde yazılıyor. |
| `@GetMapping` / `@PutMapping` / `@PatchMapping` / `@PostMapping` | Hangi HTTP fiili |
| `@RequestAttribute` | Filtrenin isteğe iliştirdiği `Caller`'ı al |
| `@RequestBody` | Gövdedeki JSON'u Java nesnesine çevir (Jackson) |
| `@RequestParam` | URL'deki `?mode=infinite` |
| `@Valid` | DTO'daki doğrulama anotasyonlarını çalıştır |

> `@RequestMapping(method = RequestMethod.GET)` eski kullanımdır; `@GetMapping` onun
> 2016'dan beri gelen kısayolu. Sınıf seviyesindeki `@RequestMapping` ise **taban yol**
> için doğru ve güncel — fiil kısayolunun taban yol karşılığı yok.

**Tek istisna** `ProgressController.save`:

```java
return switch (progress.save(caller, body)) {
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

### `PlayerService` (148 satır) — kimlik kuralları

En yoğun sınıf. İçindeki en öğretici parça `create()`:

```java
for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
    Optional<UUID> created = players.insertIfFree(caller.deviceId(), generateName(), caller.ip());
    if (created.isPresent()) return created.get();

    Optional<UUID> raced = players.findIdByDevice(caller.deviceId());
    if (raced.isPresent()) return raced.get();   // yarışı başkası kazandı, onu benimse
    // isim doluydu — başka bir isimle tekrar dene
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

### `PlayerRepository` — SQL'in yeri

```java
public Optional<Player> find(UUID playerId) {
    return db.sql("SELECT ... FROM player_profile WHERE player_id = :id")
            .param("id", playerId)
            .query((rs, rowNum) -> new Player(...))
            .optional();
}
```

`JdbcClient` zinciri:

| Parça | İşi |
|---|---|
| `.sql("...")` | Sorgu metni. `:id` = **isimli parametre** |
| `.param("id", v)` | Parametreyi bağla |
| `.query(RowMapper)` | Her satırı nesneye çevir |
| `.optional()` / `.list()` / `.single()` | 0-1 satır / N satır / tam 1 satır |
| `.update()` | Yazma. **Etkilenen satır sayısını** döndürür |

**`:id` neden şart — SQL Injection.** Şunu asla yapma:

```java
db.sql("SELECT * FROM player_profile WHERE device_id = '" + deviceId + "'")   // TEHLİKELİ
```

Kullanıcı `'; DROP TABLE player_profile; --` yollarsa tablo gider. İsimli parametrede
sürücü değeri **sorgu metnine karıştırmaz**, ayrı kanaldan gönderir — değer ne olursa
olsun veri kalır, komut olamaz.

**`.update()`'in dönüş değeri = optimistic locking.** `ProgressRepository.update`:

```sql
UPDATE player_progress
   SET progress_data = CAST(:data AS jsonb), version = version + 1
 WHERE player_id = :id AND version = :v
```

Başkası araya girip sürümü değiştirdiyse `WHERE` hiçbir satıra uymaz → 0 satır → çakışma.
Kilit almadık, bekleme yok. Adı bu yüzden **optimistic**: "muhtemelen çakışma olmaz,
olursa fark ederim." *(JPA'ya geçince bunu `@Version` devralacak.)*

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
 2. DeviceAuthFilter     Authorization header'ını ayrıştırır → "dev-a3f91c22"
                         IP'yi doğrular → Caller(deviceId, ip) isteğe iliştirilir
                         chain.doFilter → devam
 3. DispatcherServlet    "PUT /v1/progress" kime ait? → ProgressController.save()
 4. Argüman çözümü       @RequestAttribute → Caller
                         @Valid @RequestBody → Jackson JSON'u SaveRequest'e çevirir,
                         version'ın @PositiveOrZero kuralı burada işler
 5. ProgressController   → progress.save(caller, body)
 6. Proxy                @Transactional gördü → BEGIN
 7. ProgressService      progress objesi mi? değilse InvalidInputException
                         → players.resolveOrCreate(caller) → player_id
                         profile'a userId bas, boyut kontrolü
                         → progress.update(...)  UPDATE ... WHERE version = :v
                         0 satır → find() → satır yok → insert()
                         → SaveOutcome.Accepted(1)
 8. Proxy                istisna yok → COMMIT
 9. ProgressController   switch → ResponseEntity.ok(SaveAccepted.of(accepted))
10. Jackson              record'u JSON'a çevirir: {"version":1}
11. Tomcat               200 OK
```

Her adımda **kimin ne bildiğine** dikkat et:
- Filter, ilerleme diye bir şey olduğunu bilmiyor
- Controller, SQL diye bir şey olduğunu bilmiyor
- Service, HTTP diye bir şey olduğunu bilmiyor
- Repository, kuralları bilmiyor

### `@Transactional` — en önemli anotasyonlardan biri

Bu metotta birden fazla DB işlemi var. Anotasyon olmasa her biri ayrı ayrı kalıcı olurdu:
`resolveOrCreate` oyuncuyu yaratır, `update` patlar → oyuncu var ama kaydı yok.

`@Transactional` hepsini **tek işleme** sarar: metot normal biterse `COMMIT`,
`RuntimeException` çıkarsa `ROLLBACK`.

**Nasıl:** Spring, `@Transactional` gördüğü bean'i bir **proxy** ile sarar. Sen servisi
çağırdığında aslında proxy'yi çağırırsın; o işlemi açar, gerçek metodu çalıştırır,
sonucuna göre commit/rollback yapar.

İki tuzağı:
1. **Aynı sınıf içinden çağrı proxy'yi atlar.** `this.digerTransactional()` işlem açmaz.
2. **Varsayılan olarak sadece `RuntimeException`'da rollback olur**, checked exception'da
   olmaz.

---

## 8. Testler

71 test var. Yapıyı doğru kurmanın somut karşılığı bu: servisler HTTP ve SQL bilmediği
için **veritabanı olmadan, milisaniyelerde** test edilebiliyorlar.

| Sınıf | Ne test ediyor | Nasıl |
|---|---|---|
| `LeaderboardServiceTest` | daha iyi/kötü/eşit run, mod normalizasyonu, hile eşiği, sıralama, limit | Mockito sahte repository |
| `PlayerServiceTest` | isim kuralları, ad üretimi, **iki yarış senaryosu** | Mockito |
| `DeviceAuthFilterTest` | header ayrıştırma, IP doğrulama, `401`, `/health` muafiyeti | `MockHttpServletRequest` |
| `ProgressControllerTest` | status eşlemeleri (200/404/409/413/400) | `@WebMvcTest` — sadece web katmanı |

`@SpringBootTest` (tüm context, yavaş) ile `@WebMvcTest` (sadece web dilimi, hızlı)
farkını görmek için ikisi de var.

**Bir test benim yanlış varsayımımı yakaladı:** bozuk bir IP header'ında `null`
döneceğini sanmıştım, kod soket adresine geri düşüyordu — ki daha doğrusu o. Testi
düzelttim, kodu değil. Testin işi budur.

**Eksik olan: repository testleri.** Optimistic lock ve upsert davranışı ancak gerçek
Postgres'e karşı doğrulanabilir. Testcontainers ile yazılmalı, henüz yok.

---

## 9. Bu projede *kullanmadığımız* şeyler

| Şey | Neden yok |
|---|---|
| **JPA / `@Entity`** | Şimdilik. Mentör kararıyla geçilecek — 2. bölüm |
| **Lombok** (`@Data`, `@Getter`) | Java `record`'ları aynı işi dille yapıyor |
| **Spring Security** | Şimdilik dev-auth yeterli. Gerçek auth gelince |
| **`@Autowired` alan enjeksiyonu** | Constructor injection tercih edildi |
| **Servis arayüzleri** (`XService` + `XServiceImpl`) | Tek implementasyon varken gereksiz katman |
| **OpenAPI / Swagger** | Eksik, eklenmeli |

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
6. **Zor:** `create()` içindeki `ON CONFLICT DO NOTHING` yerine tekrar `try/catch` koysan
   hangi test kırılır? O testi ezbere anlatabiliyor musun?
7. **Zor:** İki cihaz aynı anda `PUT /v1/progress` yollarsa ne olur? İkisi de aynı
   `version`'ı gönderirse hangisi kazanır, diğeri ne alır?

---

## 11. Sonraki okumalar

- **JPA / Spring Data** — sıradaki büyük adım
- **Testcontainers** — repository testleri için
- **Spring Security** — gerçek kimlik doğrulama geldiğinde
- **Profiller** — `application-local.properties` zaten kullanıyoruz, prod'da genişleyecek

Resmî dokümantasyon gerçekten iyi: <https://docs.spring.io/spring-boot/index.html>
