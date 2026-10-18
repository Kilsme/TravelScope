package com.travelscope.service.workflow.dto;

import com.travelscope.service.workflow.RequirementFormSpec;

import java.util.List;
import java.util.Objects;

/**
 * 需求采集一轮的产出（S2.2，RequirementWorkflowService.handle 的返回；ChatService 据其接线）。
 *
 * @param status   本轮终态：COLLECTED（收齐/降级放行，进入编排下游）/ NEED_USER_INPUT
 *                 （清单卡片待用户输入，本轮结束）/ CANCELLED（用户取消，状态机复位回普通聊天）
 * @param formSpec 需求清单规格（NEED_USER_INPUT 时承载，渲染卡片用；其余为空表）
 * @param hint     附加提示（可空——增量修改抽取全空时带「没听懂要改哪项，可直接点卡片修改」）
 */
public record CollectOutcome(Status status, List<RequirementFormSpec.FieldSpec> formSpec,
                             String hint) {

    /** 本轮终态 */
    public enum Status {
        /** 收齐（DONE 或 DEGRADED 降级放行），进入编排下游 */
        COLLECTED,
        /** 清单卡片待用户输入，本轮结束 */
        NEED_USER_INPUT,
        /** 用户取消规划，状态机复位回普通聊天 */
        CANCELLED
    }

    public CollectOutcome {
        Objects.requireNonNull(status, "status");
        formSpec = formSpec == null ? List.of() : List.copyOf(formSpec);
    }

    /** 收齐（含降级放行） */
    public static CollectOutcome collected() {
        return new CollectOutcome(Status.COLLECTED, List.of(), null);
    }

    /** 取消（状态机复位） */
    public static CollectOutcome cancelled() {
        return new CollectOutcome(Status.CANCELLED, List.of(), null);
    }

    /** 待用户输入（清单卡片；hint 可空） */
    public static CollectOutcome needUserInput(List<RequirementFormSpec.FieldSpec> formSpec,
                                               String hint) {
        return new CollectOutcome(Status.NEED_USER_INPUT, formSpec, hint);
    }
}
