# Musichub 项目逻辑总总结

这份文档不再只给代码位置，而是直接展示项目里的关键实现代码，并配合业务逻辑、JVM、JUC 和 Redis 一起解释。

## 1. 项目定位

Musichub 是一个 Spring Boot 音乐平台后端，核心能力包括：

- 用户注册、登录、退出、资料修改、头像/背景图上传
- 歌曲上传、解析、发布、编辑、删除、审核
- 歌手信息查询与自动刮削补全
- 评论、点赞、收藏、播放历史、个人听歌报告
- 歌单管理、歌曲归档、首页推荐、搜索
- 管理后台：用户管理、歌曲审核、歌曲上下架、平台统计

项目最重要的支撑技术是：JWT 鉴权、ThreadLocal 请求上下文、Redis 热歌榜、MinIO 对象存储、MyBatis Plus 持久化、RestTemplate 外部数据刮削。

## 2. 启动与基础配置

### 2.1 启动入口

```java
@SpringBootApplication
@MapperScan("com.musichub.mapper")
@EnableScheduling
public class MusichubApplication {

	public static void main(String[] args) {
        // 启动 Spring Boot 容器，加载所有 Bean 和自动配置
		SpringApplication.run(MusichubApplication.class, args);
	}

}
```

含义很直接：扫描 Mapper、启用定时任务、启动 Spring 容器。

### 2.2 核心配置

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/musichub?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: your-db-password

  redis:
    host: localhost
    port: 6379
    database: 0

file:
  upload-dir: D:/music-project-files/

minio:
  endpoint: http://localhost:9000
  access-key: your-minio-access-key
  secret-key: your-minio-secret-key
  bucket-name: vibe-music-data
```

这说明项目的数据层、缓存层、对象存储已经全部接入。

## 3. 鉴权链路

### 3.1 JWT 工具

```java
public class JwtUtil {

    // 签名密钥已改为从 application.yml 的 jwt.secret 配置读取（HS256 要求密钥足够长）
    @Value("${jwt.secret}")
    public void initSecret(String secret) { ... }
    // Token 默认有效期 24 小时
    private static final long EXPIRATION_TIME = 24 * 60 * 60 * 1000;

    public static String generateToken(Long userId) {
        return Jwts.builder()
                // 只保存用户 ID，不把密码等敏感信息写进 Token
                .setSubject(String.valueOf(userId))
                // 签发时间，帮助判断 Token 生命周期
                .setIssuedAt(new Date())
                // 过期时间，防止 Token 长期有效
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRATION_TIME))
                // 使用 HS256 签名，保证 token 不可伪造
                .signWith(SECRET_KEY, SignatureAlgorithm.HS256)
                .compact();
    }

    public static Long getUserIdFromToken(String token) {
        try {
            Claims claims = Jwts.parserBuilder()
                    // 用同一把密钥验签和解密
                    .setSigningKey(SECRET_KEY)
                    .build()
                    // 这里如果 token 被篡改或过期，会直接抛异常
                    .parseClaimsJws(token)
                    .getBody();
            // 从 subject 里取回用户 ID
            return Long.parseLong(claims.getSubject());
        } catch (Exception e) {
            // 解析失败：token 无效、过期或伪造
            return null;
        }
    }
}
```

这个设计只把 userId 放进 token，不放敏感信息。解析成功后即可恢复用户身份。

### 3.2 ThreadLocal 请求上下文

```java
public class UserHolder {

    // 当前请求线程独享的用户 ID
    private static final ThreadLocal<Long> USER_ID = new ThreadLocal<>();
    // 当前请求线程独享的角色信息
    private static final ThreadLocal<String> USER_ROLE = new ThreadLocal<>();

    // 拦截器在认证成功后写入 userId
    public static void setUserId(Long userId) { USER_ID.set(userId); }
    // 业务代码随时读取当前登录用户
    public static Long getUserId() { return USER_ID.get(); }
    // 请求结束必须清理，避免线程池复用造成串号
    public static void removeUserId() { USER_ID.remove(); }

