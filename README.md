# Minecraft-Websocket

通过 WebSocket 控制 Minecraft 玩家移动和交互的 Bukkit 插件。

在 Minecraft 服务器上启动一个 WebSocket 服务端，允许外部客户端连接后控制一个"假玩家"（Fake Player），实时获取玩家状态并下发操作指令。

## 工作原理

1. 插件在 Bukkit 服务端上启动一个 WebSocket 服务端（默认端口 `8080`）
2. 外部客户端通过 WebSocket 连接，发送 `login` 消息以生成指定用户名的假玩家
3. 每个游戏 tick（约 50ms），插件收集假玩家的完整状态（位置、血量、周围实体、区块数据、背包、聊天消息），经 Zstd 压缩后推送
4. 客户端在任意时刻可发送 `input` 消息来控制假玩家的移动视角和动作，或发送 `chat` 消息让假玩家发送聊天
5. 断开连接时，假玩家自动被踢出

## 配置

首次启动会在插件数据目录下生成 `config.json`：

```json
{
  "port": 8080
}
```

| 字段     | 类型    | 默认值    | 说明                |
|--------|-------|--------|-------------------|
| `port` | `Int` | `8080` | WebSocket 服务端监听端口 |

## WebSocket 协议

所有消息均为 JSON 文本，使用密封类型多态序列化（`@SerialName` 区分消息类型）。服务端推送的状态消息使用 **Zstd** 压缩后的二进制帧发送。

### 客户端 → 服务端

#### Login - 登录

生成一个假玩家并开始接收状态更新。

```json
{
  "type": "login",
  "name": "Steve"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"login"` | 消息类型标识 |
| `name` | `String` | 假玩家的用户名（必须对应一个已存在的离线玩家或在线玩家名） |

**行为：**
- 如果同一 WebSocket 连接已有假玩家，先关闭旧的再创建新的
- 假玩家出生在该用户名上次下线的位置；若无记录则出生在世界出生点
- 同一用户名只能有一个活跃的假玩家，重复登录会报错
- 登录成功后，服务端开始按 tick 推送 `Status` 二进制帧

#### Input - 输入控制

控制假玩家的移动、视角和动作。每条 input 消息会替换上一条（服务端持续使用最新值）。

