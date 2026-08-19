package com.musichub.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.musichub.entity.PlayHistory;
import com.musichub.mapper.PlayHistoryMapper;
import com.musichub.service.PlayHistoryService;
import org.springframework.stereotype.Service;

@Service
public class PlayHistoryServiceImpl extends ServiceImpl<PlayHistoryMapper, PlayHistory>
        implements PlayHistoryService {
}