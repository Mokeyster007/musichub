package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.dto.CommentDTO;
import com.musichub.entity.Comment;
import com.musichub.mapper.CommentMapper;
import com.musichub.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
//新建一个 CommentServiceImpl 类继承 ServiceImpl 类，并实现 CommentService 接口，用于具体实现存放用户评论的功能
public class CommentServiceImpl extends ServiceImpl<CommentMapper, Comment> implements CommentService {
    @Autowired
    private CommentMapper commentMapper;//注入 CommentMapper

    @Override
    public Page<CommentDTO> getCommentsWithUserPage(Long songId, Integer pageNum, Integer pageSize) {
        // 1. 创建分页对象
        Page<CommentDTO> page = new Page<>(pageNum, pageSize);
        // 2. 调用我们刚才在 Mapper 写的自定义 SQL 方法
        return commentMapper.selectCommentWithUserPage(page, songId);
    }

}
