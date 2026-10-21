# MiniKV

MiniKV 是一个使用 Java 和 Netty 实现的轻量级内存键值数据库。

## 当前进度

- [x] 内存键值存储
- [x] SET、GET、DEL、EXISTS底层操作
- [x] 参数校验
- [x] 防御性复制
- [x] 单元测试
- [x] RESP2协议编解码
- [x] RESP命令解析
- [x] 命令执行器（PING、SET、GET、DEL、EXISTS）
- [x] Netty TCP服务器、半包与粘包处理
- [x] TCP集成测试、多客户端共享存储
- [x] TTL过期（惰性删除、EXPIRE、TTL、SET EX）
- [x] 容量限制与 LRU 淘汰（过期键优先回收、并发安全）
- [ ] LFU（扩展项）
- [ ] AOF持久化

## 环境

- Java 21
- Maven 3.9+

## 运行测试

```bash
mvn test
```

测试包括命令执行、每个字节边界的半包、流水线命令、错误恢复、真实 TCP
连接、多客户端并发，以及关闭服务后释放连接和端口。
TTL 测试使用可控制的时钟，覆盖到期瞬间、覆盖值、更新期限、参数错误、
时间溢出及真实 TCP 多客户端共享过期状态，无需等待真实时间流逝。
LRU 测试覆盖容量边界、访问顺序、覆盖写入、过期键优先回收、删除后容量复用、
非法写入不改变淘汰顺序、多线程容量约束，以及真实 TCP 多客户端共享淘汰状态。

## 启动服务

```bash
mvn package
java -jar target/minikv-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:6379`。可按顺序传入端口、监听地址和最大键数：

```bash
java -jar target/minikv-0.1.0-SNAPSHOT.jar 6380 127.0.0.1
java -jar target/minikv-0.1.0-SNAPSHOT.jar 6380 127.0.0.1 1000
```

`maxEntries` 必须是 `1` 到 `2147483647` 的整数；省略时使用 `Integer.MAX_VALUE`，
保持原有启动方式可用。容量按键数量计算，不限制值的总字节数；满容量时自动执行 LRU 淘汰。

按 `Ctrl+C` 关闭服务并释放连接、端口和 Netty 线程。

## 客户端验证

安装 `redis-cli` 后连接（使用默认 RESP2 模式）：

```bash
redis-cli -h 127.0.0.1 -p 6379
```

```text
127.0.0.1:6379> PING
PONG
127.0.0.1:6379> SET name MiniKV
OK
127.0.0.1:6379> GET name
"MiniKV"
127.0.0.1:6379> EXISTS name
(integer) 1
127.0.0.1:6379> DEL name
(integer) 1
127.0.0.1:6379> GET name
(nil)
```

## 支持的命令和边界

| 命令 | 返回值 |
| --- | --- |
| `PING [message]` | 无参数返回 `PONG`；有参数返回原始消息 |
| `SET key value` | `OK`，覆盖已有值 |
| `SET key value EX seconds` | `OK`，原子设置值及有效期；秒数必须为正整数 |
| `GET key` | Bulk String；不存在时返回空 Bulk String（`$-1`） |
| `DEL key [key ...]` | 实际删除的键数，重复键只删除一次 |
| `EXISTS key [key ...]` | 存在的键数，重复键逐次计数 |
| `EXPIRE key seconds` | 成功返回 `1`；键不存在或已过期返回 `0`；非正秒数立即删除 |
| `TTL key` | 剩余秒数；永久有效返回 `-1`；不存在或已过期返回 `-2` |

- 命令名大小写不敏感；键沿用存储层约束，必须是合法 UTF-8 且不能全为空白；值支持任意二进制内容和空字节串。
- 所有连接共享一个内存存储实例；数据在进程退出后丢失。
- 普通命令错误（参数数量、未知命令、无效键）返回 `ERR`，连接继续可用。
- 格式完整但不是命令数组的 RESP 值返回 `ERR`；协议格式损坏、嵌套过深或单帧超限时返回错误并关闭该连接。
- 单帧最大 16 MiB（含 RESP 开销）；支持连续发送多条命令及按序返回响应。
- 当前只实现上述 RESP2 命令子集，不支持 RESP3、内联命令、其他 SET 选项、EXPIRE 条件选项、认证、LFU 或持久化。

## 第三天的数据流

