# Online Homework API

Java 25 + Spring Boot 4.1.0 模块化单体；REST API、Spring Security、Spring Data JPA、Flyway、PostgreSQL。Spring Boot 4.1.0 官方系统要求确认兼容 Java 25。

## 当前实现契约

- 班级成员通过 `POST /api/v1/classes/{classId}/members` 精确按已注册学生登录邮箱添加；不存在返回 `STUDENT_ACCOUNT_NOT_FOUND`，不发邀请、不自动创建用户。
- 学生提交上传意图携带 `Idempotency-Key`；相同用户/作业/key/请求复用同一上传记录；同 key 不同请求返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 只有对象上传完成且服务端复核通过后，complete 才原子地产生可见提交版本、UTC 时间和逾期标记；未就绪返回可重试 `UPLOAD_NOT_READY`；重复 complete 返回第一次结果。
- 角色和对象级授权在服务端执行；教师只访问自己班级，学生只访问本班已发布作业、自己的提交/文件与当前已发布反馈。
- 附件使用私有 S3-compatible bucket；服务端生成对象 key，签名 URL 限定单对象/单操作且短时过期。对象 API endpoint 与独立 presign endpoint 均必须是 HTTPS；complete 复核实际文件签名和大小，拒绝不符内容。
- `S3_ENABLED=false`（默认）或 S3 endpoint/显式凭据缺失时，新上传意图、尚未完成的 complete 和下载 API 返回 `503 ATTACHMENTS_UNAVAILABLE`；成功完成后的 complete 重放仍幂等返回原结果，不再次访问存储或生成链接。不会提供假 URL、不落部分记录，也不回退到机器默认凭据链。

## 运行配置

运行需要 Java 25、Maven 3.6.3+ 和 PostgreSQL。附件存储是显式启用的可选功能：生产/演示环境只有在私有 S3-compatible bucket 与所有配置验证就绪后才设置 `S3_ENABLED=true`；endpoint 必须使用 HTTPS，禁用、缺少配置或使用非 HTTPS endpoint 时附件 API 返回 503 `ATTACHMENTS_UNAVAILABLE`，不生成假 URL。配置只通过环境变量提供；不要将真实密钥、测试账号密码、签名链接或用户文件提交到仓库。

短期演示数据库为免费 PostgreSQL（1 GB、无备份），Render 控制台确认当前资源到期时间为 **2026-12-06 01:49:50 UTC**（到期后 14 天宽限期再删除）；视为临时/可丢弃环境，只使用虚构演示数据，不存真实学生资料或唯一副本。资源到期或重置会丢失临时幂等 ledger/去重记录，因此不保证长期 exactly-once。

- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- `APP_JWT_SECRET_BASE64`（Base64 编码、至少 32 字节随机密钥）
- `S3_ENABLED`（默认为 `false`；只有下述对象存储字段齐全后才能设为 `true`）
- `S3_ENDPOINT`, `S3_PRESIGN_ENDPOINT`, `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`
- `S3_REQUIRE_VERSIONING`（默认 `true` 且附件启用时必须保持为 `true`；bucket 未返回 object `versionId` 时拒绝附件操作）
- `DEPLOYMENT_LEDGER_WORKFLOW_TOKEN`（账本状态转移的自动化凭证；仅配置在后端服务侧，不由 GitHub Actions CI 读取）
- `DEPLOYMENT_LEDGER_OPERATOR_TOKEN`（仅设置在测试 API 服务，用于人工 `safe-failure`/`reconcile`；不得放入 CI Secrets）
- `DEPLOYMENT_LEDGER_ALLOWED_REPOSITORY`（必需：账本 API 的精确 `owner/repository` allowlist；缺失或为空时账本返回 503 并拒绝操作）
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`（使用自助注册时配置邮件验证发送）
- `APP_PUBLIC_BASE_URL`
- `CORS_ALLOWED_ORIGINS`（逗号分隔的精确 HTTP(S) 来源，例如 `https://school.example`；不要带路径或使用 `*`。留空时默认拒绝跨域）
- `DEMO_TEACHER_PASSWORD`, `DEMO_STUDENT_PASSWORD`（仅 `DEMO_MODE=true` 时必需，必须由隔离测试环境 Secrets 注入）

