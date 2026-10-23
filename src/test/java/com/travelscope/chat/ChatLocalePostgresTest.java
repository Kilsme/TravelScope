package com.travelscope.chat;

import com.alibaba.fastjson2.JSON;
import com.travelscope.dto.ChatRequest;
import com.travelscope.service.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatLocalePostgresTest {
    final ChatTurnPostgresTest fixture=new ChatTurnPostgresTest();
    @BeforeEach void setup() throws Exception { fixture.database(); }
    @AfterEach void close() { fixture.cleanup(); }
    private String hash(ChatRequest request,boolean modern) throws Exception {
        var values=new ArrayList<Object>(Arrays.asList(request.getConversationId(),request.getMessage(),request.getSourceTurnId(),null));
        if(modern)values.add(com.travelscope.common.ReplyLanguage.normalize(request.getLocale()));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.toJSONString(values).getBytes(StandardCharsets.UTF_8)));
    }
    private ChatTurnService service() {
        var service=new ChatTurnService(fixture.store,mock(ChatService.class),mock(ConversationService.class),mock(MinioService.class),new ReconnectProperties());
        fixture.service=service;service.recover();return service;
    }
    @Test void newTurnStoresBothLanguagesAndRejectsChangedRetry() throws Exception {
        ChatRequest r=fixture.request(null);r.setLocale("en");r.setMessage("请用中文介绍杭州");
        var turn=fixture.store.reserve(1,r,hash(r,true),r.getMessage(),null);
        assertEquals("en",turn.locale());assertEquals("zh-CN",turn.responseLocale());assertEquals(2,turn.requestHashVersion());
        fixture.store.prepare(1,turn.turnId());fixture.finish(turn,"SUCCEEDED");
        var runner=service();assertEquals(turn.turnId(),runner.submit(1,r).turnId());
        r.setLocale("zh-CN");var error=assertThrows(TurnException.class,()->runner.submit(1,r));assertEquals("IDEMPOTENCY_CONFLICT",error.code);
        assertEquals(1,fixture.count("chat_turns"));assertEquals(2,fixture.count("messages"));
    }
    @Test void legacyHashSurvivesIncrementalMigrationAndMissingLocaleRetry() throws Exception {
        ChatRequest r=fixture.request(null);String oldHash=hash(r,false);
        var turn=fixture.store.reserve(1,r,oldHash,r.getMessage(),null);fixture.store.prepare(1,turn.turnId());fixture.finish(turn,"SUCCEEDED");
        fixture.db.execute("ALTER TABLE chat_turns DROP COLUMN locale,DROP COLUMN response_locale,DROP COLUMN request_hash_version");
        try(var c=fixture.db.getDataSource().getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("chat-locale-upgrade.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("chat-locale-upgrade.sql"));
        }
        var runner=service();assertEquals(turn.turnId(),runner.submit(1,r).turnId());
        assertEquals(oldHash,fixture.store.owned(1,turn.turnId()).requestHash());
        r.setLocale("en");assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(TurnException.class,()->runner.submit(1,r)).code);
    }
}
