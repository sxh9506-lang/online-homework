# Online Homework API

Java 25 + Spring Boot 4.1.0 模块化单体；REST API、Spring Security、Spring Data JPA、Flyway、PostgreSQL。Spring Boot 4.1.0 官方系统要求确认兼容 Java 25。

## 当前实现契约

- 班级成员通过 `POST /api/v1/classes/{classId}/members` 精确按已注册学生登录邮箱添加；不存在返回 `STUDENT_ACCOUNT_NOT_FOUND`，不发邀请、不自动创建用户。
- 学生提交上传意图携带 `Idempotency-Key`；相同用户/作业/key/请求复用同一上传记录；同 key 不同请求返回 `IDEMPOTENCY_KEY_CONFLICT`。
- 只有对象上传完成且服务端复核通过后，complete 才原子地产生可见提交版本、UTC 时间和逾期标记；未就绪返回可重试 `UPLOAD_NOT_READY`；重复 complete 返回第一次结果。
- 角色和对象级授权在服务端执行；教师只访问自己班级，学生只访问本班已发布作业、自己的提交/文件与当前已发布反馈。
- 附件使用私有 S3-compatible bucket；服务端生成对象 key，签名 URL 限定单对象/单操作且短时过期。complete 复核实际文件签名和大小，拒绝不符内容。

## 运行配置

运行需要 Java 25、Maven 3.6.3+、PostgreSQL 和 S3-compatible 私有存储（例如启用 bucket versioning 的 MinIO）。配置只通过环境变量提供；不要将真实密钥、测试账号密码、签名链接或用户文件提交到仓库。必要变量：

短期演示数据库方案为 Render 免费 PostgreSQL（1 GB、创建后 30 天到期、无备份，到期后 14 天宽限期再删除）。此处仅记录方案，本次不创建资源；只使用虚构演示数据，不存真实学生资料或唯一副本。

- `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD`
- `APP_JWT_SECRET_BASE64`（Base64 编码、至少 32 字节随机密钥）
- `S3_ENDPOINT`, `S3_PRESIGN_ENDPOINT`, `S3_BUCKET`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`
- `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `SMTP_FROM`
- `APP_PUBLIC_BASE_URL`

构建：`mvn --batch-mode -f backend/pom.xml verify`。运行：`mvn --batch-mode -f backend/pom.xml spring-boot:run`。

## 演示账号安全边界

- `DEMO_MODE` 默认必须为 `false`。只有明确批准的测试演示环境才可启用；演示数据仅限预置的虚构教师和学生账号，邮箱必须使用 `.invalid` 域名。
- 测试密码只能从测试环境 Secrets 注入；持久化前必须通过密码编码器转换为单向哈希。原始密码不得出现在 API 响应、日志、PR 或公开文档中。
- 生产环境检测到 `DEMO_MODE=true` 时必须在启动阶段失败。自助注册始终要求完成邮箱验证，演示模式不得绕过验证。
- **实现状态：** 当前分支尚未实现 `DEMO_MODE`、演示账号预置或生产启动保护；在代码和测试落实这些约束前，不得启用演示模式或宣称生产保护已生效。

## 已确认规则与待集成依赖

- 班级成员 API 与当前 OpenAPI 和验收用例保持一致：`POST /classes/{classId}/members`，仅教师可管理自己的班级，并按已注册学生邮箱精确添加成员。
- `POST /api/v1/auth/verify-email` 接受必填 `token`，成功返回无响应体的 `204`；无效/过期令牌及请求错误返回 `400`，关联账号缺失返回 `404`。`docs/openapi.yaml` 已同步这些请求、响应和错误码。
- 附件最大值固定为 `20 * 1024 * 1024 = 20,971,520` 字节（20 MiB）。
- 截止时间用服务端 UTC instant 比较；只有完成上传校验的 `completedAt > dueAt` 才标逾期/关闭，恰好相等视为按时。
- 邮箱验证由配置的 SMTP 发送；测试环境使用专用已验证账号。教师账号由受控运维方式预置，MVP 不开放教师自助注册。
- 测试环境实际 S3-compatible 服务、bucket versioning 与 SMTP 凭据尚未绑定；代码可用 mock/unit tests 验证，不代表线上测试环境已部署。
