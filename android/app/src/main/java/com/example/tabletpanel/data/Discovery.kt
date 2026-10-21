package com.example.tabletpanel.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import kotlin.coroutines.resume

private const val SERVICE_TYPE = "_tabletpanel._tcp."
private const val DEFAULT_PORT = 8787
private const val TAG = "Discovery"

/**
 * Finds the TabletPanel server on the LAN: first via mDNS (the server advertises
 * _tabletpanel._tcp), then, because NSD is unreliable on many older or budget tablets, by
 * probing the default port on every address in the tablet's own subnet.
 */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Returns a base URL such as http://192.168.1.20:8787, or null if no server was found. */
    suspend fun find(): String? {
        // Some devices drop multicast packets unless an app holds this lock.
        val lock = wifi.createMulticastLock("tabletpanel-discovery").apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            withTimeoutOrNull(10_000) { discoverOnce() }?.let {
                Log.i(TAG, "found via mDNS: $it")
                return it
            }
        } finally {
            lock.release()
        }
        Log.i(TAG, "mDNS found nothing; scanning the local subnet")
        return scanSubnet()?.also { Log.i(TAG, "found via subnet scan: $it") }
    }

    private suspend fun discoverOnce(): String? {
        var listener: NsdManager.DiscoveryListener? = null
        try {
            return suspendCancellableCoroutine { cont ->
                var resolving = false
                val l = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        Log.w(TAG, "discovery failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(service: NsdServiceInfo) {}

                    override fun onServiceFound(service: NsdServiceInfo) {
                        Log.d(TAG, "service found: ${service.serviceName}")
                        // Resolve one service at a time; older Android versions reject parallel resolves.
                        if (resolving) return
                        resolving = true
                        resolve(service) { url ->
                            resolving = false
                            if (url != null && cont.isActive) cont.resume(url)
                        }
                    }
                }
                listener = l
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    @Suppress("DEPRECATION") // resolveService still works everywhere; the API 34 replacement needs an executor dance.
    private fun resolve(service: NsdServiceInfo, done: (String?) -> Unit) {
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                // 3 = FAILURE_ALREADY_ACTIVE: an earlier resolve is stuck in the system; the subnet scan covers it.
                Log.w(TAG, "resolve failed: $errorCode")
                done(null)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val addr = pickAddress(info) ?: return done(null)
                val host = if (addr is Inet4Address) addr.hostAddress else "[${addr.hostAddress}]"
                done("http://$host:${info.port}")
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun pickAddress(info: NsdServiceInfo): InetAddress? {
        val all = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses else listOfNotNull(info.host)
        // A multi-homed server may list addresses from other networks (VPN, VM adapters); prefer ours.
        val local = localSubnet()
        return all.firstOrNull { it is Inet4Address && local != null && local.contains(it) }
            ?: all.firstOrNull { it is Inet4Address }
            ?: all.firstOrNull()
    }

    /** Probes every host in the tablet's /24 (or smaller) subnet for the server's health endpoint. */
    private suspend fun scanSubnet(): String? = withContext(Dispatchers.IO) {
        val subnet = localSubnet() ?: return@withContext null
        val hosts = subnet.hosts()
        if (hosts.isEmpty()) return@withContext null
        val gate = Semaphore(48)
        coroutineScope {
            hosts.map { host ->
                async { gate.withPermit { "http://$host:$DEFAULT_PORT".takeIf { isPanelServer(it) } } }
            }.awaitAll().firstOrNull { it != null }
        }
    }

    private fun isPanelServer(baseUrl: String): Boolean = runCatching {
        val conn = URL("$baseUrl/api/health").openConnection() as HttpURLConnection
        conn.connectTimeout = 600
        conn.readTimeout = 800
        try {
            conn.responseCode == 200 && conn.inputStream.bufferedReader().use { it.readText() }.contains("\"ok\":true")
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private data class Subnet(val address: Inet4Address, val prefix: Int) {
        private fun Inet4Address.toInt() = address.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
        private val mask = if (prefix == 0) 0 else -1 shl (32 - prefix)

        fun contains(other: InetAddress) = other is Inet4Address && (other.toInt() and mask) == (address.toInt() and mask)

        /** Every other usable address in the subnet; limited to /24 so a big network doesn't mean thousands of probes. */
        fun hosts(): List<String> {
            val p = prefix.coerceAtLeast(24)
            val m = -1 shl (32 - p)
            val self = address.toInt()
            val base = self and m
            return (1 until (1 shl (32 - p)) - 1).map { base + it }.filter { it != self }.map { ip ->
                "${ip ushr 24 and 0xFF}.${ip ushr 16 and 0xFF}.${ip ushr 8 and 0xFF}.${ip and 0xFF}"
            }
        }
    }

    /** The tablet's own IPv4 address and prefix on its active (usually Wi-Fi) interface. */
    private fun localSubnet(): Subnet? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .firstNotNullOfOrNull { nif ->
                nif.interfaceAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLinkLocalAddress }
                    ?.let { Subnet(it.address as Inet4Address, it.networkPrefixLength.toInt()) }
            }
    }.getOrNull()
}
