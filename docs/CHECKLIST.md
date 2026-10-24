# union-android — checklista implementacji

Kopia `union-ios/docs/CHECKLIST.md` (0.4.0) dla Androida: **każda różnica względem iOS jest oznaczona `[ANDROID]`**, a reszta wiersza znaczy to samo, co na iOS. Umowa między SDK: `docs/Engineering/SDK-PLATFORMS.md` w repo `Union`.

Jedno miejsce, w którym jest napisane **co SDK loguje, kiedy i w jakim kształcie**. Zanim dodasz event, integrację albo pole kontekstu: znajdź je tutaj. Jeśli nie ma — najpierw dopisz wiersz, potem implementuj.

Źródło prawdy dla kształtu paczki: `packages/contract/src/event-batch.ts` w repo `Union` (JSON Schema: `pnpm --filter @union/contract schema:json`, kopia w `union/src/test/resources/event-batch.v1.json`). Ten plik nie definiuje kontraktu — opisuje, co z niego mamy wyemitować.

Stan: `[x]` zaimplementowane i pokryte testem, `[~]` zaimplementowane bez testu, `[ ]` do zrobienia.

## 0. Różnice względem iOS (zbiorczo)

Wszystko poniżej jest też zaznaczone `[ANDROID]` w wierszu, którego dotyczy.

| Obszar | iOS | Android |
|---|---|---|
| Reinstalacja | id w Keychain przeżywa deinstalację, `$app_install { reinstall: true }` | nic nie przeżywa deinstalacji: **`reinstall` nigdy nie jest wysyłane**, reinstalacja = nowa instalacja i nowy `install_id`. Stan SDK leży w `noBackupFilesDir`, nie w SharedPreferences, bo te jadą w Auto Backup na nowy telefon |
| Środowisko | DEBUG → `development`, sandbox receipt / StoreKit → `testflight` | `FLAG_DEBUGGABLE` aplikacji → `development`, inaczej `production`. Play nie mówi aplikacji, na jakim torze jest (internal / closed testing), więc `testflight` tylko z `Options.environment` |
| Ekrany | `.trackScreen` / swizzling `viewDidAppear` | Compose `TrackScreen("Name")` (moduł `union-compose`), `ActivityLifecycleCallbacks` (prosta nazwa klasy aktywności, `onResume`, rotacja nie liczy się) gdy `Options.automaticScreenTracking`, `Union.screen` |
| Deep linki | `handleDeepLink(URL)` | `handleDeepLink(Uri)` i `handleDeepLink(Intent)` (`Intent.data`); query i fragment nigdy nie wyjeżdżają |
| Powrót z tła | `UIApplication` notifications | `ProcessLifecycleOwner` (`ON_START` / `ON_STOP`, ten ostatni ~700 ms po ostatniej aktywności). **Brak `willTerminate`**: zabicie procesu nie daje `$session_end`, o końcu sesji rozstrzyga timeout serwera |
| Hangi / ANR | `hang` (watchdog na wątku, stos main threada przez Mach) | `hang` z watchdoga na `Looper` głównego wątku (≥ 2 s, raport po zakończeniu epizodu ze zmierzonym czasem) **i** `anr` z `ApplicationExitInfo.REASON_ANR` (API 30+) przy następnym starcie |
| Crashe | handler sygnałów w C + Mach + `NSException`, rekord binarny + sidecar | `Thread.setDefaultUncaughtExceptionHandler` łańcuchowo do poprzedniego; **cały raport** (JSON) zapisywany synchronicznie w handlerze; ramki zarządzane (JVM), bez obrazów binarnych |
| Symbole | dSYM (`atos`) | `mapping.txt` z R8 wysyłany przez wtyczkę Gradle, klucz łączący to `build_id` z `assets/union-build-id.txt` |
| Nie-fatale | zapis na dysk, wysyłka przy następnym starcie | zapis na dysk **i od razu próba wysyłki w procesie** |
| Opt-out a crashe | `optOut()` zatrzymuje handler do końca procesu; po restarcie handler wraca | zapisany opt-out obowiązuje też crash reporting po restarcie; `optIn()` uzbraja handler z powrotem |
| Backoff | pierwsze opóźnienie ≈ 4 s × jitter (próba liczona przed obliczeniem) | pierwsze opóźnienie 2 s × jitter 0,5–1,0, potem 4, 8 ... do 5 min (tak, jak mówi `SDK-PLATFORMS.md`) |
| Powiązanie zakupów | `Product.PurchaseOption.appAccountToken = Union.installUUID` | Play Billing `setObfuscatedAccountId(Union.installId)`; nie ma `installUUID` |
| `Options.analyticsCollectionEnabled` | deprecated od 0.3.3 | **nie przeniesione** (zbieranie startuje zawsze w `configure`; odmowa to `optOut()` / `setAnalyticsCollectionEnabled(false)`) |
| `flush()` | `async` | `suspend fun flush()`, `flush(Runnable)` i `flushBlocking(timeoutMs)` |
| Pusta nazwa ekranu | wysyłana | **[ANDROID]** odrzucana (kontrakt wymaga niepustego `screen` dla zadeklarowanego `$screen_view`) |
| `in_app` ramki | n/d (obrazy binarne, `is_app`) | klasa zaczyna się od `context.packageName` + `.` albo od jednego z `Options.inAppPackages` (granica po kropce: `com.example` nie łapie `com.examplefoo`) |

## 1. Eventy automatyczne (prefiks `$`, nazwy zarezerwowane)

Nazwa musi pochodzić z `AutoEventName`. Klient nigdy nie może wysłać `$…` (`Validation.reservedName`).

