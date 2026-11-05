# 测试环境部署清单

本文描述测试环境的目标部署方式与尚未完成的前置项；它不表示服务或云资源已经创建。测试数据必须是可丢弃的虚构数据。不得把真实学生资料、账号密码、API 密钥、对象存储凭据、签名 URL 或真实附件提交到仓库、PR、日志或普通 CI 变量。

## 服务形态与构建

### API：Docker Web Service

API 监听平台注入的 `PORT`（默认 `8080`），部署目标应是 Docker Web Service，而不是静态站点。镜像应采用多阶段构建：第一阶段使用含 Java 25 JDK 和 Maven 3.6.3+ 的构建镜像，在仓库根目录构建 `backend/pom.xml` 并运行 `mvn -B -f backend/pom.xml verify`；第二阶段使用 Java 25 JRE 运行镜像，只复制可执行 JAR，并以非 root 用户启动。

建议的仓库根目录构建命令为 `docker build -f backend/Dockerfile -t online-homework-api .`。**当前 `backend/Dockerfile` 仍是单阶段运行镜像，只复制预先构建的 JAR；因此在 Dockerfile 改为多阶段并通过本地构建验证前，这条命令/部署方式尚未就绪。**不要依赖开发机上遗留的 `backend/target` 构建产物。

- API 健康检查完整地址：`https://<api-host>/actuator/health`。端点仅暴露健康状态，不应改为公开详细配置。
- 必需运行配置：`DATABASE_URL`（Spring JDBC URL，格式为 `jdbc:postgresql://...`）、`DATABASE_USERNAME`、`DATABASE_PASSWORD`、`APP_JWT_SECRET_BASE64`（Base64 解码后至少 32 字节随机值）、`APP_PUBLIC_BASE_URL`、`SMTP_HOST`、`SMTP_PORT`、`SMTP_USERNAME`、`SMTP_PASSWORD`、`SMTP_FROM`。
- `PORT` 由平台注入；数据库连接池默认较小，若调整 `DB_POOL_SIZE`，须符合数据库连接数限制。
- 将密钥放入测试环境专用 Secret，不放入仓库、普通变量、部署说明或构建参数。测试与生产必须使用不同凭据和数据库。

### 网页：Next.js Node Service

Next.js 必须以 Node 服务运行，不能作为纯静态站点发布。服务根目录设为 `frontend`，构建命令为 `npm ci && npm run build`，启动命令为 `npm run start -- --hostname 0.0.0.0 --port $PORT`；运行环境须保留 Node 服务进程并监听平台提供的 `PORT`。

- 网页可访问性检查完整地址：`https://<web-host>/`。
- 前端当前默认 API 基址是同源 `/api/v1`。如果 API 与网页使用不同域名，须在**前端构建时**设置 `NEXT_PUBLIC_API_BASE_URL=https://<api-host>/api/v1`；此公开变量只能放 API 公共地址，不能放密钥。

### CORS 与域名

优先使用同源路由（网页域名下的 `/api/v1` 代理至 API），这样无需跨域。若前端直接请求独立 API 域名，API 必须只允许精确的网页来源 `https://<web-host>`，并支持带凭据请求；不能对带凭据的请求使用 `Access-Control-Allow-Origin: *`。

**当前后端调用了 Spring Security CORS 支持，但仓库中尚无可配置的 CORS 来源白名单。**因此独立前后端域名尚未达到可部署条件；需先由 API 代码负责人实现并验证来源白名单，再设置实际域名，不能仅靠新增一个未被代码读取的环境变量解决。对象存储的浏览器直传另有独立 CORS 配置，见下节。

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

- `PR checks` 对 PR 与 `main` 提交运行；后端路径有变化时执行 Java 25 `mvn verify`，前端路径有变化时执行依赖安装、lint、测试和生产构建。
- `Deploy test environment` 仅在 `main` 有新提交后运行，按路径变化触发 API/网页部署钩子，并通过完整健康 URL 检查部署结果。
- 在 GitHub 仓库 **Settings → Environments** 建立 `test` 环境；部署钩子放 Secret，健康地址放 Variable：

| 类型 | 名称 | 值示例 |
| --- | --- | --- |
| Secret | `TEST_API_DEPLOY_HOOK_URL` | API 测试服务部署钩子 |
| Secret | `TEST_WEB_DEPLOY_HOOK_URL` | 网页测试服务部署钩子 |
| Variable | `TEST_API_HEALTH_URL` | `https://<api-host>/actuator/health` |
| Variable | `TEST_WEB_HEALTH_URL` | `https://<web-host>/` |

部署钩子、数据库密码、JWT 密钥、SMTP 凭据、对象存储密钥及演示账号凭据都必须是测试环境专用 Secret，不得放入普通变量。启用发布前还须完成数据库迁移验证、私有 bucket/versioning/CORS 验证、SMTP 验证、API CORS 方案确认、健康检查及备份/恢复策略评估。真实注册仍必须完成邮箱验证；演示账号安全边界及其尚未实现的运行时保护统一按 PR #8 的运维章节和 API README 执行。

## 当前部署阻塞项

1. `backend/Dockerfile` 仍为单阶段，需要改为并验证多阶段构建。
2. 前端需配置为 Next.js Node Service，并确定 API/网页域名和同源代理或跨域方案。
3. 若跨域，API 尚需实现精确的 CORS 白名单配置。
4. 尚未绑定私有对象存储、versioning、存储端 CORS 及测试凭据。
5. 邮箱验证投递需要测试 SMTP；PR #8 所列演示模式运行时保护在 API 实现并通过测试前不得启用。
6. 目前没有测试环境服务或数据库；本工作项仅更新文档并通过 PR 集成，不创建任何云资源或付费服务。
