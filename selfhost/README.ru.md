# Собственный туннель MCP на VPS

Схема: Samsung Android → WSS (исходящее TLS-соединение) → собственный VPS → HTTPS/MCP-клиент.
Никаких Cloudflare, ngrok или arc-tunnel.io. В этой версии TLS завершается на VPS, так что
оператор VPS может видеть трафик. Приложение сохраняет Bearer/OAuth-аутентификацию MCP.

Перед установкой понадобится: Ubuntu/Debian VPS, работающий Docker Engine + Docker Compose v2,
свой домен, DNS A-запись на VPS и открытые порты TCP 80,443.

После создания DNS-записи введите по SSH:

    sudo apt-get update
    sudo apt-get install -y git openssl docker.io
    docker compose version
    git clone https://github.com/Egor09390/android-remote-control-mcp.git
    cd android-remote-control-mcp
    sudo bash selfhost/setup-vps.sh mcp.ВАШ-ДОМЕН

ВНИМАНИЕ: на некоторых Ubuntu/Debian Docker Compose v2 ставится отдельно из официального
репозитория Docker; проверьте вывод docker compose version до запуска setup-vps.sh.
Скрипт не перезаписывает существующий .env, создаёт случайный 256-битный секрет и печатает
его в терминал. Не отправляйте этот секрет в чат и не публикуйте в репозитории.

Приложение → Настройки → Туннель → «Свой VPS». Введите адрес
https://mcp.ВАШ-ДОМЕН (без /mcp), а также секрет туннеля из setup-vps.sh.
Включите удалённый доступ и запустите MCP-сервер. Публичный адрес будет
https://mcp.ВАШ-ДОМЕН/mcp — постоянный пока работает ваш VPS.

Диагностика VPS:
    cd android-remote-control-mcp/selfhost
    sudo docker compose ps
    sudo docker compose logs --tail=80
    curl -i https://mcp.ВАШ-ДОМЕН/health

Обновление репозитория: git pull && cd selfhost && sudo docker compose up -d --build.
На VPS не нужно открывать 18080: порт релея остаётся внутри docker-сети.
Посторонний доступ к API ограничивают Bearer/OAuth на самом телефоне.
