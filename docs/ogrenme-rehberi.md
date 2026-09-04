# Spring Boot öğrenme rehberi — bu projenin kodu üzerinden

Bu dosya, `src/main/java/com/tarikusta/spacesurvivors/` altındaki 13 sınıfın
**neden** öyle yazıldığını anlatır. IntelliJ'de kodu açıp yanına bu dosyayı koy.

Toplam 762 satır kod var. Az görünüyor çünkü Spring Boot işin çoğunu üstleniyor —
rehberin amacı "Spring nereyi hallediyor, ben neyi yazıyorum" ayrımını netleştirmek.

---

## 1. Neden katmanlar var?

Tek bir sınıfa her şeyi yazabilirdik: HTTP'yi karşıla, kuralları uygula, SQL çalıştır.
Küçük projede çalışır. Sorun, işler büyüdüğünde başlar:

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
Service      "kuralları uygula"    → rekoru geçti mi? mode geçerli mi?
    ↓
Repository   "veriyi getir/yaz"    → SQL
    ↓
Veritabanı
```

**Altın kural: oklar tek yöne bakar.** Repository, Service'i tanımaz. Service,
Controller'ı tanımaz. Böylece HTTP'yi tamamen değiştirsen (mesela gRPC'ye geçsen)
Service ve Repository'ye hiç dokunmazsın.

### Somut örnek — bizim `ScoreService`

`kills / survivedSeconds > 60` kuralı Service'te. Neden Controller'da değil?
Çünkü o kural HTTP'yle ilgili değil — oyunun kuralı. Yarın aynı kontrolü bir toplu
içe aktarma işinden çağırırsan, Controller olmadan çağırabilirsin.

Aynı şekilde `ORDER BY survived_seconds DESC` Repository'de. Çünkü o SQL'in işi.
Service sadece "bana ilk 100'ü ver" der, nasıl sıralandığını bilmez.

---

## 2. "Entity" nerede? — Bu projede JPA yok

Spring Boot öğrenirken her yerde şu dörtlüyü görürsün:

> Controller → Service → **Repository (JPA)** → **Entity**

Bizde **Entity yok**, çünkü **JPA/Hibernate kullanmıyoruz**. Bu bilinçli bir karar,
onu anlaman önemli.

### JPA ne yapar?

JPA (Java Persistence API) bir **ORM**'dir — Object-Relational Mapping. Java
nesnelerini otomatik olarak tablo satırlarına çevirir. Böyle görünür:

```java
@Entity                      // "bu sınıf bir tabloya karşılık gelir"
@Table(name = "players")
public class Player {

    @Id
    private String userId;

    @Column(columnDefinition = "jsonb")
    private String profile;

    @Version                 // optimistic locking'i JPA kendi yönetir
    private int version;

    // getter/setter'lar...
}

public interface PlayerRepository extends JpaRepository<Player, String> {
    // SQL yazmadan hazır gelir: save(), findById(), delete()...
    Optional<Player> findByUserId(String userId);   // isimden SQL üretir
}
```

JPA'nın vaadi: SQL yazmazsın, nesnelerle çalışırsın.

### Neden kullanmadık?

| Sebep | Açıklama |
|---|---|
| **Veri modelimiz zaten nesne değil** | `players.profile` tek bir JSONB blob. Onu Java nesnesine açmıyoruz bile — client'tan geldiği gibi saklayıp geri veriyoruz. ORM'in çevireceği bir şey yok. |
| **JSONB ile JPA zahmetli** | `jsonb` standart bir JPA tipi değil. Özel `@Type`, converter veya Hibernate'e özgü anotasyon gerekir. Bizim `CAST(:p AS jsonb)` tek satır. |
| **Sorgularımız SQL'e yakın** | `ON CONFLICT DO UPDATE` (upsert) ve rank hesabındaki korelasyonlu alt sorgu — ikisi de JPA'nın rahat ifade edemediği şeyler. Zaten native SQL yazacaktık. |
| **Gizli davranış yok** | JPA'da lazy loading, dirty checking, N+1 sorgu problemi gibi "arkanda olan işler" vardır. Öğrenirken bunlar kafa karıştırır. `JdbcClient`'ta ne yazarsan o çalışır. |

### Peki JPA'yı ne zaman kullanırdın?

Veri modelin gerçekten nesne grafiği olduğunda. Örnek: `Order` → `OrderItem` → `Product`
ilişkileri, birini çekince diğerlerinin de gelmesi, iç içe kaydetme. Orada JPA çok iş
kurtarır. Bizim 3 tablomuzda kurtaracağı iş yok.

### Bizde "Entity"nin yerini ne tutuyor?

**Java `record`'ları.** Anotasyonsuz, sadece veri taşıyan değişmez sınıflar:

```java
// ProfileRepository.java içinde
public record StoredProfile(String json, int version) { }

