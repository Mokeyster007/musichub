package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musichub.common.Result;
import com.musichub.dto.*;
import com.musichub.entity.UserFavorite;
import com.musichub.entity.Song;
import com.musichub.entity.User;
import com.musichub.service.UserFavoriteService;
import com.musichub.service.SongService;
import com.musichub.service.UserService;
import com.musichub.utils.JwtUtil;
import com.musichub.utils.MinioUtil;
import com.musichub.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private SongService songService;

    @Autowired
    private UserFavoriteService favoriteService;  // ← 新增，用于查收藏数

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MinioUtil minioUtil;

    // ==================== 注册 ====================

    @PostMapping("/register")
    public Result<?> register(@RequestBody RegisterDTO dto) {

        // 用 dto.getUsername() 替代 user.getUsername()
        // role / status / createTime 全部在后端硬设置，前端无法干预
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(dto.getPassword());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setRole("user");    // 永远从后端设置，不信任前端
        user.setStatus(0);

        // 1. 基础非空校验
        if (isBlank(user.getUsername()) || isBlank(user.getPassword())) {
            return Result.fail("用户名或密码不能为空");
        }

        // 2. 邮箱格式校验
        if (isBlank(user.getEmail())) {
            return Result.fail("请输入邮箱地址");
        }
        if (!user.getEmail().matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            return Result.fail("邮箱格式不正确");
        }

        // 3. 手机号格式校验
        if (isBlank(user.getPhone())) {
            return Result.fail("请输入手机号码");
        }
        if (!user.getPhone().matches("^1[3-9]\\d{9}$")) {
            return Result.fail("手机号格式不正确");
        }

        // 4. 唯一性校验：用户名、邮箱、手机号
        if (userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, user.getUsername())) > 0) {
            return Result.fail("该用户名已被注册，请换一个");
        }
        if (userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getEmail, user.getEmail())) > 0) {
            return Result.fail("该邮箱已被注册");
        }
        if (userService.count(new LambdaQueryWrapper<User>()
                .eq(User::getPhone, user.getPhone())) > 0) {
            return Result.fail("该手机号已被注册");
        }

        // 5. 密码加密 + 初始化字段
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setRole("user");           // 默认普通用户
        user.setStatus(0);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());

        if (!userService.save(user)) {
            return Result.fail("系统异常，注册失败");
        }

        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        return Result.success(data);
    }

    // ==================== 登录 ====================

    @PostMapping("/login")
    public Result<?> login(@RequestBody LoginRequest loginRequest) {
        if (isBlank(loginRequest.getUsername()) || isBlank(loginRequest.getPassword())) {
            return Result.fail("用户名或密码不能为空");
        }

        User user = userService.getOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, loginRequest.getUsername()));

        if (user == null) {
            return Result.fail("用户不存在");
        }
        if (!passwordEncoder.matches(loginRequest.getPassword(), user.getPassword())) {
            return Result.fail("密码错误");
        }

        // 更新在线状态
        user.setStatus(1);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);

        if (user.getIsBanned() != null && user.getIsBanned() == 1) {
            return Result.error("账号已被封禁，请联系管理员");
        }

        String token = JwtUtil.generateToken(user.getId());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("username", user.getUsername());
        data.put("role", user.getRole());       // ← 登录就返回 role，前端不用再单独请求
        data.put("avatar", user.getAvatar());
        return Result.success(data);
    }

    // ==================== 登出 ====================

    /**
     * 登出：后端将用户状态改为离线
     * Token 本身由前端删除（localStorage.removeItem("token")）
     */
    @PostMapping("/logout")
    public Result<?> logout() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或 Token 已过期");
        }

        User user = new User();
        user.setId(currentUserId);
        user.setStatus(0);
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);

        return Result.success("已成功登出");
    }

    // ==================== 获取个人资料 ====================

    @GetMapping("/profile")
    public Result<?> getProfile() {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或 Token 已过期");
        }

        User user = userService.getById(currentUserId);
        if (user == null) {
            return Result.fail("用户不存在");
        }

        UserProfileVO profileVO = new UserProfileVO();
        BeanUtils.copyProperties(user, profileVO);

        // ---- 统计数据（真实查询）----
        // 上传歌曲数
        long uploadCount = songService.count(new LambdaQueryWrapper<Song>()
                .eq(Song::getUserId, currentUserId));   // ← 确认你的 Song 实体有 userId 字段
        profileVO.setUploadCount((int) uploadCount);

        // 收藏歌曲数（需要你有 FavoriteService）
        long favoriteCount = favoriteService.count(new LambdaQueryWrapper<UserFavorite>()
                .eq(UserFavorite::getUserId, currentUserId));
        profileVO.setFavoriteCount((int) favoriteCount);

        // 歌单数（暂时保留为 0，等歌单模块完成后再接真实数据）
        profileVO.setPlaylistCount(0);

        return Result.success(profileVO);
    }

    // ==================== 修改个人资料 ====================

    @PutMapping("/profile")
    public Result<?> updateProfile(@RequestBody UserUpdateDTO updateDTO) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或 Token 已过期");
        }

        // 用户名被修改时，检查新用户名是否与他人重复
        if (!isBlank(updateDTO.getUsername())) {
            long count = userService.count(new LambdaQueryWrapper<User>()
                    .eq(User::getUsername, updateDTO.getUsername())
                    .ne(User::getId, currentUserId));
            if (count > 0) {
                return Result.fail("该用户名已被使用，请换一个");
            }
        }

        User userToUpdate = new User();
        userToUpdate.setId(currentUserId);

        if (!isBlank(updateDTO.getUsername()))  userToUpdate.setUsername(updateDTO.getUsername());
        if (!isBlank(updateDTO.getSignature())) userToUpdate.setSignature(updateDTO.getSignature());
        if (!isBlank(updateDTO.getEmail()))     userToUpdate.setEmail(updateDTO.getEmail());
        if (!isBlank(updateDTO.getPhone()))     userToUpdate.setPhone(updateDTO.getPhone());
        if (!isBlank(updateDTO.getAvatar()))    userToUpdate.setAvatar(updateDTO.getAvatar());
        if (!isBlank(updateDTO.getBgCover()))   userToUpdate.setBgCover(updateDTO.getBgCover());

        userToUpdate.setUpdateTime(LocalDateTime.now());

        return userService.updateById(userToUpdate)
                ? Result.success("个人资料修改成功")
                : Result.fail("修改失败，请稍后重试");
    }

    // ==================== 修改密码 ====================

    /**
     * 修改密码
     * ChangePasswordDTO 包含：oldPassword, newPassword
     */
    @PutMapping("/password")
    public Result<?> changePassword(@RequestBody ChangePasswordDTO dto) {
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) {
            return Result.fail("未登录或 Token 已过期");
        }
        if (isBlank(dto.getOldPassword()) || isBlank(dto.getNewPassword())) {
            return Result.fail("旧密码和新密码不能为空");
        }
        if (dto.getNewPassword().length() < 6) {
            return Result.fail("新密码长度不能少于 6 位");
        }

        User user = userService.getById(currentUserId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            return Result.fail("旧密码不正确");
        }

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        user.setUpdateTime(LocalDateTime.now());

        return userService.updateById(user)
                ? Result.success("密码修改成功，请重新登录")
                : Result.fail("修改失败，请稍后重试");
    }

    // ==================== 上传头像 / 背景图 ====================

    @PostMapping("/upload/image")
    public Result<?> uploadImage(@RequestParam("file") MultipartFile file,
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
            User user = userService.getById(currentUserId);
            if (user == null) {
                return Result.fail("用户不存在");
            }

            // 决定目标文件夹
            String targetFolder = "avatar".equals(type) ? "users/" : "usercovers/";
            String oldUrl       = "avatar".equals(type) ? user.getAvatar() : user.getBgCover();

            // 上传新图片
            String newUrl = minioUtil.upload(file, targetFolder);

            // 更新数据库
            if ("avatar".equals(type)) {
                user.setAvatar(newUrl);
            } else {
                user.setBgCover(newUrl);
            }
            user.setUpdateTime(LocalDateTime.now());

            if (!userService.updateById(user)) {
                return Result.fail("数据库更新失败");
            }

            // 删除旧图（安全检查：只删自己桶里的文件）
            if (!isBlank(oldUrl) && oldUrl.contains("vibe-music-data")) {
                minioUtil.deleteFile(oldUrl);
            }

            return Result.success(newUrl);

        } catch (Exception e) {
            e.printStackTrace();
            return Result.fail("文件上传失败：" + e.getMessage());
        }
    }

    // ==================== 私有工具方法 ====================

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}