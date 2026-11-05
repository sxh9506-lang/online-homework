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

## CI 与启用发布前的设置

- `PR checks` 对 PR 与 `main` 提交运行；后端路径有变化时执行 Java 25 `mvn verify` 和容器镜像构建，前端路径有变化时执行依赖安装、lint、测试和生产构建。
- `Deploy test environment` 仅在 `main` 有新提交后运行，按路径变化触发 API/网页部署钩子，并通过完整健康 URL 检查部署结果。
- 在 GitHub 仓库 **Settings → Environments** 建立 `test` 环境；部署钩子放 Secret，健康地址放 Variable：

| 类型 | 名称 | 值示例 |
| --- | --- | --- |
| Secret | `TEST_API_DEPLOY_HOOK_URL` | API 测试服务部署钩子 |
| Secret | `TEST_WEB_DEPLOY_HOOK_URL` | 网页测试服务部署钩子 |
| Variable | `TEST_API_HEALTH_URL` | `https://<api-host>/actuator/health` |
| Variable | `TEST_WEB_HEALTH_URL` | `https://<web-host>/` |
| Secret | `DEMO_TEACHER_PASSWORD` | 仅隔离测试演示启用时必填；强随机值，不得复用生产密码 |
| Secret | `DEMO_STUDENT_PASSWORD` | 仅隔离测试演示启用时必填；强随机值，不得复用生产密码 |
| Variable | `DEMO_MODE` | 默认 `false`；经隔离环境核查后才可显式设为 `true` |
| Variable | `APP_ENV` | 演示测试环境设为 `test`；未设置时按 `production` 处理 |
| Variable | `CORS_ALLOWED_ORIGINS` | 逗号分隔的精确 HTTP(S) 前端来源；禁止通配符和路径 |

部署钩子、数据库密码、JWT 密钥、SMTP 凭据、对象存储密钥及演示账号凭据都必须是测试环境专用 Secret，不得放入普通变量。启用发布前还须完成数据库迁移验证、私有 bucket/versioning/CORS 验证、SMTP 验证、API CORS 来源确认、健康检查及备份/恢复策略评估。真实注册仍必须完成邮箱验证。

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
6. 目前没有测试环境服务或数据库；本工作项仅更新代码和文档，不创建任何云资源或付费服务。
