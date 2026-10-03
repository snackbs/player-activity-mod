package com.example.playeractivity;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * 把 Minecraft 连接的对端 {@link SocketAddress} 归一化为可直接记录的 IP 字符串。
 *
 * <p>归一化规则：
 * <ul>
 *   <li>IPv4 映射的 IPv6（{@code ::ffff:1.2.3.4}）还原为 IPv4（{@code 1.2.3.4}）；</li>
 *   <li>去掉 IPv6 的区域 ID（{@code fe80::1%eth0} → {@code fe80::1}）；</li>
 *   <li>不记录端口：客户端端口每次连接都会变，对识别玩家没有意义；</li>
 *   <li>非网络地址（单人/局域网的内置服务端使用 {@code LocalAddress}）返回 null，
 *       避免把无意义的占位符写进数据文件。</li>
 * </ul>
 *
 * <p>注意：经由 BungeeCord / Velocity 等代理连接时，后端服务端看到的是代理的地址；
 * 该场景需要代理侧转发（PROXY protocol / FabricProxy-Lite 等）才能拿到玩家真实 IP。
 */
public final class RemoteAddress {

    private RemoteAddress() {
    }

    /**
     * 归一化对端地址。
     *
     * @param address 连接的对端地址（可为 null）
     * @return IPv4/IPv6 字符串；无法识别时返回 null
     */
    public static String format(SocketAddress address) {
        if (address == null) {
            return null;
        }
        if (address instanceof InetSocketAddress inetSocket) {
            InetAddress inet = inetSocket.getAddress();
            if (inet == null) {
                // 未解析的地址：退化为创建时传入的主机字符串（可能是域名），不做 DNS 反向查询
                String host = inetSocket.getHostString();
                return host == null || host.isEmpty() ? null : host;
            }
            return format(inet);
        }
        return null;
    }

    /**
     * 归一化 {@link InetAddress}。
     *
     * @param address 地址（可为 null）
     * @return IPv4/IPv6 字符串（不含区域 ID）；无法识别时返回 null
     */
    public static String format(InetAddress address) {
        if (address == null) {
            return null;
        }
        String ip = address.getHostAddress();
        if (ip == null || ip.isEmpty()) {
            return null;
        }
        // 部分 JDK/系统组合会把 IPv4 映射地址输出成 ::ffff:1.2.3.4，这里统一还原为 IPv4
        if (ip.regionMatches(true, 0, "::ffff:", 0, 7) && ip.indexOf('.') > 0) {
            ip = ip.substring(7);
        }
        // 去掉 IPv6 区域 ID（如 fe80::1%eth0）
        int percent = ip.indexOf('%');
        if (percent > 0) {
            ip = ip.substring(0, percent);
        }
        return ip.isEmpty() ? null : ip;
    }
}
