package com.travelscope.common;

import java.util.regex.Pattern;

/** Immutable per-turn language; never inferred from shared session/thread state. */
public final class ReplyLanguage {
    public static final String KEY = "travelscope.replyLocale";
    private ReplyLanguage() {}
    public static String normalize(String locale) {
        if (locale == null) return "zh-CN";
        if (!locale.equals("zh-CN") && !locale.equals("en"))
            throw new IllegalArgumentException("Unsupported locale");
        return locale;
    }
    private static final Pattern EXPLICIT = Pattern.compile(
        "(?i)(?:请)?(?:用|使用|以)\\s*(中文|汉语|英文|英语)|(?:reply|respond|answer|write|explain)\\s+(?:to me\\s+)?(?:in\\s+)?(English|Chinese|Mandarin)|(?:in\\s+)(English|Chinese|Mandarin)\\s+please");
    public static String resolve(String locale, String text) {
        String result = normalize(locale);
        if (text == null || text.stripLeading().startsWith("{\"__form__\"")) return result;
        var match = EXPLICIT.matcher(text);
        while (match.find()) {
            String before=text.substring(Math.max(0,match.start()-8),match.start()).toLowerCase();
            if (before.matches("(?s).*(不要|不用|别|not|don't)\\s*$")) continue;
            String name=match.group(1)!=null?match.group(1):match.group(2)!=null?match.group(2):match.group(3);
            result = name.matches("(?i)英文|英语|English") ? "en" : "zh-CN";
        }
        return result;
    }
    public static String instruction(String locale) {
        return "\n[Response language] Use "+("en".equals(locale)?"English":"Simplified Chinese")+
          " for user-facing prose, generated descriptions and summaries. An explicit language request in the current user message takes priority. Keep JSON keys, tool arguments, verified place names, IDs and factual values unchanged. Propagate this instruction to delegated tasks.\n";
    }
    public static String annotation(String locale,String text) {
        if (!"en".equals(locale)) return text;
        return text.replace("已通过质量审阅（评分 ","Quality review passed (score ")
          .replace("当前最佳版本（已尽力，评分 ","Best available version (score ")
          .replace("，另有 ",", plus ").replace(" 项提示，详见质检明细）"," notices; see quality review details)")
          .replace(" 项未解决问题，详见质检明细）"," unresolved issues; see quality review details)")
          .replace("）",")");
    }
    public static String from(io.agentscope.core.agent.RuntimeContext context) {
        Object locale=context.get(KEY);
        return locale instanceof String s ? normalize(s) : "zh-CN";
    }
}