| Event | Kiedy | Właściwości | Stan |
|---|---|---|---|
| `$first_open` | pierwsze uruchomienie na urządzeniu (brak zapisanego `version`/`build`). **[ANDROID]** stan leży w `noBackupFilesDir/Union/<hash>/state.json`, więc znika z deinstalacją | — | [x] |
| `$app_install` | razem z `$first_open` | **[ANDROID]** brak właściwości: `reinstall` nigdy nie jest wysyłane (nic nie przeżywa deinstalacji) | [x] |
| `$app_update` | zmiana `app_version`/`app_build` względem zapisanych | `previous_version`, `previous_build` | [x] |
| `$session_start` | start nowej sesji (zimny start, rotacja po 30 min bezczynności, powrót z tła po timeout) | — | [x] |
| `$session_end` | zamknięcie poprzedniej sesji; `timestamp` = `lastActivityAt`, nie „teraz” | — | [x] |
| `$foreground` | powrót do aktywności w tej samej sesji. **[ANDROID]** `ProcessLifecycleOwner` `ON_START`; powtórka `ON_START` przy `configure` wywołanym, gdy proces już jest wystartowany, jest ignorowana (na iOS `willEnterForeground` nie odpala przy zimnym starcie) | — | [x] |
| `$background` | wejście w tło (poprzedzone flushem). **[ANDROID]** `ON_STOP` | — | [x] |
| `$screen_view` | `Union.screen(name)` / **[ANDROID]** Compose `TrackScreen` / `ActivityLifecycleCallbacks.onResume` (prosta nazwa klasy, ≤ 128; klasy anonimowe pomijane; odtworzenie tej samej klasy po zmianie konfiguracji nie liczy się) | `screen` = nazwa; opcjonalne właściwości wywołania | [x] |
| `$deep_link` | `Union.handleDeepLink(uri)` / **[ANDROID]** `handleDeepLink(intent)` (`Intent.data`; intent bez danych ignorowany) | `url_scheme`, `host`, `path` — **nigdy** query ani fragment (PII); URI nieprzezroczysty (`mailto:`) daje `path: ""` | [x] |

Reguły, których nie łamiemy:
- Kolejność w paczce jest chronologiczna, z jednym udokumentowanym wyjątkiem: `$session_end` starej sesji zawsze
  przed `$session_start` nowej, ale przy zimnym starcie `$first_open`/`$app_install`/`$app_update` idą **przed**
  `$session_start` swojej sesji (mają już jej `session_id`) — to celowe i zabetonowane testem
  `coldStartEmitsInstallAndSessionStartInTheDocumentedOrderAndBatchConformsToSchema` (**[ANDROID]** w `PipelineTests`).
- Każdy event ma `session_id` żywej sesji; eventy domykające dostają `session_id` sesji, którą domykają.
- `event_id` i `session_id` to UUIDv7 z SDK; retry zachowuje `event_id` (serwer deduplikuje).
- Timeout sesji (30 min) liczy SDK **i** serwer — obie strony muszą dawać ten sam wynik. **[ANDROID]** nie ma odpowiednika `willTerminate`: proces zabity w tle nie wysyła `$session_end`, serwer zamyka sesję po timeoucie.
- `$…` nie ma wariantu „custom” — nowy event automatyczny wymaga wpisu w `AutoEventName` w repo `Union` **przed** implementacją tutaj.

## 1a. Eventy klienta, których nazwę czyta serwer

Trzecia kategoria, obok `$auto` i dowolnych eventów aplikacji: nazwa jest **dowolna z punktu widzenia
kontraktu** (pasuje do `^[a-z][a-z0-9_]*$`), ale rollup w repo `Union` szuka jej dosłownie. Literówka
albo własny synonim nie jest błędem walidacji — event przechodzi, a metryka po drugiej stronie po
prostu nigdy nie rośnie. Cicha awaria, więc nazwy są tutaj, nie w cudzej głowie.

| Event | Kto emituje | Właściwości | Co się psuje bez niego | Stan |
|---|---|---|---|---|
| `survey_displayed` | aplikacja, z callbacku `surveyDisplayed` Survicate | `survey_id` (string) | mianownik response rate w widoku NPS; panel mówi wtedy „unknown", nigdy nie zgaduje | [ ] |
| `survey_closed` | aplikacja, z callbacku `surveyClosed` Survicate | `survey_id` (string) | rozróżnienie „zamknął bez odpowiedzi" od „nie zobaczył" | [ ] |

Dlaczego to nie są eventy `$auto`: emituje je aplikacja z callbacku **cudzego** SDK, nie Union SDK
z własnego cyklu życia. Union nie linkuje Survicate i nie ma z czego swizzlować.

- [ ] rozważyć `Union.trackSurveyDisplayed(surveyId:)` / `…Closed` — cienkie wrappery na `track`,
      których jedyna wartość to zabetonowanie nazwy i klucza właściwości w kompilatorze zamiast
      w tym akapicie. Do zrobienia, gdy pojawi się druga aplikacja emitująca te eventy ręcznie.

## 1b. Deklaracja feature'a i roli

Trzecia rzecz, jaką event może nieść obok nazwy i właściwości: **do którego feature'a należy i w jakiej
roli**. To nie podpowiedź dla panelu — to definicja. Serwer (repo `Union`, `syncDeclaredFeatures`) tworzy
z niej feature albo dorzuca brakujące role do istniejącego, bez skrzynki sugestii. Dlatego pomyłka tutaj
nie jest błędem walidacji, tylko drugim feature'em w panelu.

