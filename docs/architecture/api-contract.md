# REST API 契约草案（MVP v0.1）

前缀 `/api/v1`；资源 ID 使用 UUID；请求/响应为 JSON（文件内容经签名 URL 直传私有对象存储）。每个 endpoint 都由服务端按当前认证用户校验角色和资源归属。下面省略通用错误响应字段，业务错误统一含稳定 `code` 与可安全展示的 `message`。

## 认证与班级

| 方法 | 路径 | 角色 | 行为 |
|---|---|---|---|
| `POST` | `/auth/register` | 未登录 | 注册教师/学生账号；邮箱按部署策略验证。真实学生数据接入前必须启用邮箱验证。 |
| `POST` | `/auth/login` | 未登录 | 登录；认证机制按同域部署确定为安全会话 Cookie 或等价短期凭证。 |
| `POST` | `/auth/logout` | 已登录 | 退出当前会话。 |
| `GET` | `/me` | 已登录 | 返回当前用户基础信息、角色及邮箱验证状态。 |
| `GET` | `/classes` | 教师/学生 | 教师返回自己班级；学生返回当前所属班级。 |
| `POST` | `/classes` | 教师 | 创建班级，需指定名称和 IANA `timeZone`。 |
| `GET` | `/classes/{classId}` | 班级成员 | 获取班级信息；教师须拥有该班，学生须为该班成员。 |
| `POST` | `/classes/{classId}/members` | 班级教师 | 按学生登录邮箱精确添加已有学生账号；账号不存在返回 `STUDENT_ACCOUNT_NOT_FOUND`（提示先注册），不得发邀请或自动入班。 |
| `DELETE` | `/classes/{classId}/members/{studentId}` | 班级教师 | 移除学生成员关系；撤销其后续班级内容访问权，历史记录保留。 |

## 作业

| 方法 | 路径 | 角色 | 行为 |
|---|---|---|---|
| `POST` | `/classes/{classId}/assignments` | 班级教师 | 建立草稿，含标题、说明、UTC 截止时间（客户端按班级时区输入/展示）、截止后是否关闭。 |
| `PATCH` | `/assignments/{assignmentId}` | 作业所属教师 | 更新草稿字段；发布后核心要求不可静默覆盖。 |
| `POST` | `/assignments/{assignmentId}/publish` | 作业所属教师 | 发布作业，使本班成员可见。 |
| `POST` | `/assignments/{assignmentId}/close` | 作业所属教师 | 关闭提交；即使默认允许逾期，关闭后也拒绝新提交。 |
| `GET` | `/classes/{classId}/assignments` | 班级教师/学生 | 教师可看本班草稿与已发布作业；学生仅收到本班已发布作业及允许公开字段。 |
| `GET` | `/assignments/{assignmentId}` | 班级教师/学生 | 获取角色过滤后的作业详情；跨班或未发布作业对学生不可见。 |

## 附件上传（作业附件与学生提交附件）

| 方法 | 路径 | 角色 | 行为 |
|---|---|---|---|
| `POST` | `/assignments/{assignmentId}/attachment-upload` | 作业所属教师 | 为草稿作业申请唯一附件的上传意图与短时签名 URL；每作业最多一个附件。 |
| `POST` | `/assignments/{assignmentId}/submissions` | 本班学生 | 申请提交上传；必须提供 `Idempotency-Key`。同一用户+作业+key 返回同一上传记录；响应含 pending ID、私有对象临时上传 URL 与到期时间。每个提交版本最多一个附件。 |
| `PUT` | `{signedUploadUrl}` | 持有短时 URL | 浏览器上传文件字节；该步骤本身不创建可见提交版本。 |
| `POST` | `/submissions/{uploadId}/complete` | 上传记录所属学生 | 服务端确认对象已到达并复核大小/真实文件类型。对象未就绪返回可重试 `UPLOAD_NOT_READY`，且不创建可见版本；失败返回稳定错误码；首次成功校验后原子性分配版本号、记录 UTC 时间和 `isLate`。重复 complete 返回原结果，不多造版本。 |
| `GET` | `/submissions/{submissionId}/attachment-download` | 所属教师/提交学生 | 先按作业与班级授权，再返回短时签名下载 URL；不接受客户端 object key。 |

约束：每作业/提交最多一个附件、单文件不超过 20 MB；允许类型 PDF、JPG、PNG、DOCX、XLSX、PPTX。服务端在上传申请与 complete 两阶段校验；拒绝不支持格式、超限或内容与声明不符的对象。签名 URL 过期后不可复用。

## 提交与批改

| 方法 | 路径 | 角色 | 行为 |
|---|---|---|---|
| `GET` | `/assignments/{assignmentId}/my-submissions` | 作业所属学生 | 列出当前学生的有效提交版本；不返回其他学生数据。 |
| `GET` | `/assignments/{assignmentId}/submissions` | 作业所属教师 | 查看本班学生已完成提交及版本。 |
| `POST` | `/submissions/{submissionId}/reviews` | 作业所属教师 | 新建一条批改草稿（数值分数/文字反馈；具体评分上限待产品确认）。 |
| `POST` | `/reviews/{reviewId}/publish` | 批改教师 | 发布该批改版本；历史记录追加保留。 |
| `GET` | `/submissions/{submissionId}/reviews/current` | 所属学生/教师 | 学生只获取当前已发布结果；教师可在教师接口查看完整批改历史。 |
| `GET` | `/submissions/{submissionId}/reviews/history` | 作业所属教师 | 查看该提交完整批改历史。 |

## 统一安全与时间规则

- 未登录请求返回 `401`; 已登录但对象级越权请求拒绝访问（稳定 `403`/不泄露资源存在性的 `404` 策略需全 API 一致）。绝不依赖前端隐藏。
- 教师只能访问自己班级下的作业、提交、附件和批改；学生只能访问所属班级已发布作业及自己的提交、附件和当前已发布反馈。
- 服务端使用 UTC instant 判定截止/逾期；使用班级 IANA 时区格式化给用户。客户端时间不作为服务端判定依据。
- 上传意图与提交版本分离：失败或待上传对象不是可见提交；版本号只有首个成功 complete 时产生。重复申请受 `Idempotency-Key` 约束，重复 complete 幂等。
- 错误码至少包括 `STUDENT_ACCOUNT_NOT_FOUND`、`IDEMPOTENCY_KEY_CONFLICT`、`UPLOAD_NOT_READY`、`FILE_TOO_LARGE`、`UNSUPPORTED_FILE_TYPE`、`FILE_CONTENT_MISMATCH`、`ASSIGNMENT_CLOSED`、`ASSIGNMENT_NOT_PUBLISHED`、`FORBIDDEN`。
