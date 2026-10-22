package io.casehub.drafthouse.debate;

import io.casehub.agency.channel.ChannelMessageMeta;
import io.casehub.agency.conversation.ConversationProtocol;
import io.casehub.drafthouse.DocumentSide;
import io.casehub.drafthouse.SelectionScope;
import io.casehub.drafthouse.ThreadEntry;
import io.casehub.drafthouse.ThreadStatus;
import io.casehub.qhorus.api.spi.ChannelProjection;
import io.casehub.qhorus.api.message.MessageView;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@ApplicationScoped
public class ThreadProjection implements ChannelProjection<ThreadState> {

    private static final Logger LOG = Logger.getLogger(ThreadProjection.class.getName());

    @Override
    public ThreadState identity() { return ThreadState.empty(); }

    @Override
    public ThreadState apply(ThreadState state, MessageView message) {
        try {
            Map<String, String> meta = ChannelMessageMeta.parseMeta(
                    DebateProtocol.META_SENTINEL, message.content());
            String threadId = meta.get("threadId");
            if (threadId == null) return state;

            String action = meta.get("threadAction");
            if (action == null) {
                LOG.warning("Thread message missing threadAction — discarded");
                return state;
            }

            String role = meta.get(ConversationProtocol.ROLE);
            String body = ChannelMessageMeta.bodyContent(
                    DebateProtocol.META_SENTINEL, message.content());

            return switch (action) {
                case "START" -> applyStart(state, threadId, role, body, meta, message);
                case "REPLY" -> applyReply(state, threadId, role, body, message);
                case "RESOLVE" -> applyResolve(state, threadId);
                default -> {
                    LOG.warning("Unknown threadAction: " + action + " — discarded");
                    yield state;
                }
            };
        } catch (Exception e) {
            LOG.log(Level.WARNING, "ThreadProjection.apply() failed — discarded", e);
            return state;
        }
    }

    private ThreadState applyStart(ThreadState state, String threadId,
                                    String role, String body,
                                    Map<String, String> meta, MessageView msg) {
        String sideStr = meta.get("side");
        String startStr = meta.get("startLine");
        String endStr = meta.get("endLine");
        String selectedText = meta.get("selectedText");

        if (sideStr == null || startStr == null || endStr == null || selectedText == null) {
            LOG.warning("Thread START missing anchor fields — discarded");
            return state;
        }

        DocumentSide side;
        try { side = DocumentSide.valueOf(sideStr); }
        catch (IllegalArgumentException e) {
            LOG.warning("Thread START invalid side: " + sideStr + " — discarded");
            return state;
        }

        int startLine, endLine;
        try {
            startLine = Integer.parseInt(startStr);
            endLine = Integer.parseInt(endStr);
        } catch (NumberFormatException e) {
            LOG.warning("Thread START invalid line numbers — discarded");
            return state;
        }

        SelectionScope anchor = new SelectionScope(side, startLine, endLine, selectedText);
        ThreadEntry entry = new ThreadEntry(threadId, msg.sender(), body, role, msg.createdAt());
        ThreadView view = new ThreadView(threadId, anchor, ThreadStatus.OPEN,
                List.of(entry), role);

        Map<String, ThreadView> updated = new LinkedHashMap<>(state.threads());
        updated.put(threadId, view);
        return new ThreadState(Map.copyOf(updated));
    }

    private ThreadState applyReply(ThreadState state, String threadId,
                                    String role, String body, MessageView msg) {
        ThreadView existing = state.threads().get(threadId);
        if (existing == null) {
            LOG.warning("Thread REPLY for unknown thread " + threadId + " — discarded");
            return state;
        }

        ThreadEntry entry = new ThreadEntry(threadId, msg.sender(), body, role, msg.createdAt());
        List<ThreadEntry> entries = new ArrayList<>(existing.entries());
        entries.add(entry);
        ThreadView updated = new ThreadView(existing.threadId(), existing.anchor(),
                existing.status(), List.copyOf(entries), existing.createdBy());

        Map<String, ThreadView> threads = new LinkedHashMap<>(state.threads());
        threads.put(threadId, updated);
        return new ThreadState(Map.copyOf(threads));
    }

    private ThreadState applyResolve(ThreadState state, String threadId) {
        ThreadView existing = state.threads().get(threadId);
        if (existing == null) {
            LOG.warning("Thread RESOLVE for unknown thread " + threadId + " — discarded");
            return state;
        }

        ThreadView resolved = new ThreadView(existing.threadId(), existing.anchor(),
                ThreadStatus.RESOLVED, existing.entries(), existing.createdBy());

        Map<String, ThreadView> threads = new LinkedHashMap<>(state.threads());
        threads.put(threadId, resolved);
        return new ThreadState(Map.copyOf(threads));
    }
}
