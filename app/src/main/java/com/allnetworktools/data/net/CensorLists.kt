package com.allnetworktools.data.net

import com.allnetworktools.data.net.Endpoint.How

/**
 * What the censorship test checks by default: well-known sites in the categories censors most often aim at (in France
 * and the EU too: sanctioned state media, unlicensed gambling, file sharing, adult sites without age checks), the main
 * messaging apps and the usual ways around a block. The OONI list for a country can be added on top.
 */
object CensorLists {
    enum class Category { News, StateMedia, Social, Search, Rights, Lgbt, Comms, Ai, FileSharing, Gambling, Adult, Crypto }

    val sites: Map<Category, List<String>> = mapOf(
        Category.News to listOf(
            "www.lemonde.fr", "www.liberation.fr", "www.mediapart.fr", "www.bbc.com", "www.nytimes.com", "www.theguardian.com",
            "www.reuters.com", "www.aljazeera.com", "www.dw.com", "meduza.io", "novayagazeta.eu", "www.rferl.org", "www.voanews.com",
        ),
        Category.StateMedia to listOf("www.rt.com", "francais.rt.com", "sputnikglobe.com", "tass.com", "www.globaltimes.cn"),
        Category.Social to listOf(
            "www.facebook.com", "www.instagram.com", "x.com", "www.tiktok.com", "www.reddit.com", "www.youtube.com",
            "www.linkedin.com", "bsky.app", "mastodon.social", "vk.com", "www.twitch.tv",
        ),
        Category.Search to listOf("www.google.com", "duckduckgo.com", "www.bing.com", "fr.wikipedia.org", "web.archive.org"),
        Category.Rights to listOf("www.amnesty.org", "www.hrw.org", "rsf.org", "www.eff.org", "www.accessnow.org", "www.laquadrature.net", "ooni.org"),
        Category.Lgbt to listOf("ilga.org", "www.glaad.org", "www.grindr.com"),
        Category.Comms to listOf("signal.org", "www.whatsapp.com", "telegram.org", "element.io", "proton.me", "zoom.us", "discord.com"),
        Category.Ai to listOf("chatgpt.com", "claude.ai", "gemini.google.com", "chat.deepseek.com", "www.perplexity.ai"),
        Category.FileSharing to listOf("thepiratebay.org", "1337x.to", "libgen.is", "sci-hub.se", "annas-archive.org", "z-library.sk"),
        Category.Gambling to listOf("www.pokerstars.com", "www.bet365.com", "1xbet.com", "stake.com"),
        Category.Adult to listOf("www.pornhub.com", "www.xvideos.com", "xhamster.com"),
        Category.Crypto to listOf("www.binance.com", "www.coinbase.com", "www.kraken.com"),
    )

    val messaging: List<AppTarget> = listOf(
        AppTarget(
            "WhatsApp",
            listOf("e1", "e3", "e8", "e12").flatMap { listOf(Endpoint("$it.whatsapp.net", 443, How.Tcp), Endpoint("$it.whatsapp.net", 5222, How.Tcp)) } +
                listOf(Endpoint("web.whatsapp.com"), Endpoint("v.whatsapp.net")),
        ),
        AppTarget(
            "Telegram",
            // The five data centres, on the ports the apps use, then the web client.
            listOf("149.154.175.50", "149.154.167.51", "149.154.175.100", "149.154.167.91", "91.108.56.130")
                .flatMap { listOf(Endpoint(it, 443, How.Tcp), Endpoint(it, 80, How.Tcp)) } + Endpoint("web.telegram.org"),
        ),
        AppTarget(
            "Signal",
            // The chat and storage servers use Signal's own certificate authority: the handshake is what counts.
            listOf("chat.signal.org", "storage.signal.org", "cdn.signal.org", "cdn2.signal.org", "sfu.voip.signal.org")
                .map { Endpoint(it, 443, How.TlsAnyCert) } + Endpoint("updates.signal.org"),
        ),
        AppTarget(
            "Facebook Messenger",
            listOf("b-api.facebook.com", "b-graph.facebook.com", "edge-mqtt.facebook.com", "external.xx.fbcdn.net", "scontent.xx.fbcdn.net", "star.c10r.facebook.com")
                .map { Endpoint(it) },
        ),
        AppTarget("Discord", listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net").map { Endpoint(it) }),
    )

    val circumvention: List<AppTarget> = listOf(
        AppTarget(
            "Tor",
            // The directory authorities (tor's auth_dirs.inc), OR port and directory port.
            listOf(
                "128.31.0.39" to listOf(9201, 9231), "217.196.147.77" to listOf(443, 80), "45.66.35.11" to listOf(443, 80),
                "131.188.40.189" to listOf(443, 80), "193.23.244.244" to listOf(443, 80), "91.223.231.13" to listOf(80, 443),
                "199.58.81.140" to listOf(443, 80), "216.218.219.41" to listOf(443, 80), "204.13.164.118" to listOf(443, 80),
            ).flatMap { (ip, ports) -> ports.map { Endpoint(ip, it, How.Tcp) } },
            sites = listOf("www.torproject.org"),
        ),
        AppTarget(
            "Tor obfs4",
            // Tor Browser's built-in obfs4 bridges (tor-browser-build pt_config.json).
            listOf(
                "37.218.245.14" to 38224, "209.148.46.65" to 443, "146.57.248.225" to 22, "45.145.95.6" to 27015,
                "51.222.13.177" to 80, "212.83.43.95" to 443, "212.83.43.74" to 443,
            ).map { (ip, port) -> Endpoint(ip, port, How.Tcp) },
            sites = listOf("bridges.torproject.org"),
        ),
        AppTarget(
            "Tor Snowflake",
            listOf(Endpoint("snowflake-broker.torproject.net"), Endpoint("1098762253.rsc.cdn77.org"), Endpoint("app.datapacket.com"), Endpoint("www.datapacket.com")),
        ),
        AppTarget("Tor meek", listOf(Endpoint("1603026938.rsc.cdn77.org"), Endpoint("www.phpmyadmin.net"))),
        AppTarget(
            "DNS over HTTPS",
            listOf(Endpoint("cloudflare-dns.com", 443, How.Doh), Endpoint("dns.google", 443, How.Doh), Endpoint("dns.quad9.net", 443, How.Doh)),
        ),
        AppTarget(
            "DNS over TLS",
            listOf(Endpoint("one.one.one.one", 853), Endpoint("dns.google", 853), Endpoint("dns.quad9.net", 853)),
        ),
        AppTarget("Proton VPN", listOf(Endpoint("vpn-api.proton.me"), Endpoint("account.proton.me")), sites = listOf("protonvpn.com")),
        AppTarget("Mullvad VPN", listOf(Endpoint("api.mullvad.net")), sites = listOf("mullvad.net")),
        AppTarget("NordVPN", listOf(Endpoint("api.nordvpn.com")), sites = listOf("nordvpn.com")),
        AppTarget("ExpressVPN", emptyList(), sites = listOf("www.expressvpn.com")),
        AppTarget("Windscribe", listOf(Endpoint("api.windscribe.com")), sites = listOf("windscribe.com")),
        AppTarget("RiseupVPN", listOf(Endpoint("api.black.riseup.net", 443, How.TlsAnyCert)), sites = listOf("riseup.net")),
        AppTarget("Psiphon", emptyList(), sites = listOf("psiphon.ca")),
        AppTarget("Lantern", emptyList(), sites = listOf("lantern.io")),
    )
}
