# Wywóz śmieci (MPO Kraków)

Apka na tablet z Androidem (8.0+), która pokazuje harmonogram odbioru odpadów dla jednego adresu w Krakowie. Działa jako stały ekran: duży zegar, najbliższy odbiór, lista kolejnych terminów, ekran nie gaśnie.

## Skąd dane

Te same co w wyszukiwarce https://harmonogram.mpo.krakow.pl:

1. `POST https://kiedywywoz.pl/API/harmo_img/` z `token` zwraca listę ulic (JSON `[{id, name}]`)
2. ten sam adres z `ulica=<id>` zwraca numery budynków
3. `GET .../pdf/?id_numeru=<id>&token=...` zwraca PDF z harmonogramem

PDF jest zamieniany na tekst (pdfbox-android) i parsowany (`ScheduleParser.kt`). Harmonogram jest zapisywany lokalnie i odświeżany co 12 h, więc apka działa też bez internetu.

## Budowanie

**Android Studio:** File > Open > ten folder, poczekaj na synchronizację Gradle, potem Build > Build App Bundle(s) / APK(s) > Build APK(s). Plik: `app/build/outputs/apk/debug/app-debug.apk`.

**GitHub:** workflow `.github/workflows/build.yml` buduje APK przy każdym pushu i publikuje go jako release `apk`. Na tablecie wystarczy otworzyć:

https://github.com/regov124356/MPO-Krakow/releases/download/apk/wywoz.apk

Przy pierwszym buildzie workflow sam dodaje Gradle wrapper do repo. Dodatkowo pobiera prawdziwy harmonogram dla testowego adresu, a wynik (tekst PDF i odczytane terminy) trafia do opisu release'u. Gdy build się wywali, log ląduje w release `build-log`.

Własny adres w teście na żywo: zmienne `TEST_STREET` i `TEST_NUMBER` (np. `Romanowicza` i `1 DM`).

## Instalacja na tablecie

Skopiuj APK na tablet, otwórz go i zezwól na instalację z nieznanych źródeł. Przy pierwszym uruchomieniu wybierz ulicę i numer.

## Obsługa

- **Odśwież**: pobiera harmonogram od nowa
- **Zmień adres**: wybór innej ulicy i numeru
- przytrzymanie paska stanu na dole: pokazuje tekst odczytany z PDF (przydatne, gdy daty się nie wczytują)
