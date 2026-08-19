package com.musichub.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musichub.dto.CommentDTO;
import com.musichub.entity.Comment;
import com.musichub.entity.CommentLike;
import com.musichub.entity.Song;
import com.musichub.service.CommentLikeService;
import com.musichub.service.CommentService;
import com.musichub.service.SongService;
import com.musichub.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 评论模块控制器
 * 负责处理前端关于评论的所有请求（如发布评论、查询评论列表等）
 * @RestController 会自动将返回的 Map 或对象转换成 JSON 格式发给前端
 */
@RestController
@RequestMapping("/comment") // 这个控制器下所有接口的路径前缀都是 /comment
public class CommentController {

    // 注入评论的 Service 层，用来执行评论表的增删改查
    @Autowired
    private CommentService commentService;

    // 注入歌曲的 Service 层，用来在发评论前，检查歌曲到底存不存在
    @Autowired
    private SongService songService;

    @Autowired
    private CommentLikeService commentLikeService;
    // 点赞模块


    /**
     * 1. 发布评论接口
     * 请求方式：POST
     * 请求路径：/comment/add
     * @param comment 前端传过来的评论数据（只需要包含 songId 和 content 即可）
     */
    @PostMapping("/add")
    public Map<String, Object> addComment(@RequestBody Comment comment) {
        // 创建一个统一的返回对象，方便前端解析
        Map<String, Object> response = new HashMap<>();

        // 【安全校验 1：获取真实用户身份】
        // 核心亮点：我们不信任前端传过来的 userId（防止抓包篡改）。
        // 而是直接从 ThreadLocal（UserHolder）中获取。
        // 这个 ID 是 JWT 拦截器在验证 Token 成功后，自动帮你存进去的绝对真实的 ID。
        Long currentUserId = UserHolder.getUserId();

        // 如果拦截器配得好，其实下面这个 if 一般不会触发，但为了代码的健壮性，加上双保险
        if (currentUserId == null) {
            response.put("code", 401);
            response.put("message", "发表失败：您还未登录或登录已过期！");
            return response;
        }
        // 将绝对真实的用户 ID 赋值给评论对象，准备存入数据库
        comment.setUserId(currentUserId);

        // 【业务校验 2：检查歌曲合法性】
        // 如果前端连歌曲 ID 都没传，直接拒绝
        if (comment.getSongId() == null) {
            response.put("code", 400);
            response.put("message", "发表失败：未指定要评论的歌曲！");
            return response;
        }
        // 去数据库查一下，这首歌到底存不存在（防止前端传一个乱造的歌曲 ID）
        Song targetSong = songService.getById(comment.getSongId());
        if (targetSong == null) {
            response.put("code", 400);
            response.put("message", "发表失败：该歌曲不存在或已被下架！");
            return response;
        }

        // 【业务校验 3：防止空评论】
        // 检查评论内容是否为空，或者是不是全都是空格（trim() 可以去掉字符串两端的空格）
        if (comment.getContent() == null || comment.getContent().trim().isEmpty()) {
            response.put("code", 400);
            response.put("message", "发表失败：评论内容不能为空！");
            return response;
        }

        // 【数据补全】
        // 前端发评论时不会传时间和点赞数，我们需要在后端自动帮它补全
        comment.setCreateTime(LocalDateTime.now()); // 获取当前系统时间作为发布时间
        comment.setLikeCount(0);                    // 新发布的评论，点赞数默认为 0
        comment.setType(0);                         // 设置评论类型：0=歌曲评论

        // 【持久化操作】
        // 所有校验通过，调用 MyBatis-Plus 的 save 方法，把一条完整的记录存入数据库
        boolean result = commentService.save(comment);

        // 根据数据库操作的结果，给前端返回对应的信息
        if (result) {
            response.put("code", 200);
            response.put("message", "评论发布成功！");
        } else {
            response.put("code", 500);
            response.put("message", "系统异常，评论发布失败！");
        }

        return response;
    }

    /**
     * 2. 分页查询某首歌的评论接口（第2版：携带用户信息）
     * 请求方式：GET
     * 请求路径：/comment/detail/page?songId=xxx&pageNum=1&pageSize=10
     */
    @GetMapping("/detail/page")
    public Map<String, Object> getCommentsPage(
            @RequestParam("songId") Long songId, // 必须传：想看哪首歌的评论
            @RequestParam(value = "pageNum", defaultValue = "1") Integer pageNum,  // 选传：看第几页（默认第1页）
            @RequestParam(value = "pageSize", defaultValue = "10") Integer pageSize // 选传：一页看几条（默认10条）
    ) {
        Map<String, Object> response = new HashMap<>();

        // 直接调用 Service 里的新方法，拿到包装好的 DTO 分页数据！
        Page<CommentDTO> commentPage = commentService.getCommentsWithUserPage(songId, pageNum, pageSize);

        response.put("code", 200);
        response.put("data", commentPage);
        return response;
    }

    /**
     * 3. 点赞/取消点赞 接口
     * 请求路径：/comment/like
     */
    @PostMapping("/like")
    public Map<String, Object> toggleLike(@RequestParam("commentId") Long commentId) {
        Map<String, Object> response = new HashMap<>();

        // 1. 获取当前登录的用户
        Long currentUserId = UserHolder.getUserId();
        if (currentUserId == null) {
            response.put("code", 401);
            response.put("message", "请先登录！");
            return response;
        }

        // 2. 去点赞记录表里查一下，这哥们儿点过这手评论没？
        QueryWrapper<CommentLike> wrapper = new QueryWrapper<>();
        wrapper.eq("comment_id", commentId);
        wrapper.eq("user_id", currentUserId);
        CommentLike existLike = commentLikeService.getOne(wrapper);

        // 3. 开始执行逻辑
        if (existLike == null) {
            // 没点过赞：新增点赞记录
            CommentLike newLike = new CommentLike();
            newLike.setCommentId(commentId);
            newLike.setUserId(currentUserId);
            newLike.setCreateTime(LocalDateTime.now());
            commentLikeService.save(newLike);

            // 同时去 comment 表里把 like_count + 1
            Comment comment = commentService.getById(commentId);
            comment.setLikeCount(comment.getLikeCount() + 1);
            commentService.updateById(comment);

            response.put("message", "点赞成功！");
            response.put("hasLiked", true); // 告诉前端现在是已点赞状态
            response.put("likeCount", comment.getLikeCount()); // 返回最新的赞数
        } else {
            // 已经点过赞了：删除这条点赞记录（即取消点赞）
            commentLikeService.removeById(existLike.getId());

            // 同时去 comment 表里把 like_count - 1
            Comment comment = commentService.getById(commentId);
            // 兜个底，防止数字变成负数
            comment.setLikeCount(Math.max(0, comment.getLikeCount() - 1));
            commentService.updateById(comment);

            response.put("message", "已取消点赞");
            response.put("hasLiked", false);
            response.put("likeCount", comment.getLikeCount());
        }

        response.put("code", 200);
        return response;
    }
}
