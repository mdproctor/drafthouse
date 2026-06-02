package io.casehub.drafthouse.debate;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
interface SpecReviewerAiService {

    @SystemMessage(fromResource = "prompts/spec-reviewer.txt")
    @UserMessage("Spec:\n{spec}\n\nDebate so far:\n{debate}\n\nOpen points:\n{openPoints}\n\nRound: {round}")
    String review(@V("spec") String spec, @V("debate") String debate,
                  @V("openPoints") String openPoints, @V("round") int round);
}
