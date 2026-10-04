package com.cloudai.common.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaults_arePage1Size10() {
        PageQuery q = new PageQuery();
        assertThat(q.getPageNum()).isEqualTo(1);
        assertThat(q.getPageSize()).isEqualTo(10);
        assertThat(q.offset()).isEqualTo(0);
    }

    @Test
    void offset_calculatesFromPageNumAndSize() {
        PageQuery q = new PageQuery();
        q.setPageNum(3);
        q.setPageSize(20);
        assertThat(q.offset()).isEqualTo(40);
    }
}
