package com.wikiagent.interfaces.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Next.js 静态导出（{@code output: export}）客户端路由 RSC flight 载荷的缓存策略修正。
 *
 * <h2>背景</h2>
 * 控制台为单页应用，切换 Tab 时前端调用 {@code router.replace('/?tab=xxx')}，Next App Router
 * 不刷新整页，而是 fetch 当前路径对应的 RSC flight 数据；静态导出产物中该数据就是
 * {@code /index.txt?_rsc=<hash>}（多路由后为 {@code /<route>.txt}）。
 *
 * <p>Spring Boot 默认静态资源伺服不携带 {@code Cache-Control}，浏览器会对 {@code .txt}
 * 做启发式缓存。前端重新部署后 flight 内容（内含 buildId 与带哈希的 chunk 文件名）已经变化，
 * 客户端却可能命中磁盘中的陈旧副本，Next 校验 buildId 不一致后回退为<b>整页硬导航</b>，
 * 表现为：每次切 Tab 都整页重载、对话 SSE 被中断、各面板状态全部丢失、入场动画反复播放，
 * 主观上就是“所有页面都卡”。
 *
 * <h2>修正</h2>
 * 对 flight 载荷（{@code .txt} 静态资源）与应用入口 HTML（{@code /}、{@code /index.html}）
 * 发送 {@code Cache-Control: no-cache}，强制浏览器每次使用前向服务器协商（配合 Spring 静态
 * 资源默认的 {@code Last-Modified}，内容未变时返回 304，开销可忽略；版本变更后立即拿到新
 * 内容）。HTML 必须及时更新，否则会继续引用已删除的旧版哈希 chunk。带内容哈希的不可变资源
 * （{@code /_next/static/*}）不受影响，继续走浏览器长缓存。
 */
@Component
public class NextFlightCacheControlFilter extends OncePerRequestFilter {

    private static final String CACHE_CONTROL_HEADER = "Cache-Control";

    /** 每次使用前必须与服务器协商验证（不是 no-store，仍可利用 304 协商缓存）。 */
    private static final String NO_CACHE = "no-cache";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (mustRevalidate(request)) {
            response.setHeader(CACHE_CONTROL_HEADER, NO_CACHE);
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 需要每次协商验证的“版本敏感”资源：
     * <ul>
     *   <li>RSC flight 载荷：单页为 {@code /index.txt}，多路由后为 {@code /<route>.txt}；</li>
     *   <li>应用入口 HTML：{@code /}（welcome page）与 {@code /index.html}。</li>
     * </ul>
     * 排除 {@code /api/} 以防误伤业务接口。
     */
    private boolean mustRevalidate(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || path.startsWith("/api/")) {
            return false;
        }
        return path.endsWith(".txt") || "/".equals(path) || "/index.html".equals(path);
    }
}
