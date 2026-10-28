package com.travelscope.service.budget;

import com.travelscope.price.Money;
import com.travelscope.price.PriceFact;
import com.travelscope.price.PriceUnit;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.travelscope.collab.ItineraryDraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 确定性预算纯函数：缺价进入 pending，金额统一在乘数后按币种保留两位。 */
public class BudgetCalculator {
    private static final Logger log = LoggerFactory.getLogger(BudgetCalculator.class);
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    public ItineraryDraft.Budget calculateLegacy(Input input) {
        return toLegacyBudget(calculate(input));
    }

    public Result calculate(Input input) {
        Objects.requireNonNull(input, "input");
        List<CalculatedItem> items = new ArrayList<>();
        List<String> pending = new ArrayList<>(input.pending());
        EnumMap<Category, BigDecimal> sums = new EnumMap<>(Category.class);
        String currency = "CNY";
        for (PricedItem item : input.items()) {
            if (item.price() == null) {
                pending.add(item.name() + "：缺失价格");
                continue;
            }
            currency = item.price().currency();
            long multiplier = multiplier(item, input);
            BigDecimal amount = item.price().amount().multiply(BigDecimal.valueOf(multiplier))
                    .setScale(2, RoundingMode.HALF_UP);
            Money money = new Money(amount, currency);
            items.add(new CalculatedItem(item.name(), item.category(), money,
                    item.price().source(), item.price().estimated()));
            sums.merge(item.category(), amount, BigDecimal::add);
        }
        String finalCurrency = currency;
        List<Breakdown> breakdown = sums.entrySet().stream()
                .map(e -> new Breakdown(e.getKey(), new Money(e.getValue().setScale(2, RoundingMode.HALF_UP), finalCurrency)))
                .toList();
        BigDecimal total = breakdown.stream().map(b -> b.amount().amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new Result(List.copyOf(items), breakdown, new Money(total, currency), List.copyOf(pending));
    }

    /** 将确定性结果适配到 schemaVersion=4 的旧 Budget JSON 形状。 */
    public ItineraryDraft.Budget toLegacyBudget(Result result) {
        List<ItineraryDraft.BudgetItem> items = result.breakdown().stream().map(b -> {
            List<CalculatedItem> categoryItems = result.items().stream()
                    .filter(i -> i.category() == b.category()).toList();
            String sources = categoryItems.stream().map(CalculatedItem::source).distinct()
                    .collect(java.util.stream.Collectors.joining("/"));
            boolean estimated = categoryItems.stream().anyMatch(CalculatedItem::estimated);
            String sourceNote = (estimated ? "估算；" : "") + "来源=" + sources;
            return new ItineraryDraft.BudgetItem(categoryLabel(b.category()),
                    b.amount().amount().toPlainString(), sourceNote);
        }).toList();
        return new ItineraryDraft.Budget(result.total().currency(),
                result.total().amount().toPlainString(), items);
    }

    private static String categoryLabel(Category category) {
        return switch (category) {
            case TICKET -> "门票";
            case HOTEL -> "住宿";
            case INTERCITY_TRANSPORT -> "城际交通";
            case LOCAL_TRANSPORT -> "市内交通";
            case MEAL -> "餐饮";
        };
    }

    /** 双算观测：保留旧 LLM Budget，不改展示，只记录可解析总额与代码总额差异。 */
    public void logComparison(ItineraryDraft.Budget legacyBudget, Result calculated) {
        if (legacyBudget == null) {
            log.info("budget_dual_calculation legacy=missing calculated={} pending={}",
                    calculated.total().amount(), calculated.pending().size());
            return;
        }
        Matcher matcher = NUMBER.matcher(legacyBudget.totalEstimate());
        if (!matcher.find()) {
            log.info("budget_dual_calculation legacy=unparseable calculated={} raw={}",
                    calculated.total().amount(), legacyBudget.totalEstimate());
            return;
        }
        BigDecimal legacy = new BigDecimal(matcher.group());
        log.info("budget_dual_calculation legacy={} calculated={} delta={} pending={}",
                legacy, calculated.total().amount(), calculated.total().amount().subtract(legacy),
                calculated.pending().size());
    }

    private long multiplier(PricedItem item, Input input) {
        long quantity = item.quantity();
        return switch (item.price().unit()) {
            case PER_PERSON_TRIP, PER_TICKET, PER_PERSON -> Math.multiplyExact(quantity, input.people());
            case PER_ROOM_NIGHT -> Math.multiplyExact(quantity,
                    Math.multiplyExact(input.rooms(), input.nights()));
            case TOTAL -> quantity;
        };
    }

    public enum Category { TICKET, HOTEL, INTERCITY_TRANSPORT, LOCAL_TRANSPORT, MEAL }

    public record PricedItem(String name, Category category, PriceFact price, long quantity) {
        public PricedItem {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(category, "category");
            if (quantity < 1) throw new IllegalArgumentException("quantity 必须 >= 1");
        }
    }

    public record Input(int people, int rooms, int nights, List<PricedItem> items, List<String> pending) {
        public Input {
            if (people < 1 || rooms < 1 || nights < 0) throw new IllegalArgumentException("人数/房间/晚数非法");
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            pending = pending == null ? List.of() : List.copyOf(pending);
        }
    }

    public record CalculatedItem(String name, Category category, Money amount,
                                 String source, boolean estimated) {}
    public record Breakdown(Category category, Money amount) {}
    public record Result(List<CalculatedItem> items, List<Breakdown> breakdown,
                         Money total, List<String> pending) {}
}
