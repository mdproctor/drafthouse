package io.casehub.drafthouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BrainstormSessionTest {

    @Test
    void newSession_isActive_withNoOptions() {
        var session = new BrainstormSession("bs-1");
        assertThat(session.sessionId()).isEqualTo("bs-1");
        assertThat(session.state()).isEqualTo(BrainstormSession.State.ACTIVE);
        assertThat(session.options()).isEmpty();
        assertThat(session.lastActivity()).isNotNull();
    }

    @Test
    void addOption_appearsInList() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Description A", "Tradeoffs A"));
        assertThat(session.options()).hasSize(1);
        assertThat(session.options().get(0).id()).isEqualTo("A");
        assertThat(session.options().get(0).status()).isEqualTo(BrainstormOption.Status.LIVE);
    }

    @Test
    void addMultipleOptions() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.addOption(new BrainstormOption("B", "Option B", "Desc B", "Trade B"));
        session.addOption(new BrainstormOption("C", "Option C", "Desc C", "Trade C"));
        assertThat(session.options()).hasSize(3);
    }

    @Test
    void findOption_existingId_returnsOption() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.addOption(new BrainstormOption("B", "Option B", "Desc B", "Trade B"));
        assertThat(session.findOption("B")).isPresent();
        assertThat(session.findOption("B").get().title()).isEqualTo("Option B");
    }

    @Test
    void findOption_unknownId_returnsEmpty() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        assertThat(session.findOption("Z")).isEmpty();
    }

    @Test
    void markSelected_convergesSession() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.addOption(new BrainstormOption("B", "Option B", "Desc B", "Trade B"));

        session.markSelected("B");

        assertThat(session.state()).isEqualTo(BrainstormSession.State.CONVERGED);
        assertThat(session.findOption("B").get().status()).isEqualTo(BrainstormOption.Status.SELECTED);
        assertThat(session.findOption("A").get().status()).isEqualTo(BrainstormOption.Status.LIVE);
    }

    @Test
    void markSelected_unknownOption_throws() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));

        assertThatThrownBy(() -> session.markSelected("Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown option: Z");
    }

    @Test
    void markSelected_onConvergedSession_throws() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.markSelected("A");

        assertThatThrownBy(() -> session.markSelected("A"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addOption_onConvergedSession_throws() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.markSelected("A");

        assertThatThrownBy(() -> session.addOption(new BrainstormOption("B", "B", "B", "B")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void abandon_changesStateToAbandoned() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));

        session.abandon();

        assertThat(session.state()).isEqualTo(BrainstormSession.State.ABANDONED);
    }

    @Test
    void abandon_onConvergedSession_throws() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));
        session.markSelected("A");

        assertThatThrownBy(() -> session.abandon())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void addOption_onAbandonedSession_throws() {
        var session = new BrainstormSession("bs-1");
        session.abandon();

        assertThatThrownBy(() -> session.addOption(new BrainstormOption("A", "A", "A", "A")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void touch_updatesLastActivity() throws InterruptedException {
        var session = new BrainstormSession("bs-1");
        var firstActivity = session.lastActivity();
        Thread.sleep(10);
        session.touch();
        assertThat(session.lastActivity()).isAfter(firstActivity);
    }

    @Test
    void optionsList_isUnmodifiable() {
        var session = new BrainstormSession("bs-1");
        session.addOption(new BrainstormOption("A", "Option A", "Desc A", "Trade A"));

        assertThatThrownBy(() -> session.options().add(new BrainstormOption("X", "X", "X", "X")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void optionStatusTransitions() {
        var option = new BrainstormOption("A", "Option A", "Desc A", "Trade A");
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.LIVE);

        option.setStatus(BrainstormOption.Status.RECOMMENDED);
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.RECOMMENDED);

        option.setStatus(BrainstormOption.Status.EXPLORED);
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.EXPLORED);

        option.setStatus(BrainstormOption.Status.ELIMINATED);
        assertThat(option.status()).isEqualTo(BrainstormOption.Status.ELIMINATED);
    }

    @Test
    void optionMutation() {
        var option = new BrainstormOption("A", "Original", "Desc", "Tradeoffs");
        option.setTitle("Updated");
        option.setDescription("New desc");
        option.setTradeoffs("New tradeoffs");

        assertThat(option.title()).isEqualTo("Updated");
        assertThat(option.description()).isEqualTo("New desc");
        assertThat(option.tradeoffs()).isEqualTo("New tradeoffs");
    }
}
