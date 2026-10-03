package com.example.playeractivity;

import com.example.playeractivity.DailyRecord.IpEntry;
import com.example.playeractivity.DailyRecord.PlayerEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * 在线数据核心：内存记录 + 按天 JSON 落盘 + 查询。
 *
 * <p>所有方法都在服务端主线程调用，无需加锁。
 * 文件写入采用「先写临时文件再原子替换」，避免服务器意外崩溃导致 JSON 损坏。
 */
public final class ActivityManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("player-activity");

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    public static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 数据目录：<服务端根目录>/player-activity/ */
    public static final Path DATA_DIR = FabricLoader.getInstance().getGameDir().resolve("player-activity");

    /** 内存缓存：日期 -> 当日记录（查询历史日期时也会缓存） */
    private static final Map<String, DailyRecord> CACHE = new HashMap<>();

    /** 缓存条目上限，超过后清理除今日以外的旧条目，避免长期运行占用内存 */
    private static final int CACHE_LIMIT = 64;

    /** 有记录日期列表缓存：指令补全/查询会频繁调用，避免每次按键都扫盘 */
    private static List<String> datesCache;

    /** 日期列表缓存的写入时刻（毫秒） */
    private static long datesCacheAtMillis;

    /** 日期列表缓存有效期（毫秒）：留一个短窗口以感知外部对数据目录的改动 */
    private static final long DATES_CACHE_TTL_MILLIS = 5_000L;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private ActivityManager() {
    }

    /* ------------------------------ 事件处理 ------------------------------ */

    /**
     * 玩家进入游戏：更新当日上线次数/首次上线时间，并记录本次连接的来源 IP。
     *
     * @param remoteAddress 连接的对端地址（{@code ServerGamePacketListenerImpl#getRemoteAddress()}），
     *                      经 {@link RemoteAddress#format(SocketAddress)} 归一化后写入数据文件；
     *                      无法识别（如内置服务端）时为 null，此时只记上线不记 IP
     */
    public static void onJoin(ServerPlayer player, SocketAddress remoteAddress) {
        DailyRecord record = todayRecord();
        String uuid = player.getUUID().toString();
        String time = LocalDateTime.now().format(TIME_FORMAT);
        PlayerEntry entry = record.players.computeIfAbsent(uuid, key -> new PlayerEntry());
        entry.uuid = uuid;
        entry.name = player.getGameProfile().name();
        entry.joinCount += 1;
        if (entry.firstJoin == null) {
            entry.firstJoin = time;
        }
        String ip = RemoteAddress.format(remoteAddress);
        if (ip != null) {
            recordIp(entry, ip, time);
            LOGGER.info("{} 进入游戏（IP: {}，本日第 {} 次上线）", entry.name, ip, entry.joinCount);
        } else {
            LOGGER.info("{} 进入游戏（本日第 {} 次上线，未取得连接 IP）", entry.name, entry.joinCount);
        }
        // 进服只标脏、不在此同步写盘：避免玩家集中进服时在主线程反复重写同一文件，
        // 由每分钟批量落盘 + 关服落盘兜底（最坏丢 1 分钟内的进出标记）
        record.dirty = true;
    }

    /** 把一次上线使用的 IP 记入玩家当日条目：同一 IP 只保留一条，累加次数并刷新最后使用时间。 */
    private static void recordIp(PlayerEntry entry, String ip, String time) {
        entry.lastIp = ip;
        for (IpEntry existing : ipsOf(entry)) {
            if (ip.equalsIgnoreCase(existing.ip)) {
                existing.joinCount += 1;
                existing.lastSeen = time;
                if (existing.firstSeen == null) {
                    existing.firstSeen = time;
                }
                return;
            }
        }
        IpEntry created = new IpEntry();
        created.ip = ip;
        created.joinCount = 1;
        created.firstSeen = time;
        created.lastSeen = time;
        ipsOf(entry).add(created);
    }

    /** 玩家退出游戏。 */
    public static void onDisconnect(ServerPlayer player) {
        DailyRecord record = todayRecord();
        PlayerEntry entry = record.players.get(player.getUUID().toString());
        if (entry != null) {
            entry.lastSeen = LocalDateTime.now().format(TIME_FORMAT);
            record.dirty = true;
        }
    }

    /**
     * 按墙钟调用：为所有在线玩家累计 {@code seconds} 秒在线时长（由入口按真实时间节流，
     * 正常每次 1 秒，卡顿/休眠恢复时补偿累积值，单次上限见 MAX_CATCHUP_SECONDS）。
     * 日期按服务器系统时间计算，跨零点的补偿会自动拆分到对应日期。
     */
    public static void accrue(MinecraftServer server, long seconds) {
        if (seconds <= 0) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String time = now.format(TIME_FORMAT);
        long remain = seconds;
        while (remain > 0) {
            // 跨零点拆分：一次补偿多秒时不会整段记入同一天
            long toMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).getSeconds();
            long chunk = Math.min(remain, Math.max(1L, toMidnight));
            DailyRecord record = todayRecord();
            for (ServerPlayer player : players) {
                PlayerEntry entry = entryFor(record, player, time);
                entry.onlineSeconds += chunk;
                entry.lastSeen = time;
            }
            record.dirty = true;
            remain -= chunk;
            if (remain > 0) {
                now = LocalDateTime.now();
                time = now.format(TIME_FORMAT);
            }
        }
    }

    /** 取（或兜底创建）玩家在某条记录中的条目。 */
    private static PlayerEntry entryFor(DailyRecord record, ServerPlayer player, String time) {
        String uuid = player.getUUID().toString();
        PlayerEntry entry = record.players.get(uuid);
        if (entry == null) {
            // 兜底：例如模组在服务器运行途中才安装，玩家已在游戏里
            entry = new PlayerEntry();
            entry.uuid = uuid;
            entry.name = player.getGameProfile().name();
            entry.joinCount = 1;
            entry.firstJoin = time;
            record.players.put(uuid, entry);
        }
        return entry;
    }

    /** 确保数据目录存在（服务器启动时调用）。 */
    public static void ensureDataDir() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            LOGGER.error("创建数据目录失败：{}", DATA_DIR, e);
        }
    }

    /** 把所有未保存的记录落盘（每分钟定期调用 + 服务器关闭时调用）。 */
    public static void saveDirty() {
        for (DailyRecord record : CACHE.values()) {
            if (record.dirty) {
                save(record);
            }
        }
    }

    /* ------------------------------ 查询接口 ------------------------------ */

    /** 查询某一天（yyyy-MM-dd）的记录；不存在时返回 empty，且不会创建新记录。 */
    public static Optional<DailyRecord> queryRecord(String date) {
        DailyRecord record = CACHE.get(date);
        if (record != null) {
            return Optional.of(record);
        }
        record = load(date);
        if (record != null) {
            putCache(date, record);
        }
        return Optional.ofNullable(record);
    }

    /** 某玩家最近 days 天（含今天）的每日在线情况，按日期从新到旧排列；按名字或 UUID 匹配（忽略大小写）。 */
    public static List<DayStat> queryPlayer(String nameOrUuid, int days) {
        List<DayStat> result = new ArrayList<>();
        forEachRecentRecord(days, (date, record) -> {
            for (PlayerEntry entry : record.players.values()) {
                if (matches(entry, nameOrUuid)) {
                    result.add(new DayStat(date, entry));
                    break;
                }
            }
        });
        return result;
    }

    /**
     * 某玩家最近 days 天（含今天）使用过的 IP，跨天聚合后按上线次数从多到少排列。
     *
     * <p>同一 IP 在多天出现时会合并成一条，{@code firstSeen}/{@code lastSeen} 为完整时间戳
     * （yyyy-MM-dd HH:mm:ss），便于管理员判断「哪个 IP 是谁在用」。
     */
    public static List<IpStat> queryPlayerIps(String nameOrUuid, int days) {
        Map<String, IpAccumulator> aggregated = new LinkedHashMap<>();
        forEachRecentRecord(days, (date, record) -> {
            for (PlayerEntry entry : record.players.values()) {
                if (!matches(entry, nameOrUuid)) {
                    continue;
                }
                for (IpEntry ipEntry : ipsOf(entry)) {
                    if (ipEntry.ip == null || ipEntry.ip.isEmpty()) {
                        continue;
                    }
                    // 大小写不同但等价的 IPv6 视为同一个 IP，聚合时以小写为键
                    IpAccumulator acc = aggregated.computeIfAbsent(
                            ipEntry.ip.toLowerCase(Locale.ROOT), key -> new IpAccumulator(ipEntry.ip));
                    acc.joinCount += Math.max(ipEntry.joinCount, 1);
                    acc.firstSeen = earliest(acc.firstSeen, stamp(date, ipEntry.firstSeen));
                    acc.lastSeen = latest(acc.lastSeen, stamp(date, ipEntry.lastSeen));
                }
                break; // 同一天内一个玩家只会匹配到一条记录
            }
        });
        List<IpStat> result = new ArrayList<>();
        for (IpAccumulator acc : aggregated.values()) {
            result.add(new IpStat(acc.ip, acc.joinCount, acc.firstSeen, acc.lastSeen));
        }
        result.sort(Comparator.comparingInt(IpStat::joinCount).reversed()
                .thenComparing(IpStat::lastSeen, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(IpStat::ip));
        return result;
    }

    /** 最近 days 天（含今天）使用过指定 IP 的玩家，按该 IP 的上线次数从多到少排列（跨天聚合）。 */
    public static List<IpUserStat> queryIpUsers(String ip, int days) {
        if (ip == null || ip.isEmpty()) {
            return List.of();
        }
        Map<String, PlayerIpAccumulator> aggregated = new LinkedHashMap<>();
        forEachRecentRecord(days, (date, record) -> {
            for (Map.Entry<String, PlayerEntry> mapEntry : record.players.entrySet()) {
                PlayerEntry entry = mapEntry.getValue();
                for (IpEntry ipEntry : ipsOf(entry)) {
                    if (ipEntry.ip == null || !ipEntry.ip.equalsIgnoreCase(ip)) {
                        continue;
                    }
                    String key = entry.uuid != null ? entry.uuid : mapEntry.getKey();
                    PlayerIpAccumulator acc = aggregated.computeIfAbsent(key, PlayerIpAccumulator::new);
                    if (entry.name != null) {
                        acc.name = entry.name;
                    }
                    acc.joinCount += Math.max(ipEntry.joinCount, 1);
                    acc.firstSeen = earliest(acc.firstSeen, stamp(date, ipEntry.firstSeen));
                    acc.lastSeen = latest(acc.lastSeen, stamp(date, ipEntry.lastSeen));
                }
            }
        });
        List<IpUserStat> result = new ArrayList<>();
        for (PlayerIpAccumulator acc : aggregated.values()) {
            result.add(new IpUserStat(acc.uuid, acc.name, acc.joinCount, acc.firstSeen, acc.lastSeen));
        }
        result.sort(Comparator.comparingInt(IpUserStat::joinCount).reversed()
                .thenComparing(IpUserStat::lastSeen, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(stat -> stat.name() == null ? "" : stat.name()));
        return result;
    }

    /** 今天记录里出现过的所有 IP（用于指令补全）。 */
    public static List<String> todayIps() {
        List<String> ips = new ArrayList<>();
        queryRecord(LocalDate.now().format(DATE_FORMAT)).ifPresent(record -> {
            for (PlayerEntry entry : record.players.values()) {
                for (IpEntry ipEntry : ipsOf(entry)) {
                    if (ipEntry.ip != null && !ips.contains(ipEntry.ip)) {
                        ips.add(ipEntry.ip);
                    }
                }
            }
        });
        return ips;
    }

    /** 某玩家当日记录里出现过的 IP 个数（兼容没有该字段的旧数据）。 */
    public static int ipCount(PlayerEntry entry) {
        return entry == null || entry.ips == null ? 0 : entry.ips.size();
    }

    /** 数据目录中所有有记录的日期，从新到旧（结果短 TTL 缓存，指令补全不会每次扫盘）。 */
    public static List<String> availableDates() {
        long nowMillis = System.currentTimeMillis();
        if (datesCache != null && nowMillis - datesCacheAtMillis < DATES_CACHE_TTL_MILLIS) {
            return datesCache;
        }
        List<String> dates = new ArrayList<>();
        if (Files.isDirectory(DATA_DIR)) {
            try (Stream<Path> stream = Files.list(DATA_DIR)) {
                stream.forEach(path -> {
                    String name = path.getFileName().toString();
                    // yyyy-MM-dd.json 共 15 个字符
                    if (name.length() == 15 && name.endsWith(".json")) {
                        dates.add(name.substring(0, 10));
                    }
                });
            } catch (IOException e) {
                LOGGER.error("扫描数据目录失败：{}", DATA_DIR, e);
            }
        }
        dates.sort(Comparator.reverseOrder());
        datesCache = List.copyOf(dates);
        datesCacheAtMillis = nowMillis;
        return datesCache;
    }

    /** 把秒数格式化为「X天X小时X分X秒」形式的中文时长。 */
    public static String formatDuration(long seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("天");
        }
        if (hours > 0) {
            sb.append(hours).append("小时");
        }
        if (minutes > 0) {
            sb.append(minutes).append("分");
        }
        if (sb.length() == 0 || secs > 0) {
            sb.append(secs).append("秒");
        }
        return sb.toString();
    }

    /** 玩家跨天查询结果：日期 + 该玩家当天的数据。 */
    public record DayStat(String date, PlayerEntry entry) {
    }

    /**
     * 某玩家使用某个 IP 的跨天统计。
     *
     * @param ip        归一化后的 IP（IPv4/IPv6，不含端口）
     * @param joinCount 使用该 IP 上线的总次数
     * @param firstSeen 首次出现时间（yyyy-MM-dd HH:mm:ss）
     * @param lastSeen  最后出现时间（yyyy-MM-dd HH:mm:ss）
     */
    public record IpStat(String ip, int joinCount, String firstSeen, String lastSeen) {
    }

    /** 某个 IP 的使用者统计（跨天聚合）。 */
    public record IpUserStat(String uuid, String name, int joinCount, String firstSeen, String lastSeen) {
    }

    /* ------------------------------ 内部实现 ------------------------------ */

    /**
     * 遍历最近 days 天（含今天）里真实存在的记录，按日期从新到旧。
     *
     * <p>与逐个日历日期去 stat 文件不同，这里只遍历数据目录里真实存在的日期（列表按 TTL 缓存）。
     */
    private static void forEachRecentRecord(int days, BiConsumer<String, DailyRecord> consumer) {
        LocalDate today = LocalDate.now();
        String todayStr = today.format(DATE_FORMAT);
        String oldest = today.minusDays(Math.max(days, 1) - 1L).format(DATE_FORMAT);
        for (String date : availableDates()) {
            if (date.compareTo(todayStr) > 0) {
                continue;
            }
            if (date.compareTo(oldest) < 0) {
                break;
            }
            DailyRecord record = queryRecord(date).orElse(null);
            if (record != null) {
                consumer.accept(date, record);
            }
        }
    }

    /** 取玩家的 IP 列表，兼容旧数据（JSON 里没有 ips 字段或显式为 null）。 */
    private static List<IpEntry> ipsOf(PlayerEntry entry) {
        if (entry.ips == null) {
            entry.ips = new ArrayList<>();
        }
        return entry.ips;
    }

    /** 把「日期 + HH:mm:ss」拼成完整时间戳。 */
    private static String stamp(String date, String time) {
        if (time == null) {
            return null;
        }
        return date == null ? time : date + " " + time;
    }

    /** 取两个时间戳中较早的一个（yyyy-MM-dd HH:mm:ss 可直接按字符串比较）。 */
    private static String earliest(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) <= 0 ? a : b;
    }

    /** 取两个时间戳中较晚的一个。 */
    private static String latest(String a, String b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) >= 0 ? a : b;
    }

    /** IP 跨天聚合的可变累加器。 */
    private static final class IpAccumulator {

        private final String ip;
        private int joinCount;
        private String firstSeen;
        private String lastSeen;

        private IpAccumulator(String ip) {
            this.ip = ip;
        }
    }

    /** IP 使用者跨天聚合的可变累加器。 */
    private static final class PlayerIpAccumulator {

        private final String uuid;
        private String name;
        private int joinCount;
        private String firstSeen;
        private String lastSeen;

        private PlayerIpAccumulator(String uuid) {
            this.uuid = uuid;
        }
    }

    private static boolean matches(PlayerEntry entry, String nameOrUuid) {
        if (entry.uuid != null && entry.uuid.equalsIgnoreCase(nameOrUuid)) {
            return true;
        }
        return entry.name != null && entry.name.equalsIgnoreCase(nameOrUuid);
    }

    /** 获取今天的记录：优先取缓存，其次读当天文件（保证服务器重启后数据连续），否则新建。 */
    private static DailyRecord todayRecord() {
        String today = LocalDate.now().format(DATE_FORMAT);
        DailyRecord record = CACHE.get(today);
        if (record != null) {
            return record;
        }
        record = load(today);
        if (record == null) {
            record = new DailyRecord();
            record.date = today;
            record.players = new LinkedHashMap<>();
        }
        putCache(today, record);
        return record;
    }

    private static void putCache(String date, DailyRecord record) {
        CACHE.put(date, record);
        if (CACHE.size() <= CACHE_LIMIT) {
            return;
        }
        // 按日期从旧到新确定性淘汰（跳过今天），避免 HashMap 迭代序带来的随机性
        String today = LocalDate.now().format(DATE_FORMAT);
        long excess = CACHE.size() - CACHE_LIMIT / 2;
        List<String> victims = CACHE.keySet().stream()
                .filter(key -> !key.equals(today))
                .sorted()
                .limit(excess)
                .toList();
        for (String key : victims) {
            DailyRecord evicted = CACHE.remove(key);
            if (evicted != null && evicted.dirty) {
                save(evicted);
            }
        }
    }

    private static Path fileFor(String date) {
        return DATA_DIR.resolve(date + ".json");
    }

    private static DailyRecord load(String date) {
        Path file = fileFor(date);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            DailyRecord record = GSON.fromJson(json, DailyRecord.class);
            if (record == null) {
                return null;
            }
            if (record.date == null) {
                record.date = date;
            }
            if (record.players == null) {
                record.players = new LinkedHashMap<>();
            }
            // 旧版本文件没有 ips 字段：Gson 会保留字段初始值，这里再兜底一次显式 null
            for (PlayerEntry entry : record.players.values()) {
                if (entry != null) {
                    ipsOf(entry);
                }
            }
            record.dirty = false;
            return record;
        } catch (Exception e) {
            LOGGER.error("读取在线记录文件失败：{}", file, e);
            return null;
        }
    }

    /** 保存到 player-activity/yyyy-MM-dd.json（先写临时文件，再原子替换）。 */
    public static void save(DailyRecord record) {
        try {
            Files.createDirectories(DATA_DIR);
            record.lastUpdated = LocalDateTime.now().format(DATETIME_FORMAT);
            Path file = fileFor(record.date);
            boolean newFile = !Files.isRegularFile(file);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(record), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            record.dirty = false;
            if (newFile) {
                // 新的一天（或补建的历史日期）落盘：立即让日期列表补全感知到
                datesCache = null;
            }
        } catch (IOException e) {
            LOGGER.error("保存在线记录文件失败：{}", fileFor(record.date), e);
        }
    }
}
