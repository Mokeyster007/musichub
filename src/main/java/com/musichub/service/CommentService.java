package com.musichub.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.musichub.dto.CommentDTO;
import com.musichub.entity.Comment;

//创建一个CommentService接口继承IService，并实现CommentService接口，用于具体实现存放用户评论的功能
public interface CommentService extends IService<Comment> {
    Page<CommentDTO> getCommentsWithUserPage(Long songId, Integer pageNum, Integer pageSize);

}
