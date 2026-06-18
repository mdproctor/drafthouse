package io.casehub.drafthouse;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@QuarkusTest
class StoreActivationTest {

    @Inject
    DebateSessionStore store;

    @Test
    void defaultProfile_usesNoOpStore() {
        assertThat(store).isInstanceOf(NoOpDebateSessionStore.class);
    }
}