| Element | Co robi SDK | Stan |
|---|---|---|
| `feature` na eventcie | opcjonalny klucz (`^[a-z][a-z0-9_]*$`, ≤ 64 — ten sam alfabet co nazwa eventu, `Limits.featureKeyPattern`), przez `Union.track(name, feature = key, role = role)` | [x] |
| `role` na eventcie | jak dotąd (`discovery/start/use/success/failure`); bez `feature` tylko wypełnia edytor w panelu | [x] |
| `feature` na `$screen_view` | `Union.screen(name, feature = key)` / `TrackScreen(name, feature = key)` — jedyny event `$…`, który może nieść klucz; SDK dopisuje `role: .discovery`, bo widok ekranu jest pierwszym krokiem leja. Inne `$…` z kluczem serwer odrzuca | [x] |
| Uchwyt `Union.feature(key)` | `Feature` — klucz pisany raz, rola wynika z metody (`.screen/.discovery/.start/.use/.success/.failure`). Cienka nakładka na `track`/`screen`; klucz walidowany przy tworzeniu, żeby zły był zalogowany raz, nie per event | [x] |
| `screen` na dowolnym eventcie | `Union.track(name, screen = ...)` — opcjonalna nazwa ekranu (≤ 128) obok nazwy eventu; nie emituje `$screen_view` i nie liczy się do `screen_count`, tylko mówi **gdzie** event się stał | [x] |
| Zły klucz | **porzuca cały event**, nigdy nie wysyła go bez `feature` — event obdarty z feature'a, dla którego został napisany, czyta się po drugiej stronie jako „nie należy do żadnego" | [x] |

Reguły, których nie łamiemy:
- **Jeden event = jedna para (feature, rola) w kodzie.** Dwie różne pary dla tej samej nazwy to konflikt,
  który serwer *pokazuje* w skrzynce (`declared_conflict`) i nie rozstrzyga. Nie ma „ostatnia wygrywa".
- Tworzą wyłącznie buildy `production`/`testflight`. Build `development` tylko wypełnia edytor — można
  eksperymentować z nazwami bez mnożenia feature'ów.
- Sync serwera jest addytywny: dorzuca, nigdy nie usuwa ani nie zmienia roli ustawionej w panelu. Zmiana
  roli w kodzie po tym, jak panel ją poprawił, nic nie zrobi — to celowe.
- Nazwa feature'a w panelu to na start title-case klucza (`cart_item` → „Cart item"). Ładna nazwa to
  edycja w panelu, nie drugi klucz.

## 1c. Semantyka ról

Sekcja 1b mówi, **jak** deklarować rolę, i nigdzie nie mówi, **co która znaczy**. `FeatureRole` to w
`Options.kt` goły enum `Discovery, Start, Use, Success, Failure` z jednym zdaniem komentarza, a
jedyny opis w iOS był `// discovery — saw the screen` w przykładzie README (**[ANDROID]** KDoc `FeatureRole` odsyła tutaj). To nie jest luka
kosmetyczna: rola, której nikt nie zdefiniował, staje się w aplikacji tym, co akurat pasowało. Pierwszy
konsument (Boardly) wypełnił ją tak, że `.use` został kubłem domyślnym, żaden feature poza jednym nie
miał `start`, nic nigdy nie zgłosiło `failure`, a `onboarding_completed` dostało `success` albo `failure`
zależnie od wartości pola — czyli dokładnie `declared_conflict` z sekcji 1b. Panel pokazywał wtedy same
liczniki ruchu i ani jednego leja.

| Rola | Co znaczy | Typowy event | Stan |
|---|---|---|---|
| `discovery` | **zobaczył, że to istnieje**, i jeszcze nie spróbował. Nie liczy się do prób — dlatego `$screen_view` z kluczem dostaje ją automatycznie | `$screen_view`, otwarcie menu, dotknięcie chipa | [x] |
| `start` | **podjął próbę, wynik nieznany.** To jest mianownik leja; feature bez `start` nie ma leja, tylko licznik | `checkout_started`, fokus w polu, otwarcie pickera | [x] |
| `use` | **pracuje w środku feature'a.** Może wystąpić wielokrotnie w jednej próbie i **nie jest etapem leja** — mierzy głębokość użycia, nie postęp | `shipping_selected`, zmiana właściwości | [x] |
| `success` | **feature dał to, po co się po niego sięgnęło.** Licznik leja. Jeden na próbę, nie jeden na kliknięcie | `order_placed`, plik dodany, lista odhaczona | [x] |
| `failure` | **próba zakończona niepowodzeniem z punktu widzenia użytkownika**: odmowa uprawnienia, zero wyników, nieudany zapis. Nie każdy wyjątek w logu — od tego są `recordError` i raporty awarii | `payment_failed`, `search_no_results`, odmowa powiadomień | [x] |

Reguły, których nie łamiemy:
- **Rezygnacja to osobna nazwa eventu, nigdy `failure` pod nazwą, która gdzie indziej znaczy `success`.**
  To wprost konflikt z sekcji 1b: serwer go pokazuje i nie rozstrzyga. `onboarding_completed` i
  `onboarding_abandoned` to dwa eventy, nie jeden z polem `skipped`.
- **Feature to coś, czego użytkownik może nie zaadaptować.** Klucz nazwany od tabeli w bazie (`items`,
  `users`) ma zawsze 100% adopcji i zero informacji — każdy go dotyka w pierwszej minucie. Jeśli event
  należy do rdzenia produktu, lepiej wysłać go **bez** klucza niż wymyślić feature, którego nie da się nie
  użyć.
- **Feature bez `failure` nie pokaże, gdzie ludzie odpadają.** Jeśli próba może się nie udać w sposób,
  który użytkownik zauważa, ta ścieżka ma swój event — inaczej lej jest z definicji stuprocentowy.
- **Zadeklarowany feature bez ruchu jest gorszy niż jego brak**, bo w panelu czyta się jako zerowa
  adopcja, a nie jako „nie mierzone". Deklaruj feature, kiedy istnieje ścieżka, która go emituje.

## 2. Kontekst urządzenia (raz na paczkę, nie per event)

