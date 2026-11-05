# REST API 契约摘要（MVP）

API 前缀 `/api/v1`，资源 ID 使用 UUID，JSON 时间戳为 UTC ISO-8601 instant；截止时间展示时由前端转换成班级 IANA 时区。详细 schema 以 [`docs/openapi.yaml`](../openapi.yaml) 为准。所有资源访问都由服务端按认证用户、角色、班级所有权/成员关系与资源状态授权；不能依赖前端隐藏。

## 身份与班级

| 方法 | 路径 | 权限与行为 |
|---|---|---|
| `POST` | `/auth/register` | 未登录学生注册。测试环境使用预置教师/学生测试账号；真实学生接入前启用登录邮箱验证。邮箱验证投递方式、生产 token provider、教师生产账号开通仍需配置/确认。 |
| `POST` | `/auth/verify-email` | 未登录用户提交邮箱收到的一次性 token；成功验证邮箱后返回无响应体 `204`；请求无效或 token 无效/过期返回 `400`，关联账号不存在返回 `404`。 |
| `POST` | `/auth/login` | 未登录用户登录；认证方式与 token/session provider 仍需结合部署配置。 |
| `GET` | `/me` | 返回当前用户 ID、角色与邮箱验证状态。 |
| `GET` / `POST` | `/classes` | 教师列出/创建自己的班级；创建时明确传 IANA `timeZone`。学生仅列出自己加入的班级。 |
| `GET` | `/classes/{classId}` | 班级教师或成员读取班级。 |
| `GET` / `POST` | `/classes/{classId}/members` | 教师查看名册；按已注册学生的登录邮箱精确添加，未注册返回 `STUDENT_ACCOUNT_NOT_FOUND` 并提示先注册；服务端建立成员关系。 |
| `DELETE` | `/classes/{classId}/members/{studentId}` | 教师移除成员；立即撤销该学生之后访问此班级资源的权限，历史记录保留。 |

不发送邀请邮件、不提供自助入班码。测试环境使用预置测试账号；接入真实学生前必须验证登录邮箱。邮箱验证投递方式、生产身份提供方及教师生产账号开通仍需配置/确认。

## 作业

| 方法 | 路径 | 权限与行为 |
|---|---|---|
| `GET` / `POST` | `/classes/{classId}/assignments` | 教师查看草稿和已发布作业/创建草稿；学生仅获取本班已发布作业。截止时间保存 UTC，显示按班级时区。 |
| `GET` / `PATCH` | `/assignments/{assignmentId}` | 有权用户读取；所属教师只可修改草稿，发布后首版不静默覆盖核心内容。 |
| `POST` | `/assignments/{assignmentId}/publish` | 所属教师发布草稿，使本班成员可见。 |

逾期在首次 complete 校验成功时判定：记录 UTC `completedAt`，仅当 `completedAt > dueAt` 才标记逾期；`completedAt == dueAt` 按时。默认允许晚完成并标记逾期；`closeAfterDeadline=true` 时仅拒绝 `completedAt > dueAt` 的完成。首版不另加手动关闭接口。

## 私有附件与两阶段上传

| 方法 | 路径 | 权限与行为 |
|---|---|---|
| `POST` | `/assignments/{assignmentId}/attachment-upload-intents` | 作业所属教师为草稿创建附件上传记录；必须有 `Idempotency-Key`，每个作业仅允许一个附件。 |
| `POST` | `/assignments/{assignmentId}/submissions` | 班级学生创建待上传提交记录；必须有 `Idempotency-Key`。同一用户+作业+key 重试返回相同上传记录，不生成正式提交版本。 |
| `PUT` | `{uploadUrl}` | 浏览器用短时签名 URL 直传私有对象；上传成功本身不等于提交成功。 |
| `POST` | `/submissions/{uploadId}/complete` | 服务端确认对象已到达并校验大小/实际内容类型。未就绪返回可重试 `UPLOAD_NOT_READY` 且不生成可见版本；首次校验成功后才原子性分配版本号、记录 UTC `completedAt` 和逾期状态；仅 `completedAt > dueAt` 才逾期，相等按时；重复 complete 返回原结果，不重复计版本。 |
| `POST` | `/assignments/{assignmentId}/attachment-upload-intents/{uploadId}/complete` | 所属教师完成作业附件上传；服务端先验证对象，再将其关联至草稿作业。重复调用返回同一结果。 |
| `POST` | `/assignments/{assignmentId}/attachment-download` | 作业所属教师/班级学生先经授权，再取得短时签名下载 URL。 |
| `POST` | `/submissions/{submissionId}/attachment-download` | 所属教师/提交学生先经授权，再取得短时签名下载 URL。 |

每个作业最多一个附件，每个提交版本最多一个附件，单个不超过 **20 MiB（20,971,520 字节）**；20,971,520 字节可接受，20,971,521 字节拒绝。允许 PDF、JPG、PNG、DOCX、XLSX、PPTX。服务端生成 object key，不接受客户端任意 key；对象存储桶必须私有。上传/下载签名按对象与操作授权、短时过期，URL 不得写入日志。

## 提交与批改

| 方法 | 路径 | 权限与行为 |
|---|---|---|
| `GET` | `/assignments/{assignmentId}/my-submissions` | 学生只读自己的所有已完成版本和当前已发布批改结果；不返回同学数据或未发布批改。 |
| `GET` | `/assignments/{assignmentId}/submissions` | 作业所属教师查看班级学生已完成提交。 |
| `POST` / `GET` | `/submissions/{submissionId}/grades` | 所属教师追加一条批改草稿/查看完整批改历史。 |
| `POST` | `/grades/{gradeId}/publish` | 所属教师发布一条批改；学生端只返回当前最新已发布结果，历史记录保留给教师。 |

## 固定错误与授权底线

业务错误统一返回稳定 `code`；至少包括 `STUDENT_ACCOUNT_NOT_FOUND`、`IDEMPOTENCY_KEY_CONFLICT`、`UPLOAD_NOT_READY`、`FILE_TOO_LARGE`、`UNSUPPORTED_FILE_TYPE`、`FILE_CONTENT_MISMATCH`、`FILE_EMPTY`、`UPLOAD_EXPIRED`、`ASSIGNMENT_CLOSED`、`ASSIGNMENT_NOT_PUBLISHED`。`UPLOAD_NOT_READY` 标记为可重试；校验失败不得建立附件/提交版本。

未认证请求拒绝；已认证但对象越权请求使用一致的 403/不泄露资源存在性的 404 策略。教师只访问自己的班级及其作业、提交、附件和批改；学生只访问所属班级已发布作业、自己的提交/附件和当前已发布反馈。服务端在每个上传、complete、下载签名、发布和列表端点重新执行授权；前端可见性不是安全边界。
