package com.cloudai.system.api.dataperm;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 上下文生命周期拥有者（设计 D54，双层清理）：入口防御清（防上一请求任意路径残留=串号护栏）+
 * 出口必清（finally 覆盖正常/异常全路径）。advice 在 chain 内消费、filter 在序列化完成后收尾——
 * 上下文存活窗口精确覆盖「Service set → 响应序列化完成」。
 *
 * <p>双层缺一不可（设计红线 7）：只留 finally——finally 自身异常前丢；只留入口——本请求内
 * 残留窗口拖到序列化后。OncePerRequestFilter 形态不依赖 InterceptorRegistry 注册，
 * filter bean 即被 Boot 自动注册（api 模块仅需 provided servlet-api）。</p>
 */
public class DataPermColumnContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        DataPermColumnContext.clear();
        try {
            filterChain.doFilter(request, response);
        } finally {
            DataPermColumnContext.clear();
        }
    }
}