    // 拦截器在认证成功后写入角色
    public static void setRole(String role) { USER_ROLE.set(role); }
    // 获取当前角色
    public static String getRole() { return USER_ROLE.get(); }
    // 请求结束后清理角色信息
    public static void removeRole() { USER_ROLE.remove(); }

    public static boolean isAdmin() {
        String role = USER_ROLE.get();
        // admin 和 super_admin 都可以进入后台
        return "admin".equals(role) || "super_admin".equals(role);
    }

    public static boolean isSuperAdmin() {
        // 只允许超级管理员做高危操作，比如改角色
        return "super_admin".equals(USER_ROLE.get());
    }
}
```

这是典型的 JUC 用法：请求线程里放入上下文，请求结束后清理，避免线程池复用造成串号。

### 3.3 JWT 拦截器

```java
@Component
public class JwtInterceptor implements HandlerInterceptor {

    @Autowired
    // 需要查库确认用户仍存在、未被封禁
    private UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 前端把 JWT 放在 Authorization 请求头里传入
        String token = request.getHeader("Authorization");
        if (token == null || token.trim().isEmpty()) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或 Token 已过期\"}");
            return false;
        }

        try {
            // 从 token 中解析出 userId
            Long userId = JwtUtil.getUserIdFromToken(token);
            if (userId == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"Token 无效\"}");
                return false;
            }

            // 用 userId 再查一次数据库，避免伪造 token
            User user = userService.getById(userId);
            if (user == null) {
                response.setStatus(401);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"用户不存在\"}");
                return false;
            }

            // 被封禁账号直接拦截
            if (user.getIsBanned() != null && user.getIsBanned() == 1) {
                response.setStatus(403);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"账号已被封禁，请联系管理员\"}");
                return false;
            }

            // 写入 ThreadLocal，后续控制器直接读取当前用户信息
            UserHolder.setUserId(userId);
            UserHolder.setRole(user.getRole());
            return true;
        } catch (Exception e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Token 解析失败\"}");
            return false;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request,
                                HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束清理 ThreadLocal，防止线程池复用时数据污染
        UserHolder.removeUserId();
        UserHolder.removeRole();
    }
}
```

### 3.4 管理员拦截器

```java
@Component
public class AdminCheckInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        if (!UserHolder.isAdmin()) {
            // 非管理员直接返回 403
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"权限不足，需要管理员身份\"}");
            return false;
        }
        return true;
    }
}
```

先做登录态校验，再做角色校验，权限层次清晰。

## 4. 用户模块

### 4.1 注册

```java
@PostMapping("/register")
public Result register(@RequestBody RegisterDTO dto) {

    // 把 DTO 映射成实体，后续再做校验和入库
    User user = new User();
    user.setUsername(dto.getUsername());
    user.setPassword(dto.getPassword());
    user.setEmail(dto.getEmail());
    user.setPhone(dto.getPhone());
    user.setRole("user");
    user.setStatus(0);

    // 用户名和密码不能为空
    if (isBlank(user.getUsername()) || isBlank(user.getPassword())) {
        return Result.fail("用户名或密码不能为空");
    }

    // 校验邮箱格式
    if (isBlank(user.getEmail())) {
        return Result.fail("请输入邮箱地址");
    }
    if (!user.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
        return Result.fail("邮箱格式不正确");
    }

    // 校验手机号格式
    if (isBlank(user.getPhone())) {
        return Result.fail("请输入手机号码");
    }
    if (!user.getPhone().matches("^1[3-9]\\d{9}$")) {
        return Result.fail("手机号格式不正确");
    }

    // 用户名、邮箱、手机号都要唯一
    if (userService.count(new LambdaQueryWrapper<User>().eq(User::getUsername, user.getUsername())) > 0) {
        return Result.fail("该用户名已被注册，请换一个");
    }
    if (userService.count(new LambdaQueryWrapper<User>().eq(User::getEmail, user.getEmail())) > 0) {
        return Result.fail("该邮箱已被注册");
    }
    if (userService.count(new LambdaQueryWrapper<User>().eq(User::getPhone, user.getPhone())) > 0) {
        return Result.fail("该手机号已被注册");
    }

    // 密码必须加密后入库，不能保存明文
    user.setPassword(passwordEncoder.encode(user.getPassword()));
    user.setRole("user");
    user.setStatus(0);
    user.setCreateTime(LocalDateTime.now());
    user.setUpdateTime(LocalDateTime.now());

    // 保存成功后只返回必要信息
    if (!userService.save(user)) {
        return Result.fail("系统异常，注册失败");
    }

    Map<String, Object> data = new HashMap<>();
    data.put("userId", user.getId());
    data.put("username", user.getUsername());
    return Result.success(data);
}
```

这段代码体现了三层校验：格式校验、唯一性校验、密码加密。

### 4.2 登录

```java
@PostMapping("/login")
public Result login(@RequestBody LoginRequest loginRequest) {
    // 先判断前端有没有传账号密码
    if (isBlank(loginRequest.getUsername()) || isBlank(loginRequest.getPassword())) {
        return Result.fail("用户名或密码不能为空");
    }

    // 用用户名查用户，用户名是登录主键
    User user = userService.getOne(new LambdaQueryWrapper<User>()
            .eq(User::getUsername, loginRequest.getUsername()));

    if (user == null) {
        return Result.fail("用户不存在");
    }
    // BCrypt 匹配，不能直接字符串比较
    if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
        return Result.fail("密码错误");
    }

    // 登录成功后更新在线状态
    user.setStatus(1);
    user.setUpdateTime(LocalDateTime.now());
    userService.updateById(user);

    // 封禁用户即使密码正确也不允许登录
    if (user.getIsBanned() != null && user.getIsBanned() == 1) {
        return Result.error("账号已被封禁，请联系管理员");
    }

    // 生成 JWT，交给前端保存
    String token = JwtUtil.generateToken(user.getId());

    // 返回前端最需要的信息
    Map<String, Object> data = new HashMap<>();
    data.put("token", token);
    data.put("username", user.getUsername());
    data.put("role", user.getRole());
    data.put("avatar", user.getAvatar());
    return Result.success(data);
}
```

登录后把 token 返回给前端，前端后续请求都带上它。

### 4.3 资料与头像上传

```java
@PutMapping("/profile")
public Result updateProfile(@RequestBody UserUpdateDTO updateDTO) {
    Long currentUserId = UserHolder.getUserId();
    if (currentUserId == null) {
        return Result.fail("未登录或 Token 已过期");
    }

    // 修改用户名时，先检查是否和别人重复
    if (!isBlank(updateDTO.getUsername())) {
        long count = userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, updateDTO.getUsername())
                .ne(User::getId, currentUserId));
        if (count > 0) {
            return Result.fail("该用户名已被使用，请换一个");
        }
    }

    // 只更新允许修改的字段，避免越权修改密码、角色等字段
    User userToUpdate = new User();
    userToUpdate.setId(currentUserId);
    if (!isBlank(updateDTO.getUsername()))  userToUpdate.setUsername(updateDTO.getUsername());
    if (!isBlank(updateDTO.getSignature())) userToUpdate.setSignature(updateDTO.getSignature());
    if (!isBlank(updateDTO.getEmail()))     userToUpdate.setEmail(updateDTO.getEmail());
    if (!isBlank(updateDTO.getPhone()))     userToUpdate.setPhone(updateDTO.getPhone());
    if (!isBlank(updateDTO.getAvatar()))    userToUpdate.setAvatar(updateDTO.getAvatar());
    if (!isBlank(updateDTO.getBgCover()))   userToUpdate.setBgCover(updateDTO.getBgCover());

    // 更新时间用于记录资料最后修改时间
    userToUpdate.setUpdateTime(LocalDateTime.now());

    return userService.updateById(userToUpdate)
            ? Result.success("个人资料修改成功")
            : Result.fail("修改失败，请稍后重试");
}
```

```java
@PostMapping("/upload/image")
public Result uploadImage(@RequestParam("file") MultipartFile file,
                          @RequestParam("type") String type) {
    Long currentUserId = UserHolder.getUserId();
    if (currentUserId == null) {
        return Result.fail("未登录或 Token 已过期");
    }
    if (file.isEmpty()) {
        return Result.fail("上传的图片不能为空");
    }
    if (!"avatar".equals(type) && !"bgCover".equals(type)) {
        return Result.fail("未知的上传类型，仅支持 avatar / bgCover");
    }

    try {
        // 先读取当前用户，拿到旧头像或旧背景图
        User user = userService.getById(currentUserId);
        // 根据 type 决定上传目录
        String targetFolder = "avatar".equals(type) ? "users/" : "usercovers/";
        // 记录旧文件，更新成功后可删除
        String oldUrl = "avatar".equals(type) ? user.getAvatar() : user.getBgCover();

        // 上传到 MinIO，获得新地址
        String newUrl = minioUtil.upload(file, targetFolder);

        if ("avatar".equals(type)) {
            user.setAvatar(newUrl);
        } else {
            user.setBgCover(newUrl);
        }
        user.setUpdateTime(LocalDateTime.now());

        // 数据库更新成功后再删除旧文件，避免误删
        if (!userService.updateById(user)) {
            return Result.fail("数据库更新失败");
        }

        // 只删除当前系统桶里的文件，外链不处理
        if (!isBlank(oldUrl) && oldUrl.contains("vibe-music-data")) {
            minioUtil.deleteFile(oldUrl);
        }

        return Result.success(newUrl);
    } catch (Exception e) {
        return Result.fail("文件上传失败：" + e.getMessage());
    }
}
```

这里体现了对象存储的业务意义：先上传新文件，再替换数据库地址，最后删除旧文件。

## 5. 歌曲模块

### 5.1 发布歌曲

```java
@PostMapping("/publish")
public Result publishSong(
        @RequestParam("file") MultipartFile file,
        @RequestParam("name") String name,
        @RequestParam(value = "singer", required = false) String singer,
        @RequestParam(value = "album", required = false) String album,
        @RequestParam(value = "pic", required = false) String pic,
        @RequestParam(value = "lyric", required = false) String lyric,
        @RequestParam(value = "duration", required = false) String duration
) {
    Long currentUserId = UserHolder.getUserId();
    if (currentUserId == null) return Result.fail("用户未登录");
    if (file.isEmpty() || name == null || name.trim().isEmpty()) {
        return Result.fail("音频文件和歌曲名称不能为空");
    }

    try {
        // Step 1：先把音频文件上传到 MinIO
        String originalFilename = file.getOriginalFilename();
        String suffix = (originalFilename != null && originalFilename.contains("."))
                ? originalFilename.substring(originalFilename.lastIndexOf(".")) : ".mp3";
        String audioObjectName = "songs/" + UUID.randomUUID() + suffix;
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(BUCKET_NAME).object(audioObjectName)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType()).build());
        String finalAudioUrl = minioEndpoint + "/" + BUCKET_NAME + "/" + audioObjectName;

        // Step 2：处理封面，优先使用前端传来的 pic
        String finalCoverUrl = null;
        if (pic != null && !pic.trim().isEmpty()) {
            if (pic.startsWith("data:image")) {
                // Base64 封面直接转存 MinIO
                finalCoverUrl = resolveCoverUrl(pic);
            } else if (pic.startsWith("http")) {
                // 外链封面先下载，再上传到 MinIO
                finalCoverUrl = downloadAndUploadCoverToMinio(pic);
            }
        }

        // Step 3：如果封面仍然为空，调用刮削逻辑兜底
        if (finalCoverUrl == null || finalCoverUrl.trim().isEmpty()) {
            String scrapedCover = null;
            try {
                scrapedCover = neteaseScraperService.fetchSongCover(name.trim(), singer);
            } catch (Exception ignored) {}

            if (scrapedCover == null || scrapedCover.trim().isEmpty()) {
                MusicScraperUtil.ScrapedData scraped = MusicScraperUtil.scrapeFromApple(name.trim(), singer);
                if (scraped != null) scrapedCover = scraped.getCoverUrl();
            }

            if (scrapedCover != null && !scrapedCover.trim().isEmpty()) {
                // 刮削得到外链后，再下载并上传到 MinIO，保证数据库存稳定地址
                finalCoverUrl = downloadAndUploadCoverToMinio(scrapedCover);
            }
        }

        // Step 4：解析歌手，存在就复用，不存在就新建
        Long targetArtistId = null;
        if (singer != null && !singer.trim().isEmpty()) {
            targetArtistId = resolveArtistId(singer.trim(), finalCoverUrl);
        }

        // Step 5：组装歌曲实体写库
        Song newSong = new Song();
        newSong.setSongName(name.trim());
        newSong.setUserId(currentUserId);
        newSong.setStatus(0);
        if (targetArtistId != null) newSong.setArtistId(targetArtistId);
        newSong.setAlbum(album);
        newSong.setAudioUrl(finalAudioUrl);
        newSong.setCoverUrl(finalCoverUrl);
        if (duration != null) newSong.setDuration(duration);
        newSong.setReleaseTime(LocalDate.now());
        newSong.setLyric(lyric);
        newSong.setPlayCount(0L);

        if (!songService.save(newSong)) return Result.fail("写入数据库失败");

        // 返回前端的上传结果，不直接暴露实体对象
        Map<String, Object> returnData = new HashMap<>();
        returnData.put("songId", newSong.getSongId());
        returnData.put("songName", newSong.getSongName());
        returnData.put("coverUrl", newSong.getCoverUrl());
        returnData.put("audioUrl", newSong.getAudioUrl());
        returnData.put("lyric", newSong.getLyric());
        returnData.put("status", 0);
        return Result.success(returnData);
    } catch (Exception e) {
        return Result.fail("发布失败：" + e.getMessage());
    }
}
```

这段是核心写库路径：音频先存 MinIO，再写数据库 URL；封面和歌手也会尽量归一化处理。

### 5.2 播放记录与排行榜

```java
@PostMapping("/recordPlay")
public Result recordPlay(@RequestParam("songId") Long songId) {
    if (songId == null) return Result.fail("歌曲ID不能为空");
    try {
        // 先查歌曲，确认 ID 合法
        Song song = songService.getById(songId);
        if (song == null) return Result.fail("歌曲不存在");

        // 只有公开歌才进入 Redis 排行榜
        if (Objects.equals(song.getStatus(), Song.STATUS_PUBLIC)) {
            stringRedisTemplate.opsForZSet().incrementScore(SONG_RANK_TOTAL_KEY, String.valueOf(songId), 1);
            stringRedisTemplate.opsForZSet().incrementScore(SONG_RANK_DELTA_KEY, String.valueOf(songId), 1);
        }

        // 数据库里的总播放量直接自增
        songService.lambdaUpdate()
                .eq(Song::getSongId, songId)
                .setSql("play_count = play_count + 1")
                .update();

        // 登录用户额外写播放历史，用于最近播放和报表
        Long userId = UserHolder.getUserId();
        if (userId != null) {
            LocalDate today = LocalDate.now();
            LambdaQueryWrapper<PlayHistory> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(PlayHistory::getUserId, userId)
                    .eq(PlayHistory::getSongId, songId)
                    .eq(PlayHistory::getPlayDate, today);
            PlayHistory history = playHistoryService.getOne(wrapper);
            if (history == null) {
                // 同一天第一次播放，新增历史记录
                history = new PlayHistory();
                history.setUserId(userId);
                history.setSongId(songId);
                history.setPlayDate(today);
                history.setPlayCount(1);
                history.setPlayTime(LocalDateTime.now());
                playHistoryService.save(history);
            } else {
                // 同一天重复播放，只累加次数并刷新最近播放时间
                history.setPlayCount(history.getPlayCount() + 1);
                history.setPlayTime(LocalDateTime.now());
                playHistoryService.updateById(history);
            }
        }
        return Result.success("播放记录成功");
    } catch (Exception e) {
        return Result.fail("记录播放失败：" + e.getMessage());
    }
}
```

公开歌曲会进 Redis 榜单，所有歌曲都会累计播放量，登录用户还会生成播放历史。

### 5.3 热歌榜读取

```java
private List<Map<String, Object>> buildTopSongList(int limit) {
    Set<ZSetOperations.TypedTuple<String>> topSongs =
            stringRedisTemplate.opsForZSet().reverseRangeWithScores(SONG_RANK_TOTAL_KEY, 0, limit - 1);

    List<Map<String, Object>> resultList = new ArrayList<>();
    if (topSongs == null || topSongs.isEmpty()) return resultList;

    for (ZSetOperations.TypedTuple<String> tuple : topSongs) {
        String songIdStr = tuple.getValue();
        if (songIdStr == null) continue;
    // 再去数据库确认歌曲存在且仍是公开状态
        Song song = songService.getById(Long.parseLong(songIdStr));
        if (song == null || song.getStatus() == null || song.getStatus() != 2) continue;

    // 组装给前端展示的榜单数据
        String singerName = resolveSingerName(song.getArtistId());
        Map<String, Object> map = new HashMap<>();
        map.put("songId", song.getSongId());
        map.put("songName", song.getSongName());
        map.put("coverUrl", song.getCoverUrl());
        map.put("audioUrl", song.getAudioUrl());
        map.put("album", song.getAlbum());
        map.put("duration", song.getDuration());
        map.put("singerName", singerName);
        map.put("playCount", tuple.getScore() != null ? tuple.getScore().longValue() : 0);
        resultList.add(map);
    }
    return resultList;
}
```

Redis ZSet 的 score 就是播放量，所以榜单读取时是按分数倒序拿。

## 6. 评论、收藏、歌单与后台

### 6.1 评论发布

```java
@PostMapping("/add")
public Map<String, Object> addComment(@RequestBody Comment comment) {
    Map<String, Object> response = new HashMap<>();

    // 当前登录用户必须从后端上下文取，不能信任前端传来的 userId
    Long currentUserId = UserHolder.getUserId();
    if (currentUserId == null) {
        response.put("code", 401);
        response.put("message", "发表失败：您还未登录或登录已过期！");
        return response;
    }
    // 强制把评论作者改成真实登录用户
    comment.setUserId(currentUserId);

    // 必须指定评论哪首歌
    if (comment.getSongId() == null) {
        response.put("code", 400);
        response.put("message", "发表失败：未指定要评论的歌曲！");
        return response;
    }
    // 检查歌曲是否存在，防止前端乱传 ID
    Song targetSong = songService.getById(comment.getSongId());
    if (targetSong == null) {
        response.put("code", 400);
        response.put("message", "发表失败：该歌曲不存在或已被下架！");
        return response;
    }

    // 空评论直接拒绝
    if (comment.getContent() == null || comment.getContent().trim().isEmpty()) {
        response.put("code", 400);
        response.put("message", "发表失败：评论内容不能为空！");
        return response;
    }

    // 后端补充发布时间、点赞数、评论类型
    comment.setCreateTime(LocalDateTime.now());
    comment.setLikeCount(0);
    comment.setType(0);

    // 保存评论
    boolean result = commentService.save(comment);
    response.put("code", result ? 200 : 500);
    response.put("message", result ? "评论发布成功！" : "系统异常，评论发布失败！");
    return response;
}
```

评论作者必须由后端决定，避免前端伪造用户身份。

### 6.2 评论联表分页

```java
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    @Select("SELECT c.*, u.username, u.user_avatar AS avatar " +
            "FROM tb_comment c " +
            "LEFT JOIN tb_user u ON c.user_id = u.id " +
            "WHERE c.song_id = #{songId} " +
            "ORDER BY c.create_time DESC")
    // 联表分页：评论表 + 用户表，一次查出评论内容和作者信息
    Page<CommentDTO> selectCommentWithUserPage(Page<CommentDTO> page, @Param("songId") Long songId);
}
```

### 6.3 收藏

```java
@PostMapping("/add")
public Result<String> addCollect(@RequestBody UserFavorite favorite) {
    Long userId = UserHolder.getUserId();
    if (userId == null) {
        return Result.error("未登录");
    }

    // 收藏归属当前用户，type=0 表示收藏歌曲
    favorite.setUserId(userId);
    favorite.setType(0);
    favorite.setCreateTime(LocalDateTime.now());

    // 先查重，避免重复收藏同一首歌
    QueryWrapper<UserFavorite> wrapper = new QueryWrapper<>();
    wrapper.eq("user_id", userId)
            .eq("type", 0)
            .eq("song_id", favorite.getSongId());

    long count = userFavoriteService.count(wrapper);
    if (count > 0) {
        return Result.success("你已经收藏过这首歌曲啦！");
    }

    // 只有没有收藏过才插入新记录
    boolean result = userFavoriteService.save(favorite);
    return result ? Result.success("收藏成功！红心点亮！") : Result.error("操作失败！");
}
```

收藏本质是关系表，先查重再写入。

### 6.4 歌单创建

```java
@PostMapping(value = "/create", consumes = "multipart/form-data")
public Result createPlaylist(
        @RequestParam("title") String title,
        @RequestParam(value = "introduction", required = false) String introduction,
        @RequestParam(value = "cover", required = false) MultipartFile cover
) {
    Long currentUserId = UserHolder.getUserId();
    if (currentUserId == null) return Result.fail("用户未登录");
    if (title == null || title.trim().isEmpty()) return Result.fail("歌单名称不能为空");

    try {
        // 新建歌单实体，归属当前用户
        Playlist playlist = new Playlist();
        playlist.setUserId(currentUserId);
        playlist.setTitle(title.trim());
        playlist.setIntroduction(introduction);
        playlist.setCreateTime(new Date());

        // 默认封面，上传前如果没传图就用这个兜底
        String finalCoverUrl = "https://cdn.vuetifyjs.com/images/cards/cooking.png";
        if (cover != null && !cover.isEmpty()) {
            // 有封面时上传到 MinIO
            String originalFilename = cover.getOriginalFilename();
            String suffix = (originalFilename != null && originalFilename.contains("."))
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".png";
            String objectName = "playlist/playlist_" + System.currentTimeMillis() + suffix;
            minioClient.putObject(
                    io.minio.PutObjectArgs.builder()
                            .bucket(BUCKET_NAME)
                            .object(objectName)
                            .stream(cover.getInputStream(), cover.getSize(), -1)
                            .contentType(cover.getContentType())
                            .build()
            );
            finalCoverUrl = "http://localhost:9000/" + BUCKET_NAME + "/" + objectName;
        }

        // 记录最终封面地址后入库
        playlist.setCoverUrl(finalCoverUrl);
        boolean success = playlistService.save(playlist);
        return success ? Result.success("歌单创建成功") : Result.fail("创建失败");
    } catch (Exception e) {
        return Result.fail("创建失败：" + e.getMessage());
    }
}
```

### 6.5 管理后台

```java
@PutMapping("/users/{id}/status")
public Result updateStatus(@PathVariable Long id,
                           @RequestBody Map<String, Integer> body) {
    if (id.equals(UserHolder.getUserId())) {
        return Result.error("不能操作自己的账号");
    }
    String operatorRole = UserHolder.getRole();
    User targetUser = userService.getById(id);
    if (targetUser == null) return Result.error("用户不存在");
    if ("admin".equals(operatorRole)) {
        if ("admin".equals(targetUser.getRole()) || "super_admin".equals(targetUser.getRole())) {
            return Result.error("权限不足：无法操作同级或更高级别的管理员");
        }
    }
    Integer isBanned = body.get("isBanned");
    User user = new User();
    user.setId(id);
    user.setIsBanned(isBanned);
    userService.updateById(user);
    return Result.success("状态更新成功");
}
```

```java
@PutMapping("/{id}/approve")
public Result approveSong(@PathVariable Long id) {
    // 先查歌曲状态，确保只能审核待审核歌曲
    Song song = songService.getById(id);
    if (song == null) return Result.fail("歌曲不存在");
    if (song.getStatus() == null || song.getStatus() != Song.STATUS_AUDITING) {
        return Result.fail("该歌曲不在待审核状态");
    }

    // 审核通过：改成公开，并清空驳回原因
    LambdaUpdateWrapper<Song> updateWrapper = new LambdaUpdateWrapper<>();
    updateWrapper.eq(Song::getSongId, id)
            .set(Song::getStatus, Song.STATUS_PUBLIC)
            .set(Song::getRejectReason, null);
    songService.update(updateWrapper);

    return Result.success("审核通过，歌曲已发布到平台");
}
```

后台审核是状态机的主控入口。

## 7. 实体设计

### 7.1 Song

```java
@Data
@Accessors(chain = true)
@TableName(value = "tb_song", autoResultMap = true)
public class Song implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    // 歌曲主键
    private Long songId;

    @TableField("artist_id")
    // 关联歌手表
    private Long artistId;

    @TableField("name")
    // 歌曲名
    private String songName;

    @TableField("album")
    // 专辑名
    private String album;

    @TableField("lyric")
    // 歌词内容
    private String lyric;

    @TableField("duration")
    // 音频时长，通常是 mm:ss 格式
    private String duration;

    @TableField("cover_url")
    // 封面图地址，统一保存 MinIO URL
    private String coverUrl;

    @TableField("audio_url")
    // 音频文件地址，统一保存 MinIO URL
    private String audioUrl;

    @TableField("release_time")
    // 发布时间
    private LocalDate releaseTime;

    @TableField("play_count")
    // 总播放量
    private Long playCount;

    @TableField("user_id")
    // 上传者 ID，平台歌可为空
    private Long userId;

    @TableField("status")
    // 状态：0 私人 / 1 待审核 / 2 已发布 / 3 已拒绝
    private Integer status;

    @TableField("reject_reason")
    // 拒绝原因，审核不通过时由管理员填写
    private String rejectReason;

    // 业务状态常量，避免代码里直接写魔法数字
    public static final int STATUS_PRIVATE = 0;
    public static final int STATUS_AUDITING = 1;
    public static final int STATUS_PUBLIC = 2;
    public static final int STATUS_REJECTED = 3;
}
```

状态字段是整个业务最关键的约束条件。

### 7.2 User

```java
@Data
@TableName("tb_user")
public class User {