// ScoreDtos.java içinde
public record BoardEntry(int rank, String displayName, double survivedSeconds,
                         int kills, int reachedLevel) { }
```

Fark şu: Entity **veritabanı satırıdır** (JPA onu takip eder, değiştirince otomatik
kaydeder). Bizim record'larımız sadece **taşıma kabıdır** — SQL'den okunur, kullanılır,
atılır. Sihir yok.

> **Öğrenme notu:** JPA'yı ayrıca öğrenmen faydalı, çünkü iş ilanlarının çoğunda geçer.
> Ama bu projede kullanmadığımız için burada zorlama. Küçük bir yan proje aç, orada
> `@Entity` + `JpaRepository` ile dene.

---

## 3. Spring'in sihri: Bean'ler ve Dependency Injection

Kodda hiçbir yerde `new ProfileService(...)` yazmadım. Ama çalışıyor. Nasıl?

### Bean nedir?

Spring açılışta paketleri tarar, belirli anotasyonlara sahip sınıfları bulur, onlardan
**birer nesne üretir** ve bir havuzda tutar. O nesnelere **bean** denir, havuza da
**application context** (IoC container).

Bean üreten anotasyonlar:

| Anotasyon | Anlamı | Bizdeki örnek |
|---|---|---|
| `@RestController` | HTTP uçları | `ProfileController`, `ScoreController`, `HealthController` |
| `@Service` | İş kuralları | `ProfileService`, `ScoreService`, `UserService` |
| `@Repository` | Veri erişimi | `ProfileRepository`, `ScoreRepository` |
| `@Component` | Genel amaçlı | `DevAuthFilter` |
| `@RestControllerAdvice` | Global hata yakalayıcı | `ApiExceptionHandler` |

**Önemli:** `@Service`, `@Repository` ve `@Component` teknik olarak neredeyse aynı şeyi
yapar (hepsi `@Component`'in özelleşmiş hâli). Farkları **niyet belirtmek** — kodu okuyan
biri `@Service` görünce "burada iş kuralı var" der. `@Repository` ek olarak veritabanı
istisnalarını Spring'in kendi istisna tiplerine çevirir.

### Dependency Injection nedir?

Bir bean'in ihtiyaç duyduğu diğer bean'leri Spring **kendisi verir**. Bizim
`ProfileService`'e bak:

```java
@Service
public class ProfileService {

    private final ProfileRepository profiles;
    private final UserService users;
    private final ObjectMapper json;

