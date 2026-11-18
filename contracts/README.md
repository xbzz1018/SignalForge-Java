# ETF 风险研究数据合同

`examples/risk-comparison.fixture.json` 是可提交的演示请求，`examples/risk-response.fixture.json` 是固定输入对应的参考结果。Java 集成测试使用两者验证指标、数据版本和结果发布。用 `scripts/start-backend.ps1 -Demo` 启用合成证券，无需行情账号或模型密钥。

风险比较支持两只不同的沪深 ETF，按日线收盘价计算区间收益率、年化波动率和最大回撤。`examples/etf-comparison.fixture.json` 是预留的持仓比较请求，当前返回 422。任务端点、幂等键、权限和结果字段见 [Java API](../docs/Java后端接入指南.md)。
