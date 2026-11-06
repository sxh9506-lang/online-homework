# 测试环境部署清单

本文描述测试环境的目标部署方式与尚未完成的前置项；它不表示服务或云资源已经创建。测试数据必须是可丢弃的虚构数据。不得把真实学生资料、账号密码、API 密钥、对象存储凭据、签名 URL 或真实附件提交到仓库、PR、日志或普通 CI 变量。

## 服务形态与构建

### API：Docker Web Service

API 监听平台注入的 `PORT`（默认 `8080`），部署目标应是 Docker Web Service，而不是静态站点。镜像使用多阶段构建：第一阶段使用含 Java 25 JDK 和 Maven 3.6.3+ 的构建镜像，在仓库根目录构建 `backend/pom.xml` 并运行 `mvn -B -f backend/pom.xml verify`；第二阶段使用 Java 25 JRE 运行镜像，只复制可执行 JAR，并以非 root 用户启动。

仓库根目录构建命令为 `docker build -f backend/Dockerfile -t online-homework-api .`。多阶段 Dockerfile 已加入 PR #8，PR checks 将验证镜像构建；在该 CI 检查通过前，部署方式仍视为未就绪。不要依赖开发机上遗留的 `backend/target` 构建产物。

- API 健康检查完整地址：`https://<api-host>/actuator/health`。端点仅暴露健康状态，不应改为公开详细配置。
- 必需运行配置：`DATABASE_URL`（Spring JDBC URL，格式为 `jdbc:postgresql://...`）、`DATABASE_USERNAME`、`DATABASE_PASSWORD`、`APP_JWT_SECRET_BASE64`（Base64 解码后至少 32 字节随机值）和 `PORT`（由平台注入）。`APP_PUBLIC_BASE_URL`、`SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM` 用于真实自助注册的邮箱验证投递；仅使用预置演示账号登录时不依赖 SMTP。
- 数据库连接池默认较小；若调整 `DB_POOL_SIZE`，须符合数据库连接数限制。
- 将密钥放入测试环境专用 Secret，不放入仓库、普通变量、部署说明或构建参数。测试与生产必须使用不同凭据和数据库。

### 网页：Next.js Node Service

Next.js 必须以 Node 服务运行，不能作为纯静态站点发布。服务根目录设为 `frontend`，构建命令为 `npm ci && npm run build`，启动命令为 `npm run start -- --hostname 0.0.0.0 --port $PORT`；运行环境须保留 Node 服务进程并监听平台提供的 `PORT`。

- 网页可访问性检查完整地址：`https://<web-host>/`。
- 前端当前默认 API 基址是同源 `/api/v1`。如果 API 与网页使用不同域名，须在**前端构建时**设置 `NEXT_PUBLIC_API_BASE_URL=https://<api-host>/api/v1`；此公开变量只能放 API 公共地址，不能放密钥。

### CORS 与域名

优先使用同源路由（网页域名下的 `/api/v1` 代理至 API），这样无需跨域。若前端直接请求独立 API 域名，API 必须只允许精确的网页来源 `https://<web-host>`，并支持带凭据请求；不能对带凭据的请求使用 `Access-Control-Allow-Origin: *`。

精确来源白名单已由 PR #8 实现：通过 `CORS_ALLOWED_ORIGINS` 配置逗号分隔的 HTTP(S) 来源，拒绝通配符和带路径来源；留空时默认拒绝跨域。代码已验证，但实际域名尚未确定/配置。对象存储的浏览器直传另有独立 CORS 配置，见下节。

## 私有附件存储

附件必须写入私有 S3-compatible bucket，禁止公开桶或永久公开对象链接。API 完成对象级授权后才签发短时单对象上传/下载 URL；上传 URL 有效期约 5 分钟、下载 URL 约 2 分钟。单文件上限为 20 MiB。部署前必须绑定独立测试 bucket 与专用凭据，并验证浏览器签名上传、服务端对象复核及下载。

API 环境变量：

