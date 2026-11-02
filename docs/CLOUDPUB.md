# CloudPub on Android

The Server tab offers CloudPub first. Register at <https://cloudpub.ru/dashboard>,
copy the API token into the app, and choose **Save CloudPub and enable MCP**.
The account dashboard may require its own account verification. A VPS, Termux,
router port forwarding and a separate CloudPub app are not required.

The build packages the official `clo` Android ARM64 executable, version
`3.7.956-stable`, as `libcloudpub.so`. Android extracts it to the executable
native-library directory. Other device architectures show an explicit error.
`scripts/package-cloudpub.py` validates the ELF architecture, PIE type and
Android linker, and records the downloaded archive and executable SHA-256s.

The agent publishes `http://127.0.0.1:<tunnelLocalPort>`. Its configuration and
agent identity remain in the app's private, non-backed-up directory across
stops and restarts, preserving the server-assigned hostname. Clearing app data,
uninstalling, changing accounts or deleting the publication can change it.
The app keeps OAuth/bearer authorization enabled as configured; CloudPub is
transport, not authentication. The CloudPub API token is never part of the
copied MCP URL or an application log entry.

The app accepts only an acknowledgement for its exact local origin and an
HTTPS CloudPub subdomain. It reports connected only after the public `/health`
returns healthy JSON, so an HTML warning, login page, redirect or error page
does not appear as a ready MCP connection. It periodically rechecks reachability
and restarts an exited agent with backoff. Copy the resulting `/mcp` URL from
the ChatGPT connection card. The normal CloudPub client handles network retries.

Build checks cover Android compilation, URL parsing, rejection of interstitials,
missing/invalid configuration, provider routing and persisted settings.
Actual Samsung S23 / Beeline connectivity and a full ChatGPT OAuth/MCP session
require a CloudPub account on the device and remain unverified until tested there.
Google ARTEMIS is not added by this transport integration.

Official references: <https://cloudpub.ru/docs/>,
<https://cloudpub.ru/docs/cli>, <https://cloudpub.ru/plans/>.
