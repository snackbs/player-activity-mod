# Player Activity Tracker 1.1.2

`/pactivity list` 的日期列表现在可以直接点击查询：每一天后面的「x 名玩家」是一段可点击文本，点一下即等于执行 `/pactivity date <该日期>`，直接跳到那一天的在线记录。

## 新增

- **`/pactivity list` 的「x 名玩家」可点击直达当天记录**
  日期列表里每一行的「x 名玩家」现在带上了点击事件与悬停提示：
  鼠标移上去显示「点击查看 yyyy-MM-dd 的玩家在线记录」，点击即执行 `/pactivity date yyyy-MM-dd`，
  不再需要手动输入日期。
- 日期列表末尾新增一行灰色提示，说明「x 名玩家」可以点击。

## 兼容性

- 仅调整 `/pactivity list` 的聊天栏输出，指令结构、OP 权限判定、命令形式、JSON 数据格式与 1.1.1 完全一致，
  旧数据文件无需迁移；
- 点击与悬停事件由服务端随消息下发，**原版客户端无需安装任何模组**即可识别。

## 升级

用 `player-activity-1.1.2.jar` 替换 `mods/` 里的旧版本即可，无需其他操作。

## 验证

- `gradlew build` 通过，产物 `player-activity-1.1.2.jar`（另附 `-sources.jar`）；
- 反编译构建产物确认 `ActivityCommands` 中确实调用了
  `ClickEvent$RunCommand.<init>(String)`、`Style.withClickEvent(ClickEvent)`、`HoverEvent$ShowText.<init>(Component)`；
- 点击命令形式与指令树一致：`/pactivity date <yyyy-MM-dd>` 的页码参数本身可省略（默认第 1 页），
  与既有的翻页提示走同一条命令路径，`yyyy-MM-dd` 也满足该参数 `StringArgumentType.word()` 的要求。

---

# Player Activity Tracker 1.1.1

修复玩家**一直在线跨过零点**时每日记录出错的问题：在线时长累计中断、IP 信息丢失、首登时间显示为 00:00:00。

## 修复

- **在线时长累计中断（跨零点归属错误）**
  `accrue()` 原先在卡顿发生之后才结算，并按「真实时钟」选择写入哪一天的文件——此刻时钟已过零点，
  于是跨零点的补偿时长整段落入新的一天，昨天那部分被记到今天（最多错记 60 秒）。
  现改为对已过去的区间 `[now-seconds, now]` 按**自然日边界**切分，每段写入该段所属日期的文件：
  例如 23:59:50～00:00:30 的 40 秒补偿，分别记为前一天 10 秒 + 当天 30 秒。
- **跨零点后记录不再丢失 IP**
  玩家跨零点在线时，新一天生成的条目现在通过 `player.connection.getRemoteAddress()` 继承当前连接 IP
  （取不到实时地址时回退上一份记录的 `lastIp`），`lastIp` 与 `ips` 不再缺失。
  此前该条目由兜底分支凭空创建，IP 信息全部为空。
- **首登时间与上线次数不再失真**
  跨零点延续条目的首登显式记为 `00:00:00`（该日确实从 0 点起在线），
  `joinCount` 不再虚增一次上线（当天并没有发生上线事件），`lastSeen` 为本日实际最后在线时刻。

## 新增

- `DailyRecord.PlayerEntry` 新增 `carriedOverFrom` 字段：记录该玩家开始延续在线的日期。
  纯追加式变更，旧数据文件读取时按 null（非延续）处理，无需迁移。
- 指令 `/pactivity today`、`/pactivity date`、`/pactivity player`、`/pactivity me`
  对延续条目显示「自 yyyy-MM-dd 延续在线」。
- 新增会话表 `SESSION_START`（进服记录、退服清除），精确区分
  「跨零点延续在线」与「零点后新上线」，避免把零点后正常进服的玩家误标成延续。

## 升级

用 `player-activity-1.1.1.jar` 替换 `mods/` 中的旧版本即可，数据格式向后兼容，无需任何迁移。

## 已知限制

- 会话状态保存在内存中：服务器进程恰好在零点前后重启时，该次跨零点延续不会被打上标记，
  当天条目会按普通兜底处理（首登取真实进服时间）。

## 验证

- `gradlew build` 通过，产物 `player-activity-1.1.1.jar`；
- 独立仿真覆盖 4 个场景（60 次逐秒累计跨零点、零点后新上线、休眠 60 秒补偿等），
  切分与延续判定均符合预期，零点后真实上线未被误标为延续；
- Gson 往返测试确认新字段正确持久化，且旧数据（无 `carriedOverFrom` / `ips`）读取不受影响。