`DeviceContext`: `app_version`, `app_build`, `sdk_version`, `os_name` (**[ANDROID]** `Android`), `os_version`, `device_model`, `locale`, `timezone`. **[ANDROID]** `app_version` = `versionName` (brak → `0`), `app_build` = `longVersionCode`, `os_version` = `Build.VERSION.RELEASE`, `device_model` = `<Producent z wielkiej litery> <Build.MODEL>` (≤ 64; producent nie jest dublowany, gdy `MODEL` już od niego zaczyna: `HTC One`), `locale` = `Locale.toLanguageTag()` (≤ 16). Wszystkie obowiązkowe, wszystkie przycięte do limitów z kontraktu. Do tego opcjonalne `sdk_wrapper` (`<name>/<version>`, ≤ 32 znaki, od 0.4.0): **nieobecne** (nigdy `null`, nigdy pusty string) dla natywnej appki, ustawiane wyłącznie przez most wrapperów (sekcja 4a). `sdk_version` zostaje wersją **natywnego** SDK. Brak IDFA, brak ATT, brak dokładnej lokalizacji — IP skraca serwer.

- [x] pola wymagane przez kontrakt
- [x] limity właściwości eventu (`Limits` / `Validation.kt`, ta sama tabela co `packages/contract/src/limits.ts`):
  ≤ 32 klucze, klucz ≤ 64 znaki i `^[a-z][a-z0-9_]*$`, string ≤ 256 znaków, wartość wyłącznie string /
  skończona liczba / bool. Przekroczenie **porzuca event**, nigdy nie przycina po cichu — przycięta wartość
  czyta się po drugiej stronie jak zmierzona.
- [ ] **[ANDROID]** `device_model` na emulatorze to `Google sdk_gphone64_arm64` itp. — udokumentowane, nie normalizowane

## 3. Prywatność

- [x] `strict_anonymous` → `identity` puste; `identify()` logowane jako zignorowane, nie wysyłane
- [x] `product_analytics` → `install_id` (to urządzenie) + opcjonalny `user_id`. **[ANDROID]** tożsamość leży w `noBackupFilesDir/Union/<hash>/identity.json` (poza Auto Backup, nie przeżywa deinstalacji)
- [x] traits (`identify(userId:traits:)`): ≤ 8 kluczy, wartości ≤ 256 znaków, scalane po kluczu, trzymane
  obok id (**[ANDROID]** plik tożsamości), kasowane przez `reset()` i `optOut()`. Zapisujemy je tak, jak przyszły — decyzję
  „to jest PII i deklarujemy je w App Privacy" podejmuje aplikacja, nie SDK. Serwer scala tak samo
  (`json_patch`) i kasuje traits, gdy retencja usunie ostatnią sesję instalacji.
- [x] `optOut()` czyści kolejkę, tożsamość i sesję; `optIn()` przywraca zbieranie
- **[ANDROID]** nie dotyczy: `Options.analyticsCollectionEnabled` nie istnieje, a `setAnalyticsCollectionEnabled` działa jak na iOS po pierwszym zawołaniu. Opis iOS: start z `analyticsCollectionEnabled = false` **nie kasuje** Keychaina: znaczy „zgoda jeszcze nieznana",
  a dopiero odpowiedź aplikacji (`true` wznawia ten sam `install_id`, `false` kasuje) rozstrzyga. Do 0.3.2
  każdy taki start mintował nowe id, więc jeden telefon był w panelu nowym urządzeniem per sesja (Boardly).
  Opcja jest od 0.3.3 `deprecated` — zbieranie startuje zawsze w `configure`, odmowę niesie `optOut()`.
  Zabetonowane `testStartingPausedKeepsTheStoredInstallIdWhenConsentIsRestored` (0.3.3)
- [x] **[ANDROID]** zamiast `PrivacyInfo.xcprivacy`: odpowiedzi do formularza Google Play Data safety w `README.md` (Device or other IDs, App activity, App info and performance — crash logs i diagnostics); biblioteka dodaje tylko `INTERNET`
- [ ] `requestDataDeletion()` to dziś alias `optOut()` — zamienić na realne żądanie, gdy API je wystawi
- [x] `Union.installId` wystawia install id do odczytu (`null` przed `configure` i w `StrictAnonymous`; **[ANDROID]** nie przeżywa reinstalacji),
  żeby aplikacja mogła podać go Survicate jako trait — styk z sekcji 5 jest wykonalny po stronie SDK;
  to aplikacja musi go wywołać.
- Odpowiedzi z ankiet: do Union jedzie **wyłącznie** fakt zdarzenia i `survey_id`. Nigdy treść
  odpowiedzi, komentarz, score ani kategoria — score i kategorię Union bierze z webhooka Survicate,
  a treści nie bierze wcale. `Union.track("survey_answered", mapOf("comment" to …))` byłoby złamaniem tej
  zasady po stronie aplikacji: SDK tego nie zablokuje, więc pilnuje tego ten wiersz i review.
- Nie logujemy sami z siebie: query stringów, treści wpisywanych przez użytkownika, tokenów, współrzędnych,
  ID reklamowych. E-mail czy nazwisko trafiają do Union **wyłącznie** wtedy, gdy aplikacja jawnie poda je
  jako trait w `identify` — nigdy z autocapture.

## 4. Dostarczanie

- [x] host: `https://in.useunion.dev/v1/batch` — ten sam, co `custom_domain` w `apps/ingest/wrangler.jsonc` w repo
  `Union`. Ten adres jedzie w każdej wydanej binarce: zmiana odcina od ingestu wszystkie apki już w App Store,
  a SDK nie krzyczy głośniej niż `warning`, więc awaria jest cicha. Zmieniasz host tylko razem z ingestem.
