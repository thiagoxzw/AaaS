package com.devopsaaas.tool.execution;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort masking of secrets that third-party content (logs, API responses) may contain, applied before
 * anything is stored or sent to the LLM (RNF-SEG-07b). It is not a guarantee: no pattern list recognizes every
 * possible secret, and this limitation is documented.
 */
final class SecretRedactor {

    record Result(String text, int redactions) {
    }

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile(
                    "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                    "<redacted:private-key>"),
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}"),
                    "<redacted:jwt>"),
            new Rule(Pattern.compile("(?i)\\b(bearer\\s+)(?!<redacted)[A-Za-z0-9._~+/=-]{8,}"),
                    "$1<redacted>"),
            new Rule(Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), "<redacted:aws-access-key>"),
            new Rule(Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@]+:)(?!<redacted)[^\\s@/]+@"),
                    "$1<redacted>@"),
            new Rule(Pattern.compile(
                    "(?i)\\b([a-z0-9_.-]*(?:password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key"
                            + "|private[_-]?key|credential)[a-z0-9_.-]*)(\\s*[:=]\\s*)"
                            + "(?!<redacted)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)"),
                    "$1$2<redacted>"));

    private SecretRedactor() {
    }

    static Result redact(String value) {
        if (value == null || value.isEmpty()) {
            return new Result(value, 0);
        }
        String text = value;
        int count = 0;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(text);
            StringBuilder replaced = new StringBuilder();
            while (matcher.find()) {
                count++;
                matcher.appendReplacement(replaced, rule.replacement());
            }
            matcher.appendTail(replaced);
            text = replaced.toString();
        }
        return new Result(text, count);
    }
}
