package com.lrj.platform.agent.voting;

import com.lrj.platform.protocol.agent.VoteRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VotingControllerTest {

    @Test
    void zeroCandidatesReturns400WithoutInvokingVotingService() {
        VotingService service = mock(VotingService.class);
        when(service.isCandidateCountAllowed(0)).thenReturn(false);
        when(service.maxCandidates()).thenReturn(10);
        VotingController controller = new VotingController(service);

        var response = controller.vote(new VoteRequest("是否合规", 0));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(java.util.Map.of("error", "n must be between 1 and 10"));
        verify(service, never()).vote(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }
}
