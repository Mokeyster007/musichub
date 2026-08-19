package com.musichub.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.musichub.entity.Song;
import com.musichub.entity.UserFavorite;

import java.util.List;

//创建一个 CollectService 接口继承 IService 接口，并实现 CollectService 接口。
public interface UserFavoriteService extends IService<UserFavorite> {
    
    /**
     * 根据用户 ID 查询收藏的歌曲列表
     * @param userId 用户 ID
     * @return 收藏的歌曲列表
     */
    List<Song> listFavoriteSongsByUser(Long userId);
}
