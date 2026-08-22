package io.gen2spring.mcp.adapter.openapi.swagger;

final class SwaggerSchemaBudget {
    static final int MAX_DEPTH = 16;
    static final int MAX_BRANCHES_PER_COMPOSITION = 8;
    static final int MAX_TOTAL_BRANCHES = 64;

    private int branches;

    boolean acceptDepth(int depth) {
        return depth <= MAX_DEPTH;
    }

    boolean reserveBranches(int count) {
        if (count < 1 || count > MAX_BRANCHES_PER_COMPOSITION
                || branches + count > MAX_TOTAL_BRANCHES) {
            return false;
        }
        branches += count;
        return true;
    }
}