    public ProfileService(ProfileRepository profiles, UserService users, ObjectMapper json) {
        this.profiles = profiles;
        this.users = users;
        this.json = json;
    }
}
```

Spring şunu yapar: "`ProfileService` bean'ini üretmem lazım. Yapıcı metodu üç şey
istiyor. `ProfileRepository` var mı? Var. `UserService`? Var. `ObjectMapper`? Spring
Boot bunu Jackson yapılandırmasından otomatik üretmiş, o da var. Tamam, hepsini verip
nesneyi oluşturuyorum."

Buna **constructor injection** denir ve tercih edilen yöntemdir:
- `final` yapabilirsin → nesne oluşturulduktan sonra değişemez
- Test yazarken elle `new ProfileService(sahteRepo, sahteUser, mapper)` diyebilirsin
- Bağımlılık eksikse **açılışta** patlar, çalışma anında değil

> Eski eğitimlerde `@Autowired` ile alan enjeksiyonu görürsün. Artık önerilmiyor —
> tek yapıcı metot varsa `@Autowired` yazmana bile gerek yok, Spring anlar.

### `JdbcClient` nereden geldi?

`ProfileRepository`'nin yapıcısı `JdbcClient` istiyor ama onu hiçbir yerde üretmedik.
Zinciri şöyle:

1. `application.properties` → `spring.datasource.url/username/password`
2. Spring Boot bunu görüp bir **`DataSource`** bean'i üretir (HikariCP bağlantı havuzu)
3. `spring-boot-starter-jdbc` bağımlılığı var → Spring Boot `DataSource`'tan bir
   **`JdbcClient`** bean'i üretir
4. Bizim repository onu ister, Spring verir

Buna **auto-configuration** denir: "şu bağımlılık var + şu ayar var → şu bean'leri kur".
Spring Boot'un asıl gücü budur.

---

## 4. Sınıf sınıf: bizim kod

### `SpacesurvivorsApplication` (13 satır)

```java
@SpringBootApplication
public class SpacesurvivorsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpacesurvivorsApplication.class, args);
    }
}
```

`@SpringBootApplication` üç anotasyonun kısaltması:
- `@ComponentScan` — **bu sınıfın paketinden aşağıya** doğru tarar, bean'leri bulur.
  Bu yüzden tüm kodumuz `com.tarikusta.spacesurvivors` altında olmak zorunda.
- `@EnableAutoConfiguration` — yukarıdaki auto-configuration'ı açar
- `@SpringBootConfiguration` — bu sınıfın kendisi de yapılandırma kaynağı olabilir

`SpringApplication.run(...)` → context'i kurar, bean'leri üretir, Tomcat'i başlatır.

---

### `DevAuthFilter` (40 satır) — katmanların *dışında*

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DevAuthFilter extends OncePerRequestFilter {

    public static final String USER_ID = "userId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("X-Dev-User");
        String userId = (header == null || header.isBlank()) ? "dev-user" : header.trim();
        request.setAttribute(USER_ID, userId);
        chain.doFilter(request, response);
    }
}
```

**Filter nedir?** İstek Controller'a varmadan **önce** çalışan ara katman. Zincir
şeklinde dizilirler:

```
İstek → Filter1 → Filter2 → DispatcherServlet → Controller
```

`chain.doFilter(request, response)` = "benden sonraki halkaya geç". Bunu çağırmazsan
istek Controller'a hiç ulaşmaz — auth reddi böyle yapılır.

`OncePerRequestFilter`, Spring'in yardımcı sınıfı: bir istek içeride yönlendirilirse
(forward) filtrenin iki kez çalışmasını engeller.

`@Order(HIGHEST_PRECEDENCE)` = zincirin en başında olsun. Mantıklı, çünkü "sen kimsin"
sorusu her şeyden önce cevaplanmalı.

**Neden filtre, neden her Controller'da `@RequestHeader` okumadık?**
Çünkü yarın gerçek token doğrulaması geldiğinde **sadece bu dosya** değişecek.
Controller'lar `userId`'nin nereden geldiğini bilmiyor — sadece "istekte bir `userId`
var" biliyorlar. Buna **indirection seam** denir: değişimi tek noktaya hapsetmek.

---

### `HealthController` (35 satır) — en basit Controller

```java
@RestController
public class HealthController {

    private final JdbcClient db;

    public HealthController(JdbcClient db) { this.db = db; }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Integer one = db.sql("SELECT 1").query(Integer.class).single();
        return Map.of("status", "UP", "db", (one != null && one == 1) ? "UP" : "DOWN");
    }
}
```

