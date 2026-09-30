package io.github.hejian.gamenl2sql;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "agentscope.openai.api-key=placeholder-no-network-call")
class AgentBeanRegistrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void agentBeansAreRegistered() {
        assertThat(context.getBean(Model.class)).isNotNull();
        assertThat(context.getBean(ReActAgent.class)).isNotNull();
        assertThat(context.getBean(Toolkit.class)).isNotNull();
        assertThat(context.getBean(InMemoryMemory.class)).isNotNull();

        Model model = context.getBean(Model.class);
        ReActAgent agent = context.getBean(ReActAgent.class);
        System.out.println("[gate4] Model      = " + model.getClass().getName()
                + " id=" + context.getBeanNamesForType(Model.class)[0]);
        System.out.println("[gate4] ReActAgent = " + agent.getClass().getName()
                + " id=" + context.getBeanNamesForType(ReActAgent.class)[0]);
    }
}