- [x] nagłówek klucza to `x-union-key` (nie `Authorization: Bearer`); **[ANDROID]** `User-Agent: union-android/<ver>` (+ ` <wrapper>/<ver>`); transport to `HttpURLConnection` (bez OkHttp), eventy jadą jako zwykły JSON, crashe jako gzip
- [x] kolejka NDJSON, stabilna ścieżka między launchami. **[ANDROID]** `noBackupFilesDir/Union/<hash klucza>/queue.ndjson` (hash FNV-1a base 36, jak na iOS) — wykluczona z Auto Backup z definicji katalogu
- [x] paczki ≤ 100 eventów / ≤ 256 KB
- [x] flush: co 10 s, przy 20 eventach, na wejściu w tło (**[ANDROID]** `ON_STOP`), na `flush()` (**[ANDROID]** `suspend fun flush()`, `flush(Runnable)`, `flushBlocking(timeoutMs)`; `flush()` po `track()` widzi ten event: kolejka wywołań jest uporządkowana barierą)
- [x] `flush()` wywołane w trakcie innego flusha **czeka na niego i dosyła resztę**, nigdy nie wraca z
  własnymi eventami w kolejce. Wcześniej `guard !flushing` zwracał od razu, a przez reentrancję aktora
  (drain zawieszony w `transport.send`) `await Union.flush()` wracał, zanim eventy sprzed wywołania
  wyszły. Zabetonowane `flushDuringAnInFlightFlushWaitsAndSendsEverything` (**[ANDROID]** w `PipelineTests`; iOS 0.3.2: `testFlushDuringAnInFlightFlushWaitsAndSendsEverything`)
- [x] anulowanie timera flusha **nie odpala** flusha: `try? await Task.sleep` połykał `CancellationError`
  i spadał do `flush()`, więc każde `timer.cancel()` (wyłączenie zbierania, tło, drugie `configure`)
  wysyłało paczkę i ścigało się z następnym jawnym flushem — to była przyczyna flaky
  `testDisablingAnalyticsWipesEventsButCanBeEnabledAgain`. Zabetonowane
  `cancellingTheFlushTimerDoesNotFlush` (**[ANDROID]** `delay` rzuca `CancellationException`, której nic nie łapie)
- [x] `202` ack; `400` z `details[].path=events.<i>` usuwa tylko wskazane eventy; `400` bez ścieżek / `privacy_violation` odrzuca paczkę; `401` zatrzymuje SDK na ten launch; `403`/`429` pauza z `Retry-After`; `413` dzieli paczkę; 5xx/sieć → backoff do 5 min (**[ANDROID]** 2 s → 5 min z jitterem 0,5–1,0, pierwsza próba 2 s)
- [x] `202` niesie `rejected` — ingest ma **zdalny kill switch po nazwie eventu** (`blockedEvents`), więc paczka
  potwierdzona nie znaczy „wszystko weszło". Eventu nie ponawiamy (to decyzja projektu, nie awaria), ale liczba
  jedzie do logu: bez niej wyłączona nazwa jest po stronie SDK nieodróżnialna od przyjętej.
- [x] kod błędu w body jest tym, co rozstrzyga, nie sam status: `invalid_write_key`, `project_disabled`,
  `unsupported_contract_version`, `invalid_batch`, `batch_too_large`, `quota_exceeded`, `rate_limited`,
  `privacy_violation` (`packages/contract/src/errors.ts`). Pod jednym `403` stoją dwie różne prawdy —
  wyłączony projekt i throttle — i tylko jedna z nich ma sens do ponowienia.
- [ ] niezgodność środowiska klucza wraca jako `invalid_batch` bez `details` — rozpoznajemy po `message`; usunąć, gdy ingest doda `path: "environment"`
- [ ] **[ANDROID]** emulator widzi hosta pod `10.0.2.2`, nie `localhost`: `Options.endpoint = "http://10.0.2.2:8789/v1/dev/ingest"`. Reszta jak iOS: lokalny ingest **nie ma portu** pod `wrangler dev` z wieloma configami: repo `Union` wystawia proxy
  (`DEV_INGEST_PROXY=1`, `POST http://localhost:8789/v1/dev/ingest`). Dopisać jako udokumentowane
  `Options.endpoint` do testów na urządzeniu, żeby ścieżka „SDK przeciwko lokalnemu Unionowi" nie była folklorem.
- SDK nigdy nie rzuca i nie ubija hosta: nieprawidłowy event jest logowany i porzucany. **[ANDROID]** dotyczy też `configure` (wyjątek w środku = log i SDK nieskonfigurowane) oraz każdej pracy na wątku pipeline'u.
- `POST /v1/server` (klucze `sk_…`) **nie jest ścieżką SDK** i nie wolno jej tu dodać: to wejście dla backendu
  klienta, z własnym kontraktem, innym zaufaniem i bez `SessionDO`. Jedyny styk to `Union.installId` podany
  aplikacji do przekazania na własny serwer.

## 4a. Crashe, hangi i non-fatale

Kontrakt: `packages/contract/src/crash-batch.ts` w repo `Union` (kopia schematu w
`union/src/test/resources/crash-batch.v2.json`, walidowana testem na każdej kodowanej paczce i na każdym raporcie z dysku; kopia `event-batch.v1.json` obok niej niesie `device.sdk_wrapper`). Umowa między czterema repozytoriami (iOS, Android, Flutter, React Native): `docs/Engineering/SDK-PLATFORMS.md` w repo `Union`.
Decyzja i granice: `docs/Engineering/ADR/0004-own-crash-reporting.md` w repo `Union`.

**Jeden fakt, z którego wynika cała reszta: crasha nie wysyła proces, który umarł.** Handler zapisuje
raport na dysk, a SDK wysyła go przy **następnym uruchomieniu** — po minutach, po dniach, a dla kogoś,
kto odinstalował, nigdy. **[ANDROID]** różnica techniczna: handler JVM działa w zwykłym wątku, więc
od razu składa i zapisuje **cały raport** (JSON, plik tymczasowy + rename), bez rekordu binarnego i sidecara iOS.
Wszystko, co leży na dysku, jest kompletne; następny start tylko czyta i wysyła.

