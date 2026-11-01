# ARTEMIS host bridge (experimental)

This repository now carries an **ADB byte-stream transport**, not a bundled ARTEMIS runtime. The official [google/artemis](https://github.com/google/artemis) Python agent, MCP server, AI models, task queue, video recorder, and diagnostics stay **unchanged on a VPS**. Do not describe the Android APK as "100% ARTEMIS" until the upstream backend and end-to-end MCP routing are deployed and tested.

## Topology

ChatGPT -- HTTPS/authentication --> (MCP frontend not yet connected to ARTEMIS) -- private network --> official ARTEMIS (Python >=3.12, ADB, model credentials)

ARTEMIS/ADB -- TCP relay:15555/15556 (private Docker network) --> Go relay -- authenticated WSS /adb/device or /adb/pair --> Android app -- 127.0.0.1:<Wireless Debugging port> --> adbd

The private TCP ports **must not** be published through Docker's `ports:` or VPS firewalls. The public WSS entry points require the same random tunnel bearer secret as the original phone connection.

## One-time requirement on the Android phone

1. Enable Android Developer options and **Wireless debugging** using the Android system UI. No ordinary APK can grant itself the ADB privileges required by full ARTEMIS.
2. In the app's first **Server** tab, enter the live Wireless debugging device port and (only during pairing) the *Pair device with pairing code* port. Restart the MCP server if these values change.
3. The phone's WSS sessions must be connected to the private VPS relay. The `ArtemisAdbBridge` client opens only local `127.0.0.1` TCP sockets. No inbound ADB port is exposed from the phone.
4. With Android displaying the six-digit pairing code, from a container on the **same private Docker network** as the Go relay (and with persistent ADB credentials), run `adb pair relay:15556`. Supply the code to ADB locally. Then run `adb connect relay:15555` and `adb devices -l`; the device must be authorized.
5. Run the official ARTEMIS Python engine on that same host/network, with the same ADB server and pairing credentials. The Artemis MCP tools then have their original ADB backend available.

Pairing and device TCP ports are dynamic; a port change must be reflected in app settings. Changes are not yet discovered automatically. Without wireless debugging pairing, this bridge intentionally does not work.

## What remains before the requested one-click product

- Pin, install and supervise the complete official ARTEMIS Python runtime, tools and model configuration on the VPS.
- Expose **the real ARTEMIS MCP tools** through an OAuth-protected, public HTTPS endpoint, instead of forwarding ChatGPT to the Android MCP server. Never expose the original unauthenticated Artemis daemon or ADB TCP port to the internet.
- Make ADB pairing and reconnect diagnostics available in the APK, enforce user authorization, persist ADB identity safely, and handle Android setting resets.
- Verify autonomous tasks, screenshots, file/ADB operations, trace inspection, all MCP tools and recovery against a real phone and VPS.

At present the APK provides a tested Go-level authenticated binary transport, an Android bridge client and UI port controls. This is **not a full end-to-end ARTEMIS deployment**.