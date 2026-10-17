package com.travelscope.assessment;
import com.travelscope.eval.*;
import java.util.*;
/** Unknowns never count as passed; keyword checks are explicitly heuristic. */
public final class AssessmentRules {
    public record Result(String type,String status,String method,String evidence){}
    public static List<Result> check(EvalCase c,EvalRunArtifact artifact,List<Map<String,Object>> spans){
        List<Result> result=new ArrayList<>();
        for(var rule:c.expectations().rules()){
            if("WAITING_INPUT".equals(rule.params().get("expectedOutcome"))){
                boolean clarified=spans.stream().anyMatch(span->"ask_user".equals(span.get("name"))&&Set.of("SUCCESS","SUCCEEDED").contains(span.get("status")));
                result.add(new Result("EXPECTED_WAITING_INPUT",clarified&&artifact.itineraryText()==null?"PASS":"UNKNOWN","EVENT_EVIDENCE","检查实际 ask_user 事件及无完整产物"));
            }else if(rule.type()==RuleType.TOOL_ORDER){
                result.add(dependency(rule.params().get("before"),rule.params().get("after"),spans));
            }else{
                var r=RuleAsserters.assertRule(rule,artifact);
                result.add(new Result(r.type(),r.skipped()?"UNKNOWN":r.passed()?"PASS":"FAIL","HEURISTIC",r.details()));
            }
        }
        Integer days=null;java.math.BigDecimal budget=null;
        for(var rule:c.expectations().rules()) {
            if(rule.params().containsKey("expectedDays"))days=Integer.valueOf(rule.params().get("expectedDays"));
            if(rule.params().containsKey("budgetLimit"))budget=new java.math.BigDecimal(rule.params().get("budgetLimit"));
        }
        result.addAll(HardConstraintRules.check(artifact.itineraryOrReply(),days,budget));
        return result;
    }
    static Result dependency(String before,String after,List<Map<String,Object>> spans){
        if(before==null||after==null)return new Result("TOOL_ORDER","UNKNOWN","EVENT_DEPENDENCY","用例未声明明确调用依赖");
        var predecessors=spans.stream().filter(s->before.equals(s.get("name"))&&Set.of("SUCCEEDED","SUCCESS").contains(s.get("status"))&&s.get("ended_at")!=null).toList();
        var successors=spans.stream().filter(s->after.equals(s.get("name"))&&s.get("started_at")!=null).toList();
        if(predecessors.isEmpty()||successors.isEmpty())return new Result("TOOL_ORDER","UNKNOWN","EVENT_DEPENDENCY","缺少所需调用事件："+before+" -> "+after);
        try {
            boolean valid=successors.stream().allMatch(next->predecessors.stream().anyMatch(previous->!instant(previous.get("ended_at")).isAfter(instant(next.get("started_at")))));
            return new Result("TOOL_ORDER",valid?"PASS":"FAIL","EVENT_DEPENDENCY",before+" 成功结束先于 "+after+" 开始；其余工具允许并行");
        }catch(Exception e){return new Result("TOOL_ORDER","UNKNOWN","EVENT_DEPENDENCY","调用事件时间不可解析");}
    }
    private static java.time.Instant instant(Object value){if(value instanceof java.sql.Timestamp t)return t.toInstant();if(value instanceof java.time.OffsetDateTime t)return t.toInstant();return java.time.Instant.parse(value.toString());}

}