| Element | Co robi SDK | Stan |
|---|---|---|
| Handler wyjątków | **[ANDROID]** `Thread.setDefaultUncaughtExceptionHandler`, łańcuchowo do poprzedniego (nigdy go nie gubimy; gdy poprzedniego nie ma, wołamy `ThreadGroup.uncaughtException`). Raport zapisywany **synchronicznie**, błąd w samym handlerze jest połykany i nie zasłania oryginalnego crasha, ponowne wejście (crash w trakcie raportowania) nie rekurencjonuje. `stop()` przywraca poprzedni handler tylko wtedy, gdy nikt nie nałożył swojego na nasz; w przeciwnym razie nasz zostaje, ale jest wyłączony. Odpowiednik handlerów sygnałów/Mach/`NSException` z iOS (te nie istnieją na JVM) | [x] |
| Typ i powód | **[ANDROID]** `exception.type` = pełna nazwa klasy rzuconego wyjątku (zewnętrznego), `reason` = `message`. Typ jest częścią odcisku, `reason` nie (może zawierać PII) | [x] |
| Łańcuch przyczyn | **[ANDROID]** wątek `crashed` niesie stos wyjątku zewnętrznego; każda przyczyna (`Caused by`) to dodatkowy wątek `crashed: false` o nazwie `caused by <typ>`, najgłębsza ostatnia (do 48 wątków). Nie spłaszczamy do jednej listy ramek: opakowanie nie chowa miejsca, które opakowało, a przyczyna źródłowa nie ginie. Pętla w łańcuchu przyczyn kończy się | [x] |
| Inne wątki | **[ANDROID]** przy crashu poza głównym wątkiem dołączamy stos main threada (`crashed: false`). Pełny zrzut wszystkich wątków nie jest zbierany: w handlerze crashującego procesu to ryzyko bez dużej wartości | [x] |
| Ramki | **[ANDROID]** ramki zarządzane `{n, function: "pkg.Klasa.metoda", file, line, in_app}`; `line` ≤ 0 (−1 nieznana, −2 natywna) to brak pola; `module`/`column` nie są wysyłane. `in_app`: klasa zaczyna się od `context.packageName` + `.` (albo `==`) lub od jednego z `Options.inAppPackages` | [x] |
| Hangi | **[ANDROID]** watchdog na własnym wątku daemon: co 250 ms wrzuca tick na `Looper` głównego wątku; brak odpowiedzi ponad `Options.hangThresholdSeconds` (2 s) = epizod. Stos main threada pobierany **w chwili przekroczenia progu**, raport pisany **po zakończeniu epizodu** ze **zmierzonym** czasem (`hang_duration_ms`), nigdy z progiem. Jeden raport na epizod. Tick idzie tylko, gdy aplikacja jest na pierwszym planie (proces zamrożony przez system i odmrożony nie jest hangiem). Epizod, który nigdy się nie kończy, to ANR i raportuje go następny start | [x] |
| ANR | **[ANDROID]** przy starcie (API 30+) `ApplicationExitInfo.REASON_ANR` nowsze niż znak wodny (`last_anr_timestamp` w `state.json`) → `kind: "anr"`, `exception.type: "ApplicationNotResponding"`, `reason` = opis systemu, `crashed_at` = czas wyjścia, stos main threada sparsowany z tekstowego śladu (`"main"` → linie `at ...`; nieczytelny ślad = brak ramek, nie zgadnięty stos). **Pierwszy start w ogóle tylko ustawia znak wodny**: ANR-y sprzed SDK mają kontekst, którego nie mamy. Kontekst raportu to kontekst **poprzedniego uruchomienia** (`last_launch_context`), bo ANR należy do procesu, który zginął, a appka mogła się zaktualizować. Bez `state` i `session_id` (nic tego wtedy nie próbkowało), bez `hang_duration_ms` (Android go nie podaje) | [x] |
| Non-fatale | `Union.recordError(throwable)` / `recordError(type, reason)` → `kind: 'nonfatal'`, `is_fatal: false`. Osobna dotkliwość, nigdy dodawana do liczby crashy. **[ANDROID]** stos Throwable to jego własny (z `cause` jako dodatkowe wątki); dla `recordError(type, reason)` stos wołającego bez ramek SDK (`dev.useunion.Union`, `dev.useunion.internal.*`). Stos i stan urządzenia bierzemy na wątku wołającego, zapis pliku idzie na wątek pisarza; po zapisie **od razu próba wysyłki w procesie** (iOS czeka na następny start). Po `stop()` / `optOut()` ignorowane | [x] |
| Breadcrumbs | ring buffer w pamięci (64 wpisy), zasilany z jednego lejka `push()` w pipeline — screeny, eventy auto i własne — plus `Union.leaveBreadcrumb(name)`. **Nazwy, nigdy wartości**: kontrakt nie ma pola na wartość, bo breadcrumbs nie przechodzą przez zdalny kill switch eventów | [x] |
| Custom keys | `Union.setCrashKey(key, value)`, ≤ 8 kluczy, klucz ≤ 40, wartości ≤ 256. **Zakazane w `StrictAnonymous`** (SDK odmawia, a przy wysyłce zdejmuje `custom_keys` także z raportu zapisanego pod inną polityką); serwer odrzuca taki batch niezależnie | [x] |
| Obrazy binarne | **[ANDROID]** nie dotyczy: `images: []` (pole wymagane przez kontrakt, ramki zarządzane nie mają obrazu) | — |
| Ucięty stos | ≤ 128 ramek na wątek (zostają najbardziej wewnętrzne) i **obowiązkowe** `frames_truncated` na każdym wątku. Ucięty dump nie może czytać się jak krótki stos | [x] |
| Stan urządzenia | **[ANDROID]** próbkowany **w chwili raportu** (w handlerze), nie wcześniej: `orientation`, pamięć (`ActivityManager.MemoryInfo`), dysk (`StatFs`), bateria (sticky `ACTION_BATTERY_CHANGED`), `low_power_mode` (`PowerManager.isPowerSaveMode`), `in_foreground` (z `ProcessLifecycleOwner`), `uptime_ms` (od startu procesu), `jailbroken` = heurystyka roota (`test-keys` w `Build.TAGS` lub ścieżka `su`; znane fałszywe trafienia na własnych ROM-ach i emulatorach, znane pominięcia przy Magisk Hide — to próbka, nie wyrok). Każde pole opcjonalne, brak odpowiedzi zostaje brakiem, nigdy zerem | [x] |
| `build_id` | **[ANDROID]** `assets/union-build-id.txt` (zapisuje wtyczka Gradle), tylko gdy pasuje do `^[0-9a-f-]{7,64}$`; brak pliku = pole nieobecne. Klucz łączący raport z `mapping.txt` | [x] |
| Sesja crasha | `session_id` żywej sesji (pipeline podaje go reporterowi przy każdej zmianie); `attach` przejmuje kontynuowaną sesję | [x] |
| Wysyłka | `POST /v1/crash`, **gzip wymagany** (`GZIPOutputStream`, stały `Content-Length`, nigdy chunked). Kasujemy raport wyłącznie po 2xx albo po trwałym odrzuceniu (400/413/422); 5xx i błąd sieci **zostawiają plik**; po jednym raporcie na żądanie, najstarsze pierwsze, jedna wysyłka naraz (mutex) | [x] |
| Limity paczki | ≤ 8 raportów, ≤ 1 MB po gzipie i ≤ 8 MB po rozpakowaniu. **[ANDROID]** SDK wysyła po jednym raporcie na żądanie, więc limity nie zależą od liczby plików | [x] |
| `503` | osobne od 5xx z reszty świata: „nie udało się odłożyć tego raportu, ponów” — plik **zostaje** | [x] |
| `401`/`403` | zatrzymują wysyłkę na ten launch, pliki zostają | [x] |
| Kontrakt v2 | paczka niesie `contract_version: 2`, a **każdy** raport `platform: "android"` (zgodne z `context.os_name`). **[ANDROID]** nie ma rekordów 0.3.x do konwersji: SDK od początku mówi v2 | [x] |
| Most wrapperów | **[ANDROID]** dokładnie dwa wywołania na `Union.Wrapper` (`@RestrictTo(LIBRARY_GROUP)`, reguły ProGuard je zachowują), bez obietnicy stabilności poza `SDK-PLATFORMS.md`. `setWrapper(name, version)` — przed `configure`: `device.sdk_wrapper = "<name>/<version>"` i dopisek do `User-Agent` (eventy **i** crashe); po `configure` albo dla nazwy/wersji pustej, ze spacją, `/`, znakiem spoza ASCII, albo gdy całość > 32 znaki — **ignorowane z logiem**. `recordManagedError(type, reason, frames: List<ManagedFrame>, fatal, thread = "main")` — `ManagedFrame(function, module?, file?, line?, column?, inApp)`; `n` z kolejności listy, ≤ 128, ucięcie → `frames_truncated`. `fatal = false` → `nonfatal`, ta sama ścieżka co `recordError`. `fatal = true` → raport `fatal` **zapisany synchronicznie** i wysłany przy następnym starcie; pusty `function` → `<anonymous>`, `line`/`column` ≤ 0 → brak, pusty `type` → `Error`. Raport zarządzany nie ma obrazów | [x] |
| Retencja lokalna | `Options.maxStoredCrashReports` (16), najstarsze wypadają pierwsze | [x] |
| `optOut()` | kasuje też katalog crashów i zatrzymuje handler. **[ANDROID]** zapisany opt-out jest honorowany **także po restarcie**: `Client` nie instaluje handlera ani watchdoga, gdy `opt_out` jest ustawione; `optIn()` uzbraja je z powrotem. (iOS instaluje handlery przy każdym `configure`, także dla użytkownika po opt-oucie) | [x] |
| Domyślnie | **włączone** (`Options.crashReporting = true`), po obu stronach (serwerowa bramka `crash_reporting_enabled`). Wyłączenie po stronie projektu **nie** zatrzymuje uploadu — zamienia go w zapisaną odmowę (`collection_disabled`). `crashReporting = false` nie instaluje niczego: ani handlera, ani watchdoga, ani katalogu | [x] |
| Weryfikacja end-to-end | **[ANDROID]** brak odpowiednika `crash-e2e.sh`: handler JVM nie wymaga zabicia procesu, żeby go przetestować — `CrashTests` wywołuje zainstalowany handler wprost i sprawdza plik, łańcuch do poprzedniego handlera i schemat. Zabicie procesu na urządzeniu to krok ręczny przed wydaniem (przycisk `throw` w aplikacji, restart, panel) | [~] |
| Symbolikacja | **nie w SDK.** Offsety/nazwy to tożsamość, mapowanie to wyświetlanie. **[ANDROID]** tożsamość to **zaciemnione** `function:line`; wtyczka Gradle `dev.useunion.union` wysyła `mapping.txt` po `minify<Variant>WithR8` (`POST /v1/mappings`, klucz serwerowy `UNION_SERVER_KEY`, gzip, nagłówki `x-union-key`, `x-union-build-id`, `x-union-app-version`, `x-union-app-build`); brak klucza = ostrzeżenie i pominięcie, **nigdy** błąd builda; klucz klienta `av_…` jest odrzucany. Panel odtwarza nazwy wyłącznie do wyświetlania i **nigdy nie przegrupowuje** historii. Klucz serwerowy nie wolno trafić do aplikacji | [x] |