- `@RestController` = `@Controller` + `@ResponseBody`. İkincisi şu demek: metodun
  döndürdüğü nesne **doğrudan cevap gövdesi olur** (Jackson JSON'a çevirir). Klasik
  `@Controller`'da dönen `String` bir HTML şablonunun adı sayılırdı.
- `@GetMapping("/health")` = "GET /health gelirse bu metodu çalıştır"

Bu sınıf katman kuralını bilerek çiğniyor — Controller doğrudan `JdbcClient` kullanıyor,
arada Service/Repository yok. Sebep: yapacağı iş sadece "bağlantı ayakta mı?" kontrolü.
Bunun için üç sınıf açmak gereksiz olurdu. **Kurallar amaca hizmet eder, tersi değil.**

---

### `ProfileController` (64 satır) — Controller'ın gerçek işi

```java
@RestController
@RequestMapping("/v1/profile")
public class ProfileController {

    @GetMapping
    public ResponseEntity<Object> load(@RequestAttribute("userId") String userId) {
        return service.load(userId)
                .<ResponseEntity<Object>>map(p -> ResponseEntity.ok(
                        Map.of("profile", p.profile(), "version", p.version())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "no profile stored yet")));
    }

    @PutMapping
    public ResponseEntity<Object> save(@RequestAttribute("userId") String userId,
                                       @RequestBody SaveRequest body) { ... }

    public record SaveRequest(JsonNode profile, int version) { }
}
```

**Controller'ın tek işi HTTP'yi çevirmek.** Dikkat et: burada tek bir iş kuralı yok.
"Rekoru geçti mi", "sürüm uyuyor mu" — hiçbiri burada değil.

Anotasyonlar:

| Anotasyon | İşi |
|---|---|
| `@RequestMapping("/v1/profile")` | Sınıf seviyesi ön ek. Metotlardaki `@GetMapping` boş kalabiliyor. |
| `@GetMapping` / `@PutMapping` | Hangi HTTP fiiline cevap veriyor |
| `@RequestAttribute("userId")` | Filtrenin isteğe iliştirdiği değeri parametre olarak al |
| `@RequestBody` | Gövdedeki JSON'u Java nesnesine çevir (Jackson yapar) |
| `@RequestParam` | URL'deki `?mode=infinite` değerini al (`ScoreController`'da) |
| `@PathVariable` | `/users/{id}` gibi yoldaki değişkeni al (bizde yok, bilmen için) |

**`ResponseEntity` neden?** Normalde metottan bir nesne döndürürsün, Spring `200 OK`
yapar. Ama biz duruma göre `200` veya `404` döndürmek istiyoruz. `ResponseEntity` =
"gövde **ve** status kodu **ve** header'lar" paketi.

**`SaveRequest` record'u neden Controller'ın içinde?** Çünkü sadece bu endpoint'in
gövde şeklini tanımlıyor. Başka kimseyi ilgilendirmiyor — kullanıldığı yerde dursun.

---

### `ProfileService` (95 satır) — kuralların yeri

```java
@Service
public class ProfileService {

    private static final int MAX_PROFILE_BYTES = 64 * 1024;

    @Transactional
    public SaveOutcome save(String userId, JsonNode profile, int clientVersion) {
        ObjectNode toStore = (ObjectNode) profile;
        toStore.put("userId", userId);              // kural 1: id'yi sunucu basar
        String profileJson = json.writeValueAsString(toStore);

        if (profileJson.getBytes(UTF_8).length > MAX_PROFILE_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "profile too large");
        }                                            // kural 2: boyut sınırı

        users.touch(userId);                         // kural 3: users satırı garanti

        Optional<StoredProfile> existing = profiles.find(userId);
        if (existing.isEmpty()) {
            profiles.insert(userId, profileJson);
            return SaveOutcome.saved(1);             // kural 4: ilk kayıt → v1
        }

        Optional<Integer> newVersion = profiles.update(userId, profileJson, clientVersion);
        if (newVersion.isPresent()) {
            return SaveOutcome.saved(newVersion.get());
        }

        StoredProfile current = existing.get();
        return SaveOutcome.conflict(current.version(), json.readTree(current.json()));
    }
}
```

