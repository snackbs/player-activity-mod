package com.example.playeractivity;

import com.example.playeractivity.DailyRecord.PlayerEntry;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * /pactivity（别名 /pa）指令。
 *
 * <ul>
 *   <li>/pactivity today [页码] —— 查看今天的在线统计（OP）</li>
 *   <li>/pactivity date &lt;yyyy-MM-dd&gt; [页码] —— 查看某一天的统计（OP）</li>
 *   <li>/pactivity player &lt;玩家名&gt; [天数] —— 查看玩家最近 N 天的在线情况（OP，默认 14 天）</li>
 *   <li>/pactivity ip &lt;玩家名&gt; [天数] —— 查看玩家最近 N 天用过的 IP（OP，默认 30 天）</li>
 *   <li>/pactivity whois &lt;IP&gt; [天数] —— 反查某 IP 被哪些玩家使用过（OP，默认 30 天）</li>
 *   <li>/pactivity list —— 列出所有有记录的日期（OP）</li>
 *   <li>/pactivity me —— 查看自己今天的在线时长（所有玩家可用）</li>
 * </ul>
 *
 * <p>IP 属于敏感信息：只有 OP 指令会输出 IP，普通玩家（含 /pactivity me）看不到。
 */
public final class ActivityCommands {

    /** 每页显示的玩家数 */
    private static final int PAGE_SIZE = 10;

    /** 玩家查询默认回溯天数 */
    private static final int DEFAULT_DAYS = 14;

    /** IP 查询默认回溯天数 */
    private static final int DEFAULT_IP_DAYS = 30;

    /** IP 查询最大回溯天数（约 10 年，等价于查询全部历史） */
    private static final int MAX_IP_DAYS = 3650;

