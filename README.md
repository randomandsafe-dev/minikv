# MiniKV

MiniKV 是一个使用 Java 和 Netty 实现的轻量级内存键值数据库。

## 当前进度

- [x] 内存键值存储
- [x] SET、GET、DEL、EXISTS底层操作
- [x] 参数校验
- [x] 防御性复制
- [x] 单元测试
- [ ] RESP协议
- [ ] TCP服务器
- [ ] TTL过期
- [ ] LRU/LFU
- [ ] AOF持久化

## 环境

- Java 21
- Maven 3.9+

## 运行测试

```bash
mvn test