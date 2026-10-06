package com.wikiagent.application.multiagent;

import com.wikiagent.application.agent.pero.PeroAgent;
import com.wikiagent.application.agent.pero.Perception;
import com.wikiagent.application.task.handler.AgentTaskLauncher;
import com.wikiagent.service.chat.SseSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * v6 §21.5 IntentAgent 实现（仅真实 Agent）。
 * <p>
 * Mock 意图（ai_coding / customer_intake / business_rule_config / order_query）
 * 已全部移除，由真实业务逻辑或 knowledge_qa 兜底承接。
 */
final class IntentAgents {

    private IntentAgents() {
    }

    /**
     * 真实 KnowledgeQa：委托 §20 {@link PeroAgent} PERO 主循环执行（Plan→Execute→Reflect→Optimize）。
     * <p>
     * PeroAgent 内部会调用 PeroPlanner.plan() → ReActExecutor.execute() → LlmReflector.reflect()
     * → SimpleOptimizer.optimize() → SimpleGenerator.generate()，完整走 §20 主循环。
     */
    @Component
    static class KnowledgeQaAgent implements IntentAgent {

        private final PeroAgent peroAgent;
        private final ObjectProvider<AgentTaskLauncher> taskLauncherProvider;

        KnowledgeQaAgent(PeroAgent peroAgent,
                         ObjectProvider<AgentTaskLauncher> taskLauncherProvider) {
            this.peroAgent = peroAgent;
            this.taskLauncherProvider = taskLauncherProvider;
        }

        @Override
        public String intent() {
            return "knowledge_qa";
        }

        @Override
        public void invoke(Perception ctx, SseSender sse) {
            AgentTaskLauncher launcher = taskLauncherProvider.getIfAvailable();
            if (launcher != null) {
                launcher.launchAndBridge(ctx.userId(), ctx.sessionId(), ctx.userInput(), sse);
                return;
            }
            peroAgent.run(ctx.userId(), ctx.sessionId(), ctx.userInput(), sse);
        }
    }
}