    private ActivityCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root("pactivity"));
        dispatcher.register(root("pa"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> root(String name) {
        return Commands.literal(name)
                .then(Commands.literal("me")
                        .executes(ctx -> me(ctx.getSource())))
                .then(Commands.literal("today")
                        .requires(ActivityCommands::isOp)
                        .executes(ctx -> showDate(ctx.getSource(), LocalDate.now().toString(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> showDate(ctx.getSource(), LocalDate.now().toString(),
                                        IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("date")
                        .requires(ActivityCommands::isOp)
                        .then(Commands.argument("date", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(ActivityManager.availableDates(), builder))
                                .executes(ctx -> showDate(ctx.getSource(), StringArgumentType.getString(ctx, "date"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> showDate(ctx.getSource(), StringArgumentType.getString(ctx, "date"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("player")
                        .requires(ActivityCommands::isOp)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(playerSuggestions(ctx.getSource()), builder))
                                .executes(ctx -> showPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "name"), DEFAULT_DAYS))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                        .executes(ctx -> showPlayer(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "days"))))))
                .then(Commands.literal("ip")
                        .requires(ActivityCommands::isOp)
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(playerSuggestions(ctx.getSource()), builder))
                                .executes(ctx -> showPlayerIps(ctx.getSource(), StringArgumentType.getString(ctx, "name"), DEFAULT_IP_DAYS))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, MAX_IP_DAYS))
                                        .executes(ctx -> showPlayerIps(ctx.getSource(), StringArgumentType.getString(ctx, "name"),
                                                IntegerArgumentType.getInteger(ctx, "days"))))))
                .then(Commands.literal("whois")
                        .requires(ActivityCommands::isOp)
                        .then(Commands.argument("ip", StringArgumentType.word())
                                .suggests((ctx, builder) -> suggest(ActivityManager.todayIps(), builder))
                                .executes(ctx -> showIpUsers(ctx.getSource(), StringArgumentType.getString(ctx, "ip"), DEFAULT_IP_DAYS))
                                .then(Commands.argument("days", IntegerArgumentType.integer(1, MAX_IP_DAYS))
                                        .executes(ctx -> showIpUsers(ctx.getSource(), StringArgumentType.getString(ctx, "ip"),
                                                IntegerArgumentType.getInteger(ctx, "days"))))))
                .then(Commands.literal("list")
                        .requires(ActivityCommands::isOp)
                        .executes(ctx -> listDates(ctx.getSource())));
    }

    /** OP 判定：26.1 起改用新权限系统，COMMANDS_GAMEMASTER 等价于旧的「权限等级 ≥ 2」。 */
    private static boolean isOp(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /* ------------------------------ 子指令实现 ------------------------------ */

    /** /pactivity me —— 查看自己今天的在线时长（所有玩家可用）。 */
    private static int me(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("该指令只能由玩家执行，控制台请使用 /pactivity today"));
            return 0;
        }
        PlayerEntry entry = ActivityManager.queryRecord(LocalDate.now().toString())
                .map(record -> record.players.get(player.getUUID().toString()))
                .orElse(null);
        if (entry == null) {
            source.sendSuccess(() -> Component.literal("你今天还没有在线记录").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        MutableComponent text = Component.literal("你今天已在线 ").withStyle(ChatFormatting.AQUA)
                .append(Component.literal(ActivityManager.formatDuration(entry.onlineSeconds)).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("（上线 " + entry.joinCount + " 次"
                        + (entry.firstJoin != null ? "，首次上线 " + entry.firstJoin : "")
                        + (entry.carriedOverFrom != null ? "，自 " + entry.carriedOverFrom + " 延续在线" : "")
                        + "）").withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> text, false);
        return 1;
    }

    /** /pactivity today 或 /pactivity date —— 按天展示统计。 */
    private static int showDate(CommandSourceStack source, String dateStr, int page) {
        LocalDate date;
        try {
            date = LocalDate.parse(dateStr);
        } catch (DateTimeParseException e) {
            source.sendFailure(Component.literal("日期格式不正确，应为 yyyy-MM-dd，例如 2026-10-02"));
            return 0;
        }
        if (date.isAfter(LocalDate.now())) {
            source.sendFailure(Component.literal("不能查询未来的日期"));
            return 0;
        }
        DailyRecord record = ActivityManager.queryRecord(dateStr).orElse(null);
        if (record == null || record.players.isEmpty()) {
            source.sendSuccess(() -> Component.literal(dateStr + "：没有任何玩家上线记录").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }

        List<PlayerEntry> entries = new ArrayList<>(record.players.values());
        entries.sort(Comparator.comparingLong((PlayerEntry e) -> e.onlineSeconds).reversed());
        long totalSeconds = entries.stream().mapToLong(e -> e.onlineSeconds).sum();

        // 查询今天时，标记当前在线的玩家
        Set<String> online = new LinkedHashSet<>();
        if (dateStr.equals(LocalDate.now().toString())) {
            for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
                online.add(p.getGameProfile().name().toLowerCase(Locale.ROOT));
            }
        }

        int totalPages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        int currentPage = Math.min(Math.max(page, 1), totalPages);
        int from = (currentPage - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());

        source.sendSuccess(() -> Component.literal("===== 玩家在线统计 | " + dateStr + " =====").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("共 " + entries.size() + " 名玩家上线，合计在线 ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ActivityManager.formatDuration(totalSeconds)).withStyle(ChatFormatting.GREEN)), false);

        for (PlayerEntry entry : entries.subList(from, to)) {
            String marker = entry.name != null && online.contains(entry.name.toLowerCase(Locale.ROOT)) ? " ●在线" : "";
            String name = entry.name != null ? entry.name : String.valueOf(entry.uuid);
            StringBuilder detail = new StringBuilder();
            detail.append("（上线 ").append(entry.joinCount).append(" 次");
            if (entry.firstJoin != null) {
                detail.append("｜首登 ").append(entry.firstJoin);
            }
            if (entry.lastSeen != null) {
                detail.append("｜最后 ").append(entry.lastSeen);
            }
            detail.append(carryOverNote(entry));
            detail.append(ipSummary(entry));
            detail.append("）").append(marker);
            MutableComponent text = Component.literal(name).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("：" + ActivityManager.formatDuration(entry.onlineSeconds)).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(detail.toString()).withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> text, false);
        }

        if (totalPages > 1) {
            int next = currentPage < totalPages ? currentPage + 1 : 1;
            source.sendSuccess(() -> Component.literal("第 " + currentPage + "/" + totalPages + " 页｜输入 /pactivity date " + dateStr + " " + next + " 翻页").withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    /** /pactivity player —— 查看玩家最近 N 天的在线情况。 */
    private static int showPlayer(CommandSourceStack source, String name, int days) {
        List<ActivityManager.DayStat> stats = ActivityManager.queryPlayer(name, days);
        if (stats.isEmpty()) {
            source.sendSuccess(() -> Component.literal("最近 " + days + " 天内没有找到玩家 " + name + " 的在线记录").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        long totalSeconds = stats.stream().mapToLong(stat -> stat.entry().onlineSeconds).sum();
        long activeDays = stats.stream().filter(stat -> stat.entry().onlineSeconds > 0).count();

        PlayerEntry latest = stats.get(0).entry();
        String displayName = latest.name != null ? latest.name : name;
        boolean isOnline = source.getServer().getPlayerList().getPlayers().stream()
                .anyMatch(p -> (latest.uuid != null && p.getUUID().toString().equalsIgnoreCase(latest.uuid))
                        || p.getGameProfile().name().equalsIgnoreCase(name));

        source.sendSuccess(() -> Component.literal("===== 玩家 " + displayName + (isOnline ? "（当前在线）" : "") + " =====").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("最近 " + days + " 天：上线 " + activeDays + " 天，合计在线 ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ActivityManager.formatDuration(totalSeconds)).withStyle(ChatFormatting.GREEN)), false);

        for (ActivityManager.DayStat stat : stats) {
            PlayerEntry entry = stat.entry();
            MutableComponent text = Component.literal(stat.date() + "：").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(ActivityManager.formatDuration(entry.onlineSeconds)).withStyle(ChatFormatting.GREEN))
                    .append(Component.literal("（上线 " + entry.joinCount + " 次" + carryOverNote(entry)
                            + ipSummary(entry) + "）").withStyle(ChatFormatting.GRAY));
            source.sendSuccess(() -> text, false);
        }
        return 1;
    }

    /** /pactivity ip —— 查看某玩家最近 N 天用过的 IP（OP）。 */
    private static int showPlayerIps(CommandSourceStack source, String name, int days) {
        List<ActivityManager.IpStat> stats = ActivityManager.queryPlayerIps(name, days);
        if (stats.isEmpty()) {
            boolean hasActivity = !ActivityManager.queryPlayer(name, days).isEmpty();
            source.sendSuccess(() -> Component.literal(hasActivity
                            ? "最近 " + days + " 天内 " + name + " 有在线记录，但没有 IP 数据（属于模组升级前的历史数据）"
                            : "最近 " + days + " 天内没有找到玩家 " + name + " 的在线记录")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        long totalJoins = stats.stream().mapToLong(ActivityManager.IpStat::joinCount).sum();
        source.sendSuccess(() -> Component.literal("===== 玩家 " + name + " 的 IP 记录｜最近 " + days + " 天 =====").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("共 " + stats.size() + " 个 IP，累计上线 " + totalJoins + " 次").withStyle(ChatFormatting.GRAY), false);

        int shown = Math.min(stats.size(), PAGE_SIZE);
        for (ActivityManager.IpStat stat : stats.subList(0, shown)) {
            source.sendSuccess(() -> Component.literal(stat.ip()).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("：" + stat.joinCount() + " 次").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(between(stat.firstSeen(), stat.lastSeen())).withStyle(ChatFormatting.GRAY)), false);
        }
        if (stats.size() > shown) {
            source.sendSuccess(() -> Component.literal("（仅显示上线次数最多的 " + shown + " 个，共 " + stats.size() + " 个）").withStyle(ChatFormatting.DARK_GRAY), false);
        }
        return 1;
    }

    /** /pactivity whois —— 反查某个 IP 被哪些玩家使用过（OP）。 */
    private static int showIpUsers(CommandSourceStack source, String ip, int days) {
        List<ActivityManager.IpUserStat> stats = ActivityManager.queryIpUsers(ip, days);
        if (stats.isEmpty()) {
            source.sendSuccess(() -> Component.literal("最近 " + days + " 天内没有玩家使用过 IP " + ip).withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        long totalJoins = stats.stream().mapToLong(ActivityManager.IpUserStat::joinCount).sum();
        source.sendSuccess(() -> Component.literal("===== 使用过 IP " + ip + " 的玩家｜最近 " + days + " 天 =====").withStyle(ChatFormatting.AQUA), false);
        source.sendSuccess(() -> Component.literal("共 " + stats.size() + " 名玩家，累计上线 " + totalJoins + " 次").withStyle(ChatFormatting.GRAY), false);

        int shown = Math.min(stats.size(), PAGE_SIZE);
        for (ActivityManager.IpUserStat stat : stats.subList(0, shown)) {
            String displayName = stat.name() != null ? stat.name() : String.valueOf(stat.uuid());
            source.sendSuccess(() -> Component.literal(displayName).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal("：" + stat.joinCount() + " 次").withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(between(stat.firstSeen(), stat.lastSeen())).withStyle(ChatFormatting.GRAY)), false);
        }
        if (stats.size() > shown) {
            source.sendSuccess(() -> Component.literal("（仅显示上线次数最多的 " + shown + " 名，共 " + stats.size() + " 名）").withStyle(ChatFormatting.DARK_GRAY), false);
        }
        source.sendSuccess(() -> Component.literal("提示：同一 IP 背后可能是多人（家庭/宿舍/网吧/NAT），不能仅凭 IP 断定是同一人").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /** 当日条目的 IP 摘要：最近一次 IP（当日多于一个时附上个数）；无 IP 数据时返回空串。 */
    private static String ipSummary(PlayerEntry entry) {
        if (entry == null || entry.lastIp == null) {
            return "";
        }
        int count = ActivityManager.ipCount(entry);
        return "｜IP " + entry.lastIp + (count > 1 ? "（当日 " + count + " 个）" : "");
    }

    /**
     * 跨零点延续标记：玩家在当日 00:00:00 前就已在线、当天没有新的上线事件时返回说明文字，
     * 否则返回空串。旧数据没有该字段，按非延续处理。
     */
    private static String carryOverNote(PlayerEntry entry) {
        if (entry == null || entry.carriedOverFrom == null) {
            return "";
        }
        return "｜自 " + entry.carriedOverFrom + " 延续在线";
    }

    /** 把「首次～最后」时间戳拼成一段简短说明。 */
    private static String between(String firstSeen, String lastSeen) {
        if (firstSeen == null && lastSeen == null) {
            return "";
        }
        if (firstSeen == null || firstSeen.equals(lastSeen)) {
            return "（" + (lastSeen != null ? lastSeen : firstSeen) + "）";
        }
        return "（首次 " + firstSeen + "，最后 " + lastSeen + "）";
    }

    /** /pactivity list —— 列出所有有记录的日期。 */
    private static int listDates(CommandSourceStack source) {
        List<String> dates = ActivityManager.availableDates();
        if (dates.isEmpty()) {
            source.sendSuccess(() -> Component.literal("还没有任何记录（数据目录：" + ActivityManager.DATA_DIR + "）").withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        int shown = Math.min(dates.size(), PAGE_SIZE);
        source.sendSuccess(() -> Component.literal("共 " + dates.size() + " 天有记录，最近 " + shown + " 天：").withStyle(ChatFormatting.AQUA), false);
        for (String date : dates.subList(0, shown)) {
            int count = ActivityManager.queryRecord(date).map(record -> record.players.size()).orElse(0);
            // 「x 名玩家」可点击：直接在聊天栏里执行 /pactivity date <日期>，跳到该天的在线记录
            Style clickable = Style.EMPTY
                    .withColor(ChatFormatting.GREEN)
                    .withClickEvent(new ClickEvent.RunCommand("/pactivity date " + date))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("点击查看 " + date + " 的玩家在线记录").withStyle(ChatFormatting.YELLOW)));
            source.sendSuccess(() -> Component.literal(date + "：").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(count + " 名玩家").withStyle(clickable)), false);
        }
        source.sendSuccess(() -> Component.literal("提示：点击日期后面的「x 名玩家」即可查看当天的在线记录").withStyle(ChatFormatting.DARK_GRAY), false);
        return 1;
    }

    /* ------------------------------ 补全建议 ------------------------------ */

    /** 按已输入前缀过滤候选项（自行实现，不依赖游戏内部工具类，避免版本兼容问题）。 */
    private static CompletableFuture<Suggestions> suggest(Iterable<String> options, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        int count = 0;
        for (String option : options) {
            if (count >= 60) {
                break;
            }
            if (option.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(option);
                count++;
            }
        }
        return builder.buildFuture();
    }

    /** 玩家名建议：今天记录里的名字 + 当前在线玩家。 */
    private static Set<String> playerSuggestions(CommandSourceStack source) {
        Set<String> names = new LinkedHashSet<>();
        ActivityManager.queryRecord(LocalDate.now().toString()).ifPresent(record ->
                record.players.values().forEach(entry -> {
                    if (entry.name != null) {
                        names.add(entry.name);
                    }
                }));
        for (ServerPlayer p : source.getServer().getPlayerList().getPlayers()) {
            names.add(p.getGameProfile().name());
        }
        return names;
    }
}
