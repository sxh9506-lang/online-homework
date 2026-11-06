# 测试环境：Render-only 自动部署与临时账本

## 唯一部署路径

测试环境 API/Web 只由 Render 原生自动部署。已有服务应跟踪受保护的 `main` 分支，并配置 `autoDeployTrigger: checksPass`；GitHub Actions 只负责 CI，不负责触发部署。仓库中的 PR checks 在指向 `main` 的 PR 和 `main` 的每次 push 上运行。API include build filter 必须同时包含 `backend/**` 与仓库根目录 `.dockerignore`，因为 API 镜像构建使用仓库根目录作为 Docker context；Web include build filter 保持 `frontend/**`，当前 Web 项目、锁文件和配置都在 `frontend/` 下，且没有跨出该目录的源码导入或 package 依赖。

普通 docs-only commit 不匹配上述应用路径；根 `.dockerignore` 本身变化会触发 API rebuild，也由 CI 路径分类器视作 backend 变更，必须运行 Maven verify 和 Docker build。若未来增加其他共享根目录构建输入，须将相关路径加入受影响的 Render filter 与 CI 分类器。

每次 `push: main` 都会产生 `Release readiness` 检查，包括 docs-only/no-code 变更。`backend/**` 或根 `.dockerignore` 变更要求 Maven verify 与容器镜像构建所在 job 成功；前端变更要求 lint 与生产构建 job 成功（当前没有独立前端 test 脚本）；混合变更要求两者都成功。该检查依赖代码检查、路径检测和组件任务：变更组件的对应任务必须成功，若结果缺失、被跳过、取消或失败，release-ready 失败并阻止 Render 的 `checksPass` 部署；未变更组件允许跳过。文档类变更也会有一个明确成功的 release-ready 结果。workflow 的并发策略可能取消较旧 main SHA；取消不是成功，只有最新且完整通过门禁的 main SHA 才有资格部署。

`checksPass` 控制部署时机，不代替 Render build filters；若服务未配置 filters，docs-only main commit 仍可能导致 Render 构建服务。API filter 必须覆盖 `backend/**` 与 `.dockerignore`，Web filter 使用 `frontend/**`。本次 PR 只调整仓库代码、CI 门禁和文档；不修改 GitHub 或 Render 设置，不创建 Blueprint 或 API/Web 服务，也不发起部署。Ops 在启用前须核实已有服务跟踪正确仓库的受保护 `main`、Render 使用 `checksPass` 和上述 filters，且 main push 的 CI 门禁确实通过。若服务或设置尚未就绪，停止并另行评估，不在此 PR 中创建资源或更改设置。

## 受控首次 bootstrap 顺序（未来步骤；本次未执行）

1. 等最终候选 SHA 的必需 PR CI 与 QA 验收全部通过后，才由获授权人员合并 PR。合并后立即冻结 `main` 的后续合并，并记录当前 main tip 的完整 40 位 SHA。
2. 建服务前核对 GitHub Actions 只保留 CI、没有旧发布 hook/路线仍能触发部署；同时确认 Render 没有旧 hook 或自动发布配置会独立触发。确认现有免费 PostgreSQL 仍可用且未到期；否则停止，不创建付费替代资源。
3. 先从该 main tip 创建 API 服务并关联现有免费 PostgreSQL。API 的首次 build 本身就是 bootstrap；等待 Render 显示 Healthy/Live，再调用 `GET /internal/deployment-version`。它应返回 HTTP 200 和 `{"commit":"<main-tip-sha>"}`，其中 40 位 SHA 必须与冻结的 main tip 完全一致。
4. API 健康且 SHA 一致后，再从同一仓库、同一 main tip 创建 Web 服务。Web 的首次 build 也是 bootstrap；等待 Healthy/Live，并调用 `GET /api/deployment-version`，确认返回的 `commit` 是同一完整 SHA。
5. 两个首次 build 完成后，核对 API include build filters 同时包含 `backend/**` 与根 `.dockerignore`、Web filter 为 `frontend/**`，并为两服务配置 `autoDeployTrigger: checksPass`。不要添加 GitHub Actions 发布 job、hook 或凭据。
6. 只有两服务都 Healthy/Live、两个版本端点报告相同 SHA，且 filters、`checksPass` 和 main CI 门禁复核通过后，才解除 main 合并冻结。任何状态未知或版本/健康不匹配时保持冻结并调查，不盲目重建或重试。
7. 服务创建后的第一次正常 main 更新，检查 Render Events 以及该 SHA 的 GitHub check-runs/check suite。已观察到 Render GitHub App suite `114243869581`（SHA `598b2b8312439830996c498ef02a9b5a27698a45`）为 queued，关联 check-runs 数为 0；Ops 未观察到部署，其对 `checksPass` 的影响尚未明确。若首次更新被这个空 queued suite 阻塞或原因不明，暂停后续 main 更新并与 Ops/Render reconcile；不手动 retrigger、不盲目重试。

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
