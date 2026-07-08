package com.lrj.platform.conversation.extract;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExtractControllerTest {

    @Test
    void extract_ticketType_dispatchesToExtractor() {
        Extractor extractor = mock(Extractor.class);
        Ticket ticket = new Ticket("t", Ticket.Priority.HIGH, "billing", "s", List.of("step"));
        when(extractor.extractTicket("报表导出超时")).thenReturn(ticket);
        ExtractController controller = new ExtractController(extractor);

        Object result = controller.extract("ticket", Map.of("text", "报表导出超时"));

        assertThat(result).isSameAs(ticket);
    }

    @Test
    void extract_defaultsToTicket() {
        Extractor extractor = mock(Extractor.class);
        Ticket ticket = new Ticket("t", Ticket.Priority.LOW, "ui", "s", List.of());
        when(extractor.extractTicket("x")).thenReturn(ticket);
        ExtractController controller = new ExtractController(extractor);

        assertThat(controller.extract("ticket", Map.of("text", "x"))).isSameAs(ticket);
    }

    @Test
    void extract_unknownType_throws400() {
        ExtractController controller = new ExtractController(mock(Extractor.class));

        assertThatThrownBy(() -> controller.extract("invoice", Map.of("text", "x")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("unknown extract type: invoice");
    }

    @Test
    void extract_missingText_passesEmptyString() {
        Extractor extractor = mock(Extractor.class);
        when(extractor.extractTicket("")).thenReturn(
                new Ticket("t", Ticket.Priority.MEDIUM, "c", "s", List.of()));
        ExtractController controller = new ExtractController(extractor);

        assertThat(controller.extract("ticket", Map.of())).isNotNull();
    }
}
