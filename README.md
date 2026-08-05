# 🔗 ShortURL — 短链接服务

> 🤖 **AI 学习声明**：本项目通过与 AI 结对编程的方式完成，用于学习和理解 Spring Boot 短链接服务的完整实现。每一行代码都经过人工审查和理解，是学习过程的产物而非自动化生成的黑盒。

一个基于 Spring Boot 的短链接服务，支持长链接转短码、302 重定向、访问次数统计、重复长链接复用，以及管理员后台管理。

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [快速开始](#快速开始)
  - [开发环境（H2 内嵌数据库）](#开发环境h2-内嵌数据库)
  - [生产环境（Docker Compose + MySQL + Nginx）](#生产环境docker-compose--mysql--nginx)
- [API 文档](#api-文档)
  - [公开接口](#公开接口)
  - [管理员认证](#管理员认证)
  - [管理员接口](#管理员接口)
- [安全设计](#安全设计)
- [配置说明](#配置说明)
- [部署架构](#部署架构)
- [License](#license)

---

## 功能特性

### 核心功能

- ✅ **长链接转短码**：将长 URL 映射为 6 位随机短码（大小写字母 + 数字，62^6 ≈ 568 亿组合）
- ✅ **302 重定向**：访问 `/s/{shortCode}` 时 302 跳转到原始链接
- ✅ **访问计数**：每次重定向自动 +1，使用原子 UPDATE 杜绝竞态条件
- ✅ **重复复用**：相同长链接自动返回已有短码，避免重复创建
- ✅ **用户历史**：基于 Cookie（SUID）匿名追踪，无需登录即可查看自己创建的短链接
- ✅ **统计查询**：通过短码或完整短链接查询访问次数和创建时间

### 管理与安全

- ✅ **管理员后台**：独立的管理面板（`/admin.html`）
- ✅ **多角色权限**：超级管理员（SUPER_ADMIN）可创建/删除/禁用其他管理员
- ✅ **BCrypt 密码哈希**：密码不可逆存储
- ✅ **Session 管理**：防 Session 固定攻击（登录前注销旧 Session）
- ✅ **暴力破解防护**：IP 粒度，60 秒内超过 5 次失败封锁 5 分钟
- ✅ **密码强度验证**：至少 8 位，必须包含字母和数字
- ✅ **URL 安全校验**：拒绝 `javascript:`、`data:`、`file:` 等危险协议，限制 2000 字符

### 软删除

- ✅ 管理员可删除/批量删除短链接（标记 `deleted=true`）
- ✅ 已删除的短链接不再重定向，短码不可复用

---

## 技术栈

| 层级         | 技术                                        |
| ------------ | ------------------------------------------- |
| 后端框架     | Spring Boot 3.2.5                           |
| 构建工具     | Maven                                       |
| 数据库（开发）| H2（文件持久化，零配置启动）                |
| 数据库（生产）| MySQL 8.0                                  |
| ORM          | Spring Data JPA (Hibernate)                 |
| 密码加密     | BCrypt (`spring-security-crypto`)           |
| 前端         | 原生 HTML + CSS + JavaScript（零依赖）      |
| 反向代理     | Nginx                                       |
| 容器化       | Docker + Docker Compose                     |
| Java 版本    | 17                                          |

---

## 项目结构

```
short-url/
├── Dockerfile                        # 多阶段构建（Maven 编译 + JRE 运行镜像）
├── docker-compose.yml                # 生产环境编排（MySQL + App + Nginx）
├── nginx.conf                        # Nginx 反向代理配置
├── pom.xml                           # Maven 配置
├── .env.example                      # 环境变量模板
├── .gitignore
├── README.md
├── scripts/
│   └── load_test.py                  # 可选：简单的性能测试脚本
└── src/main/
    ├── java/com/shorturl/
    │   ├── ShortUrlApplication.java  # Spring Boot 入口
    │   ├── model/
    │   │   ├── UrlMapping.java       # 短链接实体
    │   │   ├── AdminUser.java        # 管理员实体
    │   │   └── SessionUser.java      # Session DTO（不含密码）
    │   ├── repository/
    │   │   ├── UrlMappingRepository.java
    │   │   └── AdminUserRepository.java
    │   ├── service/
    │   │   ├── UrlMappingService.java # 短链接业务逻辑
    │   │   ├── AdminService.java      # 管理员业务逻辑（含初始化）
    │   │   └── LoginRateLimiter.java  # 登录速率限制
    │   ├── controller/
    │   │   ├── UrlController.java     # 公开 API（缩短、重定向、统计、历史）
    │   │   ├── AuthController.java    # 认证 API（登录/登出/状态）
    │   │   └── AdminController.java   # 管理 API（CRUD）
    │   └── util/
    │       └── PasswordUtil.java      # BCrypt 工具类
    └── resources/
        ├── application.properties     # 默认配置
        ├── application-dev.yml        # 开发环境覆盖
        ├── application-prod.yml       # 生产环境覆盖
        └── static/
            ├── index.html             # 用户端页面
            └── admin.html             # 管理端页面
```

---

## 快速开始

### 开发环境（H2 内嵌数据库）

**前置条件：** JDK 17+、Maven 3.8+

```bash
# 1. 进入项目目录
cd short-url

# 2. 启动（使用默认 H2 数据库，无需额外配置）
mvn spring-boot:run

# 3. 打开浏览器
#    用户端：http://localhost:8080
#    H2 控制台：http://localhost:8080/h2-console
#      JDBC URL: jdbc:h2:file:./data/shorturl
#      用户名: sa  密码: h2secret
```

> **说明**：开发环境默认使用 H2 文件数据库（`./data/shorturl.mv.db`），数据持久化在本地文件中，重启不丢失。如需开启 H2 在线控制台，使用 `SPRING_PROFILES_ACTIVE=dev` 启动：
> ```bash
> mvn spring-boot:run -Dspring-boot.run.profiles=dev
> ```

**开发环境无法使用管理员功能？**

开发环境不会自动创建超级管理员（因为 `SpringProfile` 默认为 `default`）。如需测试管理员功能：

```bash
# Windows PowerShell
$env:DEFAULT_ADMIN_PASSWORD="Admin1234"
# Linux/macOS
export DEFAULT_ADMIN_PASSWORD="Admin1234"

mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

---

### 生产环境（Docker Compose + MySQL + Nginx）

**前置条件：** Docker、Docker Compose

```bash
# 1. 进入项目目录
cd short-url

# 2. 创建环境变量文件
cp .env.example .env

# 3. 编辑 .env，设置强密码（所有密码都必须修改）
#    MYSQL_ROOT_PASSWORD=你的MySQL根密码
#    MYSQL_PASSWORD=你的应用数据库密码
#    DEFAULT_ADMIN_PASSWORD=你的管理员密码（至少8位，含字母和数字）

# 4. 编辑 nginx.conf，替换 server_name 为你的域名

# 5. 构建并启动
docker-compose up -d --build

# 6. 查看日志
docker-compose logs -f app

# 7. 访问
#    http://your-domain  （通过 Nginx 80 端口）
```

**服务端口规划：**

| 服务   | 端口 | 说明                  |
| ------ | ---- | --------------------- |
| Nginx  | 80   | 对外入口，反向代理    |
| App    | 8080 | 内网，仅 Nginx 访问   |
| MySQL  | 3306 | 数据库                |

---

## API 文档

### 公开接口

#### 1. 创建短链接

```
POST /api/shorten
Content-Type: application/json

Request:
{
  "url": "https://example.com/very/long/url/path"
}

Response 200:
{
  "shortUrl": "http://localhost:8080/s/aB3xY9",
  "shortCode": "aB3xY9",
  "originalUrl": "https://example.com/very/long/url/path",
  "accessCount": 0
}
```

#### 2. 短链接重定向

```
GET /s/{shortCode}

→ 302 Found → Location: https://original.url/...
（每次访问计数 +1）
```

#### 3. 查询访问统计

```
GET /api/stats?code={shortCode}
GET /api/stats?code=http://localhost:8080/s/{shortCode}

Response 200:
{
  "shortCode": "aB3xY9",
  "originalUrl": "https://example.com/...",
  "accessCount": 42,
  "createdAt": "2026-05-20T14:30:00"
}
```

#### 4. 查询用户历史

```
GET /api/history

Response 200:
[
  {
    "id": 1,
    "shortCode": "aB3xY9",
    "originalUrl": "https://...",
    "accessCount": 42,
    "createdAt": "2026-05-20T14:30:00"
  }
]
```

> 用户通过 Cookie `suid`（UUID）匿名标识，无需登录。清除 Cookie 会丢失历史记录。

---

### 管理员认证

#### 登录

```
POST /api/auth/login
Content-Type: application/json

Request:
{
  "username": "admin",
  "password": "your_password"
}

Response 200:
{
  "success": true,
  "username": "admin",
  "role": "SUPER_ADMIN"
}

Response 429 (被封锁):
{
  "error": "登录尝试过于频繁，请5分钟后再试"
}
```

#### 获取登录状态

```
GET /api/auth/me

Response 200: { "loggedIn": true, "username": "admin", "role": "SUPER_ADMIN" }
Response 200: { "loggedIn": false }
```

#### 退出登录

```
POST /api/logout
```

---

### 管理员接口

> **鉴权说明：** 所有 `/api/admin/**` 接口需要登录后使用 Session Cookie。部分接口需要 `SUPER_ADMIN` 角色。

| 方法     | 路径                        | 权限         | 说明             |
| -------- | --------------------------- | ------------ | ---------------- |
| `GET`    | `/api/admin/urls`           | 管理员       | 查看所有短链接   |
| `DELETE` | `/api/admin/urls/{code}`    | 管理员       | 软删除短链接     |
| `POST`   | `/api/admin/urls/batch-delete` | 管理员    | 批量软删除       |
| `POST`   | `/api/admin/admins`         | 超级管理员   | 创建管理员       |
| `GET`    | `/api/admin/admins`         | 超级管理员   | 查看所有管理员   |
| `DELETE` | `/api/admin/admins/{id}`    | 超级管理员   | 删除管理员       |
| `PUT`    | `/api/admin/admins/{id}/toggle` | 超级管理员 | 禁用/启用管理员  |
| `PUT`    | `/api/admin/password`       | 管理员       | 修改自己的密码   |

#### 创建管理员

```
POST /api/admin/admins
Content-Type: application/json

{
  "username": "newadmin",
  "password": "NewAdmin123",
  "role": "ADMIN"
}
```

> `role` 可选值：`ADMIN` 或 `SUPER_ADMIN`。不传默认为 `ADMIN`。

#### 批量删除

```
POST /api/admin/urls/batch-delete
Content-Type: application/json

{
  "codes": ["abc123", "xyz789"]
}
```

---

## 安全设计

### 认证与授权

- **密码**：BCrypt 哈希存储，原始密码不落盘、不传输
- **Session**：登录成功后 Session 持久化（`setMaxInactiveInterval(-1)`），不存密码 Hash，仅存轻量 DTO（`SessionUser`：id / username / role / enabled）
- **防 Session 固定**：每次登录前 `invalidate()` 旧 Session，创建全新 Session ID
- **角色控制**：Controller 方法级鉴权，`requireAdmin()` / `requireSuperAdmin()`

### 暴力破解防护

`LoginRateLimiter`（内存实现）：

- **窗口**：60 秒内最多 5 次失败——这是合理的风险评估，因为分布式共享 IP（公司 WiFi、学校网络）会在同一个 NAT 出口汇聚大量用户，阈值过低会误伤正常用户
- **封锁**：触发后封锁 5 分钟，返回 HTTP 429
- **清理**：定期清理过期记录（封锁到期 + 窗口过期后 60 秒）

> **说明**：对于高并发或多节点部署，建议将速率限制迁移至 Redis 等外部存储，以保证节点间一致。

### URL 安全

- **协议白名单**：仅允许 `http://` 和 `https://` 协议
- **危险协议拦截**：拒绝 `javascript:`、`data:`、`file:`、`vbscript:`、`about:`
- **长度限制**：最长 2000 字符，防止 DoS 和数据库截断
- **大小写规范化**：`HTTP://` 自动转为 `http://`，避免 scheme 不一致导致的拼接 bug

### 代理头部安全

- **配置开关** `shorturl.trusted-proxy`：
  - `false`（默认/开发环境）：忽略 `X-Forwarded-*` 头部，使用请求直连信息
  - `true`（生产环境）：信任 Nginx 传递的 `X-Forwarded-For`/`X-Forwarded-Proto`/`X-Forwarded-Host`/`X-Forwarded-Port`
- `X-Forwarded-Port` 解析异常保护（`NumberFormatException` 捕获）

### 生产部署安全

- 应用绑定 `127.0.0.1:8080`，不直接暴露公网
- Nginx 作为反向代理处理所有外部请求
- Docker 容器以非 root 用户（`appuser`）运行
- JVM 堆内存限制（`-Xmx256m -Xms128m`）
- 数据库密码通过环境变量注入，不硬编码

---

## MySQL 数据库说明

### 为什么需要 MySQL？

项目采用**双数据库策略**：开发环境使用 H2（纯 Java 内嵌数据库，零配置起步），生产环境使用 MySQL（成熟的关系型数据库，适合高并发和数据可靠性场景）。

| 环境       | 数据库   | 激活方式                              |
| ---------- | -------- | ------------------------------------- |
| 开发       | H2       | `mvn spring-boot:run`（默认 profile） |
| 开发 + 控制台 | H2    | `mvn spring-boot:run -Dspring-boot.run.profiles=dev` |
| 生产       | MySQL 8.0 | `SPRING_PROFILES_ACTIVE=prod`         |

### MySQL 存储了哪些数据？

MySQL 中自动创建两张核心表，由 JPA（`ddl-auto=update`）自动建表：

```
mysql> SHOW TABLES;
+----------------------+
| Tables_in_shorturl   |
+----------------------+
| admin_users          |  管理员表（username, bcrypt 密码哈希, role, enabled, deleted）
| url_mappings         |  短链接表（originalUrl, shortCode, accessCount, createdBy, deleted）
+----------------------+
```

### 在开发环境使用 MySQL（替代 H2）

如果希望在本地开发时就用 MySQL 调试：

```bash
# 1. 启动本地 MySQL（Docker 方式）
docker run -d --name mysql-dev \
  -e MYSQL_ROOT_PASSWORD=root123 \
  -e MYSQL_DATABASE=shorturl \
  -e MYSQL_USER=shorturl \
  -e MYSQL_PASSWORD=shorturl123 \
  -p 3306:3306 mysql:8.0

# 2. 设置环境变量并启动
# Windows PowerShell
$env:MYSQL_HOST="localhost"
$env:MYSQL_PORT="3306"
$env:MYSQL_DB="shorturl"
$env:MYSQL_USER="shorturl"
$env:MYSQL_PASSWORD="shorturl123"
$env:DEFAULT_ADMIN_PASSWORD="Admin1234"
$env:SPRING_PROFILES_ACTIVE="prod"

mvn spring-boot:run

# 3. 连接验证
# MySQL 客户端连接：
#   Host: localhost  Port: 3306
#   User: shorturl  Password: shorturl123  Database: shorturl
```

### 生产环境（docker-compose）

生产环境通过 Docker Compose 编排 MySQL + App + Nginx 三个服务：

```
docker-compose.yml
├── mysql    → 官方 MySQL 8.0 镜像，挂载持久化卷（mysql_data）
├── app      → Spring Boot，通过环境变量连接 MySQL
└── nginx    → 反向代理，将外部请求转发给 app
```

MySQL 的连接串在 `application-prod.yml` 中配置：

```
spring.datasource.url=jdbc:mysql://${MYSQL_HOST:mysql}:${MYSQL_PORT:3306}/${MYSQL_DB:shorturl}?...
```

关键参数都由 `.env` 文件导入，所有密码必须修改默认值。

---

## 配置说明

### 环境变量

| 变量                     | 说明                 | 必须 |
| ------------------------ | -------------------- | ---- |
| `DEFAULT_ADMIN_PASSWORD` | 超级管理员初始密码   | 是*  |
| `MYSQL_ROOT_PASSWORD`    | MySQL root 密码      | 是   |
| `MYSQL_DB`               | 数据库名（默认 shorturl） | 否 |
| `MYSQL_USER`             | 数据库用户（默认 shorturl） | 否 |
| `MYSQL_PASSWORD`         | 数据库密码           | 是   |

> \* `DEFAULT_ADMIN_PASSWORD` 仅在首次启动且不存在 `admin` 用户时需要。之后修改密码通过 `/api/admin/password` 接口。

### 关键配置项（application.properties）

| 配置项                     | 默认值         | 说明                       |
| -------------------------- | -------------- | -------------------------- |
| `server.port`              | 8080           | 服务端口                   |
| `server.address`           | 0.0.0.0        | 生产环境覆盖为 127.0.0.1  |
| `shorturl.trusted-proxy`   | false          | 是否信任反向代理头部       |
| `spring.datasource.url`    | H2 file        | 生产环境通过 profile 覆盖  |
| `spring.datasource.password` | h2secret    | H2 数据库密码              |
| `spring.h2.console.enabled` | false         | 默认关闭 H2 控制台         |
| `spring.jpa.hibernate.ddl-auto` | update   | 自动建表/更新表结构        |

### Spring Profile

| Profile | 数据库 | 说明              |
| ------- | ------ | ----------------- |
| default | H2     | 开发环境          |
| dev     | H2     | 开发环境 + H2 控制台 |
| prod    | MySQL  | 生产环境          |

---

## 部署架构

```
                   Internet
                       |
                  [Nginx :80]
                   /       \
            /index.html   /api/*  /s/*
            (静态文件)     \       /
                       [Spring Boot :8080]
                              |
                         [MySQL :3306]
```

- **Nginx**：负责静态文件托管、反向代理、设置代理头部（`X-Forwarded-*`）
- **Spring Boot**：API 服务，仅绑定内网 `127.0.0.1`
- **MySQL**：持久化存储，数据挂载到 Docker Volume

---

## License

MIT