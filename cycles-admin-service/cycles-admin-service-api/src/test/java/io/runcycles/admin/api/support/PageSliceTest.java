package io.runcycles.admin.api.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageSliceTest {
    @Test
    void exactLimit_isACompleteFinalPage() {
        PageSlice<String> slice = PageSlice.from(List.of("a", "b"), 2);

        assertThat(slice.items()).containsExactly("a", "b");
        assertThat(slice.hasMore()).isFalse();
    }

    @Test
    void lookaheadRow_setsHasMoreAndIsNotExposed() {
        PageSlice<String> slice = PageSlice.from(List.of("a", "b", "c"), 2);

        assertThat(slice.items()).containsExactly("a", "b");
        assertThat(slice.hasMore()).isTrue();
    }

    @Test
    void returnedItems_areAnImmutableSnapshot() {
        List<String> rows = new ArrayList<>(List.of("a", "b"));
        PageSlice<String> slice = PageSlice.from(rows, 1);
        rows.set(0, "changed");

        assertThat(slice.items()).containsExactly("a");
        assertThatThrownBy(() -> slice.items().add("c"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
