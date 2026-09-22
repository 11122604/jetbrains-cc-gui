package com.github.claudecodegui.action.editor;

import java.util.List;

/**
 * An AI agent (CLI channel) the plugin can talk to, and whether edit-history search
 * is implemented for it.
 *
 * <p>This is the extension point for supporting more agents in "Find AI Edit History".
 * Only Claude history is indexed today; the other agents show up in the result popup
 * but cannot be selected until their own index/search lands. Adding support is a matter
 * of flipping {@code searchSupported} to true and providing an index builder for the
 * agent.
 *
 * @author luliang
 */
public enum AgentType {

    CLAUDE("claude", "Claude Code", true),
    CODEX("codex", "Codex", false),
    DSH("dsh", "DSH", false),
    GROK("grok", "Grok", false),
    KIMI("kimi", "Kimi", false),
    MINIMAX("minimax", "MiniMax", false),
    OMP("omp", "OMP", false),
    OPENCODE("opencode", "OpenCode", false),
    PI("pi", "Pi", false),
    ZCODE("zcode", "zcode", false);

    /** Stable id, matching the ai-bridge channel name. */
    private final String id;
    private final String displayName;
    /** Whether edit-history search is implemented for this agent. */
    private final boolean searchSupported;

    AgentType(String id, String displayName, boolean searchSupported) {
        this.id = id;
        this.displayName = displayName;
        this.searchSupported = searchSupported;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSearchSupported() {
        return searchSupported;
    }

    /** All selectable agents in display order (Claude first). */
    public static List<AgentType> all() {
        return List.of(values());
    }
}
