package com.media.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

// ============================================================================
//  AURA SHARE - the network half
//
//  Casting to a TV without a dongle. Google Cast only talks to Cast hardware;
//  UPnP/DLNA is what every smart TV, AV receiver and network speaker has
//  spoken for fifteen years, and it needs nothing but the same Wi-Fi.
//
//  The shape of it: shout on the LAN, read the answer, tell the renderer to
//  fetch a URL off this phone and play it. The TV decodes; the phone is a
//  remote. That is also why picture and sound can never drift apart - one
//  device is doing both.
//
//  RAW SOCKETS, deliberately. UPnP is plain HTTP on the local network, and
//  targetSdk 36 blocks cleartext for HttpURLConnection and OkHttp. The usual
//  workaround is cleartextTrafficPermitted=true, which switches that
//  protection off for the WHOLE app including the ad SDK. A socket is not
//  subject to the network security config, so the LAN stays reachable and
//  everything that leaves this device keeps its TLS requirement.
// ============================================================================

data class Renderer(
    val id: String,              // the description URL; unique and stable per boot
    val name: String,
    val model: String,
    val controlUrl: String,      // absolute, AVTransport
    val serviceType: String,
    // RenderingControl is a SEPARATE service on the same device and plenty of
    // renderers ship without it. Null means this one has no volume we can
    // reach, which is a thing to hide a slider for, not to fail over.
    val volumeUrl: String? = null,
    val volumeType: String? = null
)

private const val SSDP_HOST = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"

object AuraShare {

    // ------------------------------------------------------ WHICH INTERFACE
    //
    // A phone with mobile data up and Wi-Fi up has TWO networks, and mobile
    // is usually the default route. An unbound socket therefore sends the
    // discovery multicast out of the cellular interface, where it reaches
    // nothing and nothing answers - which is indistinguishable from an empty
    // network and is exactly the bug that made this look broken.
    //
    // So every socket here is bound to the Wi-Fi (or Ethernet) network
    // explicitly. allNetworks would be one line, and is deprecated from API
    // 31; a one-shot callback is the supported way to ask the same question.
    @Volatile
    private var lan: Network? = null

