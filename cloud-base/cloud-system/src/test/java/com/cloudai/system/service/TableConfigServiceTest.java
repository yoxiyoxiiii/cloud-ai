package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.convert.TableConfigConvert;
import com.cloudai.system.dto.TableConfigSaveRequest;
import com.cloudai.system.entity.SysTableConfig;
import com.cloudai.system.mapper.SysTableConfigMapper;
import com.cloudai.system.vo.TableConfigVo;
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

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 表格列配置服务单测（契约 2026-10-23-table-config-api §2/§5，设计 §7.1）：
 * 盲存校验链逐项红例 / upsert 三态（无行 insert、有行 update、并发撞 uk 收敛 update）/
 * 物理删幂等 / GET 无行 null·有行反序列化 / userId 恒取登录态。
 */
@ExtendWith(MockitoExtension.class)
class TableConfigServiceTest {

    private static final Long USER_ID = 9L;
    private static final String TABLE_KEY = "system:user";

    @Mock
    private SysTableConfigMapper configMapper;
    @InjectMocks
    private TableConfigService service;

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder 是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
    }

    private void loginAs(Long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(userId);
        loginUser.setAccount("admin");
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private TableConfigSaveRequest.TableColumnConfigDto item(String key, Boolean visible, Integer width) {
        TableConfigSaveRequest.TableColumnConfigDto dto = new TableConfigSaveRequest.TableColumnConfigDto();
        dto.setKey(key);
        dto.setVisible(visible);
        dto.setWidth(width);
        return dto;
    }

    private TableConfigSaveRequest request(List<TableConfigSaveRequest.TableColumnConfigDto> items) {
        TableConfigSaveRequest req = new TableConfigSaveRequest();
        req.setColumns(items);
        return req;
    }

    private BusinessException saveRejected(String tableKey, TableConfigSaveRequest req) {
        return catchThrowableOfType(() -> service.saveMyConfig(tableKey, req), BusinessException.class);
    }

    // ---- findMyConfig ----

    @Test
    void get_noRow_returnsNull() {
        loginAs(USER_ID);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(null);

        TableConfigVo vo = service.findMyConfig(TABLE_KEY);

        assertThat(vo).isNull();
    }

    @Test
    void get_withRow_returnsParsedColumnsInOrder() {
        loginAs(USER_ID);
        SysTableConfig row = new SysTableConfig();
        row.setUserId(USER_ID);
        row.setTableKey(TABLE_KEY);
        row.setConfig("{\"columns\":[{\"key\":\"account\",\"visible\":true,\"width\":160},"
                + "{\"key\":\"nickname\",\"visible\":false,\"width\":null}]}");
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(row);

        TableConfigVo vo = service.findMyConfig(TABLE_KEY);

        assertThat(vo.getColumns()).hasSize(2);
        assertThat(vo.getColumns().get(0).getKey()).isEqualTo("account");
        assertThat(vo.getColumns().get(0).getVisible()).isTrue();
        assertThat(vo.getColumns().get(0).getWidth()).isEqualTo(160);
        assertThat(vo.getColumns().get(1).getKey()).isEqualTo("nickname");
        assertThat(vo.getColumns().get(1).getVisible()).isFalse();
        assertThat(vo.getColumns().get(1).getWidth()).isNull();
    }

    // ---- saveMyConfig upsert 三态 ----

    @Test
    void save_noRow_insertsWithLoginUserIdAndWrappedJson() {
        loginAs(USER_ID);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(null);

        service.saveMyConfig(TABLE_KEY, request(List.of(
                item("account", true, 160), item("nickname", false, null))));

        ArgumentCaptor<SysTableConfig> captor = ArgumentCaptor.forClass(SysTableConfig.class);
        verify(configMapper).saveConfig(captor.capture());
        verify(configMapper, never()).updateConfig(any(SysTableConfig.class));
        SysTableConfig saved = captor.getValue();
        // userId 恒取登录态（入参无 userId 字段，编译期即防前端传参）
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getTableKey()).isEqualTo(TABLE_KEY);
        // 落库 JSON 为 {"columns":[...]} 包装形态且可无损回读
        assertThat(saved.getConfig()).startsWith("{\"columns\":[");
        TableConfigVo back = TableConfigConvert.toVo(saved.getConfig());
        assertThat(back.getColumns()).extracting(TableConfigVo.TableColumnConfigItemVo::getKey)
                .containsExactly("account", "nickname");
        assertThat(back.getColumns().get(0).getWidth()).isEqualTo(160);
    }

    @Test
    void save_existingRow_updatesWithoutInsert() {
        loginAs(USER_ID);
        SysTableConfig existing = new SysTableConfig();
        existing.setId(1L);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(existing);

        service.saveMyConfig(TABLE_KEY, request(List.of(item("account", true, null))));

        ArgumentCaptor<SysTableConfig> captor = ArgumentCaptor.forClass(SysTableConfig.class);
        verify(configMapper).updateConfig(captor.capture());
        verify(configMapper, never()).saveConfig(any(SysTableConfig.class));
        assertThat(captor.getValue().getConfig()).contains("\"key\":\"account\"");
    }

    @Test
    void save_concurrentInsertConflict_convergesToUpdate() {
        loginAs(USER_ID);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(null);
        doThrow(new DuplicateKeyException("uk_user_table")).when(configMapper)
                .saveConfig(any(SysTableConfig.class));

        service.saveMyConfig(TABLE_KEY, request(List.of(item("account", true, null))));

        // 并发首次保存撞 uk：幂等收敛为 update（不报错，last write wins）
        verify(configMapper).updateConfig(any(SysTableConfig.class));
    }

    @Test
    void save_emptyColumns_blindAccepted() {
        // 空数组=全隐藏偏好：盲存放行（只管格式与上限，不校验列含义）
        loginAs(USER_ID);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(null);

        service.saveMyConfig(TABLE_KEY, request(List.of()));

        verify(configMapper).saveConfig(any(SysTableConfig.class));
    }

    @Test
    void save_widthBoundaries_accepted() {
        loginAs(USER_ID);
        when(configMapper.findConfig(USER_ID, TABLE_KEY)).thenReturn(null);

        service.saveMyConfig(TABLE_KEY, request(List.of(
                item("account", true, 40), item("nickname", true, 1000))));

        verify(configMapper).saveConfig(any(SysTableConfig.class));
    }

    // ---- deleteMyConfig ----

    @Test
    void delete_physicalDeleteByLoginUserAndKey() {
        loginAs(USER_ID);

        service.deleteMyConfig(TABLE_KEY);

        verify(configMapper).deleteConfig(USER_ID, TABLE_KEY);
    }

    @Test
    void delete_noRow_stillSucceedsIdempotently() {
        // 行不存在=DELETE 影响 0 行，Service 不判存不报错——重复恢复默认幂等
        loginAs(USER_ID);
        when(configMapper.deleteConfig(USER_ID, TABLE_KEY)).thenReturn(0);

        service.deleteMyConfig(TABLE_KEY);

        verify(configMapper).deleteConfig(USER_ID, TABLE_KEY);
    }

    // ---- 校验链红例（1002 两条文案，错误码零新增） ----

    @Test
    void save_badTableKeyCharset_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected("bad key!", request(List.of(item("account", true, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("表格标识格式无效");
        verifyNoInteractions(configMapper);
    }

    @Test
    void get_badTableKeyCharset_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = catchThrowableOfType(() -> service.findMyConfig("bad/key"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("表格标识格式无效");
        verifyNoInteractions(configMapper);
    }

    @Test
    void save_tableKeyTooLong_rejected1002() {
        loginAs(USER_ID);
        String tooLong = "a".repeat(65);

        BusinessException ex = saveRejected(tooLong, request(List.of(item("account", true, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("表格标识格式无效");
    }

    @Test
    void save_nullColumns_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY, request(null));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("列配置格式无效");
        verifyNoInteractions(configMapper);
    }

    @Test
    void save_nullItem_rejected1002() {
        loginAs(USER_ID);
        List<TableConfigSaveRequest.TableColumnConfigDto> items = new ArrayList<>();
        items.add(item("account", true, null));
        items.add(null);

        BusinessException ex = saveRejected(TABLE_KEY, request(items));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("为空");
    }

    @Test
    void save_badColumnKeyCharset_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY, request(List.of(item("bad-key", true, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("列标识格式无效");
    }

    @Test
    void save_columnKeyTooLong_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY,
                request(List.of(item("k".repeat(33), true, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("列标识格式无效");
    }

    @Test
    void save_missingVisible_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY, request(List.of(item("account", null, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("visible");
    }

    @Test
    void save_widthTooSmall_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY, request(List.of(item("account", true, 39))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("列宽");
    }

    @Test
    void save_widthTooLarge_rejected1002() {
        loginAs(USER_ID);

        BusinessException ex = saveRejected(TABLE_KEY, request(List.of(item("account", true, 1001))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("列宽");
    }

    @Test
    void save_columnCountOver50_rejected1002() {
        loginAs(USER_ID);
        List<TableConfigSaveRequest.TableColumnConfigDto> items = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            items.add(item("col" + i, true, null));
        }

        BusinessException ex = saveRejected(TABLE_KEY, request(items));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("列数超上限");
    }

    @Test
    void save_serializedLengthOver4000_rejected1002() {
        // 60 项×32 字符 key：序列化约 4200+ 字符 > 4000（总长检查先于列数检查，
        // 超大 payload 快速拒绝——两项同为 1002 同段文案，报错归属按先命中分支）
        loginAs(USER_ID);
        List<TableConfigSaveRequest.TableColumnConfigDto> items = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            items.add(item("k".repeat(32), true, 1000));
        }

        BusinessException ex = saveRejected(TABLE_KEY, request(items));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).contains("列配置格式无效").contains("序列化长度超上限");
    }

    // ---- 登录态防御 ----

    @Test
    void save_noLoginContext_rejected1002Defensively() {
        // 网关鉴权前置下理论不可达；防御性兜底（不 NPE）
        BusinessException ex = saveRejected(TABLE_KEY, request(List.of(item("account", true, null))));

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("登录态缺失");
        verifyNoInteractions(configMapper);
    }
}
