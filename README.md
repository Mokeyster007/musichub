# MusicHub 音乐平台后端

一个基于 Spring Boot 3 的音乐分享平台后端服务，支持歌曲上传发布、审核流转、歌词与封面自动刮削、热歌榜排行、评论互动等功能。

## ✨ 功能特性

- **歌曲全流程管理**：上传 → 私人 → 申请审核 → 公开 / 驳回 → 重新提交，状态机完整闭环
- **元数据自动补全**：上传时自动解析音频 Tag（标题 / 歌手 / 专辑 / 内嵌封面），三级兜底刮削封面（音频内嵌 → 网易云 → 苹果 iTunes），二级兜底获取歌词（网易云 → Lrclib）
- **文件云存储**：音频与封面统一存储于 MinIO，数据库仅保存 URL
- **热歌榜**：Redis ZSet 实时累加播放量，定时任务增量刷入 MySQL
- **播放历史**：记录最近播放，支持查询最近 20 条
- **用户体系**：注册 / 登录（JWT）、资料修改、密码修改，ThreadLocal 传递用户上下文
- **管理员能力**：歌曲审核、用户管理、举报处理
- **评论互动**：歌曲评论、点赞，评论与点赞数联动

## 🛠️ 技术栈

| 分类 | 技术 |
|------|------|
| 语言 / 框架 | Java 21、Spring Boot 3.1.5 |
| ORM | MyBatis-Plus 3.5.7 |
| 数据库 | MySQL 8.0 |
| 缓存 / 排行 | Redis（String / ZSet） |
| 对象存储 | MinIO 8.5.7 |
| 认证 | JWT（jjwt 0.11.5）+ 拦截器 |
| 元数据解析 | jaudiotagger 3.0.1 |
| 构建 | Maven |

## 📋 环境要求

- JDK 21
- MySQL 8.0
- Redis
- MinIO（bucket：`vibe-music-data`）
- 网易云音乐 API 服务（可选，用于歌词 / 封面刮削，默认 `http://127.0.0.1:3000`）

## 🚀 快速开始

### 1. 配置文件

复制配置模板并填写你自己的真实凭据（**模板不包含任何敏感信息**）：

```bash
cp src/main/resources/application.example.yml src/main/resources/application.yml
```

按需修改以下配置：

| 配置项 | 说明 |
|--------|------|
| `spring.datasource.*` | MySQL 连接地址、账号密码 |
| `spring.data.redis.*` | Redis 地址、密码 |
| `minio.endpoint / access-key / secret-key` | MinIO 服务地址与密钥 |
| `netease.api.base-url` | 网易云 API 服务地址 |

> ⚠️ **安全约定**：`application.yml` 包含真实凭据，已被 `.gitignore` 排除，**严禁提交到 Git 仓库**。仓库中只保留脱敏模板 `application.example.yml`。

### 2. 初始化数据库

创建数据库 `musichub`，导入项目对应的建表 SQL（表结构见 `entity` 包中的实体类）。

### 3. 启动服务

```bash
# 方式一：Maven 直接运行
mvn spring-boot:run

# 方式二：打包后运行
mvn clean package
java -jar target/musichub-0.0.1-SNAPSHOT.jar
```

服务默认启动在 `http://localhost:8080`。

## 📁 项目结构

```
src/main/java/com/musichub
├── common          # 统一响应 Result
├── config          # 跨域 / MinIO / MyBatis-Plus / RestTemplate 配置
├── controller      # REST 接口层
├── dto             # 请求 / 响应对象
├── entity          # 数据库实体
├── interceptor     # JWT 认证、管理员校验拦截器
├── mapper          # MyBatis-Plus Mapper
├── service         # 业务层（含 impl 实现）
│   ├── NeteaseScraperService  # 网易云歌词 / 封面刮削
│   ├── LrclibService          # Lrclib 歌词兜底
│   └── MusicScraperUtil       # 苹果 iTunes 刮削工具
├── task            # 定时任务（播放量增量入库）
└── utils           # JWT / MinIO / 用户上下文工具
```

## 🔌 核心接口

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/song/hot` | 热歌榜 Top10 |
| GET | `/song/detail?songId=` | 歌曲详情（自动补全歌词 / 封面） |
| POST | `/song/parse` | 解析音频元数据（上传表单预览） |
| POST | `/song/publish` | 发布歌曲（私人，待申请审核） |
| PUT | `/song/{id}/apply` | 私人歌曲申请发布 |
| PUT | `/song/{id}/edit` | 编辑私人 / 被拒歌曲 |
| PUT | `/song/{id}/resubmit` | 被拒歌曲重新提交审核 |
| DELETE | `/song/{id}` | 删除歌曲（同步清理 MinIO 文件） |
| GET | `/song/my` / `/song/private` | 我的歌曲列表 |
| GET | `/history/recent` | 最近播放记录 |
| POST | `/user/register` / `/user/login` | 注册 / 登录 |

## 📄 License

本项目仅供学习交流使用。
