package com.example.playeractivity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一天的玩家在线记录，与 player-activity/yyyy-MM-dd.json 文件一一对应。
 */
public class DailyRecord {

    /** 日期，格式 yyyy-MM-dd */
    public String date;

    /** 本文件最后保存时间（yyyy-MM-dd HH:mm:ss） */
    public String lastUpdated;

    /** 玩家 UUID -> 该玩家当日数据 */
    public Map<String, PlayerEntry> players = new LinkedHashMap<>();

    /** 是否有未落盘的修改（transient：不写入 JSON） */
    public transient boolean dirty = false;

    /**
     * 单个玩家在一天内的在线数据。
     */
    public static class PlayerEntry {

        /** 玩家 UUID */
        public String uuid;

        /** 玩家名（最后已知名称，玩家改名后会自动更新） */
        public String name;

        /** 当日上线次数 */
        public int joinCount = 0;

        /** 当日累计在线秒数 */
        public long onlineSeconds = 0;

        /** 当日首次上线时间（HH:mm:ss） */
        public String firstJoin;

        /** 当日最后在线时间（HH:mm:ss） */
        public String lastSeen;

        /** 当日最近一次上线时使用的 IP（旧数据/内置服务端可能为 null） */
        public String lastIp;

        /** 当日使用过的 IP（按首次出现顺序，同一 IP 只有一条） */
        public List<IpEntry> ips = new ArrayList<>();
    }

    /**
     * 单个 IP 在一天内的使用情况（归属于某个玩家）。
     */
    public static class IpEntry {

        /** 连接来源 IP（IPv4 或 IPv6，不含端口；IPv4 映射地址已还原为 IPv4） */
        public String ip;

        /** 当日用该 IP 上线的次数 */
        public int joinCount = 0;

        /** 当日该 IP 首次出现时间（HH:mm:ss） */
        public String firstSeen;

        /** 当日该 IP 最后出现时间（HH:mm:ss） */
        public String lastSeen;
    }
}
