package com.ai.agent.tool.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolArgumentsHasherTest {
    private final ToolArgumentsHasher hasher = new ToolArgumentsHasher();

    @Test
    void canonicalHashIgnoresConfirmTokenAndDryRunAndSortsKeys() {
        String dryRun = """
                {"dryRun":true,"orderId":"O-1001","reason":"buyer requested"}
                """;
        String confirm = """
                {"reason":"buyer requested","confirmToken":"ct_abc","orderId":"O-1001"}
                """;

        assertThat(hasher.hash(dryRun)).isEqualTo(hasher.hash(confirm));
    }
}
