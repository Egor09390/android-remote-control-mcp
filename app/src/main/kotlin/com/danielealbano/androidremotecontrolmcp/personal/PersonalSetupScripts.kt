package com.danielealbano.androidremotecontrolmcp.personal

/** Commands affect only this app and the current Android user. No global security/battery changes. */
internal object PersonalSetupScripts {
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    fun grants(packageName: String, user: Int, permissions: List<String>): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")) && user >= 0)
        require(permissions.all { it.matches(Regex("[A-Za-z0-9_.]+")) })
        val pkg = quote(packageName)
        val accessibility = quote("$packageName/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService")
        val listener = quote("$packageName/com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService")
        val grantCommands = permissions.sortedBy {
            when (it) {
                "android.permission.ACCESS_COARSE_LOCATION" -> 0
                "android.permission.ACCESS_FINE_LOCATION" -> 1
                "android.permission.ACCESS_BACKGROUND_LOCATION" -> 3
                else -> 2
            }
        }.joinToString("\n") { "pm grant --user $user $pkg ${quote(it)} >/dev/null 2>&1 || true" }
        return """
            [ "${'$'}(id -u)" = 0 ] || exit 17
            echo ROOT_OK
            $grantCommands
            cmd appops set --user $user $pkg ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1 || true
            CURRENT=${'$'}(settings --user $user get secure enabled_accessibility_services)
            [ "${'$'}CURRENT" = null ] && CURRENT=''
            case ":${'$'}CURRENT:" in
              *:$packageName/com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService:*) ;;
              *)
                if [ -n "${'$'}CURRENT" ]; then CURRENT="${'$'}CURRENT:"; fi
                settings --user $user put secure enabled_accessibility_services "${'$'}CURRENT"$accessibility >/dev/null 2>&1
                ;;
            esac
            settings --user $user put secure accessibility_enabled 1 >/dev/null 2>&1
            cmd notification allow_listener $listener $user >/dev/null 2>&1 || true
            dumpsys deviceidle whitelist +$packageName >/dev/null 2>&1 || true
            cmd appops set --user $user $pkg RUN_IN_BACKGROUND allow >/dev/null 2>&1 || true
            cmd appops set --user $user $pkg RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1 || true
            am set-standby-bucket --user $user $pkg active >/dev/null 2>&1 || true
        """.trimIndent()
    }

    fun backup(dataPath: String, packageName: String, user: Int, restore: Boolean, uid: Int, backupRoot: String = "/data/adb/android-mcp-personal"): String {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")) && user >= 0 && uid >= 0)
        val data = quote(dataPath)
        val base = quote("$backupRoot/user-$user/$packageName")
        val preamble = """
            [ "${'$'}(id -u)" = 0 ] || exit 17
            umask 077
            DATA=$data
            BASE=$base
            [ ! -L "${'$'}BASE" ] || exit 18
            if [ ! -d "${'$'}BASE/current" ] && [ -d "${'$'}BASE/previous" ]; then
              mv "${'$'}BASE/previous" "${'$'}BASE/current" || exit 18
            fi
        """.trimIndent()
        return if (restore) {
            preamble + "\n" + """
                echo ROOT_OK
                if [ ! -f "${'$'}DATA/files/datastore/settings.preferences_pb" ] && [ -f "${'$'}BASE/current/settings.preferences_pb" ]; then
                  mkdir -p "${'$'}DATA/files/datastore" "${'$'}DATA/no_backup/cloudpub" || exit 19
                  for name in settings oauth_clients; do
                    if [ -f "${'$'}BASE/current/${'$'}name.preferences_pb" ]; then
                      cp "${'$'}BASE/current/${'$'}name.preferences_pb" "${'$'}DATA/files/datastore/${'$'}name.preferences_pb" || exit 20
                    fi
                  done
                  if [ -f "${'$'}BASE/current/client.toml" ]; then
                    cp "${'$'}BASE/current/client.toml" "${'$'}DATA/no_backup/cloudpub/client.toml" || exit 21
                  fi
                  chown $uid:$uid "${'$'}DATA/files" "${'$'}DATA/no_backup" || exit 22
                  chmod 700 "${'$'}DATA/files" "${'$'}DATA/no_backup"
                  chown -R $uid:$uid "${'$'}DATA/files/datastore" "${'$'}DATA/no_backup/cloudpub" || exit 22
                  chmod -R u+rwX,go-rwx "${'$'}DATA/files/datastore" "${'$'}DATA/no_backup/cloudpub"
                  restorecon -RF "${'$'}DATA/files/datastore" "${'$'}DATA/no_backup/cloudpub" >/dev/null 2>&1 || true
                  echo RESTORED_OK
                fi
            """.trimIndent()
        } else {
            preamble + "\n" + """
                [ -f "${'$'}DATA/files/datastore/settings.preferences_pb" ] || exit 23
                [ ! -d "${'$'}BASE/staging" ] || rm -rf "${'$'}BASE/staging"
                mkdir -p "${'$'}BASE/staging" || exit 24
                chmod 700 "${'$'}BASE" "${'$'}BASE/staging"
                for name in settings oauth_clients; do
                  if [ -f "${'$'}DATA/files/datastore/${'$'}name.preferences_pb" ]; then
                    cp "${'$'}DATA/files/datastore/${'$'}name.preferences_pb" "${'$'}BASE/staging/${'$'}name.preferences_pb" || exit 25
                  fi
                done
                if [ -f "${'$'}DATA/no_backup/cloudpub/client.toml" ]; then
                  cp "${'$'}DATA/no_backup/cloudpub/client.toml" "${'$'}BASE/staging/client.toml" || exit 26
                fi
                # Keep the last complete snapshot until the replacement is ready.
                if [ -d "${'$'}BASE/previous" ]; then rm -rf "${'$'}BASE/previous"; fi
                if [ -d "${'$'}BASE/current" ]; then mv "${'$'}BASE/current" "${'$'}BASE/previous" || exit 27; fi
                mv "${'$'}BASE/staging" "${'$'}BASE/current" || {
                  [ ! -d "${'$'}BASE/previous" ] || mv "${'$'}BASE/previous" "${'$'}BASE/current"
                  exit 28
                }
                chmod -R go-rwx "${'$'}BASE"
                echo BACKUP_OK
            """.trimIndent()
        }
    }
}
