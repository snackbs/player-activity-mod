<!-- 本文件就是本次发布的更新日志正文（CI 的 body_path 直接取用整个文件）。
     发布新版本时，请用新版本的小节**整体替换**本文件，不要把新小节追加到旧内容前面；
     历史版本见 GitHub Releases。 -->

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
