package vip.mate.agent.context;

import java.util.HashMap;
import java.util.Map;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import vip.mate.agent.graph.state.ActionExecutionLedger;

/** 分别保留模型协议调用名和实际执行工具名，分类规则只使用真实完成事件提供的身份。 */
public final class ToolResponseIdentity {
    /** 消息内部的实际工具身份，不进入模型工具参数或返回正文。 */
    private static final String EXECUTED_NAMES = "mateclaw.executedToolNames";

    /** 禁止实例化工具身份装配器。 */
    private ToolResponseIdentity() { }

    /**
     * 在响应创建时绑定真实执行身份，桥接调用的协议名保持原值。
     * @param message 当前批次的协议响应
     * @param ledger 从本批完成事件构建的真实执行台账
     * @return 带内部身份元数据的响应，不推断尚未执行调用的身份或成功状态
     */
    public static ToolResponseMessage attach(ToolResponseMessage message, ActionExecutionLedger ledger) {
        var names = new HashMap<String, String>();
        for (var response : message.getResponses()) {
            var receipt = ledger.receipts().get(response.id());
            if (receipt != null) names.put(response.id(), receipt.toolName());
        }
        var metadata = new HashMap<>(message.getMetadata());
        metadata.put(EXECUTED_NAMES, Map.copyOf(names));
        return ToolResponseMessage.builder().responses(message.getResponses()).metadata(metadata).build();
    }

    /**
     * 查询真实工具身份，未经真实完成事件绑定的响应沿用协议名。
     * @param message 保存调用身份的消息
     * @param response 当前工具回应
     * @return 实际执行名；不会改写模型协议中的响应名
     */
    public static String name(ToolResponseMessage message, ToolResponseMessage.ToolResponse response) {
        Object names = message.getMetadata().get(EXECUTED_NAMES);
        if (names instanceof Map<?, ?> map && map.get(response.id()) instanceof String actual) return actual;
        return response.name();
    }
}
