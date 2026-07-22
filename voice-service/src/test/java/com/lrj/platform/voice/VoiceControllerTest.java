package com.lrj.platform.voice;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class VoiceControllerTest {

    @Test
    void streamRejectsEmptyAudioWith400BeforeStartingSse() throws Exception {
        VoiceConversationService voice = mock(VoiceConversationService.class);
        VoiceStreamService stream = mock(VoiceStreamService.class);
        VoiceProperties properties = new VoiceProperties();
        VoiceController controller = new VoiceController(voice, stream, properties);
        MockMultipartFile empty = new MockMultipartFile("audio", "empty.wav", "audio/wav", new byte[0]);

        standaloneSetup(controller).build()
                .perform(multipart("/voice/chat/stream").file(empty))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(voice, stream);
    }
}
