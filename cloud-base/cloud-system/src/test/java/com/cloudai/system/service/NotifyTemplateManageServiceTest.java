package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifyTemplateAddRequest;
import com.cloudai.system.dto.NotifyTemplateEditRequest;
import com.cloudai.system.dto.NotifyTemplatePageQuery;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.vo.NotifyTemplateVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板管理单测（契约 2026-11-08-notify-api §1/§4）：
 * 3043 撞 code（预检 + DuplicateKey 墓碑兜底）/ 3044 不存在 / 3049 内置禁删（内置可改）/ 软删审计两值 / VO 转换。
 */
@ExtendWith(MockitoExtension.class)
class NotifyTemplateManageServiceTest {

    @Mock
    private SysNotifyTemplateMapper templateMapper;
    @InjectMocks
    private NotifyTemplateManageService service;

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder 是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String account) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private SysNotifyTemplate template(Long id, String code, int isBuiltin) {
        SysNotifyTemplate t = new SysNotifyTemplate();
        t.setId(id);
        t.setCode(code);
        t.setName("模板-" + code);
        t.setTitleTemplate("标题 ${var}");
        t.setContentTemplate("内容 ${var}");
        t.setType(SysNotifyTemplate.TypeEnum.NOTICE.getCode());
        t.setLevel(SysNotifyTemplate.LevelEnum.NORMAL.getCode());
        t.setVariablesDesc("var=示例");
        t.setIsBuiltin(isBuiltin);
        return t;
    }

    private NotifyTemplateAddRequest addRequest(String code) {
        NotifyTemplateAddRequest req = new NotifyTemplateAddRequest();
        req.setCode(code);
        req.setName("测试模板");
        req.setTitleTemplate("标题 ${var}");
        req.setContentTemplate("内容 ${var}");
        req.setType(SysNotifyTemplate.TypeEnum.REMIND.getCode());
        req.setLevel(SysNotifyTemplate.LevelEnum.IMPORTANT.getCode());
        return req;
    }

    @Test
    void pageList_convertsRowsToVo() {
        Page<SysNotifyTemplate> page = new Page<>(1, 10);
        page.setRecords(List.of(template(1L, "welcome_notice", 1)));
        page.setTotal(1);
        when(templateMapper.pageList(any(Page.class), any(NotifyTemplatePageQuery.class))).thenReturn(page);

        var result = service.pageList(new NotifyTemplatePageQuery());

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        NotifyTemplateVo vo = result.getRows().get(0);
        assertThat(vo.getId()).isEqualTo(1L);
        assertThat(vo.getCode()).isEqualTo("welcome_notice");
        assertThat(vo.getTitleTemplate()).isEqualTo("标题 ${var}");
        assertThat(vo.getIsBuiltin()).isEqualTo(1);
    }

    @Test
    void findById_notFoundRejected() {
        when(templateMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.findById(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void findById_returnsVo() {
        when(templateMapper.findById(1L)).thenReturn(template(1L, "welcome_notice", 1));
        NotifyTemplateVo vo = service.findById(1L);
        assertThat(vo.getCode()).isEqualTo("welcome_notice");
        assertThat(vo.getType()).isEqualTo(SysNotifyTemplate.TypeEnum.NOTICE.getCode());
    }

    @Test
    void save_duplicateCodeRejected() {
        when(templateMapper.countByCode("welcome_notice", null)).thenReturn(1L);
        assertThatThrownBy(() -> service.save(addRequest("welcome_notice")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板编码已存在");
        verify(templateMapper, never()).save(any(SysNotifyTemplate.class));
    }

    @Test
    void save_duplicateKeyFallbackOnUniqueViolation() {
        // 预检通过但 INSERT 撞唯一键（并发 TOCTOU / 墓碑占键）：DuplicateKeyException 兜底同转 3043
        when(templateMapper.countByCode("welcome_notice", null)).thenReturn(0L);
        doThrow(new DuplicateKeyException("uk_code")).when(templateMapper).save(any(SysNotifyTemplate.class));
        assertThatThrownBy(() -> service.save(addRequest("welcome_notice")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板编码已存在");
    }

    @Test
    void save_ok_buildsUserCreatedTemplateWithAudit() {
        loginAs("admin");
        when(templateMapper.countByCode("new_notice", null)).thenReturn(0L);

        service.save(addRequest("new_notice"));

        ArgumentCaptor<SysNotifyTemplate> captor = ArgumentCaptor.forClass(SysNotifyTemplate.class);
        verify(templateMapper).save(captor.capture());
        SysNotifyTemplate saved = captor.getValue();
        assertThat(saved.getIsBuiltin())
                .isEqualTo(SysNotifyTemplate.BuiltinEnum.USER_CREATED.getCode());
        assertThat(saved.getCreateBy()).isEqualTo("admin");
        assertThat(saved.getUpdateBy()).isEqualTo("admin");
        assertThat(saved.getType()).isEqualTo(SysNotifyTemplate.TypeEnum.REMIND.getCode());
        assertThat(saved.getLevel()).isEqualTo(SysNotifyTemplate.LevelEnum.IMPORTANT.getCode());
        // 审计时间由 XML 的 NOW() 落库，Service 不传时间参数
        assertThat(saved.getCreateTime()).isNull();
    }

    @Test
    void update_notFoundRejected() {
        when(templateMapper.findById(9L)).thenReturn(null);
        NotifyTemplateEditRequest req = new NotifyTemplateEditRequest();
        req.setId(9L);
        req.setCode("welcome_notice");
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void update_duplicateCodeRejected() {
        when(templateMapper.findById(1L)).thenReturn(template(1L, "notice_a", 0));
        when(templateMapper.countByCode("notice_b", 1L)).thenReturn(1L);
        NotifyTemplateEditRequest req = new NotifyTemplateEditRequest();
        req.setId(1L);
        req.setCode("notice_b");
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板编码已存在");
        verify(templateMapper, never()).update(any(SysNotifyTemplate.class));
    }

    @Test
    void update_builtinContentChangeAllowed() {
        // 契约 §1.4：内置模板（isBuiltin=1）内容可改，仅禁删
        loginAs("admin");
        when(templateMapper.findById(1L)).thenReturn(template(1L, "welcome_notice", 1));
        when(templateMapper.countByCode("welcome_notice", 1L)).thenReturn(0L);
        NotifyTemplateEditRequest req = new NotifyTemplateEditRequest();
        req.setId(1L);
        req.setCode("welcome_notice");
        req.setName("欢迎公告改");
        req.setTitleTemplate("欢迎加入 v2");
        req.setContentTemplate("欢迎使用本平台 v2");
        req.setType(SysNotifyTemplate.TypeEnum.NOTICE.getCode());
        req.setLevel(SysNotifyTemplate.LevelEnum.NORMAL.getCode());

        service.update(req);

        ArgumentCaptor<SysNotifyTemplate> captor = ArgumentCaptor.forClass(SysNotifyTemplate.class);
        verify(templateMapper).update(captor.capture());
        assertThat(captor.getValue().getTitleTemplate()).isEqualTo("欢迎加入 v2");
        assertThat(captor.getValue().getUpdateBy()).isEqualTo("admin");
    }

    @Test
    void delete_notFoundRejected() {
        when(templateMapper.findById(9L)).thenReturn(null);
        assertThatThrownBy(() -> service.delete(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void delete_builtinRejected() {
        when(templateMapper.findById(1L)).thenReturn(template(1L, "welcome_notice", 1));
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置模板禁止删除");
        verify(templateMapper, never()).deleteById(any(Long.class), any());
    }

    @Test
    void delete_ok_softDeleteWithUpdateAudit() {
        loginAs("admin");
        when(templateMapper.findById(2L)).thenReturn(template(2L, "notice_a", 0));

        service.delete(2L);

        // 逻辑删除带 update 审计两值（update_by=操作人，update_time 由 XML NOW() 落库）
        verify(templateMapper).deleteById(2L, "admin");
    }
}
