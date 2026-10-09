package com.cloudai.common.rocketmq.tx;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** channel 路由表单测：命中/未注册 null/重复 channel 启动即败 */
class TxExecutorRegistryTest {

    @Test
    void find_registeredChannel_returnsExecutor() {
        StubExecutor executor = new StubExecutor("ch-a");
        TxExecutorRegistry registry = new TxExecutorRegistry(List.of(executor, new StubExecutor("ch-b")));

        assertThat(registry.find("ch-a")).isSameAs(executor);
        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    void find_unknownChannel_returnsNull() {
        TxExecutorRegistry registry = new TxExecutorRegistry(List.of(new StubExecutor("ch-a")));

        assertThat(registry.find("nope")).isNull();
    }

    @Test
    void duplicateChannel_failsFast() {
        assertThatThrownBy(() -> new TxExecutorRegistry(
                List.of(new StubExecutor("ch-a"), new StubExecutor("ch-a"))))
                .isInstanceOf(IllegalStateException.class);
    }

    static class StubExecutor implements TxLocalExecutor<Object> {

        private final String channel;

        StubExecutor(String channel) {
            this.channel = channel;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public Class<Object> payloadType() {
            return Object.class;
        }

        @Override
        public Object executeInTx(Object payload, TxContext ctx) {
            return null;
        }
    }
}
