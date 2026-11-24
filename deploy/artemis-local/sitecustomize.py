"""Route adbutils (and uiautomator2) to the same private ADB server as the CLI."""

import os
import socket


def configure_adb(socket_path):
    from adbutils import _adb

    def connect(_self):
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            connection.settimeout(3)
            connection.connect(socket_path)
            connection.settimeout(None)
            return connection
        except OSError as exc:
            connection.close()
            raise _adb.AdbConnectionError("Private ADB socket is unavailable") from exc

    _adb.AdbConnection._create_socket = connect
    return True


socket_path = os.environ.get("ARTEMIS_LOCAL_ADB_SOCKET")
private_adb_configured = bool(socket_path and configure_adb(socket_path))
