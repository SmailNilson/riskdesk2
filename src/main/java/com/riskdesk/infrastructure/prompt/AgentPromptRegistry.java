package com.riskdesk.infrastructure.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches agent system-prompts from {@code classpath:/prompts/}.
 *
 * <p>Prompts used to live as 50-line {@code private static final String}s inside
 * each AI agent. Externalizing them to {@code *.md} resource files:
 * <ul>
 *   <li>keeps each agent class focused on its evaluation logic,</li>
 *   <li>makes prompt diffs readable in code review (no Java escaping),</li>
 *   <li>lets ops inspect what Gemini actually sees without opening the jar.</li>
 * </ul>
 *
 * <p>Lives in {@code infrastructure} because the only thing it does is read
 * classpath resources via Spring's {@link ClassPathResource}. No use-case
 * orchestration, no domain rules — pure I/O plumbing.
 *
 * <p>Not a {@code @ConfigurationProperties}: prompts are code, not operator
 * knobs. A bad prompt silently breaks trading — we don't want it tweakable via
 * {@code application.properties}.
 *
 * <p>Prompts are loaded lazily on first {@link #prompt(String)} call and cached
 * for the lifetime of the bean. A missing prompt throws
 * {@link IllegalStateException} — a typo in a key should fail loudly at boot or
 * in the first agent invocation, not silently downgrade to a blank prompt.
 */
@Service
public class AgentPromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptRegistry.class);
    private static final String BASE_PATH = "prompts/";

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * Returns the prompt for {@code name}, loading it from
     * {@code classpath:/prompts/<name>.md} on first call.
     *
     * @throws IllegalStateException if the prompt file is missing or unreadable
     */
    public String prompt(String name) {
        return cache.computeIfAbsent(name, this::loadFromClasspath);
    }

    private String loadFromClasspath(String name) {
        String path = BASE_PATH + name + ".md";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("Missing agent prompt: " + path);
        }
        try (var in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.info("Loaded agent prompt '{}' ({} chars)", name, content.length());
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt: " + path, e);
        }
    }
}
