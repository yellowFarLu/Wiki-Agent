# 安全体系

> 信任边界详表：[operations/trust-boundary.md](operations/trust-boundary.md)
> 开关清单：[operations/feature-toggles.md](operations/feature-toggles.md)

## 1. 信任边界

- [TrustedHeaderFilter.java](../src/main/java/com/wikiagent/infrastructure/security/TrustedHeaderFilter.java)：校验内部调用头（`X-Internal-Secret`），透传并约束 `X-User-Id`，防止外部请求伪造内部身份；
- [RetrievalIdentityFilter.java](../src/main/java/com/wikiagent/infrastructure/security/RetrievalIdentityFilter.java)：将身份/业务域标签注入检索安全上下文，越权内容在 Milvus 标量过滤层即被排除；
- 边界模型与威胁分析见 [operations/trust-boundary.md](operations/trust-boundary.md)。

## 2. 输入安全网关

[GuardrailAdvisorChain.java](../src/main/java/com/wikiagent/infrastructure/gateway/GuardrailAdvisorChain.java) 按序执行检测器（统一 [GuardrailResult](../src/main/java/com/wikiagent/infrastructure/gateway/GuardrailResult.java)）：

| 检测器 | 说明 |
|---|---|
| [KeywordBlacklistDetector](../src/main/java/com/wikiagent/infrastructure/gateway/KeywordBlacklistDetector.java) | 关键词黑名单，快速拒绝 |
| [LlmJudgeDetector](../src/main/java/com/wikiagent/infrastructure/gateway/LlmJudgeDetector.java) | LLM 评判（qwen-flash，阈值 0.7） |
| [SystemPromptLeakDetector](../src/main/java/com/wikiagent/infrastructure/gateway/SystemPromptLeakDetector.java) | 系统提示词套取检测 |
| [PresidioPiiDetector](../src/main/java/com/wikiagent/infrastructure/gateway/PresidioPiiDetector.java) | Presidio sidecar PII 探测（可选） |
| [LakeraDetector](../src/main/java/com/wikiagent/infrastructure/gateway/LakeraDetector.java) / [AzurePromptShieldDetector](../src/main/java/com/wikiagent/infrastructure/gateway/AzurePromptShieldDetector.java) / [ProtectedMaterialDetector](../src/main/java/com/wikiagent/infrastructure/gateway/ProtectedMaterialDetector.java) / [ModerationDetector](../src/main/java/com/wikiagent/infrastructure/gateway/ModerationDetector.java) | 外部/云端检测能力，按配置启用 |

[SpotlightingDecorator.java](../src/main/java/com/wikiagent/infrastructure/security/SpotlightingDecorator.java) 对输入加 Spotlighting 对抗标记，降低注入指令被模型执行的概率。拦截结果以 SSE `blocked` 事件返回。

## 3. 输出安全网关

- [OutputGuardrailAdvisor.java](../src/main/java/com/wikiagent/infrastructure/security/OutputGuardrailAdvisor.java)：非流式路径对整段输出做内容审核与泄露检测；
- [StreamingOutputGuardrailSender.java](../src/main/java/com/wikiagent/infrastructure/security/StreamingOutputGuardrailSender.java)：流式路径逐 chunk 包裹、末端统一校验，违规不送达用户。

## 4. 规则之双（写操作审批）

[RuleOfTwoStateMachine.java](../src/main/java/com/wikiagent/infrastructure/security/RuleOfTwoStateMachine.java)：Agent 的写操作（如更新用户画像）必须经第二方（用户/审批方）确认才执行；状态机驱动，拒绝即终止，不允许 Agent 自行落库。

## 5. PII 最小化

- [PiiMinimizer.java](../src/main/java/com/wikiagent/infrastructure/security/PiiMinimizer.java) + [PiiMinimizingChatModelDecorator.java](../src/main/java/com/wikiagent/infrastructure/security/PiiMinimizingChatModelDecorator.java)：送外部模型前最小化/替换 PII，回答返回后还原；
- [PiiMinimizationConfiguration.java](../src/main/java/com/wikiagent/infrastructure/security/PiiMinimizationConfiguration.java)：按开关装配（Presidio sidecar 不可用时降级为本地规则）。

## 6. 审计

[GatewayAuditService.java](../src/main/java/com/wikiagent/application/gateway/GatewayAuditService.java) 将网关拦截/放行事件落库（V8 网关审计表），与 `audit_log`（V3）、traceId（V16）贯通。