Reguły, których nie łamiemy:
- **Trzy zegary są rozdzielne.** `crashed_at` (atrybucja dnia), `sent_at` (opóźnienie uploadu),
  `received_at` (retencja, po stronie serwera). Zlepienie dowolnych dwóch jest kłamstwem.
- **Kontekst jest z chwili crasha, nie z chwili wysyłki.** Appka prawie zawsze zostaje w międzyczasie
  zaktualizowana, a przypisanie crasha do wersji, która go zaraportowała, obwinia release, który go
  naprawił.
- **Rekord bez wątku oznaczonego jako crashed jest porzucany, nie naprawiany.** **[ANDROID]** raport fatal zawsze ma dokładnie jeden wątek `crashed` z konstrukcji (`ThreadDump(crashed = true)` dla stosu wyjątku). Wybranie wątku 0
  postawiłoby issue, regresję i alert na stosie, o którym nikt nie ustalił, że zabił proces.
- **Rekord w nowszym formacie jest odrzucany, nie reinterpretowany.** **[ANDROID]** format na dysku to ten sam JSON, który idzie na drut (`crash-batch.v2`); plik, którego nie da się odczytać, jest kasowany, nie ponawiany w nieskończoność.
  Źle odczytany stos daje wiarygodne ramki i odcisk, który skleja niepowiązane crashe.