`TCP → RespFrameDecoder → RespCommandParser → CommandExecutor → StorageEngine → RespMessageEncoder → TCP`

`RespDecoder.decodeOne()` 返回一个完整 RESP 值及已消费字节数。
数据不足时抛出 `RespIncompleteException`，网络层保留输入等待后续数据；
格式错误时抛出 `RespProtocolException`，网络层终止该连接。

## 第四天：TTL 过期

存储条目同时保存值和可选的毫秒级到期时间，`当前时间 >= 到期时间` 即失效。
`GET`、`EXISTS`、`DEL`、`EXPIRE` 和 `TTL` 访问时清理过期条目，过期键按不存在处理；
`size()` 扫描清理并统计有效键。
第五天加入容量限制后，过期检查、读写、统计和淘汰统一在同一把锁内完成，值继续采用防御性复制。

- 普通 `SET` 覆盖值时清除原有 TTL；`SET ... EX` 同时替换值和期限。
- `EXPIRE` 可以重新设置期限，从调用时开始计时；不会复活已过期的键。
- `TTL` 将剩余毫秒四舍五入为秒，可能返回 `0`，不代表已经过期。
- 非法整数、秒数转换或到期时间溢出返回 `ERR`，保留原值及期限，连接仍可继续使用。
- 默认使用系统 UTC 时钟；测试通过 `InMemoryStorage(Clock)` 注入时钟。
- 当前没有后台定期清理；过期条目在访问、调用 `size()` 或满容量新增键时清理。

命令语义参考 Redis 官方文档：[EXPIRE](https://redis.io/docs/latest/commands/expire/)、
[TTL](https://redis.io/docs/latest/commands/ttl/)、[SET](https://redis.io/docs/latest/commands/set/)。

手动验收：在 `redis-cli` 中执行 `SET session token EX 2`，立即 `GET session` 应返回 `"token"`；
等待至少两秒后，`GET session` 返回 `(nil)`、`EXISTS session` 返回 `0`、`TTL session` 返回 `-2`。

## 第五天：容量限制与 LRU 淘汰

可通过 `InMemoryStorage(int maxEntries)` 设置最大键数，
或通过 `InMemoryStorage(Clock clock, int maxEntries)` 同时注入时钟和容量。
原有无参和仅传入时钟的构造方法使用 `Integer.MAX_VALUE`。

- 成功的 `GET`、`SET` 和 `SET ... EX` 将该键标记为最近使用；覆盖已有键不增加键数。
- `EXISTS`、`TTL`、`EXPIRE`、`size()` 和未命中的读取不改变 LRU 顺序。
- 新增键且容量已满时，先扫描删除所有过期条目；仍然没有空位时，淘汰最久未使用的有效键。
- 普通 `SET` 清除旧 TTL，`SET ... EX` 替换期限；这两种覆盖写入都会更新 LRU 顺序。
- 删除、过期和淘汰同时移除该键及其访问顺序记录。淘汰后的键与不存在的键语义相同，
  `GET` 返回空、`EXISTS` 返回 `0`、`TTL` 返回 `-2`，`EXPIRE` 不会复活它。
- 参数校验和到期时间计算在修改数据前完成，非法写入不会触发淘汰或改变 LRU 顺序。

实现使用 `LinkedHashMap` 同时保存值、到期时间和 LRU 顺序，避免两套索引失步。
所有存储操作使用同一把锁，保证并发情况下容量检查、淘汰和写入作为一个整体完成；
`size()` 返回锁内同一个时间点的有效键数。普通键查找和 LRU 顺序更新平均为 O(1)，
满容量新增键和 `size()` 的过期扫描为 O(n)，值的防御性复制耗时与字节数成正比。
这一版优先保证正确性，单锁及全量过期扫描会限制高并发吞吐；LFU 留作扩展。

手动验收：启动一个容量为 `2` 的新实例：

```bash
java -jar target/minikv-0.1.0-SNAPSHOT.jar 6380 127.0.0.1 2
redis-cli -h 127.0.0.1 -p 6380
```

在 `redis-cli` 中依次执行：

```text
SET a 1
SET b 2
GET a
SET c 3
GET b
EXISTS a c
TTL b
```

`GET a` 返回 `"1"`；插入 `c` 后，最久未使用的 `b` 被淘汰，
`GET b` 返回 `(nil)`、`EXISTS a c` 返回 `2`、`TTL b` 返回 `-2`。
