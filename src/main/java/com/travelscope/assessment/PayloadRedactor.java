package com.travelscope.assessment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

/** Redact before persistence, preserving JSON types and decoding embedded JSON strings recursively. */
@Component
public class PayloadRedactor {
    private static final int MAX_DEPTH = 32;
    private static final Set<String> SENSITIVE = Set.of("apikey", "authorization", "password", "secret", "token",
            "passport", "idcard", "idnumber", "fullname", "realname", "address", "mobile", "phone",
            "accesstoken", "refreshtoken", "cookie", "clientsecret", "privatekey", "sessiontoken", "authtoken");
    // Malformed/escaped credential-bearing text is discarded rather than risking a partial regex match.
    private static final Pattern CREDENTIAL_TEXT = Pattern.compile(
            "(?i)(?:api[_-]?key|authorization|password|secret|(?:access[_-]?|refresh[_-]?|session[_-]?)?token|cookie|private[_-]?key)[\\\\\"'\\s]*[:=]");
    private final ObjectMapper mapper;
    public PayloadRedactor(ObjectMapper mapper) { this.mapper = mapper; }

    public String redact(Object value) {
        try {
            if (value instanceof CharSequence text) return text(text.toString(), 0);
            return mapper.writeValueAsString(node(mapper.valueToTree(value), 0));
        } catch (Exception e) { return "[UNSERIALIZABLE]"; }
    }

    private JsonNode node(JsonNode value, int depth) throws Exception {
        if (depth > MAX_DEPTH) return TextNode.valueOf("[REDACTED: NESTING_LIMIT]");
        if (value.isObject()) {
            ObjectNode copy = mapper.createObjectNode();
            var fields = value.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String key = field.getKey().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                copy.set(field.getKey(), SENSITIVE.contains(key) ? TextNode.valueOf("[REDACTED]") : node(field.getValue(), depth + 1));
            }
            return copy;
        }
        if (value.isArray()) {
            ArrayNode copy = mapper.createArrayNode();
            for (var item : value) copy.add(node(item, depth + 1));
            return copy;
        }
        return value.isTextual() ? TextNode.valueOf(text(value.textValue(), depth + 1)) : value;
    }

    private String text(String value, int depth) throws Exception {
        if (depth > MAX_DEPTH) return "[REDACTED: NESTING_LIMIT]";
        String trimmed = value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
            try {
                JsonNode parsed = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(trimmed);
                if (parsed != null) {
                    if (parsed.isTextual()) return mapper.writeValueAsString(text(parsed.textValue(), depth + 1));
                    return mapper.writeValueAsString(node(parsed, depth + 1));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { /* malformed text: fail closed below */ }
        }
        if (CREDENTIAL_TEXT.matcher(value).find()) return "[REDACTED: CREDENTIAL_TEXT]";
        return value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)sk-[A-Za-z0-9_-]+", "[KEY]")
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}", "[EMAIL]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "[PHONE]")
                .replaceAll("(?<!\\d)\\d{17}[\\dXx](?!\\d)", "[ID]");
    }
}