- `S3_ENABLED=true`
- `S3_BUCKET`、`S3_REGION`
- `S3_ENDPOINT`（API 服务端使用的 S3 API 地址）
- `S3_PRESIGN_ENDPOINT`（签名 URL 中使用、浏览器可访问的 S3 API 地址；不得指向仅 API 容器可达的内网地址）
- `S3_ACCESS_KEY`、`S3_SECRET_KEY`（测试专用 Secret）
- 启用 bucket versioning，并设置 `S3_REQUIRE_VERSIONING=true`；存储服务还须支持签名 PUT 所需的 `If-None-Match: *` 条件写入。

若浏览器直接访问签名 URL，bucket CORS 只允许 `https://<web-host>`，并仅开放应用所需的 `PUT`、`GET` 方法及 `Content-Type`、`If-None-Match`、`x-amz-meta-upload-intent-id` 等请求头。不得把签名 URL 记入日志或分享；它是短时 bearer 凭据。所有存储密钥只能放测试环境 Secret。

## 数据库

短期演示可使用 Render 免费 PostgreSQL，但只允许放可丢弃的虚构数据，不保存真实学生资料或唯一副本。规格、到期时间和备份限制统一见 [API README](../backend/README.md)；本工作项只记录方案，不创建数据库、服务、工作区或其他云资源，也不注册新服务或升级付费档。

当前演示库 `cue-demo-postgres` 是 Render 免费 PostgreSQL，控制台确认到期时间为 **2026-12-06 01:49:50 UTC**（日期：**2026-12-06**）。它只用于临时幂等账本/去重记录，不是长期可靠存储；资源到期或重置会丢失账本记录，因此不保证长期 exactly-once。并发控制只能减少并行冲突，不能替代幂等账本。继续保持免费档，不因该限制创建或升级资源。

## CI 与 Render-only 自动部署

- `PR checks` 对指向 `main` 的 PR 和 `main` 上的每次 push 运行。`backend/**` 或根目录 `.dockerignore` 变更都执行 Java 25 Maven verify 与容器镜像构建；前端变更执行依赖安装、lint 和生产构建（目前没有独立前端 test 脚本）。
- `Release readiness` 在每次 `push: main` 都运行，包括 docs-only/no-code 改动。它检查代码检查与路径检测成功，并核对后端/前端是否按变更路径实际运行且成功；变更组件对应检查若缺失、skipped、cancelled 或失败，门禁失败。未变更组件可跳过，文档类变更仍会得到成功的门禁结果。新 main push 可能取消较旧 SHA 的运行；取消不算成功，只有最新且完整通过门禁的 main SHA 才有资格部署。
- 测试环境唯一部署路径是 Render 原生自动部署：已有 API/Web 服务应跟踪受保护的 `main`，并使用 `autoDeployTrigger: checksPass`。API include build filter 必须同时包含 `backend/**` 与仓库根目录 `.dockerignore`，因为 API 镜像构建使用仓库根目录作为 Docker context；Web include build filter 保持 `frontend/**`。
- 普通 docs-only commit 不匹配上述应用路径；`.dockerignore` 本身变化会触发 API rebuild，也由 CI 路径分类器视作 backend 变更，必须通过 Maven verify 与 Docker build。若未来增加其他共享根目录构建输入，须将相关路径加入受影响的 Render filter 和 CI 分类器。`checksPass` 控制部署时机，不代替 build filters；未配置 filters 时，Render 仍可能为 docs-only commit 构建服务。
- GitHub Actions 仅执行 CI，不含部署 workflow 或部署凭据。本次只更新代码与文档，不修改 GitHub 或 Render 设置、不创建 Blueprint 或服务、不调用部署。Ops 启用前须核实已有服务连接到本仓库的受保护 `main`、`checksPass` 与 build filters 已配置且 CI 门禁生效；服务或设置未就绪时不要在本次改动中创建资源。
- 应用运行时凭据（数据库、JWT、SMTP、私有存储及演示账号）只配置在隔离的 Render 服务环境中。GitHub Actions 仅保留测试所需的 CI secrets；不要为发布新增 GitHub deploy secrets 或环境配置。真实注册仍须完成邮箱验证。

## 受控首次 bootstrap（未来步骤；本次未执行）

