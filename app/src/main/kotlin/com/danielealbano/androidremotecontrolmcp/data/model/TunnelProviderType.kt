package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Available tunnel provider types for remote access.
 *
 * @property adbValue The value accepted by the `tunnel_provider` ADB / E2E extra.
 */
enum class TunnelProviderType(
    val adbValue: String,
) {
    /** Free end-to-end encrypted tunnel — no account, hardware-attested enrollment, device-held certificate. */
    ARC_TUNNEL("android-remote-control-mcp-tunneld"),

    /** Cloudflare Quick Tunnel — no account needed, random URL per session. */
    CLOUDFLARE("CLOUDFLARE"),

    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK("NGROK"),

    /** Hosted zrok2 public HTTPS share; no owner-operated VPS required. */
    ZROK("ZROK"),

    /** Owner-operated reverse tunnel hosted on a private VPS. */
    SELF_HOSTED("SELF_HOSTED"),
    ;

    companion object {
        /** Resolves an ADB value; null when unknown. */
        fun fromAdbValue(value: String): TunnelProviderType? = entries.firstOrNull { it.adbValue == value }
    }
}
