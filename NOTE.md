# TODO

## 已完成：LlmProviderRegistry 缓存代际化（并发串代问题）

背景：`reload()` 分多步清空 `clientCache`、`chatModelCache`、`embeddingModelCache` 和 `cachedDefaultChatProviderId`，缺少整体管理，存在以下竞态：

- `clientCache` 先清、`chatModelCache` 后清。并发线程可能在两者之间从旧的 `chatModelCache` 取到旧 `ChatModel`，并把由它构建的 `ChatClient` 写入新一代的 `clientCache`，之后一直保留旧配置直到下次 `reload()`。
- `reload()` 置空默认 provider ID 后，旧线程仍可能把旧值写回缓存。
- `embeddingModelCache` 与其他缓存的清空同样不是原子的。

方案（代际快照）：

- [x] 新增私有静态内部类 `Generation`，持有 `clients`、`chatModels`、`embeddingModels` 和 `volatile defaultChatProviderId`
- [x] 字段改为 `AtomicReference<Generation>`，所有 `get*` 方法开头只读取一次快照
- [x] 快照引用显式传递给 `createChatClient`、`getChatModel`、`resolveDefaultChatProviderId` 等私有方法，禁止内部再次调用 `generation.get()`
- [x] `reload()` 改为 `generation.getAndSet(new Generation())`，并打印旧代的统计信息
- [x] 补充并发测试：多线程调用 `getChatClient` 的同时反复 `reload()`，验证同一 `ChatClient` 与其使用的 `ChatModel` 属于同一代
- [x] 运行 `./gradlew :app:test --no-daemon` 验证改动