    fun lanNetwork(context: Context, timeoutMs: Long = 2500): Network? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        // The current one still counts if it is already the right kind.
        cm.activeNetwork?.let { active ->
            if (isLan(cm, active)) {
                lan = active
                return active
            }
        }
        val latch = CountDownLatch(1)
        var found: Network? = null
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found = network
                latch.countDown()
            }
        }
        return try {
            cm.registerNetworkCallback(request, callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            lan = found
            found
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    private fun isLan(cm: ConnectivityManager, n: Network): Boolean {
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ---------------------------------------------------------------- HTTP
    //
    // Small, blocking, and deliberately incurious: UPnP control responses are
    // a few hundred bytes. Connection: close so the read terminates at EOF,
    // with de-chunking for the renderers that ignore that and reply chunked
    // anyway - which is most of the cheap ones.
    private fun request(
        url: URL,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        timeoutMs: Int = 5000
    ): String? {
        return try {
            Socket().use { sock ->
                val port = if (url.port > 0) url.port else 80
                // Same reason as discovery: talk to the TV over the network
                // the TV is on, not over whatever happens to be the default.
                runCatching { lan?.bindSocket(sock) }
                sock.connect(InetSocketAddress(url.host, port), timeoutMs)
                sock.soTimeout = timeoutMs
                val path = (url.path.ifEmpty { "/" }) + (url.query?.let { "?$it" } ?: "")
                val head = StringBuilder()
                    .append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
                    .append("HOST: ").append(url.host).append(':').append(port).append("\r\n")
                    .append("CONNECTION: close\r\n")
                    .append("USER-AGENT: Aura/1 UPnP/1.0\r\n")
                headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("CONTENT-LENGTH: ").append(body?.size ?: 0).append("\r\n\r\n")
                sock.getOutputStream().apply {
                    write(head.toString().toByteArray(Charsets.UTF_8))
                    if (body != null) write(body)
                    flush()
                }
                val raw = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
                val split = raw.indexOf("\r\n\r\n")
                if (split < 0) return null
                val header = raw.substring(0, split)
                if (!header.lineSequence().first().contains(" 200")) return null
                val payload = raw.substring(split + 4)
                if (header.contains("Transfer-Encoding: chunked", ignoreCase = true))
                    dechunk(payload) else payload
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun dechunk(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val nl = s.indexOf("\r\n", i)
            if (nl < 0) break
            val size = s.substring(i, nl).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = nl + 2
            val end = minOf(start + size, s.length)
            out.append(s, start, end)
            i = end + 2
        }
        return out.toString()
    }

    // ----------------------------------------------------------- DISCOVERY
    //
    // M-SEARCH is UDP, so it is lossy by design: the search goes out three
    // times and answers are collected until the window closes. A renderer
    // that answers twice is the same renderer - they are keyed by location.
    //
    // The multicast lock matters. Without it Wi-Fi hardware filters multicast
    // packets to save power and the phone never hears a single reply, which
    // looks exactly like "no devices on this network".
    fun discover(context: Context, windowMs: Long = 3000): List<Renderer> {
        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val lock = wifi?.createMulticastLock("aura-share")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }
        if (lan == null) lanNetwork(context)
        val found = LinkedHashMap<String, Renderer>()
        try {
            val locations = linkedSetOf<String>()
            DatagramSocket().use { sock ->
                runCatching { lan?.bindSocket(sock) }
                sock.broadcast = true
                sock.soTimeout = 400
                // Two searches. MediaRenderer is the correct one and most
                // devices answer it; a stubborn minority only ever answer
                // ssdp:all. Anything without an AVTransport service is
                // dropped when its description is read, so widening the net
                // costs a few HTTP GETs and nothing else.
                val probes = listOf(RENDERER, "ssdp:all").map { st ->
                    ("M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: $SSDP_HOST:$SSDP_PORT\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: $st\r\n\r\n").toByteArray(Charsets.UTF_8)
                }
                val target = InetAddress.getByName(SSDP_HOST)
                repeat(2) {
                    probes.forEach { probe ->
                        runCatching {
                            sock.send(DatagramPacket(probe, probe.size, target, SSDP_PORT))
                        }
                    }
                }
                val deadline = System.currentTimeMillis() + windowMs
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(packet)
                    } catch (t: Throwable) {
                        continue
                    }
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    text.lineSequence()
                        .firstOrNull { it.startsWith("LOCATION:", true) }
                        ?.substringAfter(':')?.trim()
                        ?.let { if (it.startsWith("http")) locations += it }
                }
            }
            for (loc in locations) describe(loc)?.let { found[it.id] = it }
        } catch (t: Throwable) {
            // A network that refuses multicast is an empty list, not a crash.
        } finally {
            runCatching { lock?.release() }
        }
        return found.values.toList()
    }

    // The description is machine-generated XML with a fixed shape, so it is
    // read with patterns rather than a pull parser. If a device ever ships
    // something exotic enough to break this, it returns null and simply does
    // not appear - which is the right failure for a device we cannot drive.
    private val TAG = { n: String -> Regex("<$n[^>]*>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL) }
    private val SERVICE = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)

    private fun describe(location: String): Renderer? {
        val base = runCatching { URL(location) }.getOrNull() ?: return null
        val xml = request(base, "GET", timeoutMs = 2500) ?: return null
        val services = SERVICE.findAll(xml).map { it.groupValues[1] }.toList()
        val service = services.firstOrNull { it.contains("AVTransport", true) } ?: return null
        val type = TAG("serviceType").find(service)?.groupValues?.get(1)?.trim() ?: return null
        val control = TAG("controlURL").find(service)?.groupValues?.get(1)?.trim() ?: return null
        val absolute = runCatching { URL(base, control).toString() }.getOrNull() ?: return null
        val rc = services.firstOrNull { it.contains("RenderingControl", true) }
        val rcType = rc?.let { TAG("serviceType").find(it)?.groupValues?.get(1)?.trim() }
        val rcUrl = rc?.let { TAG("controlURL").find(it)?.groupValues?.get(1)?.trim() }
            ?.let { runCatching { URL(base, it).toString() }.getOrNull() }
        val name = TAG("friendlyName").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val model = TAG("modelName").find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        return Renderer(
            id = location,
            name = if (name.isNotEmpty()) unescape(name) else base.host,
            model = unescape(model),
            controlUrl = absolute,
            serviceType = type,
            volumeUrl = rcUrl,
            volumeType = rcType
        )
    }

    // -------------------------------------------------------------- ACTIONS
    private fun call(endpoint: String?, type: String?, action: String, args: String): String? {
        if (endpoint == null || type == null) return null
        val url = runCatching { URL(endpoint) }.getOrNull() ?: return null
        val envelope = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:$action xmlns:u="$type"><InstanceID>0</InstanceID>$args</u:$action></s:Body>
</s:Envelope>"""
        return request(
            url, "POST",
            mapOf(
                "SOAPACTION" to "\"$type#$action\"",
                "CONTENT-TYPE" to "text/xml; charset=\"utf-8\""
            ),
            envelope.toByteArray(Charsets.UTF_8)
        )
    }

    private fun soap(r: Renderer, action: String, args: String): String? =
        call(r.controlUrl, r.serviceType, action, args)

    private fun render(r: Renderer, action: String, args: String): String? =
        call(r.volumeUrl, r.volumeType, action, args)

    /**
     * Hand the renderer a URL and start it.
     *
     * The metadata is not optional in practice. A bare SetAVTransportURI with
     * an empty CurrentURIMetaData is legal and several renderers reject it, so
     * a minimal DIDL-Lite item goes with every request - it is also what puts
     * the title on the TV's own overlay instead of a file path.
     */
    fun play(r: Renderer, url: String, title: String, mime: String, durationMs: Long): Boolean {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>${if (mime.startsWith("video")) "object.item.videoItem" else "object.item.audioItem.musicTrack"}</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:*\" duration=\"${clock(durationMs)}\">${escape(url)}</res>" +
            "</item></DIDL-Lite>"
        val loaded = soap(
            r, "SetAVTransportURI",
            "<CurrentURI>${escape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escape(didl)}</CurrentURIMetaData>"
        )
        // A 200 with an empty body is still a 200. Judge on the response
        // arriving, never on what is in it.
        if (loaded == null) return false
        return soap(r, "Play", "<Speed>1</Speed>") != null
    }

    fun pause(r: Renderer): Boolean = soap(r, "Pause", "") != null
    fun resume(r: Renderer): Boolean = soap(r, "Play", "<Speed>1</Speed>") != null
    fun stop(r: Renderer): Boolean = soap(r, "Stop", "") != null

    fun seek(r: Renderer, ms: Long): Boolean =
        soap(r, "Seek", "<Unit>REL_TIME</Unit><Target>${clock(ms)}</Target>") != null

    /** Where the renderer thinks it is, in ms, or -1 when it will not say. */
    fun position(r: Renderer): Long {
        val body = soap(r, "GetPositionInfo", "") ?: return -1L
        val rel = TAG("RelTime").find(body)?.groupValues?.get(1)?.trim() ?: return -1L
        return parseClock(rel)
    }

    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, or null. */
    fun transportState(r: Renderer): String? =
        soap(r, "GetTransportInfo", "")
            ?.let { TAG("CurrentTransportState").find(it)?.groupValues?.get(1)?.trim() }

    fun next(r: Renderer): Boolean = soap(r, "Next", "") != null
    fun previous(r: Renderer): Boolean = soap(r, "Previous", "") != null

    /** How long the renderer thinks the item is, in ms, or -1. */
    fun duration(r: Renderer): Long {
        val body = soap(r, "GetPositionInfo", "") ?: return -1L
        val d = TAG("TrackDuration").find(body)?.groupValues?.get(1)?.trim() ?: return -1L
        return parseClock(d)
    }

    /** 0..100, or -1 when this renderer has no volume service. */
    fun volume(r: Renderer): Int {
        val body = render(r, "GetVolume", "<Channel>Master</Channel>") ?: return -1
        return TAG("CurrentVolume").find(body)?.groupValues?.get(1)?.trim()?.toIntOrNull() ?: -1
    }

    fun setVolume(r: Renderer, value: Int): Boolean = render(
        r, "SetVolume",
        "<Channel>Master</Channel><DesiredVolume>${value.coerceIn(0, 100)}</DesiredVolume>"
    ) != null

    // ---------------------------------------------------------------- UTIL
    /**
     * This phone's address on the LAN, for the URL the renderer will fetch.
     * Read off the Wi-Fi network specifically - the active network's address
     * is a carrier address when mobile data is up, and no TV can reach that.
     */
    fun localAddress(context: Context): String? {
        val cm = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        val net = lan ?: lanNetwork(context) ?: return null
        val props = cm.getLinkProperties(net) ?: return null
        return props.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }

    internal fun clock(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(t / 3600, (t % 3600) / 60, t % 60)
    }

    private fun parseClock(s: String): Long {
        val parts = s.substringBefore('.').split(':').mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
            2 -> (parts[0] * 60 + parts[1]) * 1000
            else -> -1L
        }
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
