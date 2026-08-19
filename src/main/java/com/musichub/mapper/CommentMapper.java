package com.musichub.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musichub.dto.CommentDTO;
import com.musichub.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;


@Mapper
public interface CommentMapper extends BaseMapper<Comment> {
    // 自定义多表联查分页方法
    // 这里的 @Select 注解里面写的就是原生的 SQL JOIN 语句
    // 注意：这里的 c.user_id = u.id 是联表的关键！
    @Select("SELECT c.*, u.username, u.user_avatar AS avatar " + 
            "FROM tb_comment c " +
            "LEFT JOIN tb_user u ON c.user_id = u.id " +
            "WHERE c.song_id = #{songId} " +
            "ORDER BY c.create_time DESC")
    Page<CommentDTO> selectCommentWithUserPage(Page<CommentDTO> page, @Param("songId") Long songId);
}
