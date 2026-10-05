package com.cloudai.common.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void of_carriesTotalAndRows() {
        PageResult<String> r = PageResult.of(2, List.of("a", "b"));
        assertThat(r.getTotal()).isEqualTo(2);
        assertThat(r.getRows()).containsExactly("a", "b");
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_whenRowsNull() {
        PageResult<String> r = PageResult.of(0, null);
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_whenRowsEmpty() {
        PageResult<String> r = PageResult.of(0, List.of());
        assertThat(r.isEmpty()).isTrue();
    }
}
