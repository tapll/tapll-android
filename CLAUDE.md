# tapll-android — wskazówki dla agentów

Klient SDK Androida dla platformy Tapll (repo `Tapll`, `~/Desktop/Tapll`); kotlinowy bliźniak `tapll-ios`. Pakiet `com.tapll`, współrzędne `com.tapll:tapll`, minSdk 24, bez zależności poza AndroidX lifecycle/annotation i kotlinx-coroutines.

**Przed każdą zmianą zachowania przeczytaj `docs/CHECKLIST.md`** — jest tam wypisane, co SDK loguje, kiedy, z jakimi właściwościami i czym różni się od iOS (wiersze oznaczone `[ANDROID]`). Znajdź wiersz; jeśli go nie ma, najpierw go dopisz. Umowa między czterema SDK (iOS, Android, Flutter, React Native): `docs/Engineering/SDK-PLATFORMS.md` w repo `Tapll` — zmiana reguły tam zmienia też tutaj.

Kontrakt wire żyje **wyłącznie** w `packages/contract` w repo `Tapll` (`event-batch.ts`, `crash-batch.ts`, `limits.ts`, `errors.ts`) — tutaj go nie definiujemy, tylko realizujemy. `tapll/src/test/resources/event-batch.v1.json` i `crash-batch.v2.json` to kopie wygenerowanych JSON Schema; odświeżamy je, gdy kontrakt się zmienia, a testy walidują nimi każdą paczkę i każdy raport, jaki SDK buduje. Nie edytuj kopii ręcznie.

Zasady twarde:
- eventy `$…` tylko z listy automatycznych (`AutoEvent`); klient nigdy nie wysyła `$…` (`Validation.customName`);
- `StrictAnonymous` nigdy nie wysyła `identity`, traits ani custom keys (eventy i crashe);
- retry zachowuje `event_id` (serwer deduplikuje);
- przychód nie przechodzi przez SDK;
- SDK nigdy nie rzuca w stronę hosta: niepoprawny event = log + drop, `configure` łapie wszystko;
- breadcrumbs to nazwy, nigdy wartości — kontrakt nie ma na nie pola i nie wolno go poszerzać;
- klucz serwerowy (`sk_…`) nigdy nie trafia do aplikacji; wtyczka Gradle odrzuca `av_…` i nie robi klucza wejściem żadnego taska;
- stan SDK (kolejka, tożsamość, sesja, crashe) leży w `noBackupFilesDir`, nigdy w SharedPreferences (Auto Backup przeniósłby go na nowy telefon);
- na Androidzie `reinstall` nigdy nie jest wysyłane w `$app_install`.

Komendy: `./gradlew build test` (wszystkie moduły, wtyczka jako included build, `sample`, lint). Bez emulatora: testy JVM + Robolectric (SDK 34 — SDK 35 wymaga JDK 21). Toolchain: JDK 17 (`JAVA_HOME=/opt/homebrew/opt/openjdk@17`), Android SDK w `ANDROID_HOME`. Zawsze `./gradlew`, nie globalny gradle.

Struktura: `tapll/` (SDK; `internal/` pipeline, stores, `internal/crash/` raporty), `tapll-compose/`, `tapll-gradle-plugin/` (included build; kod zależny od AGP tylko w `AndroidWiring`, bo `TapllPlugin` musi się dać załadować bez AGP), `sample/` (nie publikowany). Wersja SDK jest w jednym miejscu: `VERSION_NAME` w `gradle.properties` (BuildConfig, `User-Agent`, `device.sdk_version`, współrzędne Maven).

Stringi user-facing i logi po angielsku; dokumentacja może być po polsku (CHECKLIST i ten plik są po polsku jak w iOS, README po angielsku).

Zmiana zachowania bez aktualizacji `docs/CHECKLIST.md` w tym samym commicie jest niedokończona. Wiersze zrobione zostają (z `[x]`); to lista tego, co gwarantujemy, nie backlog.

Rename (0.2.0): SDK ma wcześniej nazwę Union (`dev.useunion:union`). Stan na urządzeniu z 0.1.x (`noBackupFilesDir/Union/`) przenosi jednorazowo `internal/LegacyMigration.kt` (nowy plik wygrywa, stary jest usuwany) — nie usuwaj tego, dopóki istnieją apki na 0.1.x. Stare nazwy zostają tylko jako: alias `Union` (`Deprecated.kt`), stałe migracji, fallbacki wtyczki (`UNION_SERVER_KEY`, `-Punion.enabled`) i odczyt `union-build-id.txt`.