```json
{
  "type": "input",
  "input": {
    "yaw": 90.0,
    "pitch": -30.0,
    "w": true,
    "a": false,
    "s": false,
    "d": false,
    "jump": true,
    "sneak": false,
    "sprint": true,
    "fly": false,
    "attack": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `yaw` | `Float` | `0.0` | 水平朝向角度（度） |
| `pitch` | `Float` | `0.0` | 垂直朝向角度（度） |
| `w` | `Boolean` | `false` | 向前移动 |
| `a` | `Boolean` | `false` | 向左平移 |
| `s` | `Boolean` | `false` | 向后移动 |
| `d` | `Boolean` | `false` | 向右平移 |
| `jump` | `Boolean` | `false` | 跳跃 |
| `sneak` | `Boolean` | `false` | 潜行（移动速度降为 30%） |
| `sprint` | `Boolean` | `false` | 疾跑（需同时 `w = true`） |
| `fly` | `Boolean` | `false` | 飞行（需要该玩家有飞行权限） |
| `attack` | `String?` | `null` | 攻击目标的实体 UUID（十六进制格式），攻击后自动重置为 `null` |

**攻击机制：**
- `attack` 指定目标的 UUID（十六进制字符串，如 `"550e8400e29b41d4a716446655440000"`）
- 仅在攻击距离内生效：创造模式 5 格，其他模式 3 格
- 攻击后 `attack` 在当前 tick 末尾自动重置为 `null`（不会连续攻击）

#### Chat - 发送聊天

让假玩家在服务端发送一条聊天消息。

```json
{
  "type": "chat",
  "message": "Hello World"
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `"chat"` | 消息类型标识 |
| `message` | `String` | 聊天消息内容 |

### 服务端 → 客户端

服务端每个 tick 推送一个 `Status` 二进制帧（Zstd 压缩的 JSON）。解压后格式如下：

```json
{
  "playerLocation": { "x": 100.5, "y": 64.0, "z": -200.5 },
  "playerHealth": 20.0,
  "playerFoodLevel": 20,
  "nearbyEntities": [...],
  "keepChunks": [...],
  "newChunks": [...],
  "updateBlocks": [...],
  "backpack": [...],
  "messages": ["<Steve> Hello"]
}
```

#### Status 字段详解

| 字段 | 类型 | 说明 |
|------|------|------|
| `playerLocation` | `Location` | 玩家当前坐标 |
| `playerHealth` | `Double` | 玩家当前血量（0.0 ~ 20.0） |
| `playerFoodLevel` | `Int` | 玩家当前饥饿值（0 ~ 20） |
| `nearbyEntities` | `List<EntityInfo>` | 半径 48 格内的实体列表 |
| `keepChunks` | `List<KeepChunkInfo>` | 仍在视野内、无需重发的已加载区块坐标 |
| `newChunks` | `List<NewChunkInfo>` | 新进入视野的区块（包含完整方块数据） |
| `updateBlocks` | `List<UpdateBlockInfo>` | 自上次 tick 以来发生变化的方块 |
| `backpack` | `List<ItemInfo>` | 玩家背包内所有物品 |
| `messages` | `List<String>` | 自上次 tick 以来接收到的聊天消息 |

#### Location

```json
{ "x": 100.5, "y": 64.0, "z": -200.5 }
```

#### EntityInfo

```json
{
  "id": "550e8400e29b41d4a716446655440000",
  "type": "minecraft:zombie",
  "name": null,
  "x": 105.0,
  "y": 64.0,
  "z": -198.0,
  "helmet": "minecraft:diamond_helmet",
  "chestplate": null,
  "leggings": null,
  "boots": null
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `String` | 实体 UUID（十六进制） |
| `type` | `String` | 实体类型（Minecraft 命名空间格式，如 `minecraft:zombie`） |
| `name` | `String?` | 实体自定义名称；玩家类型返回玩家名，其他返回自定义名或 `null` |
| `x`, `y`, `z` | `Double` | 实体坐标 |
| `helmet` | `String?` | 头盔物品类型，无则 `null` |
| `chestplate` | `String?` | 胸甲物品类型，无则 `null` |
| `leggings` | `String?` | 护腿物品类型，无则 `null` |
| `boots` | `String?` | 靴子物品类型，无则 `null` |

#### KeepChunkInfo

表示一个仍在视野内的已加载区块，客户端应保留其数据。

```json
{ "x": 6, "y": 4, "z": -13 }
```

坐标为区块坐标（世界坐标右移 4 位，即 `blockX >> 4`）。此区块的数据客户端已在之前收到过，无需重发。

#### NewChunkInfo

新进入视野的区块，包含完整的 4096 个方块数据。

```json
{
  "x": 6,
  "y": 4,
  "z": -13,
  "blocks": [
    { "type": "minecraft:stone", "passable": false },
    { "type": "minecraft:air", "passable": true },
    "...共 4096 个"
  ]
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `x`, `y`, `z` | `Int` | 区块坐标 |
| `blocks` | `List<BlockInfo>` | 4096 个方块数据，索引映射为 `x = i & 0xF, y = (i >> 4) & 0xF, z = (i >> 8) & 0xF` |

> **注意**：这里的区块坐标包含 `y` 轴（即三维区块），每个区块 16×16×16 = 4096 个方块，与 Minecraft 原版的二维区块（16×16×384）不同。

#### BlockInfo

```json
{ "type": "minecraft:stone", "passable": false }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 方块类型（命名空间格式） |
| `passable` | `Boolean` | 是否可通过（空气、草丛等为 `true`） |

#### UpdateBlockInfo

```json
{
  "x": 100,
  "y": 64,
  "z": -200,
  "block": { "type": "minecraft:air", "passable": true }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `x`, `y`, `z` | `Int` | 方块的世界绝对坐标 |
| `block` | `BlockInfo` | 更新后的方块信息 |

#### ItemInfo

```json
{ "type": "minecraft:diamond_sword", "amount": 1 }
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `String` | 物品类型（命名空间格式） |
| `amount` | `Int` | 堆叠数量 |

## 区块加载机制

假玩家周围以 **半径 3 个区块** 为视野范围加载区块，以 **半径 4 个区块** 为延迟卸载范围：

- **加载半径**：`LOAD_CHUNK_RADIUS = 3`（即玩家周围 7×7×7 个区块）
- **卸载半径**：`UNLOAD_CHUNK_RADIUS = 4`（超出加载半径但在卸载半径内的区块会保留）
- **实体可视半径**：`VIEW_ENTITY_RADIUS = 48`（3 × 16 格）

客户端侧的区块管理策略：
1. 首次出现的区块出现在 `newChunks` 中（含完整方块数据）
2. 已知且仍在视野内的区块仅出现在 `keepChunks` 中（客户端保留缓存）
3. 既不在 `keepChunks` 也不在 `newChunks` 中的区块表示已离开视野，客户端可释放
4. 方块变化通过 `updateBlocks` 增量更新

## 方块变化监听

插件监听以下 Bukkit 事件来追踪方块变化：

| 事件 | 说明 |
|------|------|
| `BlockBreakEvent` | 玩家破坏方块 |
| `BlockPlaceEvent` | 玩家放置方块 |
| `BlockPhysicsEvent` | 方块物理更新 |
| `EntityChangeBlockEvent` | 实体改变方块（如末影人搬方块） |
| `BlockFormEvent` | 方块形成（如冰雪形成） |
| `BlockFadeEvent` | 方块消退（如冰融化） |

所有事件均在 `MONITOR` 优先级且 `ignoreCancelled = true` 下监听。

## 假玩家实现

假玩家通过 NMS（Net Minecraft Server）直接创建 `EntityPlayer`，使用 `EmbeddedChannel` 作为假网络连接：

- 连接方向为 `SERVERBOUND`（模拟客户端发往服务端）
- 通过 `ChannelDuplexHandler` 拦截服务端发往假玩家的数据包，提取聊天消息后丢弃
- 支持拦截的数据包类型：`ClientboundSystemChatPacket`、`ClientboundPlayerChatPacket`、`ClientboundDisguisedChatPacket`
- 假玩家的 `PlayerConnection.tick()` 被空实现覆盖，避免服务端对假连接的超时检测

### 移动物理

每个 tick 调用 `EntityPlayer.aiStep()` 来执行标准的 Minecraft 移动物理（包括重力、碰撞、跳跃等）。移动后会触发 `PlayerMoveEvent`，允许其他插件取消假玩家的移动。

## 技术栈

| 组件 | 版本 / 说明 |
|------|-------------|
| Kotlin | 2.3.20 |
| TabooLib | 6.3.0 (Gradle 插件 2.0.36) |
| Java | 17 |
| Java-WebSocket | 1.6.0 |
| kotlinx-serialization-json | 1.11.0 |
| zstd-jni | 1.5.7-8 |
| 目标 Minecraft | 1.21.1 (NMS: `v1_21_R1`) |

## 构建

```bash
./gradlew build
```

构建产物位于 `build/libs/`，将 JAR 放入 Bukkit 服务端的 `plugins/` 目录即可。

## 许可证

[GNU Affero General Public License v3.0](LICENSE)
