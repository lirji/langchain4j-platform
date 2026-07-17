package com.lrj.platform.channel;

/**
 * 单次出站投递的结果：{@code status} 取 {@code ACCEPTED}（如出站开关关闭时的挂起）/{@code SENT}
 * （已送达）/{@code FAILED}（投递失败），{@code detail} 为可读原因。由 {@link ChannelMessageDispatcher}
 * 各实现返回，提供三个静态工厂方法。
 */
public record ChannelDeliveryResult(String status, String detail) {

    public static ChannelDeliveryResult accepted(String detail) {
        return new ChannelDeliveryResult("ACCEPTED", detail);
    }

    public static ChannelDeliveryResult sent(String detail) {
        return new ChannelDeliveryResult("SENT", detail);
    }

    public static ChannelDeliveryResult failed(String detail) {
        return new ChannelDeliveryResult("FAILED", detail);
    }
}
