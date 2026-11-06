# 测试环境：Render-only 自动部署与临时账本

## 唯一部署路径

测试环境 API/Web 只由 Render 原生自动部署。已有服务应跟踪受保护的 `main` 分支，并配置 `autoDeployTrigger: checksPass`；GitHub Actions 只负责 CI，不负责触发部署。仓库中的 PR checks 在指向 `main` 的 PR 和 `main` 的每次 push 上运行。建议 API 服务配置 include build filter `backend/**`，Web 服务配置 `frontend/**`；当前没有共享根目录构建文件，因此 docs-only commit 不会匹配应用代码路径。若以后增加共享根目录构建配置，必须将其路径同时加入两组 filters。

每次 `push: main` 都会产生 `Release readiness` 检查，包括 docs-only/no-code 变更。后端变更要求 Maven verify 与容器镜像构建所在 job 成功；前端变更要求 lint 与生产构建 job 成功（当前没有独立前端 test 脚本）；混合变更要求两者都成功。该检查依赖代码检查、路径检测和组件任务：变更组件的对应任务必须成功，若结果缺失、被跳过、取消或失败，release-ready 失败并阻止 Render 的 `checksPass` 部署；未变更组件允许跳过。文档类变更也会有一个明确成功的 release-ready 结果。workflow 的并发策略可能取消较旧 main SHA；取消不是成功，只有最新且完整通过门禁的 main SHA 才有资格部署。

`checksPass` 控制部署时机，不代替 Render build filters；若服务未配置 filters，docs-only main commit 仍可能导致 Render 构建服务。当前建议的 `backend/**`/`frontend/**` filters 可避免此情况。本次 PR 只调整仓库代码、CI 门禁和文档；不修改 GitHub 或 Render 设置，不创建 Blueprint 或 API/Web 服务，也不发起部署。Ops 在启用前须核实已有服务跟踪正确仓库的受保护 `main`、Render 使用 `checksPass` 和上述 filters，且 main push 的 CI 门禁确实通过。若服务或设置尚未就绪，停止并另行评估，不在此 PR 中创建资源或更改设置。

## 临时 PostgreSQL ledger 的边界

后端部署账本 API 保留独立的 repository allowlist：必须显式配置精确的 `owner/repository`；配置缺失或为空时，账本 API 返回 503 并拒绝操作。账本使用 `(repository, target environment, deployment SHA)` 唯一键；allowlist 和状态转移授权不构成 Render 部署触发器，Render 自动发布与该账本彼此独立。

当前 Render 免费 PostgreSQL `cue-demo-postgres` 的控制台到期时间为 **2026-12-06 01:49:50 UTC**，且无备份。它只适合临时 ledger/去重记录和可丢弃的虚构演示数据；资源到期或重置会丢失记录，因此不保证长期 exactly-once。不要存放真实学生资料或唯一副本，也不要因临时性限制升级付费档或创建额外资源。

账本状态机仍要求在外部调用前写入 `REQUESTING`，对结果不明的操作保留 `UNKNOWN` 并先人工核验，不能盲目重试。同一 SHA 的数据库唯一键可以限制重复 claim，但不能抵御账本数据库到期或重置造成的记录丢失。

## CI 与应用凭据

后端 Maven 测试、容器构建和前端 lint/build 属于 CI。可信同仓库 CI 可使用专门的演示测试密码 secrets；fork PR 不接收这些 secrets。部署所需的应用运行时凭据（数据库、JWT、SMTP、对象存储及演示账号）只配置在隔离的 Render 服务环境，不为 GitHub Actions 发布增加凭据或环境设置。

API 的 `DEPLOYMENT_LEDGER_WORKFLOW_TOKEN` 与独立的 `DEPLOYMENT_LEDGER_OPERATOR_TOKEN` 仅用于保护账本状态转移与人工 reconciliation；不要将 operator token 暴露给自动化调用方。账本当前不是 Render 发布流程的控制面。

## 启用前核对

- 确认 main 的 PR/branch protection 和 PR checks 已按维护策略生效；本 PR 不修改仓库规则。
- 由 Ops 核实已有 Render 服务关联正确仓库、跟踪受保护 `main`、使用 `autoDeployTrigger: checksPass`。
- 确认每次 main push 都有 `Release readiness` 结果，并且变更组件任务必须成功；任何 required 检查 skipped、cancelled 或失败都不得视作发布就绪。
- 确认演示数据全为虚构、可丢弃；明确数据库在 2026-12-06 到期，必要数据需提前导出或确认可丢弃。
- 本次不创建资源、不更改 Render 设置、不新增 GitHub deploy secrets、不部署。
