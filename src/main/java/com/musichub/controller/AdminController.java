package com.musichub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musichub.entity.Song;
import com.musichub.entity.User;
import com.musichub.service.SongService;
import com.musichub.service.UserService;
import com.musichub.common.Result;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private UserService userService;

    @Autowired
    private SongService songService;

    // ================================
    // 分页获取用户列表
    // GET /admin/users?page=1&size=20&keyword=xxx
    // ================================
    @GetMapping("/users")
    public Result<?> getUsers(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword) {
        
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User> wrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(User::getUsername, kw)
                    .or()
                    .like(User::getEmail, kw)
                    .or()
                    .like(User::getPhone, kw)
            );
        }
        
        wrapper.orderByDesc(User::getId);
        
        Page<User> userPage = new Page<>(page, size);
        userService.page(userPage, wrapper);
        userPage.getRecords().forEach(user -> user.setPassword(null));
        return Result.success(userPage);
    }

    // ================================
    // 分页获取歌曲列表（管理员全览）
    // GET /admin/songs?page=1&size=20&keyword=xxx
    // ================================
    @GetMapping("/songs")
    public Result<?> getSongs(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) String keyword) {
        
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Song> wrapper = 
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            wrapper.and(w -> w
                    .like(Song::getSongName, kw)
                    .or()
                    .like(Song::getAlbum, kw)
            );
        }
        
        wrapper.orderByDesc(Song::getSongId);
        
        Page<Song> songPage = new Page<>(page, size);
        songService.page(songPage, wrapper);
        return Result.success(songPage);
    }

    // ================================
    // 仪表板统计
    // GET /admin/stats
    // ================================
    @GetMapping("/stats")
    public Result<?> getStats() {
        long userCount = userService.count();
        long songCount = songService.count();
        
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("totalUsers", userCount);
        stats.put("totalSongs", songCount);
        
        // 统计在线人数：查询 tb_user 表中 status=1 的用户数量
        long onlineToday = userService.lambdaQuery()
                .eq(User::getStatus, 1)
                .count();
        
        stats.put("onlineToday", onlineToday);
        
        return Result.success(stats);
    }

    // ================================
    // 封禁/解封用户
    // PUT /admin/users/{id}/status
    // ================================
    @PutMapping("/users/{id}/status")
    public Result<?> updateStatus(
            @PathVariable Long id,
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

    // ================================
    // 修改用户角色
    // PUT /admin/users/{id}/role
    // ================================
    @PutMapping("/users/{id}/role")
    public Result<?> updateRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        if (id.equals(UserHolder.getUserId())) {
            return Result.error("不能修改自己的权限");
        }
        if (!"super_admin".equals(UserHolder.getRole())) {
            return Result.error("权限不足：只有超级管理员才能修改角色");
        }
        String targetRole = body.get("role");
        if ("super_admin".equals(targetRole)) {
            return Result.error("不允许通过接口创建超级管理员");
        }
        User user = new User();
        user.setId(id);
        user.setRole(targetRole);
        userService.updateById(user);
        return Result.success("权限修改成功");
    }
}