## 5. Integracje

| Integracja | Co robi SDK | Stan |
|---|---|---|
| Compose | **[ANDROID]** `TrackScreen("Name", feature = null)` (moduł `union-compose`) — jeden `$screen_view` na **wejście do kompozycji** (`LaunchedEffect(name, feature)`): rekompozycja nie liczy się, zmiana nazwy i wyjście/wejście tak | [x] |
| Aktywności | **[ANDROID]** `Options.automaticScreenTracking` — `ActivityLifecycleCallbacks.onResume`, prosta nazwa klasy; rotacja (ta sama klasa odtworzona po `isChangingConfigurations`) nie liczy się | [x] |
| Deep linki | `handleDeepLink(Uri)` / `handleDeepLink(Intent)` z `onCreate` / `onNewIntent`; bez automatycznego przechwytywania | [x] |
| Środowisko | **[ANDROID]** auto-detekcja: `FLAG_DEBUGGABLE` → `development`, inaczej `production`; `testflight` (tor testowy Play) wyłącznie z `Options.environment`, bo Play nie mówi aplikacji, na jakim torze jest | [x] |
| Zakupy (Play Billing) | **[ANDROID]** aplikacja ustawia `BillingFlowParams.ProductDetailsParams`… `.setObfuscatedAccountId(Union.installId)` przy zakupie — to jedyny styk. SDK daje wartość (`null` przed `configure` i w `StrictAnonymous`; nie przeżywa reinstalacji) i snippet w README; nie importuje biblioteki Billing i nie owija zakupu. Zakupów sprzed ustawienia nie da się połączyć wstecz. Id to identyfikator urządzenia: aplikacja deklaruje go w Data safety. Odbiorca po stronie Union: patrz `SDK-PLATFORMS.md`; ten SDK niczego o zakupach nie wysyła | [x] |
| RevenueCat | **nic w hot pathu.** Revenue wchodzi webhookiem RC → `apps/ingest`. Dwa styki po stronie aplikacji: atrybut subskrybenta `union_install_id` = `Union.installId` (pierwsza ścieżka wiązania; **[ANDROID]** to samo wywołanie, `Purchases.sharedInstance.setAttributes(mapOf("union_install_id" to Union.installId))`) i `Union.identify(userId:)` tym samym id co RC `app_user_id` (druga). RC jest właścicielem liczb wyłącznie w projekcie **bez** ASSN | [x] |
| Survicate / NPS | **prawie nic, ale dwa styki.** Odpowiedzi wchodzą webhookiem Survicate → `apps/ingest`; SDK nie czyta ankiet i nie wysyła odpowiedzi. Styk pierwszy: aplikacja ustawia `SurvicateSdk.shared.setUserTrait(UserTrait(withName: "union_install_id", value: <install_id>))`, żeby odpowiedź trafiła na profil osoby — bez tego Union próbuje dopasować po `user_id`, a w ostatniej kolejności pyta Data Export API. Styk drugi: eventy z sekcji 1a. Treści odpowiedzi Union nie przyjmuje w żadnej formie | [ ] |
| Flutter / React Native | cienkie wrappery nad tym SDK (`union_analytics`, `@useunion/react-native`): nic nie wysyłają same, tylko przekazują wywołania; deklarują się przez `Union.Wrapper.setWrapper` (sekcja 4a). Aplikacja używa wrappera, nie tego mostu | [x] |
| Push / notyfikacje | poza MVP | [ ] |
| Crash reporting | **własne, zaimplementowane** — patrz sekcja 4a. Kontrakt to `crash-batch.v2.json`, nie `EventBatch` | [x] |
| Feature flags | poza MVP | — |

Nowa integracja przechodzi ten sam próg: wiersz w tabeli + odpowiedź na pytanie „co dokładnie leci na wire i dlaczego to nie jest PII”.

## 6. Zanim domkniesz zmianę

- [ ] `./gradlew build test` przechodzi (**[ANDROID]** testy JVM + Robolectric 4.17 na SDK 34; wtyczka Gradle jako included build; `sample` się kompiluje, także `assembleRelease` z R8)
- [ ] testy zgodności ze schematem walidują każdą zakodowaną paczkę względem `event-batch.v1.json` (eventy) i `crash-batch.v2.json` (crashe) — **[ANDROID]** walidatorem `com.networknt:json-schema-validator`, nie własnym minimalnym
- [ ] fixtures schematów (`union/src/test/resources/`) odświeżone, jeśli kontrakt w repo `Union` się zmienił (ostatnio: `device.sdk_wrapper` i crash v2)
- [ ] `README.md` zgadza się z zachowaniem (README to publiczna obietnica)
- [ ] wiersz w tej checkliście dopisany/zaktualizowany, stan `[x]/[~]/[ ]` prawdziwy
- [ ] stringi user-facing i logi po angielsku

## Jak utrzymywać ten plik

Zmiana zachowania SDK bez zmiany tej checklisty jest niedokończona. Konkretnie: nowy event, nowa właściwość automatyczna, nowe pole kontekstu, nowa integracja, zmiana reguł flushu/retry albo zmiana `contract_version` → aktualizacja w tym samym commicie. Wiersze zrobione zostają (z `[x]`), nie usuwamy ich — to lista tego, co gwarantujemy, nie backlog.
