package io.casehub.drafthouse;

import io.casehub.agency.conversation.ConversationState;
import io.casehub.agency.conversation.orchestration.AgentParticipant;
import io.casehub.agency.conversation.orchestration.PromptAssembler;
import io.casehub.agency.summarisation.observation.PartitionedDrain;

import java.util.function.Function;
import java.util.function.Supplier;

public class DebatePromptAssembler implements PromptAssembler {

    private final Supplier<DebateSession> sessionSupplier;
    private final Function<String, String> fileReader;

    public DebatePromptAssembler(Supplier<DebateSession> sessionSupplier,
                                  Function<String, String> fileReader) {
        this.sessionSupplier = sessionSupplier;
        this.fileReader = fileReader;
    }

    @Override
    public String assemble(AgentParticipant agent,
                           PartitionedDrain<String> drain,
                           ConversationState state) {
        var sb = new StringBuilder();
        DebateSession session = sessionSupplier.get();

        ComparisonPair comparison = session.currentComparison();
        if (comparison != null) {
            String contentA = fileReader.apply(comparison.pathA());
            String contentB = fileReader.apply(comparison.pathB());
            sb.append("## Document A (").append(comparison.pathA()).append(")\n\n");
            if (contentA != null && !contentA.isEmpty()) {
                sb.append(contentA).append("\n\n");
            }
            sb.append("## Document B (").append(comparison.pathB()).append(")\n\n");
            if (contentB != null && !contentB.isEmpty()) {
                sb.append(contentB).append("\n\n");
            }
        }

        SelectionScope sel = session.currentSelection();
        if (sel != null) {
            sb.append("## Selection (").append(sel.side())
              .append(" lines ").append(sel.startLine())
              .append("-").append(sel.endLine()).append(")\n")
              .append(sel.selectedText()).append("\n\n");
        }

        var obsResult = drain.currentPartition();
        if (obsResult != null && obsResult.renderedText() != null
                && !obsResult.renderedText().isEmpty()) {
            sb.append("## Context Since Your Last Turn\n\n")
              .append(obsResult.renderedText()).append("\n\n");
        }

        if (!state.points().isEmpty()) {
            sb.append("## Open Points: ").append(state.points().size()).append("\n\n");
        }

        return sb.toString();
    }
}
