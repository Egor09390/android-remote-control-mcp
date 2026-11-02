# S23 personal build

Build `assembleFossRelease -POWN_VPS_TEST_APK=true -PPERSONAL_BUILD=true`.
The application ID stays compatible with the earlier `.ownvps` preview; the
signing key must also match to install an update in place.

The personal build has one compact screen: MCP/CloudPub state, copyable URL,
one verified row for every permission in the merged installed manifest,
root/accessibility/notification/background checks, and a retry control.
Failed rows show their exact permission/condition when tapped. A small settings
sheet holds only the CloudPub token and boot-start preference. OAuth connection
approvals remain explicit and reachable when pending.

At process startup, before settings or OAuth stores are consumed, the app requests
`su`. A root manager can require its first grant; the APK cannot approve itself.
No Android runtime-permission dialogs are launched by the personal startup path.
`pm grant` targets this app's declared dangerous permissions and current Android
user, with coarse/fine location granted before background location. Other enabled
accessibility services are preserved. Notification-listener and battery exceptions
are scoped to this app. No global SELinux, signature, lock-screen, notification,
privacy-indicator, or certificate protection is disabled. The dashboard checks the
Android APIs afterwards, so a rejected grant remains red.

A one-time profile selects loopback MCP + public CloudPub HTTPS, OAuth and bearer
authentication, all Android automation tools, writable built-in media storage,
auto-start, visible tool indicators, 100 MB/120 s downloads, verified HTTPS, and
blocking of downloads to private network addresses. Event forwarding, optional
privacy-model processing, upstream auto-update checks and unused root ADB/VPS
configuration are disabled. Existing tokens and OAuth signing identity are retained.

Root-owned backups are kept with mode 0700 below
`/data/adb/android-mcp-personal/user-<id>/<application-id>/` and contain only
`settings.preferences_pb`, `oauth_clients.preferences_pb`, and CloudPub's
`client.toml`. Secrets never enter source control, chat, release assets or logs.
File observers capture later settings/identity changes. A new install restores
these before DataStore reads, repairs UID ownership/SELinux labels and reapplies
the personal profile. Live app data is never overwritten by an older backup;
interrupted backup swaps recover the previous complete snapshot. Backups can only
restore state captured by this version; an old uninstallation cannot be undone.
Persisted SAF selections can still require re-selection after uninstall because
Android's URI grants are outside the app's private state.

The release includes only ARM64 native code and the CloudPub tunnel executable.
The informational IP-geolocation database (~100 MB unpacked),
Google Play Services/geofences, old tunnel binaries and VPS deployment assets are
absent. General Android tools, screenshots, camera, microphone, files, location and
notifications stay available. This is not a Google ARTEMIS runtime integration.

Validation: root script execution against isolated fixture commands; current-user
scope; preservation of other accessibility services; ordered location grants;
shell escaping; real backup/restore round trips; stale-backup and interrupted-swap
protection; existing settings/CloudPub/tunnel unit tests; Android compilation;
signature and ZIP-content checks. Actual Samsung/One UI root behavior requires
checking the resulting dashboard on the phone.
