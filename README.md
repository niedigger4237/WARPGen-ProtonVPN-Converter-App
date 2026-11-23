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

1. Создайте пустой репозиторий на GitHub и загрузите в него содержимое этой папки.
2. **Actions → Build Android + Windows → Run workflow**.
3. Через 5–10 минут скачайте артефакты:
   - `WarpProtonAWG-windows` — `WarpProtonAWG-portable-2.1.0.exe` (без установки) и `WarpProtonAWG-setup-2.1.0.exe`;
   - `WarpProtonAWG-android` — `app-debug.apk` (разрешите установку из неизвестных источников).

## Локальная сборка

Нужны Node.js 20+ и Python 3.

```bash
npm install
npm run build:web        # собирает www/index.html из tools/
npm start                # запуск Windows-версии для проверки
npm run dist:win         # (на Windows) -> dist/*.exe

npm run android:init     # создаёт папку android/ (один раз)
cd android && ./gradlew assembleDebug    # JDK 21 + Android SDK
# APK: android/app/build/outputs/apk/debug/app-debug.apk
```

`www/index.html` можно открыть и в обычном браузере: вкладка Proton работает целиком, а регистрация WARP из браузера
невозможна (Cloudflare не разрешает CORS) — для неё нужно приложение.

## Структура

- `tools/parts_warp.*` — вкладка WARP; `tools/warp_data.json` — I1-пакеты, списки IP сервисов, порты, домены;
- `tools/parts_proton.*` — вкладка Proton; `tools/x25519.js` — генерация ключей WireGuard;
- `tools/template.html` + `tools/build.py` — оболочка и сборка единого `www/index.html`;
- `electron/` — Windows (запросы к Cloudflare идут через основной процесс); `capacitor.config.json` — Android (нативный HTTP).

## Безопасность

Конфиги содержат приватный ключ — не публикуйте их. Приложение ничего не сохраняет на диск само и не отправляет
данные никуда, кроме запроса регистрации в Cloudflare.
