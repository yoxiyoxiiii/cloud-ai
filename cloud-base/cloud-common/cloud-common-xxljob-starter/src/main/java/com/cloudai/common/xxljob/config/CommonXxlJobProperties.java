package com.cloudai.common.xxljob.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xxl-job 连接配置（设计 D1：键名与官方 sample 逐键镜像零转译——xxl.job.admin.* / xxl.job.executor.*，
 * 官方文档直接可查；类名带 Common 对齐本仓 CommonRocketMqProperties 命名惯例——类名本仓惯例、键名官方惯例）。
 * 连接值（addresses/appname/port/ip/accessToken 等）经 Nacos 下发不进仓库（设计 D6 矩阵：
 * 共享 cloud-common.yaml 放公共项，per-service 放差异项）。
 */
@ConfigurationProperties(prefix = "xxl.job")
public class CommonXxlJobProperties {

    /** 调度中心 admin 连接（xxl.job.admin.*） */
    private Admin admin = new Admin();

    /** 执行器配置（xxl.job.executor.*） */
    private Executor executor = new Executor();

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    /** 调度中心 admin 连接（xxl.job.admin.*，官方零转译） */
    public static class Admin {

        /** 调度中心地址列表，逗号分隔（http://address01,http://address02） */
        private String addresses;

        /** 调度中心调用超时（秒，官方默认 3） */
        private Integer timeout = 3;

        public String getAddresses() {
            return addresses;
        }

        public void setAddresses(String addresses) {
            this.addresses = addresses;
        }

        public Integer getTimeout() {
            return timeout;
        }

        public void setTimeout(Integer timeout) {
            this.timeout = timeout;
        }
    }

    /** 执行器配置（xxl.job.executor.*，官方零转译） */
    public static class Executor {

        /** 执行器启动旗标（官方语义：false 时 executor 启动跳过不报错）——与装配门控 cloud.common.xxljob.enabled 是两层开关（后者控制 bean 是否创建） */
        private Boolean enabled = true;

        /** 执行器 appname（注册分组依据；本项目=spring.application.name 同名字面量，设计 D6） */
        private String appname;

        /** 调度通信令牌（须与 admin 侧 xxl_job_group.access_token 一致，种子段 default_token） */
        private String accessToken;

        /** 注册用 IP（多网卡机器自动探测不可用需显式钉；空=自动探测） */
        private String ip;

        /** 执行器回调端口（netty 绑定；绑定失败会阻断宿主服务启动——运维注意，设计 R7） */
        private Integer port;

        /** 注册地址（优先于 ip:port，可写 http://host:port 形态；空=用 ip:port 拼接） */
        private String address;

        /** 执行日志目录（Windows 显式配置勿落 Unix 默认 /data/applogs；目录自动创建，日志文件按全局 logId 命名多服务同目录无碰撞） */
        private String logPath;

        /** 执行日志保留天数（默认 30 显式记档） */
        private Integer logRetentionDays = 30;

        /** @XxlJob 扫描排除包（对齐 XxlJobSpringExecutor 内部默认值，防 null 打穿排除逻辑导致全量扫 bean） */
        private String excludedPackage = "org.springframework.,spring.";

        /** GLUE（控制台注入代码）任务开关——控制台代码执行面是攻击面，本项目无需求默认关（偏离官方 sample 的 true，需要时 Nacos 显式开，设计 D1 记档） */
        private Boolean glueEnabled = false;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public String getAppname() {
            return appname;
        }

        public void setAppname(String appname) {
            this.appname = appname;
        }

        public String getAccessToken() {
            return accessToken;
        }

        public void setAccessToken(String accessToken) {
            this.accessToken = accessToken;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getLogPath() {
            return logPath;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath;
        }

        public Integer getLogRetentionDays() {
            return logRetentionDays;
        }

        public void setLogRetentionDays(Integer logRetentionDays) {
            this.logRetentionDays = logRetentionDays;
        }

        public String getExcludedPackage() {
            return excludedPackage;
        }

        public void setExcludedPackage(String excludedPackage) {
            this.excludedPackage = excludedPackage;
        }

        public Boolean getGlueEnabled() {
            return glueEnabled;
        }

        public void setGlueEnabled(Boolean glueEnabled) {
            this.glueEnabled = glueEnabled;
        }
    }
}
