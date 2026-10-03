# Player Activity Tracker（玩家每日在线统计）

适用于 **Minecraft Java Edition 26.1**（Tiny Takeover）的 Fabric 服务端模组。

自动记录**每天有哪些玩家上线**、**每名玩家当日的在线时长**以及**玩家进服时的来源 IP**，数据按天保存为 JSON 文件，并可在游戏内用 OP 指令查询。

## 功能特性

- 记录内容（按玩家、按天）：
  - 玩家名 + UUID（玩家改名后自动更新为最新名字）
  - 当日累计在线时长（精确到秒）
  - 当日上线次数、首次上线时间、最后在线时间
  - **来源 IP 地址**：玩家每次进服时记录其连接 IP（去端口、IPv4 映射地址还原为 IPv4、去掉 IPv6 区域后缀），同一玩家当天同一 IP 只记一条并累加上线次数
- 数据保存：`<服务端目录>/player-activity/yyyy-MM-dd.json`，每天一个文件（UTF-8、带缩进、可直接阅读或二次处理）
- 服务器**重启不丢数据**：启动后自动读取当天已有文件继续累计
- **跨零点自动拆分**：玩家跨天在线时，时长自动分别计入对应日期
- 防崩溃：先写临时文件再原子替换；运行期间每分钟自动落盘一次，关闭服务器时强制落盘（玩家进出服只标记脏数据、随该批次统一写入，不在主线程逐次写盘）
- 玩家进服时在服务端日志打印一行 `玩家名 进入游戏（IP: x.x.x.x，本日第 N 次上线）`，同时写入当天 JSON
- 隐私提示：IP 属于个人信息，数据文件保存在服务端本地，请自行确认是否告知玩家并注意文件访问权限（指令查看 IP 均需 OP）
- 纯服务端逻辑，**客户端无需安装**本模组（也支持单人/局域网联机的内置服务端）
- 性能开销极低：按**真实时间（墙钟）**节流累计，无玩家在线时零额外开销；日期列表带短缓存，指令补全不反复扫盘；无数据库、无外部依赖

## 游戏内指令

主指令 `/pactivity`，短别名 `/pa`。除 `me` 外均需要 OP（权限等级 ≥ 2）；控制台也可直接执行。

| 指令 | 权限 | 说明 |
|---|---|---|
| `/pactivity today [页码]` | OP | 查看今天的在线统计（按在线时长降序，每页 10 人） |
| `/pactivity date <yyyy-MM-dd> [页码]` | OP | 查看某一天的统计，输入日期时有历史日期补全 |
| `/pactivity player <玩家名> [天数]` | OP | 查看某玩家最近 N 天（默认 14，最大 365）的每日在线情况，也支持填 UUID |
| `/pactivity ip <玩家名> [天数]` | OP | 查看某玩家最近 N 天（默认 30，最大 3650）用过的 IP，按上线次数排序（最多显示 10 条） |
| `/pactivity whois <ip> [天数]` | OP | 反查某 IP 最近 N 天（默认 30）有哪些玩家用过，并显示各人的上线次数与首末时间 |
| `/pactivity list` | OP | 列出所有有记录的日期及当天上线人数 |
| `/pactivity me` | 所有玩家 | 查看自己今天的在线时长 |

示例：

```
/pactivity today
/pactivity date 2026-09-30 2
/pactivity player Steve 30
/pactivity ip Steve 7
/pactivity whois 203.0.113.7
/pactivity list
/pa me
```

## 安装（服务端）

1. 服务端为 **Minecraft 26.1 + Fabric Loader 0.19.3+**（`fabric.mod.json` 声明 `>=0.19.3`，更高版本如 0.19.4/0.19.5 同样兼容；26.1 要求 **Java 25** 运行环境）；
2. `mods` 文件夹内放入：
   - `fabric-api-0.155.3+26.1.2.jar`（Fabric API，26.1/26.1.1/26.1.2 通用）
   - `player-activity-1.1.0.jar`（本模组，见 `build/libs/` 或随附构建产物）
3. 重启服务器。首次有玩家上线后，服务端根目录会出现 `player-activity` 文件夹。

## 数据文件示例

`player-activity/2026-10-02.json`：

