package com.nanobot.security.guard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络安全守卫 — SSRF 和内网访问防护
 * =====================================
 *
 * 参考 Nanobot (Python) 的 {@code validate_url_target()} 设计。
 *
 * 核心逻辑：
 * 1. 验证 URL 协议（仅允许 http/https）
 * 2. DNS 解析 hostname → IP
 * 3. 检查 IP 是否在 blockedRanges 中
 * 4. 如配置了 allowedDomains（非空），仅允许列表中的域名
 * 5. 不在 blockedRanges 中或在 whitelistRanges 中则放行
 *
 * 默认 blockedRanges（同 Nanobot）：
 * - RFC1918 私有地址: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16
 * - 回环地址: 127.0.0.0/8, ::1
 * - 链路本地: 169.254.0.0/16 (包含云元数据 169.254.169.254)
 * - 当前网络: 0.0.0.0/8
 * - CGNAT/Tailscale: 100.64.0.0/10
 * - IPv6 链路本地: fe80::/10
 *
 * 使用示例：
 * ```java
 * NetworkGuard guard = NetworkGuard.withDefaults();
 * guard.validateUrl("https://api.github.com"); // 放行
 * guard.validateUrl("http://169.254.169.254/latest/meta-data"); // 抛 SecurityException
 * ```
 */
public class NetworkGuard {

    private static final Logger logger = LoggerFactory.getLogger(NetworkGuard.class);

    /** 禁止的 IP 范围 */
    private final List<CIDRRange> blockedRanges;

    /** 白名单 IP 范围（优先于 blockedRanges） */
    private final List<CIDRRange> whitelistRanges;

    /** 允许的域名（非空时仅允许列表中的域名） */
    private final List<String> allowedDomains;

    /** 禁止的域名 */
    private final List<String> blockedDomains;

    /** 是否强制 HTTPS */
    private boolean enforceHttps;

    // ==================== 构造函数 ====================

    public NetworkGuard() {
        this.blockedRanges = new ArrayList<>();
        this.whitelistRanges = new ArrayList<>();
        this.allowedDomains = new ArrayList<>();
        this.blockedDomains = new ArrayList<>();
        this.enforceHttps = false;
    }

    /**
     * 创建带有默认内置 SSRF 防护的 NetworkGuard
     */
    public static NetworkGuard withDefaults() {
        NetworkGuard guard = new NetworkGuard();
        guard.addDefaultBlockedRanges();
        return guard;
    }

    // ==================== 配置方法 ====================

    public void setEnforceHttps(boolean enforce) {
        this.enforceHttps = enforce;
    }

    public void addBlockedCidr(String cidr) {
        blockedRanges.add(CIDRRange.parse(cidr));
        logger.debug("Added blocked CIDR: {}", cidr);
    }

    public void addWhitelistCidr(String cidr) {
        whitelistRanges.add(CIDRRange.parse(cidr));
        logger.debug("Added whitelist CIDR: {}", cidr);
    }

    public void addAllowedDomain(String domain) {
        allowedDomains.add(domain.toLowerCase());
    }

    public void addBlockedDomain(String domain) {
        blockedDomains.add(domain.toLowerCase());
    }

    /**
     * 加载内置默认黑名单 IP 范围（同 Nanobot validate_url_target）
     */
    private void addDefaultBlockedRanges() {
        addBlockedCidr("10.0.0.0/8");        // RFC1918 A类私有
        addBlockedCidr("172.16.0.0/12");     // RFC1918 B类私有
        addBlockedCidr("192.168.0.0/16");    // RFC1918 C类私有
        addBlockedCidr("127.0.0.0/8");       // 回环
        addBlockedCidr("169.254.0.0/16");    // 链路本地 + 云元数据
        addBlockedCidr("0.0.0.0/8");         // 当前网络
        addBlockedCidr("100.64.0.0/10");     // CGNAT / Tailscale
        addBlockedCidr("224.0.0.0/4");       // 多播
        addBlockedCidr("240.0.0.0/4");       // 保留
        addBlockedCidr("172.17.0.0/16");     // Docker 默认网桥
        // IPv6
        addBlockedCidr("::1/128");           // IPv6 回环
        addBlockedCidr("fe80::/10");         // IPv6 链路本地
        logger.info("Loaded {} default blocked IP ranges", blockedRanges.size());
    }

    // ==================== 核心方法 ====================

    /**
     * 验证 URL 是否安全
     *
     * @param urlString 要验证的 URL
     * @return 解析后的 URI（验证通过时）
     * @throws SecurityException 如果 URL 指向禁止的地址
     */
    public URI validateUrl(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            throw new SecurityException("NetworkGuard", "URL cannot be null or empty");
        }

        URI uri;
        try {
            uri = new URI(urlString);
        } catch (Exception e) {
            throw new SecurityException("NetworkGuard", "Invalid URL format: " + urlString, e);
        }

