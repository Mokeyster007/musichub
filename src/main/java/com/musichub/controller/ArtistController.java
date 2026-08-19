package com.musichub.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musichub.entity.Artist;
import com.musichub.service.ArtistService;
import com.musichub.service.NeteaseScraperService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import com.musichub.common.Result;
import java.util.List;
//新建一个 ArtistController 来接收前端发来的歌手数据，并调用刚刚写好的 Service 保存到数据库。
@RestController
@RequestMapping("/artist")
public class ArtistController {

    @Autowired
    private ArtistService artistService;


    // 注入我们刚写的网易云刮削器
    @Autowired
    private NeteaseScraperService neteaseScraperService;

    @GetMapping("/detail")
    public Result<?> getArtistDetail(@RequestParam("artistId") Long artistId) {
        Artist artist = artistService.getById(artistId);
        if (artist == null) {
            return Result.error("歌手不存在"); // 或者 Result.fail("歌手不存在"); 具体看你的 Result 类
        }

        // 💡 增加一条日志，确认请求进来了
        System.out.println(">>> 收到查询歌手请求，歌手名: " + artist.getArtistName());

        boolean needScrape = false;
        String avatar = artist.getAvatar();
        String intro = artist.getIntroduction();
        String style = artist.getStyle(); // 👈 拿到刚才在实体类里加的 style 字段

        // 1. 判断头像是否不合格（为空、或者包含了 songcovers、或者是前端默认的 cooking 图片）
        if (avatar == null
                || avatar.trim().isEmpty()
                || avatar.contains("songcovers")
                || avatar.contains("cooking")
                || avatar.contains("vuetifyjs")) {
            needScrape = true;
            System.out.println("--- 触发刮削原因: 头像不合格 (" + avatar + ")");
        }

        // 2. 判断简介是否不合格（为空、或者是默认的占位符文字）
        if (!needScrape) { // 如果头像没问题，继续检查简介
            if (intro == null
                    || intro.trim().isEmpty()
                    || intro.contains("暂无")
                    || intro.contains("正在获取")) {
                needScrape = true;
                System.out.println("--- 触发刮削原因: 简介不合格 (" + intro + ")");
            }
        }

        // 3. 判断标签是否为空 👈 (这是为你新加的逻辑)
        if (!needScrape) {
            if (style == null || style.trim().isEmpty()) {
                needScrape = true;
                System.out.println("--- 触发刮削原因: 标签为空");
            }
        }

        // 如果发现数据不完整或有假头像/缺标签，触发刮削
        if (needScrape) {
            System.out.println(">>> 开始调用网易云刮削器...");

            // 1. 去网易云抓取。我们刚才在 Service 里修改过了，这里会自动去抓头像、简介和【标签】！
            neteaseScraperService.fetchAndFillArtistInfo(artist);

            // 2. 如果简介没抓到，给一个友好的默认提示，不要留空
            if (artist.getIntroduction() == null || artist.getIntroduction().trim().isEmpty() || artist.getIntroduction().contains("正在获取")) {
                artist.setIntroduction("暂无该歌手的详细百科介绍。");
            }

            // 3. 兜底标签 👈 (如果网易云没抓到标签，或者我们代码里的判断没命中，默认给个身份)
            if (artist.getStyle() == null || artist.getStyle().trim().isEmpty()) {
                artist.setStyle("独立音乐人");
            }

            // 4. 更新数据库 (这一下会把头像、简介、标签全部一起 update 进去)
            artistService.updateById(artist);
            System.out.println(">>> 刮削流程结束，已更新数据库！当前标签: " + artist.getStyle());
        } else {
            System.out.println(">>> 数据已经很完美了，跳过刮削。");
        }

        // 返回给前端包含最新 style 数据的 artist 对象
        return Result.success(artist);
    }


    // 添加歌手的接口
    @PostMapping("/add")
    public String addArtist(@RequestBody Artist Artist) {
        // 调用 MyBatis-Plus 提供的 save 方法，直接把对象存入数据库
        boolean result = artistService.save(Artist);
        if (result) {
            return "歌手添加成功！";
        } else {
            return "歌手添加失败！";
        }
    }

    // 查询所有歌手
    @GetMapping("/all")
    public List<Artist> getAllArtists() {
        // 直接调用 MyBatis-Plus 的 list() 方法，它会自动执行 SELECT * FROM artist
        return artistService.list();
    }

    // 获取歌手分页列表
    @GetMapping("/page")
    public Result<?> getArtistPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {

        // 1. 创建 MyBatis-Plus 的 Page 对象
        Page<Artist> pageInfo = new Page<>(pageNum, pageSize);

        // 2. 调用自带的分页方法查询，如果有条件也可以构造 QueryWrapper
        Page<Artist> artistPage = artistService.page(pageInfo);

        // 3. 返回给前端（返回的数据中会自动包含 records, total 等字段）
        return Result.success(artistPage);
    }
}
