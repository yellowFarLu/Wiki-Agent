package com.wikiagent.interfaces.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.view.RedirectView;

/**
 * 旧版静态页与早期一级路由的统一入口重定向。
 * <p>
 * 控制台已收敛为单页（/?tab=xxx），Next.js 静态导出产物由 Spring Boot 托管在 8090；
 * 旧书签/硬链接（/chat、/tasks/{id} 等）在此 307 到对应 Tab 查询串，
 * 与 frontend/next.config.mjs 在 dev/standalone 模式下的 redirects 保持一致。
 */
@Controller
public class LegacyRedirectController {

    @GetMapping("/chat")
    public RedirectView chat() {
        return to("chat");
    }

    @GetMapping("/upload")
    public RedirectView upload() {
        return to("upload");
    }

    @GetMapping("/tasks")
    public RedirectView tasks() {
        return to("tasks");
    }

    @GetMapping("/tasks/{taskId}")
    public RedirectView taskDetail(@PathVariable String taskId) {
        return to("tasks&taskId=" + taskId);
    }

    @GetMapping("/workbench")
    public RedirectView workbench() {
        return to("workbench");
    }

    @GetMapping("/graph")
    public RedirectView graph() {
        return to("graph");
    }

    @GetMapping("/settings")
    public RedirectView settings() {
        return to("settings");
    }

    @GetMapping("/results/{docId}")
    public RedirectView results(@PathVariable String docId) {
        return to("upload&docId=" + docId);
    }

    private RedirectView to(String query) {
        RedirectView view = new RedirectView("/?" + "tab=" + query);
        view.setHttp10Compatible(false);
        return view;
    }
}
