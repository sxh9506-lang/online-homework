# 测试环境部署幂等与 PR #8 Bootstrap

## 部署路由与唯一键

测试部署只由合并到 `main` 的 `pull_request_target: closed` 事件路由。PR #8 在读取文件列表、访问账本或调用 hook 前跳过；其他已合并 PR 按可信文件名元数据选择 API/Web，文件列表读取失败或不完整时安全回退到两端。工作流不 checkout、不执行 PR 分支代码，且不存在第二条 push 部署路由。merge、squash、rebase 都由同一个 PR closed 事件处理。

PR event 的 `merge_commit_sha` 仅表示触发事件所对应的合并结果，不是这里所说的部署 SHA。每个组件 job 开始执行时，通过 GitHub API 读取当时 `main` 的完整 40 位 commit SHA，并把它作为 **deployment SHA（部署目标 SHA）**：API/Web 各自用 `(repository_name, target_environment, deployment_sha)` 建立 PostgreSQL 唯一键。两个组件是两个独立环境键。

相同 deployment SHA 的并发 claim 只有一个能获得账本行并调用 hook；重复 PR close 事件、多个 PR close 事件在 job 执行时汇聚到同一 main tip、以及同一 SHA 的手动 rerun 都复用该键，不会再次调用同一组件 hook。main 前进到新 SHA 后，新 SHA 是独立键，API/Web 可各自部署一次。每个 hook 通过 `ref=<deployment SHA>` 指向该精确 commit，不是分支名 `main`。若 main 在 SHA 读取后继续前进，本次仍只请求刚才固定的 SHA；后续合并事件再读取新 tip 并按新键处理。

API 与 Web 各用独立的 concurrency group，设置 `queue: max`，默认不取消正在运行的部署，也不以并发组代替持久 ledger。GitHub 文档规定最多保留 100 个 pending job；队列满时后续任务会取消，且队列顺序按开始等待的时间、并不保证等同于事件分发顺序。因此应监控被取消/排队的 workflow，队列满或状态不明时先核实 main tip 与部署历史，不要手工盲目重发。

## Render 精确 ref 的格式与限制

Render 官方示例 URL 为：

```text
https://api.render.com/deploy/srv-XXYYZZ?key=AABBCC&ref=baaa339926cb474b61c1f0e6297b024eaa09ac7d
```

其中 `srv-XXYYZZ`、`AABBCC` 和 commit SHA 是 Render 文档示例/占位值，不是本项目凭据。本工作流不在文件中存真实 hook URL，而是对受保护的 Secret URL 使用 `curl --get --data-urlencode "ref=$DEPLOYMENT_SHA"`，由 Render 收到完整 deployment SHA。官方说明：指定 commit 后会部署该 commit，而不是 linked branch 的最新 commit；用 ref 指定 commit 会关闭该服务的 Auto-Deploy。部署前必须确认当前测试服务已存在、连接到本仓库的 `main`，并确认首次 PR #8 merge transition 前没有其他 Auto-Deploy 路径会抢先部署。

Hook URL 是秘密，不得写入日志、PR、文档或工件。Render 文档说明 HTTP 200 表示请求已开始并返回 deploy ID；HTTP 202 表示已有部署在运行、按 workspace 的 `Wait` 策略排队，但不返回 deploy ID。工作流仅把 HTTP 200 当作明确接受的新请求；202、超时及其他不确定结果进入 `UNKNOWN`，不自动重试，须由操作人员核对 Render 部署历史后人工 reconciliation。部署成功门槛同时要求 health endpoint 可用、runtime version endpoint 返回完整小写 SHA，且与 hook 的固定目标 SHA 完全一致：API 使用 `/internal/deployment-version`，Web 使用 `/api/deployment-version`。版本 URL 从已配置 health URL 的 origin 派生，不需新增 GitHub Variable；缺少 `RENDER_GIT_COMMIT` 或响应错配都会继续轮询，超时后记录 `UNKNOWN`。

