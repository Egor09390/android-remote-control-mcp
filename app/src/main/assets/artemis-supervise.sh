#!/system/bin/sh
set -eu
umask 077
export PATH=/system/bin:/system/xbin

root="$1"
state="$2"
uid="$3"
owner_pid="$4"
stop_file="$5"
child=
adb_changed=0

cleanup() {
    trap - EXIT TERM INT HUP
    if [ -n "$child" ]; then
        kill -TERM "$child" 2>/dev/null || true
        wait "$child" 2>/dev/null || true
    fi
    if [ "$adb_changed" = 1 ]; then
        stop adbd || true
        setprop service.adb.listen_addrs "$old_listen" || true
        setprop service.adb.tcp.port "$old_port" || true
        if [ "$old_status" = running ]; then start adbd || true; fi
    fi
}
trap cleanup EXIT
trap 'exit 143' TERM INT HUP

if [ "$(id -u)" != 0 ]; then
    echo ARTEMIS_ROOT_REQUIRED
    exit 1
fi
if [ "$(getprop ro.adb.secure)" != 1 ] || [ "$(getprop service.adb.root)" = 1 ]; then
    echo ARTEMIS_ADB_UNSAFE
    exit 1
fi

if ! "$root/artemis-launcher" "$root" "$state" "$uid" keygen; then
    echo ARTEMIS_RUNTIME_UNSUPPORTED
    exit 1
fi

keys=/data/misc/adb/adb_keys
pubkey="$(cut -d ' ' -f 1 "$state/home/.android/adbkey.pub")"
case "$pubkey" in
    ''|*[!A-Za-z0-9+/=]*) echo ARTEMIS_ADB_KEY_ERROR; exit 1 ;;
esac
[ "${#pubkey}" -eq 700 ] || { echo ARTEMIS_ADB_KEY_ERROR; exit 1; }
if [ -L "$keys" ] || { [ -e "$keys" ] && [ ! -f "$keys" ]; }; then
    echo ARTEMIS_ADB_KEY_ERROR
    exit 1
fi
if [ ! -e "$keys" ]; then
    touch "$keys"
    chown 1000:2000 "$keys"
    chmod 0640 "$keys"
    restorecon "$keys"
fi
grep -Fqx "$pubkey s23-control-artemis" "$keys" ||
    printf '\n%s s23-control-artemis\n' "$pubkey" >> "$keys"

# The connection must be authenticated and visible only on the local interface.
old_listen="$(getprop service.adb.listen_addrs)"
old_port="$(getprop service.adb.tcp.port)"
old_status="$(getprop init.svc.adbd)"
adb_changed=1
setprop service.adb.listen_addrs tcp:localhost:15575
setprop service.adb.tcp.port 0
stop adbd
start adbd

attempt=0
while [ "$attempt" -lt 10 ]; do
    attempt=$((attempt + 1))
    listeners="$(awk '$4 == "0A" && $2 ~ /:3CD7$/ {print $2}' /proc/net/tcp /proc/net/tcp6)"
    if [ "$listeners" = "0100007F:3CD7" ]; then break; fi
    if [ -n "$listeners" ] || [ -e "$stop_file" ]; then break; fi
    sleep 1
done
if [ "$listeners" != "0100007F:3CD7" ]; then
    echo ARTEMIS_ADB_UNSAFE
    exit 1
fi

"$root/artemis-launcher" "$root" "$state" "$uid" serve &
child=$!
while kill -0 "$child" 2>/dev/null && kill -0 "$owner_pid" 2>/dev/null && [ ! -e "$stop_file" ]; do
    sleep 1
done
if kill -0 "$child" 2>/dev/null; then exit 0; fi
wait "$child"
