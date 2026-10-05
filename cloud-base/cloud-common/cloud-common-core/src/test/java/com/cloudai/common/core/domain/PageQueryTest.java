package com.cloudai.common.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaults_arePage1Size10() {
        PageQuery q = new PageQuery();
        assertThat(q.getPageNum()).isEqualTo(1);
        assertThat(q.getPageSize()).isEqualTo(10);
    }
}