Burada **beş ayrı karar** var ve hiçbiri HTTP'yle ya da SQL'le ilgili değil. Service
katmanının varlık sebebi bu.

#### `@Transactional` — en önemli anotasyonlardan biri

Bu metotta üç ayrı DB işlemi var: `users.touch()`, `profiles.find()`, `profiles.update()`.
`@Transactional` olmadan her biri ayrı ayrı, anında kalıcı olurdu. Sorun:

> `users.touch()` başarılı oldu → `profiles.update()` patladı
> Sonuç: kullanıcı satırı var ama profil yok. Yarım kalmış durum.

`@Transactional` üçünü **tek bir veritabanı işlemine** sarar:
- Metot normal biterse → `COMMIT` (hepsi kalıcı)
- Metottan bir `RuntimeException` çıkarsa → `ROLLBACK` (hiçbiri olmamış sayılır)

**Nasıl çalışıyor?** Spring, `@Transactional` gördüğü bean'i bir **proxy** ile sarar.
Sen `profileService.save(...)` çağırdığında aslında proxy'yi çağırırsın; o transaction'ı
açar, gerçek metodu çalıştırır, sonucuna göre commit/rollback yapar.

Bunun iki tuzağı var, bilmende fayda var:
1. **Aynı sınıf içinden çağrı proxy'yi atlar.** `save()` içinden `this.baskaTransactional()`
   çağırırsan transaction açılmaz.
2. **Varsayılan olarak sadece `RuntimeException`'da rollback olur**, checked exception'da
   olmaz (`@Transactional(rollbackFor = ...)` ile değiştirilir).

---

### `ProfileRepository` (57 satır) — SQL'in yeri

```java
@Repository
public class ProfileRepository {

    public Optional<StoredProfile> find(String userId) {
        return db.sql("SELECT profile::text AS profile, version FROM players WHERE user_id = :id")
                .param("id", userId)
                .query((rs, rowNum) -> new StoredProfile(rs.getString("profile"), rs.getInt("version")))
                .optional();
    }

    public Optional<Integer> update(String userId, String profileJson, int expectedVersion) {
        int rowsChanged = db.sql("""
                UPDATE players
                SET profile = CAST(:p AS jsonb), version = version + 1
                WHERE user_id = :id AND version = :v
                """)
                .param("id", userId).param("p", profileJson).param("v", expectedVersion)
                .update();
        return rowsChanged == 1 ? Optional.of(expectedVersion + 1) : Optional.empty();
    }
}
```

`JdbcClient` zinciri:

| Parça | İşi |
|---|---|
| `.sql("...")` | Sorgu metni. `:id` = **isimli parametre** |
| `.param("id", userId)` | Parametreyi bağla |
| `.query(RowMapper)` | Her satırı bir nesneye çevir |
| `.optional()` | 0 veya 1 satır bekle → `Optional` |
| `.list()` | N satır bekle → `List` |
| `.single()` | Tam 1 satır bekle, yoksa hata |
| `.update()` | Yazma işlemi. **Etkilenen satır sayısını** döndürür |

#### `:id` neden önemli — SQL Injection

Şunu **asla** yapma:

```java
db.sql("SELECT * FROM players WHERE user_id = '" + userId + "'")   // TEHLİKELİ
```

Kullanıcı `userId` yerine `' OR '1'='1` yollarsa sorgu tüm tabloyu döndürür. Daha
kötüsü `'; DROP TABLE players; --` yollayabilir.

`:id` + `.param(...)` kullanınca sürücü, değeri **sorgu metnine karıştırmaz** — ayrı
kanaldan gönderir. Değer ne olursa olsun veri olarak kalır, komut olamaz.

#### `.update()`'in dönüş değeri = optimistic locking

`update()` metodumuz `rowsChanged == 1` kontrolü yapıyor. İşin tüm inceliği burada:

```sql
WHERE user_id = :id AND version = :v
```

