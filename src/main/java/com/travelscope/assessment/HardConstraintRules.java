package com.travelscope.assessment;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;
public final class HardConstraintRules {
    private static final Pattern DAY=Pattern.compile("(?im)^\\s*#{0,6}\\s*(?:第\\s*([0-9]+)\\s*天|Day\\s+([0-9]+))");
    private static final Pattern RANGE=Pattern.compile("(?<![0-9])(\\d{1,2}:\\d{2})\\s*[-–—~至]\\s*(\\d{1,2}:\\d{2})");
    public static List<AssessmentRules.Result> check(String plan,Integer expectedDays,BigDecimal limit){
        plan=plan==null?"":plan;
        List<AssessmentRules.Result> result=new ArrayList<>();
        var day=DAY.matcher(plan);Set<Integer> days=new HashSet<>();List<Integer> positions=new ArrayList<>();
        while(day.find()){days.add(Integer.parseInt(day.group(1)!=null?day.group(1):day.group(2)));positions.add(day.start());}
        result.add(new AssessmentRules.Result("DAYS",expectedDays==null||days.isEmpty()?"UNKNOWN":days.size()==expectedDays?"PASS":"FAIL","HARD_CONSTRAINT","明确日标题="+days+"; expected="+expectedDays));
        boolean known=false,overlap=false,invalid=false;
        for(int i=0;i<positions.size();i++){
            String section=plan.substring(positions.get(i),i+1<positions.size()?positions.get(i+1):plan.length());
            var matcher=RANGE.matcher(section);List<int[]> intervals=new ArrayList<>();
            while(matcher.find()){
                try { int start=LocalTime.parse(pad(matcher.group(1))).toSecondOfDay();int end=LocalTime.parse(pad(matcher.group(2))).toSecondOfDay();
                    if(end<=start){invalid=true;continue;}intervals.add(new int[]{start,end});known=true;
                }catch(Exception e){invalid=true;}
            }
            intervals.sort(Comparator.comparingInt(a->a[0]));int previous=-1;
            for(int[] interval:intervals){if(interval[0]<previous)overlap=true;previous=Math.max(previous,interval[1]);}
        }
        result.add(new AssessmentRules.Result("TIME_OVERLAP",overlap?"FAIL":!known||invalid?"UNKNOWN":"PASS","HARD_CONSTRAINT",overlap?"存在明确时段重叠":"仅检查带日标题的明确同日区间；点时刻或跨夜无法可靠判定"));
        // Deliberately restricted accounting grammar: explicit item lines and explicit total, never arbitrary numbers.
        Pattern item=Pattern.compile("(?m)^\\s*[-*]\\s*(?:费用项|cost-item)\\s*[:：]\\s*[^=\\n]+?=\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|CNY)\\s*$",Pattern.CASE_INSENSITIVE);
        Pattern total=Pattern.compile("(?m)^\\s*(?:合计|总计|total)\\s*[:：]\\s*(\\d+(?:\\.\\d{1,2})?)\\s*(?:元|CNY)\\s*$",Pattern.CASE_INSENSITIVE);
        var amounts=item.matcher(plan);BigDecimal sum=BigDecimal.ZERO;int n=0;while(amounts.find()){sum=sum.add(new BigDecimal(amounts.group(1)));n++;}
        var totals=total.matcher(plan);BigDecimal declared=totals.find()?new BigDecimal(totals.group(1)):null;
        boolean ambiguous=totals.find();
        String status=n==0||declared==null||limit==null||ambiguous?"UNKNOWN":sum.compareTo(declared)==0&&sum.compareTo(limit)<=0?"PASS":"FAIL";
        result.add(new AssessmentRules.Result("BUDGET_SUM_AND_LIMIT",status,"HARD_CONSTRAINT","独立费用项="+n+", sum="+sum+", declared="+declared+", limit="+limit+"；费用项完整性未由工具证据确认"));
        return result;
    }
    private static String pad(String time){return time.length()==4?"0"+time:time;}
}
