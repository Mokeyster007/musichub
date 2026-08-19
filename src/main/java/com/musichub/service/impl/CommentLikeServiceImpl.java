package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.CommentLike;
import com.musichub.mapper.CommentLikeMapper;
import com.musichub.service.CommentLikeService;
import org.springframework.stereotype.Service;

@Service
public class CommentLikeServiceImpl extends ServiceImpl<CommentLikeMapper, CommentLike> implements CommentLikeService {
}
