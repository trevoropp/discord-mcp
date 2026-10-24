package dev.saseq.services;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Counts every MCP tool call: how many times each tool ran, how many bytes it returned, and how
 * long it took. The point is to give the agents a true, server-side measure of what their tool
 * use costs in context instead of estimating it from their own side. One log line is written per
 * call, and {@code get_tool_usage} returns the totals since the bot started.
 */
@Service
public class ToolUsageStats {

    private static final class Counter {
        final AtomicLong calls = new AtomicLong();
        final AtomicLong bytes = new AtomicLong();
        final AtomicLong millis = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        final AtomicLong maxBytes = new AtomicLong();
    }

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Instant since = Instant.now();

    /** Wraps a tool callback so every call is measured and logged. */
    public ToolCallback wrap(ToolCallback inner) {
        String name = inner.getToolDefinition().name();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return inner.getToolDefinition();
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return inner.getToolMetadata();
            }

            @Override
            public String call(String toolInput) {
                return measured(name, () -> inner.call(toolInput));
            }

            @Override
            public String call(String toolInput, ToolContext toolContext) {
                return measured(name, () -> inner.call(toolInput, toolContext));
            }
        };
    }

    private interface Call {
        String run();
    }

    private String measured(String name, Call call) {
        Counter c = counters.computeIfAbsent(name, k -> new Counter());
        long start = System.nanoTime();
        try {
            String result = call.run();
            long ms = (System.nanoTime() - start) / 1_000_000;
            long size = result == null ? 0 : result.getBytes(StandardCharsets.UTF_8).length;
            c.calls.incrementAndGet();
            c.bytes.addAndGet(size);
            c.millis.addAndGet(ms);
            c.maxBytes.accumulateAndGet(size, Math::max);
            System.out.println("tool " + name + " " + size + " bytes " + ms + " ms");
            return result;
        } catch (RuntimeException ex) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            c.calls.incrementAndGet();
            c.errors.incrementAndGet();
            c.millis.addAndGet(ms);
            System.out.println("tool " + name + " error " + ms + " ms: " + ex.getMessage());
            throw ex;
        }
    }

    @Tool(name = "get_tool_usage", description = "Server-side usage of every MCP tool since the bot started: calls, total and average bytes returned (what lands in your context), largest single response, average duration and error count. Use this to measure and reduce your own token cost. Pass reset=true to zero the counters after reading them.")
    public String getToolUsage(@ToolParam(description = "true = reset the counters after returning them (default false)", required = false) String reset) {
        if (counters.isEmpty()) {
            return "No tool calls recorded since " + since + ".";
        }
        long totalCalls = counters.values().stream().mapToLong(c -> c.calls.get()).sum();
        long totalBytes = counters.values().stream().mapToLong(c -> c.bytes.get()).sum();
        String rows = counters.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Counter> e) -> e.getValue().bytes.get()).reversed())
                .map(e -> {
                    Counter c = e.getValue();
                    long calls = c.calls.get();
                    long ok = Math.max(1, calls - c.errors.get());
                    return String.format("- %s: %d calls, %,d bytes total, %,d avg, %,d max, %d ms avg%s",
                            e.getKey(), calls, c.bytes.get(), c.bytes.get() / ok, c.maxBytes.get(), c.millis.get() / Math.max(1, calls),
                            c.errors.get() > 0 ? ", " + c.errors.get() + " error(s)" : "");
                })
                .collect(Collectors.joining("\n"));
        String out = "Tool usage since " + since + " (" + totalCalls + " calls, " + String.format("%,d", totalBytes)
                + " bytes returned, roughly " + String.format("%,d", totalBytes / 4) + " tokens):\n" + rows;
        if ("true".equalsIgnoreCase(reset)) {
            counters.clear();
            out += "\n(counters reset)";
        }
        return out;
    }
}
