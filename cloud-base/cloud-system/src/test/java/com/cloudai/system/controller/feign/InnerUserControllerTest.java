package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.UserEntry;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.service.SysUserLinkageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * inner 用户端点委托转发单测（契约 2026-10-07-inner-api §2）：controller 只转发 Provider，口径在 Provider/mapper。
 */
@ExtendWith(MockitoExtension.class)
class InnerUserControllerTest {

    @Mock
    private SysUserLinkageService linkageService;
    @Mock
    private UserSourceProvider userSourceProvider;
    @InjectMocks
    private InnerUserController controller;

    @Test
    void listAll_delegatesToProvider() {
        UserEntry entry = new UserEntry();
        entry.setId(1L);
        entry.setAccount("admin");
        entry.setNickname("管理员");
        when(userSourceProvider.listAll()).thenReturn(List.of(entry));

        R<List<UserEntry>> result = controller.listAll();

        verify(userSourceProvider).listAll();
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).extracting(UserEntry::getAccount).containsExactly("admin");
    }

    @Test
    void getUserByAccount_delegatesToLinkageService() {
        LoginUserDTO loginUser = new LoginUserDTO();
        when(linkageService.findByAccount("admin")).thenReturn(loginUser);

        R<LoginUserDTO> result = controller.getUserByAccount("admin");

        verify(linkageService).findByAccount("admin");
        assertThat(result.getData()).isSameAs(loginUser);
    }
}
