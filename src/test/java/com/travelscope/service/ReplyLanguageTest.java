package com.travelscope.service;

import com.travelscope.common.ReplyLanguage;
import com.travelscope.collab.ItineraryDraft;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReplyLanguageTest {
    @Test void defaultsValidationAndExplicitRequests() {
        assertEquals("zh-CN",ReplyLanguage.normalize(null));
        assertThrows(IllegalArgumentException.class,()->ReplyLanguage.normalize("fr"));
        assertEquals("en",ReplyLanguage.resolve("en","杭州有哪些园林？"));
        assertEquals("zh-CN",ReplyLanguage.resolve("en","请用中文回答：杭州有哪些园林？"));
        assertEquals("en",ReplyLanguage.resolve("zh-CN","Please answer in English"));
        assertEquals("zh-CN",ReplyLanguage.resolve("en","Reply in Chinese please"));
        assertEquals("en",ReplyLanguage.resolve("en","不要用中文回答"));
        assertEquals("en",ReplyLanguage.resolve("en","{\"__form__\":{\"special\":\"请用中文\"}}"));
        assertEquals("en",ReplyLanguage.resolve("zh-CN","请用中文，不，请用英文回答"));
    }
    @Test void localizedAssemblerAndPreviewKeepCanonicalFieldsAndChineseCompatibility() {
        var day=new ItineraryDraft.DraftDay(1,"2026-11-01","Gardens",List.of(new ItineraryDraft.DraftItem("09:00-10:00","苏州博物馆","苏州博物馆","CNY 0","Book ahead")));
        var draft=new ItineraryDraft(List.of(day),new ItineraryDraft.Budget("CNY","100",List.of()),List.of("Bring water"),List.of("Confirm dates"),"");
        var assembler=new ItineraryAssembler();
        String english=assembler.assemble(draft,null,null,null,null,null,List.of(),"en").markdownBody();
        assertTrue(english.contains("| Time | Activity | Place | Cost |"));
        assertTrue(english.contains("## Budget"));assertTrue(english.contains("## To confirm"));assertTrue(english.contains("苏州博物馆"));
        assertTrue(english.contains(assembler.renderDaySection(day,"en")));
        assertTrue(assembler.assemble(draft,null,null,null,null).markdownBody().contains("| 时间 | 活动 | 地点 | 费用 |"));
        assertTrue(ReplyLanguage.instruction("en").contains("English"));
    }
    @Test void cachedResultTemplatesUseLocaleAndKeepSupplierValues() {
        String hotel="{\"hotels\":[{\"name\":\"苏州酒店\",\"price\":\"200\",\"address\":\"苏州\"}]}";
        assertTrue(TaskResultFormatter.formatHotel(hotel,"en").contains("Hotel | Type | Indicative price"));
        assertTrue(TaskResultFormatter.formatHotel(hotel,"en").contains("苏州酒店"));
        assertTrue(TaskResultFormatter.formatHotel(hotel).contains("酒店 | 类型"));
        String weather="{\"forecasts\":[{\"date\":\"2026-11-01\",\"temp_min\":\"10\",\"temp_max\":\"20\",\"text_day\":\"晴\",\"uv_index\":\"6\"}]}";
        assertTrue(TaskResultFormatter.formatWeather(weather,"en").contains("Date | Day | Night"));
        assertTrue(TaskResultFormatter.formatWeather(weather,"en").contains("Clothing advice: Minimum temperature 10"));
        assertEquals("第三方原始文本",TaskResultFormatter.formatTrain("第三方原始文本","en"));
    }
}
