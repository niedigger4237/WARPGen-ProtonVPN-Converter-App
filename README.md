# WARP + Proton AWG — приложение для Android и Windows

Одно приложение с переключателем вкладок:

- **WARP генератор** — регистрирует бесплатный аккаунт Cloudflare WARP и собирает конфиг для
  **AmneziaWG** (3 варианта маскировки AWG 2.0, AWG 3.0/3.1), **WireSock**, **Clash** и **Xray**.
  Есть выбор DNS, MTU, IPv6, keepalive, endpoint, исключение LAN и раздельное туннелирование по сервисам.
- **Proton конвертер** — превращает WireGuard-конфиги Proton VPN (`.conf`) в AmneziaWG / WireSock / Clash / Xray. Работает полностью офлайн.

- **ByeDPI** — встроенный локальный SOCKS5-прокси для обхода DPI-блокировок: готовые стратегии, ручная правка параметров и
  **автоподбор** (приложение по очереди запускает стратегии и проверяет Cloudflare API, YouTube, Discord и др.). В WARP-вкладке его
  можно выбрать как прокси для получения ключей. Движок собирается GitHub Actions из исходников
  [hufrea/byedpi](https://github.com/hufrea/byedpi) (лицензия MIT); если сборка движка не удалась, приложение собирается без него.
  Работает как прокси (127.0.0.1:порт), а не как VPN: другие программы можно направить на него вручную. DNS-блокировки он не обходит.

Из исходных проектов убраны все внешние и авторские ссылки (Telegram, реклама, партнёрские кнопки, сторонние серверы
и воркеры). Регистрация WARP идёт напрямую в API Cloudflare, ключи (X25519) создаются на устройстве и нигде не хранятся.

## Сборка установочных файлов (самый простой путь)

**Actions → Preview** собирает debug APK и два EXE (portable и установщик). Запускается вручную, для PR в main и при push в main или preview-build. Файлы доступны в Artifacts.

**Actions → Release** собирает APK с release-подписью и Windows EXE. При ручном запуске файлы доступны в Artifacts. При отправке тега `v*` они также прикрепляются к GitHub Release.

В Settings → Secrets and variables → Actions добавьте секреты репозитория:

| Секрет | Значение |
| --- | --- |
| `KEYSTORE_BASE64` | Содержимое существующего keystore в Base64 |
| `KEYSTORE_PASS` | Пароль keystore |
| `ALIAS_PASS` | Пароль приватного ключа с alias `VladConvGen` |

Для обновления установленного release APK используйте тот же ключ. Версию выпуска задаёт Git-тег вида `v1.0`, `v1.1` или `v1.1.1`. Для `v1.0` APK получит versionName `1.0` и versionCode `30101`, Windows — `1.0.0`. Preview берёт ближайший достижимый тег, а до первого тега — `1.0`. Для ручной Release-сборки выберите тег либо ветку, вершина которой помечена тегом.

## Локальная сборка

Нужны Node.js 20+ и Python 3.

```bash
npm install
npm run build:web        # собирает www/index.html из tools/
npm start                # запуск Windows-версии для проверки
npm run dist:win         # (на Windows) -> dist/*.exe

# Android — JDK 21, Android SDK 35 и Python 3
cd android && ./gradlew assembleDebug    # на Windows: gradlew.bat assembleDebug
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

`www/index.html` можно открыть и в обычном браузере: вкладка Proton работает целиком, а регистрация WARP из браузера
невозможна (Cloudflare не разрешает CORS) — для неё нужно приложение.

## Структура

- `tools/parts_warp.*` — вкладка WARP; `tools/warp_data.json` — I1-пакеты, списки IP сервисов, порты, домены;
- `tools/parts_proton.*` — вкладка Proton; `tools/x25519.js` — генерация ключей WireGuard;
- `tools/template.html` + `tools/build.py` — оболочка и сборка единого `www/index.html`;
- `electron/` — Windows (запросы к Cloudflare идут через основной процесс); `android/app/src/main/AndroidManifest.xml` — разрешения и компоненты Android; Java-код рядом в каталоге `java/` реализует WebView, нативный HTTP, импорт/экспорт файлов и ByeDPI.

## Безопасность

Конфиги содержат приватный ключ — не публикуйте их. Приложение ничего не сохраняет на диск само и не отправляет
данные никуда, кроме запроса регистрации в Cloudflare.
