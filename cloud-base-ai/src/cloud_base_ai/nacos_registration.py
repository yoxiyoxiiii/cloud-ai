"""Nacos 注册封装（spec D6-D10）：

- start：create_naming_service + register_instance；失败告警降级不阻断启动（D7，SDK redo 自愈）
- stop：先 deregister_instance 再 shutdown（D8 时序；deregister 失败也关连接）
- 参数口径（D9/D10）：group_name=DEFAULT_GROUP、namespace_id=""（SDK naming 转 public）、
  ephemeral=True、metadata 三键
"""

import logging

from v2.nacos import (ClientConfig, ClientConfigBuilder, DeregisterInstanceParam,
                      NacosNamingService, RegisterInstanceParam)

from cloud_base_ai.config import Settings

logger = logging.getLogger(__name__)


class NacosRegistration:
    """Nacos 临时实例注册生命周期（挂 app.state.nacos）。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._service: NacosNamingService | None = None
        self._registered = False

    @property
    def registered(self) -> bool:
        """注册状态（/health 可观测，降级不静默）。"""
        return self._registered

    async def start(self) -> bool:
        """建连 + 注册；任何异常 log.error 降级返回 False，不冒泡（D7）。"""
        try:
            service = await NacosNamingService.create_naming_service(self._build_client_config())
            # 无论注册成败都持有 service：shutdown 需关连接；注册失败时 redo 自愈也依赖该连接
            self._service = service
            await service.register_instance(self._build_register_param())
        except Exception as e:
            self._registered = False
            logger.error("nacos register failed, degraded: server_addr=%s service_name=%s err=%s",
                         self._settings.nacos.server_addr, self._settings.nacos.service_name, e)
            return False
        self._registered = True
        logger.info("nacos registered: service=%s group=%s %s:%s",
                    self._settings.nacos.service_name, self._settings.nacos.group_name,
                    self._settings.nacos.ip, self._settings.app_port)
        return True

    async def stop(self) -> None:
        """先注销后关连接（D8）；幂等（二次调用直接返回）；停机不冒泡。"""
        service = self._service
        self._service = None
        self._registered = False
        if service is None:
            # create_naming_service 本身失败时无连接可关——D8 停机不冒泡的边界
            logger.info("nacos service not created, skip stop")
            return
        try:
            await service.deregister_instance(self._build_deregister_param())
        except Exception as e:
            logger.error("nacos deregister failed: err=%s", e)
        finally:
            try:
                await service.shutdown()
            except Exception as e:
                logger.error("nacos shutdown failed: err=%s", e)

    def _build_client_config(self) -> ClientConfig:
        return (
            ClientConfigBuilder()
            .server_address(self._settings.nacos.server_addr)
            .namespace_id(self._settings.nacos.namespace_id)
            .log_level("ERROR")  # 抑制 SDK 内部日志噪音
            .build()
        )

    def _build_register_param(self) -> RegisterInstanceParam:
        return RegisterInstanceParam(
            ip=self._settings.nacos.ip,
            port=self._settings.app_port,
            service_name=self._settings.nacos.service_name,
            group_name=self._settings.nacos.group_name,
            ephemeral=True,
            metadata={"lang": "python", "framework": "fastapi", "version": "0.1.0"},  # D10
        )

    def _build_deregister_param(self) -> DeregisterInstanceParam:
        return DeregisterInstanceParam(
            ip=self._settings.nacos.ip,
            port=self._settings.app_port,
            service_name=self._settings.nacos.service_name,
            group_name=self._settings.nacos.group_name,
        )