来源：[Render Deploy Hooks](https://render.com/docs/deploy-hooks)、[Render deploys / specific commit `ref`](https://render.com/docs/deploys)、[GitHub Actions job concurrency and `queue: max`](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/control-the-concurrency-of-workflows-and-jobs)。

## 一次性 bootstrap 与服务状态

ledger API 和账本表由同一个后端服务提供。PR #8 的自动 closed event 必须跳过；只有最终代码 head、CI、QA、Ops、分支保护、免费测试服务和部署路径全部通过验证后，才可按明确授权，对 main 上 PR #8 对应的合并 commit 做一次人工测试环境 bootstrap。该例外不会改变 PR #8 自动事件跳过规则。启动时 Flyway 执行 V2 migration；之后才可由其他已合并 PR 工作流调用内部账本 API。bootstrap 后 API 未健康或账本 endpoint 未配置时，停止后续动作。

测试环境只能使用 Ops 已准备并确认免费的资源，不得在验收前新建 API/Web 服务、创建资源或调用部署 hook，也不得升级付费 plan。已核实的免费 PostgreSQL 为短期资源，当前元数据标注约 30 天到期、无备份；到期前导出需要保留的虚构数据，或确认可丢弃。Render 服务清单尚需 Ops 核实 API/Web 服务存在、Auto-Deploy 状态、linked branch、hook 与 health URL 配置。

## 必需配置（只列名称，不写值）

API 测试服务使用 `DEPLOYMENT_LEDGER_WORKFLOW_TOKEN` 接受 CI 自动 claim/状态转移，另用独立 `DEPLOYMENT_LEDGER_OPERATOR_TOKEN` 保护人工 `safe-failure`/`reconcile`；两项服务端变量留空时各自 fail-closed。Operator token 只配置在 API 服务，绝不注入 GitHub Actions。仓库 allowlist 固定为 `sxh9506-lang/online-homework`。GitHub Actions `test` environment 使用 Variable `TEST_DEPLOYMENT_LEDGER_API_URL`、Secret `TEST_DEPLOYMENT_LEDGER_TOKEN`（只映射 workflow token），以及现有 API/Web deploy hook Secrets 与 health URL Variables。工作流会在发送 hook 前检查 ledger、hook、health URL 均为无内嵌凭据的 HTTPS 地址；版本 URL 从各自 health URL origin 自动派生，不需 Ops 创建新变量。缺任何自动部署配置时，对应 job 必须在发送 hook 前失败。测试日志扫描、报告清理和零工件策略继续生效；CI 使用 fake hook，不连真实部署服务。

## 账本状态与重试

`CLAIMED` 以数据库唯一约束原子占用 repository/environment/deployment SHA。发送外部请求前先写 `REQUESTING`；明确收到 HTTP 200 后写 `REQUESTED`，health endpoint 响应后写 `SUCCEEDED`。hook 超时、返回 202 或健康检查超时写 `UNKNOWN`；未核对 Render 历史前不能自动重发。仅在明确确认请求未到达服务方后，人工 reconciliation 为 `NOT_DEPLOYED`，才允许对同一个 SHA 再 claim。账本不可用、认证失败或响应不完整时 workflow fail-closed。

## QA 与发布门槛

- [ ] 所有 QA/Ops 对同一个远端 PR #8 head SHA 核验，不混用本地未推送状态。
- [ ] QA 复验 Demo P0 五项、真实 DB rollback、Secret 注入/无泄漏、prod fail-closed，以及 ledger 并发、duplicate PR close、multiple PR events 同 tip、manual rerun、新 main SHA、API/Web 分键。
- [ ] fake-hook 测试确认请求参数包含完整固定 deployment SHA `ref`，不传 `main` 分支；HTTP 202/网络不确定结果不得重发。
- [ ] fake-curl 测试确认 API/Web 只有在 health 可用且各自 version endpoint 返回目标 SHA 时才成功；旧 SHA、`unknown`、无效响应和 health 失败均不得标成功。
- [ ] `S3_ENABLED=false` API 测试覆盖 intent create、PENDING complete 回滚且不生成可见版本/附件关系、作业与学生提交两种下载均稳定返回503；已成功 complete 的幂等重放返回原结果且不创建新版本。未来启用对象存储前需使用 HTTPS 私有 bucket、开启 bucket versioning 且 `S3_REQUIRE_VERSIONING=true`；缺失/显式关闭或对象无 `versionId` 均须 fail-closed。
- [ ] workflow token 不能使用 `safe-failure`/`reconcile`，operator token 仅由 Ops 用于人工处置，并且不进入 Actions job。
- [ ] QA 继续核实 PR #8 删除/替换旧 `push: main` workflow 的 merge transition 是否可能仍触发旧 workflow；官方材料未能证明时作为 blocker，不 merge、不部署。
- [ ] 仓库只读确认 `main` 必须由 PR 更新且管理员不可绕过；required approvals/status checks 的实际状态另行准确报告，不擅自新增分支规则。
- [ ] Ops 核验免费 API/Web/PostgreSQL 是否已存在、Auto-Deploy/linked branch、hook/health URL、账本 token/URL；资源/设置未就绪时不创建、不部署。
- [ ] 所有门槛通过后，按一次性授权做 PR #8 bootstrap，核对 V2 migration、ledger API、Render 部署记录中的目标 SHA 与服务 health；PR #8 自动 closed event 仍跳过。

所有当前测试与 mock 不执行真实 deploy hook。
