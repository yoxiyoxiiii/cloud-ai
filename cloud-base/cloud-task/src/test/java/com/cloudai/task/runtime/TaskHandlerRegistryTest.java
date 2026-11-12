package com.cloudai.task.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 注册表 fail-fast 单测（设计 §5.3）：类型码空白/注解缺失或不一致/重复注册启动即炸；
 * 正常注册后 containsType 与 get 可用（提交面 6001 依据）。
 * 注解用 FQN 标注——同名接口在同包，import 注解会遮蔽接口引用。
 */
class TaskHandlerRegistryTest {

    @com.cloudai.task.runtime.annotation.TaskHandler("type-a")
    static class HandlerA implements TaskHandler {
        @Override
        public String taskType() {
            return "type-a";
        }

        @Override
        public String execute(TaskContext ctx) {
            return "a";
        }
    }

    /** 与 HandlerA 同类型码（重复注册用） */
    @com.cloudai.task.runtime.annotation.TaskHandler("type-a")
    static class HandlerADup implements TaskHandler {
        @Override
        public String taskType() {
            return "type-a";
        }

        @Override
        public String execute(TaskContext ctx) {
            return "dup";
        }
    }

    /** 注解值与 taskType() 不一致 */
    @com.cloudai.task.runtime.annotation.TaskHandler("type-b")
    static class HandlerMismatch implements TaskHandler {
        @Override
        public String taskType() {
            return "type-c";
        }

        @Override
        public String execute(TaskContext ctx) {
            return "m";
        }
    }

    /** 无注解 */
    static class HandlerNoAnnotation implements TaskHandler {
        @Override
        public String taskType() {
            return "type-d";
        }

        @Override
        public String execute(TaskContext ctx) {
            return "n";
        }
    }

    private TaskHandlerRegistry registryOf(TaskHandler... handlers) {
        TaskHandlerRegistry registry = new TaskHandlerRegistry(List.of(handlers));
        registry.afterSingletonsInstantiated();
        return registry;
    }

    @Test
    void duplicateType_failsFast() {
        assertThatThrownBy(() -> registryOf(new HandlerA(), new HandlerADup()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复任务类型注册");
    }

    @Test
    void annotationMismatch_failsFast() {
        assertThatThrownBy(() -> registryOf(new HandlerMismatch()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    void missingAnnotation_failsFast() {
        assertThatThrownBy(() -> registryOf(new HandlerNoAnnotation()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("注解缺失");
    }

    @Test
    void registeredType_resolvable() {
        TaskHandlerRegistry registry = registryOf(new HandlerA());
        assertThat(registry.containsType("type-a")).isTrue();
        assertThat(registry.containsType("type-x")).isFalse();
        assertThat(registry.containsType(null)).isFalse();
        assertThat(registry.get("type-a")).isInstanceOf(HandlerA.class);
        assertThatThrownBy(() -> registry.get("type-x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("任务类型未注册");
    }
}
