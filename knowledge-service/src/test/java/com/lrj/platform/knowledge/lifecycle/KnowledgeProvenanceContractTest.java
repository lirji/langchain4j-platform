package com.lrj.platform.knowledge.lifecycle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.platform.protocol.knowledge.KnowledgeHit;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

/** 跨服务加法契约：历史无来源字段仍可读，新定位字段保持稳定字符串，不影响租户身份。 */
class KnowledgeProvenanceContractTest {
    @Test void readsOldResponseAndRoundTripsSourceMetadata() throws Exception {
        var mapper=new ObjectMapper();
        var old=mapper.readValue("{\"id\":\"id\",\"text\":\"body\",\"visibility\":\"tenant\"}",KnowledgeHit.class);
        assertThat(old.sourceMetadata()).isEmpty();
        var next=new KnowledgeHit("id",1d,"doc","manual.pdf",null,"0","body","es","tenant","2",Map.of("source_page","4"));
        var restored=mapper.readValue(mapper.writeValueAsBytes(next),KnowledgeHit.class);
        assertThat(restored).isEqualTo(next);
        assertThat(restored.sourceMetadata()).containsEntry("source_page","4");
    }
}
