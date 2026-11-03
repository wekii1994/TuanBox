/*
 * This file is part of YumeBox.
 *
 * YumeBox is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c)  YumeYucca 2025 - Present
 *
 */

package com.github.yumeyucca.yumebox.feature.meta.presentation.viewmodel

import com.google.common.net.InetAddresses
import com.google.common.net.InternetDomainName
import java.net.IDN
import java.util.Locale

data class BrandEntity(
    val entityKey: String,
    val displayName: String,
    val primaryDomain: String,
    val serviceTag: String? = null,
    val isIp: Boolean = false,
)

data class NormalizedDomain(
    val rawHost: String,
    val rootDomain: String,
    val brandEntity: BrandEntity,
    val serviceTag: String?,
    val isIp: Boolean,
    val isCdnSharded: Boolean,
)

object DomainClassifier {
    private val BRAND_MAP: Map<String, BrandEntity> by lazy {
        val map = HashMap<String, BrandEntity>(128)
        fun registerBrand(
            entityKey: String,
            displayName: String,
            primaryDomain: String,
            serviceTag: String?,
            vararg domains: String,
        ) {
            val entity = BrandEntity(entityKey, displayName, primaryDomain, serviceTag, isIp = false)
            for (domain in domains) {
                map[domain.lowercase()] = entity
            }
        }

        registerBrand(
            "bilibili", "哔哩哔哩", "bilibili.com", "视频 / 社区",
            "bilibili.com", "biliapi.com", "biliapi.net", "hdslb.com", "bilivideo.com", "bilivideo.cn"
        )
        registerBrand(
            "tencent", "腾讯服务", "qq.com", "社交 / 腾讯服务",
            "qq.com", "tencent.com", "gdtimg.com", "gtimg.com", "gtimg.cn", "qpic.cn", "qlogo.cn",
            "weixin.qq.com", "tenpay.com", "wechat.com", "foxmail.com"
        )
        registerBrand(
            "bytedance", "字节跳动", "douyin.com", "抖音 / 媒体 CDN",
            "douyin.com", "douyincdn.com", "byteoversea.com", "ibytedtos.com", "pstatp.com",
            "snssdk.com", "tiktok.com", "tiktokcdn.com", "toutiao.com"
        )
        registerBrand(
            "alibaba", "阿里服务", "taobao.com", "电商 / 云计算",
            "taobao.com", "tmall.com", "alipay.com", "alicdn.com", "aliyun.com", "tbcdn.cn", "alibaba.com"
        )
        registerBrand(
            "baidu", "百度", "baidu.com", "搜索 / 地图",
            "baidu.com", "bdimg.com", "baidupcs.com", "bcebos.com"
        )
        registerBrand(
            "google", "Google 服务", "google.com", "Google 核心",
            "google.com", "googleapis.com", "gstatic.com", "googleusercontent.com", "1e100.net",
            "gvt1.com", "gvt2.com", "ggpht.com", "android.com"
        )
        registerBrand(
            "youtube", "YouTube", "youtube.com", "流媒体视频",
            "youtube.com", "youtu.be", "ytimg.com", "googlevideo.com"
        )
        registerBrand(
            "apple", "Apple 服务", "apple.com", "Apple / iCloud",
            "apple.com", "icloud.com", "mzstatic.com", "aaplimg.com", "apple-dns.net", "itunes.com"
        )
        registerBrand(
            "microsoft", "Microsoft", "microsoft.com", "Windows / 办公",
            "microsoft.com", "office.com", "live.com", "azure.com", "bing.com",
            "msftconnecttest.com", "windows.com", "microsoftonline.com"
        )
        registerBrand(
            "telegram", "Telegram", "telegram.org", "即时通讯",
            "telegram.org", "t.me", "telegram.me", "telesco.pe"
        )
        registerBrand(
            "openai", "OpenAI / ChatGPT", "openai.com", "AI 大模型",
            "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com"
        )
        registerBrand(
            "twitter", "X (Twitter)", "x.com", "社交网络",
            "x.com", "twitter.com", "twimg.com", "t.co"
        )
        registerBrand(
            "meta", "Meta (Instagram/FB)", "instagram.com", "社交网络",
            "facebook.com", "fbcdn.net", "instagram.com", "cdninstagram.com",
            "whatsapp.com", "whatsapp.net", "messenger.com"
        )
        registerBrand(
            "github", "GitHub", "github.com", "代码托管",
            "github.com", "githubusercontent.com", "githubassets.com"
        )
        registerBrand(
            "cloudflare", "Cloudflare", "cloudflare.com", "网络加速 / CDN",
            "cloudflare.com", "cloudflare-dns.com"
        )
        registerBrand(
            "netflix", "Netflix", "netflix.com", "影视流媒体",
            "netflix.com", "nflxvideo.net", "nflximg.net", "nflxso.net"
        )
        registerBrand(
            "spotify", "Spotify", "spotify.com", "音乐流媒体",
            "spotify.com", "scdn.co", "spoti.fi"
        )
        registerBrand(
            "weibo", "微博", "weibo.com", "社交媒体",
            "weibo.com", "sinaimg.cn", "weibo.cn", "sina.com.cn"
        )
        registerBrand(
            "heytap", "欢太 (OPPO)", "heytapmobi.com", "系统生态",
            "heytapmobi.com", "oppo.com", "coloros.com"
        )
        registerBrand(
            "xiaomi", "小米服务", "xiaomi.com", "MIUI / 澎湃",
            "xiaomi.com", "mi.com", "miui.com", "xiaomi.net"
        )
        registerBrand(
            "vivo", "vivo 服务", "vivo.com", "OriginOS",
            "vivo.com", "vivo.com.cn"
        )
        registerBrand(
            "huawei", "华为服务", "huawei.com", "HarmonyOS",
            "huawei.com", "hicloud.com", "dbankcdn.com", "honor.com"
        )
        map
    }