        // 协议检查
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new SecurityException("NetworkGuard", "URL must have a scheme (http/https): " + urlString);
        }
        scheme = scheme.toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SecurityException("NetworkGuard",
                    "Only http/https protocols are allowed, got: " + scheme);
        }
        if (enforceHttps && !scheme.equals("https")) {
            throw new SecurityException("NetworkGuard", "HTTPS is required, got: " + scheme);
        }

        // 域名检查
        String hostname = uri.getHost();
        if (hostname == null) {
            throw new SecurityException("NetworkGuard", "URL has no host: " + urlString);
        }
        hostname = hostname.toLowerCase();

        // allowedDomains 非空时仅允许列表中的域名
        if (!allowedDomains.isEmpty() && !allowedDomains.contains(hostname)) {
            throw new SecurityException("NetworkGuard",
                    "Domain not in allowed list: " + hostname);
        }

        // blockedDomains 检查
        if (blockedDomains.contains(hostname)) {
            throw new SecurityException("NetworkGuard",
                    "Domain is blocked: " + hostname);
        }

        // IP 检查
        try {
            InetAddress address = InetAddress.getByName(hostname);
            if (isBlocked(address)) {
                throw new SecurityException("NetworkGuard",
                        "IP address is blocked: " + address.getHostAddress()
                        + " (hostname: " + hostname + ")");
            }
        } catch (UnknownHostException e) {
            // 无法解析的域名 — 放行（DNS 解析失败不会造成 SSRF）
            logger.debug("Cannot resolve hostname {}: {}", hostname, e.getMessage());
        }

        logger.debug("URL validated: {}", urlString);
        return uri;
    }

    /**
     * 检查 IP 是否被拦截
     */
    public boolean isBlocked(InetAddress address) {
        byte[] addr = address.getAddress();

        // 先检查白名单
        for (CIDRRange range : whitelistRanges) {
            if (range.matches(addr)) {
                logger.debug("IP {} whitelisted by range {}", address.getHostAddress(), range);
                return false;
            }
        }

        // 再检查黑名单
        for (CIDRRange range : blockedRanges) {
            if (range.matches(addr)) {
                logger.warn("IP {} blocked by range {}", address.getHostAddress(), range);
                return true;
            }
        }

        return false;
    }

    // ==================== 查询方法 ====================
    // 注意：以下 getter 包含自定义转换逻辑（CIDRRange → String、防御性拷贝），
    // 无法用 Lombok @Getter 直接替代，因此保留为手动实现。

    public List<String> getBlockedCidrs() {
        List<String> result = new ArrayList<>();
        for (CIDRRange r : blockedRanges) result.add(r.toString());
        return result;
    }

    public List<String> getWhitelistCidrs() {
        List<String> result = new ArrayList<>();
        for (CIDRRange r : whitelistRanges) result.add(r.toString());
        return result;
    }

    public List<String> getAllowedDomains() {
        return List.copyOf(allowedDomains);
    }

    public List<String> getBlockedDomains() {
        return List.copyOf(blockedDomains);
    }

    // ==================== 内部类: CIDR 范围 ====================

    /**
     * CIDR 网络范围
     *
     * 支持 IPv4（如 "192.168.0.0/16"）和 IPv6（如 "fe80::/10"）。
     */
    public static class CIDRRange {
        private final byte[] network;
        private final byte[] mask;
        private final int prefixLength;
        private final String cidr;

        private CIDRRange(byte[] network, byte[] mask, int prefixLength, String cidr) {
            this.network = network;
            this.mask = mask;
            this.prefixLength = prefixLength;
            this.cidr = cidr;
        }

        /**
         * 解析 CIDR 表示法
         */
        public static CIDRRange parse(String cidr) {
            String[] parts = cidr.split("/");
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid CIDR format: " + cidr);
            }

            int prefixLength = Integer.parseInt(parts[1]);
            try {
                InetAddress netAddr = InetAddress.getByName(parts[0]);
                byte[] addr = netAddr.getAddress();
                int addrLen = addr.length * 8;

                if (prefixLength < 0 || prefixLength > addrLen) {
                    throw new IllegalArgumentException(
                            "Prefix length " + prefixLength + " out of range for " + cidr);
                }

                byte[] mask = new byte[addr.length];
                for (int i = 0; i < prefixLength; i++) {
                    mask[i / 8] |= (byte) (0x80 >> (i % 8));
                }

                // 归一化网络地址
                byte[] network = new byte[addr.length];
                for (int i = 0; i < addr.length; i++) {
                    network[i] = (byte) (addr[i] & mask[i]);
                }

                return new CIDRRange(network, mask, prefixLength, cidr);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid IP in CIDR: " + cidr, e);
            }
        }

        /**
         * 检查 IP 地址是否在此 CIDR 范围内
         */
        public boolean matches(byte[] address) {
            if (address.length != network.length) return false;
            for (int i = 0; i < address.length; i++) {
                if ((address[i] & mask[i]) != network[i]) return false;
            }
            return true;
        }

        public boolean matches(InetAddress address) {
            return matches(address.getAddress());
        }

        @Override
        public String toString() {
            return cidr;
        }
    }
}
