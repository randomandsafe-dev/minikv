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
- [ ] LRU/LFU
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

## 启动服务

```bash
mvn package
java -jar target/minikv-0.1.0-SNAPSHOT.jar
```

默认监听 `127.0.0.1:6379`。可按顺序传入端口和监听地址：

```bash
java -jar target/minikv-0.1.0-SNAPSHOT.jar 6380 127.0.0.1
```

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
- 当前只实现上述 RESP2 命令子集，不支持 RESP3、内联命令、其他 SET 选项、EXPIRE 条件选项、认证、淘汰或持久化。

## 第三天的数据流

`TCP → RespFrameDecoder → RespCommandParser → CommandExecutor → StorageEngine → RespMessageEncoder → TCP`

`RespDecoder.decodeOne()` 返回一个完整 RESP 值及已消费字节数。
数据不足时抛出 `RespIncompleteException`，网络层保留输入等待后续数据；
格式错误时抛出 `RespProtocolException`，网络层终止该连接。

## 第四天：TTL 过期

存储条目同时保存值和可选的毫秒级到期时间，`当前时间 >= 到期时间` 即失效。
`GET`、`EXISTS`、`DEL`、`EXPIRE` 和 `TTL` 访问时清理过期条目，过期键按不存在处理；
`size()` 扫描清理并统计有效键，并发写入时统计结果为弱一致。
同一个键的过期检查与更新通过 `ConcurrentHashMap` 原子操作完成，值继续采用防御性复制。

- 普通 `SET` 覆盖值时清除原有 TTL；`SET ... EX` 同时替换值和期限。
- `EXPIRE` 可以重新设置期限，从调用时开始计时；不会复活已过期的键。
- `TTL` 将剩余毫秒四舍五入为秒，可能返回 `0`，不代表已经过期。
- 非法整数、秒数转换或到期时间溢出返回 `ERR`，保留原值及期限，连接仍可继续使用。
- 默认使用系统 UTC 时钟；测试通过 `InMemoryStorage(Clock)` 注入时钟。
- 当前采用惰性删除，没有后台定期清理；不再访问的过期条目会留在内存中，直到访问或调用 `size()`。

命令语义参考 Redis 官方文档：[EXPIRE](https://redis.io/docs/latest/commands/expire/)、
[TTL](https://redis.io/docs/latest/commands/ttl/)、[SET](https://redis.io/docs/latest/commands/set/)。

手动验收：在 `redis-cli` 中执行 `SET session token EX 2`，立即 `GET session` 应返回 `"token"`；
等待至少两秒后，`GET session` 返回 `(nil)`、`EXISTS session` 返回 `0`、`TTL session` 返回 `-2`。