本仓库的可信 PR CI 期望在 GitHub **repository-level Actions secrets** 中配置 `ONLINE_HOMEWORK_CI_DEMO_TEACHER_PASSWORD` 和 `ONLINE_HOMEWORK_CI_DEMO_STUDENT_PASSWORD`，分别映射到上述两个进程环境变量。缺失或超出长度边界时，`Verify backend with CI demo Secrets (trusted runs)` 在运行 Maven 前失败；fork PR 使用不注入 Secrets 的单独验证路径。

构建：`mvn --batch-mode -f backend/pom.xml verify`。运行：`mvn --batch-mode -f backend/pom.xml spring-boot:run`。多阶段容器构建（仓库根目录）：`docker build -f backend/Dockerfile -t online-homework-api:local .`。

## 演示账号安全边界

- `DEMO_MODE` 默认关闭。只有隔离的 `APP_ENV=test` 环境可显式开启；启动时只预置固定教师/学生虚构账号，邮箱使用 `.invalid`。这些预置账号仅用于演示，不依赖 SMTP，且不会把已有的其他账号标记为已验证。
- 开启 demo mode 时必须通过环境 Secrets 注入 `DEMO_TEACHER_PASSWORD` 和 `DEMO_STUDENT_PASSWORD`；不提供通用默认密码。种子密码至少 16 个字符且不超过 BCrypt 的 72 UTF-8 字节限制，只持久化单向哈希；不在 API 响应、日志、PR 或公开文档中写入密码值。
- `APP_ENV` 默认按生产环境处理；`DEMO_MODE=true` 只允许 `APP_ENV=test` 且未激活 `prod`/`production` Spring profile，否则会在 WebServer 创建前拒绝启动。启用时若缺少或弱化 Secret，或保留演示账号与预置角色/验证状态/Secret 不匹配，也会拒绝启动。
- `DEMO_MODE=true` 时自助注册仍可使用，但新账号始终未验证，只有配置的邮件发送器投递一次性 token 且用户成功调用 `POST /api/v1/auth/verify-email` 后才可登录。测试使用仅驻留进程内存、不写日志的邮件捕获器；无 SMTP 的演示环境可直接使用预置账号，不能因此跳过新账号验证。
- **实现状态：** 启动保护、预置账号、注册邮箱验证、CORS 白名单和相关测试位于 PR #8；测试服务尚未部署或启用，Render 自动部署设置也尚未由 Ops 核验。所有数据仅限虚构演示用途，不放真实学生资料或唯一副本。

CI 的 Secret 注入只发生在本仓库 push/同仓库 PR 的受信步骤；fork PR 不接收这些值。Maven 输出先在进程内扫描并脱敏，Surefire/Failsafe 报告扫描后删除，workflow 不上传测试报告或工件。Workflow 源码只能证明变量引用与运行条件，无法单独证明 GitHub 中实际 Secret 的存储范围；必须由仓库维护者在 Actions 设置中确认它们确为 repository-level secrets。

## 已确认规则与待集成依赖

- 班级成员 API 与当前 OpenAPI 和验收用例保持一致：`POST /classes/{classId}/members`，仅教师可管理自己的班级，并按已注册学生邮箱精确添加成员。
- `POST /api/v1/auth/verify-email` 接受必填 `token`，成功返回无响应体的 `204`；无效/过期令牌及请求错误返回 `400`，关联账号缺失返回 `404`。`docs/openapi.yaml` 已同步这些请求、响应和错误码。
- 邮箱验证路由已列入 `docs/architecture/api-contract.md` 摘要，并按 `docs/openapi.yaml` 的详细契约实现；生产邮件投递配置仍需完成。
- 附件最大值固定为 `20 * 1024 * 1024 = 20,971,520` 字节（20 MiB）。
- 截止时间用服务端 UTC instant 比较；只有完成上传校验的 `completedAt > dueAt` 才标逾期/关闭，恰好相等视为按时。
- 邮箱验证通过 `VerificationEmailSender` 发送；生产注册须配置 SMTP，测试使用内存捕获器。教师账号由受控运维方式预置，MVP 不开放教师自助注册。
- 测试环境实际 S3-compatible 服务、bucket versioning 与 SMTP 凭据尚未绑定；附件功能默认关闭并 fail-closed 返回 503，代码测试不代表线上存储已部署。
- 测试环境 Render-only 自动部署、`checksPass` 门槛及临时 PostgreSQL ledger 的说明见 [`docs/ops/TEST_DEPLOYMENT_IDEMPOTENCY.md`](../docs/ops/TEST_DEPLOYMENT_IDEMPOTENCY.md)；GitHub Actions 只负责 CI，不触发部署。
