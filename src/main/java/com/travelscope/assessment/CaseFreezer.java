package com.travelscope.assessment;
import com.travelscope.eval.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
/** Deterministic test calendar policy, not a statement about official holiday opening dates. */
final class CaseFreezer {
    static final String VERSION="calendar-v1";
    private static final java.util.regex.Pattern ISO_DATE=java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static EvalCase freeze(EvalCase c,LocalDate base){
        List<String> messages=new ArrayList<>();
        for(String input:c.userMessages()){
            String value=input.replace("大后天",base.plusDays(3).toString()).replace("后天",base.plusDays(2).toString()).replace("明天",base.plusDays(1).toString()).replace("今天",base.toString())
                .replace("下个月",base.plusMonths(1).withDayOfMonth(1).toString()).replace("下月",base.plusMonths(1).withDayOfMonth(1).toString())
                .replace("下周末",base.with(TemporalAdjusters.next(DayOfWeek.SATURDAY)).toString()).replace("周末",base.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).toString())
                .replace("下周",base.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toString())
                .replace("五一假期",annual(base,5,1)+"（测试起始日）").replace("国庆",annual(base,10,1)+"（测试起始日）").replace("暑假",annual(base,7,1)+"（测试起始日）");
            for(var weekday:Map.of("周一",DayOfWeek.MONDAY,"周二",DayOfWeek.TUESDAY,"周三",DayOfWeek.WEDNESDAY,"周四",DayOfWeek.THURSDAY,"周五",DayOfWeek.FRIDAY,"周六",DayOfWeek.SATURDAY,"周日",DayOfWeek.SUNDAY).entrySet())
                value=value.replace(weekday.getKey(),base.with(TemporalAdjusters.nextOrSame(weekday.getValue())).toString());
            messages.add(value);
        }
        if(c.category()!=EvalCategory.MISSING_INFORMATION&&!ISO_DATE.matcher(messages.getFirst()).find())
            messages.set(0,"出发日期为"+base.plusDays(7)+"。"+messages.getFirst());
        EvalCase.Requirements requirements=c.requirements();
        if(requirements!=null&&"@case-date".equals(requirements.startDate())){
            var matcher=ISO_DATE.matcher(messages.getFirst());
            if(!matcher.find())throw new IllegalStateException(c.caseId()+" 冻结后仍缺少出发日期");
            requirements=new EvalCase.Requirements(requirements.destination(),requirements.days(),matcher.group(),
                    requirements.fromCity(),requirements.budget(),requirements.preference(),
                    requirements.people(),requirements.special());
        }
        return new EvalCase(c.caseId(),c.category(),List.copyOf(messages),requirements,c.expectations());
    }
    private static LocalDate annual(LocalDate base,int month,int day){LocalDate target=LocalDate.of(base.getYear(),month,day);return target.isBefore(base)?target.plusYears(1):target;}
}