1. 只有最终候选 SHA 的必需 CI 与 QA 检查都通过后，才由获授权人员合并 PR。合并后立即冻结 `main` 的后续合并，并记录当前 tip SHA。
2. 创建资源前确认仓库只保留 CI workflow、没有遗留的 GitHub/Render 发布 hook 或其他发布路线；确认现有免费 PostgreSQL 仍可用且未到期。发现旧入口或数据库不可用时停止，不创建替代付费资源。
3. 先在该 `main` tip 创建 API 服务，并关联现有免费 PostgreSQL。创建产生的第一次 API build 就是 bootstrap；等待 Render 显示 Healthy/Live，再请求 `GET /internal/deployment-version`，确认 HTTP 200 的 JSON `commit` 与冻结的 main tip SHA 完全一致。
4. API 健康且 SHA 一致后，才在同一仓库、同一 main tip 创建 Web 服务。Web 的第一次 build 同样就是 bootstrap；等待 Healthy/Live，并在 Render Dashboard → Web service → Deploys 记录中确认 deployed commit SHA 与冻结的 main tip 完全一致；不要依赖 Web API 版本路由。
5. 两个 bootstrap build 完成后，核实 API 使用 `backend/**` 与根 `.dockerignore` include filters、Web 使用 `frontend/**`，并核实两服务的 `autoDeployTrigger: checksPass`；不添加 GitHub Actions 发布 hook。
6. 只有 API/Web 都健康、API 版本端点与 Web Deploys 记录都对应同一个 main SHA，且 filters、`checksPass` 与 CI 门禁复核通过后，才解除 main 合并冻结。任一状态不明或 SHA/健康不匹配时继续冻结并排查，不盲目重建或重试。
7. 服务存在后的第一次正常 main 更新，应同时检查 Render Events 与该 SHA 的 GitHub check-runs/check suite。已观察到 Render GitHub App suite `114243869581`（SHA `598b2b8312439830996c498ef02a9b5a27698a45`）处于 queued、关联 check-runs 数为 0，Ops 未观察到部署；它对 `checksPass` 的影响尚未明确。若首次更新被这个空 queued suite 阻塞或原因不明，暂停后续 main 更新并与 Ops/Render reconcile；不要手动 retrigger 或盲目重试。

## Demo-only 测试账号

- `DEMO_MODE` 默认关闭；只允许在隔离的 `APP_ENV=test` 环境显式开启。仅预置教师和学生的虚构账号，邮箱固定使用保留的 `.invalid` 域名；不得把真实用户放入 demo seed。
- 教师、学生密码只能从测试环境 Secrets 注入，不提供通用默认密码。启动前检查缺失或过弱的 Secret；账号只保存密码编码器生成的单向哈希，不在 API 响应、日志、PR 或公开文档中写入密码值。
- `DEMO_MODE=true` 在生产环境或 `prod`/`production` Spring profile 下会在 WebServer 创建和数据库迁移前拒绝启动。预置账号无需 SMTP；自助注册仍可用，但新账号必须通过有效的一次性邮箱 token 验证后才能登录。
- **实现状态：**上述 seed、Secret 校验、生产启动拒绝、注册邮箱验证边界及 CORS 白名单已加入 PR #8 并通过本地测试；尚未部署或启用。启用前须确认隔离测试环境、`APP_ENV=test`、测试专用 Secrets 和非生产数据源。本次不创建 Render 或其他云资源。

## 当前部署阻塞项

1. 多阶段 `backend/Dockerfile` 与 PR CI 镜像构建已加入；需等待远端 CI 检查通过后才可视为验证完成。
2. 前端需配置为 Next.js Node Service，并确定 API/网页域名及同源代理或跨域方案。
3. 若跨域，白名单代码已实现；仍须选定实际前端域名并配置精确的 `CORS_ALLOWED_ORIGINS`。
4. 尚未绑定私有对象存储、versioning、存储端 CORS 及测试凭据。
5. 真实自助注册的邮箱验证投递需要测试 SMTP；只用预置演示账号登录时不依赖 SMTP。
6. 当前尚未创建测试用 API/网页服务；现有免费演示 PostgreSQL 仅用于临时幂等账本，并将于 2026-12-06 到期。本工作项仅更新代码和文档，不创建任何云资源或付费服务。
