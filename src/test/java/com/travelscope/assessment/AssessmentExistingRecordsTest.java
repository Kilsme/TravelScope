package com.travelscope.assessment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Explicit, read-only verification of existing local records. Never writes data or invokes a model. */
@EnabledIfEnvironmentVariable(named="ASSESSMENT_VERIFY_EXISTING",matches="true")
class AssessmentExistingRecordsTest {
    @Test void existingRecordsAndActualModelTotalsRemainConsistent() throws Exception {
        Properties credentials=new Properties();
        for(String key:List.of("USER","PASSWORD")){String value=System.getenv("ASSESSMENT_TEST_DATABASE_"+key);if(value!=null)credentials.setProperty(key.toLowerCase(Locale.ROOT),value);}
        try(Connection connection=DriverManager.getConnection(System.getenv("ASSESSMENT_TEST_DATABASE_URL"),credentials)){
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);connection.setReadOnly(true);connection.setAutoCommit(false);
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            long runs=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM assessment_runs",Long.class));
            assumeTrue(runs>0,"No existing assessment records to inspect");
            var queries=new AssessmentQueries(jdbc);
            int groups=queries.components("ONLINE",null,"PLANNING",null,null).size()
              +queries.components("ASSESSMENT","FIXTURE","PLANNING",null,null).size()
              +queries.components("ASSESSMENT","LIVE","PLANNING",null,null).size();
            var records=queries.runs(null,0,5);int nodes=0,orphans=0;
            for(var record:records){
                var spans=queries.spans((UUID)record.get("id"));nodes+=spans.size();
                var ids=new HashSet<>();spans.forEach(span->ids.add(span.get("id")));
                Long expected=null;
                for(var span:spans){
                    if(span.get("parent_id")!=null&&!ids.contains(span.get("parent_id")))orphans++;
                    if("MODEL".equals(span.get("kind")))for(String key:List.of("input_tokens","output_tokens"))if(span.get(key) instanceof Number value)expected=(expected==null?0:expected)+value.longValue();
                }
                if(expected==null)assertNull(record.get("tokens"));else assertEquals(expected,((Number)record.get("tokens")).longValue());
            }
            long activeRuns=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM assessment_runs WHERE status='RUNNING'",Long.class));
            long activeBatches=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) FROM assessment_batches WHERE status IN ('QUEUED','RUNNING','CANCELLING')",Long.class));
            System.out.println("Existing records verified (read only): runs="+runs+", inspected="+records.size()+", nodes="+nodes+", missingParents="+orphans+", componentGroups="+groups+", activeRuns="+activeRuns+", activeBatches="+activeBatches);
            connection.rollback();
        }
    }
}
