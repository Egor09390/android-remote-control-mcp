# ARTEMIS на том же телефоне, что и Android MCP

Официальный Python-движок Google ARTEMIS работает в Ubuntu внутри Termux.
Android MCP вызывает его на `127.0.0.1:18765`. ChatGPT использует прежний
HTTPS-адрес `/mcp` и OAuth приложения. CloudPub и туннель через VPS подходят
для доставки запроса до телефона. VPS может продолжать только пересылать трафик.

Для запуска нужен Android ARM64, актуальный Termux с PRoot Distro 5+, доступ к
Wi-Fi для «Беспроводной отладки» и ключ провайдера моделей для ARTEMIS.
Для установки окружения не требуется root. Модель отдельно использует
настроенный в ARTEMIS API.

## Разовая настройка

В Termux установите Ubuntu 24.04 и откройте контейнер с каталогом установщика.
Если контейнер `artemis` уже существует, повторите только вход в него.

```bash
pkg install git proot-distro
git clone --branch feature/on-device-artemis --single-branch https://github.com/Egor09390/android-remote-control-mcp.git
cd android-remote-control-mcp
proot-distro install --name artemis ubuntu:24.04
proot-distro login --isolated --kill-on-exit \
  --bind "$PWD/deploy/artemis-local:/mnt/artemis-local" artemis
```

В Ubuntu выполните `bash /mnt/artemis-local/artemis.sh install`. Установщик
скачивает зафиксированную ревизию официального ARTEMIS и устанавливает пакеты из
её `uv.lock`. Добавьте ключ модели в `~/.artemis-local/artemis/.env`. Например,
для Gemini заполните `GEMINI_API_KEY`. Ключ остаётся в Termux.

Откройте на телефоне **Параметры разработчика → Беспроводная отладка**.
Запишите основной порт с этого экрана. Затем нажмите **Связать устройство
с помощью кода** и запишите отдельный порт из диалога. В Ubuntu запустите:

```bash
bash /mnt/artemis-local/artemis.sh pair ПОРТ_ИЗ_ДИАЛОГА ОСНОВНОЙ_ПОРТ
```

ADB запросит шестизначный код. Используйте режим разделённого экрана, чтобы
оставить диалог Android открытым во время ввода. Пара нужна для подключения к
этому же телефону. При изменении основного порта повторите привязку.

Получите токен через `bash /mnt/artemis-local/artemis.sh token`. В APK
остановите MCP и сохраните этот токен в разделе **ARTEMIS на этом телефоне**.
На обычном экране раздел находится на вкладке **Сервер**. В S23 Control откройте
**Настройки**. Токен не нужен на VPS и в ChatGPT.

## Запуск и проверка

1. В Ubuntu запустите `bash /mnt/artemis-local/artemis.sh start` и оставьте
   сессию Termux открытой.
2. В APK запустите MCP с OAuth или bearer-аутентификацией. Для инструментов
   ARTEMIS режим приватности должен быть выключен, так как движок сам читает
   экран и ведёт свой журнал.
3. Обновите список инструментов подключения в ChatGPT. Вызовите
   `android_artemis_status`, затем `android_artemis_run_task`, например с
   заданием «Открой Настройки и сообщи версию Android. Ничего не меняй».
   `android_artemis_manage_task` принимает полученный `trace_id` для проверки
   или остановки задачи. `android_artemis_inspect_trace` возвращает сводку либо
   результаты поиска по истории. Если задан префикс устройства, имена
   инструментов включают его.
4. Для завершения активной задачи вызовите `android_artemis_manage_task` с
   действием `stop`. Для остановки локального сервера нажмите Ctrl+C в Termux.

Клиент ADB использует файлы ключей и Unix-сокет в каталоге Termux. HTTP-интерфейс
адаптера слушает только loopback и проверяет отдельный случайный токен. Для
ARTEMIS выбран автономный режим, в котором его веб-панель не запускается.
