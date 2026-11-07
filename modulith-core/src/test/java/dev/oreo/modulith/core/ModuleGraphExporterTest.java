package dev.oreo.modulith.core;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModuleGraphExporterTest {
    @PluginModule("graph")
    static final class Module implements MinecraftModule {}

    @Test
    void mermaidNodeIdsDoNotCollideWhenNamesNormalizeTheSameWay() {
        ModuleDescriptor first = new ModuleDescriptor(
                "wallet.east", List.of(), Module.class, false);
        ModuleDescriptor second = new ModuleDescriptor(
                "wallet_east", List.of(), Module.class, false);
        String mermaid = ModuleGraphExporter.toMermaid(List.of(first, second));
        String[] nodes = mermaid.lines()
                .filter(line -> line.contains("[\"wallet"))
                .toArray(String[]::new);
        assertEquals(2, nodes.length);
        assertNotEquals(nodes[0].substring(0, nodes[0].indexOf('[')),
                nodes[1].substring(0, nodes[1].indexOf('[')));
    }
}
