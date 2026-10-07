package com.wikiagent.application.agent.pero;

import com.wikiagent.application.business.BusinessSlotGate;
import com.wikiagent.domain.agent.Reflection;
import com.wikiagent.infrastructure.tool.GenerateCustomsInfoTool;
import com.wikiagent.infrastructure.tool.ListAbandonedPathTool;
import com.wikiagent.infrastructure.tool.QueryOrderTool;
import com.wikiagent.infrastructure.tool.QueryTrajectoryTool;
import com.wikiagent.infrastructure.tool.ReadHandoverTool;
import com.wikiagent.infrastructure.tool.SearchHistoryTool;
import com.wikiagent.infrastructure.tool.SearchKnowledgeBaseTool;
import com.wikiagent.infrastructure.tool.UpdateUserProfileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * PeroToolExecutor 分派与参数校验测试：
 * 五个真实工具的分派、JSON/裸文本参数回退、ctx 标识注入、未知工具与参数错误的 Observation 文本。
 */
class PeroToolExecutorTest {

    private SearchKnowledgeBaseTool searchKb;
    private SearchHistoryTool searchHistory;
    private UpdateUserProfileTool updateProfile;
    private ReadHandoverTool readHandover;
    private ListAbandonedPathTool listAbandoned;
    private QueryOrderTool queryOrder;
    private QueryTrajectoryTool queryTrajectory;
    private GenerateCustomsInfoTool generateCustoms;
    private PeroToolExecutor executor;

    private static final Perception CTX = new Perception() {
        @Override public String userId() { return "u-1"; }
        @Override public String sessionId() { return "s-1"; }
        @Override public String userInput() { return "input"; }
        @Override public String intent() { return "knowledge_qa"; }
        @Override public Perception with(Reflection reflection) { return this; }
    };

    @BeforeEach
    void setUp() {
        searchKb = mock(SearchKnowledgeBaseTool.class);
        searchHistory = mock(SearchHistoryTool.class);
        updateProfile = mock(UpdateUserProfileTool.class);
        readHandover = mock(ReadHandoverTool.class);
        listAbandoned = mock(ListAbandonedPathTool.class);
        queryOrder = mock(QueryOrderTool.class);
        queryTrajectory = mock(QueryTrajectoryTool.class);
        generateCustoms = mock(GenerateCustomsInfoTool.class);
        ObjectProvider<BusinessSlotGate> slotGateProvider = mock(ObjectProvider.class);
        when(slotGateProvider.getIfAvailable()).thenReturn(mock(BusinessSlotGate.class));
        executor = new PeroToolExecutor(searchKb, searchHistory, updateProfile, readHandover,
                listAbandoned, queryOrder, queryTrajectory, generateCustoms, slotGateProvider);
    }

    @Test
    void searchKb按JSON参数分派() {
        when(searchKb.execute("理赔流程")).thenReturn("来源：...");
        String out = executor.invoke(new ReActAction("search_knowledge_base", "{\"query\":\"理赔流程\"}"), CTX);
        assertThat(out).isEqualTo("来源：...");
        verify(searchKb).execute("理赔流程");
    }

    @Test
    void searchKb裸文本args回退为query() {
        when(searchKb.execute("退保政策")).thenReturn("ok");
        executor.invoke(new ReActAction("search_knowledge_base", "退保政策"), CTX);
        verify(searchKb).execute("退保政策");
    }

    @Test
    void searchKb缺query返回参数错误且不调工具() {
        String out = executor.invoke(new ReActAction("search_knowledge_base", "{\"q\":\"x\"}"), CTX);
        assertThat(out).contains("参数错误").contains("query");
        verifyNoInteractions(searchKb);
    }

    @Test
    void searchHistory带topK分派() {
        when(searchHistory.execute(eq("上次结论"), anyInt())).thenReturn("历史事件：...");
        executor.invoke(new ReActAction("search_history", "{\"query\":\"上次结论\",\"topK\":3}"), CTX);
        verify(searchHistory).execute("上次结论", 3);
    }

    @Test
    void searchHistory缺省topK传0由工具取默认值() {
        when(searchHistory.execute(eq("q"), anyInt())).thenReturn("ok");
        executor.invoke(new ReActAction("search_history", "{\"query\":\"q\"}"), CTX);
        verify(searchHistory).execute("q", 0);
    }

    @Test
    void updateProfile使用ctx用户与JSON键值() {
        when(updateProfile.execute("u-1", "businessIdentity", "admin")).thenReturn("已更新");
        String out = executor.invoke(
                new ReActAction("update_user_profile", "{\"key\":\"businessIdentity\",\"value\":\"admin\"}"), CTX);
        assertThat(out).isEqualTo("已更新");
        verify(updateProfile).execute("u-1", "businessIdentity", "admin");
    }

    @Test
    void updateProfile缺value返回参数错误且不调工具() {
        String out = executor.invoke(new ReActAction("update_user_profile", "{\"key\":\"displayName\"}"), CTX);
        assertThat(out).contains("参数错误").contains("value");
        verifyNoInteractions(updateProfile);
    }

    @Test
    void readHandover使用ctx会话标识() {
        when(readHandover.execute("u-1", "s-1")).thenReturn("{}");
        executor.invoke(new ReActAction("read_handover", null), CTX);
        verify(readHandover).execute("u-1", "s-1");
    }

    @Test
    void listAbandoned使用ctx会话标识() {
        when(listAbandoned.execute("u-1", "s-1")).thenReturn("放弃路径：...");
        executor.invoke(new ReActAction("list_abandoned_paths", "{}"), CTX);
        verify(listAbandoned).execute("u-1", "s-1");
    }

    @Test
    void 未知工具返回明确提示且列出已注册() {
        String out = executor.invoke(new ReActAction("delete_everything", "{}"), CTX);
        assertThat(out).contains("未知工具").contains("delete_everything").contains("search_knowledge_base");
    }

    @Test
    void 空工具名返回提示() {
        String out = executor.invoke(new ReActAction(" ", "{}"), CTX);
        assertThat(out).contains("工具名为空");
    }

    @Test
    void 非FINAL空name不触发任何工具() {
        executor.invoke(new ReActAction(null, null), CTX);
        verifyNoInteractions(searchKb, searchHistory, updateProfile, readHandover, listAbandoned);
    }
}
