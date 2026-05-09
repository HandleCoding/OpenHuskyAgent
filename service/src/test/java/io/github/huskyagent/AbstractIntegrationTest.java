package io.github.huskyagent;

import io.github.huskyagent.domain.context.ContextManager;
import io.github.huskyagent.domain.session.SessionManager;
import io.github.huskyagent.infra.memory.*;
import io.github.huskyagent.infra.tool.registry.ToolRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
abstract class AbstractIntegrationTest {

    @Autowired
    protected SessionManager sessionManager;

    @Autowired
    protected ContextManager contextManager;

    @Autowired
    protected ToolRegistry toolRegistry;

    @Autowired
    protected io.github.huskyagent.infra.tool.executor.ToolExecutor toolExecutor;

    @Autowired
    protected MemoryManager memoryManager;

    @Autowired
    protected BuiltinMemoryProvider builtinMemoryProvider;

    @Autowired
    protected SessionMemoryProvider sessionMemoryProvider;

    @Autowired
    protected MemorySecurityScanner securityScanner;

    @Autowired
    protected BuiltinMemoryToolProvider builtinToolProvider;

    @Autowired
    protected SessionMemoryToolProvider sessionToolProvider;

    protected static Path tempDir;

    @BeforeAll
    static void setUpTempDir() throws Exception {
        tempDir = Files.createTempDirectory("agent-test");
    }

    @AfterAll
    static void tearDownTempDir() throws Exception {
        if (tempDir != null) {
            Files.walk(tempDir)
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception e) { }
                });
        }
    }
}