    // 用户主键
    private Long id;
    // 登录用户名
    private String username;
    // BCrypt 加密后的密码
    private String password;
    // 邮箱
    private String email;
    // 手机号
    private String phone;

    @TableField("user_avatar")
    // 用户头像地址
    private String avatar;

    @TableField("create_time")
    // 创建时间
    private LocalDateTime createTime;

    @TableField("update_time")
    // 最近更新时间
    private LocalDateTime updateTime;

    @TableField("status")
    // 在线状态：0 离线 / 1 在线
    private Integer status;

    @TableField("signature")
    // 个性签名
    private String signature;

    @TableField("bg_cover")
    // 主页背景图
    private String bgCover;

    @TableField("role")
    // 角色：user / admin / super_admin
    private String role;

    @TableField("is_banned")
    // 是否封禁：0 正常 / 1 封禁
    private Integer isBanned;
}
```

## 8. JVM、JUC、Redis 知识映射

### 8.1 JVM

- `ThreadLocal` 的数据挂在当前线程上，线程池复用时必须清理，否则会有脏数据和内存残留风险。
- `ByteArrayInputStream`、JSON 对象、临时文件等短生命周期对象由 GC 管理。
- Spring Bean 是容器托管对象，生命周期由 IOC 容器控制。

### 8.2 JUC

- 当前项目最核心的 JUC 知识是 `ThreadLocal`。
- 它解决的是“请求上下文跨方法传递”问题。
- 典型流程是：拦截器写入，业务层读取，请求结束清理。

### 8.3 Redis

- Redis 使用 ZSet 存热歌榜和增量播放量。
- `incrementScore` 实现原子自增。
- `reverseRangeWithScores` 读取排行榜。
- `rangeWithScores` 读取增量榜给定时任务同步回 MySQL。

## 9. 读代码的顺序建议

1. 先看 JWT、拦截器、ThreadLocal，理解登录态怎么传递。
2. 再看歌曲发布、播放、热榜，理解核心业务主线。
3. 然后看评论、收藏、歌单、历史，理解用户互动模块。
4. 最后看后台审核、封禁和统计，理解管理侧逻辑。

如果你需要，我可以继续把这份文档扩展成“每个接口都贴完整方法体”的版本，或者整理成“面试背诵版”。