```json
{
  "date": "2026-10-02",
  "lastUpdated": "2026-10-02 21:30:01",
  "players": {
    "069a79f4-44e9-4726-a5be-fca90e38aaf5": {
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "name": "Notch",
      "joinCount": 3,
      "onlineSeconds": 5124,
      "firstJoin": "09:12:33",
      "lastSeen": "21:30:01",
      "lastIp": "203.0.113.7",
      "ips": [
        {
          "ip": "203.0.113.7",
          "joinCount": 2,
          "firstSeen": "09:12:33",
          "lastSeen": "19:40:12"
        },
        {
          "ip": "198.51.100.24",
          "joinCount": 1,
          "firstSeen": "21:30:01",
          "lastSeen": "21:30:01"
        }
      ]
    }
  }
}
```

> 旧版本留下的数据文件里没有 `lastIp` / `ips` 字段，模组会自动兼容（视为该玩家当天没有 IP 记录），无需手工修改。

## 从源码构建

要求 **JDK 25**（Minecraft 26.1 本身要求 Java 25）：

```
./gradlew build
```

产物在 `build/libs/player-activity-1.1.0.jar`（另附 `-sources.jar` 源码包）。

> 网络说明：`gradle/wrapper/gradle-wrapper.properties` 默认使用腾讯云镜像下载 Gradle 9.7.1（国内网络 `services.gradle.org` 常无法直连）。如果你的网络可以访问官方源，可将其改回：
> `distributionUrl=https\://services.gradle.org/distributions/gradle-9.7.1-bin.zip`

## 工程结构

```
player-activity-mod/
├── build.gradle / gradle.properties / settings.gradle   # Loom 1.18 + MC 26.1（26.1 起官方未混淆，默认 Mojang 映射，无需 yarn）
├── src/main/java/com/example/playeractivity/
│   ├── PlayerActivityMod.java    # 入口：注册事件与指令
│   ├── ActivityManager.java      # 核心：计时、按天落盘、IP 记录与查询
│   ├── ActivityCommands.java     # /pactivity 指令树
│   ├── RemoteAddress.java        # 从连接地址取出干净的 IP 文本
│   └── DailyRecord.java          # 每日记录数据结构（Gson 序列化）
└── src/main/resources/fabric.mod.json
```

## 注意事项

- **按天划分的依据是服务器系统时区**的本地日期；
- 在线时长按**真实时间（墙钟）**统计：TPS 下降/卡顿时长不会少计；系统休眠恢复或长时间单 tick 卡死后的补偿单次上限 60 秒，避免把挂起时间算成在线；
- 落盘粒度为 1 分钟：进程被强杀（非正常关闭）最多丢失最近 1 分钟内的进出服标记与累计时长，正常关闭会强制全部落盘；
- 记录以 UUID 为主键，玩家改名不影响历史累计；`/pactivity player` 按名字或 UUID 匹配（忽略大小写）；
- **IP 记录的两个前提**：① 只有真实网络连接才有 IP，单人存档/局域网内置服务端（本地地址）会记录为「未取得连接 IP」；② 若服务端前面挂着 BungeeCord / Velocity 等代理且未启用 HAProxy 协议转发，`getRemoteAddress()` 拿到的是**代理的地址**而不是玩家真实地址，这与绝大多数 Fabric 模组的取法一致；
- 同一 IP 背后可能有多个玩家（例如同一家庭/校园网出口），所以 `/pactivity whois` 只说明「这个 IP 近期有谁用」，不能当作身份证明；
- 数据文件里的 IP 时间用当日 `HH:mm:ss`，而 `/pactivity ip`、`/pactivity whois` 汇总时会自动补上日期，显示为 `yyyy-MM-dd HH:mm:ss`；
- 兼容版本声明为 `~26.1`（即 26.1 / 26.1.1 / 26.1.2）。若日后升级到 26.2+，需将 `gradle.properties` 中 `fabric_api_version` 换成对应版本，并把 `fabric.mod.json` 的 `minecraft` 依赖放宽（如 `">=26.1 <27"`）后重新构建；
- 本模组不需要 Mixin，仅使用 Fabric API 稳定事件，升级成本很低。

> DeepSeek V4.1 Flash AI生成
