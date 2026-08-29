# ReasonWeave 公开架构边界

ReasonWeave 的公开仓库包含自托管 API、通用控制台和可验证的领域扩展合同。Kubernetes Pod Diagnostics 与 Cold Holding Excursion Diagnostics 都通过相同合同接入，不是核心引擎中的特殊分支。

```text
API client / Web console / external collector
                    │
                    ▼
         Versioned public contracts
 EventIR · Observation Bundle · OpenAPI · Domain Pack
                    │
                    ▼
      Domain-neutral reasoning engine
 validation · evidence · retrieval · rules · scoring
 immutable run · citation · graph · audit
                    │
          ┌─────────┴─────────┐
          ▼                   ▼
 PostgreSQL / pgvector   Local Blob store
          │
          ▼
 Local Ollama embedding provider
```

## 公开合同

- `contracts/eventir`：事件与调查对象的通用表示；Domain Pack Format 1 在第二层约束为单一主调查对象。
- `contracts/domain-pack`：事件 Schema、Predicate、规则、检索、知识、展示和许可证合同。
- `contracts/observation-bundle`：外部采集器向引擎提交结构化事实的协议。
- `contracts/openapi/reasonweave-v1.json`：API 客户端的固定合同和 TypeScript 类型唯一来源。
- 调查运行保存领域包指纹、知识索引版本、证据快照和 Citation，旧运行不可变。

## 核心与领域边界

核心只认识事件类型、主对象、Predicate、关系和分数，不认识 Kubernetes Namespace、Pod、冷藏阈值、设备编号或行业专有术语。领域包声明可接受的数据与显示方式，采集器作为独立进程生成标准 Bundle，第三方代码不会在后端进程中执行。

知识检索用于 grounding 和解释，不产生评分贡献。只有已确认的现实 Observation 能按版本化规则产生支持、反驳或缺失惩罚。

## 本地实例边界

当前版本使用一个内部实例作用域，以保持数据库约束一致；它不是公开 Workspace、用户或租户能力。API 不返回用户、邮箱、角色或 `workspace_id`，审计操作者只有 `local_api` 与 `system` 等真实执行语义。

根级 Compose 是公开部署入口：只有前端绑定回环地址，后端、数据库和 Ollama 留在内部网络。控制台经同源 `/api/v1` 访问后端。

## 不属于公开运行合同

- 特定部署环境的运维配置与操作流程；
- 托管服务、官网、遥测、运营后台和私有基础设施；
- 用户认证、组织、多租户、计费或企业策略；
- 任意第三方代码插件与自动修复执行器。

这些能力若未来出现，必须通过独立版本化合同接入，不能复制或绕过公开核心中的验证、快照和审计逻辑。

## 信任边界

- EventIR 先通过通用 Schema，再通过所选领域包事件 Schema。
- Bundle 必须与事件的领域包、事件类型和主对象身份完全匹配，整包事务提交。
- 领域包启动时校验文件 Hash、引用、路径、许可证和稳定指纹；同版本内容漂移会失败。
- Ollama 模型摘要从本地 API 回读并校验；OpenAI-compatible Provider 必须显式声明摘要。
- 领域包是规则与知识的审计输入，不构成官方认证或结果保证。
