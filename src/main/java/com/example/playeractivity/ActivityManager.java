package com.example.playeractivity;

import com.example.playeractivity.DailyRecord.IpEntry;
import com.example.playeractivity.DailyRecord.PlayerEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    /**
     * 当前在线会话：玩家 UUID -> 本次上线时刻（epoch 秒）。
     *
     * <p>用来精确判断「某段时间里玩家是否真的在线」，以及跨越零点时
     * 该玩家究竟是「延续在线」还是「当天新上线」。仅在主线程读写，无需加锁。
     */
    private static final Map<String, Long> SESSION_START = new HashMap<>();

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
        // 记录本次会话起点，供跨零点时区分「延续在线」与「当天新上线」
        SESSION_START.put(uuid, Instant.now().getEpochSecond());
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
        String uuid = player.getUUID().toString();
        SESSION_START.remove(uuid);
        DailyRecord record = todayRecord();
        PlayerEntry entry = record.players.get(uuid);
        if (entry != null) {
            entry.lastSeen = LocalDateTime.now().format(TIME_FORMAT);
            record.dirty = true;
        }
    }

    /**
     * 按墙钟调用：为所有在线玩家累计 {@code seconds} 秒在线时长（由入口按真实时间节流，
     * 正常每次 1 秒，卡顿/休眠恢复时补偿累积值，单次上限见 MAX_CATCHUP_SECONDS）。
     *
     * <p>要计入的那段时间是「已经过去的区间」{@code [now - seconds, now]}。
     * 区间按<b>自然日边界</b>切分：每一段写入该段所属日期的文件，
     * 因此跨零点的补偿会精确拆分到两天——而不是像以前那样整段落到「当前」这一天。
     */
    public static void accrue(MinecraftServer server, long seconds) {
        if (seconds <= 0) {
            return;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime end = LocalDateTime.now();
        LocalDateTime cursor = end.minusSeconds(seconds);
        while (cursor.isBefore(end)) {
            LocalDate day = cursor.toLocalDate();
            LocalDateTime dayStart = day.atStartOfDay();
            LocalDateTime nextMidnight = dayStart.plusDays(1);
            LocalDateTime segEnd = nextMidnight.isBefore(end) ? nextMidnight : end;

            long segStartEpoch = cursor.atZone(zone).toEpochSecond();
            long segEndEpoch = segEnd.atZone(zone).toEpochSecond();
            long dayStartEpoch = dayStart.atZone(zone).toEpochSecond();
            // 本段覆盖的最后一秒：段区间是左闭右开，若段终点正好是零点，
            // 则本段最后一秒属于前一天（否则昨天的 lastSeen 会显示成 00:00:00）
            LocalDateTime seenAt = segEnd.toLocalDate().atStartOfDay().equals(segEnd)
                    ? segEnd.minusSeconds(1) : segEnd;
            String lastSeenTime = seenAt.format(TIME_FORMAT);

            DailyRecord record = recordFor(day.format(DATE_FORMAT));
            boolean touched = false;
            for (ServerPlayer player : players) {
                String uuid = player.getUUID().toString();
                Long sessionStart = SESSION_START.get(uuid);
                // 只累计「玩家确实在线」的那部分：会话开始晚于本段开头时从会话开始算
                long from = sessionStart == null ? segStartEpoch : Math.max(segStartEpoch, sessionStart);
                if (from >= segEndEpoch) {
                    continue;
                }
                boolean continuation = isCarriedOver(sessionStart, dayStartEpoch);
                PlayerEntry entry = entryFor(record, player, uuid, continuation, day, lastSeenTime);
                entry.onlineSeconds += segEndEpoch - from;
                entry.lastSeen = lastSeenTime;
                touched = true;
            }
            if (touched) {
                record.dirty = true;
            }
            cursor = segEnd;
        }
    }

    /**
     * 判断某玩家在 {@code day} 这一天是否属于「跨零点延续在线」（当天没有上线事件）。
     *
     * <p>依据本次会话起点：会话在当天 00:00:00 之前就已开始即为延续。
     * 会话信息缺失（模组没看到该玩家的进服事件）时按「非延续」处理，
     * 走 {@link #entryFor} 的兜底分支，避免把当天新上线的人误标成延续。
     */
    private static boolean isCarriedOver(Long sessionStart, long dayStartEpoch) {
        return sessionStart != null && sessionStart < dayStartEpoch;
    }

    /**
     * 取（或兜底创建）玩家在某条记录中的条目。
     *
     * @param continuation 该玩家是否属于「跨零点延续在线」：当天没有上线事件，
     *                     新条目首登记为 00:00:00、上线次数不虚增，并打上延续标记与继承 IP
     */
    private static PlayerEntry entryFor(DailyRecord record, ServerPlayer player, String uuid,
                                        boolean continuation, LocalDate day, String lastSeenTime) {
        PlayerEntry entry = record.players.get(uuid);
        if (entry != null) {
            return entry;
        }
        entry = new PlayerEntry();
        entry.uuid = uuid;
        entry.name = player.getGameProfile().name();
        if (continuation) {
            // 跨零点延续：玩家在零点前就已在线，本日并没有发生上线事件。
            // 若这里再计一次 joinCount / 用「现在」当首登，就会造出一条 IP 缺失、
            // 首登恰好卡在 00:00:00 的假记录，因此显式区分。
            LocalDate from = carriedOverDate(uuid, day);
            entry.joinCount = 0;
            entry.firstJoin = "00:00:00";
            entry.carriedOverFrom = from.format(DATE_FORMAT);
            String ip = currentIp(player);
            if (ip == null) {
                // 实时地址取不到时，退回上一份记录里该玩家最后使用的 IP
                ip = previousDayIp(from, uuid);
            }
            if (ip != null) {
                recordIp(entry, ip, "00:00:00");
            }
            LOGGER.info("{} 跨零点延续在线（IP: {}，自 {} 起）", entry.name,
                    ip == null ? "未取得" : ip, entry.carriedOverFrom);
        } else {
            // 兜底：例如模组在服务器运行途中才安装，玩家已在游戏里
            entry.joinCount = 1;
            entry.firstJoin = sessionTime(uuid, day, lastSeenTime);
        }
        record.players.put(uuid, entry);
        return entry;
    }

    /** 兜底条目的首登时间：优先用会话真实起点，取不到则退回本段结束时刻。 */
    private static String sessionTime(String uuid, LocalDate day, String fallback) {
        Long sessionStart = SESSION_START.get(uuid);
        if (sessionStart == null) {
            return fallback;
        }
        LocalDateTime started = LocalDateTime.ofInstant(Instant.ofEpochSecond(sessionStart),
                ZoneId.systemDefault());
        return started.toLocalDate().equals(day) ? started.format(TIME_FORMAT) : fallback;
    }

    /** 取玩家当前连接的对端 IP（无法识别时返回 null）。 */
    private static String currentIp(ServerPlayer player) {
        if (player == null) {
            return null;
        }
        ServerGamePacketListenerImpl connection = player.connection;
        return connection == null ? null : RemoteAddress.format(connection.getRemoteAddress());
    }

    /** 延续条目的起始日期：尽量用会话真实开始日，取不到时退化为前一天。 */
    private static LocalDate carriedOverDate(String uuid, LocalDate day) {
        Long sessionStart = SESSION_START.get(uuid);
        if (sessionStart != null) {
            return Instant.ofEpochSecond(sessionStart).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        return day.minusDays(1);
    }

    /** 从某天的记录里取该玩家最后使用的 IP（延续条目兜底用，读取走缓存）。 */
    private static String previousDayIp(LocalDate day, String uuid) {
        if (day == null) {
            return null;
        }
        DailyRecord previous = queryRecord(day.format(DATE_FORMAT)).orElse(null);
        if (previous == null) {
            return null;
        }
        PlayerEntry entry = previous.players.get(uuid);
        return entry == null ? null : entry.lastIp;
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
        return recordFor(LocalDate.now().format(DATE_FORMAT));
    }

    /**
     * 获取指定日期（yyyy-MM-dd）的记录：优先取缓存，其次读该日期文件，否则新建。
     *
     * <p>与 {@link #queryRecord(String)} 的区别是会为空记录创建条目，
     * 供写入路径（跨零点切分时会写「昨天」那一份）使用。
     */
    private static DailyRecord recordFor(String date) {
        DailyRecord record = CACHE.get(date);
        if (record != null) {
            return record;
        }
        record = load(date);
        if (record == null) {
            record = new DailyRecord();
            record.date = date;
            record.players = new LinkedHashMap<>();
        }
        putCache(date, record);
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