Başka biri araya girip sürümü değiştirdiyse bu `WHERE` hiçbir satıra uymaz → 0 satır
etkilenir → biz bunu "çakışma" olarak yorumlarız. Ayrıca kilit almadık, bekleme yok.
Adı bu yüzden **optimistic** (iyimser): "muhtemelen çakışma olmaz, olursa fark ederim".

---

### `ScoreDtos` (73 satır) — DTO nedir?

**DTO** = Data Transfer Object. Katmanlar veya sistemler arasında veri taşıyan,
davranışı olmayan sınıf.

```java
public record Submission(
        @NotBlank String mode,
        @PositiveOrZero @DecimalMax("21600") double survivedSeconds,
        @PositiveOrZero int kills,
        @Min(1) int reachedLevel,
        @PositiveOrZero int bossesDefeated
) { }
```

**Neden DTO, neden doğrudan tablo sınıfını kullanmıyoruz?**
- İstemciye göstermek istemediğin alanlar olabilir (`user_id` boarda görünmüyor)
- İstemciden almak istemediğin alanlar olabilir (`version`'ı client belirleyemez)
- API'nin şekli ile tablonun şekli **bağımsız evrilebilmeli**

Bizim `BoardEntry`'de `rank` var ama tabloda böyle bir kolon yok — hesaplanıyor.
`user_id` tabloda var ama DTO'da yok — gizli. Tam da bu yüzden ayrı sınıflar.

**Bean Validation anotasyonları:** Controller'da `@Valid` yazılınca Spring bunları
metot çalışmadan önce kontrol eder. Geçmezse `MethodArgumentNotValidException` fırlar,
`ApiExceptionHandler` onu `400` + alan adlarına çevirir.

Kural: **tek alana bakarak karar verilebiliyorsa anotasyon, birden fazla alanı
karşılaştırman gerekiyorsa Service.** Bu yüzden `kills / survivedSeconds > 60` kontrolü
`ScoreService`'te, anotasyonla değil.

---

### `ApiException` + `ApiExceptionHandler` — merkezî hata yönetimi

```java
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handle(ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) { ... }
}
```

`@RestControllerAdvice` = "tüm `@RestController`'ları dinle". Herhangi bir Controller
veya onun çağırdığı herhangi bir Service'ten `ApiException` fırlarsa buraya düşer.

**Faydası:** `ScoreService`'in derinlerinde `throw new ApiException(422, "kill rate...")`
diyebiliyoruz. O istisna Service'ten Controller'a, oradan Spring'e kadar yükseliyor ve
burada düzgün bir HTTP cevabına dönüşüyor. Ara katmanların hiçbiri hata yönetimi kodu
içermiyor.

Bu olmasaydı her metot `try/catch` ile dolardı.

---

## 5. Bir isteğin tam yolculuğu

`PUT /v1/profile` isteğini baştan sona izleyelim:

```
 1. Tomcat          8080'de HTTP isteğini alır
 2. DevAuthFilter   "X-Dev-User" header'ını okur → request'e userId = "alice" koyar
                    chain.doFilter() → devam
 3. DispatcherServlet  URL + method'a bakar: "PUT /v1/profile" kime ait?
                       → ProfileController.save()
 4. Argüman çözümü  @RequestAttribute("userId") → "alice"
                    @RequestBody SaveRequest → Jackson JSON'u record'a çevirir
 5. ProfileController.save()
                    profile bir JSON objesi mi? değilse → ApiException(400)
                    → service.save("alice", profile, 0)
 6. Proxy           @Transactional gördü → BEGIN
 7. ProfileService.save()
                    profile'a userId bas
                    boyut kontrolü
                    → users.touch("alice")         → INSERT ... ON CONFLICT
                    → profiles.find("alice")       → SELECT
                    kayıt yoksa → profiles.insert() → INSERT
                    → SaveOutcome.saved(1)
 8. Proxy           istisna yok → COMMIT
 9. ProfileController  outcome.isConflict()? hayır
                       → ResponseEntity.ok(Map.of("version", 1))
10. Jackson         Map'i JSON'a çevirir: {"version":1}
11. Tomcat          200 OK + gövdeyi yollar
```

Her adımda **kimin ne bildiğine** dikkat et:
- Filter, profil diye bir şey olduğunu bilmiyor
- Controller, SQL diye bir şey olduğunu bilmiyor
- Service, HTTP diye bir şey olduğunu bilmiyor
- Repository, kuralları bilmiyor

Bu ayrım sağlandığı sürece kod büyüdükçe karmaşıklaşmaz.

---

## 6. Bu projede *kullanmadığımız* şeyler

Eğitimlerde göreceğin ama bizde olmayan şeyler ve sebepleri:

| Şey | Neden yok |
|---|---|
| **JPA / `@Entity`** | Yukarıda anlatıldı — veri modelimiz nesne grafiği değil |
| **Lombok** (`@Data`, `@Getter`) | Java `record`'ları aynı işi dille yapıyor, ek kütüphane gerekmiyor |
| **Spring Security** | Şimdilik dev-auth yeterli. Gerçek auth gelince eklenecek |
| **DTO ↔ Entity mapper** (MapStruct) | Entity yok, çevrilecek bir şey yok |
| **`@Autowired` alan enjeksiyonu** | Constructor injection tercih edildi (test edilebilirlik) |
| **`application.yml`** | `.properties` seçildi (sen öyle üretmiştin), fark yok |
| **Servis arayüzleri** (`ProfileService` + `ProfileServiceImpl`) | Tek implementasyon varken arayüz açmak gereksiz katman. İkinci implementasyon gerektiğinde eklenir |

Son maddeyi biraz aç: eski Java projelerinde her servisin bir `interface`'i ve bir
`Impl`'i olurdu. Sebebi eski test araçlarının sınıfları taklit edememesiydi. Modern
Mockito sınıfları da taklit edebiliyor, o yüzden bu alışkanlık artık gereksiz. Arayüz,
**gerçekten birden fazla implementasyon olduğunda** açılır — Unity tarafındaki
`IProfileStore` gibi (local ve HTTP olmak üzere iki implementasyonu olacak).

---

## 7. Kendini sınama

Kodu anladığını şunlarla test et:

1. **Kolay:** `GET /v1/scores`'a `limit` yerine `page` parametresi eklemek isteseydin
   hangi dosyalara dokunurdun?
2. **Kolay:** `MAX_KILLS_PER_SECOND` değerini 30'a düşür, derle, Postman'de test et.
3. **Orta:** `DELETE /v1/profile` endpoint'i eklemek isteseydin hangi sınıflara ne
   eklerdin? (Controller'a metot, Service'e metot, Repository'ye `DELETE` sorgusu)
4. **Orta:** `ProfileService.save()`'ten `@Transactional`'ı kaldırsan hangi senaryoda
   veri tutarsız kalır?
5. **Zor:** `leaderboard_entries`'e "en iyi 3 run" saklamak isteseydin (tek best yerine)
   şema ve upsert mantığı nasıl değişirdi?
6. **Zor:** İki cihaz aynı anda `PUT /v1/profile` yollarsa ne olur? İkisi de aynı
   `version`'ı gönderirse hangisi kazanır, diğeri ne alır?

---

## 8. Sonraki adımlar için okuma

Bu projede karşılaşacağın sıradaki kavramlar:

- **Testler** — `@SpringBootTest` (tüm context) vs `@WebMvcTest` (sadece web katmanı)
  vs düz birim testi. Şu an sadece bir "context yükleniyor mu" testi var.
- **Profiller** — `application-dev.properties` / `application-prod.properties` ile
  ortama göre farklı ayar
- **Spring Security** — gerçek kimlik doğrulama geldiğinde
- **Actuator** — `/actuator/health` dışında metrikler, uygulama bilgisi

Resmî dokümantasyon gerçekten iyi: <https://docs.spring.io/spring-boot/index.html>
