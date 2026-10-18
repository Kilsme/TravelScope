package com.travelscope.assessment;

import com.travelscope.collab.CollabSchemaValidator;
import com.travelscope.collab.ItineraryDraft;
import java.util.Map;

/** JSON 契约与旧 Markdown 共用同一套最终正文选择规则。 */
final class PlanningArtifacts {
    static final String JSON_NAME = "itinerary_draft.json";
    static final String MARKDOWN_NAME = "itinerary_draft.md";

    private PlanningArtifacts() {}

    static String text(String name, String body) {
        if (body == null || body.isBlank()) return null;
        if (!JSON_NAME.equals(name)) return body;
        try {
            String markdown = CollabSchemaValidator.parse(body, ItineraryDraft.class).markdownBody();
            return markdown.isBlank() ? null : markdown;
        } catch (RuntimeException invalidContract) {
            return null;
        }
    }

    static String name(Map<String, String> artifacts) {
        if (text(JSON_NAME, artifacts.get(JSON_NAME)) != null) return JSON_NAME;
        return text(MARKDOWN_NAME, artifacts.get(MARKDOWN_NAME)) == null ? null : MARKDOWN_NAME;
    }

    static String text(Map<String, String> artifacts) {
        String name = name(artifacts);
        return name == null ? null : text(name, artifacts.get(name));
    }
}
