# TODO

## 优化：缓存默认 Provider ID（LlmProviderRegistry）

背景：调用 `getChatClientOrDefault(null)` 等默认 provider 场景时，每次都会先查询数据库（`globalSettingRepository.findById`）解析默认 ID，即使 `clientCache` 已命中。这是一次可以避免的主键查询。

- [ ] 在 `LlmProviderRegistry` 中新增 `volatile` 字段，缓存默认 chat / embedding provider ID，避免每次 `null` 调用都查库
- [ ] 在 `reload()` 中同步清空默认 provider ID 缓存，保证与全局设置修改一致
- [ ] 补充单元测试：修改全局默认 provider 并 `reload()` 后，解析结果随之更新；未 `reload()` 时不再访问数据库
- [ ] 运行 `./gradlew :app:test --no-daemon` 验证改动
