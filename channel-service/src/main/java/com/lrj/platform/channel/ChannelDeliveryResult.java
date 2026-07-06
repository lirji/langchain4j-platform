package com.lrj.platform.channel;

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
