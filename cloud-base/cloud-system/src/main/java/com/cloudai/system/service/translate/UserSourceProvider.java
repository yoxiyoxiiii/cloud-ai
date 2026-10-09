package com.cloudai.system.service.translate;

import com.cloudai.system.api.domain.UserEntry;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户回源实现：全量未删用户投影转发 mapper（listTransAll 小表取舍见翻译设计 §6）。
 * 转换用原生 setter 逐字段（禁三方拷贝工具）。
 * 与 common 接口同简单名（SPI 场景惯例）——implements 用全限定名避免自引用歧义。
 */
@Service
@RequiredArgsConstructor
public class UserSourceProvider implements com.cloudai.common.translate.provider.UserSourceProvider {

    private final SysUserMapper userMapper;

    @Override
    public List<UserEntry> listAll() {
        return userMapper.listTransAll().stream()
                .map(UserSourceProvider::toEntry)
                .toList();
    }

    private static UserEntry toEntry(SysUser user) {
        UserEntry entry = new UserEntry();
        entry.setId(user.getId());
        entry.setAccount(user.getAccount());
        entry.setNickname(user.getNickname());
        return entry;
    }
}
