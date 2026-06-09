package io.casehub.drafthouse;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.casehub.drafthouse.debate.AgentTask;
import io.casehub.drafthouse.debate.DebateAgentProvider;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Default DebateAgentProvider implementation backed by LangChain4j ChatModel.
 * Displaced by ClaudeAgentSdkDebateAgentProvider when the claude-agent module is on the classpath.
 */
@DefaultBean
@ApplicationScoped
public class LangChain4jDebateAgentProvider implements DebateAgentProvider {

    private final ChatModel chatModel;

    @Inject
    LangChain4jDebateAgentProvider(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String analyse(AgentTask task) {
        var response = chatModel.chat(List.of(
                SystemMessage.from(task.systemPrompt()),
                UserMessage.from(task.assembledInput())
        ));
        return response.aiMessage().text();
    }
}
