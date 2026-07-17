package com.lrj.platform.workflow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * webhook 载荷签名工具：对请求体做 HmacSHA256（以 webhook secret 为密钥），返回 {@code sha256=<hex>} 形式的签名头，
 * 供接收方校验来源真实性。null secret/body 按空串处理，签名异常时返回空串。纯静态工具类，不可实例化。
 */
final class WorkflowWebhookSigner {

    private WorkflowWebhookSigner() {
    }

    static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal((body == null ? "" : body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