    /** Metadata contains a host or host:port, never a URL. No DNS lookup occurs here. */
    fun parse(rawHostWithPort: String): NormalizedDomain {
        val host = extractHost(rawHostWithPort) ?: return unknown(rawHostWithPort)
        val literal = host.substringBefore('%')
        if (InetAddresses.isInetAddress(literal)) {
            val address = InetAddresses.forString(literal)
            val canonical = InetAddresses.toAddrString(address) +
                host.substringAfter('%', "").let { if (it.isEmpty()) "" else "%$it" }
            val bytes = address.address.map { it.toInt() and 0xff }
            val tag = when {
                address.isAnyLocalAddress -> "未指定地址"
                address.isLoopbackAddress -> "回环地址"
                address.isLinkLocalAddress -> "链路本地"
                address.isMulticastAddress -> "组播地址"
                address.isSiteLocalAddress ||
                    (bytes.size == 16 && bytes[0] and 0xfe == 0xfc) -> "私有地址"
                bytes.size == 4 && bytes[0] == 100 && bytes[1] in 64..127 -> "运营商共享地址"
                else -> "IP 地址"
            }
            // An IP destination does not imply DIRECT routing or a streaming service.
            val entity = BrandEntity("ip:$canonical", canonical, canonical, tag, isIp = true)
            return NormalizedDomain(rawHostWithPort, canonical, entity, tag, true, false)
        }
        // Never reinterpret a malformed IP literal as a domain.
        if (':' in host || '%' in host || host.all { it.isDigit() || it == '.' }) {
            return unknown(rawHostWithPort)
        }
        val ascii = try {
            IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        } catch (_: IllegalArgumentException) {
            return unknown(rawHostWithPort)
        }
        val domain = try {
            InternetDomainName.from(ascii)
        } catch (_: IllegalArgumentException) {
            return unknown(rawHostWithPort)
        }
        // The bundled PSL includes private suffixes, e.g. github.io and workers.dev.
        val root = if (domain.isUnderPublicSuffix) domain.topPrivateDomain().toString() else ascii
        var candidate = ascii
        var brand: BrandEntity? = null
        while (true) {
            brand = BRAND_MAP[candidate]
            if (brand != null || candidate == root || '.' !in candidate) break
            candidate = candidate.substringAfter('.')
        }
        val entity = brand ?: BrandEntity("domain:$root", root, root)
        val sharded = (ascii.endsWith(".googlevideo.com") && ascii.startsWith("rr") && "---" in ascii) ||
            (ascii.endsWith(".bilivideo.com") && ascii.startsWith("mcdn."))
        return NormalizedDomain(rawHostWithPort, root, entity, entity.serviceTag, false, sharded)
    }

    private fun extractHost(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() || it in "/\\?#@" }) return null
        val host = when {
            text.startsWith('[') -> {
                val end = text.indexOf(']')
                if (end <= 1) return null
                val rest = text.substring(end + 1)
                if (rest.isNotEmpty() && (!rest.startsWith(':') || !validPort(rest.drop(1)))) return null
                text.substring(1, end).takeIf { ':' in it } ?: return null
            }
            '[' in text || ']' in text -> return null
            text.count { it == ':' } == 1 -> {
                if (!validPort(text.substringAfter(':'))) return null
                text.substringBefore(':')
            }
            else -> text
        }.removeSuffix(".")
        if (host.isEmpty() || host.endsWith('.')) return null
        if ('%' in host) {
            val zone = host.substringAfter('%')
            if (':' !in host || zone.isEmpty() || !zone.all { it.isLetterOrDigit() || it in "_.-" }) return null
        }
        return host
    }

    private fun validPort(value: String): Boolean =
        value.isNotEmpty() && value.all { it in '0'..'9' } && value.toIntOrNull() in 1..65535

    private fun unknown(raw: String): NormalizedDomain {
        val entity = BrandEntity("category:unknown", "未知目标", "未知目标")
        return NormalizedDomain(raw, entity.primaryDomain, entity, null, false, false)
    }
}
