package io.gen2spring.mcp.application.toolmodel.schema;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class SchemaPatternMatcher {
    private static final int MAX_PATTERN_CHARACTERS = 512;
    private static final int MAX_VALUE_CHARACTERS = 8_192;
    private static final int MAX_CHARACTER_ACCESSES = 100_000;

    private SchemaPatternMatcher() {}

    public static boolean matches(String expression, String value) {
        if (expression == null || value == null || expression.length() > MAX_PATTERN_CHARACTERS
                || value.length() > MAX_VALUE_CHARACTERS) {
            return false;
        }
        try {
            return Pattern.compile(expression)
                    .matcher(new BudgetedCharSequence(value, MAX_CHARACTER_ACCESSES))
                    .matches();
        } catch (PatternSyntaxException | PatternBudgetExceededException | StackOverflowError failure) {
            return false;
        }
    }

    private static final class BudgetedCharSequence implements CharSequence {
        private final String value;
        private final PatternBudget budget;
        private final int start;
        private final int end;

        private BudgetedCharSequence(String value, int maximumAccesses) {
            this(value, new PatternBudget(maximumAccesses), 0, value.length());
        }

        private BudgetedCharSequence(String value, PatternBudget budget, int start, int end) {
            this.value = value;
            this.budget = budget;
            this.start = start;
            this.end = end;
        }

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= length()) throw new IndexOutOfBoundsException(index);
            budget.consume();
            return value.charAt(start + index);
        }

        @Override
        public CharSequence subSequence(int subsequenceStart, int subsequenceEnd) {
            if (subsequenceStart < 0 || subsequenceEnd < subsequenceStart || subsequenceEnd > length()) {
                throw new IndexOutOfBoundsException();
            }
            return new BudgetedCharSequence(value, budget, start + subsequenceStart, start + subsequenceEnd);
        }

        @Override
        public String toString() {
            return value.substring(start, end);
        }
    }

    private static final class PatternBudget {
        private int remaining;

        private PatternBudget(int remaining) {
            this.remaining = remaining;
        }

        private void consume() {
            if (remaining-- <= 0) throw new PatternBudgetExceededException();
        }
    }

    private static final class PatternBudgetExceededException extends RuntimeException {
        private PatternBudgetExceededException() {
            super(null, null, false, false);
        }
    }
}
