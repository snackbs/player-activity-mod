package com.example.playeractivity;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口：注册各事件监听与指令。
 *
 * <p>全部逻辑都在服务端主线程执行（Fabric 事件与指令执行均满足），
 * 因此 {@link ActivityManager} 无需加锁。
 *
 * <p>计时基于真实时间（墙钟）而非 tick 计数：服务器降频/卡顿（TPS &lt; 20）时
 * 在线时长依然准确；每 tick 只做一次 {@code System.nanoTime()} 比较，
 * 没有玩家在线时几乎零额外开销。
 *
 * <p>玩家进服时会记录来源 IP（IPv4/IPv6，不含端口），随当日数据一起落盘；
 * IP 属于敏感信息，游戏内仅 OP 可见（{@code /pactivity ip}、{@code /pactivity whois}），
 * 同时也写入服务端日志一行，请按当地隐私法规使用与清理。
 */
public class PlayerActivityMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("player-activity");

    /** 自动落盘间隔（毫秒） */
    private static final long FLUSH_INTERVAL_MILLIS = 60_000L;

    /**
     * 单次在线时长补偿上限（秒）：正常运行时每秒累计一次用不到它；
     * 只在操作系统休眠恢复、服务器长时间单 tick 卡死后触发，
     * 避免把整段「离线/挂起」时间算进玩家在线时长。
     */
    private static final long MAX_CATCHUP_SECONDS = 60L;

    /** 上次按墙钟累计在线时长的时刻（纳秒） */
    private long lastAccrualNanos = System.nanoTime();

    /** 上次自动落盘的时刻（毫秒） */
    private long lastFlushMillis = System.currentTimeMillis();

    @Override
    public void onInitialize() {
        LOGGER.info("[Player Activity] 已加载，每日在线数据目录：{}", ActivityManager.DATA_DIR);

        // 玩家进入游戏（同时记录本次连接的来源 IP，见 RemoteAddress）
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                ActivityManager.onJoin(handler.getPlayer(), handler.getRemoteAddress()));

        // 玩家退出游戏
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                ActivityManager.onDisconnect(handler.getPlayer()));

        // 服务端 tick：按墙钟为在线玩家累计时长；按墙钟每分钟自动落盘一次
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long now = System.nanoTime();
            long elapsedSeconds = (now - lastAccrualNanos) / 1_000_000_000L;
            if (elapsedSeconds >= 1) {
                lastAccrualNanos += elapsedSeconds * 1_000_000_000L;
                ActivityManager.accrue(server, Math.min(elapsedSeconds, MAX_CATCHUP_SECONDS));
            }

            long millis = System.currentTimeMillis();
            if (millis - lastFlushMillis >= FLUSH_INTERVAL_MILLIS) {
                lastFlushMillis = millis;
                ActivityManager.saveDirty();
            }
        });

        // 服务器启动：确保数据目录存在（便于管理员直接看到 player-activity 文件夹）
        ServerLifecycleEvents.SERVER_STARTING.register(server -> ActivityManager.ensureDataDir());

        // 服务器关闭：保存全部未落盘数据
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ActivityManager.saveDirty());

        // 注册 /pactivity（别名 /pa）指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                ActivityCommands.register(dispatcher));
    }
}
